#pragma once

#include "NeoRemote/Core/DesktopRemoteService.hpp"
#include "NeoRemote/Core/JsonMessageStreamDecoder.hpp"
#include "NeoRemote/Core/Protocol.hpp"

#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <unordered_map>

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>

namespace NeoRemote::Windows {

class TcpRemoteServer final : public Core::IRemoteServer {
public:
    using EventHandler = std::function<void(const Core::RemoteServerEvent&)>;

    TcpRemoteServer();
    ~TcpRemoteServer() override;

    void SetEventHandler(EventHandler handler);
    void Start(unsigned short port) override;
    void Stop() override;
    void Send(const Core::ProtocolMessage& message, const std::string& clientId) override;
    void Disconnect(const std::string& clientId) override;

private:
    struct ClientConnection {
        SOCKET socket{INVALID_SOCKET};
        Core::RemoteClientEndpoint endpoint;
    };

    void AcceptLoop(unsigned short port);
    void ReceiveLoop(std::string clientId);
    void Reject(SOCKET socket, const Core::RemoteClientEndpoint& endpoint, const std::string& reason);
    void Emit(const Core::RemoteServerEvent& event);
    static Core::RemoteClientEndpoint EndpointFromSocket(SOCKET socket);
    static std::string MakeClientId();

    Core::ProtocolCodec codec_;
    EventHandler onEvent_;
    std::atomic<bool> running_{false};
    SOCKET listener_{INVALID_SOCKET};
    std::thread acceptThread_;
    std::mutex mutex_;
    std::optional<std::string> activeClientId_;
    std::unordered_map<std::string, std::unique_ptr<ClientConnection>> clients_;
};

} // namespace NeoRemote::Windows
