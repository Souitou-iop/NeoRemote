import SwiftUI
import UIKit

struct SettingsView: View {
    @ObservedObject var coordinator: SessionCoordinator

    var body: some View {
        NavigationStack {
            List {
                Section("默认控制模式") {
                    Picker(
                        "启动后进入",
                        selection: Binding(
                            get: { coordinator.defaultControlMode },
                            set: { coordinator.setDefaultControlMode($0) }
                        )
                    ) {
                        ForEach(ControlMode.allCases) { mode in
                            Text(mode.displayName).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)

                    LabeledContent("当前模式") {
                        Text(coordinator.controlMode.displayName)
                    }
                }

                Section("触控反馈") {
                    Toggle(
                        "震动反馈",
                        isOn: Binding(
                            get: { coordinator.isHapticsEnabled },
                            set: { coordinator.setHapticsEnabled($0) }
                        )
                    )
                }

                Section("连接策略") {
                    LabeledContent("自动发现") {
                        Text("Bonjour / LAN")
                    }
                    LabeledContent("协议编码") {
                        Text("JSON v1")
                    }
                }

                Section("当前会话") {
                    LabeledContent("Desktop") {
                        Text(coordinator.activeEndpoint?.displayName ?? "未连接")
                    }
                }

                Section("维护") {
                    Button("断开当前连接") {
                        coordinator.disconnect()
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}

private struct SensitivitySlider: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                Spacer()
                Text(value.formatted(.number.precision(.fractionLength(1))))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            NoHapticSlider(
                value: $value,
                range: range,
                step: TouchSensitivitySettings.step
            )
            .frame(height: 32)
        }
        .padding(.vertical, 4)
    }
}

private struct NoHapticSlider: UIViewRepresentable {
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double

    func makeUIView(context: Context) -> UISlider {
        let slider = UISlider(frame: .zero)
        slider.minimumValue = Float(range.lowerBound)
        slider.maximumValue = Float(range.upperBound)
        slider.value = Float(value)
        slider.isContinuous = true
        slider.addTarget(
            context.coordinator,
            action: #selector(Coordinator.valueChanged(_:)),
            for: .valueChanged
        )
        return slider
    }

    func updateUIView(_ slider: UISlider, context: Context) {
        slider.minimumValue = Float(range.lowerBound)
        slider.maximumValue = Float(range.upperBound)
        let nextValue = Float(value)
        if abs(slider.value - nextValue) > 0.001 {
            slider.setValue(nextValue, animated: false)
        }
        context.coordinator.parent = self
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    final class Coordinator: NSObject {
        var parent: NoHapticSlider

        init(parent: NoHapticSlider) {
            self.parent = parent
        }

        @objc func valueChanged(_ sender: UISlider) {
            let rawValue = Double(sender.value)
            let steppedValue = (rawValue / parent.step).rounded() * parent.step
            let clampedValue = steppedValue.clamped(to: parent.range)
            parent.value = clampedValue

            let sliderValue = Float(clampedValue)
            if abs(sender.value - sliderValue) > 0.001 {
                sender.setValue(sliderValue, animated: false)
            }
        }
    }
}
