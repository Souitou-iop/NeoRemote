#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <thread>

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>

namespace NeoRemote::Windows {

class UdpDiscoveryResponder final {
public:
    using PortProvider = std::function<unsigned short()>;

    UdpDiscoveryResponder();
    ~UdpDiscoveryResponder();

    void Start(unsigned short discoveryPort, PortProvider portProvider);
    void Stop();

private:
    void ReceiveLoop(unsigned short discoveryPort);
    std::string BuildResponse() const;

    std::atomic<bool> running_{false};
    SOCKET socket_{INVALID_SOCKET};
    std::thread thread_;
    PortProvider portProvider_;
};

} // namespace NeoRemote::Windows
