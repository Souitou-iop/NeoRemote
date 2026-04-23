#include "NeoRemote/Core/DesktopRemoteService.hpp"
#include "NeoRemote/Core/JsonMessageStreamDecoder.hpp"
#include "NeoRemote/Core/MouseEventPlanner.hpp"
#include "NeoRemote/Core/Protocol.hpp"

#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using namespace NeoRemote::Core;

namespace {

void Require(bool condition, const std::string& message)
{
    if (!condition) {
        throw std::runtime_error(message);
    }
}

template <typename T>
void RequireEqual(const T& actual, const T& expected, const std::string& message)
{
    if (!(actual == expected)) {
        throw std::runtime_error(message);
    }
}

struct SentRecord {
    ProtocolMessage message;
    std::string clientId;
};

class RecordingServer final : public IRemoteServer {
public:
    void Start(unsigned short port) override
    {
        ++startCount;
        lastPort = port;
    }

    void Stop() override
    {
        ++stopCount;
    }

    void Send(const ProtocolMessage& message, const std::string& clientId) override
    {
        sent.push_back(SentRecord{message, clientId});
    }

    void Disconnect(const std::string& clientId) override
    {
        disconnected.push_back(clientId);
    }

    int startCount{0};
    int stopCount{0};
    unsigned short lastPort{0};
    std::vector<SentRecord> sent;
    std::vector<std::string> disconnected;
};

class RecordingInjector final : public IInputInjector {
public:
    void Handle(const RemoteCommand& command) override
    {
        commands.push_back(command);
    }

    std::vector<RemoteCommand> commands;
};

class RecordingPublisher final : public IMdnsPublisher {
public:
    void Start(unsigned short port, const std::string& instanceName) override
    {
        ++startCount;
        lastPort = port;
        lastInstanceName = instanceName;
    }

    void Stop() override
    {
        ++stopCount;
    }

    std::string LastError() const override
    {
        return lastError;
    }

