package dev.voqal.provider.clients.openai

import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.assistant.Assistant
import com.aallam.openai.api.assistant.AssistantId
import com.aallam.openai.api.assistant.AssistantRequest
import com.aallam.openai.api.audio.SpeechRequest
import com.aallam.openai.api.audio.TranscriptionRequest
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.core.SortOrder
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.message.Message
import com.aallam.openai.api.message.MessageId
import com.aallam.openai.api.message.MessageRequest
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.api.run.*
import com.aallam.openai.api.thread.Thread
import com.aallam.openai.api.thread.ThreadId
import com.aallam.openai.api.thread.ThreadRequest
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import dev.voqal.core.Project
import dev.voqal.assistant.VoqalDirective
import dev.voqal.core.Disposer
import dev.voqal.provider.*
import dev.voqal.services.*
import dev.voqal.utils.Iso639Language
import dev.voqal.utils.SharedAudioCapture
import io.ktor.utils.io.*
import io.vertx.core.Future
import io.vertx.core.Promise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okio.buffer
import okio.source
import java.io.File
import java.nio.ByteBuffer

open class OpenAiClient(
    override val name: String,
    private val project: Project,
    openAiConfig: OpenAIConfig,
    providerKey: String? = null,
    audioModality: Boolean = false,
    modelName: String? = null
) : StmProvider, LlmProvider, SttProvider, TtsProvider, AssistantProvider, SharedAudioCapture.AudioDataListener {

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"

        @JvmStatic
        val MODELS = listOf(
            "gpt-3.5-turbo",
            "gpt-3.5-turbo-instruct",
            "gpt-3.5-turbo-16k",
            "gpt-4o-mini",
            "gpt-4o",
            "gpt-4",
            "gpt-4-turbo",
            "gpt-4o-realtime-preview"
        )

        @JvmStatic
        val VOICES = arrayOf(
            "alloy",
            "echo",
            "fable",
            "onyx",
            "nova",
            "shimmer"
        )

        @JvmStatic
        fun getTokenLimit(modelName: String): Int {
            return when {
                modelName.startsWith("gpt-4-32k") -> 32_768
                modelName == "gpt-4" -> 8_192
                modelName.startsWith("gpt-4") -> 128_000
                modelName == "gpt-3.5-turbo-instruct" -> 4_096
                modelName.startsWith("gpt-3.5-turbo") -> 16_385
                else -> -1
            }
        }

        @JvmStatic
        fun getModels(project: Project, token: String): Future<List<String>> {
            val promise = Promise.promise<List<String>>()
            if (token.isEmpty()) {
                promise.complete(MODELS)
                return promise.future()
            }
            project.scope.launch {
                try {
                    val openAI = OpenAI(OpenAIConfig(token, logging = LoggingConfig(LogLevel.None)))
                    val models = openAI.models()
                    openAI.close()
                    promise.complete(
                        models.filter { it.id.id.startsWith("gpt-") || it.id.id.startsWith("ft:gpt-") }
                            .map { it.id.id }
                    )
                } catch (_: Throwable) {
                    promise.complete(MODELS)
                }
            }
            return promise.future()
        }
    }

    private val log = project.getVoqalLogger(this::class)
    private val openAI = OpenAI(openAiConfig)
    private val realtimeSession: RealtimeSession?

    init {
        realtimeSession = if (audioModality) {
            project.audioCapture.registerListener(this)
            RealtimeSession(
                project = project,
                wssProviderUrl = "wss://api.openai.com/v1/realtime?model=$modelName",
                wssHeaders = mapOf(
                    "Authorization" to "Bearer $providerKey",
                    "OpenAI-Beta" to "realtime=v1"
                )
            ).apply { Disposer.register(this@OpenAiClient, this) }
        } else null
    }

    override suspend fun transcribe(speechFile: File, modelName: String): String {
        log.info("Sending speech to STT provider: openai")

        val durationInSeconds = getAudioDuration(speechFile)
        if (durationInSeconds < 0.1) {
            log.warn("Audio transcript file is too short. Duration: $durationInSeconds")
            return ""
        }

        var languageCode: String? = null
        val sttSettings = project.service<VoqalConfigService>().getConfig().speechToTextSettings
        if (sttSettings.language != Iso639Language.AUTO_DETECT) {
            languageCode = sttSettings.language.code
        }

        val request = TranscriptionRequest(
            audio = FileSource(speechFile.name, speechFile.source().buffer()),
            model = ModelId(modelName),
            language = languageCode
        )
        val transcription = openAI.transcription(request)
        return transcription.text
    }

    override suspend fun chatCompletion(request: ChatCompletionRequest, directive: VoqalDirective?): ChatCompletion {
        return openAI.chatCompletion(request)
    }

    override suspend fun streamChatCompletion(
        request: ChatCompletionRequest,
        directive: VoqalDirective?
    ): Flow<ChatCompletionChunk> {
        return openAI.chatCompletions(request)
    }

    override suspend fun speech(request: SpeechRequest): TtsProvider.RawAudio {
        return TtsProvider.RawAudio(
            ByteReadChannel(ByteBuffer.wrap(openAI.speech(request))),
            24000f,
            16,
            1
        )
    }

    @BetaOpenAI
    override suspend fun assistant(request: AssistantRequest): Assistant {
        return openAI.assistant(request)
    }

    @BetaOpenAI
    override suspend fun assistant(id: AssistantId): Assistant? {
        return openAI.assistant(id)
    }

    @BetaOpenAI
    override suspend fun thread(request: ThreadRequest?): Thread {
        return openAI.thread(request)
    }

    @BetaOpenAI
    override suspend fun thread(id: ThreadId): Thread? {
        return openAI.thread(id)
    }

    @BetaOpenAI
    override suspend fun runs(
        threadId: ThreadId,
        limit: Int?,
        order: SortOrder?,
        after: RunId?,
        before: RunId?
    ): List<Run> {
        return openAI.runs(threadId, limit, order, after, before)
    }

    @BetaOpenAI
    override suspend fun message(threadId: ThreadId, messageId: MessageId): Message {
        return openAI.message(threadId, messageId)
    }

    @BetaOpenAI
    override suspend fun message(threadId: ThreadId, request: MessageRequest): Message {
        return openAI.message(threadId, request)
    }

    @OptIn(BetaOpenAI::class)
    override suspend fun messages(threadId: ThreadId, limit: Int?, order: SortOrder?): List<Message> {
        return openAI.messages(threadId, limit = limit, order = order)
    }

    @BetaOpenAI
    override suspend fun createRun(threadId: ThreadId, request: RunRequest): Run {
        return openAI.createRun(threadId, request)
    }

    @BetaOpenAI
    override suspend fun getRun(threadId: ThreadId, runId: RunId): Run {
        return openAI.getRun(threadId, runId)
    }

    @BetaOpenAI
    override suspend fun runSteps(
        threadId: ThreadId,
        runId: RunId,
        limit: Int?,
        order: SortOrder?,
        after: RunStepId?,
        before: RunStepId?
    ): List<RunStep> {
        return openAI.runSteps(threadId, runId, limit, order, after, before)
    }

    @BetaOpenAI
    override suspend fun submitToolOutput(threadId: ThreadId, runId: RunId, output: List<ToolOutput>): Run {
        return openAI.submitToolOutput(threadId, runId, output)
    }

    @BetaOpenAI
    override suspend fun delete(id: AssistantId): Boolean {
        return openAI.delete(id)
    }

    override fun onAudioData(data: ByteArray, detection: SharedAudioCapture.AudioDetection) {
        realtimeSession?.onAudioData(data, detection)
    }

    override fun sampleRate() = realtimeSession?.sampleRate() ?: super.sampleRate()
    override fun isLiveDataListener() = realtimeSession != null
    override fun isStreamable() = true
    override fun getAvailableModelNames() = MODELS
    override fun dispose() {
        project.audioCapture.removeListener(this)
        openAI.close()
    }
}
