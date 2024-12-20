package dev.voqal.provider.clients.openai

import com.aallam.openai.api.chat.*
import com.aallam.openai.api.model.ModelId
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.ThreadingAssertions
import dev.voqal.assistant.VoqalDirective
import dev.voqal.assistant.focus.SpokenTranscript
import dev.voqal.config.settings.PromptSettings.FunctionCalling
import dev.voqal.config.settings.VoiceDetectionSettings.VoiceDetectionProvider
import dev.voqal.services.*
import dev.voqal.utils.SharedAudioCapture
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.utils.io.CancellationException
import io.ktor.websocket.*
import io.pebbletemplates.pebble.error.PebbleException
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.io.InterruptedIOException
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

class RealtimeSession(
    private val project: Project,
    val wssProviderUrl: String,
    val wssHeaders: Map<String, String> = emptyMap(),
    private val azureHost: Boolean = false
) : Disposable {

    companion object {
        fun calculateTtsCost(durationInSeconds: Double): Double {
            //24¢ per minutes
            val rate = 0.24 / 60
            return durationInSeconds * rate
        }

        private fun calculateSttCost(durationInSeconds: Double): Double {
            //6¢ per minutes
            val rate = 0.06 / 60
            return durationInSeconds * rate
        }

        private fun calculateLlmCost(inputTokens: Int, outputTokens: Int): Double {
            //$5.00 /1M input tokens
            //$20.00 /1M output tokens
            val inputRate = 5.0 / 1_000_000
            val outputRate = 20.0 / 1_000_000
            return inputTokens * inputRate + outputTokens * outputRate
        }
    }

    private val log = project.getVoqalLogger(this::class)
    private var capturing = false
    private lateinit var session: DefaultClientWebSocketSession
    private lateinit var readThread: Thread
    private lateinit var writeThread: Thread
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val jsonEncoder = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        install(WebSockets)
    }
    private val activeSession = JsonObject()
    private var disposed: Boolean = false
    private val responseQueue = LinkedBlockingQueue<Promise<Any>>()
    private val deltaQueue = LinkedBlockingQueue<Promise<FlowCollector<String>>>()
    private val deltaMap = mutableMapOf<String, Any>()
    private val realtimeAudioMap = mutableMapOf<String, RealtimeAudio>()
    private val realtimeToolMap = mutableMapOf<String, RealtimeTool>()
    private var serverVad = false
    private var speechStartTime = -1L
    private var speechEndTime = -1L
    private var currentInputTokens = 0

    init {
        val config = project.service<VoqalConfigService>().getConfig()
        if (config.voiceDetectionSettings.provider == VoiceDetectionProvider.NONE) {
            serverVad = true
        }

        restartConnection()
    }

    private fun restartConnection(): Boolean {
        ThreadingAssertions.assertBackgroundThread()
        log.debug("Establishing new Realtime API session")
        if (::readThread.isInitialized) {
            readThread.interrupt()
            readThread.join()
        }
        if (::writeThread.isInitialized) {
            writeThread.interrupt()
            writeThread.join()
        }
        audioQueue.clear()
        responseQueue.clear()
        currentInputTokens = 0

        try {
            session = runBlocking {
                //establish connection, 3 attempts
                var session: DefaultClientWebSocketSession? = null
                for (i in 0..2) {
                    try {
                        withTimeout(10_000) {
                            session = client.webSocketSession(wssProviderUrl) {
                                wssHeaders.forEach { header(it.key, it.value) }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        if (i == 2) {
                            throw e
                        } else {
                            log.warn("Failed to connect to Realtime API. Retrying...")
                        }
                    }
                    if (session != null) break
                }
                session!!
            }
            log.debug("Connected to Realtime API")
            readThread = Thread(readLoop(), "RealtimeSession-Read").apply { start() }
            writeThread = Thread(writeLoop(), "RealtimeSession-Write").apply { start() }

            project.scope.launch {
                while (!disposed) {
                    try {
                        updateSession()
                    } catch (e: PebbleException) {
                        log.warn("Failed to update session: ${e.message}")
                    } catch (e: Throwable) {
                        log.error("Failed to update session", e)
                    }
                    delay(500)
                }
            }
        } catch (e: Exception) {
            val warnMessage = if (e.message != null) {
                "Realtime API connection failed: ${e.message}"
            } else {
                "Failed to connect to Realtime API"
            }
            log.warnChat(warnMessage)
            return false
        }
        return true
    }

    private fun updateSession() {
        val configService = project.service<VoqalConfigService>()
        val promptName = configService.getActivePromptName()
        if (configService.getConfig().promptLibrarySettings.prompts.none { it.promptName == promptName }) {
            log.warn("Prompt $promptName not found in prompt library")
            return
        }

        val toolService = project.service<VoqalToolService>()
        var nopDirective = project.service<VoqalDirectiveService>().createDirective(
            transcription = SpokenTranscript("n/a", null),
            promptName = promptName,
            usingAudioModality = true
        )
        nopDirective = nopDirective.copy(
            contextMap = nopDirective.contextMap.toMutableMap().apply {
                put(
                    "assistant", nopDirective.assistant.copy(
                        availableActions = toolService.getAvailableTools().values,
                        promptSettings = nopDirective.assistant.promptSettings?.copy(
                            functionCalling = FunctionCalling.NATIVE
                        )
                    )
                )
            }
        )
        val prompt = nopDirective.toMarkdown()
        val tools = nopDirective.assistant.availableActions
            .filter { it.isVisible(nopDirective) }
            .filter {
                //todo: dynamic
                if (nopDirective.assistant.promptSettings!!.promptName.lowercase() == "edit mode") {
                    it.name in setOf("edit_text", "looks_good", "cancel")
                } else {
                    it.name != "answer_question"
                }
            }

        val newSession = JsonObject().apply {
            put("modalities", JsonArray().add("text").add("audio"))
            put("instructions", prompt)
            put("input_audio_transcription", JsonObject().apply {
                put("model", "whisper-1")
            })
            put("tools", JsonArray(tools.map {
                it.asTool(nopDirective).function
            }.map {
                JsonObject(jsonEncoder.encodeToJsonElement(it).toString())
                    .put("type", "function")
            }))
            if (!serverVad) {
                if (azureHost) {
                    put("turn_detection", JsonObject().apply {
                        put("type", "none")
                    })
                } else {
                    put("turn_detection", null)
                }
            }
        }
        if (newSession.toString() == activeSession.toString()) return

        log.trace { "Updating realtime session prompt" }
        activeSession.mergeIn(newSession)
        val json = JsonObject().apply {
            put("type", "session.update")
            put("session", activeSession)
        }
        runBlocking {
            session.send(Frame.Text(json.toString()))
        }
    }

    private fun readLoop(): Runnable {
        return Runnable {
            try {
                while (!disposed) {
                    val frame = runBlocking { session.incoming.receive() }
                    when (frame) {
                        is Frame.Text -> {
                            val json = JsonObject(frame.readText())
                            if (!json.getString("type").endsWith(".delta")) {
                                if (json.getString("type") == "error") {
                                    log.warn { "Realtime error: $json" }
                                } else {
                                    log.trace { "Realtime event: $json" }
                                }
                            }

                            if (json.getString("type") == "error") {
                                val errorMessage = json.getJsonObject("error").getString("message")
                                if (errorMessage != "Response parsing interrupted") {
                                    log.warnChat(errorMessage)
                                }
                            } else if (json.getString("type") == "response.function_call_arguments.delta") {
                                if (speechEndTime != -1L) {
                                    project.service<VoqalConfigService>().getAiProvider().asObservabilityProvider()
                                        .logStmLatency(System.currentTimeMillis() - speechEndTime)
                                    speechEndTime = -1L
                                }

//                                val respId = json.getString("response_id")
//                                val deltaValue = deltaMap.get(respId)
//                                if (deltaValue is FlowCollector<*>) {
//                                    @Suppress("UNCHECKED_CAST") val flowCollector = deltaValue as FlowCollector<String>
//                                    channel.trySend(GlobalScope.launch(start = CoroutineStart.LAZY) {
//                                        flowCollector.emit(json.getString("delta"))
//                                    })
//                                } else if (deltaValue == null) {
//                                    //first delta
//                                    val promise = deltaQueue.take()
//                                    deltaMap[respId] = promise
//                                }
                            } else if (json.getString("type") == "response.function_call_arguments.done") {
                                val convoId = json.getString("item_id")
                                val realtimeTool = realtimeToolMap.getOrPut(convoId) {
                                    RealtimeTool(project, session, convoId)
                                }
                                realtimeTool.executeTool(json)

                                val tokens = project.service<VoqalContextService>().getTokenCount(json.toString())
                                project.service<VoqalConfigService>().getAiProvider().asObservabilityProvider()
                                    .logStmCost(calculateLlmCost(currentInputTokens, tokens))

                                //just guessing here, but I believe the input tokens is cumulative of the output tokens
                                currentInputTokens += tokens
                            } else if (json.getString("type") == "response.audio.delta") {
                                if (speechEndTime != -1L) {
                                    project.service<VoqalConfigService>().getAiProvider().asObservabilityProvider()
                                        .logStmLatency(System.currentTimeMillis() - speechEndTime)
                                    speechEndTime = -1L
                                }

                                val convoId = json.getString("item_id")
                                val realtimeAudio = realtimeAudioMap.getOrPut(convoId) {
                                    RealtimeAudio(project, convoId)
                                }
                                realtimeAudio.addAudioData(json)
                            } else if (json.getString("type") == "response.audio.done") {
                                val convoId = json.getString("item_id")
                                val realtimeAudio = realtimeAudioMap[convoId]!!
                                realtimeAudio.finishAudio()
                            } else if (json.getString("type") == "input_audio_buffer.speech_started") {
                                log.info("Realtime speech started")
                                stopCurrentVoice()
                                speechStartTime = System.currentTimeMillis()
                            } else if (json.getString("type") == "input_audio_buffer.speech_stopped") {
                                log.info("Realtime speech stopped")
                                speechEndTime = System.currentTimeMillis()

                                project.service<VoqalConfigService>().getAiProvider().asObservabilityProvider()
                                    .logStmCost(calculateSttCost((speechEndTime - speechStartTime) / 1000.0))
                            } else if (json.getString("type") == "conversation.item.input_audio_transcription.completed") {
                                var transcript = json.getString("transcript")
                                if (transcript.endsWith("\n")) {
                                    transcript = transcript.substring(0, transcript.length - 1)
                                }

                                log.info("User transcript: $transcript")
                                val chatContentManager = project.service<ChatToolWindowContentManager>()
                                chatContentManager.addUserMessage(transcript)
                            } else if (json.getString("type") == "response.audio_transcript.done") {
                                val transcript = json.getString("transcript")
                                log.info("Assistant transcript: $transcript")
                                val chatContentManager = project.service<ChatToolWindowContentManager>()
                                chatContentManager.addAssistantMessage(transcript)
                            } else if (json.getString("type") == "response.text.done") {
                                val text = json.getString("text")
                                log.info("Assistant text: $text")

                                val tokens = project.service<VoqalContextService>().getTokenCount(json.toString())
                                project.service<VoqalConfigService>().getAiProvider().asObservabilityProvider()
                                    .logStmCost(calculateLlmCost(currentInputTokens, tokens))

                                //just guessing here, but I believe the input tokens is cumulative of the output tokens
                                currentInputTokens += tokens

                                responseQueue.take().complete(JsonObject().apply {
                                    put("tool", "answer_question")
                                    put("parameters", JsonObject().apply {
                                        put("answer", text)
                                        //put("audioModality", true)
                                    })
                                }.toString())
                            }
                        }

                        is Frame.Close -> {
                            log.info("Connection closed")
                            break
                        }

                        else -> log.warn("Unexpected frame: $frame")
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                //todo: Deepgram closes socket to indicate end of transcription
                log.debug("Connection closed")
            } catch (_: InterruptedException) {
            } catch (_: InterruptedIOException) {
            } catch (e: Exception) {
                log.error("Error processing audio: ${e.message}", e)
            } finally {
                if (!disposed) {
                    project.scope.launch {
                        restartConnection()
                    }
                }
            }
        }
    }

    private fun stopCurrentVoice() {
        realtimeAudioMap.values.forEach { it.stopAudio() }
    }

    private fun writeLoop(): Runnable {
        return Runnable {
            try {
                while (!disposed) {
                    val buffer = try {
                        audioQueue.take()
                    } catch (_: InterruptedException) {
                        break
                    }

                    if (buffer === SharedAudioCapture.EMPTY_BUFFER) {
                        log.debug("No speech detected, flushing stream")
                        runBlocking {
                            session.send(Frame.Text(JsonObject().apply {
                                put("type", "input_audio_buffer.commit")
                            }.toString()))
                            session.send(Frame.Text(JsonObject().apply {
                                put("type", "response.create")
                            }.toString()))
                        }
                    } else {
                        runBlocking {
                            val json = JsonObject().apply {
                                put("type", "input_audio_buffer.append")
                                put("audio", Base64.getEncoder().encodeToString(buffer))
                            }
                            session.send(Frame.Text(json.toString()))
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                log.error("Error processing audio: ${e.message}", e)
            }
        }
    }

    fun onAudioData(data: ByteArray, detection: SharedAudioCapture.AudioDetection) {
        if (serverVad) {
            audioQueue.put(data)
            return
        }

        if (detection.speechDetected.get()) {
            speechStartTime = System.currentTimeMillis()
            stopCurrentVoice()
            //todo: response.cancel

            capturing = true
            detection.framesBeforeVoiceDetected.forEach {
                audioQueue.put(it.data)
            }
            audioQueue.put(data)
        } else if (capturing && !detection.speechDetected.get()) {
            speechEndTime = System.currentTimeMillis()
            capturing = false
            audioQueue.put(SharedAudioCapture.EMPTY_BUFFER)

            project.service<VoqalConfigService>().getAiProvider().asObservabilityProvider()
                .logStmCost(calculateSttCost((speechEndTime - speechStartTime) / 1000.0))
        }
    }

    suspend fun chatCompletion(request: ChatCompletionRequest, directive: VoqalDirective?): ChatCompletion {
        val eventId = sendTextMessage(directive!!)

        val promise = Promise.promise<Any>()
        responseQueue.add(promise)

        session.send(Frame.Text(JsonObject().apply {
            put("event_id", "$eventId.response") //todo: doesn't correlate with response
            put("type", "response.create")
        }.toString()))

        //todo: realtime can choose to merge reqs (i.e. hi 3 times quickly = 1 response)
        val responseJson = promise.future().coAwait()
        return ChatCompletion(
            id = "n/a",
            created = System.currentTimeMillis(),
            model = ModelId("n/a"),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    ChatMessage(
                        ChatRole.Assistant,
                        TextContent(
                            content = responseJson as String
                        )
                    )
                )
            )
        )
    }

    suspend fun streamChatCompletion(
        request: ChatCompletionRequest,
        directive: VoqalDirective?
    ): Flow<ChatCompletionChunk> {
        val eventId = sendTextMessage(directive!!)

        val promise = Promise.promise<FlowCollector<String>>()
        deltaQueue.add(promise)

        session.send(Frame.Text(JsonObject().apply {
            put("event_id", "$eventId.response") //todo: doesn't correlate with response
            put("type", "response.create")
        }.toString()))

        TODO()

//        val responseFlow = promise.future().coAwait()
//        return object : Flow<ChatCompletionChunk> {
//            override suspend fun collect(collector: FlowCollector<ChatCompletionChunk>) {
//                responseFlow.collect {
//                    collector.emit(ChatCompletionChunk(it))
//                }
//            }
//        }
    }

    private suspend fun sendTextMessage(directive: VoqalDirective): String {
        val eventId = "voqal." + UUID.randomUUID().toString()
        val json = JsonObject().apply {
            put("event_id", "$eventId.conversation.item")
            put("type", "conversation.item.create")
            put("item", JsonObject().apply {
                put("type", "message")
                put("status", "completed")
                put("role", "user")
                put("content", JsonArray().add(JsonObject().apply {
                    put("type", "input_text")
                    put("text", directive.transcription)
                }))
            })
        }
        session.send(Frame.Text(json.toString()))

        return eventId
    }

    fun sampleRate() = 24000f

    override fun dispose() {
        disposed = true
        if (::session.isInitialized) {
            runBlocking { session.close(CloseReason(CloseReason.Codes.NORMAL, "Disposed")) }
        }
        if (::writeThread.isInitialized) writeThread.interrupt()
    }
}