    int startCount{0};
    int stopCount{0};
    unsigned short lastPort{0};
    std::string lastInstanceName;
    std::string lastError;
};

void TestDecodeMoveCommand()
{
    ProtocolCodec codec;
    RequireEqual(codec.DecodeCommand(R"({"type":"move","dx":12.5,"dy":-8})"), RemoteCommand::Move(12.5, -8), "move command decode failed");
}

void TestDecodeTapCommand()
{
    ProtocolCodec codec;
    RequireEqual(codec.DecodeCommand(R"({"type":"tap","button":"primary"})"), RemoteCommand::Tap(MouseButtonKind::Primary), "tap command decode failed");
}

void TestDecodeMiddleTapCommand()
{
    ProtocolCodec codec;
    RequireEqual(codec.DecodeCommand(R"({"type":"tap","button":"middle"})"), RemoteCommand::Tap(MouseButtonKind::Middle), "middle tap command decode failed");
}

void TestEncodeStatusMessage()
{
    ProtocolCodec codec;
    const std::string json = codec.EncodeMessage(ProtocolMessage::Status("ok"));
    Require(json.find(R"("type":"status")") != std::string::npos, "status type missing");
    Require(json.find(R"("message":"ok")") != std::string::npos, "status message missing");
}

void TestStreamDecoderSplitsMultipleJsonObjects()
{
    JsonMessageStreamDecoder decoder;
    const auto payloads = decoder.Append(R"({"type":"heartbeat"}{"type":"tap","button":"primary"})");
    Require(payloads.size() == 2, "stream decoder did not split two JSON payloads");
    Require(payloads[0] == R"({"type":"heartbeat"})", "first payload mismatch");
    Require(payloads[1] == R"({"type":"tap","button":"primary"})", "second payload mismatch");
}

void TestMoveCommandMapsToMovedCursorPosition()
{
    MouseEventPlanner planner;
    const auto events = planner.Apply(RemoteCommand::Move(20, 10), [] { return Point{100, 100}; });
    Require(events.size() == 1, "move event count mismatch");
    RequireEqual(events[0], PlannedMouseEvent::Move(Point{120, 110}), "move event mismatch");
}

void TestTapCommandMapsToMouseDownAndMouseUp()
{
    MouseEventPlanner planner;
    const auto events = planner.Apply(RemoteCommand::Tap(MouseButtonKind::Primary), [] { return Point{40, 60}; });
    Require(events.size() == 2, "tap event count mismatch");
    RequireEqual(events[0], PlannedMouseEvent::MouseDown(MouseButtonKind::Primary, Point{40, 60}), "tap down mismatch");
    RequireEqual(events[1], PlannedMouseEvent::MouseUp(MouseButtonKind::Primary, Point{40, 60}), "tap up mismatch");
}

void TestMiddleTapCommandMapsToMouseDownAndMouseUp()
{
    MouseEventPlanner planner;
    const auto events = planner.Apply(RemoteCommand::Tap(MouseButtonKind::Middle), [] { return Point{40, 60}; });
    Require(events.size() == 2, "middle tap event count mismatch");
    RequireEqual(events[0], PlannedMouseEvent::MouseDown(MouseButtonKind::Middle, Point{40, 60}), "middle tap down mismatch");
    RequireEqual(events[1], PlannedMouseEvent::MouseUp(MouseButtonKind::Middle, Point{40, 60}), "middle tap up mismatch");
}

void TestDragLifecycleMapsToDownDragUp()
{
    MouseEventPlanner planner;
    const auto start = planner.Apply(RemoteCommand::Drag(DragState::Started, 0, 0), [] { return Point{30, 30}; });
    const auto change = planner.Apply(RemoteCommand::Drag(DragState::Changed, 5, -4), [] { return Point{30, 30}; });
    const auto end = planner.Apply(RemoteCommand::Drag(DragState::Ended, 0, 0), [] { return Point{30, 30}; });

    RequireEqual(start[0], PlannedMouseEvent::MouseDown(MouseButtonKind::Primary, Point{30, 30}), "drag start mismatch");
    RequireEqual(change[0], PlannedMouseEvent::Drag(MouseButtonKind::Primary, Point{35, 26}), "drag change mismatch");
    RequireEqual(end[0], PlannedMouseEvent::MouseUp(MouseButtonKind::Primary, Point{35, 26}), "drag end mismatch");
}

void TestClientConnectedSendsAckAndStatus()
{
    RecordingServer server;
    RecordingInjector injector;
    RecordingPublisher publisher;
    DesktopRemoteService service(server, injector, &publisher);

    service.Start();
    service.HandleServerEvent(RemoteServerEvent::ListenerReady(50505));
    service.HandleServerEvent(RemoteServerEvent::ClientConnected("client-1", RemoteClientEndpoint{"192.168.1.8", 60000}));

    Require(server.startCount == 1, "server did not start");
    Require(publisher.startCount == 1, "mDNS publisher did not start");
    Require(server.sent.size() == 2, "connect did not send ack and status");
    Require(server.sent[0].message == ProtocolMessage::Ack(), "first connect message was not ack");
    Require(server.sent[1].message.type == ProtocolMessageType::Status, "second connect message was not status");
    Require(service.State().type == DesktopDashboardStateType::Connected, "service did not enter connected state");
}

void TestMdnsPublisherFailureIsReportedButTcpKeepsListening()
{
    RecordingServer server;
    RecordingInjector injector;
    RecordingPublisher publisher;
    publisher.lastError = "mDNS unavailable";
    DesktopRemoteService service(server, injector, &publisher);

    service.Start();
    service.HandleServerEvent(RemoteServerEvent::ListenerReady(51101));

    Require(service.State().type == DesktopDashboardStateType::IdleListening, "mDNS failure should not stop TCP listener");
    Require(service.RecentEvents().size() >= 2, "mDNS failure did not add events");
    Require(service.RecentEvents()[0].title == "服务已启动", "TCP fallback event missing");
    Require(service.RecentEvents()[1].title == "发现服务发布失败", "mDNS failure event missing");
}

void TestStartUsesAdbReversePort()
{
    RecordingServer server;
    RecordingInjector injector;
    DesktopRemoteService service(server, injector);

    service.Start();

    RequireEqual(server.lastPort, static_cast<unsigned short>(51101), "service did not use adb reverse default port");
}

void TestHeartbeatRepliesToActiveClient()
{
    RecordingServer server;
    RecordingInjector injector;
    DesktopRemoteService service(server, injector);
    service.HandleServerEvent(RemoteServerEvent::ClientConnected("client-1", RemoteClientEndpoint{"10.0.0.2", 55000}));
    service.HandleServerEvent(RemoteServerEvent::Command("client-1", RemoteCommand::Heartbeat()));

    Require(server.sent.size() == 3, "heartbeat did not add third sent message");
    Require(server.sent[2].message == ProtocolMessage::Heartbeat(), "heartbeat reply mismatch");
    Require(injector.commands.empty(), "heartbeat should not inject input");
}

void TestActiveClientCommandInjectsInput()
{
    RecordingServer server;
    RecordingInjector injector;
    DesktopRemoteService service(server, injector);
    service.HandleServerEvent(RemoteServerEvent::ClientConnected("client-1", RemoteClientEndpoint{"10.0.0.2", 55000}));
    service.HandleServerEvent(RemoteServerEvent::Command("client-1", RemoteCommand::Move(10, 5)));

    Require(injector.commands.size() == 1, "active command was not injected");
    RequireEqual(injector.commands[0], RemoteCommand::Move(10, 5), "injected command mismatch");
}

void TestRejectedConnectionShowsOccupiedState()
{
    RecordingServer server;
    RecordingInjector injector;
    DesktopRemoteService service(server, injector);
    service.HandleServerEvent(RemoteServerEvent::ClientConnected("client-1", RemoteClientEndpoint{"10.0.0.2", 55000}));
    service.HandleServerEvent(RemoteServerEvent::ClientRejected(RemoteClientEndpoint{"10.0.0.3", 55001}, "Windows is already controlled"));

    Require(service.State().type == DesktopDashboardStateType::Occupied, "second connection rejection did not enter occupied state");
}

void TestDisconnectReturnsToIdleListening()
{
    RecordingServer server;
    RecordingInjector injector;
    DesktopRemoteService service(server, injector);
    const RemoteClientEndpoint endpoint{"10.0.0.2", 55000};
    service.HandleServerEvent(RemoteServerEvent::ListenerReady(50505));
    service.HandleServerEvent(RemoteServerEvent::ClientConnected("client-1", endpoint));
    service.HandleServerEvent(RemoteServerEvent::ClientDisconnected("client-1", endpoint));

    Require(service.State().type == DesktopDashboardStateType::IdleListening, "disconnect did not return to idle listening");
}

} // namespace

int main()
{
    const std::vector<void (*)()> tests = {
        TestDecodeMoveCommand,
        TestDecodeTapCommand,
        TestDecodeMiddleTapCommand,
        TestEncodeStatusMessage,
        TestStreamDecoderSplitsMultipleJsonObjects,
        TestMoveCommandMapsToMovedCursorPosition,
        TestTapCommandMapsToMouseDownAndMouseUp,
        TestMiddleTapCommandMapsToMouseDownAndMouseUp,
        TestDragLifecycleMapsToDownDragUp,
        TestClientConnectedSendsAckAndStatus,
        TestMdnsPublisherFailureIsReportedButTcpKeepsListening,
        TestStartUsesAdbReversePort,
        TestHeartbeatRepliesToActiveClient,
        TestActiveClientCommandInjectsInput,
        TestRejectedConnectionShowsOccupiedState,
        TestDisconnectReturnsToIdleListening,
    };

    try {
        for (const auto test : tests) {
            test();
        }
        std::cout << tests.size() << " core tests passed\n";
        return EXIT_SUCCESS;
    } catch (const std::exception& error) {
        std::cerr << "Test failed: " << error.what() << "\n";
        return EXIT_FAILURE;
    }
}
