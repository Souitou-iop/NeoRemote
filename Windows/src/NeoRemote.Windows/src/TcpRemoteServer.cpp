#include "NeoRemote/Windows/TcpRemoteServer.hpp"

#include <array>
#include <chrono>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>

#pragma comment(lib, "Ws2_32.lib")

namespace NeoRemote::Windows {
namespace {

class WsaRuntime {
public:
    WsaRuntime()
    {
        WSADATA data{};
        if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
            throw std::runtime_error("WSAStartup failed");
        }
    }

    ~WsaRuntime()
    {
        WSACleanup();
    }
};

WsaRuntime& Runtime()
{
    static WsaRuntime runtime;
    return runtime;
}

void CloseSocket(SOCKET socket)
{
    if (socket != INVALID_SOCKET) {
        shutdown(socket, SD_BOTH);
        closesocket(socket);
    }
}

} // namespace

TcpRemoteServer::TcpRemoteServer()
{
    (void)Runtime();
}

TcpRemoteServer::~TcpRemoteServer()
{
    Stop();
}

void TcpRemoteServer::SetEventHandler(EventHandler handler)
{
    std::scoped_lock lock(mutex_);
    onEvent_ = std::move(handler);
}

void TcpRemoteServer::Start(unsigned short port)
{
    Stop();
    running_ = true;
    acceptThread_ = std::thread([this, port] { AcceptLoop(port); });
}

void TcpRemoteServer::Stop()
{
    running_ = false;
    CloseSocket(listener_);
    listener_ = INVALID_SOCKET;

    {
        std::scoped_lock lock(mutex_);
        for (auto& [_, client] : clients_) {
            CloseSocket(client->socket);
        }
    }

    if (acceptThread_.joinable()) {
        acceptThread_.join();
    }

    {
        std::scoped_lock lock(mutex_);
        clients_.clear();
    }

    Emit(Core::RemoteServerEvent::ListenerStopped());
}

void TcpRemoteServer::Send(const Core::ProtocolMessage& message, const std::string& clientId)
{
    const std::string payload = codec_.EncodeMessage(message);
    std::scoped_lock lock(mutex_);
    const auto it = clients_.find(clientId);
    if (it == clients_.end()) {
        return;
    }
    send(it->second->socket, payload.data(), static_cast<int>(payload.size()), 0);
}

void TcpRemoteServer::Disconnect(const std::string& clientId)
{
    std::scoped_lock lock(mutex_);
    const auto it = clients_.find(clientId);
    if (it != clients_.end()) {
        CloseSocket(it->second->socket);
    }
}

void TcpRemoteServer::AcceptLoop(unsigned short port)
{
    std::vector<unsigned short> candidatePorts{port, 50505, 51102, 51103};
    unsigned short boundPort = 0;
    std::string bindErrors;

    for (const unsigned short candidatePort : candidatePorts) {
        listener_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (listener_ == INVALID_SOCKET) {
            Emit(Core::RemoteServerEvent::ListenerFailed("Could not create TCP socket"));
            return;
        }

        BOOL reuse = TRUE;
        setsockopt(listener_, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));

        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_ANY);
        address.sin_port = htons(candidatePort);

        if (bind(listener_, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == SOCKET_ERROR) {
            bindErrors += "port " + std::to_string(candidatePort) + " failed with WSA " + std::to_string(WSAGetLastError()) + "; ";
            CloseSocket(listener_);
            listener_ = INVALID_SOCKET;
            continue;
        }

        if (listen(listener_, SOMAXCONN) == SOCKET_ERROR) {
            bindErrors += "port " + std::to_string(candidatePort) + " listen failed with WSA " + std::to_string(WSAGetLastError()) + "; ";
            CloseSocket(listener_);
            listener_ = INVALID_SOCKET;
            continue;
        }

        boundPort = candidatePort;
        break;
    }

    if (boundPort == 0) {
        Emit(Core::RemoteServerEvent::ListenerFailed("Could not bind TCP listener: " + bindErrors));
        return;
    }

    Emit(Core::RemoteServerEvent::ListenerReady(boundPort));

    while (running_) {
        SOCKET clientSocket = accept(listener_, nullptr, nullptr);
        if (clientSocket == INVALID_SOCKET) {
            if (running_) {
                Emit(Core::RemoteServerEvent::ListenerFailed("Accept failed"));
            }
            continue;
        }

        BOOL noDelay = TRUE;
        setsockopt(clientSocket, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&noDelay), sizeof(noDelay));

        const auto endpoint = EndpointFromSocket(clientSocket);
        std::string clientId;
        {
            std::scoped_lock lock(mutex_);
            auto connection = std::make_unique<ClientConnection>();
            connection->socket = clientSocket;
            connection->endpoint = endpoint;
            clientId = MakeClientId();
            clients_[clientId] = std::move(connection);
        }

        std::thread([this, clientId] { ReceiveLoop(clientId); }).detach();
        Emit(Core::RemoteServerEvent::ClientConnected(clientId, endpoint));
    }
}

