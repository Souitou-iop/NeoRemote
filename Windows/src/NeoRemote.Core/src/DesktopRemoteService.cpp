#include "NeoRemote/Core/DesktopRemoteService.hpp"

#include <utility>

namespace NeoRemote::Core {

std::string RemoteClientEndpoint::AddressSummary() const
{
    return host + ":" + std::to_string(port);
}

RemoteServerEvent RemoteServerEvent::ListenerReady(unsigned short port)
{
    RemoteServerEvent event;
    event.type = RemoteServerEventType::ListenerReady;
    event.port = port;
    return event;
}

RemoteServerEvent RemoteServerEvent::ListenerStopped()
{
    RemoteServerEvent event;
    event.type = RemoteServerEventType::ListenerStopped;
    return event;
}

RemoteServerEvent RemoteServerEvent::ListenerFailed(std::string message)
{
    RemoteServerEvent event;
    event.type = RemoteServerEventType::ListenerFailed;
    event.message = std::move(message);
    return event;
}

RemoteServerEvent RemoteServerEvent::ClientConnected(std::string clientId, RemoteClientEndpoint endpoint)
{
    RemoteServerEvent event;
    event.type = RemoteServerEventType::ClientConnected;
    event.clientId = std::move(clientId);
    event.endpoint = std::move(endpoint);
    return event;
}

RemoteServerEvent RemoteServerEvent::ClientRejected(RemoteClientEndpoint endpoint, std::string reason)
{
    RemoteServerEvent event;
    event.type = RemoteServerEventType::ClientRejected;
    event.endpoint = std::move(endpoint);
    event.message = std::move(reason);
    return event;
}

RemoteServerEvent RemoteServerEvent::Command(std::string clientId, RemoteCommand command)
{
    RemoteServerEvent event;
    event.type = RemoteServerEventType::Command;
    event.clientId = std::move(clientId);
    event.command = command;
    return event;
}

RemoteServerEvent RemoteServerEvent::ClientDisconnected(std::string clientId, RemoteClientEndpoint endpoint, std::string errorDescription)
{
    RemoteServerEvent event;
    event.type = RemoteServerEventType::ClientDisconnected;
    event.clientId = std::move(clientId);
    event.endpoint = std::move(endpoint);
    event.message = std::move(errorDescription);
    return event;
}

DesktopRemoteService::DesktopRemoteService(IRemoteServer& server, IInputInjector& injector, IMdnsPublisher* publisher)
    : server_(server), injector_(injector), publisher_(publisher)
{
    RecalculateState();
}

void DesktopRemoteService::Start()
{
    if (!isListeningEnabled_) {
        RecalculateState();
        return;
    }
    server_.Start(listeningPort_);
}

void DesktopRemoteService::Stop()
{
    server_.Stop();
    if (publisher_) {
        publisher_->Stop();
    }
    isListening_ = false;
    activeClientId_.reset();
    activeClient_.reset();
    occupiedEndpoint_.reset();
    RecalculateState();
}

void DesktopRemoteService::SetListeningEnabled(bool enabled)
{
    if (isListeningEnabled_ == enabled) {
        return;
    }

    isListeningEnabled_ = enabled;
    if (enabled) {
        AppendEvent("已开启监听", "NeoRemote 现在可以接收控制端连接");
        Start();
    } else {
        AppendEvent("已停止监听", "NeoRemote 已停止接收控制端连接");
        Stop();
    }
    RecalculateState();
}

void DesktopRemoteService::DisconnectCurrentSession()
{
    if (activeClientId_) {
        server_.Disconnect(*activeClientId_);
    }
}

void DesktopRemoteService::HandleServerEvent(const RemoteServerEvent& event)
{
    switch (event.type) {
    case RemoteServerEventType::ListenerReady:
        isListening_ = true;
        listeningPort_ = event.port;
        lastError_.clear();
        if (publisher_) {
            publisher_->Start(event.port, "NeoRemote Windows");
            if (!publisher_->LastError().empty()) {
                AppendEvent("发现服务发布失败", publisher_->LastError());
                AppendEvent("服务已启动", "TCP 正在监听端口 " + std::to_string(event.port) + "，可手动连接");
                break;
            }
        }
        AppendEvent("服务已启动", "已发布局域网发现服务，TCP 正在监听端口 " + std::to_string(event.port));
        break;

    case RemoteServerEventType::ListenerStopped:
        isListening_ = false;
        break;

    case RemoteServerEventType::ListenerFailed:
        isListening_ = false;
        lastError_ = event.message;
        AppendEvent("监听失败", event.message);
        break;

    case RemoteServerEventType::ClientConnected:
        activeClientId_ = event.clientId;
        activeClient_ = event.endpoint;
        occupiedEndpoint_.reset();
        AppendEvent("设备已连接", event.endpoint.AddressSummary());
        server_.Send(ProtocolMessage::Ack(), event.clientId);
        server_.Send(ProtocolMessage::Status("已连接到 Windows，可以开始控制"), event.clientId);
        break;

    case RemoteServerEventType::ClientRejected:
        occupiedEndpoint_ = activeClient_.value_or(event.endpoint);
        AppendEvent("连接已拒绝", event.endpoint.AddressSummary() + " - " + event.message);
        break;

    case RemoteServerEventType::Command:
        if (!activeClientId_ || event.clientId != *activeClientId_) {
            break;
        }
        if (event.command.type == RemoteCommandType::Heartbeat) {
            server_.Send(ProtocolMessage::Heartbeat(), event.clientId);
            break;
        }
        injector_.Handle(event.command);
        occupiedEndpoint_.reset();
        break;

    case RemoteServerEventType::ClientDisconnected:
        if (activeClientId_ && event.clientId == *activeClientId_) {
            AppendEvent("设备已断开", event.endpoint.AddressSummary());
            activeClientId_.reset();
            activeClient_.reset();
            occupiedEndpoint_.reset();
        }
        break;
    }

    RecalculateState();
}

const DesktopDashboardState& DesktopRemoteService::State() const
{
    return state_;
}

const std::vector<DashboardEvent>& DesktopRemoteService::RecentEvents() const
{
    return recentEvents_;
}

bool DesktopRemoteService::IsListeningEnabled() const
{
    return isListeningEnabled_;
}

bool DesktopRemoteService::IsListening() const
{
    return isListening_;
}

unsigned short DesktopRemoteService::ListeningPort() const
{
    return listeningPort_;
}

std::optional<RemoteClientEndpoint> DesktopRemoteService::ActiveClient() const
{
    return activeClient_;
}

void DesktopRemoteService::AppendEvent(std::string title, std::string detail)
{
    recentEvents_.insert(recentEvents_.begin(), DashboardEvent{std::move(title), std::move(detail)});
    if (recentEvents_.size() > 20) {
        recentEvents_.resize(20);
    }
}

void DesktopRemoteService::RecalculateState()
{
    if (!lastError_.empty()) {
        state_ = DesktopDashboardState{DesktopDashboardStateType::Error, std::nullopt, lastError_};
        return;
    }
    if (!isListeningEnabled_) {
        state_ = DesktopDashboardState{DesktopDashboardStateType::ListeningDisabled, std::nullopt, ""};
        return;
    }
    if (occupiedEndpoint_) {
        state_ = DesktopDashboardState{DesktopDashboardStateType::Occupied, occupiedEndpoint_, ""};
        return;
    }
    if (activeClient_) {
        state_ = DesktopDashboardState{DesktopDashboardStateType::Connected, activeClient_, ""};
        return;
    }
    state_ = DesktopDashboardState{DesktopDashboardStateType::IdleListening, std::nullopt, ""};
}

} // namespace NeoRemote::Core
