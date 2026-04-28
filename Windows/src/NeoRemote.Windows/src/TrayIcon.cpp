#include "NeoRemote/Windows/TrayIcon.hpp"

#include <strsafe.h>

namespace NeoRemote::Windows {
namespace {

constexpr UINT MenuOpen = 1001;
constexpr UINT MenuToggleListening = 1002;
constexpr UINT MenuDisconnect = 1003;
constexpr UINT MenuExit = 1004;
constexpr UINT MenuThemeFollowSystem = 1101;
constexpr UINT MenuThemeLight = 1102;
constexpr UINT MenuThemeDark = 1103;
constexpr WORD AppIconResourceId = 101;

HICON LoadAppIcon()
{
    if (HICON icon = LoadIconW(GetModuleHandleW(nullptr), MAKEINTRESOURCEW(AppIconResourceId))) {
        return icon;
    }
    return LoadIconW(nullptr, IDI_APPLICATION);
}

} // namespace

TrayIcon::TrayIcon(HWND ownerWindow)
    : ownerWindow_(ownerWindow)
{
    data_.cbSize = sizeof(data_);
    data_.hWnd = ownerWindow_;
    data_.uID = 1;
    data_.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    data_.uCallbackMessage = CallbackMessage;
    data_.hIcon = LoadAppIcon();
}

TrayIcon::~TrayIcon()
{
    Hide();
}

void TrayIcon::Show(const std::wstring& tooltip)
{
    StringCchCopyW(data_.szTip, ARRAYSIZE(data_.szTip), tooltip.c_str());
    if (!isVisible_) {
        Shell_NotifyIconW(NIM_ADD, &data_);
        isVisible_ = true;
    } else {
        Shell_NotifyIconW(NIM_MODIFY, &data_);
    }
}

void TrayIcon::UpdateTooltip(const std::wstring& tooltip)
{
    Show(tooltip);
}

void TrayIcon::Hide()
{
    if (isVisible_) {
        Shell_NotifyIconW(NIM_DELETE, &data_);
        isVisible_ = false;
    }
}

void TrayIcon::ShowContextMenu(POINT point, bool listeningEnabled, ThemeMode themeMode)
{
    HMENU menu = CreatePopupMenu();
    HMENU themeMenu = CreatePopupMenu();
    AppendMenuW(menu, MF_STRING, MenuOpen, L"打开主界面");
    AppendMenuW(menu, MF_STRING, MenuToggleListening, listeningEnabled ? L"停止监听" : L"开始监听");
    AppendMenuW(menu, MF_STRING, MenuDisconnect, L"断开当前设备");
    AppendMenuW(themeMenu, MF_STRING | (themeMode == ThemeMode::FollowSystem ? MF_CHECKED : 0), MenuThemeFollowSystem, L"跟随系统");
    AppendMenuW(themeMenu, MF_STRING | (themeMode == ThemeMode::Light ? MF_CHECKED : 0), MenuThemeLight, L"浅色");
    AppendMenuW(themeMenu, MF_STRING | (themeMode == ThemeMode::Dark ? MF_CHECKED : 0), MenuThemeDark, L"深色");
    AppendMenuW(menu, MF_POPUP, reinterpret_cast<UINT_PTR>(themeMenu), L"主题");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING, MenuExit, L"退出");

    SetForegroundWindow(ownerWindow_);
    const UINT command = TrackPopupMenu(
        menu,
        TPM_RETURNCMD | TPM_NONOTIFY | TPM_RIGHTBUTTON,
        point.x,
        point.y,
        0,
        ownerWindow_,
        nullptr);
    DestroyMenu(menu);

    switch (command) {
    case MenuOpen:
        if (onOpen) {
            onOpen();
        }
        break;
    case MenuToggleListening:
        if (onToggleListening) {
            onToggleListening();
        }
        break;
    case MenuDisconnect:
        if (onDisconnect) {
            onDisconnect();
        }
        break;
    case MenuExit:
        if (onExit) {
            onExit();
        }
        break;
    case MenuThemeFollowSystem:
        if (onThemeModeChange) {
            onThemeModeChange(ThemeMode::FollowSystem);
        }
        break;
    case MenuThemeLight:
        if (onThemeModeChange) {
            onThemeModeChange(ThemeMode::Light);
        }
        break;
    case MenuThemeDark:
        if (onThemeModeChange) {
            onThemeModeChange(ThemeMode::Dark);
        }
        break;
    default:
        break;
    }
}

} // namespace NeoRemote::Windows
