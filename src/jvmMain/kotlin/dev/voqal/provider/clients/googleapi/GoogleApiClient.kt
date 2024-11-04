package dev.voqal.provider.clients.googleapi

import com.aallam.openai.api.chat.*
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.exception.*
import com.aallam.openai.api.model.ModelId
import com.intellij.openapi.project.Project
import dev.voqal.assistant.VoqalDirective
import dev.voqal.assistant.memory.local.asDirectiveTool
import dev.voqal.config.settings.PromptSettings.FunctionCalling
import dev.voqal.provider.LlmProvider
import dev.voqal.provider.StmProvider
import dev.voqal.provider.clients.picovoice.NativesExtractor
import dev.voqal.services.getVoqalLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*

class GoogleApiClient(
    override val name: String,
    project: Project,
    private val providerKey: String
) : LlmProvider, StmProvider {

    companion object {
        const val DEFAULT_MODEL = "gemini-1.5-flash-latest"

        @JvmStatic
        fun getTokenLimit(modelName: String): Int {
            return when {
                modelName.startsWith("gemini-1.5-flash") -> 1048576
                modelName.startsWith("gemini-1.5-pro") -> 2097152
                else -> -1
            }
        }

        @JvmStatic
        val MODELS = listOf(
            "gemini-1.5-flash-latest",
            "gemini-1.5-pro-latest",
            "gemini-1.5-flash-002",
            "gemini-1.5-pro-002",
            "gemini-1.5-flash-001",
            "gemini-1.5-pro-001",
            "gemini-1.5-flash-8b",
        )
    }

    private val log = project.getVoqalLogger(this::class)
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    private val providerUrl = "https://generativelanguage.googleapis.com"

    override suspend fun chatCompletion(request: ChatCompletionRequest, directive: VoqalDirective?): ChatCompletion {
        val modelName = request.model.id
        val requestJson = toRequestJson(request, directive)

        val response = try {
            client.post("$providerUrl/v1beta/models/$modelName:generateContent?key=$providerKey") {
                header("Content-Type", "application/json")
                header("Accept", "application/json")
                setBody(requestJson.encode())
            }
        } catch (e: HttpRequestTimeoutException) {
            throw OpenAITimeoutException(e)
        }
        val roundTripTime = response.responseTime.timestamp - response.requestTime.timestamp
        log.debug("Google API response status: ${response.status} in $roundTripTime ms")

        throwIfError(response)
        val body = JsonObject(response.bodyAsText())
        log.debug("Completion: $body")
        val completion = ChatCompletion(
            id = UUID.randomUUID().toString(),
            created = System.currentTimeMillis(),
            model = ModelId(request.model.id),
            choices = toChatChoices(body.getJsonArray("candidates")),
            usage = toUsage(body.getJsonObject("usageMetadata"))
        )
        return completion
    }

    override suspend fun streamChatCompletion(
        request: ChatCompletionRequest,
        directive: VoqalDirective?
    ): Flow<ChatCompletionChunk> = flow {
        val modelName = request.model.id
        val requestJson = toRequestJson(request, directive)

        try {
            client.preparePost("$providerUrl/v1beta/models/$modelName:streamGenerateContent?key=$providerKey") {
                header("Content-Type", "application/json")
                header("Accept", "application/json")
                setBody(requestJson.encode())
            }.execute { response ->
                throwIfError(response)

                val fullResponse = StringBuilder()
                val channel: ByteReadChannel = response.body()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line()?.takeUnless { it.isEmpty() } ?: continue
                    fullResponse.append(line)

                    try {
                        val resultArray = JsonArray("$fullResponse}]")
                        if (!resultArray.isEmpty) {
                            val lastResult = resultArray.getJsonObject(resultArray.size() - 1)
                            val choices = toChatChoices(lastResult.getJsonArray("candidates"))

                            emit(
                                ChatCompletionChunk(
                                    id = UUID.randomUUID().toString(),
                                    created = 0, //todo: System.currentTimeMillis(),
                                    model = ModelId(request.model.id),
                                    choices = choices.mapIndexed { index, it ->
                                        ChatChunk(
                                            index = index,
                                            delta = ChatDelta(
                                                role = choices[0].message.role,
                                                content = it.message.content
                                            )
                                        )
                                    },
                                    usage = toUsage(lastResult.getJsonObject("usageMetadata"))
                                )
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            throw OpenAITimeoutException(e)
        }
    }

    private fun toRequestJson(request: ChatCompletionRequest, directive: VoqalDirective?): JsonObject {
        val requestJson = JsonObject().put("contents", JsonArray(request.messages.map { it.toJson() }))

        if (directive?.assistant?.promptSettings?.functionCalling == FunctionCalling.NATIVE) {
            requestJson.put("tools", JsonArray().apply {
                val toolsArray = JsonArray(request.tools?.map { it.asDirectiveTool() }
                    ?.map { JsonObject(Json.encodeToString(it)) })
                val functionDeclarations = toolsArray.map {
                    val jsonObject = it as JsonObject
                    jsonObject.remove("type")
                    jsonObject.getJsonObject("function")
                }
                add(JsonObject().put("function_declarations", JsonArray(functionDeclarations)))
            })
            requestJson.put("tool_config", JsonObject().put("function_calling_config", JsonObject().put("mode", "ANY")))
        }

        if (directive?.assistant?.speechId != null && directive.assistant.usingAudioModality) {
            log.debug("Using audio modality")
            val speechId = directive.assistant.speechId
            val speechDirectory = File(NativesExtractor.workingDirectory, "speech")
            speechDirectory.mkdirs()
            val speechFile = File(speechDirectory, "developer-$speechId.wav")
            val audio1Bytes = speechFile.readBytes()

            //add audio bytes to last contents/parts
            val lastContent = requestJson.getJsonArray("contents")
                .getJsonObject(requestJson.getJsonArray("contents").size() - 1)
            val parts = lastContent.getJsonArray("parts")
            parts.add(JsonObject().put("inline_data", JsonObject().apply {
                put("mime_type", "audio/wav")
                put("data", Base64.getEncoder().encodeToString(audio1Bytes))
            }))
            lastContent.put("parts", parts)
        }

        return requestJson
    }

    private suspend fun throwIfError(response: HttpResponse) {
        if (response.status.isSuccess()) return

        if (response.status.value == 401) {
            throw AuthenticationException(
                response.status.value,
                OpenAIError(
                    OpenAIErrorDetails(
                        code = null,
                        message = "Unauthorized access to Google API. Please check your API key and try again.",
                        param = null,
                        type = null
                    )
                ),
                ClientRequestException(response, response.bodyAsText())
            )
        } else if (response.status.value == 429) {
            var errorMessage = "Rate limit exceeded. Please try again later."
            try {
                val jsonBody = JsonObject(response.bodyAsText())
                if (jsonBody.containsKey("error")) {
                    errorMessage = jsonBody.getJsonObject("error").getString("message")
                }
            } catch (_: Exception) {
            }
            throw RateLimitException(
                response.status.value,
                OpenAIError(
                    OpenAIErrorDetails(
                        code = null,
                        message = errorMessage,
                        param = null,
                        type = null
                    )
                ),
                ClientRequestException(response, response.bodyAsText())
            )
        }

        throw InvalidRequestException(
            response.status.value,
            OpenAIError(
                OpenAIErrorDetails(
                    code = null,
                    message = "Invalid request to Google API. Please check your configuration and try again.",
                    param = null,
                    type = null
                )
            ),
            ClientRequestException(response, response.bodyAsText())
        )
    }

    private fun toChatChoices(json: JsonArray): List<ChatChoice> {
        return json.mapIndexed { index, jsonElement ->
            val jsonObject = jsonElement as JsonObject
            val content = jsonObject.getJsonObject("content")
            val parts = content.getJsonArray("parts")
            val text = parts.getJsonObject(0).getString("text") //todo: other parts?
            ChatChoice(
                index = index,
                ChatMessage(
                    if (content.getString("role") == "model") Role.Assistant else Role.User,
                    TextContent(text)
                )
            )
        }
    }

    private fun toUsage(json: JsonObject): Usage {
        return Usage(
            promptTokens = json.getInteger("promptTokenCount"),
            completionTokens = json.getInteger("candidatesTokenCount"),
            totalTokens = json.getInteger("totalTokenCount")
        )
    }

    override fun isStreamable() = true
    override fun getAvailableModelNames() = MODELS
    override fun dispose() = Unit

    private fun ChatMessage.toJson(): JsonObject {
        return JsonObject().apply {
            put("role", if (role == Role.Assistant) "model" else "user")
            put("parts", JsonArray().apply {
                add(JsonObject().put("text", content))
            })
        }
    }
}
