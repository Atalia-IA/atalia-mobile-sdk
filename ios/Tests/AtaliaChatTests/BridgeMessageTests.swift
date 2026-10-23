import XCTest
@testable import AtaliaChat

final class BridgeMessageTests: XCTestCase {
    func testPublicEventsMatchFixture() throws {
        let fixture = try Fixtures.json("bridge-events.json")
        let cases = try XCTUnwrap(fixture["publicEvents"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for item in cases {
            let message = try XCTUnwrap(BridgeMessage(body: Fixtures.raw(item["raw"]!)), "não decodificou \(item["raw"]!)")
            let expected = try XCTUnwrap(item["event"] as? [String: Any])
            XCTAssertEqual(message.publicEvent, try event(from: expected), "evento errado para \(message.type)")
        }
    }

    func testInternalMessagesAreNotPublicEvents() throws {
        let fixture = try Fixtures.json("bridge-events.json")
        let cases = try XCTUnwrap(fixture["internalMessages"] as? [[String: Any]])
        for item in cases {
            let message = try XCTUnwrap(BridgeMessage(body: Fixtures.raw(item["raw"]!)))
            XCTAssertNil(message.publicEvent, "\(message.type) não é evento público")
            if let expect = item["expect"] as? [String: Any] {
                for (key, value) in expect {
                    switch value {
                    case let bool as Bool where CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID():
                        XCTAssertEqual(message.bool(key), bool, key)
                    case let text as String:
                        XCTAssertEqual(message.string(key), text, key)
                    default:
                        XCTFail("tipo inesperado em \(key)")
                    }
                }
            }
        }
    }

    func testUnknownAndMalformedMessagesAreIgnored() throws {
        let fixture = try Fixtures.json("bridge-events.json")
        let cases = try XCTUnwrap(fixture["ignored"] as? [Any])
        for raw in cases {
            let message = BridgeMessage(body: Fixtures.raw(raw))
            XCTAssertNil(message?.publicEvent)
        }
    }

    private func event(from json: [String: Any]) throws -> AtaliaEvent {
        let name = try XCTUnwrap(json["name"] as? String)
        func int(_ key: String) throws -> Int { try XCTUnwrap(json[key] as? Int) }
        switch name {
        case "ready": return .ready
        case "unreadChanged": return .unreadChanged(count: try int("count"))
        case "messageReceived": return .messageReceived(conversationId: try int("conversationId"), messageId: try int("messageId"))
        case "conversationStarted": return .conversationStarted(conversationId: try int("conversationId"))
        case "conversationClosed": return .conversationClosed(conversationId: try int("conversationId"))
        case "identified": return .identified(externalId: try XCTUnwrap(json["externalId"] as? String), contactId: try int("contactId"))
        case "loggedOut": return .loggedOut
        case "error": return .error(code: try XCTUnwrap(json["code"] as? String), command: json["command"] as? String)
        default:
            XCTFail("evento desconhecido na fixture: \(name)")
            return .ready
        }
    }
}
