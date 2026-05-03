#include "NeoRemote/Core/Protocol.hpp"

#include <charconv>
#include <cctype>
#include <cmath>
#include <sstream>
#include <utility>

namespace NeoRemote::Core {
namespace {

std::optional<std::string> FindString(std::string_view json, std::string_view key)
{
    const std::string needle = "\"" + std::string(key) + "\"";
    const size_t keyPos = json.find(needle);
    if (keyPos == std::string_view::npos) {
        return std::nullopt;
    }
    const size_t colon = json.find(':', keyPos + needle.size());
    if (colon == std::string_view::npos) {
        return std::nullopt;
    }
    size_t quote = json.find('"', colon + 1);
    if (quote == std::string_view::npos) {
        return std::nullopt;
    }

    std::string value;
    bool escaping = false;
    for (size_t i = quote + 1; i < json.size(); ++i) {
        const char c = json[i];
        if (escaping) {
            value.push_back(c);
            escaping = false;
            continue;
        }
        if (c == '\\') {
            escaping = true;
            continue;
        }
        if (c == '"') {
            return value;
        }
        value.push_back(c);
    }
    return std::nullopt;
}

std::optional<double> FindNumber(std::string_view json, std::string_view key)
{
    const std::string needle = "\"" + std::string(key) + "\"";
    const size_t keyPos = json.find(needle);
    if (keyPos == std::string_view::npos) {
        return std::nullopt;
    }
    const size_t colon = json.find(':', keyPos + needle.size());
    if (colon == std::string_view::npos) {
        return std::nullopt;
    }
    size_t begin = colon + 1;
    while (begin < json.size() && std::isspace(static_cast<unsigned char>(json[begin]))) {
        ++begin;
    }
    size_t end = begin;
    while (end < json.size()) {
        const char c = json[end];
        if (!(std::isdigit(static_cast<unsigned char>(c)) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E')) {
            break;
        }
        ++end;
    }
    if (begin == end) {
        return std::nullopt;
    }

    double value = 0;
    const auto text = std::string(json.substr(begin, end - begin));
    const auto [ptr, ec] = std::from_chars(text.data(), text.data() + text.size(), value);
    if (ec != std::errc()) {
        return std::nullopt;
    }
    return value;
}

bool HasKey(std::string_view json, std::string_view key)
{
    const std::string needle = "\"" + std::string(key) + "\"";
    return json.find(needle) != std::string_view::npos;
}

void ValidateText(std::string_view value, std::string_view key)
{
    constexpr size_t MaxTextLength = 128;
    if (value.size() > MaxTextLength) {
        throw ProtocolCodecError(std::string(key) + " exceeds 128 characters");
    }
}

double ReadNumber(std::string_view json, std::string_view key, double fallback, double limit)
{
    const auto value = FindNumber(json, key);
    if (!value) {
        if (HasKey(json, key)) {
            throw ProtocolCodecError(std::string(key) + " must be a finite number");
        }
        return fallback;
    }
    if (!std::isfinite(*value)) {
        throw ProtocolCodecError(std::string(key) + " must be finite");
    }
    if (std::abs(*value) > limit) {
        throw ProtocolCodecError(std::string(key) + " exceeds allowed range");
    }
    return *value;
}

MouseButtonKind ParseButton(const std::optional<std::string>& value)
{
    if (value == "secondary") {
        return MouseButtonKind::Secondary;
    }
    if (value == "middle") {
        return MouseButtonKind::Middle;
    }
    return MouseButtonKind::Primary;
}

DragState ParseDragState(const std::optional<std::string>& value)
{
    if (value == "started") {
        return DragState::Started;
    }
    if (value == "ended") {
        return DragState::Ended;
    }
    return DragState::Changed;
}

std::string EscapeJson(std::string_view value)
{
    std::string escaped;
    escaped.reserve(value.size());
    for (const char c : value) {
        if (c == '"' || c == '\\') {
            escaped.push_back('\\');
        }
        escaped.push_back(c);
    }
    return escaped;
}

} // namespace

RemoteCommand RemoteCommand::Move(double dxValue, double dyValue)
{
    RemoteCommand command;
    command.type = RemoteCommandType::Move;
    command.dx = dxValue;
    command.dy = dyValue;
    return command;
}

RemoteCommand RemoteCommand::ClientHello(std::string clientIdValue, std::string displayNameValue, std::string platformValue)
{
    RemoteCommand command;
    command.type = RemoteCommandType::ClientHello;
    command.clientId = std::move(clientIdValue);
    command.displayName = std::move(displayNameValue);
    command.platform = std::move(platformValue);
    return command;
}

RemoteCommand RemoteCommand::Tap(MouseButtonKind buttonValue)
{
    RemoteCommand command;
    command.type = RemoteCommandType::Tap;
    command.button = buttonValue;
    return command;
}

RemoteCommand RemoteCommand::Scroll(double deltaXValue, double deltaYValue)
{
    RemoteCommand command;
    command.type = RemoteCommandType::Scroll;
    command.deltaX = deltaXValue;
    command.deltaY = deltaYValue;
    return command;
}

RemoteCommand RemoteCommand::Drag(DragState stateValue, double dxValue, double dyValue)
{
    return RemoteCommand::Drag(stateValue, dxValue, dyValue, MouseButtonKind::Primary);
}

RemoteCommand RemoteCommand::Drag(DragState stateValue, double dxValue, double dyValue, MouseButtonKind buttonValue)
{
    RemoteCommand command;
    command.type = RemoteCommandType::Drag;
    command.state = stateValue;
    command.dx = dxValue;
    command.dy = dyValue;
    command.button = buttonValue;
    return command;
}

RemoteCommand RemoteCommand::Heartbeat()
{
    return RemoteCommand{};
}

ProtocolMessage ProtocolMessage::Ack()
{
    return ProtocolMessage{ProtocolMessageType::Ack, ""};
}

ProtocolMessage ProtocolMessage::Status(std::string text)
{
    return ProtocolMessage{ProtocolMessageType::Status, std::move(text)};
}

ProtocolMessage ProtocolMessage::Heartbeat()
{
    return ProtocolMessage{ProtocolMessageType::Heartbeat, ""};
}

ProtocolCodecError::ProtocolCodecError(const std::string& message)
    : std::runtime_error(message)
{
}

RemoteCommand ProtocolCodec::DecodeCommand(std::string_view json) const
{
    const auto type = FindString(json, "type");
    if (!type) {
        throw ProtocolCodecError("Missing command type");
    }

    if (*type == "move") {
        return RemoteCommand::Move(
            ReadNumber(json, "dx", 0, 4096),
            ReadNumber(json, "dy", 0, 4096));
    }
    if (*type == "clientHello") {
        const auto clientId = FindString(json, "clientId").value_or("");
        const auto displayName = FindString(json, "displayName").value_or("");
        const auto platform = FindString(json, "platform").value_or("");
        ValidateText(clientId, "clientId");
        ValidateText(displayName, "displayName");
        ValidateText(platform, "platform");
        return RemoteCommand::ClientHello(
            clientId,
            displayName,
            platform);
    }
    if (*type == "tap") {
        return RemoteCommand::Tap(ParseButton(FindString(json, "button")));
    }
    if (*type == "scroll") {
        return RemoteCommand::Scroll(
            ReadNumber(json, "deltaX", 0, 240),
            ReadNumber(json, "deltaY", 0, 240));
    }
    if (*type == "drag") {
        return RemoteCommand::Drag(
            ParseDragState(FindString(json, "state")),
            ReadNumber(json, "dx", 0, 4096),
            ReadNumber(json, "dy", 0, 4096),
            ParseButton(FindString(json, "button")));
    }
    if (*type == "heartbeat") {
        return RemoteCommand::Heartbeat();
    }

    throw ProtocolCodecError("Unknown command type: " + *type);
}

std::string ProtocolCodec::EncodeMessage(const ProtocolMessage& message) const
{
    switch (message.type) {
    case ProtocolMessageType::Ack:
        return R"({"type":"ack"})";
    case ProtocolMessageType::Heartbeat:
        return R"({"type":"heartbeat"})";
    case ProtocolMessageType::Status:
        return R"({"type":"status","message":")" + EscapeJson(message.message) + R"("})";
    }
    return R"({"type":"status","message":"unknown message"})";
}

} // namespace NeoRemote::Core
