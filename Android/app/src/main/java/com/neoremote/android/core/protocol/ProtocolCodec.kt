package com.neoremote.android.core.protocol

import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.ScreenGestureKind
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.ToggleState
import com.neoremote.android.core.model.VideoActionKind
import com.neoremote.android.core.model.VideoInteractionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

class ProtocolCodec {
    fun encode(command: RemoteCommand): ByteArray {
        val payload = buildJsonObject {
            when (command) {
            is RemoteCommand.ClientHello -> {
                    put("type", "clientHello")
                    put("clientId", command.clientId)
                    put("displayName", command.displayName)
                    put("platform", command.platform)
            }

            is RemoteCommand.Move -> {
                    put("type", "move")
                    put("dx", command.dx)
                    put("dy", command.dy)
            }

            is RemoteCommand.Tap -> {
                    put("type", "tap")
                    put("button", command.kind.name.lowercase())
            }

            is RemoteCommand.Scroll -> {
                    put("type", "scroll")
                    put("deltaX", command.deltaX)
                    put("deltaY", command.deltaY)
            }

            is RemoteCommand.Drag -> {
                    put("type", "drag")
                    put("state", command.state.name.lowercase())
                    put("button", command.button.name.lowercase())
                    put("dx", command.dx)
                    put("dy", command.dy)
            }

            is RemoteCommand.SystemActionCommand -> {
                    put("type", "systemAction")
                    put("action", command.action.name.lowercase())
            }

            is RemoteCommand.VideoAction -> {
                    put("type", "videoAction")
                    put("action", command.action.protocolName)
            }

            is RemoteCommand.ScreenGesture -> {
                    put("type", "screenGesture")
                    put("kind", command.kind.protocolName)
                    put("startX", command.startX)
                    put("startY", command.startY)
                    put("endX", command.endX)
                    put("endY", command.endY)
                    put("durationMs", command.durationMs)
            }

                RemoteCommand.RequestVideoState -> put("type", "videoStateRequest")
                RemoteCommand.Heartbeat -> put("type", "heartbeat")
            }
        }
        return payload.toString().encodeToByteArray()
    }

    fun encode(message: ProtocolMessage): ByteArray {
        val payload = buildJsonObject {
            when (message) {
                ProtocolMessage.Ack -> put("type", "ack")
                ProtocolMessage.Heartbeat -> put("type", "heartbeat")
                is ProtocolMessage.Status -> {
                    put("type", "status")
                    put("message", message.message)
                }
                is ProtocolMessage.VideoState -> {
                    put("type", "videoState")
                    put("targetPackage", message.state.targetPackage)
                    put("likeState", message.state.likeState.protocolName)
                    put("favoriteState", message.state.favoriteState.protocolName)
                }
                is ProtocolMessage.Unknown -> {
                    put("type", message.type)
                }
            }
        }
        return payload.toString().encodeToByteArray()
    }

    fun decodeMessage(data: ByteArray): ProtocolMessage {
        val payload = Json.parseToJsonElement(data.decodeToString()).jsonObject
        return when (payload.string("type")) {
            "ack" -> ProtocolMessage.Ack
            "status" -> ProtocolMessage.Status(payload.string("message"))
            "videoState" -> ProtocolMessage.VideoState(
                VideoInteractionState(
                    targetPackage = payload.string("targetPackage"),
                    likeState = payload.string("likeState").toToggleState(),
                    favoriteState = payload.string("favoriteState").toToggleState(),
                ),
            )
            "heartbeat" -> ProtocolMessage.Heartbeat
            else -> ProtocolMessage.Unknown(payload.string("type"))
        }
    }

    fun decodeCommand(data: ByteArray): RemoteCommand {
        val payload = Json.parseToJsonElement(data.decodeToString()).jsonObject
        return when (payload.string("type")) {
            "clientHello" -> RemoteCommand.ClientHello(
                clientId = payload.string("clientId").validatedText("clientId"),
                displayName = payload.string("displayName").validatedText("displayName"),
                platform = payload.string("platform").validatedText("platform"),
            )

            "move" -> RemoteCommand.Move(
                dx = payload.double("dx").validatedFinite("dx", MAX_POINTER_DELTA),
                dy = payload.double("dy").validatedFinite("dy", MAX_POINTER_DELTA),
            )

            "tap" -> RemoteCommand.Tap(
                kind = payload.string("button")
                    .toMouseButtonKind()
                    ?: MouseButtonKind.PRIMARY,
            )

            "scroll" -> RemoteCommand.Scroll(
                deltaX = payload.double("deltaX").validatedFinite("deltaX", MAX_SCROLL_DELTA),
                deltaY = payload.double("deltaY").validatedFinite("deltaY", MAX_SCROLL_DELTA),
            )
            "drag" -> RemoteCommand.Drag(
                state = payload.string("state").toDragState() ?: DragState.CHANGED,
                dx = payload.double("dx").validatedFinite("dx", MAX_POINTER_DELTA),
                dy = payload.double("dy").validatedFinite("dy", MAX_POINTER_DELTA),
                button = payload.string("button").toMouseButtonKind() ?: MouseButtonKind.PRIMARY,
            )

            "systemAction" -> RemoteCommand.SystemActionCommand(
                action = payload.string("action").toSystemAction() ?: SystemAction.BACK,
            )

            "videoAction" -> RemoteCommand.VideoAction(
                action = payload.string("action").toVideoActionKind(),
            )

            "screenGesture" -> RemoteCommand.ScreenGesture(
                kind = payload.string("kind").toScreenGestureKind(),
                startX = payload.double("startX"),
                startY = payload.double("startY"),
                endX = payload.double("endX"),
                endY = payload.double("endY"),
                durationMs = payload.long("durationMs").takeIf { it > 0L } ?: 180L,
            )

            "videoStateRequest" -> RemoteCommand.RequestVideoState
            "heartbeat" -> RemoteCommand.Heartbeat
            else -> throw IllegalArgumentException("未识别的命令类型：${payload.string("type")}")
        }
    }
}

