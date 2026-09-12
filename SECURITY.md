# Security policy

## Supported versions

prune-java has not published a release yet; every artifact is a `0.1.0-SNAPSHOT` built from
`main`. Security fixes land on `main` and will be included in the first release. Until then,
build from the current `main` to pick them up. Once releases exist, security fixes are
released for the latest patch of the latest minor version only.

## Release integrity

Releases are built by [release.yml](.github/workflows/release.yml) from a `vX.Y.Z` tag, never
from a workstation. Every published jar is GPG-signed, carries a
[build provenance attestation](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations)
naming the workflow and commit that built it, and is reproducible: rebuilding the tag with
JDK 21 and the checked-in wrappers yields byte-identical jars, which CI checks on every
change. Verify a downloaded jar against this repository with:

```bash
gh attestation verify prune-cli-<version>.jar --repo patbaumgartner/prune-java
```

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository:
<https://github.com/patbaumgartner/prune-java/security/advisories/new>. Do not open a public
issue for a vulnerability. You should hear back within a week; the advisory is published together
with the fix.

## What prune-java trusts, and what it does not

prune-java is a local developer tool. It reads the source tree it is pointed at, never contacts
the network, and never executes anything from the analyzed project.

- **Analyzed projects are untrusted input.** Class, member, and dependency names from the
  analyzed sources end up in the report, so every output sink escapes for its own context:
  JSON strings, GitHub workflow-command properties and messages, and terminal control
  characters. `pom.xml` is read with the JDK's SAX parser with DTDs and external entities
  disabled; `build.gradle` files are only pattern-matched, never evaluated.
- **`fix` rewrites files.** `prune-java fix`, `prune:fix`, `pruneFix`, and the language server's
  quick fixes edit sources and build files under the analyzed root. Every edit is planned
  against the exact text it will replace and is abandoned if the file changed in between; files
  are replaced atomically and only when their real path is inside the analyzed root, so a
  symlink in a checked-out repository cannot redirect a rewrite outside it. Run `check` first
  and review the diff `fix` produces before committing it.
- **Findings are conservative, not proofs.** A reported symbol may still be reached through a
  mechanism the analyzer does not model (see the guards in the README). A false positive that
  deletes live code is treated as a defect; report it as a bug, not as a vulnerability.
- **The language server speaks JSON-RPC over stdio** with the editor that started it. It opens
  no sockets.

The build pins the Maven and Gradle wrapper distributions by checksum, pins GitHub Actions to
commit SHAs, runs the build with a read-only token, and has secret scanning with push protection
enabled. The one workflow with a write token, the Dependabot auto-merge, never checks out or runs
pull-request code; it only queues a merge that branch protection holds until the build passes.
The Dependency Review workflow compares dependency changes for every pull request and push to
`main`, and fails when a change introduces a high- or critical-severity vulnerability. Its
pull-request run posts a summary comment; the push run compares the previous and current commit.
