#include "NeoRemote/Windows/MdnsPublisher.hpp"

#include <string>
#include <utility>

#pragma comment(lib, "Dnsapi.lib")

namespace NeoRemote::Windows {
namespace {

std::wstring ToWide(const std::string& value)
{
    if (value.empty()) {
        return L"";
    }
    const int length = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    std::wstring wide(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), wide.data(), length);
    return wide;
}

std::string FormatDnsStatus(DWORD status)
{
    return "DnsServiceRegister callback returned status " + std::to_string(status);
}

} // namespace

MdnsPublisher::MdnsPublisher()
    : serviceType_(L"._neoremote._tcp.local")
{
}

MdnsPublisher::~MdnsPublisher()
{
    Stop();
}

void MdnsPublisher::Start(unsigned short port, const std::string& instanceName)
{
    Stop();
    instanceName_ = ToWide(instanceName) + serviceType_;
    SetLastError("");

    serviceInstance_ = DNS_SERVICE_INSTANCE{};
    serviceInstance_.pszInstanceName = instanceName_.data();
    serviceInstance_.pszHostName = nullptr;
    serviceInstance_.ip4Address = nullptr;
    serviceInstance_.ip6Address = nullptr;
    serviceInstance_.wPort = htons(port);
    serviceInstance_.wPriority = 0;
    serviceInstance_.wWeight = 0;
    serviceInstance_.dwPropertyCount = 0;
    serviceInstance_.keys = nullptr;
    serviceInstance_.values = nullptr;

    request_ = DNS_SERVICE_REGISTER_REQUEST{};
    request_.Version = DNS_QUERY_REQUEST_VERSION1;
    request_.InterfaceIndex = 0;
    request_.pServiceInstance = &serviceInstance_;
    request_.pRegisterCompletionCallback = MdnsPublisher::RegisterComplete;
    request_.pQueryContext = this;
    request_.hCredentials = nullptr;
    request_.unicastEnabled = FALSE;

    const DNS_STATUS status = DnsServiceRegister(&request_, &cancel_);
    if (status != ERROR_SUCCESS) {
        SetLastError("DnsServiceRegister failed with status " + std::to_string(status));
        isRegistered_ = false;
        return;
    }
    isRegistered_ = true;
}

void MdnsPublisher::Stop()
{
    if (isRegistered_) {
        DnsServiceDeRegister(&request_, &cancel_);
    }
    isRegistered_ = false;
    cancel_ = DNS_SERVICE_CANCEL{};
    request_ = DNS_SERVICE_REGISTER_REQUEST{};
    serviceInstance_ = DNS_SERVICE_INSTANCE{};
}

std::string MdnsPublisher::LastError() const
{
    std::lock_guard lock(mutex_);
    return lastError_;
}

void WINAPI MdnsPublisher::RegisterComplete(DWORD status, PVOID context, PDNS_SERVICE_INSTANCE)
{
    if (status == ERROR_SUCCESS || context == nullptr) {
        return;
    }
    auto* publisher = static_cast<MdnsPublisher*>(context);
    publisher->SetLastError(FormatDnsStatus(status));
}

void MdnsPublisher::SetLastError(std::string message)
{
    std::lock_guard lock(mutex_);
    lastError_ = std::move(message);
}

} // namespace NeoRemote::Windows
