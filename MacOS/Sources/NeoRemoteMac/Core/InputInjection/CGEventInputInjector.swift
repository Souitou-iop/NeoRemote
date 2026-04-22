import AppKit
import CoreGraphics
import Foundation

protocol RemoteCommandInjecting: AnyObject {
    func handle(_ command: RemoteCommand) throws
}

enum InputInjectionError: Error, LocalizedError {
    case unableToCreateEvent

    var errorDescription: String? {
        "无法创建系统鼠标事件。"
    }
}

final class CGEventInputInjector: RemoteCommandInjecting {
    private var planner = MouseEventPlanner()
    private let eventSource: CGEventSource?
    private let positionProvider: () -> CGPoint

    init(
        eventSource: CGEventSource? = CGEventSource(stateID: .hidSystemState),
        positionProvider: @escaping () -> CGPoint = { NSEvent.mouseLocation }
    ) {
        self.eventSource = eventSource
        self.positionProvider = positionProvider
    }

    func handle(_ command: RemoteCommand) throws {
        let plannedEvents = planner.apply(command, positionProvider: positionProvider)
        for plannedEvent in plannedEvents {
            try post(plannedEvent)
        }
    }

    private func post(_ event: PlannedMouseEvent) throws {
        switch event {
        case let .move(point):
            try postMouse(type: .mouseMoved, point: point, button: .left)

        case let .mouseDown(button, point):
            try postMouse(type: mouseDownType(for: button), point: point, button: cgButton(for: button))

        case let .mouseUp(button, point):
            try postMouse(type: mouseUpType(for: button), point: point, button: cgButton(for: button))

        case let .drag(button, point):
            try postMouse(type: dragType(for: button), point: point, button: cgButton(for: button))

        case let .scroll(lines):
            guard let event = CGEvent(
                scrollWheelEvent2Source: eventSource,
                units: .line,
                wheelCount: 1,
                wheel1: lines,
                wheel2: 0,
                wheel3: 0
            ) else {
                throw InputInjectionError.unableToCreateEvent
            }
            event.post(tap: .cghidEventTap)
        }
    }

    private func postMouse(type: CGEventType, point: CGPoint, button: CGMouseButton) throws {
        guard let event = CGEvent(
            mouseEventSource: eventSource,
            mouseType: type,
            mouseCursorPosition: point,
            mouseButton: button
        ) else {
            throw InputInjectionError.unableToCreateEvent
        }
        event.post(tap: .cghidEventTap)
    }

    private func cgButton(for button: MouseButtonKind) -> CGMouseButton {
        switch button {
        case .primary:
            .left
        case .secondary:
            .right
        }
    }

    private func mouseDownType(for button: MouseButtonKind) -> CGEventType {
        switch button {
        case .primary:
            .leftMouseDown
        case .secondary:
            .rightMouseDown
        }
    }

    private func mouseUpType(for button: MouseButtonKind) -> CGEventType {
        switch button {
        case .primary:
            .leftMouseUp
        case .secondary:
            .rightMouseUp
        }
    }

    private func dragType(for button: MouseButtonKind) -> CGEventType {
        switch button {
        case .primary:
            .leftMouseDragged
        case .secondary:
            .rightMouseDragged
        }
    }
}
