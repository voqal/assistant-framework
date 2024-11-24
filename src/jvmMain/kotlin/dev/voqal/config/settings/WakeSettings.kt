package dev.voqal.config.settings

import dev.voqal.config.ConfigurableSettings
import io.vertx.core.json.JsonObject

data class WakeSettings(
    val provider: WakeProvider = WakeProvider.NONE,
    val providerKey: String = "",
    val wakeWord: String = "Voqal",
    val customWakeWordFile: String = ""
) : ConfigurableSettings {

    /**
     * Need to set defaults so config changes don't reset stored config due to parse error.
     */
    constructor(json: JsonObject) : this(
        provider = WakeProvider.lenientValueOf(json.getString("provider") ?: WakeProvider.NONE.name),
        providerKey = json.getString("providerKey", ""),
        wakeWord = json.getString("wakeWord", WakeWord.Voqal.name),
        customWakeWordFile = json.getString("customWakeWordFile", "")
    )

    override fun toJson(): JsonObject {
        return JsonObject().apply {
            put("provider", provider.name)
            put("providerKey", providerKey)
            put("wakeWord", wakeWord)
            put("customWakeWordFile", customWakeWordFile)
        }
    }

    override fun withKeysRemoved(): WakeSettings {
        return copy(providerKey = if (providerKey == "") "" else "***")
    }

    override fun withPiiRemoved(): WakeSettings {
        return withKeysRemoved().copy(customWakeWordFile = if (customWakeWordFile.isEmpty()) "" else "***")
    }

    enum class WakeProvider(val displayName: String) {
        NONE("None"),
        PICOVOICE("Picovoice");

        fun isKeyRequired(): Boolean {
            return this in setOf(PICOVOICE)
        }

        fun isCustomWakeFileAllowed(): Boolean {
            return this in setOf(PICOVOICE)
        }

        companion object {
            @JvmStatic
            fun lenientValueOf(str: String): WakeProvider {
                return WakeProvider.valueOf(str)
            }
        }
    }

    enum class WakeWord {
        Americano,
        Blueberry,
        Bumblebee,
        Computer,
        Grapefruit,
        Grasshopper,
        Jarvis,
        Picovoice,
        Porcupine,
        Terminator,
        Voqal,
        CustomFile;

        companion object {
            @JvmStatic
            fun valueOfOrNull(str: String): WakeWord? {
                return try {
                    WakeWord.valueOf(str)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }
    }
}