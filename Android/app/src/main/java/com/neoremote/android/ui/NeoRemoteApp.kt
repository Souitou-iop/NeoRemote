package com.neoremote.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.SessionRoute
import com.neoremote.android.core.model.SessionUiState
import com.neoremote.android.core.model.TouchSurfaceOutput
import com.neoremote.android.core.model.VideoActionKind
import com.neoremote.android.ui.components.HapticsController
import com.neoremote.android.ui.screens.ConnectedShell
import com.neoremote.android.ui.screens.OnboardingScreen

@Composable
fun NeoRemoteApp(
    state: SessionUiState,
    isAndroidReceiverEnabled: Boolean,
    onRefreshDiscovery: () -> Unit,
    onEnterDemoMode: () -> Unit,
    onOpenAndroidReceiverSettings: () -> Unit,
    onConnect: (DesktopEndpoint) -> Unit,
    onDisconnect: () -> Unit,
    onClearRecent: () -> Unit,
    onManualDraftChange: (String, String) -> Unit,
    onManualConnect: () -> Unit,
    onAdbWiredConnect: () -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onCursorSensitivityChange: (Double) -> Unit = {},
    onSwipeSensitivityChange: (Double) -> Unit = {},
    onTouchOutput: (TouchSurfaceOutput) -> Unit,
    onVideoAction: (VideoActionKind) -> Unit,
) {
    val context = LocalContext.current
    val haptics = remember(context) { HapticsController(context) }

    when (state.route) {
        SessionRoute.ONBOARDING -> OnboardingScreen(
            state = state,
            onRefreshDiscovery = onRefreshDiscovery,
            onEnterDemoMode = onEnterDemoMode,
            isAndroidReceiverEnabled = isAndroidReceiverEnabled,
            onOpenAndroidReceiverSettings = onOpenAndroidReceiverSettings,
            onConnect = onConnect,
            onManualDraftChange = onManualDraftChange,
            onManualConnect = onManualConnect,
            onAdbWiredConnect = onAdbWiredConnect,
        )

        SessionRoute.CONNECTED -> ConnectedShell(
            state = state,
            onRefreshDiscovery = onRefreshDiscovery,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onClearRecent = onClearRecent,
            onHapticsEnabledChange = onHapticsEnabledChange,
            onCursorSensitivityChange = onCursorSensitivityChange,
            onSwipeSensitivityChange = onSwipeSensitivityChange,
            onTouchOutput = { output ->
                if (state.hapticsEnabled) {
                    haptics.perform(output.semanticEvent)
                }
                onTouchOutput(output)
            },
            onVideoAction = onVideoAction,
        )
    }
}
