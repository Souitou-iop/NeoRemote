import SwiftUI
import UIKit

struct RemoteView: View {
    @ObservedObject var coordinator: SessionCoordinator

    var body: some View {
        NavigationStack {
            ZStack {
                LinearGradient(
                    colors: [Color.black.opacity(0.95), Color.blue.opacity(0.36)],
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
                    .background(.white.opacity(0.07))
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .overlay(alignment: .bottomLeading) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Gesture Surface")
                                .font(.headline)
                            Text("单指移动 · 单击 · 双指滚动 · 双击拖拽")
                                .font(.footnote)
                                .foregroundStyle(.white.opacity(0.72))
                        }
                        .padding(18)
                    }

                    footerHint
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
                    .foregroundStyle(.white)
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
        }
    }

    private var statusHeader: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(coordinator.activeEndpoint?.displayName ?? "Desktop")
                    .font(.title2.weight(.bold))
                Spacer()
                Text(coordinator.status.rawValue.capitalized)
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.white.opacity(0.12))
                    .clipShape(Capsule())
            }

            Text(coordinator.statusMessage)
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.72))
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var footerHint: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("纯手势模式")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
            Text("首次使用建议先轻点体验点击，再尝试双指滚动和双击拖拽。")
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.72))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
