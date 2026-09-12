# prune-java

`prune-java` is a conservative Java dead-code analysis tool inspired by [knip](https://knip.dev).

It is designed to help teams safely identify unused production code while accounting for Java ecosystem realities such as reflection, framework conventions, generated code, and external API contracts.

## Philosophy

- Conservative by default: prefer false negatives over false positives
- Shared analysis core across all integrations
- CI-first output and stable exit codes
- Autofix-capable architecture with explicit planning before rewrites
- Extensible design for future bytecode and framework-aware analysis

## What it detects

The analyzer reads Java sources directly (no compilation, no classpath) and reports five kinds of
issue. Every rule errs towards silence: a symbol is only reported when nothing in the project could
plausibly reach it.

| Issue type | Reported when | Autofix |
| --- | --- | --- |
| `UNUSED_CLASS` | A **package-private** top-level type, or a **non-public nested** type, is never mentioned outside its own declaration: not by an identifier in any Java file (main or test), not in a string literal, not in a resource file. Public types are never reported because an external caller cannot be ruled out, and neither are nested types of interfaces (implicitly public) or of annotated outer types (a framework may pick them up). Types carrying an annotation or a non-private `main` method are treated as framework entry points and kept. | No. Deleting a type is left to a human. |
| `UNUSED_METHOD` | A **private** method has no identifier reference anywhere in its file and its name appears in no string literal or resource. Constructors, `native` methods, and the serialization hooks (`readObject`, `writeReplace`, ...) are never reported. Annotated members are kept. | Removes the member, its attached comments, and imports that only it needed. |
| `UNUSED_FIELD` | A **private** field meets the same conditions. Fields of annotated or `Serializable` types are kept, `serialVersionUID` is kept, and instance fields are kept project-wide as soon as a reflective serialization library (Gson, Jackson, JPA, JAXB, ...) is imported anywhere. | Same, for single-declarator fields. |
| `UNUSED_VISIBILITY` | A **public class** that cannot be instantiated from outside (all constructors private, no non-static members), carries no Javadoc, and is referenced only from Java files in its own package. Reported at `INFO` severity. Skipped when the project has a `module-info.java`, when the class is annotated, or when any other package, string literal, or resource mentions it. | Drops the `public` modifier. |
| `UNUSED_DEPENDENCY` | A compile- or provided-scope jar dependency in `pom.xml` or `build.gradle(.kts)` whose packages are imported nowhere (imports, qualified names, string literals, and resources all count; a Lombok `@Slf4j`-style annotation counts for the logging API it generates against). Libraries with a known package root (Commons, Guava, Gson, Jackson, SLF4J, ...) are matched exactly; others by distinctive coordinate tokens. Runtime-only artifacts such as JDBC drivers, logging backends, starters, BOMs, annotation processors, and migration tools are never reported. | Removes the declaration together with an attached comment. |

Unused-class findings are suppressed for a type's private members and nested types, so a dead
class produces one finding rather than one per member. Findings are sorted by file, line, and
column, and locations are `path[:line[:column]]` relative to the analyzed root.

References are collected from the **whole enclosing build**: when the analyzed root sits inside a
Maven reactor or a Gradle multi-project build, sibling modules count as callers, so a module
analyzed on its own does not report a class that another module uses. Candidates always come from
`src/main` trees only; code under `src/test` is read as a source of references but never
reported. Sources that the structural parser cannot read are skipped as candidates while their
identifiers still count as references; the summary line names every skipped file so the gap is
visible (`Skipped 1 file(s) the parser could not follow: ...`). The parser reads Java as written
and does not translate `\uXXXX` escapes outside literals, so a file that spells a delimiter that
way is skipped. Exclude patterns (`--exclude`, `prune.excludes`, `excludes`) take further files
out of the candidate set, for example committed generated code; excluded files still count as
references.

Dependency removal has one known blind spot: a declaration that exists only to pin a transitive
version looks unused. Review dependency findings before applying them in a build that relies on
such pins.

## Coordinates and packages

- Group: `com.patbaumgartner`
- Base packages: `com.patbaumgartner.prune.*`
- Root project name: `prune-java`

## Module layout

- `prune-core`
  - Analysis configuration and report model
  - `ConservativeUnusedCodeAnalyzer`: source scanner, structural Java parser, reference index, and
    the rules above
  - `SourceAutofixEngine`: plans text edits from a report and applies them only while the file
    still matches what was planned
- `prune-cli`
  - Console entry point for local and CI usage
- `prune-lsp`
  - Language server that publishes `prune-core` findings as editor diagnostics over stdio and
    offers the autofixes as quick-fix code actions
- `prune-gradle-plugin`
  - Gradle plugin with `pruneCheck` and `pruneFix` tasks
  - Built by its own Gradle build, not by the Maven reactor (see below)
- `prune-maven-plugin`
  - Maven mojos with `check` and `fix` goals, under the `prune` goal prefix
- `samples/`
  - `maven-sample` and `gradle-sample`: realistic fixture projects with deliberate dead
    code and one deliberately unused dependency each, plus an `expected-findings.txt`
    ground-truth manifest that the test suite asserts exactly (see [samples/README.md](samples/README.md))

## Building

The repository contains two builds:

- a **Maven reactor at the root** for `prune-core`, `prune-cli`, `prune-lsp`, and `prune-maven-plugin`
- a **standalone Gradle build under `prune-gradle-plugin/`**, because a Gradle plugin
  needs the real Gradle API and Maven cannot supply it

Requires JDK 21 or later; all modules compile to Java 17 bytecode. Both builds use
the active JDK, so a separate JDK 17 installation is not needed. CI runs both builds
on Linux with JDK 21 and 25; other operating systems and JDK releases are not
currently covered by CI.

```bash
./mvnw install                                    # core, CLI, LSP, Maven plugin
cd prune-gradle-plugin && ./gradlew build         # Gradle plugin
```

The Gradle build resolves `prune-core` from the local repository, so `./mvnw install`
must run first — `./mvnw verify` is not enough.

`./mvnw install` also runs the Maven plugin's integration tests, which invoke the packaged
goals against `samples/maven-sample` in a real Maven process. Skip them with
`-Dinvoker.skip=true`. The Gradle build runs the equivalent TestKit check against
`samples/gradle-sample`. Neither resolves the samples' own dependencies, so both stay
offline-safe.

The Maven wrapper pins Maven 3.9.11 and the Gradle wrapper pins Gradle 9.7.1; both
verify a distribution checksum before use.

## CLI and CI usage

After `./mvnw package`:

```bash
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check                       # analyze the working directory
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --root=path/to/project
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --exclude='**/generated/**'  # repeatable glob, relative to the root
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --ci                  # GitHub annotations
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --format=json
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar fix --root=path/to/project  # apply autofixes, report the rest
```

`check` only reads. `fix` analyzes, applies every autofixable finding to the sources and
build files under the root, and then renders only the findings that still need a human. A
file that changed between planning and applying aborts the whole run without writing anything;
each rewritten file is replaced atomically with its permissions intact, and a symlink that
points outside the root is never written through. See [SECURITY.md](SECURITY.md) for the trust
model.

Output modes:

- `terminal` (default local output): one `- [SEVERITY] path:line:col :: message` line per finding
- `github` (default with `--ci`): `::warning file=...,line=...,col=...::message` workflow commands
- `json` (machine-readable output): `{"summary","conservativeMode","issues":[{type,severity,symbol,location,message,autoFixable}]}`

Exit codes:

- `0`: no issues (for `fix`: nothing left after applying fixes)
- `1`: issues reported (`check`), or issues remain that need a human (`fix`)
- `2`: usage error (unknown command, unknown option, unsupported `--format`, `--root` is not a directory, empty `--exclude`)
- `3`: the analysis or rewrite failed (unreadable root, file changed during `fix`)

### Maven plugin

Once `./mvnw install` has published it to the local repository:

```bash
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check -Dprune.failOnIssues=false -Dprune.format=json
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check -Dprune.excludes='**/generated/**,**/*Stub.java'
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:fix
```

`check` (bound to `verify` by default) fails the build with `prune-java found N unused-code
issue(s).` and logs every finding as a warning. Parameters: `failOnIssues` (`prune.failOnIssues`,
default `true`), `format` (`prune.format`, default `terminal`), `excludes` (`prune.excludes`,
comma-separated globs), `skip` (`prune.skip`). Both goals skip `pom`-packaging modules, so in a
reactor each module is analyzed exactly once. `fix` rewrites the module's sources and `pom.xml`
and logs what remains.

### Gradle plugin

```groovy
plugins {
    id 'com.patbaumgartner.prune-java'
}

pruneCheck {
    ignoreFailures = false        // default; true logs the findings without failing the build
    format = 'terminal'           // terminal, github, or json
    excludes = ['**/generated/**'] // globs relative to the project directory, default empty
}
```

`pruneCheck` (group `verification`) fails the build with the same message as the Maven goal;
`pruneFix` rewrites the project and accepts the same `format` and `excludes`. Both are
configuration-cache compatible and analyze the applying project's directory, with sibling
projects of the same build counted as callers. Apply the plugin to the root project to analyze
every subproject in one task, or to individual subprojects for one task each; doing both reports
the same findings twice.

## Language server

`prune-lsp` is a language server built on [lsp4j](https://github.com/eclipse-lsp4j/lsp4j)
that speaks the protocol over stdin/stdout. Point an editor's generic LSP client at:

```bash
java -jar prune-lsp/target/prune-lsp-0.1.0-SNAPSHOT.jar
```

On `initialized`, on every `textDocument/didSave`, and on `workspace/didChangeWatchedFiles`
it runs the same `UnusedCodeAnalyzer` the CLI uses against the first workspace folder
(falling back to `rootUri`) and publishes each `AnalysisIssue` as a diagnostic with source
`prune-java`, the `IssueType` as code, and the `Unnecessary` tag so editors fade the symbol.
The analyzer summary goes to `window/logMessage`; an analysis failure is logged as an error
and leaves the previous diagnostics in place.

Diagnostics anchor at the line and column parsed from a trailing `:line[:column]` suffix on
`AnalysisIssue.location`, and at `0:0` when the location is a bare path.

The server advertises `codeActionProvider` for the `quickfix` kind. A `textDocument/codeAction`
request carrying `prune-java` diagnostics returns one quick fix per autofixable finding, built
from the same `SourceAutofixEngine` plan the CLI applies: the member or dependency removal plus
any import it leaves unused, as a `WorkspaceEdit` against the file on disk. Unused classes get a
diagnostic but no code action. The server analyzes with the default configuration; exclude
patterns are a CLI and build-plugin option.

Still to come, on top of the same `prune-core` results:

- incremental re-analysis scoped to the saved file instead of the whole project
