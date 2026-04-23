#pragma once

#include "MainWindow.g.h"

#include "NeoRemote/Core/DesktopRemoteService.hpp"
#include "NeoRemote/Windows/MdnsPublisher.hpp"
#include "NeoRemote/Windows/TcpRemoteServer.hpp"
#include "NeoRemote/Windows/TrayIcon.hpp"
#include "NeoRemote/Windows/Win32InputInjector.hpp"

#include <memory>

namespace winrt::NeoRemote::App::implementation {

struct MainWindow : MainWindowT<MainWindow> {
    MainWindow();
    ~MainWindow();

    void OnToggleListening(IInspectable const&, Microsoft::UI::Xaml::RoutedEventArgs const&);
    void OnDisconnect(IInspectable const&, Microsoft::UI::Xaml::RoutedEventArgs const&);
    void OnHideToTray(IInspectable const&, Microsoft::UI::Xaml::RoutedEventArgs const&);

private:
    void WireEvents();
    void RefreshUi();
    std::wstring StateText() const;
    HWND WindowHandle() const;
    void InstallWindowProc(HWND hwnd);
    static LRESULT CALLBACK WindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);

    ::NeoRemote::Windows::TcpRemoteServer server_;
    ::NeoRemote::Windows::Win32InputInjector injector_;
    ::NeoRemote::Windows::MdnsPublisher publisher_;
    ::NeoRemote::Core::DesktopRemoteService service_;
    std::unique_ptr<::NeoRemote::Windows::TrayIcon> tray_;
    WNDPROC previousWindowProc_{nullptr};
};

} // namespace winrt::NeoRemote::App::implementation
