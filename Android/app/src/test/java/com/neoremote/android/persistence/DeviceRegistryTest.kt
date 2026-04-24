package com.neoremote.android.persistence

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.EndpointSource
import com.neoremote.android.core.model.ManualConnectDraft
import com.neoremote.android.core.model.TouchSensitivitySettings
import com.neoremote.android.core.persistence.DeviceRegistry
import com.neoremote.android.core.persistence.MemoryKeyValueStore
import com.neoremote.android.core.model.DesktopPlatform
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class DeviceRegistryTest {
    private val store = MemoryKeyValueStore()
    private val registry = DeviceRegistry(store)

    @Test
    fun `upsert recent keeps most recent endpoint first`() {
        val first = DesktopEndpoint(
            displayName = "Mac Studio",
            host = "192.168.0.2",
            port = 50505,
            source = EndpointSource.RECENT,
        )
        val second = DesktopEndpoint(
            displayName = "Work PC",
            host = "192.168.0.3",
            port = 50505,
            source = EndpointSource.RECENT,
        )

        registry.upsertRecent(first)
        registry.upsertRecent(second)

        val recents = registry.loadRecentDevices()
        assertThat(recents).hasSize(2)
        assertThat(recents.first().host).isEqualTo("192.168.0.3")
    }

    @Test
    fun `upsert recent merges same desktop across bonjour and ip address`() {
        val bonjour = DesktopEndpoint(
            displayName = "Ebato的 Mac mini",
            host = "77db8123-21bc-4729-be20-875c7365bc3d.local",
            port = 50505,
            platform = DesktopPlatform.MAC_OS,
            source = EndpointSource.RECENT,
        )
        val ipAddress = DesktopEndpoint(
            displayName = "Ebato的 Mac mini",
            host = "192.168.31.35",
            port = 50505,
            platform = DesktopPlatform.WINDOWS,
            source = EndpointSource.DISCOVERED,
        )

        registry.upsertRecent(bonjour)
        registry.upsertRecent(ipAddress)

        val recents = registry.loadRecentDevices()
        assertThat(recents).hasSize(1)
        assertThat(recents.first().host).isEqualTo("192.168.31.35")
    }

    @Test
    fun `load recent compacts legacy duplicates and limits count`() {
        store.putString(
            "recent_devices",
            buildJsonArray {
                add(endpointJson("Mac A", "a.local", 1L))
                add(endpointJson("Mac A", "192.168.1.2", 5L))
                add(endpointJson("Mac B", "192.168.1.3", 4L))
                add(endpointJson("Mac C", "192.168.1.4", 3L))
                add(endpointJson("Mac D", "192.168.1.5", 2L))
            }.toString(),
        )

        val recents = registry.loadRecentDevices()

        assertThat(recents.map { it.displayName }).containsExactly("Mac A", "Mac B", "Mac C").inOrder()
        assertThat(recents.first().host).isEqualTo("192.168.1.2")
    }

    @Test
    fun `save manual draft persists current host and port`() {
        registry.saveManualDraft(ManualConnectDraft(host = "10.0.0.8", port = "51101"))

        assertThat(registry.loadManualDraft()).isEqualTo(
            ManualConnectDraft(host = "10.0.0.8", port = "51101"),
        )
    }
    @Test
    fun `manual draft defaults to adb reverse receiver port`() {
        assertThat(registry.loadManualDraft()).isEqualTo(ManualConnectDraft(port = "51101"))
    }

    @Test
    fun `haptics enabled defaults to true and persists updates`() {
        assertThat(registry.loadHapticsEnabled()).isTrue()

        registry.saveHapticsEnabled(false)

        assertThat(registry.loadHapticsEnabled()).isFalse()
    }

    @Test
    fun `touch sensitivity settings default and persist updates`() {
        assertThat(registry.loadTouchSensitivitySettings()).isEqualTo(TouchSensitivitySettings())

        registry.saveTouchSensitivitySettings(
            TouchSensitivitySettings(cursorSensitivity = 1.7, swipeSensitivity = 0.8),
        )

        assertThat(registry.loadTouchSensitivitySettings()).isEqualTo(
            TouchSensitivitySettings(cursorSensitivity = 1.7, swipeSensitivity = 0.8),
        )
    }

    @Test
    fun `touch sensitivity settings are clamped`() {
        registry.saveTouchSensitivitySettings(
            TouchSensitivitySettings(cursorSensitivity = 99.0, swipeSensitivity = 0.1),
        )

        assertThat(registry.loadTouchSensitivitySettings()).isEqualTo(
            TouchSensitivitySettings(cursorSensitivity = 2.5, swipeSensitivity = 0.5),
        )
    }

    private fun endpointJson(displayName: String, host: String, lastSeenAt: Long) =
        buildJsonObject {
            put("id", "$displayName-$host")
            put("displayName", displayName)
            put("host", host)
            put("port", 50505)
            put("platform", JsonPrimitive(DesktopPlatform.MAC_OS.name))
            put("lastSeenAt", JsonPrimitive(lastSeenAt))
            put("source", EndpointSource.RECENT.name)
        }
}
