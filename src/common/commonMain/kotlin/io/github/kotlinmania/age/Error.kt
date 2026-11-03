// port-lint: source error.rs

package io.github.kotlinmania.age

/**
 * Errors returned when converting an identity file to a recipients file.
 */
sealed class IdentityFileConvertError {
    /**
     * An I/O error occurred while writing out a recipient corresponding to an identity
     * in this file.
     */
    class FailedToWriteOutput(
        val error: Any,
    ) : IdentityFileConvertError()

    /**
     * The identity file contains a plugin identity, which can be converted to a
     * recipient for encryption purposes, but not for writing a recipients file.
     */
    class IdentityFileContainsPlugin(
        /** The given identity file. */
        val filename: String?,
        /** The name of the plugin. */
        val pluginName: String,
    ) : IdentityFileConvertError()

    /**
     * The identity file contains no identities, and thus cannot be used to produce a
     * recipients file.
     */
    class NoIdentities(
        /** The given identity file. */
        val filename: String?,
    ) : IdentityFileConvertError()

    /** Returns the underlying error, when this variant wraps one. */
    fun source(): Any? =
        when (this) {
            is FailedToWriteOutput -> error

            is IdentityFileContainsPlugin,
            is NoIdentities,
            -> null
        }

    /** Formats this error for display. */
    fun fmt(): String =
        when (this) {
            is FailedToWriteOutput -> {
                "Failed to write to output: $error"
            }

            is IdentityFileContainsPlugin -> {
                "Identity file '${filename.orEmpty()}' contains identities for 'age-plugin-$pluginName'.\n" +
                    "Try using 'age-plugin-$pluginName' to convert this identity to a recipient."
            }

            is NoIdentities -> {
                if (filename != null) {
                    "No identities found in file '$filename'."
                } else {
                    "No identities found in standard input."
                }
            }
        }

    override fun toString(): String = fmt()
}

/**
 * Errors returned by a plugin.
 */
sealed class PluginError {
    companion object {
        /** Creates a plugin error from an error stanza. */
        fun from(tag: String, args: MutableList<String>, body: ByteArray): PluginError {
            require(tag == "error") { "expected error tag" }
            val kind = args.removeAt(0)
            return Other(
                kind = kind,
                metadata = args.toList(),
                message = body.decodeToString(),
            )
        }
    }

    /** An error caused by a specific identity. */
    data class Identity(
        /** The plugin's binary name. */
        val binaryName: String,
        /** The error message. */
        val message: String,
    ) : PluginError()

    /** An error caused by a specific recipient. */
    data class Recipient(
        /** The plugin's binary name. */
        val binaryName: String,
        /** The recipient. */
        val recipient: String,
        /** The error message. */
        val message: String,
    ) : PluginError()

    /** Some other error we don't know about. */
    data class Other(
        /** The error kind. */
        val kind: String,
        /** Any metadata associated with the error. */
        val metadata: List<String>,
        /** The error message. */
        val message: String,
    ) : PluginError()

