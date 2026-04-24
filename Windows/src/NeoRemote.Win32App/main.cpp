#ifndef NOMINMAX
#define NOMINMAX
#endif

#include "NeoRemote/Core/DesktopRemoteService.hpp"
#include "NeoRemote/Windows/MdnsPublisher.hpp"
#include "NeoRemote/Windows/TcpRemoteServer.hpp"
#include "NeoRemote/Windows/TrayIcon.hpp"
#include "NeoRemote/Windows/UdpDiscoveryResponder.hpp"
#include "NeoRemote/Windows/Win32InputInjector.hpp"
#include "resources/resource.h"

#include <dwmapi.h>
#include <mmsystem.h>
#include <windows.h>
#include <windowsx.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <memory>
#include <string>
#include <vector>

namespace {

constexpr wchar_t WindowClassName[] = L"NeoRemoteWin32ReceiverWindow";
constexpr UINT RefreshUiMessage = WM_APP + 900;
#ifndef DWMWA_USE_IMMERSIVE_DARK_MODE
#define DWMWA_USE_IMMERSIVE_DARK_MODE 20
#endif

enum class Action {
    ToggleListening,
    Disconnect,
    DisconnectAll,
    ApproveFirstRequest,
    RejectFirstRequest,
    ToggleTrustedAutoAllow,
    Hide,
    Exit,
};

struct UiButton {
    RECT rect{};
    std::wstring label;
    Action action{Action::Hide};
    bool primary{false};
    bool enabled{true};
};

std::unique_ptr<NeoRemote::Windows::TcpRemoteServer> server;
std::unique_ptr<NeoRemote::Windows::UdpDiscoveryResponder> discoveryResponder;
std::unique_ptr<NeoRemote::Windows::Win32InputInjector> injector;
std::unique_ptr<NeoRemote::Windows::MdnsPublisher> publisher;
std::unique_ptr<NeoRemote::Core::DesktopRemoteService> service;
std::unique_ptr<NeoRemote::Windows::TrayIcon> tray;
std::vector<UiButton> buttons;
HFONT titleFont{};
HFONT headingFont{};
HFONT bodyFont{};
HFONT smallFont{};
UINT currentDpi{96};
std::wstring preferredFontFace = L"Segoe UI Variable";
NeoRemote::Windows::ThemeMode themeMode = NeoRemote::Windows::ThemeMode::FollowSystem;
bool cachedSystemDarkMode = false;
std::atomic<unsigned short> discoveryTcpPort{0};

struct ThemePalette {
    COLORREF windowBackground;
    COLORREF surfaceBackground;
    COLORREF surfaceBorder;
    COLORREF titleText;
    COLORREF primaryText;
    COLORREF secondaryText;
    COLORREF mutedText;
    COLORREF buttonDefaultFill;
    COLORREF buttonDefaultStroke;
    COLORREF buttonDefaultText;
    COLORREF buttonDisabledFill;
    COLORREF buttonDisabledStroke;
    COLORREF buttonDisabledText;
    COLORREF buttonPrimaryFill;
    COLORREF buttonPrimaryText;
    COLORREF runningBadgeFill;
    COLORREF runningBadgeText;
    COLORREF stoppedBadgeFill;
    COLORREF stoppedBadgeText;
};

COLORREF Rgb(int r, int g, int b)
{
    return RGB(r, g, b);
}

bool QuerySystemDarkMode()
{
    DWORD appsUseLightTheme = 1;
    DWORD size = sizeof(appsUseLightTheme);
    const LSTATUS status = RegGetValueW(
        HKEY_CURRENT_USER,
        L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        L"AppsUseLightTheme",
        RRF_RT_REG_DWORD,
        nullptr,
        &appsUseLightTheme,
        &size);
    if (status != ERROR_SUCCESS) {
        return false;
    }
    return appsUseLightTheme == 0;
}

bool IsDarkModeActive()
{
    return themeMode == NeoRemote::Windows::ThemeMode::Dark
        || (themeMode == NeoRemote::Windows::ThemeMode::FollowSystem && cachedSystemDarkMode);
}

ThemePalette Palette()
{
    if (IsDarkModeActive()) {
        return ThemePalette{
            Rgb(0, 0, 0),
            Rgb(10, 10, 10),
            Rgb(38, 38, 38),
            Rgb(255, 255, 255),
            Rgb(245, 245, 245),
            Rgb(163, 163, 163),
            Rgb(115, 115, 115),
            Rgb(12, 12, 12),
            Rgb(56, 56, 56),
            Rgb(245, 245, 245),
            Rgb(8, 8, 8),
            Rgb(32, 32, 32),
            Rgb(94, 94, 94),
            Rgb(36, 99, 235),
            Rgb(255, 255, 255),
            Rgb(5, 46, 22),
            Rgb(187, 247, 208),
            Rgb(69, 10, 10),
            Rgb(254, 202, 202),
        };
    }

    return ThemePalette{
        Rgb(246, 248, 251),
        Rgb(255, 255, 255),
        Rgb(226, 232, 240),
        Rgb(17, 24, 39),
        Rgb(31, 41, 55),
        Rgb(99, 109, 122),
        Rgb(107, 114, 128),
        Rgb(255, 255, 255),
        Rgb(210, 216, 224),
        Rgb(28, 32, 38),
        Rgb(241, 243, 246),
        Rgb(226, 232, 240),
        Rgb(139, 148, 158),
        Rgb(0, 103, 192),
        Rgb(255, 255, 255),
        Rgb(220, 252, 231),
        Rgb(22, 101, 52),
        Rgb(254, 226, 226),
        Rgb(153, 27, 27),
    };
}

void ApplyWindowTheme(HWND hwnd)
{
    const BOOL immersiveDark = IsDarkModeActive() ? TRUE : FALSE;
    DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, &immersiveDark, sizeof(immersiveDark));
}

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

