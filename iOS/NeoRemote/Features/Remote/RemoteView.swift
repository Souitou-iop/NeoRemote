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

                VStack(spacing: 0) {
                    switch coordinator.controlMode {
                    case .screenControl:
                        ScreenControlSurface(
                            theme: theme,
                            onGesture: coordinator.sendScreenGesture
                        )
                    case .shortVideo:
                        ShortVideoControlPanel(
                            theme: theme,
                            onAction: coordinator.sendVideoAction
                        )
                    }
                }
                .padding(.horizontal, 14)
                .padding(.top, 8)
                .padding(.bottom, 16)

                if let hud = coordinator.lastHUDMessage {
                    HUDView(text: hud)
                }
            }
            .navigationTitle("Remote")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(coordinator.controlMode.displayName) {
                        coordinator.setControlMode(
                            coordinator.controlMode == .shortVideo ? .screenControl : .shortVideo
                        )
                    }
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
        }
    }

    private var theme: RemoteTheme {
        colorScheme == .dark ? .dark : .light
    }
}

private struct ScreenControlSurface: View {
    let theme: RemoteTheme
    let onGesture: (ScreenGestureKind, Double, Double, Double, Double, Int) -> Void
    @State private var gestureStartDate = Date()

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .fill(theme.surfaceBackground)
                    .overlay(
                        RoundedRectangle(cornerRadius: 30, style: .continuous)
                            .strokeBorder(theme.secondaryForeground.opacity(0.22), lineWidth: 1)
                    )

                VStack(spacing: 8) {
                    Text("屏幕镜像")
                        .font(.title3.weight(.bold))
                    Text("在这里点按、上滑和侧滑，动作会直接映射到被控端")
                        .font(.footnote.weight(.medium))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(theme.secondaryForeground)
                }
                .foregroundStyle(theme.primaryForeground)
                .padding(.horizontal, 28)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if value.translation == .zero {
                            gestureStartDate = value.time
                        }
                    }
                    .onEnded { value in
                        let size = proxy.size
                        guard size.width > 1, size.height > 1 else { return }
                        let start = normalized(value.startLocation, size: size)
                        let end = normalized(value.location, size: size)
                        let distance = hypot(
                            value.location.x - value.startLocation.x,
                            value.location.y - value.startLocation.y
                        )
                        let kind: ScreenGestureKind = distance < 18 ? .tap : .swipe
                        let duration = max(80, Int(value.time.timeIntervalSince(gestureStartDate) * 1_000))
                        onGesture(kind, start.x, start.y, end.x, end.y, duration)
                    }
            )
        }
        .frame(maxWidth: .infinity)
        .frame(maxHeight: .infinity)
    }

    private func normalized(_ point: CGPoint, size: CGSize) -> (x: Double, y: Double) {
        (
            x: Double(point.x / size.width).clamped(to: 0 ... 1),
            y: Double(point.y / size.height).clamped(to: 0 ... 1)
        )
    }
}

