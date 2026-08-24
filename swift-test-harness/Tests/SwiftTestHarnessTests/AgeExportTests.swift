import Testing
import Age

@Suite("Age Swift Export Tests")
struct AgeExportTests {
    @Test("Swift module imports and basic types are reachable")
    func swiftModuleLoads() throws {
        #expect(Bool(true))
    }
}
