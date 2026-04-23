#pragma once

#include "NeoRemote/Core/Protocol.hpp"

#include <functional>
#include <optional>
#include <vector>

namespace NeoRemote::Core {

struct Point {
    double x{0};
    double y{0};

    bool operator==(const Point& other) const = default;
};

enum class PlannedMouseEventType {
    Move,
    MouseDown,
    MouseUp,
    Drag,
    Scroll,
};

struct PlannedMouseEvent {
    PlannedMouseEventType type{PlannedMouseEventType::Move};
    MouseButtonKind button{MouseButtonKind::Primary};
    Point point{};
    int scrollLines{0};

    static PlannedMouseEvent Move(Point point);
    static PlannedMouseEvent MouseDown(MouseButtonKind button, Point point);
    static PlannedMouseEvent MouseUp(MouseButtonKind button, Point point);
    static PlannedMouseEvent Drag(MouseButtonKind button, Point point);
    static PlannedMouseEvent Scroll(int lines);

    bool operator==(const PlannedMouseEvent& other) const = default;
};

class MouseEventPlanner {
public:
    using PositionProvider = std::function<Point()>;

    std::vector<PlannedMouseEvent> Apply(const RemoteCommand& command, const PositionProvider& positionProvider);
    std::optional<Point> CurrentPosition() const;

private:
    Point TranslatedPoint(double dx, double dy);

    std::optional<Point> currentPosition_;
    std::optional<MouseButtonKind> activeDragButton_;
};

} // namespace NeoRemote::Core
