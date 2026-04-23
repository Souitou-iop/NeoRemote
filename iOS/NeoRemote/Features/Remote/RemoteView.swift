import SwiftUI
import UIKit

struct RemoteView: View {
    @ObservedObject var coordinator: SessionCoordinator
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        NavigationStack {
            ZStack {
                LinearGradient(
                    colors: theme.backgroundGradient,
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()

                VStack(spacing: 18) {
                    statusHeader

                    TouchSurfaceRepresentable { output in
                        coordinator.handleTouchOutput(output)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(theme.surfaceBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))

                    Text("单指移动/点击 · 双击拖拽 · 双指右键/滚动 · 三指中键")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(theme.secondaryForeground)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(20)

                if let hud = coordinator.lastHUDMessage {
                    HUDView(text: hud)
                }
            }
            .navigationTitle("Remote")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("断开") {
                        coordinator.disconnect()
                    }
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
        }
    }

    private var statusHeader: some View {
        HStack {
            Text(coordinator.activeEndpoint?.displayName ?? "Desktop")
                .font(.title2.weight(.bold))
            Spacer()
            Text(coordinator.status.rawValue.capitalized)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .foregroundStyle(theme.statusChipForeground)
                .background(theme.statusChipBackground)
                .clipShape(Capsule())
        }
        .foregroundStyle(theme.primaryForeground)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var theme: RemoteTheme {
        colorScheme == .dark ? .dark : .light
    }
}

private struct HUDView: View {
    let text: String

    var body: some View {
        VStack {
            Spacer()
            Text(text)
                .font(.callout.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(.black.opacity(0.72))
                .clipShape(Capsule())
                .padding(.bottom, 42)
        }
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

private struct RemoteTheme {
    let backgroundGradient: [Color]
    let surfaceBackground: Color
    let primaryForeground: Color
    let secondaryForeground: Color
    let statusChipBackground: Color
    let statusChipForeground: Color

    static let dark = RemoteTheme(
        backgroundGradient: [
            Color.black.opacity(0.95),
            Color.blue.opacity(0.36),
        ],
        surfaceBackground: .white.opacity(0.07),
        primaryForeground: .white,
        secondaryForeground: .white.opacity(0.72),
        statusChipBackground: Color.white.opacity(0.16),
        statusChipForeground: .white
    )

    static let light = RemoteTheme(
        backgroundGradient: [
            Color(red: 0.83, green: 0.87, blue: 0.92),
            Color(red: 0.63, green: 0.74, blue: 0.88),
        ],
        surfaceBackground: Color(red: 0.88, green: 0.92, blue: 0.97).opacity(0.46),
        primaryForeground: Color(red: 0.10, green: 0.16, blue: 0.24),
        secondaryForeground: Color(red: 0.28, green: 0.36, blue: 0.46),
        statusChipBackground: Color(red: 0.56, green: 0.66, blue: 0.78).opacity(0.82),
        statusChipForeground: Color(red: 0.06, green: 0.11, blue: 0.18)
    )
}

struct TouchSurfaceRepresentable: UIViewRepresentable {
    var onOutput: (TouchSurfaceOutput) -> Void

    func makeUIView(context: Context) -> TouchSurfaceUIKitView {
        let view = TouchSurfaceUIKitView()
        view.onOutput = onOutput
        return view
    }

    func updateUIView(_ uiView: TouchSurfaceUIKitView, context _: Context) {
        uiView.onOutput = onOutput
    }
}

final class TouchSurfaceUIKitView: UIView {
    var onOutput: ((TouchSurfaceOutput) -> Void)?
    private var adapter = TouchSurfaceInputAdapter()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesBegan(touches, with: event)
        touches.forEach { touch in
            let point = touch.location(in: self)
            emit(adapter.touchBegan(id: touchID(for: touch), point: point, timestamp: touch.timestamp))
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesMoved(touches, with: event)
        touches.forEach { touch in
            let point = touch.location(in: self)
            emit(adapter.touchMoved(id: touchID(for: touch), point: point, timestamp: touch.timestamp))
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesEnded(touches, with: event)
        touches.forEach { touch in
            let point = touch.location(in: self)
            emit(adapter.touchEnded(id: touchID(for: touch), point: point, timestamp: touch.timestamp))
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesCancelled(touches, with: event)
        emit(adapter.cancelAllTouches())
    }

    private func emit(_ output: TouchSurfaceOutput) {
        guard !output.commands.isEmpty || output.semanticEvent != nil else { return }
        onOutput?(output)
    }

    private func touchID(for touch: UITouch) -> Int {
        ObjectIdentifier(touch).hashValue
    }
}
