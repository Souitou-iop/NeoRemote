#pragma once

#include "NeoRemote/Core/DesktopRemoteService.hpp"

#include <string>
#include <mutex>

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <windns.h>

namespace NeoRemote::Windows {

class MdnsPublisher final : public Core::IMdnsPublisher {
public:
    MdnsPublisher();
    ~MdnsPublisher() override;

    void Start(unsigned short port, const std::string& instanceName) override;
    void Stop() override;
    std::string LastError() const override;

private:
    static void WINAPI RegisterComplete(DWORD status, PVOID context, PDNS_SERVICE_INSTANCE instance);
    void SetLastError(std::string message);

    DNS_SERVICE_CANCEL cancel_{};
    DNS_SERVICE_INSTANCE serviceInstance_{};
    DNS_SERVICE_REGISTER_REQUEST request_{};
    bool isRegistered_{false};
    std::wstring instanceName_;
    std::wstring serviceType_;
    mutable std::mutex mutex_;
    std::string lastError_;
};

} // namespace NeoRemote::Windows
