import Testing
import Age

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
}
