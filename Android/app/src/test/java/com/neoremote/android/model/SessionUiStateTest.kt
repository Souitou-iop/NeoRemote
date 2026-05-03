package com.neoremote.android.model

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.DesktopPlatform
import com.neoremote.android.core.model.EndpointSource
import com.neoremote.android.core.model.SessionUiState
import org.junit.Test

class SessionUiStateTest {
    @Test
    fun `android receiver target flag is false for desktop and true for android`() {
        val desktopState = SessionUiState(
            activeEndpoint = DesktopEndpoint(
                displayName = "Mac Studio",
                host = "10.0.0.8",
                port = 50505,
                platform = DesktopPlatform.MAC_OS,
                source = EndpointSource.MANUAL,
            ),
        )
        val androidState = SessionUiState(
            activeEndpoint = DesktopEndpoint(
                displayName = "Android Tablet",
                host = "10.0.0.20",
                port = 51101,
                platform = DesktopPlatform.ANDROID,
                source = EndpointSource.MANUAL,
            ),
        )

        assertThat(desktopState.isAndroidReceiverTarget).isFalse()
        assertThat(androidState.isAndroidReceiverTarget).isTrue()
    }
}
