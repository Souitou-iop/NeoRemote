#include "pch.h"
#include "MainWindow.xaml.h"

#include <microsoft.ui.xaml.window.h>
#include <winrt/Microsoft.UI.Xaml.Controls.h>
#include <winrt/Microsoft.UI.Xaml.h>

namespace winrt::NeoRemote::App::implementation {
namespace {

std::wstring ToWide(const std::string& value)
{
    return std::wstring(value.begin(), value.end());
}

} // namespace

MainWindow::MainWindow()
    : service_(server_, injector_, &publisher_)
{
    InitializeComponent();
    WireEvents();
    const HWND hwnd = WindowHandle();
    InstallWindowProc(hwnd);
    tray_ = std::make_unique<::NeoRemote::Windows::TrayIcon>(hwnd);
    tray_->onOpen = [this] {
        ShowWindow(WindowHandle(), SW_SHOW);
        this->Activate();
    };
    tray_->onToggleListening = [this] {
        service_.SetListeningEnabled(!service_.IsListeningEnabled());
        RefreshUi();
    };
    tray_->onDisconnect = [this] {
        service_.DisconnectCurrentSession();
        RefreshUi();
    };
    tray_->onExit = [this] {
        service_.Stop();
        tray_->Hide();
        this->Close();
    };
    tray_->Show(L"NeoRemote Windows - 正在监听");
    service_.Start();
    RefreshUi();
}

MainWindow::~MainWindow()
{
    if (const HWND hwnd = WindowHandle(); hwnd && previousWindowProc_) {
        SetWindowLongPtr(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(previousWindowProc_));
        SetWindowLongPtr(hwnd, GWLP_USERDATA, 0);
    }
    service_.Stop();
}

void MainWindow::OnToggleListening(IInspectable const&, Microsoft::UI::Xaml::RoutedEventArgs const&)
{
    service_.SetListeningEnabled(!service_.IsListeningEnabled());
    RefreshUi();
}

void MainWindow::OnDisconnect(IInspectable const&, Microsoft::UI::Xaml::RoutedEventArgs const&)
{
    service_.DisconnectCurrentSession();
    RefreshUi();
}

void MainWindow::OnHideToTray(IInspectable const&, Microsoft::UI::Xaml::RoutedEventArgs const&)
{
    ShowWindow(WindowHandle(), SW_HIDE);
}

void MainWindow::WireEvents()
{
    server_.SetEventHandler([this](const ::NeoRemote::Core::RemoteServerEvent& event) {
        service_.HandleServerEvent(event);
        this->DispatcherQueue().TryEnqueue([this] { RefreshUi(); });
    });
}

void MainWindow::RefreshUi()
{
    StateBadge().Text(winrt::hstring(StateText()));
    ListeningButton().Content(winrt::box_value(service_.IsListeningEnabled() ? L"停止监听" : L"开始监听"));
    if (tray_) {
        tray_->UpdateTooltip(L"NeoRemote Windows - " + StateText());
    }

    EventsList().Items().Clear();
    for (const auto& event : service_.RecentEvents()) {
        EventsList().Items().Append(winrt::box_value(ToWide(event.title + " - " + event.detail)));
    }
}

std::wstring MainWindow::StateText() const
{
    using ::NeoRemote::Core::DesktopDashboardStateType;
    const auto& state = service_.State();
    switch (state.type) {
    case DesktopDashboardStateType::ListeningDisabled:
        return L"监听已关闭";
    case DesktopDashboardStateType::IdleListening:
        return L"正在监听端口 51101";
    case DesktopDashboardStateType::Connected:
        return state.endpoint ? ToWide("已连接设备：" + state.endpoint->AddressSummary()) : L"已连接";
    case DesktopDashboardStateType::Occupied:
        return L"当前正被其他设备控制";
    case DesktopDashboardStateType::Error:
        return ToWide(state.message);
    }
    return L"未知状态";
}

HWND MainWindow::WindowHandle() const
{
    HWND hwnd{};
    this->try_as<::IWindowNative>()->get_WindowHandle(&hwnd);
    return hwnd;
}

void MainWindow::InstallWindowProc(HWND hwnd)
{
    SetWindowLongPtr(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(this));
    previousWindowProc_ = reinterpret_cast<WNDPROC>(SetWindowLongPtr(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&MainWindow::WindowProc)));
}

LRESULT CALLBACK MainWindow::WindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam)
{
    auto* self = reinterpret_cast<MainWindow*>(GetWindowLongPtr(hwnd, GWLP_USERDATA));
    if (self && message == ::NeoRemote::Windows::TrayIcon::CallbackMessage) {
        if (LOWORD(lParam) == WM_RBUTTONUP || LOWORD(lParam) == WM_CONTEXTMENU) {
            POINT point{};
            GetCursorPos(&point);
            self->tray_->ShowContextMenu(point, self->service_.IsListeningEnabled());
            return 0;
        }
        if (LOWORD(lParam) == WM_LBUTTONDBLCLK) {
            ShowWindow(self->WindowHandle(), SW_SHOW);
            self->Activate();
            return 0;
        }
    }

    if (self && self->previousWindowProc_) {
        return CallWindowProc(self->previousWindowProc_, hwnd, message, wParam, lParam);
    }
    return DefWindowProc(hwnd, message, wParam, lParam);
}

} // namespace winrt::NeoRemote::App::implementation