HFONT MakeFont(int size, int weight)
{
    HDC screen = GetDC(nullptr);
    const int dpi = screen ? GetDeviceCaps(screen, LOGPIXELSY) : static_cast<int>(currentDpi);
    if (screen) {
        ReleaseDC(nullptr, screen);
    }
    return CreateFontW(
        -MulDiv(size, dpi, 72),
        0,
        0,
        0,
        weight,
        FALSE,
        FALSE,
        FALSE,
        DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS,
        CLIP_DEFAULT_PRECIS,
        CLEARTYPE_QUALITY,
        DEFAULT_PITCH | FF_DONTCARE,
        preferredFontFace.c_str());
}

bool FontExists(const wchar_t* faceName)
{
    LOGFONTW query{};
    wcscpy_s(query.lfFaceName, faceName);
    HDC hdc = GetDC(nullptr);
    bool found = false;
    EnumFontFamiliesExW(
        hdc,
        &query,
        [](const LOGFONTW*, const TEXTMETRICW*, DWORD, LPARAM param) -> int {
            *reinterpret_cast<bool*>(param) = true;
            return 0;
        },
        reinterpret_cast<LPARAM>(&found),
        0);
    ReleaseDC(nullptr, hdc);
    return found;
}

void PickPreferredFont()
{
    for (const wchar_t* candidate : {L"MiSans", L"MiSans VF", L"MiSans Latin", L"Microsoft YaHei UI", L"Segoe UI Variable", L"Segoe UI"}) {
        if (FontExists(candidate)) {
            preferredFontFace = candidate;
            return;
        }
    }
}

std::wstring StateText()
{
    using NeoRemote::Core::DesktopDashboardStateType;
    if (!service) {
        return L"正在启动";
    }

    const auto& state = service->State();
    switch (state.type) {
    case DesktopDashboardStateType::ListeningDisabled:
        return L"监听已关闭";
    case DesktopDashboardStateType::IdleListening:
        return L"正在监听端口 " + std::to_wstring(service->ListeningPort());
    case DesktopDashboardStateType::Connected:
        return state.endpoint ? ToWide("已连接设备 " + state.endpoint->AddressSummary()) : L"已连接";
    case DesktopDashboardStateType::Occupied:
        return L"当前正被其他设备控制";
    case DesktopDashboardStateType::Error:
        return ToWide(state.message);
    }
    return L"未知状态";
}

