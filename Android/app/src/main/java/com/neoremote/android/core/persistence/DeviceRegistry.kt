package com.neoremote.android.core.persistence

import android.content.Context
import android.content.SharedPreferences
import com.neoremote.android.core.model.ConnectionFailure
import com.neoremote.android.core.model.ControlMode
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.DesktopPlatform
import com.neoremote.android.core.model.EndpointSource
import com.neoremote.android.core.model.ManualConnectDraft
import com.neoremote.android.core.model.TouchSensitivitySettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesStore(
    context: Context,
    name: String = "neoremote_preferences",
) : KeyValueStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

class MemoryKeyValueStore : KeyValueStore {
    private val values = linkedMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

class DeviceRegistry(
    private val store: KeyValueStore,
) {
    private companion object {
        const val KEY_RECENT_DEVICES = "recent_devices"
        const val KEY_LAST_CONNECTED = "last_connected"
        const val KEY_MANUAL_CONNECT_DRAFT = "manual_connect_draft"
        const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CONTROL_MODE = "control_mode"
        const val KEY_CURSOR_SENSITIVITY = "cursor_sensitivity"
        const val KEY_SWIPE_SENSITIVITY = "swipe_sensitivity"
        const val MAX_RECENT_COUNT = 3
    }

    fun loadRecentDevices(): List<DesktopEndpoint> {
        val encoded = store.getString(KEY_RECENT_DEVICES) ?: return emptyList()
        val stored = Json.parseToJsonElement(encoded).jsonArray.map { it.jsonObject.toEndpoint() }
        val compacted = compactRecentDevices(stored)
        if (compacted != stored) {
            saveRecentDevices(compacted)
        }
        return compacted
    }

    fun loadLastConnectedDevice(): DesktopEndpoint? =
        store.getString(KEY_LAST_CONNECTED)?.let { encoded ->
            val endpoint = Json.parseToJsonElement(encoded).jsonObject.toEndpoint()
            loadRecentDevices().firstOrNull { it.matchesSameDesktop(endpoint) } ?: endpoint
        }

    fun loadManualDraft(): ManualConnectDraft =
        store.getString(KEY_MANUAL_CONNECT_DRAFT)
            ?.let { encoded ->
                Json.parseToJsonElement(encoded).jsonObject.let { json ->
                    ManualConnectDraft(
                        host = json.string("host"),
                        port = json.string("port").ifBlank { "51101" },
                    )
                }
            }
            ?: ManualConnectDraft()

    fun loadHapticsEnabled(): Boolean =
        store.getString(KEY_HAPTICS_ENABLED)?.toBooleanStrictOrNull() ?: true

    fun saveManualDraft(draft: ManualConnectDraft) {
        store.putString(
            KEY_MANUAL_CONNECT_DRAFT,
            buildJsonObject {
                put("host", draft.host)
                put("port", draft.port)
            }.toString(),
        )
    }

    fun saveHapticsEnabled(isEnabled: Boolean) {
        store.putString(KEY_HAPTICS_ENABLED, isEnabled.toString())
    }

    fun loadControlMode(): ControlMode =
        store.getString(KEY_CONTROL_MODE)
            ?.let { raw -> runCatching { ControlMode.valueOf(raw) }.getOrNull() }
            ?: ControlMode.SCREEN_CONTROL

    fun saveControlMode(mode: ControlMode) {
        store.putString(KEY_CONTROL_MODE, mode.name)
    }

    fun loadTouchSensitivitySettings(): TouchSensitivitySettings =
        TouchSensitivitySettings(
            cursorSensitivity = store.getString(KEY_CURSOR_SENSITIVITY)
                ?.toDoubleOrNull()
                ?: TouchSensitivitySettings().cursorSensitivity,
            swipeSensitivity = store.getString(KEY_SWIPE_SENSITIVITY)
                ?.toDoubleOrNull()
                ?: TouchSensitivitySettings().swipeSensitivity,
        ).clamped

    fun saveTouchSensitivitySettings(settings: TouchSensitivitySettings) {
        val clamped = settings.clamped
        store.putString(KEY_CURSOR_SENSITIVITY, clamped.cursorSensitivity.toString())
        store.putString(KEY_SWIPE_SENSITIVITY, clamped.swipeSensitivity.toString())
    }

    fun loadOrCreateClientId(): String {
        val existing = store.getString(KEY_CLIENT_ID).orEmpty()
        if (existing.isNotBlank()) return existing

        val id = UUID.randomUUID().toString()
        store.putString(KEY_CLIENT_ID, id)
        return id
    }

    fun upsertRecent(endpoint: DesktopEndpoint) {
        val current = loadRecentDevices().toMutableList()
        current.removeAll { it.matchesSameDesktop(endpoint) }

        val updated = endpoint.copy(
            source = EndpointSource.RECENT,
            lastSeenAt = System.currentTimeMillis(),
        )
        current.add(0, updated)

        saveRecentDevices(compactRecentDevices(current))
    }

    private fun saveRecentDevices(devices: List<DesktopEndpoint>) {
        val encoded = buildJsonArray {
            devices.take(MAX_RECENT_COUNT).forEach { add(it.toJson()) }
        }
        store.putString(KEY_RECENT_DEVICES, encoded.toString())
    }

    fun saveLastConnected(endpoint: DesktopEndpoint?) {
        if (endpoint == null) {
            store.remove(KEY_LAST_CONNECTED)
            return
        }
        val updated = endpoint.copy(
            source = EndpointSource.RECENT,
            lastSeenAt = System.currentTimeMillis(),
        )
        store.putString(KEY_LAST_CONNECTED, updated.toJson().toString())
    }

    fun clearRecentDevices() {
        store.remove(KEY_RECENT_DEVICES)
        store.remove(KEY_LAST_CONNECTED)
    }

    fun validate(host: String, portText: String): DesktopEndpoint {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) throw ConnectionFailure.InvalidHost
        val port = portText.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: throw ConnectionFailure.InvalidPort

        return DesktopEndpoint(
            displayName = "Desktop",
            host = trimmedHost,
            port = port,
            source = EndpointSource.MANUAL,
            lastSeenAt = System.currentTimeMillis(),
        )
    }

