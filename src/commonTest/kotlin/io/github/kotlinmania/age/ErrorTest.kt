// port-lint: ignore
// Exercises translated error.rs display behavior.
package io.github.kotlinmania.age

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ErrorTest {
    @Test
    fun identityFileConvertErrorFormatsPluginAndMissingIdentityMessages() {
        assertEquals(
            "Identity file 'ids.txt' contains identities for 'age-plugin-yubikey'.\n" +
                "Try using 'age-plugin-yubikey' to convert this identity to a recipient.",
            IdentityFileConvertError.IdentityFileContainsPlugin("ids.txt", "yubikey").toString(),
        )
        assertEquals(
            "No identities found in standard input.",
            IdentityFileConvertError.NoIdentities(null).toString(),
        )
    }

    @Test
    fun encryptErrorFormatsRecipientAndPluginMessages() {
        assertEquals(
            "Cannot encrypt to a recipient with labels 'work' alongside a recipient with no labels",
            EncryptError.IncompatibleRecipients(setOf("work"), emptySet()).toString(),
        )
        assertEquals(
            "The first recipient requires one or more invalid labels: 'bad'",
            EncryptError.InvalidRecipientLabels(setOf("bad")).toString(),
        )
        assertEquals(
            "Plugin returned multiple errors:\n" +
                "- (plugin metadata) failed\n" +
                "- 'age-plugin-demo' couldn't use an identity: denied\n",
            EncryptError
                .Plugin(
                    listOf(
                        PluginError.Other("plugin", listOf("metadata"), "failed"),
                        PluginError.Identity("age-plugin-demo", "denied"),
                    ),
                ).toString(),
        )
    }

    @Test
    fun decryptErrorFormatsWorkPluginAndUnknownFormatMessages() {
        assertEquals(
            "Excessive work parameter for passphrase.\n" +
                "Decryption would take around 4 seconds.",
            DecryptError.ExcessiveWork(12u, 10u).toString(),
        )
        assertEquals(
            "Could not find 'age-plugin-demo' on the PATH.\nHave you installed the plugin?",
            DecryptError.MissingPlugin("age-plugin-demo").toString(),
        )
        assertEquals(
            "Unknown age format.\nHave you tried upgrading to the latest version?",
            DecryptError.UnknownFormat.toString(),
        )
    }

    @Test
    fun sourceReturnsWrappedThrowableOnlyForIoLikeVariants() {
        val failure = IllegalStateException("disk")
        assertSame(
            failure,
            IdentityFileConvertError.FailedToWriteOutput(failure).source(),
        )
        assertSame(failure, EncryptError.Io(failure).source())
        assertSame(failure, DecryptError.Io(failure).source())
        assertEquals(null, EncryptError.MissingRecipients.source())
        assertEquals(null, DecryptError.NoMatchingKeys.source())
    }
}