std::wstring DetailText()
{
    if (!service) {
        return L"正在准备 NeoRemote 接收端";
    }
    if (const auto active = service->ActiveClient()) {
        return ToWide("当前连接设备：" + active->AddressSummary() + "，共 " + std::to_string(service->ConnectedClients().size()) + " 台");
    }
    if (!service->PendingConnectionRequests().empty()) {
        return ToWide("有 " + std::to_string(service->PendingConnectionRequests().size()) + " 个连接请求等待处理");
    }
    if (service->IsListening()) {
        return L"局域网中的 iOS 或 Android 设备现在可以连接";
    }
    return L"当前不接受新的控制连接";
}

void EnsureFonts()
{
    if (!titleFont) {
        titleFont = MakeFont(28, FW_SEMIBOLD);
        headingFont = MakeFont(15, FW_SEMIBOLD);
        bodyFont = MakeFont(13, FW_NORMAL);
        smallFont = MakeFont(11, FW_NORMAL);
    }
}

void ResetFontsForDpi(UINT dpi)
{
    currentDpi = dpi == 0 ? 96 : dpi;
    for (HFONT* font : {&titleFont, &headingFont, &bodyFont, &smallFont}) {
        if (*font) {
            DeleteObject(*font);
            *font = nullptr;
        }
    }
    EnsureFonts();
}

void FillRoundRect(HDC hdc, RECT rect, int radius, COLORREF fill)
{
    HBRUSH brush = CreateSolidBrush(fill);
    HPEN pen = CreatePen(PS_SOLID, 1, fill);
    HGDIOBJ oldBrush = SelectObject(hdc, brush);
    HGDIOBJ oldPen = SelectObject(hdc, pen);
    RoundRect(hdc, rect.left, rect.top, rect.right, rect.bottom, radius, radius);
    SelectObject(hdc, oldPen);
    SelectObject(hdc, oldBrush);
    DeleteObject(pen);
    DeleteObject(brush);
}

void StrokeRoundRect(HDC hdc, RECT rect, int radius, COLORREF stroke)
{
    HBRUSH hollow = reinterpret_cast<HBRUSH>(GetStockObject(HOLLOW_BRUSH));
    HPEN pen = CreatePen(PS_SOLID, 1, stroke);
    HGDIOBJ oldBrush = SelectObject(hdc, hollow);
    HGDIOBJ oldPen = SelectObject(hdc, pen);
    RoundRect(hdc, rect.left, rect.top, rect.right, rect.bottom, radius, radius);
    SelectObject(hdc, oldPen);
    SelectObject(hdc, oldBrush);
    DeleteObject(pen);
}

void DrawTextLine(HDC hdc, const std::wstring& text, RECT rect, HFONT font, COLORREF color, UINT format = DT_LEFT | DT_VCENTER | DT_SINGLELINE)
{
    HGDIOBJ oldFont = SelectObject(hdc, font);
    SetTextColor(hdc, color);
    SetBkMode(hdc, TRANSPARENT);
    DrawTextW(hdc, text.c_str(), -1, &rect, format);
    SelectObject(hdc, oldFont);
}

int TextWidth(HDC hdc, HFONT font, const std::wstring& text)
{
    HGDIOBJ oldFont = SelectObject(hdc, font);
    SIZE size{};
    GetTextExtentPoint32W(hdc, text.c_str(), static_cast<int>(text.size()), &size);
    SelectObject(hdc, oldFont);
    return size.cx;
}

RECT InsetRectangle(RECT rect, int horizontal, int vertical)
{
    rect.left += horizontal;
    rect.right -= horizontal;
    rect.top += vertical;
    rect.bottom -= vertical;
    return rect;
}