private struct ShortVideoControlPanel: View {
    let theme: RemoteTheme
    let onAction: (VideoActionKind) -> Void

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                actionButton("点赞", .doubleTapLike, style: .like, systemImage: "heart")
                actionButton("收藏", .favorite, systemImage: "bookmark")
            }

            VStack(spacing: 14) {
                VStack(spacing: 0) {
                    rockerButton(title: "上一条", subtitle: "向下滑动", action: .swipeDown)
                    Divider().overlay(Color.white.opacity(0.26))
                    rockerButton(title: "下一条", subtitle: "向上滑动", action: .swipeUp)
                }
                .background(theme.videoRockerBackground)
                .clipShape(RoundedRectangle(cornerRadius: 34, style: .continuous))
                .shadow(color: theme.videoRockerShadow, radius: 20, y: 10)

                HStack(spacing: 12) {
                    actionButton("左滑", .swipeLeft, systemImage: "arrow.left")
                    actionButton("右滑", .swipeRight, systemImage: "arrow.right")
                }
            }
            .frame(maxHeight: .infinity)

            HStack(spacing: 12) {
                actionButton("播放/暂停", .playPause, style: .primary, systemImage: "playpause")
                actionButton("返回", .back, style: .secondary, systemImage: "chevron.backward")
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.surfaceBackground)
        .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .strokeBorder(theme.secondaryForeground.opacity(0.16), lineWidth: 1)
        )
    }

    private func rockerButton(title: String, subtitle: String, action: VideoActionKind) -> some View {
        Button {
            onAction(action)
        } label: {
            VStack(spacing: 6) {
                Image(systemName: "arrow.up.arrow.down")
                    .font(.title3.weight(.bold))
                Text(title)
                    .font(.title3.weight(.bold))
                Text(subtitle)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.66))
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func actionButton(
        _ title: String,
        _ action: VideoActionKind,
        style: ShortVideoButtonStyle = .secondary,
        systemImage: String? = nil
    ) -> some View {
        Button {
            onAction(action)
        } label: {
            HStack(spacing: 7) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.headline.weight(.bold))
                }
                Text(title)
                    .font(.headline.weight(.bold))
            }
            .frame(maxWidth: .infinity)
            .frame(height: 72)
            .foregroundStyle(theme.videoButtonForeground(style))
            .background(theme.videoButtonBackground(style))
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private enum ShortVideoButtonStyle {
    case secondary
    case primary
    case like
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
    let videoRockerBackground: Color
    let videoRockerShadow: Color

    static let dark = RemoteTheme(
        backgroundGradient: [
            Color.black.opacity(0.95),
            Color.blue.opacity(0.36),
        ],
        surfaceBackground: .white.opacity(0.07),
        primaryForeground: .white,
        secondaryForeground: .white.opacity(0.72),
        statusChipBackground: Color.white.opacity(0.16),
        statusChipForeground: .white,
        videoRockerBackground: Color(red: 0.12, green: 0.43, blue: 0.60),
        videoRockerShadow: Color.black.opacity(0.22)
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
        statusChipForeground: Color(red: 0.06, green: 0.11, blue: 0.18),
        videoRockerBackground: Color(red: 0.12, green: 0.43, blue: 0.60),
        videoRockerShadow: Color(red: 0.12, green: 0.43, blue: 0.60).opacity(0.18)
    )

    func videoButtonBackground(_ style: ShortVideoButtonStyle) -> Color {
        switch style {
        case .secondary:
            return surfaceBackground.opacity(0.92)
        case .primary:
            return Color(red: 0.15, green: 0.49, blue: 0.66)
        case .like:
            return Color(red: 0.96, green: 0.28, blue: 0.42)
        }
    }

    func videoButtonForeground(_ style: ShortVideoButtonStyle) -> Color {
        switch style {
        case .secondary:
            return primaryForeground
        case .primary, .like:
            return .white
        }
    }
}

struct TouchSurfaceRepresentable: UIViewRepresentable {
    var settings: TouchSensitivitySettings
    var onOutput: (TouchSurfaceOutput) -> Void

    func makeUIView(context: Context) -> TouchSurfaceUIKitView {
        let view = TouchSurfaceUIKitView(settings: settings)
        view.onOutput = onOutput
        return view
    }

    func updateUIView(_ uiView: TouchSurfaceUIKitView, context _: Context) {
        uiView.onOutput = onOutput
        uiView.update(settings: settings)
    }
}

final class TouchSurfaceUIKitView: UIView {
    var onOutput: ((TouchSurfaceOutput) -> Void)?
    private var adapter = TouchSurfaceInputAdapter()

    init(settings: TouchSensitivitySettings, frame: CGRect = .zero) {
        super.init(frame: frame)
        adapter.apply(settings: settings)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func update(settings: TouchSensitivitySettings) {
        adapter.apply(settings: settings)
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
