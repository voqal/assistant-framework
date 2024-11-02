package dev.voqal.config.settings

import dev.voqal.config.ConfigurableSettings
import io.vertx.core.json.JsonObject

data class VoiceDetectionSettings(
    val provider: VoiceDetectionProvider = VoiceDetectionProvider.NONE,
    val providerKey: String = "",
    val sensitivity: Int = 3,
    val sustainDuration: Long = 100,
    val voiceSilenceThreshold: Long = 100,
    val speechSilenceThreshold: Long = 1000
) : ConfigurableSettings {

    /**
     * Need to set defaults so config changes don't reset stored config due to parse error.
     */
    constructor(json: JsonObject) : this(
        provider = VoiceDetectionProvider.lenientValueOf(
            json.getString("provider") ?: VoiceDetectionProvider.NONE.name
        ),
        providerKey = json.getString("providerKey", ""),
        sensitivity = json.getInteger("sensitivity") ?: 3,
        sustainDuration = json.getLong("sustainDuration") ?: 100,
        voiceSilenceThreshold = json.getLong("voiceSilenceThreshold") ?: 100,
        speechSilenceThreshold = json.getLong("speechSilenceThreshold") ?: 1000
    )

    override fun toJson(): JsonObject {
        return JsonObject().apply {
            put("provider", provider.name)
            put("providerKey", providerKey)
            put("sensitivity", sensitivity)
            put("sustainDuration", sustainDuration)
            put("voiceSilenceThreshold", voiceSilenceThreshold)
            put("speechSilenceThreshold", speechSilenceThreshold)
        }
    }

    override fun withKeysRemoved(): VoiceDetectionSettings {
        return copy(providerKey = if (providerKey == "") "" else "***")
    }

    override fun withPiiRemoved(): VoiceDetectionSettings {
        return withKeysRemoved()
    }

    enum class VoiceDetectionProvider(val displayName: String) {
        NONE("None"),
        PICOVOICE("Picovoice"),
        VOQAL("Voqal (Community)");

        fun isKeyRequired(): Boolean {
            return this in setOf(PICOVOICE)
        }

        companion object {
            @JvmStatic
            fun lenientValueOf(str: String): VoiceDetectionProvider {
                return VoiceDetectionProvider.valueOf(str
                    .replace(" (Community)", "")
                    .replace(" ", "_").uppercase())
            }
        }
    }
}
