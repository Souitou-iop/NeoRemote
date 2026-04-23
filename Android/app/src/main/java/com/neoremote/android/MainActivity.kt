package com.neoremote.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neoremote.android.core.session.SessionCoordinatorViewModel
import com.neoremote.android.ui.NeoRemoteApp
import com.neoremote.android.ui.theme.NeoRemoteTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SessionCoordinatorViewModel by viewModels {
        SessionCoordinatorViewModel.provideFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeoRemoteTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    viewModel.start()
                }
                NeoRemoteApp(
                    state = state,
                    onRefreshDiscovery = viewModel::refreshDiscovery,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onClearRecent = viewModel::clearRecentDevices,
                    onManualDraftChange = viewModel::updateManualDraft,
                    onManualConnect = viewModel::connectUsingManualDraft,
                    onHapticsEnabledChange = viewModel::setHapticsEnabled,
                    onTouchOutput = viewModel::handleTouchOutput,
                )
            }
        }
    }
}

