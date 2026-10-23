import XCTest
@testable import AtaliaChat

final class ChatURLBuilderTests: XCTestCase {
    func testUrlsMatchFixture() throws {
        let fixture = try Fixtures.json("sdk-fragment.json")
        let cases = try XCTUnwrap(fixture["cases"] as? [[String: Any]])
        for item in cases {
            let name = item["name"] as? String ?? "?"
            let expectedJson = try XCTUnwrap(item["fragment"] as? [String: Any])
            var fragment = SdkFragment()
            fragment.protocolVersion = expectedJson["protocol"] as? Int ?? 1
            fragment.platform = try XCTUnwrap(expectedJson["platform"] as? String)
            fragment.sdkVersion = try XCTUnwrap(expectedJson["sdkVersion"] as? String)
            fragment.appVersion = expectedJson["appVersion"] as? String
            fragment.session = expectedJson["session"] as? String
            fragment.conversationId = expectedJson["conversationId"] as? Int
            fragment.closeButton = expectedJson["closeButton"] as? Bool ?? true

            let url = ChatURLBuilder.url(
                baseURL: try XCTUnwrap(URL(string: try XCTUnwrap(item["baseUrl"] as? String))),
                channelToken: try XCTUnwrap(item["channelToken"] as? String),
                customDomain: item["customDomain"] as? Bool ?? false,
                fragment: fragment
            )
            let prefix = try XCTUnwrap(item["expectedPrefix"] as? String)
            XCTAssertTrue(url.absoluteString.hasPrefix(prefix), "\(name): \(url.absoluteString)")

            // O fragmento decodifica de volta para o mesmo JSON que o widget lê.
            let encoded = String(url.absoluteString.dropFirst(prefix.count))
            let data = try XCTUnwrap(Base64URL.decode(encoded))
            let decoded = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? NSDictionary)
            XCTAssertEqual(decoded, expectedJson as NSDictionary, name)
            XCTAssertFalse(encoded.contains("="), "\(name): base64url sem padding")
        }
    }

    func testBaseUrlTrailingSlashIsIgnored() {
        let url = ChatURLBuilder.url(
            baseURL: URL(string: "https://chat.atalia.ai/")!,
            channelToken: "wct_x",
            customDomain: false,
            fragment: SdkFragment()
        )
        XCTAssertTrue(url.absoluteString.hasPrefix("https://chat.atalia.ai/chat/wct_x?sdk=1#sdk="))
    }

    func testApiEndpoint() {
        let api = APIClient(baseURL: URL(string: "https://chat.atalia.ai")!, channelToken: "wct_x")
        XCTAssertEqual(api.endpoint("/unread").absoluteString, "https://chat.atalia.ai/api/webchat/wct_x/unread")
        XCTAssertEqual(api.endpoint("").absoluteString, "https://chat.atalia.ai/api/webchat/wct_x")
    }
}
