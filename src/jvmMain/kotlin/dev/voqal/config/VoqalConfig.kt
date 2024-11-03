package dev.voqal.config

import dev.voqal.config.settings.*
import io.vertx.core.json.JsonObject

data class VoqalConfig(
    val browserSettings: BrowserSettings = BrowserSettings(),
    val voiceDetectionSettings: VoiceDetectionSettings = VoiceDetectionSettings(),
    val speechToTextSettings: SpeechToTextSettings = SpeechToTextSettings(),
    val languageModelsSettings: LanguageModelsSettings = LanguageModelsSettings(),
    val textToSpeechSettings: TextToSpeechSettings = TextToSpeechSettings(),
    val promptLibrarySettings: PromptLibrarySettings = PromptLibrarySettings()
) : ConfigurableSettings {

    /**
     * Need to set defaults so config changes don't reset stored config due to parse error.
     */
    constructor(json: JsonObject) : this(
        browserSettings = json.getJsonObject("browserSettings")?.let {
            BrowserSettings(it)
        } ?: BrowserSettings(),
        voiceDetectionSettings = json.getJsonObject("voiceDetectionSettings")?.let {
            VoiceDetectionSettings(it)
        } ?: VoiceDetectionSettings(),
        speechToTextSettings = json.getJsonObject("speechToTextSettings")?.let {
            SpeechToTextSettings(it)
        } ?: SpeechToTextSettings(),
        languageModelsSettings = json.getJsonObject("languageModelsSettings")?.let {
            LanguageModelsSettings(it)
        } ?: LanguageModelsSettings(),
        textToSpeechSettings = json.getJsonObject("textToSpeechSettings")?.let {
            TextToSpeechSettings(it)
        } ?: TextToSpeechSettings(),
        promptLibrarySettings = json.getJsonObject("promptLibrarySettings")?.let {
            PromptLibrarySettings(it)
        } ?: PromptLibrarySettings()
    )

    override fun toJson(): JsonObject {
        return JsonObject().apply {
            put("browserSettings", browserSettings.toJson())
            put("voiceDetectionSettings", voiceDetectionSettings.toJson())
            put("speechToTextSettings", speechToTextSettings.toJson())
            put("languageModelsSettings", languageModelsSettings.toJson())
            put("textToSpeechSettings", textToSpeechSettings.toJson())
            put("promptLibrarySettings", promptLibrarySettings.toJson())
        }
    }

    override fun withKeysRemoved(): VoqalConfig {
        return VoqalConfig(
            browserSettings = browserSettings.withKeysRemoved(),
            voiceDetectionSettings = voiceDetectionSettings.withKeysRemoved(),
            speechToTextSettings = speechToTextSettings.withKeysRemoved(),
            languageModelsSettings = languageModelsSettings.withKeysRemoved(),
            textToSpeechSettings = textToSpeechSettings.withKeysRemoved(),
            promptLibrarySettings = promptLibrarySettings.withKeysRemoved()
        )
    }

    override fun withPiiRemoved(): ConfigurableSettings {
        return VoqalConfig(
            browserSettings = browserSettings.withPiiRemoved(),
            voiceDetectionSettings = voiceDetectionSettings.withPiiRemoved(),
            speechToTextSettings = speechToTextSettings.withPiiRemoved(),
            languageModelsSettings = languageModelsSettings.withPiiRemoved(),
            textToSpeechSettings = textToSpeechSettings.withPiiRemoved(),
            promptLibrarySettings = promptLibrarySettings.withPiiRemoved(),
        )
    }
}
