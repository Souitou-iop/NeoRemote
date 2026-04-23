#pragma once

#include "NeoRemote/Core/DesktopRemoteService.hpp"
#include "NeoRemote/Core/MouseEventPlanner.hpp"

namespace NeoRemote::Windows {

class Win32InputInjector final : public Core::IInputInjector {
public:
    void Handle(const Core::RemoteCommand& command) override;

private:
    static Core::Point CurrentCursorPosition();
    static void Post(const Core::PlannedMouseEvent& event);
    static unsigned long ButtonDownFlag(Core::MouseButtonKind button);
    static unsigned long ButtonUpFlag(Core::MouseButtonKind button);

    Core::MouseEventPlanner planner_;
};

} // namespace NeoRemote::Windows