void TcpRemoteServer::ReceiveLoop(std::string clientId)
{
    Core::JsonMessageStreamDecoder decoder;
    std::array<char, 4096> buffer{};
    Core::RemoteClientEndpoint endpoint;

    {
        std::scoped_lock lock(mutex_);
        if (const auto it = clients_.find(clientId); it != clients_.end()) {
            endpoint = it->second->endpoint;
        }
    }

    while (running_) {
        SOCKET socketHandle = INVALID_SOCKET;
        {
            std::scoped_lock lock(mutex_);
            const auto it = clients_.find(clientId);
            if (it == clients_.end()) {
                break;
            }
            socketHandle = it->second->socket;
        }

        const int received = recv(socketHandle, buffer.data(), static_cast<int>(buffer.size()), 0);
        if (received <= 0) {
            break;
        }

        std::vector<std::string> payloads;
        try {
            payloads = decoder.Append(std::string_view(buffer.data(), static_cast<size_t>(received)));
        } catch (const Core::JsonMessageStreamDecoderError& error) {
            Emit(Core::RemoteServerEvent::ClientRejected(endpoint, error.what()));
            break;
        }

        for (const auto& payload : payloads) {
            try {
                Emit(Core::RemoteServerEvent::Command(clientId, codec_.DecodeCommand(payload)));
            } catch (const std::exception& error) {
                Emit(Core::RemoteServerEvent::ClientRejected(endpoint, error.what()));
            }
        }
    }

    {
        std::scoped_lock lock(mutex_);
        clients_.erase(clientId);
    }
    Emit(Core::RemoteServerEvent::ClientDisconnected(clientId, endpoint));
}

void TcpRemoteServer::Reject(SOCKET socket, const Core::RemoteClientEndpoint& endpoint, const std::string& reason)
{
    const std::string payload = codec_.EncodeMessage(Core::ProtocolMessage::Status(reason));
    send(socket, payload.data(), static_cast<int>(payload.size()), 0);
    CloseSocket(socket);
    Emit(Core::RemoteServerEvent::ClientRejected(endpoint, reason));
}

void TcpRemoteServer::Emit(const Core::RemoteServerEvent& event)
{
    EventHandler handler;
    {
        std::scoped_lock lock(mutex_);
        handler = onEvent_;
    }
    if (handler) {
        handler(event);
    }
}

Core::RemoteClientEndpoint TcpRemoteServer::EndpointFromSocket(SOCKET socket)
{
    sockaddr_storage storage{};
    int length = sizeof(storage);
    getpeername(socket, reinterpret_cast<sockaddr*>(&storage), &length);

    char host[NI_MAXHOST]{};
    char service[NI_MAXSERV]{};
    getnameinfo(
        reinterpret_cast<sockaddr*>(&storage),
        length,
        host,
        sizeof(host),
        service,
        sizeof(service),
        NI_NUMERICHOST | NI_NUMERICSERV);

    return Core::RemoteClientEndpoint{host, static_cast<unsigned short>(std::stoi(service))};
}

std::string TcpRemoteServer::MakeClientId()
{
    const auto now = std::chrono::steady_clock::now().time_since_epoch().count();
    std::ostringstream stream;
    stream << "client-" << now;
    return stream.str();
}

} // namespace NeoRemote::Windows
