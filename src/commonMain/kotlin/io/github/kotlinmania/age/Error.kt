// port-lint: source src/error.rs
package io.github.kotlinmania.age

/**
 * Error type.
 *
 * The upstream Rust error types delegate their `Display` impls to the `wfl!` / `wlnfl!`
 * macros, which resolve to localized strings out of the `i18n/<locale>/age.ftl` Fluent
 * bundle. There is no i18n sibling in the kotlinmania workspace yet, so the English text
 * from `i18n/en-US/age.ftl` is inlined directly into each variant's [Throwable.message]
 * here. When an i18n port lands, callers will be able to route through that instead.
 *
 * Upstream uses `std::io::Error` to carry I/O failures (kind + message + cause). Kotlin's
 * stdlib does not have a portable analog, so the [EncryptError.Io] and [DecryptError.Io]
 * variants below carry a plain [Throwable]. Cloning an I/O failure in upstream rebuilds
 * it from kind+message; Kotlin shares the [Throwable] by reference instead.
 *
 * The upstream `From<chacha20poly1305::aead::Error>`, `From<hmac::digest::MacError>` and
 * `From<rsa::errors::Error>` conversions on [DecryptError] desugar Rust's `?` operator
 * across crypto crates. Kotlin has no implicit conversion trait, so call sites that
 * previously relied on `?` construct the appropriate [DecryptError] variant directly
 * ([DecryptError.DecryptionFailed], [DecryptError.InvalidMac], etc.). Those construction
 * idioms live with the crypto call sites, not in this file.
 *
 * The plugin-feature variants ([PluginError], [IdentityFileConvertError.IdentityFileContainsPlugin],
 * [EncryptError.MissingPlugin], [EncryptError.Plugin], [DecryptError.MissingPlugin] and
 * [DecryptError.Plugin]) were originally feature-gated behind `feature = "plugin"`. Kotlin
 * does not have crate features, so they are present unconditionally; the parsing
 * conversion from `age_core::format::Stanza` will land once `age-core` is ported.
 */

/** Errors returned when converting an identity file to a recipients file. */
sealed class IdentityFileConvertError(message: String, cause: Throwable? = null) :
    Throwable(message, cause) {

    /**
     * An I/O error occurred while writing out a recipient corresponding to an identity
     * in this file.
     */
    class FailedToWriteOutput(val error: Throwable) :
        IdentityFileConvertError("Failed to write to output: $error", error)

    /**
     * The identity file contains a plugin identity, which can be converted to a
     * recipient for encryption purposes, but not for writing a recipients file.
     */
    class IdentityFileContainsPlugin(
        /** The given identity file. */
        val filename: String?,
        /** The name of the plugin. */
        val pluginName: String,
    ) : IdentityFileConvertError(
        "Identity file '${filename ?: ""}' contains identities for 'age-plugin-$pluginName'.\n" +
            "Try using 'age-plugin-$pluginName' to convert this identity to a recipient.",
    )

    /**
     * The identity file contains no identities, and thus cannot be used to produce a
     * recipients file.
     */
    class NoIdentities(
        /** The given identity file. */
        val filename: String?,
    ) : IdentityFileConvertError(
        when (filename) {
            null -> "No identities found in standard input."
            else -> "No identities found in file '$filename'."
        },
    )
}

/** Errors returned by a plugin. */
sealed class PluginError {
    /** An error caused by a specific identity. */
    data class Identity(
        /** The plugin's binary name. */
        val binaryName: String,
        /** The error message. */
        val message: String,
    ) : PluginError() {
        override fun toString(): String = "'$binaryName' couldn't use an identity: $message"
    }

    /** An error caused by a specific recipient. */
    data class Recipient(
        /** The plugin's binary name. */
        val binaryName: String,
        /** The recipient. */
        val recipient: String,
        /** The error message. */
        val message: String,
    ) : PluginError() {
        override fun toString(): String =
            "'$binaryName' couldn't use recipient $recipient: $message"
    }

    /** Some other error we don't know about. */
    data class Other(
        /** The error kind. */
        val kind: String,
        /** Any metadata associated with the error. */
        val metadata: List<String>,
        /** The error message. */
        val message: String,
    ) : PluginError() {
        override fun toString(): String = buildString {
            append('(').append(kind)
            for (d in metadata) {
                append(' ').append(d)
            }
            append(')')
            if (message.isNotEmpty()) {
                append(' ').append(message)
            }
        }
    }
}