void RebuildButtons(HDC hdc, int y, int left, int right)
{
    const bool listening = service && service->IsListeningEnabled();
    const bool hasClient = service && !service->ConnectedClients().empty();
    const bool hasPending = service && !service->PendingConnectionRequests().empty();
    const bool autoAllow = service && service->PermissionPolicy().autoAllowTrustedDevices;

    buttons = {
        UiButton{{}, listening ? L"停止监听" : L"开始监听", Action::ToggleListening, true, true},
        UiButton{{}, L"允许请求", Action::ApproveFirstRequest, false, hasPending},
        UiButton{{}, L"拒绝请求", Action::RejectFirstRequest, false, hasPending},
        UiButton{{}, L"断开当前", Action::Disconnect, false, hasClient},
        UiButton{{}, L"断开全部", Action::DisconnectAll, false, hasClient},
        UiButton{{}, autoAllow ? L"关闭自动允许" : L"开启自动允许", Action::ToggleTrustedAutoAllow, false, service != nullptr},
        UiButton{{}, L"隐藏到托盘", Action::Hide, false, true},
        UiButton{{}, L"退出", Action::Exit, false, true},
    };

    constexpr int gap = 12;
    constexpr int minWidth = 104;
    constexpr int horizontalPadding = 44;
    constexpr int height = 44;
    int x = left;

    for (auto& button : buttons) {
        const int measuredWidth = TextWidth(hdc, bodyFont, button.label) + horizontalPadding;
        const int width = std::max(minWidth, measuredWidth);
        button.rect = {x, y, std::min(x + width, right), y + height};
        x += width + gap;
    }
}

