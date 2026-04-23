#include "NeoRemote/Core/MouseEventPlanner.hpp"

#include <cmath>

namespace NeoRemote::Core {

PlannedMouseEvent PlannedMouseEvent::Move(Point pointValue)
{
    PlannedMouseEvent event;
    event.type = PlannedMouseEventType::Move;
    event.point = pointValue;
    return event;
}

PlannedMouseEvent PlannedMouseEvent::MouseDown(MouseButtonKind buttonValue, Point pointValue)
{
    PlannedMouseEvent event;
    event.type = PlannedMouseEventType::MouseDown;
    event.button = buttonValue;
    event.point = pointValue;
    return event;
}

PlannedMouseEvent PlannedMouseEvent::MouseUp(MouseButtonKind buttonValue, Point pointValue)
{
    PlannedMouseEvent event;
    event.type = PlannedMouseEventType::MouseUp;
    event.button = buttonValue;
    event.point = pointValue;
    return event;
}

PlannedMouseEvent PlannedMouseEvent::Drag(MouseButtonKind buttonValue, Point pointValue)
{
    PlannedMouseEvent event;
    event.type = PlannedMouseEventType::Drag;
    event.button = buttonValue;
    event.point = pointValue;
    return event;
}

PlannedMouseEvent PlannedMouseEvent::Scroll(int lines)
{
    PlannedMouseEvent event;
    event.type = PlannedMouseEventType::Scroll;
    event.scrollLines = lines;
    return event;
}

std::vector<PlannedMouseEvent> MouseEventPlanner::Apply(const RemoteCommand& command, const PositionProvider& positionProvider)
{
    if (!currentPosition_) {
        currentPosition_ = positionProvider();
    }

    switch (command.type) {
    case RemoteCommandType::Move:
        return {PlannedMouseEvent::Move(TranslatedPoint(command.dx, command.dy))};

    case RemoteCommandType::Tap: {
        const Point point = currentPosition_.value_or(positionProvider());
        return {
            PlannedMouseEvent::MouseDown(command.button, point),
            PlannedMouseEvent::MouseUp(command.button, point),
        };
    }

    case RemoteCommandType::Scroll:
        return {PlannedMouseEvent::Scroll(static_cast<int>(std::llround(command.deltaY)))};

    case RemoteCommandType::Drag:
        switch (command.state) {
        case DragState::Started: {
            const Point point = currentPosition_.value_or(positionProvider());
            activeDragButton_ = MouseButtonKind::Primary;
            return {PlannedMouseEvent::MouseDown(MouseButtonKind::Primary, point)};
        }
        case DragState::Changed: {
            const MouseButtonKind button = activeDragButton_.value_or(MouseButtonKind::Primary);
            activeDragButton_ = button;
            return {PlannedMouseEvent::Drag(button, TranslatedPoint(command.dx, command.dy))};
        }
        case DragState::Ended: {
            const Point point = currentPosition_.value_or(positionProvider());
            const MouseButtonKind button = activeDragButton_.value_or(MouseButtonKind::Primary);
            activeDragButton_.reset();
            return {PlannedMouseEvent::MouseUp(button, point)};
        }
        }
        break;

    case RemoteCommandType::Heartbeat:
        return {};
    }

    return {};
}

std::optional<Point> MouseEventPlanner::CurrentPosition() const
{
    return currentPosition_;
}

Point MouseEventPlanner::TranslatedPoint(double dx, double dy)
{
    Point current = currentPosition_.value_or(Point{});
    current.x += dx;
    current.y += dy;
    currentPosition_ = current;
    return current;
}

} // namespace NeoRemote::Core
