// swift-tools-version:5.9
//
// Swift Package Manager na raiz do repositório (exigência do SPM); o código do SDK iOS
// fica em ios/. O macOS entra só para `swift test` rodar a parte sem UIKit na CI.
import PackageDescription

let package = Package(
    name: "AtaliaChat",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "AtaliaChat", targets: ["AtaliaChat"]),
    ],
    targets: [
        .target(
            name: "AtaliaChat",
            path: "ios/Sources/AtaliaChat",
            resources: [.copy("Resources/PrivacyInfo.xcprivacy")]
        ),
        .testTarget(
            name: "AtaliaChatTests",
            dependencies: ["AtaliaChat"],
            path: "ios/Tests/AtaliaChatTests"
        ),
    ]
)
