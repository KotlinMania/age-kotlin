# age-kotlin in Kotlin

[![GitHub link](https://img.shields.io/badge/GitHub-KotlinMania%2Fage--kotlin-blue.svg)](https://github.com/KotlinMania/age-kotlin)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kotlinmania/age-kotlin)](https://central.sonatype.com/artifact/io.github.kotlinmania/age-kotlin)
[![Build status](https://img.shields.io/github/actions/workflow/status/KotlinMania/age-kotlin/ci.yml?branch=main)](https://github.com/KotlinMania/age-kotlin/actions)

This is a Kotlin Multiplatform line-by-line transliteration port of [`str4d/rage`](https://github.com/str4d/rage).

**Original Project:** This port is based on [`str4d/rage`](https://github.com/str4d/rage). All design credit and project intent belong to the upstream authors; this repository is a faithful port to Kotlin Multiplatform with no behavioural changes intended.

### Porting status

This is an **in-progress port**. The goal is feature parity with the upstream Rust crate while providing a native Kotlin Multiplatform API. Every Kotlin file carries a `// port-lint: source <path>` header naming its upstream Rust counterpart so the AST-distance tool can track provenance.

---

## Upstream README — `str4d/rage`

> The text below is reproduced and lightly edited from [`https://github.com/str4d/rage`](https://github.com/str4d/rage). It is the upstream project's own description and remains under the upstream authors' authorship; links have been rewritten to absolute upstream URLs so they continue to resolve from this repository.

<p align="center"><img alt="The age logo, an wireframe of St. Peters dome in Rome, with the text: age, file encryption" width="600" src="https://user-images.githubusercontent.com/1225294/132245842-fda4da6a-1cea-4738-a3da-2dc860861c98.png"></p>

## rage: Rust implementation of age

rage is a simple, modern, and secure file encryption tool, using the *age*
format. It features small explicit keys, no config options, and UNIX-style
composability.

The format specification is at [age-encryption.org/v1](https://age-encryption.org/v1).
age was designed by [@Benjojo](https://benjojo.co.uk/) and
[@FiloSottile](https://bsky.app/profile/did:plc:x2nsupeeo52oznrmplwapppl).

The reference interoperable Go implementation is available at
[filippo.io/age](https://filippo.io/age).

Hardware PIV tokens such as YubiKeys are supported through the
[age-plugin-yubikey](https://github.com/str4d/age-plugin-yubikey) plugin.

For more plugins, implementations, tools, and integrations, check out the
[awesome age](https://github.com/FiloSottile/awesome-age) list.

## Installation

| Environment | CLI command |
|-------------|-------------|
| Cargo (Rust 1.74+) | `cargo install rage` |
| Homebrew (macOS or Linux) | `brew install rage` |
| MacPorts | `port install rage` |
| Alpine Linux (edge) | `apk add rage` |
| Arch Linux | `pacman -S rage-encryption` |
| Debian | [Debian packages](https://github.com/str4d/rage/releases) |
| NixOS | Add to config: `environment.systemPackages = [ pkgs.rage ];`<br>Or run `nix-env -i rage` |
| openSUSE Tumbleweed | `zypper install rage-encryption` |
| Ubuntu 22.04+ | [Debian packages](https://github.com/str4d/rage/releases) |
| FreeBSD | `pkg install rage-encryption` |
| Scoop (Windows) | `scoop bucket add main`<br>`scoop install main/rage` |

On Windows, Linux, and macOS, you can use the
[pre-built binaries](https://github.com/str4d/rage/releases).

> Help from new packagers is very welcome. Please use the package name `rage`;
> the fallback package name `rage-encryption` should be used only when there are
> *unavoidable* name conflicts in package systems that use global namespaces. Do
> not rename any binaries; instead use your package system's conflicting package
> mechanism to prevent installation of both packages at once.

## Usage

```
Usage: rage [--encrypt] (-r RECIPIENT | -R PATH)... [-i IDENTITY] [-a] [-o OUTPUT] [INPUT]
       rage [--encrypt] --passphrase [-a] [-o OUTPUT] [INPUT]
       rage --decrypt [-i IDENTITY] [-o OUTPUT] [INPUT]

Arguments:
  [INPUT]  Path to a file to read from.

Options:
  -h, --help                    Print this help message and exit.
  -V, --version                 Print version info and exit.
  -e, --encrypt                 Encrypt the input (the default).
  -d, --decrypt                 Decrypt the input.
  -p, --passphrase              Encrypt with a passphrase instead of recipients.
      --max-work-factor <WF>    Maximum work factor to allow for passphrase decryption.
  -a, --armor                   Encrypt to a PEM encoded format.
  -r, --recipient <RECIPIENT>   Encrypt to the specified RECIPIENT. May be repeated.
  -R, --recipients-file <PATH>  Encrypt to the recipients listed at PATH. May be repeated.
  -i, --identity <IDENTITY>     Use the identity file at IDENTITY. May be repeated.
  -j <PLUGIN-NAME>              Use age-plugin-PLUGIN-NAME in its default mode as an identity.
  -o, --output <OUTPUT>         Write the result to the file at path OUTPUT.

INPUT defaults to standard input, and OUTPUT defaults to standard output.
If OUTPUT exists, it will be overwritten.

RECIPIENT can be:
- An age public key, as generated by rage-keygen ("age1...").
- An SSH public key ("ssh-ed25519 AAAA...", "ssh-rsa AAAA...").

PATH is a path to a file containing age recipients, one per line
(ignoring "#" prefixed comments and empty lines). "-" may be used to
read recipients from standard input.

IDENTITY is a path to a file with age identities, one per line
(ignoring "#" prefixed comments and empty lines), or to an SSH key file.
Passphrase-encrypted age identity files can be used as identity files.
Multiple identities may be provided, and any unused ones will be ignored.
"-" may be used to read identities from standard input.
```

### Multiple recipients

Files can be encrypted to multiple recipients by repeating `-r/--recipient`.
Every recipient will be able to decrypt the file.

```bash
$ rage -o example.png.age -r age1uvscypafkkxt6u2gkguxet62cenfmnpc0smzzlyun0lzszfatawq4kvf2u \
    -r age1ex4ty8ppg02555at009uwu5vlk5686k3f23e7mac9z093uvzfp8sxr5jum example.png
```

#### Recipient files

Multiple recipients can also be listed one per line in one or more files passed
with the `-R/--recipients-file` flag.

```
$ cat recipients.txt
# Alice
age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p
# Bob
age1lggyhqrw2nlhcxprm67z43rta597azn8gknawjehu9d9dl0jq3yqqvfafg
$ rage -R recipients.txt example.jpg > example.jpg.age
```

If the argument to `-R` (or `-i`) is `-`, the file is read from standard input.

### Passphrases

Files can be encrypted with a passphrase by using `-p/--passphrase`. By default
rage will automatically generate a secure passphrase.

```bash
$ rage -p -o example.png.age example.png
Type passphrase (leave empty to autogenerate a secure one): [hidden]
Using an autogenerated passphrase:
    kiwi-general-undo-bubble-dwarf-dizzy-fame-side-sunset-sibling
$ rage -d example.png.age >example.png
Type passphrase: [hidden]
```

If a binary named `pinentry` is available in `$PATH`, it will be used to ask the
user for a passphrase. The `PINENTRY_PROGRAM` environment variable can be used
to set the binary name or path to use. If a `pinentry` binary is not available,
or `PINENTRY_PROGRAM` is set to the empty string, `rage` will fall back to the
CLI instead.

### Passphrase-protected identity files

If an identity file passed to `-i/--identity` is a passphrase-encrypted age
file, it will be automatically decrypted.

```
$ rage -p -o key.age <(rage-keygen)
Public key: age1pymw5hyr39qyuc950tget63aq8vfd52dclj8x7xhm08g6ad86dkserumnz
Type passphrase (leave empty to autogenerate a secure one): [hidden]
Using an autogenerated passphrase:
    flash-bean-celery-network-curious-flower-salt-amateur-fence-giant
$ rage -r age1pymw5hyr39qyuc950tget63aq8vfd52dclj8x7xhm08g6ad86dkserumnz secrets.txt > secrets.txt.age
$ rage -d -i key.age secrets.txt.age > secrets.txt
Type passphrase: [hidden]
```

Passphrase-protected identity files are not necessary for most use cases, where
access to the encrypted identity file implies access to the whole system.
However, they can be useful if the identity file is stored remotely.

### SSH keys

As a convenience feature, rage also supports encrypting to `ssh-rsa` and
`ssh-ed25519` SSH public keys, and decrypting with the respective private key
file. (`ssh-agent` is not supported.)

```
$ rage -R ~/.ssh/id_ed25519.pub example.png > example.png.age
$ rage -d -i ~/.ssh/id_ed25519 example.png.age > example.png
```

Note that SSH key support employs more complex cryptography, and embeds a public
key tag in the encrypted file, making it possible to track files that are
encrypted to a specific public key.

### Feature flags

When building with Cargo, you can configure rage using `--no-default-features`
and `--features comma,separated,flags` to enable or disable the following
feature flags:

- `mount` enables the `rage-mount` tool, which can mount age-encrypted TAR or
  ZIP archives as read-only. It is currently only usable on Unix systems, as it
  relies on `libfuse`.

- `ssh` (enabled by default) enables support for reusing existing SSH key files
  for age encryption.

- `unstable` enables in-development functionality. Anything behind this feature
  flag has no stability or interoperability guarantees.

## Rust Library

Applications wishing to use rage as a library should use the [`age`](https://crates.io/crates/age)
crate, which rage is built on top of.

## License

Licensed under either of

 * Apache License, Version 2.0, ([LICENSE-APACHE](https://github.com/str4d/rage/blob/HEAD/LICENSE-APACHE) or
   http://www.apache.org/licenses/LICENSE-2.0)
 * MIT license ([LICENSE-MIT](https://github.com/str4d/rage/blob/HEAD/LICENSE-MIT) or http://opensource.org/licenses/MIT)

at your option.

### Contribution

Unless you explicitly state otherwise, any contribution intentionally
submitted for inclusion in the work by you, as defined in the Apache-2.0
license, shall be dual licensed as above, without any additional terms or
conditions.

---

## About this Kotlin port

### Installation

```kotlin
dependencies {
    implementation("io.github.kotlinmania:age-kotlin:0.1.0")
}
```

### Building

```bash
./gradlew build
./gradlew test
```

### Targets

- macOS arm64
- Linux x64
- Windows mingw-x64
- iOS arm64 / simulator-arm64 (Swift export + XCFramework)
- JS (browser + Node.js)
- Wasm-JS (browser + Node.js)
- Android (API 24+)

### Porting guidelines

See [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md) for translator discipline, port-lint header convention, and Rust → Kotlin idiom mapping.

### License

This Kotlin port is distributed under the same MIT license as the upstream [`str4d/rage`](https://github.com/str4d/rage). See [LICENSE](LICENSE) (and any sibling `LICENSE-*` / `NOTICE` files mirrored from upstream) for the full text.

Original work copyrighted by the rage authors.  
Kotlin port: Copyright (c) 2026 Sydney Renee and The Solace Project.

### Acknowledgments

Thanks to the [`str4d/rage`](https://github.com/str4d/rage) maintainers and contributors for the original Rust implementation. This port reproduces their work in Kotlin Multiplatform; bug reports about upstream design or behavior should go to the upstream repository.
