#include "NeoRemote/Windows/Win32InputInjector.hpp"

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

namespace NeoRemote::Windows {

void Win32InputInjector::Handle(const Core::RemoteCommand& command)
{
    for (const auto& event : planner_.Apply(command, CurrentCursorPosition)) {
        Post(event);
    }
}

Core::Point Win32InputInjector::CurrentCursorPosition()
{
    POINT point{};
    GetCursorPos(&point);
    return Core::Point{static_cast<double>(point.x), static_cast<double>(point.y)};
}

void Win32InputInjector::Post(const Core::PlannedMouseEvent& event)
{
    switch (event.type) {
    case Core::PlannedMouseEventType::Move:
    case Core::PlannedMouseEventType::Drag:
        SetCursorPos(static_cast<int>(event.point.x), static_cast<int>(event.point.y));
        return;

    case Core::PlannedMouseEventType::MouseDown: {
        INPUT input{};
        input.type = INPUT_MOUSE;
        input.mi.dwFlags = ButtonDownFlag(event.button);
        SendInput(1, &input, sizeof(INPUT));
        return;
    }

    case Core::PlannedMouseEventType::MouseUp: {
        INPUT input{};
        input.type = INPUT_MOUSE;
        input.mi.dwFlags = ButtonUpFlag(event.button);
        SendInput(1, &input, sizeof(INPUT));
        return;
    }

    case Core::PlannedMouseEventType::Scroll: {
        INPUT input{};
        input.type = INPUT_MOUSE;
        input.mi.dwFlags = MOUSEEVENTF_WHEEL;
        input.mi.mouseData = static_cast<DWORD>(event.scrollLines * WHEEL_DELTA);
        SendInput(1, &input, sizeof(INPUT));
        return;
    }
    }
}

unsigned long Win32InputInjector::ButtonDownFlag(Core::MouseButtonKind button)
{
    switch (button) {
    case Core::MouseButtonKind::Secondary:
        return MOUSEEVENTF_RIGHTDOWN;
    case Core::MouseButtonKind::Middle:
        return MOUSEEVENTF_MIDDLEDOWN;
    case Core::MouseButtonKind::Primary:
        return MOUSEEVENTF_LEFTDOWN;
    }
    return MOUSEEVENTF_LEFTDOWN;
}

unsigned long Win32InputInjector::ButtonUpFlag(Core::MouseButtonKind button)
{
    switch (button) {
    case Core::MouseButtonKind::Secondary:
        return MOUSEEVENTF_RIGHTUP;
    case Core::MouseButtonKind::Middle:
        return MOUSEEVENTF_MIDDLEUP;
    case Core::MouseButtonKind::Primary:
        return MOUSEEVENTF_LEFTUP;
    }
    return MOUSEEVENTF_LEFTUP;
}

} // namespace NeoRemote::Windows
