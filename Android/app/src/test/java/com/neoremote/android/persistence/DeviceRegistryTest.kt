package com.neoremote.android.persistence

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.EndpointSource
import com.neoremote.android.core.model.ManualConnectDraft
import com.neoremote.android.core.persistence.DeviceRegistry
import com.neoremote.android.core.persistence.MemoryKeyValueStore
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
    fun `haptics setting defaults to disabled and persists changes`() {
        assertThat(registry.loadHapticsEnabled()).isFalse()

        registry.saveHapticsEnabled(true)

        assertThat(registry.loadHapticsEnabled()).isTrue()
    }
}

