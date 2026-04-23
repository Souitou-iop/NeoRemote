package com.neoremote.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.SessionRoute
import com.neoremote.android.core.model.SessionUiState
import com.neoremote.android.core.model.TouchSurfaceOutput
import com.neoremote.android.ui.components.HapticsController
import com.neoremote.android.ui.screens.ConnectedShell
import com.neoremote.android.ui.screens.OnboardingScreen

@Composable
fun NeoRemoteApp(
    state: SessionUiState,
    onRefreshDiscovery: () -> Unit,
    onConnect: (DesktopEndpoint) -> Unit,
    onDisconnect: () -> Unit,
    onClearRecent: () -> Unit,
    onManualDraftChange: (String, String) -> Unit,
    onManualConnect: () -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onTouchOutput: (TouchSurfaceOutput) -> Unit,
) {
    val context = LocalContext.current
    val haptics = remember(context) { HapticsController(context) }

    when (state.route) {
        SessionRoute.ONBOARDING -> OnboardingScreen(
            state = state,
            onRefreshDiscovery = onRefreshDiscovery,
            onConnect = onConnect,
            onManualDraftChange = onManualDraftChange,
            onManualConnect = onManualConnect,
        )

        SessionRoute.CONNECTED -> ConnectedShell(
            state = state,
            onRefreshDiscovery = onRefreshDiscovery,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onClearRecent = onClearRecent,
            onHapticsEnabledChange = onHapticsEnabledChange,
            onTouchOutput = { output ->
                if (state.hapticsEnabled) {
                    haptics.perform(output.semanticEvent)
                }
                onTouchOutput(output)
            },
        )
    }
}
