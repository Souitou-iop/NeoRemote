package com.neoremote.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neoremote.android.core.receiver.MobileControlAccessibilityService
import com.neoremote.android.core.session.SessionCoordinatorViewModel
import com.neoremote.android.ui.NeoRemoteApp
import com.neoremote.android.ui.theme.NeoRemoteTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SessionCoordinatorViewModel by viewModels {
        SessionCoordinatorViewModel.provideFactory(applicationContext)
    }
    private var androidReceiverEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidReceiverEnabled = MobileControlAccessibilityService.isEnabled(this)
        enableEdgeToEdge()
        setContent {
            NeoRemoteTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    viewModel.start()
                }
                NeoRemoteApp(
                    state = state,
                    isAndroidReceiverEnabled = androidReceiverEnabled,
                    onRefreshDiscovery = viewModel::refreshDiscovery,
                    onEnterDemoMode = viewModel::enterDemoMode,
                    onOpenAndroidReceiverSettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onManualDraftChange = viewModel::updateManualDraft,
                    onManualConnect = viewModel::connectUsingManualDraft,
                    onAdbWiredConnect = viewModel::connectUsingAdbWiredDebug,
                    onHapticsEnabledChange = viewModel::setHapticsEnabled,
                    onCursorSensitivityChange = viewModel::setCursorSensitivity,
                    onSwipeSensitivityChange = viewModel::setSwipeSensitivity,
                    onControlModeChange = viewModel::setControlMode,
                    onDefaultControlModeChange = viewModel::setDefaultControlMode,
                    onTouchOutput = viewModel::handleTouchOutput,
                    onScreenGesture = viewModel::sendScreenGesture,
                    onSystemAction = viewModel::sendSystemAction,
                    onVideoAction = viewModel::sendVideoAction,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        androidReceiverEnabled = MobileControlAccessibilityService.isEnabled(this)
    }
}
