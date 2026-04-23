#pragma once

#include <functional>
#include <string>

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <shellapi.h>

namespace NeoRemote::Windows {

enum class ThemeMode {
    FollowSystem,
    Light,
    Dark,
};

class TrayIcon {
public:
    static constexpr UINT CallbackMessage = WM_APP + 505;

    explicit TrayIcon(HWND ownerWindow);
    ~TrayIcon();

    void Show(const std::wstring& tooltip);
    void UpdateTooltip(const std::wstring& tooltip);
    void Hide();
    void ShowContextMenu(POINT point, bool listeningEnabled, ThemeMode themeMode);

    std::function<void()> onOpen;
    std::function<void()> onToggleListening;
    std::function<void()> onDisconnect;
    std::function<void()> onExit;
    std::function<void(ThemeMode)> onThemeModeChange;

private:
    HWND ownerWindow_{nullptr};
    NOTIFYICONDATAW data_{};
    bool isVisible_{false};
};

} // namespace NeoRemote::Windows
