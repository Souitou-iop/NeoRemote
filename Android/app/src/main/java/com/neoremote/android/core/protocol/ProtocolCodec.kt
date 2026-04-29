package com.neoremote.android.core.protocol

import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.VideoActionKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

                RemoteCommand.Heartbeat -> put("type", "heartbeat")
            }
        }
        return payload.toString().encodeToByteArray()
    }

    fun decodeMessage(data: ByteArray): ProtocolMessage {
        val payload = Json.parseToJsonElement(data.decodeToString()).jsonObject
        return when (payload.string("type")) {
            "ack" -> ProtocolMessage.Ack
            "status" -> ProtocolMessage.Status(payload.string("message"))
            "heartbeat" -> ProtocolMessage.Heartbeat
            else -> ProtocolMessage.Unknown(payload.string("type"))
        }
    }

    fun decodeCommand(data: ByteArray): RemoteCommand {
        val payload = Json.parseToJsonElement(data.decodeToString()).jsonObject
        return when (payload.string("type")) {
            "clientHello" -> RemoteCommand.ClientHello(
                clientId = payload.string("clientId"),
                displayName = payload.string("displayName"),
                platform = payload.string("platform"),
            )

            "move" -> RemoteCommand.Move(
                dx = payload.double("dx"),
                dy = payload.double("dy"),
            )

            "tap" -> RemoteCommand.Tap(
                kind = payload.string("button")
                    .toMouseButtonKind()
                    ?: MouseButtonKind.PRIMARY,
            )

            "scroll" -> RemoteCommand.Scroll(
                deltaX = payload.double("deltaX"),
                deltaY = payload.double("deltaY"),
            )
            "drag" -> RemoteCommand.Drag(
                state = payload.string("state").toDragState() ?: DragState.CHANGED,
                dx = payload.double("dx"),
                dy = payload.double("dy"),
                button = payload.string("button").toMouseButtonKind() ?: MouseButtonKind.PRIMARY,
            )

            "systemAction" -> RemoteCommand.SystemActionCommand(
                action = payload.string("action").toSystemAction() ?: SystemAction.BACK,
            )

            "videoAction" -> RemoteCommand.VideoAction(
                action = payload.string("action").toVideoActionKind(),
            )

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
    "playPause" -> VideoActionKind.PLAY_PAUSE
    "back" -> VideoActionKind.BACK
    else -> VideoActionKind.UNKNOWN
}

private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
    this[key]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.content
        ?: ""

private fun kotlinx.serialization.json.JsonObject.double(key: String): Double =
    this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