void DrawButton(HDC hdc, const UiButton& button)
{
    const ThemePalette palette = Palette();
    const COLORREF fill = !button.enabled ? palette.buttonDisabledFill : button.primary ? palette.buttonPrimaryFill : palette.buttonDefaultFill;
    const COLORREF stroke = !button.enabled ? palette.buttonDisabledStroke : button.primary ? fill : palette.buttonDefaultStroke;
    const COLORREF text = !button.enabled ? palette.buttonDisabledText : button.primary ? palette.buttonPrimaryText : palette.buttonDefaultText;
    FillRoundRect(hdc, button.rect, 8, fill);
    StrokeRoundRect(hdc, button.rect, 8, stroke);
    RECT labelRect = InsetRectangle(button.rect, 14, 0);
    DrawTextLine(hdc, button.label, labelRect, bodyFont, text, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
}

void DrawDevicesAndEvents(HDC hdc, RECT rect)
{
    const ThemePalette palette = Palette();
    FillRoundRect(hdc, rect, 12, palette.surfaceBackground);
    StrokeRoundRect(hdc, rect, 12, palette.surfaceBorder);

    RECT heading{rect.left + 20, rect.top + 16, rect.right - 20, rect.top + 42};
    DrawTextLine(hdc, L"设备与事件", heading, headingFont, palette.primaryText);

    int y = rect.top + 54;
    if (!service) {
        RECT empty{rect.left + 20, y, rect.right - 20, y + 28};
        DrawTextLine(hdc, L"暂无事件", empty, bodyFont, palette.mutedText);
        return;
    }

    if (!service->PendingConnectionRequests().empty()) {
        DrawTextLine(hdc, L"待处理请求", RECT{rect.left + 20, y, rect.right - 20, y + 22}, headingFont, palette.primaryText);
        y += 28;
        for (const auto& request : service->PendingConnectionRequests()) {
            if (y + 40 > rect.bottom - 16) {
                return;
            }
            DrawTextLine(hdc, ToWide((request.displayName.empty() ? "未知移动端" : request.displayName) + " · " + request.endpoint.AddressSummary()), RECT{rect.left + 20, y, rect.right - 20, y + 22}, bodyFont, palette.primaryText);
            DrawTextLine(hdc, ToWide(request.platform.empty() ? "等待 clientHello" : request.platform), RECT{rect.left + 20, y + 20, rect.right - 20, y + 40}, smallFont, palette.mutedText);
            y += 48;
        }
    }

    if (!service->ConnectedClients().empty()) {
        DrawTextLine(hdc, L"已连接设备", RECT{rect.left + 20, y, rect.right - 20, y + 22}, headingFont, palette.primaryText);
        y += 28;
        for (const auto& client : service->ConnectedClients()) {
            if (y + 40 > rect.bottom - 16) {
                return;
            }
            DrawTextLine(hdc, ToWide(client.displayName + " · " + client.endpoint.AddressSummary()), RECT{rect.left + 20, y, rect.right - 20, y + 22}, bodyFont, palette.primaryText);
            DrawTextLine(hdc, ToWide((client.platform.empty() ? "unknown" : client.platform) + std::string(client.isTrusted ? " · trusted" : "")), RECT{rect.left + 20, y + 20, rect.right - 20, y + 40}, smallFont, palette.mutedText);
            y += 48;
        }
    }

    if (service->RecentEvents().empty()) {
        RECT empty{rect.left + 20, y, rect.right - 20, y + 28};
        DrawTextLine(hdc, L"暂无事件", empty, bodyFont, palette.mutedText);
        return;
    }

    DrawTextLine(hdc, L"最近事件", RECT{rect.left + 20, y, rect.right - 20, y + 22}, headingFont, palette.primaryText);
    y += 28;

    int shown = 0;
    for (const auto& event : service->RecentEvents()) {
        if (shown >= 6 || y + 42 > rect.bottom - 16) {
            break;
        }
        RECT title{rect.left + 20, y, rect.right - 20, y + 20};
        RECT detail{rect.left + 20, y + 20, rect.right - 20, y + 40};
        DrawTextLine(hdc, ToWide(event.title), title, bodyFont, palette.primaryText);
        DrawTextLine(hdc, ToWide(event.detail), detail, smallFont, palette.mutedText);
        y += 48;
        ++shown;
    }
}

void Paint(HDC hdc, RECT client)
{
    EnsureFonts();
    RebuildButtons(hdc, 236, 32, client.right - 32);
    const ThemePalette palette = Palette();

    HBRUSH background = CreateSolidBrush(palette.windowBackground);
    FillRect(hdc, &client, background);
    DeleteObject(background);

    RECT title{32, 26, client.right - 32, 76};
    DrawTextLine(hdc, L"NeoRemote Windows", title, titleFont, palette.titleText, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
    RECT subtitle{34, 78, client.right - 32, 106};
    DrawTextLine(hdc, L"本机触控接收端", subtitle, bodyFont, palette.secondaryText, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    RECT statusCard{32, 124, client.right - 32, 212};
    FillRoundRect(hdc, statusCard, 14, palette.surfaceBackground);
    StrokeRoundRect(hdc, statusCard, 14, palette.surfaceBorder);

    const bool active = service && service->IsListening();
    RECT badge{statusCard.left + 18, statusCard.top + 24, statusCard.left + 132, statusCard.top + 56};
    FillRoundRect(hdc, badge, 16, active ? palette.runningBadgeFill : palette.stoppedBadgeFill);
    DrawTextLine(hdc, active ? L"运行中" : L"已停止", badge, smallFont, active ? palette.runningBadgeText : palette.stoppedBadgeText, DT_CENTER | DT_VCENTER | DT_SINGLELINE);

    RECT stateRect{statusCard.left + 154, statusCard.top + 16, statusCard.right - 24, statusCard.top + 48};
    DrawTextLine(hdc, StateText(), stateRect, headingFont, palette.titleText, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
    RECT detailRect{statusCard.left + 154, statusCard.top + 50, statusCard.right - 24, statusCard.top + 76};
    DrawTextLine(hdc, DetailText(), detailRect, smallFont, palette.secondaryText, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    for (const auto& button : buttons) {
        DrawButton(hdc, button);
    }

    RECT eventsRect{32, 300, client.right - 32, client.bottom - 32};
    DrawDevicesAndEvents(hdc, eventsRect);
}

void RefreshUi(HWND hwnd)
{
    if (tray) {
        tray->UpdateTooltip(L"NeoRemote Windows - " + StateText());
    }
    ApplyWindowTheme(hwnd);
    InvalidateRect(hwnd, nullptr, FALSE);
}

void ShowMainWindow(HWND hwnd)
{
    ShowWindow(hwnd, SW_SHOWNORMAL);
    SetForegroundWindow(hwnd);
    RefreshUi(hwnd);
}

void PlayListeningToggleSound(bool listeningEnabled)
{
    PlaySoundW(
        MAKEINTRESOURCEW(listeningEnabled ? IDW_LISTENING_ON : IDW_LISTENING_OFF),
        GetModuleHandleW(nullptr),
        SND_RESOURCE | SND_ASYNC | SND_NODEFAULT);
}

void ToggleListening(HWND hwnd)
{
    if (!service) {
        return;
    }

    const bool nextListeningState = !service->IsListeningEnabled();
    service->SetListeningEnabled(nextListeningState);
    PlayListeningToggleSound(nextListeningState);
    RefreshUi(hwnd);
}

void PerformAction(HWND hwnd, Action action)
{
    switch (action) {
    case Action::ToggleListening:
        ToggleListening(hwnd);
        return;
    case Action::Disconnect:
        if (service) {
            service->DisconnectCurrentSession();
        }
        RefreshUi(hwnd);
        return;
    case Action::DisconnectAll:
        if (service) {
            std::vector<std::string> clientIds;
            for (const auto& client : service->ConnectedClients()) {
                clientIds.push_back(client.id);
            }
            service->DisconnectClients(clientIds);
        }
        RefreshUi(hwnd);
        return;
    case Action::ApproveFirstRequest:
        if (service && !service->PendingConnectionRequests().empty()) {
            service->ApproveConnection(service->PendingConnectionRequests().front().id);
        }
        RefreshUi(hwnd);
        return;
    case Action::RejectFirstRequest:
        if (service && !service->PendingConnectionRequests().empty()) {
            service->RejectConnection(service->PendingConnectionRequests().front().id);
        }
        RefreshUi(hwnd);
        return;
    case Action::ToggleTrustedAutoAllow:
        if (service) {
            service->SetAutoAllowTrustedDevices(!service->PermissionPolicy().autoAllowTrustedDevices);
        }
        RefreshUi(hwnd);
        return;
    case Action::Hide:
        ShowWindow(hwnd, SW_HIDE);
        return;
    case Action::Exit:
        DestroyWindow(hwnd);
        return;
    }
}

LRESULT CALLBACK WindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam)
{
    if (message == RefreshUiMessage) {
        RefreshUi(hwnd);
        return 0;
    }

    if (message == WM_PAINT) {
        PAINTSTRUCT paint{};
        HDC hdc = BeginPaint(hwnd, &paint);
        RECT client{};
        GetClientRect(hwnd, &client);
        HDC memory = CreateCompatibleDC(hdc);
        HBITMAP bitmap = CreateCompatibleBitmap(hdc, client.right - client.left, client.bottom - client.top);
        HGDIOBJ oldBitmap = SelectObject(memory, bitmap);
        Paint(memory, client);
        BitBlt(hdc, 0, 0, client.right, client.bottom, memory, 0, 0, SRCCOPY);
        SelectObject(memory, oldBitmap);
        DeleteObject(bitmap);
        DeleteDC(memory);
        EndPaint(hwnd, &paint);
        return 0;
    }

    if (message == WM_ERASEBKGND) {
        return 1;
    }

    if (message == WM_DPICHANGED) {
        ResetFontsForDpi(HIWORD(wParam));
        const RECT* suggested = reinterpret_cast<const RECT*>(lParam);
        SetWindowPos(
            hwnd,
            nullptr,
            suggested->left,
            suggested->top,
            suggested->right - suggested->left,
            suggested->bottom - suggested->top,
            SWP_NOZORDER | SWP_NOACTIVATE);
        RefreshUi(hwnd);
        return 0;
    }

    if (message == WM_SETTINGCHANGE || message == WM_THEMECHANGED || message == WM_SYSCOLORCHANGE) {
        cachedSystemDarkMode = QuerySystemDarkMode();
        RefreshUi(hwnd);
        return 0;
    }

    if (message == WM_LBUTTONUP) {
        const POINT point{GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
        for (const auto& button : buttons) {
            if (button.enabled && PtInRect(&button.rect, point)) {
                PerformAction(hwnd, button.action);
                return 0;
            }
        }
    }

    if (message == WM_SETCURSOR) {
        POINT point{};
        GetCursorPos(&point);
        ScreenToClient(hwnd, &point);
        for (const auto& button : buttons) {
            if (button.enabled && PtInRect(&button.rect, point)) {
                SetCursor(LoadCursorW(nullptr, IDC_HAND));
                return TRUE;
            }
        }
    }

    if (message == NeoRemote::Windows::TrayIcon::CallbackMessage) {
        if (LOWORD(lParam) == WM_RBUTTONUP || LOWORD(lParam) == WM_CONTEXTMENU) {
            POINT point{};
            GetCursorPos(&point);
            tray->ShowContextMenu(point, service ? service->IsListeningEnabled() : false, themeMode);
            return 0;
        }
        if (LOWORD(lParam) == WM_LBUTTONDBLCLK || LOWORD(lParam) == WM_LBUTTONUP) {
            ShowMainWindow(hwnd);
            return 0;
        }
    }

    if (message == WM_CLOSE) {
        ShowWindow(hwnd, SW_HIDE);
        return 0;
    }

    if (message == WM_DESTROY) {
        if (discoveryResponder) {
            discoveryResponder->Stop();
        }
        if (service) {
            service->Stop();
        }
        if (tray) {
            tray->Hide();
        }
        PostQuitMessage(0);
        return 0;
    }

    return DefWindowProcW(hwnd, message, wParam, lParam);
}

HWND CreateMainWindow(HINSTANCE instance)
{
    WNDCLASSEXW windowClass{};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.hInstance = instance;
    windowClass.lpfnWndProc = WindowProc;
    windowClass.lpszClassName = WindowClassName;
    windowClass.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    windowClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    windowClass.hbrBackground = nullptr;
    RegisterClassExW(&windowClass);

    return CreateWindowExW(
        0,
        WindowClassName,
        L"NeoRemote Windows",
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        880,
        690,
        nullptr,
        nullptr,
        instance,
        nullptr);
}

void Bootstrap(HWND hwnd)
{
    server = std::make_unique<NeoRemote::Windows::TcpRemoteServer>();
    discoveryResponder = std::make_unique<NeoRemote::Windows::UdpDiscoveryResponder>();
    injector = std::make_unique<NeoRemote::Windows::Win32InputInjector>();
    publisher = std::make_unique<NeoRemote::Windows::MdnsPublisher>();
    service = std::make_unique<NeoRemote::Core::DesktopRemoteService>(*server, *injector, publisher.get());
    tray = std::make_unique<NeoRemote::Windows::TrayIcon>(hwnd);

    tray->onOpen = [hwnd] { ShowMainWindow(hwnd); };
    tray->onToggleListening = [hwnd] {
        ToggleListening(hwnd);
    };
    tray->onDisconnect = [hwnd] {
        if (service) {
            service->DisconnectCurrentSession();
        }
        PostMessageW(hwnd, RefreshUiMessage, 0, 0);
    };
    tray->onThemeModeChange = [hwnd](NeoRemote::Windows::ThemeMode newThemeMode) {
        themeMode = newThemeMode;
        PostMessageW(hwnd, RefreshUiMessage, 0, 0);
    };
    tray->onExit = [hwnd] { DestroyWindow(hwnd); };

    server->SetEventHandler([hwnd](const NeoRemote::Core::RemoteServerEvent& event) {
        service->HandleServerEvent(event);
        discoveryTcpPort = service->IsListening() ? service->ListeningPort() : 0;
        PostMessageW(hwnd, RefreshUiMessage, 0, 0);
    });

    tray->Show(L"NeoRemote Windows - 正在启动");
    service->Start();
    discoveryResponder->Start(51101, [] { return discoveryTcpPort.load(); });
    RefreshUi(hwnd);
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int)
{
    if (!SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)) {
        SetProcessDPIAware();
    }
    PickPreferredFont();
    cachedSystemDarkMode = QuerySystemDarkMode();

    HWND hwnd = CreateMainWindow(instance);
    if (!hwnd) {
        MessageBoxW(nullptr, L"无法创建 NeoRemote 接收端窗口。", L"NeoRemote Windows", MB_OK | MB_ICONERROR);
        return 1;
    }

    Bootstrap(hwnd);
    ShowMainWindow(hwnd);

    MSG message{};
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }

    return static_cast<int>(message.wParam);
}