    /** Formats this error for display. */
    fun fmt(): String =
        when (this) {
            is Identity -> {
                "'$binaryName' couldn't use an identity: $message"
            }

            is Recipient -> {
                "'$binaryName' couldn't use recipient $recipient: $message"
            }

            is Other -> {
                buildString {
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

    override fun toString(): String = fmt()
}

/**
 * The various errors that can be returned during the encryption process.
 */
sealed class EncryptError {
    companion object {
        /** Lifts an I/O failure into an encryption error. */
        fun from(error: Any): EncryptError = Io(error)

        /** Lifts a decryption failure into an encryption error. */
        fun from(error: DecryptError): EncryptError = EncryptedIdentities(error)
    }

    /** An error occurred while decrypting passphrase-encrypted identities. */
    class EncryptedIdentities(
        val source: DecryptError,
    ) : EncryptError()

    /** The encryptor was given recipients that declare themselves incompatible. */
    class IncompatibleRecipients(
        /** The set of labels from the first recipient provided to the encryptor. */
        val lLabels: Set<String>,
        /** The set of labels from the first non-matching recipient. */
        val rLabels: Set<String>,
    ) : EncryptError()

    /**
     * One or more of the labels from the first recipient provided to the encryptor are
     * invalid.
     */
    class InvalidRecipientLabels(
        val labels: Set<String>,
    ) : EncryptError()

    /** An I/O error occurred during encryption. */
    class Io(
        val error: Any,
    ) : EncryptError()

    /** A required plugin could not be found. */
    class MissingPlugin(
        /** The plugin's binary name. */
        val binaryName: String,
    ) : EncryptError()

    /** The encryptor was not given any recipients. */
    object MissingRecipients : EncryptError()

    /** Recipient was mixed with other recipient types. */
    object MixedRecipientAndPassphrase : EncryptError()

    /** Errors from a plugin. */
    class Plugin(
        val errors: List<PluginError>,
    ) : EncryptError()

    /** Creates an equivalent error value. */
    fun clone(): EncryptError =
        when (this) {
            is EncryptedIdentities -> EncryptedIdentities(source.clone())
            is IncompatibleRecipients -> IncompatibleRecipients(lLabels.toSet(), rLabels.toSet())
            is InvalidRecipientLabels -> InvalidRecipientLabels(labels.toSet())
            is Io -> Io(error)
            is MissingPlugin -> MissingPlugin(binaryName)
            MissingRecipients -> MissingRecipients
            MixedRecipientAndPassphrase -> MixedRecipientAndPassphrase
            is Plugin -> Plugin(errors.toList())
        }

    /** Returns the underlying error, when this variant wraps one. */
    fun source(): Any? =
        when (this) {
            is EncryptedIdentities -> source.source()

            is Io -> error

            is IncompatibleRecipients,
            is InvalidRecipientLabels,
            is MissingPlugin,
            MissingRecipients,
            MixedRecipientAndPassphrase,
            is Plugin,
            -> null
        }

    /** Formats this error for display. */
    fun fmt(): String =
        when (this) {
            is EncryptedIdentities -> {
                source.fmt()
            }

            is IncompatibleRecipients -> {
                val lEmpty = lLabels.isEmpty()
                val rEmpty = rLabels.isEmpty()
                when {
                    lEmpty && rEmpty -> {
                        error("labels are compatible")
                    }

                    !lEmpty && rEmpty -> {
                        "Cannot encrypt to a recipient with labels '${printLabels(lLabels)}' alongside a recipient with no labels"
                    }

                    lEmpty && !rEmpty -> {
                        "Cannot encrypt to a recipient with labels '${printLabels(rLabels)}' alongside a recipient with no labels"
                    }

                    else -> {
                        "Cannot encrypt to a recipient with labels '${printLabels(lLabels)}' alongside a recipient with labels '${printLabels(rLabels)}'"
                    }
                }
            }

            is InvalidRecipientLabels -> {
                "The first recipient requires one or more invalid labels: '${printLabels(labels)}'"
            }

            is Io -> {
                error.toString()
            }

            is MissingPlugin -> {
                "Could not find '$binaryName' on the PATH.\nHave you installed the plugin?"
            }

            MissingRecipients -> {
                "Missing recipients."
            }

            MixedRecipientAndPassphrase -> {
                "scrypt::Recipient can't be used with other recipients."
            }

            is Plugin -> {
                when (errors.size) {
                    0 -> {
                        error("empty plugin error list")
                    }

                    1 -> {
                        errors[0].fmt()
                    }

                    else -> {
                        buildString {
                            append("Plugin returned multiple errors:\n")
                            for (e in errors) {
                                append("- ").append(e.fmt()).append('\n')
                            }
                        }
                    }
                }
            }
        }

    override fun toString(): String = fmt()
}

/**
 * The various errors that can be returned during the decryption process.
 */
sealed class DecryptError {
    companion object {
        /** Maps an authenticated-decryption failure into the age decryption error. */
        fun fromAuthenticatedDecryptionError(): DecryptError = DecryptionFailed

        /** Lifts an I/O failure into a decryption error. */
        fun from(error: Any): DecryptError = Io(error)

        /** Maps a header MAC failure into the age decryption error. */
        fun fromMacError(): DecryptError = InvalidMac

        /** Maps an RSA failure into the age decryption error. */
        fun fromRsaError(): DecryptError = DecryptionFailed
    }

    /** The age file failed to decrypt. */
    object DecryptionFailed : DecryptError()

    /** The age file used an excessive work factor for passphrase encryption. */
    class ExcessiveWork(
        /** The work factor required to decrypt. */
        val required: UByte,
        /** The target work factor for this device (around 1 second of work). */
        val target: UByte,
    ) : DecryptError()

    /** The age header was invalid. */
    object InvalidHeader : DecryptError()

    /** The MAC in the age header was invalid. */
    object InvalidMac : DecryptError()

    /** An I/O error occurred during decryption. */
    class Io(
        val error: Any,
    ) : DecryptError()

    /** Failed to decrypt an encrypted key. */
    object KeyDecryptionFailed : DecryptError()

    /** A required plugin could not be found. */
    class MissingPlugin(
        /** The plugin's binary name. */
        val binaryName: String,
    ) : DecryptError()

    /** None of the provided keys could be used to decrypt the age file. */
    object NoMatchingKeys : DecryptError()

    /** Errors from a plugin. */
    class Plugin(
        val errors: List<PluginError>,
    ) : DecryptError()

    /** An unknown age format, probably from a newer version. */
    object UnknownFormat : DecryptError()

    /** Creates an equivalent error value. */
    fun clone(): DecryptError =
        when (this) {
            DecryptionFailed -> DecryptionFailed
            is ExcessiveWork -> ExcessiveWork(required, target)
            InvalidHeader -> InvalidHeader
            InvalidMac -> InvalidMac
            is Io -> Io(error)
            KeyDecryptionFailed -> KeyDecryptionFailed
            is MissingPlugin -> MissingPlugin(binaryName)
            NoMatchingKeys -> NoMatchingKeys
            is Plugin -> Plugin(errors.toList())
            UnknownFormat -> UnknownFormat
        }

    /** Returns the underlying error, when this variant wraps one. */
    fun source(): Any? =
        when (this) {
            is Io -> error

            DecryptionFailed,
            is ExcessiveWork,
            InvalidHeader,
            InvalidMac,
            KeyDecryptionFailed,
            is MissingPlugin,
            NoMatchingKeys,
            is Plugin,
            UnknownFormat,
            -> null
        }

    /** Formats this error for display. */
    fun fmt(): String =
        when (this) {
            DecryptionFailed -> {
                "Decryption failed"
            }

            is ExcessiveWork -> {
                val duration = 1 shl (required.toInt() - target.toInt())
                "Excessive work parameter for passphrase.\n" +
                    "Decryption would take around $duration seconds."
            }

            InvalidHeader -> {
                "Header is invalid"
            }

            InvalidMac -> {
                "Header MAC is invalid"
            }

            is Io -> {
                error.toString()
            }

            KeyDecryptionFailed -> {
                "Failed to decrypt an encrypted key"
            }

            is MissingPlugin -> {
                "Could not find '$binaryName' on the PATH.\nHave you installed the plugin?"
            }

            NoMatchingKeys -> {
                "No matching keys found"
            }

            is Plugin -> {
                when (errors.size) {
                    0 -> {
                        error("empty plugin error list")
                    }

                    1 -> {
                        errors[0].fmt()
                    }

                    else -> {
                        buildString {
                            append("Plugin returned multiple errors:\n")
                            for (e in errors) {
                                append("- ").append(e.fmt()).append('\n')
                            }
                        }
                    }
                }
            }

            UnknownFormat -> {
                "Unknown age format.\nHave you tried upgrading to the latest version?"
            }
        }

    override fun toString(): String = fmt()
}

private fun printLabels(labels: Set<String>): String =
    buildString {
        for ((i, label) in labels.withIndex()) {
            append(label)
            if (i != 0) {
                append(", ")
            }
        }
    }
