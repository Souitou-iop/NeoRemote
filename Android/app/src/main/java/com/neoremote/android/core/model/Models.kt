package com.neoremote.android.core.model

import java.util.UUID

enum class SessionStatus {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

enum class SessionRoute {
    ONBOARDING,
    CONNECTED,
}

enum class DesktopPlatform {
    MAC_OS,
    WINDOWS,
    ANDROID,
    ;

    val displayName: String
        get() = when (this) {
            MAC_OS -> "macOS"
            WINDOWS -> "Windows"
            ANDROID -> "Android"
        }
}

enum class EndpointSource {
    DISCOVERED,
    RECENT,
    MANUAL,
}

data class DesktopEndpoint(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val host: String,
    val port: Int,
    val platform: DesktopPlatform? = null,
    val lastSeenAt: Long? = null,
    val source: EndpointSource,
) {
    val addressText: String
        get() = "$host:$port"
}

enum class MouseButtonKind {
    PRIMARY,
    SECONDARY,
    MIDDLE,
}

enum class DragState {
    STARTED,
    CHANGED,
    ENDED,
}

enum class SystemAction {
    BACK,
    HOME,
    RECENTS,
}

enum class VideoActionKind {
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    DOUBLE_TAP_LIKE,
    PLAY_PAUSE,
    BACK,
    UNKNOWN,
}

sealed interface RemoteCommand {
    data class ClientHello(
        val clientId: String,
        val displayName: String,
        val platform: String,
    ) : RemoteCommand
    data class Move(val dx: Double, val dy: Double) : RemoteCommand
    data class Tap(val kind: MouseButtonKind) : RemoteCommand
    data class Scroll(
        val deltaX: Double = 0.0,
        val deltaY: Double = 0.0,
    ) : RemoteCommand
    data class Drag(
        val state: DragState,
        val dx: Double,
        val dy: Double,
        val button: MouseButtonKind = MouseButtonKind.PRIMARY,
    ) : RemoteCommand
    data class SystemActionCommand(val action: SystemAction) : RemoteCommand
    data class VideoAction(val action: VideoActionKind) : RemoteCommand
    data object Heartbeat : RemoteCommand
}

sealed interface ProtocolMessage {
    data object Ack : ProtocolMessage
    data class Status(val message: String) : ProtocolMessage
    data object Heartbeat : ProtocolMessage
    data class Unknown(val type: String) : ProtocolMessage
}

sealed interface TransportConnectionState {
    data object Idle : TransportConnectionState
    data object Connecting : TransportConnectionState
    data object Connected : TransportConnectionState
    data class Disconnected(val errorDescription: String?) : TransportConnectionState
    data class Failed(val errorDescription: String) : TransportConnectionState
}

data class ManualConnectDraft(
    val host: String = "",
    val port: String = "51101",
)

data class TouchPoint(
    val x: Float,
    val y: Float,
)

enum class TouchSurfaceSemanticEvent {
    PRIMARY_TAP,
    SECONDARY_TAP,
    MIDDLE_TAP,
    SCROLLING,
    PRIMARY_DRAG_STARTED,
    PRIMARY_DRAG_CHANGED,
    PRIMARY_DRAG_ENDED,
    SECONDARY_DRAG_STARTED,
    SECONDARY_DRAG_CHANGED,
    SECONDARY_DRAG_ENDED,
}

data class TouchSurfaceOutput(
    val commands: List<RemoteCommand> = emptyList(),
    val semanticEvent: TouchSurfaceSemanticEvent? = null,
)

data class TouchSensitivitySettings(
    val cursorSensitivity: Double = 1.0,
    val swipeSensitivity: Double = 1.0,
) {
    val clamped: TouchSensitivitySettings
        get() = copy(
            cursorSensitivity = cursorSensitivity.coerceIn(CURSOR_RANGE),
            swipeSensitivity = swipeSensitivity.coerceIn(SWIPE_RANGE),
        )

    companion object {
        val CURSOR_RANGE = 0.4..2.5
        val SWIPE_RANGE = 0.5..2.0
        const val STEP = 0.1
    }
}

sealed class ConnectionFailure(message: String) : IllegalArgumentException(message) {
    data object InvalidHost : ConnectionFailure("请输入有效的桌面端地址。")
    data object InvalidPort : ConnectionFailure("请输入有效的端口。")
    data object ConnectionUnavailable : ConnectionFailure("当前桌面连接不可用，请稍后重试。")
}

data class SessionUiState(
    val status: SessionStatus = SessionStatus.DISCONNECTED,
    val route: SessionRoute = SessionRoute.ONBOARDING,
    val discoveredDevices: List<DesktopEndpoint> = emptyList(),
    val recentDevices: List<DesktopEndpoint> = emptyList(),
    val activeEndpoint: DesktopEndpoint? = null,
    val lastConnectedEndpoint: DesktopEndpoint? = null,
    val lastHudMessage: String? = null,
    val hapticsEnabled: Boolean = true,
    val touchSensitivitySettings: TouchSensitivitySettings = TouchSensitivitySettings(),
    val errorMessage: String? = null,
    val statusMessage: String = "等待连接桌面端",
    val manualConnectDraft: ManualConnectDraft = ManualConnectDraft(),
) {
    val isBusy: Boolean
        get() = status == SessionStatus.DISCOVERING ||
            status == SessionStatus.CONNECTING ||
            status == SessionStatus.RECONNECTING
}

val SessionStatus.displayText: String
    get() = when (this) {
        SessionStatus.DISCONNECTED -> "未连接"
        SessionStatus.DISCOVERING -> "发现中"
        SessionStatus.CONNECTING -> "连接中"
        SessionStatus.CONNECTED -> "已连接"
        SessionStatus.RECONNECTING -> "重连中"
        SessionStatus.FAILED -> "失败"
    }
