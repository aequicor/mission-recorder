package io.aequicor.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

class MissionRecorderSettingsStore(
    private val path: Path,
    private val json: Json = defaultSettingsJson,
) {
    fun loadOrDefault(defaults: MissionRecorderSettings = MissionRecorderSettingsFactory.defaultLocal()): MissionRecorderSettings =
        if (path.exists()) {
            decode(Files.readString(path))
        } else {
            defaults
        }

    fun save(settings: MissionRecorderSettings) {
        val issues = MissionRecorderSettingsValidator.validate(settings)
        if (issues.isNotEmpty()) {
            throw SettingsValidationException(issues)
        }
        path.parent?.createDirectories()
        val temp = path.resolveSibling("${path.name}.tmp")
        Files.writeString(temp, json.encodeToString(MissionRecorderSettings.serializer(), settings))
        moveAtomically(temp, path)
    }

    private fun decode(raw: String): MissionRecorderSettings =
        json.decodeFromString(MissionRecorderSettings.serializer(), SettingsMigrator.migrate(raw, json))

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

object SettingsMigrator {
    fun migrate(raw: String, json: Json = defaultSettingsJson): String {
        val element = json.parseToJsonElement(raw)
        require(element is JsonObject) { "Settings document must be a JSON object." }
        val schemaVersion = element["schemaVersion"]?.jsonPrimitiveIntOrNull()
        return when (schemaVersion) {
            CURRENT_SETTINGS_SCHEMA_VERSION -> raw
            null -> json.encodeToString(JsonObject.serializer(), element.withDefaultsForSchemaOne())
            else -> throw IllegalArgumentException("Unsupported settings schema version: $schemaVersion.")
        }
    }

    private fun JsonObject.withDefaultsForSchemaOne(): JsonObject =
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(CURRENT_SETTINGS_SCHEMA_VERSION))
            entries.forEach { (key, value) -> put(key, value) }
            if (!containsKey("defaultProfileId")) {
                put("defaultProfileId", JsonPrimitive(firstProfileId(value = this@withDefaultsForSchemaOne)))
            }
        }

    private fun firstProfileId(value: JsonObject): String {
        val profiles = value["profiles"] as? JsonArray ?: return "default"
        val firstProfile = profiles.firstOrNull() as? JsonObject ?: return "default"
        return (firstProfile["id"] as? JsonPrimitive)?.content ?: "default"
    }
}

val defaultSettingsJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private fun JsonElement.jsonPrimitiveIntOrNull(): Int? =
    (this as? JsonPrimitive)?.content?.toIntOrNull()
