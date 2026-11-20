import Testing
import Age
import KotlinStdlib

@Suite("Age Swift Export Tests")
struct AgeExportTests {
    @Test("Plugin errors expose their data and display text")
    func pluginErrorsPreserveMessages() throws {
        let identity = PluginError.Identity(binaryName: "age-plugin-demo", message: "denied")
        #expect(identity.binaryName == "age-plugin-demo")
        #expect(identity.fmt() == "'age-plugin-demo' couldn't use an identity: denied")

        let recipient = PluginError.Recipient(
            binaryName: "age-plugin-demo", recipient: "age1recipient", message: "denied"
        )
        #expect(recipient.recipient == "age1recipient")
        #expect(recipient.fmt() == "'age-plugin-demo' couldn't use recipient age1recipient: denied")

        let other = PluginError.Other(kind: "plugin", metadata: ["metadata"], message: "failed")
        #expect(other.metadata == ["metadata"])
        #expect(other.fmt() == "(plugin metadata) failed")
    }

    @Test("Armor helpers round-trip binary and ASCII-armored payloads and report canonical errors")
    func armorRoundTripsAndErrorMessages() throws {
        let payload = kotlin.ByteArray(size: 5)
        payload._set(index: 0, value: 104) // 'h'
        payload._set(index: 1, value: 101) // 'e'
        payload._set(index: 2, value: 108) // 'l'
        payload._set(index: 3, value: 108) // 'l'
        payload._set(index: 4, value: 111) // 'o'

        let armored = primitives.armorEncode(data: payload, format: primitives.Format.AsciiArmor.shared)
        let decoded = primitives.armorDecode(armored: armored)
        #expect(decoded.isSuccess)
        let decodedBytes = try #require(decoded.data)
        #expect(decodedBytes.size == 5)
        #expect(decodedBytes._get(index: 0) == 104)
        #expect(decodedBytes._get(index: 4) == 111)

        let binary = primitives.armorEncode(data: payload, format: primitives.Format.Binary.shared)
        #expect(binary.size == 5)

        #expect(primitives.ArmoredReadError.InvalidBeginMarker.shared.fmt() == "invalid armor begin marker")
        #expect(primitives.ArmoredReadError.TrailingGarbage.shared.fmt() == "invalid armor (non-whitespace characters after end marker)")
    }
}
