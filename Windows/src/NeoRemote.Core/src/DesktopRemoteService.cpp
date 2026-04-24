#include "NeoRemote/Core/DesktopRemoteService.hpp"

#include <algorithm>
#include <chrono>
#include <sstream>
#include <utility>

namespace NeoRemote::Core {
namespace {

std::string NowText()
{
    const auto now = std::chrono::system_clock::now().time_since_epoch().count();
    return std::to_string(now);
}

std::string TemporaryDeviceId(const RemoteClientEndpoint& endpoint)
{
    return "endpoint:" + endpoint.AddressSummary();
}

std::string RequestSummary(const PendingConnectionRequest& request)
{
    const std::string name = request.displayName.empty() ? request.endpoint.host : request.displayName;
    return name + " - " + request.endpoint.AddressSummary();
}

std::string ClientSummary(const ConnectedRemoteClient& client)
{
    return client.displayName + " - " + client.endpoint.AddressSummary();
}

} // namespace

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
    for (const auto& request : pendingConnectionRequests_) {
        AppendConnectionHistory(request, ConnectionHistoryEvent::Disconnected, "停止监听");
    }
    for (const auto& client : connectedClients_) {
        AppendConnectionHistory(client, ConnectionHistoryEvent::Disconnected, "停止监听");
    }
    pendingConnectionRequests_.clear();
    connectedClients_.clear();
    lastActiveClientId_.reset();
    activeClient_.reset();
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
    if (lastActiveClientId_) {
        DisconnectClient(*lastActiveClientId_);
    } else if (!connectedClients_.empty()) {
        DisconnectClient(connectedClients_.back().id);
    }
}

void DesktopRemoteService::ApproveConnection(const std::string& requestId)
{
    const auto it = std::find_if(pendingConnectionRequests_.begin(), pendingConnectionRequests_.end(), [&](const auto& request) {
        return request.id == requestId;
    });
    if (it == pendingConnectionRequests_.end()) {
        return;
    }
    const auto request = *it;
    ApproveRequest(request);
    RecalculateState();
}

void DesktopRemoteService::RejectConnection(const std::string& requestId)
{
    const auto it = std::find_if(pendingConnectionRequests_.begin(), pendingConnectionRequests_.end(), [&](const auto& request) {
        return request.id == requestId;
    });
    if (it == pendingConnectionRequests_.end()) {
        return;
    }

    const auto request = *it;
    pendingConnectionRequests_.erase(it);
    AppendConnectionHistory(request, ConnectionHistoryEvent::Rejected, "用户拒绝");
    AppendEvent("连接已拒绝", RequestSummary(request));
    server_.Send(ProtocolMessage::Status("连接已被 Windows 拒绝"), request.id);
    server_.Disconnect(request.id);
    RecalculateState();
}

void DesktopRemoteService::DisconnectClient(const std::string& clientId)
{
    if (std::any_of(connectedClients_.begin(), connectedClients_.end(), [&](const auto& client) { return client.id == clientId; })) {
        server_.Disconnect(clientId);
    }
}

void DesktopRemoteService::DisconnectClients(const std::vector<std::string>& clientIds)
{
    for (const auto& clientId : clientIds) {
        DisconnectClient(clientId);
    }
}