private fun String.toMouseButtonKind(): MouseButtonKind? = when (lowercase()) {
    "primary" -> MouseButtonKind.PRIMARY
    "secondary" -> MouseButtonKind.SECONDARY
    "middle" -> MouseButtonKind.MIDDLE
    else -> null
}

private fun String.toDragState(): DragState? = when (lowercase()) {
    "started" -> DragState.STARTED
    "changed" -> DragState.CHANGED
    "ended" -> DragState.ENDED
    else -> null
}

private fun String.toSystemAction(): SystemAction? = when (lowercase()) {
    "back" -> SystemAction.BACK
    "home" -> SystemAction.HOME
    "recents" -> SystemAction.RECENTS
    else -> null
}

private val VideoActionKind.protocolName: String
    get() = when (this) {
        VideoActionKind.SWIPE_UP -> "swipeUp"
        VideoActionKind.SWIPE_DOWN -> "swipeDown"
        VideoActionKind.SWIPE_LEFT -> "swipeLeft"
        VideoActionKind.SWIPE_RIGHT -> "swipeRight"
        VideoActionKind.DOUBLE_TAP_LIKE -> "doubleTapLike"
        VideoActionKind.FAVORITE -> "favorite"
        VideoActionKind.PLAY_PAUSE -> "playPause"
        VideoActionKind.BACK -> "back"
        VideoActionKind.UNKNOWN -> "unknown"
    }

private fun String.toVideoActionKind(): VideoActionKind = when (this) {
    "swipeUp" -> VideoActionKind.SWIPE_UP
    "swipeDown" -> VideoActionKind.SWIPE_DOWN
    "swipeLeft" -> VideoActionKind.SWIPE_LEFT
    "swipeRight" -> VideoActionKind.SWIPE_RIGHT
    "doubleTapLike" -> VideoActionKind.DOUBLE_TAP_LIKE
    "favorite" -> VideoActionKind.FAVORITE
    "playPause" -> VideoActionKind.PLAY_PAUSE
    "back" -> VideoActionKind.BACK
    else -> VideoActionKind.UNKNOWN
}

private val ToggleState.protocolName: String
    get() = when (this) {
        ToggleState.ACTIVE -> "active"
        ToggleState.INACTIVE -> "inactive"
        ToggleState.UNKNOWN -> "unknown"
    }

private fun String.toToggleState(): ToggleState = when (this) {
    "active" -> ToggleState.ACTIVE
    "inactive" -> ToggleState.INACTIVE
    else -> ToggleState.UNKNOWN
}

private val ScreenGestureKind.protocolName: String
    get() = when (this) {
        ScreenGestureKind.TAP -> "tap"
        ScreenGestureKind.LONG_PRESS -> "longPress"
        ScreenGestureKind.SWIPE -> "swipe"
        ScreenGestureKind.UNKNOWN -> "unknown"
    }

private fun String.toScreenGestureKind(): ScreenGestureKind = when (this) {
    "tap" -> ScreenGestureKind.TAP
    "longPress" -> ScreenGestureKind.LONG_PRESS
    "swipe" -> ScreenGestureKind.SWIPE
    else -> ScreenGestureKind.UNKNOWN
}

private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
    this[key]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.content
        ?: ""

private fun kotlinx.serialization.json.JsonObject.double(key: String): Double =
    this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

private fun kotlinx.serialization.json.JsonObject.long(key: String): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: 0L

private fun String.validatedText(field: String): String {
    require(length <= MAX_TEXT_LENGTH) { "$field exceeds $MAX_TEXT_LENGTH characters" }
    return this
}

private fun Double.validatedFinite(field: String, limit: Double): Double {
    require(isFinite()) { "$field must be finite" }
    require(abs(this) <= limit) { "$field exceeds allowed range" }
    return this
}

private const val MAX_TEXT_LENGTH = 128
private const val MAX_POINTER_DELTA = 4_096.0
private const val MAX_SCROLL_DELTA = 240.0
