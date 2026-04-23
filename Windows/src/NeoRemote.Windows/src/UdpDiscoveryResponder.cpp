#include "NeoRemote/Windows/UdpDiscoveryResponder.hpp"

#include <array>
#include <chrono>
#include <stdexcept>
#include <utility>

#include <ws2tcpip.h>

#pragma comment(lib, "Ws2_32.lib")

namespace NeoRemote::Windows {
namespace {

constexpr char DiscoveryRequest[] = "NEOREMOTE_DISCOVER_V1";

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
        closesocket(socket);
    }
}

} // namespace

UdpDiscoveryResponder::UdpDiscoveryResponder()
{
    (void)Runtime();
}

UdpDiscoveryResponder::~UdpDiscoveryResponder()
{
    Stop();
}

void UdpDiscoveryResponder::Start(unsigned short discoveryPort, PortProvider portProvider)
{
    Stop();
    portProvider_ = std::move(portProvider);
    running_ = true;
    thread_ = std::thread([this, discoveryPort] { ReceiveLoop(discoveryPort); });
}

void UdpDiscoveryResponder::Stop()
{
    running_ = false;
    CloseSocket(socket_);
    socket_ = INVALID_SOCKET;
    if (thread_.joinable()) {
        thread_.join();
    }
}

void UdpDiscoveryResponder::ReceiveLoop(unsigned short discoveryPort)
{
    socket_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socket_ == INVALID_SOCKET) {
        return;
    }

    BOOL reuse = TRUE;
    setsockopt(socket_, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));
    BOOL broadcast = TRUE;
    setsockopt(socket_, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&broadcast), sizeof(broadcast));

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(discoveryPort);

    if (bind(socket_, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == SOCKET_ERROR) {
        CloseSocket(socket_);
        socket_ = INVALID_SOCKET;
        return;
    }

    std::array<char, 512> buffer{};
    while (running_) {
        sockaddr_in remote{};
        int remoteLength = sizeof(remote);
        const int received = recvfrom(
            socket_,
            buffer.data(),
            static_cast<int>(buffer.size() - 1),
            0,
            reinterpret_cast<sockaddr*>(&remote),
            &remoteLength);
        if (received <= 0) {
            continue;
        }

        buffer[static_cast<size_t>(received)] = '\0';
        const std::string request(buffer.data(), static_cast<size_t>(received));
        if (request.find(DiscoveryRequest) == std::string::npos) {
            continue;
        }

        const std::string response = BuildResponse();
        if (response.empty()) {
            continue;
        }

        sendto(
            socket_,
            response.data(),
            static_cast<int>(response.size()),
            0,
            reinterpret_cast<sockaddr*>(&remote),
            remoteLength);
    }
}

std::string UdpDiscoveryResponder::BuildResponse() const
{
    const unsigned short port = portProvider_ ? portProvider_() : 0;
    if (port == 0) {
        return "";
    }

    return "NEOREMOTE_DESKTOP_V1\n"
        "name=NeoRemote Windows\n"
        "platform=windows\n"
        "port=" + std::to_string(port) + "\n";
}

} // namespace NeoRemote::Windows