void DesktopRemoteService::SetAutoAllowTrustedDevices(bool enabled)
{
    if (permissionPolicy_.autoAllowTrustedDevices == enabled) {
        return;
    }
    permissionPolicy_.autoAllowTrustedDevices = enabled;
    AppendEvent(enabled ? "已开启历史设备自动允许" : "已关闭历史设备自动允许",
        enabled ? "已允许过的移动端下次会自动连接" : "每次连接都需要在 Windows 端确认");
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
    {
        PendingConnectionRequest request;
        request.id = event.clientId;
        request.deviceId = TemporaryDeviceId(event.endpoint);
        request.endpoint = event.endpoint;
        request.requestedAt = NowText();
        pendingConnectionRequests_.push_back(request);
        AppendConnectionHistory(request, ConnectionHistoryEvent::Requested);
        AppendEvent("收到连接请求", RequestSummary(request));
        if (ShouldAutoApprove(request.deviceId)) {
            ApproveRequest(request);
        } else {
            server_.Send(ProtocolMessage::Status("正在等待 Windows 允许连接"), event.clientId);
        }
        break;
    }

    case RemoteServerEventType::ClientRejected:
        AppendEvent("连接已拒绝", event.endpoint.AddressSummary() + " - " + event.message);
        break;

    case RemoteServerEventType::Command:
        if (event.command.type == RemoteCommandType::ClientHello) {
            HandleClientHello(event.clientId, event.command);
            break;
        }
        if (std::none_of(connectedClients_.begin(), connectedClients_.end(), [&](const auto& client) { return client.id == event.clientId; })) {
            break;
        }
        MarkClientActive(event.clientId);
        if (event.command.type == RemoteCommandType::Heartbeat) {
            server_.Send(ProtocolMessage::Heartbeat(), event.clientId);
            break;
        }
        injector_.Handle(event.command);
        break;

    case RemoteServerEventType::ClientDisconnected:
    {
        const auto pendingIt = std::find_if(pendingConnectionRequests_.begin(), pendingConnectionRequests_.end(), [&](const auto& request) {
            return request.id == event.clientId;
        });
        if (pendingIt != pendingConnectionRequests_.end()) {
            AppendConnectionHistory(*pendingIt, ConnectionHistoryEvent::Disconnected, event.message);
            pendingConnectionRequests_.erase(pendingIt);
        }
        const auto clientIt = std::find_if(connectedClients_.begin(), connectedClients_.end(), [&](const auto& client) {
            return client.id == event.clientId;
        });
        if (clientIt != connectedClients_.end()) {
            AppendConnectionHistory(*clientIt, ConnectionHistoryEvent::Disconnected, event.message);
            connectedClients_.erase(clientIt);
        }
        if (lastActiveClientId_ == event.clientId) {
            lastActiveClientId_ = connectedClients_.empty() ? std::optional<std::string>{} : std::optional<std::string>{connectedClients_.back().id};
        }
        activeClient_ = connectedClients_.empty() ? std::optional<RemoteClientEndpoint>{} : std::optional<RemoteClientEndpoint>{connectedClients_.back().endpoint};
        AppendEvent("设备已断开", event.endpoint.AddressSummary());
        break;
    }
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

const std::vector<ConnectedRemoteClient>& DesktopRemoteService::ConnectedClients() const
{
    return connectedClients_;
}

const std::vector<PendingConnectionRequest>& DesktopRemoteService::PendingConnectionRequests() const
{
    return pendingConnectionRequests_;
}

const std::vector<ConnectionHistoryEntry>& DesktopRemoteService::ConnectionHistory() const
{
    return connectionHistory_;
}

const ConnectionPermissionPolicy& DesktopRemoteService::PermissionPolicy() const
{
    return permissionPolicy_;
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

void DesktopRemoteService::HandleClientHello(const std::string& clientId, const RemoteCommand& command)
{
    const auto pendingIt = std::find_if(pendingConnectionRequests_.begin(), pendingConnectionRequests_.end(), [&](const auto& request) {
        return request.id == clientId;
    });
    if (pendingIt != pendingConnectionRequests_.end()) {
        pendingIt->deviceId = command.clientId.empty() ? pendingIt->deviceId : command.clientId;
        pendingIt->displayName = command.displayName;
        pendingIt->platform = command.platform;
        AppendEvent("连接请求已识别", RequestSummary(*pendingIt));
        if (ShouldAutoApprove(pendingIt->deviceId)) {
            const auto request = *pendingIt;
            ApproveRequest(request);
        }
        RecalculateState();
        return;
    }

    const auto clientIt = std::find_if(connectedClients_.begin(), connectedClients_.end(), [&](const auto& client) {
        return client.id == clientId;
    });
    if (clientIt == connectedClients_.end()) {
        return;
    }

    if (clientIt->isTrusted && !command.clientId.empty()) {
        trustedDeviceIds_.insert(command.clientId);
    }
    clientIt->deviceId = command.clientId.empty() ? clientIt->deviceId : command.clientId;
    clientIt->displayName = command.displayName.empty() ? clientIt->displayName : command.displayName;
    clientIt->platform = command.platform;
    clientIt->isTrusted = trustedDeviceIds_.find(clientIt->deviceId) != trustedDeviceIds_.end();
    MarkClientActive(clientId);
    RecalculateState();
}

void DesktopRemoteService::ApproveRequest(const PendingConnectionRequest& request)
{
    pendingConnectionRequests_.erase(
        std::remove_if(pendingConnectionRequests_.begin(), pendingConnectionRequests_.end(), [&](const auto& item) {
            return item.id == request.id;
        }),
        pendingConnectionRequests_.end());

    trustedDeviceIds_.insert(request.deviceId);

    ConnectedRemoteClient client;
    client.id = request.id;
    client.deviceId = request.deviceId;
    client.endpoint = request.endpoint;
    client.displayName = request.displayName.empty() ? request.endpoint.host : request.displayName;
    client.platform = request.platform;
    client.connectedAt = NowText();
    client.lastSeenAt = client.connectedAt;
    client.isTrusted = true;

    connectedClients_.erase(
        std::remove_if(connectedClients_.begin(), connectedClients_.end(), [&](const auto& item) {
            return item.id == client.id;
        }),
        connectedClients_.end());
    connectedClients_.push_back(client);
    MarkClientActive(client.id);
    AppendConnectionHistory(client, ConnectionHistoryEvent::Approved);
    AppendEvent("设备已允许", ClientSummary(client));
    server_.Send(ProtocolMessage::Ack(), client.id);
    server_.Send(ProtocolMessage::Status("已连接到 Windows，可以开始控制"), client.id);
}

bool DesktopRemoteService::ShouldAutoApprove(const std::string& deviceId) const
{
    return permissionPolicy_.autoAllowTrustedDevices && trustedDeviceIds_.find(deviceId) != trustedDeviceIds_.end();
}

void DesktopRemoteService::MarkClientActive(const std::string& clientId)
{
    lastActiveClientId_ = clientId;
    const auto it = std::find_if(connectedClients_.begin(), connectedClients_.end(), [&](const auto& client) {
        return client.id == clientId;
    });
    if (it != connectedClients_.end()) {
        it->lastSeenAt = NowText();
        activeClient_ = it->endpoint;
    }
}

void DesktopRemoteService::AppendConnectionHistory(const PendingConnectionRequest& request, ConnectionHistoryEvent event, std::string reason)
{
    ConnectionHistoryEntry entry;
    entry.deviceId = request.deviceId;
    entry.displayName = request.displayName.empty() ? request.endpoint.host : request.displayName;
    entry.platform = request.platform;
    entry.endpointSummary = request.endpoint.AddressSummary();
    entry.event = event;
    entry.timestamp = NowText();
    entry.reason = std::move(reason);
    connectionHistory_.insert(connectionHistory_.begin(), std::move(entry));
    if (connectionHistory_.size() > 50) {
        connectionHistory_.resize(50);
    }
}

void DesktopRemoteService::AppendConnectionHistory(const ConnectedRemoteClient& client, ConnectionHistoryEvent event, std::string reason)
{
    ConnectionHistoryEntry entry;
    entry.deviceId = client.deviceId;
    entry.displayName = client.displayName;
    entry.platform = client.platform;
    entry.endpointSummary = client.endpoint.AddressSummary();
    entry.event = event;
    entry.timestamp = NowText();
    entry.reason = std::move(reason);
    connectionHistory_.insert(connectionHistory_.begin(), std::move(entry));
    if (connectionHistory_.size() > 50) {
        connectionHistory_.resize(50);
    }
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
    if (activeClient_) {
        state_ = DesktopDashboardState{DesktopDashboardStateType::Connected, activeClient_, ""};
        return;
    }
    state_ = DesktopDashboardState{DesktopDashboardStateType::IdleListening, std::nullopt, ""};
}

} // namespace NeoRemote::Core
