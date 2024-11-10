package dev.voqal.provider.clients.togetherai

import com.aallam.openai.api.chat.*
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.exception.*
import com.aallam.openai.api.model.ModelId
import dev.voqal.core.Project
import dev.voqal.assistant.VoqalDirective
import dev.voqal.provider.LlmProvider
import dev.voqal.services.VoqalContextService
import dev.voqal.services.getVoqalLogger
import dev.voqal.services.service
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
import kotlinx.serialization.json.Json

class TogetherAiClient(
    override val name: String,
    private val project: Project,
    private val providerKey: String
) : LlmProvider {

    companion object {
        const val DEFAULT_MODEL = "meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo"

        @JvmStatic
        fun getTokenLimit(modelName: String): Int {
            return when {
                modelName.startsWith("meta-llama/Meta-Llama-3.1") -> 128_000
                modelName == "meta-llama/Llama-3-8b-chat-hf" -> 8192
                modelName == "meta-llama/Llama-3-70b-chat-hf" -> 8192
                else -> -1
            }
        }

        @JvmStatic
        val MODELS = listOf(
            "meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo",
            "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo",
            "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo"
        )
    }

    private val log = project.getVoqalLogger(this::class)
    private val jsonDecoder = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(ContentNegotiation) { json(jsonDecoder) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    private val providerUrl = "https://api.together.xyz/v1/completions" //todo: /chat/completions?

    override suspend fun chatCompletion(request: ChatCompletionRequest, directive: VoqalDirective?): ChatCompletion {
        val requestJson = toRequestJson(request)

        val response = try {
            client.post(providerUrl) {
                header("Accept", "application/json")
                header("Content-Type", "application/json")
                header("Authorization", "Bearer $providerKey")
                setBody(requestJson.toString())
            }
        } catch (e: HttpRequestTimeoutException) {
            throw OpenAITimeoutException(e)
        }
        val roundTripTime = response.responseTime.timestamp - response.requestTime.timestamp
        log.debug("Together AI response status: ${response.status} in $roundTripTime ms")

        throwIfError(response)
        val body = JsonObject(response.bodyAsText())
        log.info("Completion: $body")
        val completion = ChatCompletion(
            id = body.getString("id"),
            created = body.getLong("created"),
            model = ModelId(body.getString("model")),
            choices = body.getJsonArray("choices").mapIndexed { index, it ->
                val choiceJson = JsonObject.mapFrom(it)
                ChatChoice(
                    index = index,
                    ChatMessage(
                        ChatRole.Assistant,
                        TextContent(choiceJson.getString("text"))
                    )
                )
            },
            usage = Usage(
                body.getJsonObject("usage").getInteger("prompt_tokens"),
                body.getJsonObject("usage").getInteger("completion_tokens"),
                body.getJsonObject("usage").getInteger("total_tokens")
            )
        )
        return completion
    }

    override suspend fun streamChatCompletion(
        request: ChatCompletionRequest,
        directive: VoqalDirective?
    ): Flow<ChatCompletionChunk> = flow {
        val requestJson = toRequestJson(request).put("stream", true)

        try {
            client.preparePost(providerUrl) {
                header("Accept", "application/json")
                header("Content-Type", "application/json")
                header("Authorization", "Bearer $providerKey")
                setBody(requestJson.encode())
            }.execute { response ->
                throwIfError(response)

                var hasError = false
                val channel: ByteReadChannel = response.body()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line()?.takeUnless { it.isEmpty() } ?: continue

                    if (line == "event: error") {
                        hasError = true
                        continue
                    } else if (hasError) {
                        val errorJson = JsonObject(line.substringAfter("data: "))
                        log.warn("Received error while streaming completions: $errorJson")

                        val statusCode = errorJson.getJsonObject("error").getInteger("status_code")
                        throw UnknownAPIException(statusCode, jsonDecoder.decodeFromString(errorJson.toString()))
                    }

                    val chunkJson = line.substringAfter("data: ")
                    if (chunkJson != "[DONE]") {
                        emit(jsonDecoder.decodeFromString(chunkJson))
                    }
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            throw OpenAITimeoutException(e)
        }
    }

    private fun toRequestJson(request: ChatCompletionRequest): JsonObject {
        val messages = JsonArray(request.messages.map { it.toJson() })
        val messagesTokenCount = project.service<VoqalContextService>().getTokenCount(messages.toString())
        val requestJson = JsonObject()
            .put("model", request.model.id) //todo: temp and stuff
            .put("messages", messages)
            .put("max_tokens", 4096 - messagesTokenCount) //todo: shouldn't need to hardcode 4096
        //.put("stop", JsonArray().add("<|eot_id|>"))
        return requestJson
    }

    private suspend fun throwIfError(response: HttpResponse) {
        if (response.status.isSuccess()) return

        val body = JsonObject(response.bodyAsText())
        if (response.status.value == 401) {
            throw AuthenticationException(
                response.status.value,
                OpenAIError(
                    OpenAIErrorDetails(
                        code = null,
                        message = body.getString("error"),
                        param = null,
                        type = null
                    )
                ),
                IllegalStateException(body.toString())
            )
        } else if (response.status.value == 429) {
            throw RateLimitException(
                response.status.value,
                OpenAIError(
                    OpenAIErrorDetails(
                        code = null,
                        message = body.getString("error"),
                        param = null,
                        type = null
                    )
                ),
                IllegalStateException(body.toString())
            )
        }

        throw UnknownAPIException(
            response.status.value,
            OpenAIError(
                OpenAIErrorDetails(
                    code = null,
                    message = body.getString("error", "Unknown error"),
                    param = null,
                    type = null
                )
            ),
            IllegalStateException(body.toString())
        )
    }

    override fun isStreamable() = true
    override fun getAvailableModelNames() = MODELS
    override fun dispose() = client.close()
    private fun ChatMessage.toJson() = JsonObject().put("role", role.role.lowercase()).put("content", content)
}
