#pragma once

#include "NeoRemote/Core/Protocol.hpp"

#include <functional>
#include <optional>
#include <set>
#include <string>
#include <vector>

namespace NeoRemote::Core {

struct RemoteClientEndpoint {
    std::string host;
    unsigned short port{0};

    std::string AddressSummary() const;
    bool operator==(const RemoteClientEndpoint& other) const = default;
};

enum class DesktopDashboardStateType {
    ListeningDisabled,
    IdleListening,
    Connected,
    Occupied,
    Error,
};

struct DesktopDashboardState {
    DesktopDashboardStateType type{DesktopDashboardStateType::IdleListening};
    std::optional<RemoteClientEndpoint> endpoint;
    std::string message;

    bool operator==(const DesktopDashboardState& other) const = default;
};

struct DashboardEvent {
    std::string title;
    std::string detail;
};

enum class RemoteClientConnectionStatus {
    Connected,
    Disconnected,
};

struct ConnectedRemoteClient {
    std::string id;
    std::string deviceId;
    RemoteClientEndpoint endpoint;
    std::string displayName;
    std::string platform;
    RemoteClientConnectionStatus status{RemoteClientConnectionStatus::Connected};
    std::string connectedAt;
    std::string lastSeenAt;
    bool isTrusted{false};
};

struct PendingConnectionRequest {
    std::string id;
    std::string deviceId;
    RemoteClientEndpoint endpoint;
    std::string displayName;
    std::string platform;
    std::string requestedAt;
};

enum class ConnectionHistoryEvent {
    Requested,
    Approved,
    Rejected,
    Disconnected,
    Failed,
};

struct ConnectionHistoryEntry {
    std::string deviceId;
    std::string displayName;
    std::string platform;
    std::string endpointSummary;
    ConnectionHistoryEvent event{ConnectionHistoryEvent::Requested};
    std::string timestamp;
    std::string reason;
};

struct ConnectionPermissionPolicy {
    bool autoAllowTrustedDevices{true};
};

enum class RemoteServerEventType {
    ListenerReady,
    ListenerStopped,
    ListenerFailed,
    ClientConnected,
    ClientRejected,
    Command,
    ClientDisconnected,
};

struct RemoteServerEvent {
    RemoteServerEventType type{RemoteServerEventType::ListenerReady};
    std::string clientId;
    RemoteClientEndpoint endpoint;
    RemoteCommand command{RemoteCommand::Heartbeat()};
    unsigned short port{51101};
    std::string message;

    static RemoteServerEvent ListenerReady(unsigned short port);
    static RemoteServerEvent ListenerStopped();
    static RemoteServerEvent ListenerFailed(std::string message);
    static RemoteServerEvent ClientConnected(std::string clientId, RemoteClientEndpoint endpoint);
    static RemoteServerEvent ClientRejected(RemoteClientEndpoint endpoint, std::string reason);
    static RemoteServerEvent Command(std::string clientId, RemoteCommand command);
    static RemoteServerEvent ClientDisconnected(std::string clientId, RemoteClientEndpoint endpoint, std::string errorDescription = "");
};

class IRemoteServer {
public:
    virtual ~IRemoteServer() = default;
    virtual void Start(unsigned short port) = 0;
    virtual void Stop() = 0;
    virtual void Send(const ProtocolMessage& message, const std::string& clientId) = 0;
    virtual void Disconnect(const std::string& clientId) = 0;
};

class IInputInjector {
public:
    virtual ~IInputInjector() = default;
    virtual void Handle(const RemoteCommand& command) = 0;
};

class IMdnsPublisher {
public:
    virtual ~IMdnsPublisher() = default;
    virtual void Start(unsigned short port, const std::string& instanceName) = 0;
    virtual void Stop() = 0;
    virtual std::string LastError() const { return ""; }
};

class DesktopRemoteService {
public:
    DesktopRemoteService(IRemoteServer& server, IInputInjector& injector, IMdnsPublisher* publisher = nullptr);

    void Start();
    void Stop();
    void SetListeningEnabled(bool enabled);
    void DisconnectCurrentSession();
    void ApproveConnection(const std::string& requestId);
    void RejectConnection(const std::string& requestId);
    void DisconnectClient(const std::string& clientId);
    void DisconnectClients(const std::vector<std::string>& clientIds);
    void SetAutoAllowTrustedDevices(bool enabled);
    void HandleServerEvent(const RemoteServerEvent& event);

    const DesktopDashboardState& State() const;
    const std::vector<DashboardEvent>& RecentEvents() const;
    const std::vector<ConnectedRemoteClient>& ConnectedClients() const;
    const std::vector<PendingConnectionRequest>& PendingConnectionRequests() const;
    const std::vector<ConnectionHistoryEntry>& ConnectionHistory() const;
    const ConnectionPermissionPolicy& PermissionPolicy() const;
    bool IsListeningEnabled() const;
    bool IsListening() const;
    unsigned short ListeningPort() const;
    std::optional<RemoteClientEndpoint> ActiveClient() const;

private:
    void AppendEvent(std::string title, std::string detail);
    void HandleClientHello(const std::string& clientId, const RemoteCommand& command);
    void ApproveRequest(const PendingConnectionRequest& request);
    bool ShouldAutoApprove(const std::string& deviceId) const;
    void MarkClientActive(const std::string& clientId);
    void AppendConnectionHistory(const PendingConnectionRequest& request, ConnectionHistoryEvent event, std::string reason = "");
    void AppendConnectionHistory(const ConnectedRemoteClient& client, ConnectionHistoryEvent event, std::string reason = "");
    void RecalculateState();

    IRemoteServer& server_;
    IInputInjector& injector_;
    IMdnsPublisher* publisher_{nullptr};
    DesktopDashboardState state_{};
    std::vector<DashboardEvent> recentEvents_;
    bool isListeningEnabled_{true};
    bool isListening_{false};
    unsigned short listeningPort_{51101};
    std::optional<RemoteClientEndpoint> activeClient_;
    std::optional<std::string> lastActiveClientId_;
    std::vector<ConnectedRemoteClient> connectedClients_;
    std::vector<PendingConnectionRequest> pendingConnectionRequests_;
    std::vector<ConnectionHistoryEntry> connectionHistory_;
    ConnectionPermissionPolicy permissionPolicy_{};
    std::set<std::string> trustedDeviceIds_;
    std::string lastError_;
};

} // namespace NeoRemote::Core
