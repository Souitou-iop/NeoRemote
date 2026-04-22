// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "NeoRemoteMac",
    platforms: [
        .macOS(.v15),
    ],
    products: [
        .executable(name: "NeoRemoteMac", targets: ["NeoRemoteMac"]),
    ],
    targets: [
        .executableTarget(
            name: "NeoRemoteMac",
            path: "Sources/NeoRemoteMac"
        ),
        .testTarget(
            name: "NeoRemoteMacTests",
            dependencies: ["NeoRemoteMac"],
            path: "Tests/NeoRemoteMacTests"
        ),
    ]
)