    private fun compactRecentDevices(devices: List<DesktopEndpoint>): List<DesktopEndpoint> {
        val seenKeys = linkedSetOf<String>()
        val result = mutableListOf<DesktopEndpoint>()

        devices.sortedByDescending { it.lastSeenAt ?: Long.MIN_VALUE }.forEach { endpoint ->
            val key = endpoint.deduplicationKey()
            if (seenKeys.add(key)) {
                result += endpoint
            }
            if (result.size == MAX_RECENT_COUNT) return result
        }

        return result
    }
}

fun DesktopEndpoint.deduplicationKey(): String {
    val normalizedName = displayName.trim().lowercase()
    val normalizedHost = host.trim().trim('.').lowercase()

    if (normalizedName.isNotBlank() && normalizedName != "desktop") {
        return "name:$normalizedName|port:$port"
    }

    return "host:$normalizedHost|port:$port"
}

fun DesktopEndpoint.matchesSameDesktop(other: DesktopEndpoint): Boolean =
    deduplicationKey() == other.deduplicationKey()

private fun DesktopEndpoint.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("displayName", displayName)
        put("host", host)
        put("port", port)
        put("platform", platform?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("lastSeenAt", lastSeenAt?.let(::JsonPrimitive) ?: JsonNull)
        put("source", source.name)
    }

private fun JsonObject.toEndpoint(): DesktopEndpoint =
    DesktopEndpoint(
        id = string("id"),
        displayName = string("displayName"),
        host = string("host"),
        port = int("port"),
        platform = string("platform").takeIf { it.isNotBlank() }?.let(DesktopPlatform::valueOf),
        lastSeenAt = this["lastSeenAt"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
        source = EndpointSource.valueOf(string("source")),
    )

private fun JsonObject.string(key: String): String =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content
        ?: ""

private fun JsonObject.int(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: 0