/** The various errors that can be returned during the encryption process. */
sealed class EncryptError(message: String, cause: Throwable? = null) :
    Throwable(message, cause) {

    /** An error occured while decrypting passphrase-encrypted identities. */
    class EncryptedIdentities(val source: DecryptError) :
        EncryptError(source.toString(), source)

    /** The encryptor was given recipients that declare themselves incompatible. */
    class IncompatibleRecipients(
        /** The set of labels from the first recipient provided to the encryptor. */
        val lLabels: Set<String>,
        /** The set of labels from the first non-matching recipient. */
        val rLabels: Set<String>,
    ) : EncryptError(formatMessage(lLabels, rLabels)) {
        companion object {
            internal fun formatMessage(lLabels: Set<String>, rLabels: Set<String>): String {
                val lEmpty = lLabels.isEmpty()
                val rEmpty = rLabels.isEmpty()
                return when {
                    lEmpty && rEmpty -> error("labels are compatible")
                    !lEmpty && rEmpty ->
                        "Cannot encrypt to a recipient with labels '${printLabels(lLabels)}' alongside a recipient with no labels"
                    lEmpty && !rEmpty ->
                        "Cannot encrypt to a recipient with labels '${printLabels(rLabels)}' alongside a recipient with no labels"
                    else ->
                        "Cannot encrypt to a recipient with labels '${printLabels(lLabels)}' alongside a recipient with labels '${printLabels(rLabels)}'"
                }
            }
        }
    }

    /**
     * One or more of the labels from the first recipient provided to the encryptor are
     * invalid.
     *
     * Labels must be valid age "arbitrary string"s (`1*VCHAR` in ABNF).
     */
    class InvalidRecipientLabels(val labels: Set<String>) :
        EncryptError(
            "The first recipient requires one or more invalid labels: '${printLabels(labels)}'",
        )

    /** An I/O error occurred during encryption. */
    class Io(val error: Throwable) : EncryptError(error.toString(), error)

    /** A required plugin could not be found. */
    class MissingPlugin(
        /** The plugin's binary name. */
        val binaryName: String,
    ) : EncryptError(
        "Could not find '$binaryName' on the PATH.\nHave you installed the plugin?",
    )

    /** The encryptor was not given any recipients. */
    object MissingRecipients : EncryptError("Missing recipients.")

    /** [io.github.kotlinmania.age.scrypt.Recipient] was mixed with other recipient types. */
    object MixedRecipientAndPassphrase :
        EncryptError("scrypt::Recipient can't be used with other recipients.")

    /** Errors from a plugin. */
    class Plugin(val errors: List<PluginError>) : EncryptError(formatMessage(errors)) {
        companion object {
            internal fun formatMessage(errors: List<PluginError>): String =
                when (errors.size) {
                    0 -> error("empty plugin error list")
                    1 -> errors[0].toString()
                    else -> buildString {
                        append("Plugin returned multiple errors:\n")
                        for (e in errors) {
                            append("- ").append(e).append('\n')
                        }
                    }
                }
        }
    }
}

/** The various errors that can be returned during the decryption process. */
sealed class DecryptError(message: String, cause: Throwable? = null) :
    Throwable(message, cause) {

    /** The age file failed to decrypt. */
    object DecryptionFailed : DecryptError("Decryption failed")

    /** The age file used an excessive work factor for passphrase encryption. */
    class ExcessiveWork(
        /** The work factor required to decrypt. */
        val required: UByte,
        /** The target work factor for this device (around 1 second of work). */
        val target: UByte,
    ) : DecryptError(formatMessage(required, target)) {
        companion object {
            internal fun formatMessage(required: UByte, target: UByte): String {
                val duration = 1 shl (required.toInt() - target.toInt())
                return "Excessive work parameter for passphrase.\n" +
                    "Decryption would take around $duration seconds."
            }
        }
    }

    /** The age header was invalid. */
    object InvalidHeader : DecryptError("Header is invalid")

    /** The MAC in the age header was invalid. */
    object InvalidMac : DecryptError("Header MAC is invalid")

    /** An I/O error occurred during decryption. */
    class Io(val error: Throwable) : DecryptError(error.toString(), error)

    /** Failed to decrypt an encrypted key. */
    object KeyDecryptionFailed : DecryptError("Failed to decrypt an encrypted key")

    /** A required plugin could not be found. */
    class MissingPlugin(
        /** The plugin's binary name. */
        val binaryName: String,
    ) : DecryptError(
        "Could not find '$binaryName' on the PATH.\nHave you installed the plugin?",
    )

    /** None of the provided keys could be used to decrypt the age file. */
    object NoMatchingKeys : DecryptError("No matching keys found")

    /** Errors from a plugin. */
    class Plugin(val errors: List<PluginError>) : DecryptError(formatMessage(errors)) {
        companion object {
            internal fun formatMessage(errors: List<PluginError>): String =
                when (errors.size) {
                    0 -> error("empty plugin error list")
                    1 -> errors[0].toString()
                    else -> buildString {
                        append("Plugin returned multiple errors:\n")
                        for (e in errors) {
                            append("- ").append(e).append('\n')
                        }
                    }
                }
        }
    }

    /** An unknown age format, probably from a newer version. */
    object UnknownFormat : DecryptError(
        "Unknown age format.\nHave you tried upgrading to the latest version?",
    )
}

private fun printLabels(labels: Set<String>): String = buildString {
    for ((i, label) in labels.withIndex()) {
        append(label)
        if (i != 0) {
            append(", ")
        }
    }
}
