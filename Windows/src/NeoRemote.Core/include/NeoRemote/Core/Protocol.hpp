#pragma once

#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>

namespace NeoRemote::Core {

enum class MouseButtonKind {
    Primary,
    Secondary,
    Middle,
};

enum class DragState {
    Started,
    Changed,
    Ended,
};

enum class RemoteCommandType {
    ClientHello,
    Move,
    Tap,
    Scroll,
    Drag,
    Heartbeat,
};

struct RemoteCommand {
    RemoteCommandType type{RemoteCommandType::Heartbeat};
    double dx{0};
    double dy{0};
    double deltaX{0};
    double deltaY{0};
    MouseButtonKind button{MouseButtonKind::Primary};
    DragState state{DragState::Changed};
    std::string clientId;
    std::string displayName;
    std::string platform;

    static RemoteCommand ClientHello(std::string clientId, std::string displayName, std::string platform);
    static RemoteCommand Move(double dx, double dy);
    static RemoteCommand Tap(MouseButtonKind button);
    static RemoteCommand Scroll(double deltaX, double deltaY);
    static RemoteCommand Drag(DragState state, double dx, double dy);
    static RemoteCommand Drag(DragState state, double dx, double dy, MouseButtonKind button);
    static RemoteCommand Heartbeat();

    bool operator==(const RemoteCommand& other) const = default;
};

enum class ProtocolMessageType {
    Ack,
    Status,
    Heartbeat,
};

struct ProtocolMessage {
    ProtocolMessageType type{ProtocolMessageType::Ack};
    std::string message;

    static ProtocolMessage Ack();
    static ProtocolMessage Status(std::string message);
    static ProtocolMessage Heartbeat();

    bool operator==(const ProtocolMessage& other) const = default;
};

class ProtocolCodecError final : public std::runtime_error {
public:
    explicit ProtocolCodecError(const std::string& message);
};

class ProtocolCodec {
public:
    RemoteCommand DecodeCommand(std::string_view json) const;
    std::string EncodeMessage(const ProtocolMessage& message) const;
};

} // namespace NeoRemote::Core
