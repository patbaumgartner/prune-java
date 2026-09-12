# prune-java

[![CI](https://github.com/patbaumgartner/prune-java/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/patbaumgartner/prune-java/actions/workflows/ci.yml)
[![Release](https://github.com/patbaumgartner/prune-java/actions/workflows/release.yml/badge.svg)](https://github.com/patbaumgartner/prune-java/actions/workflows/release.yml)
[![CodeQL](https://github.com/patbaumgartner/prune-java/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/patbaumgartner/prune-java/actions/workflows/codeql.yml)
[![Dependency Review](https://github.com/patbaumgartner/prune-java/actions/workflows/dependency-review.yml/badge.svg)](https://github.com/patbaumgartner/prune-java/actions/workflows/dependency-review.yml)
[![Scorecards](https://github.com/patbaumgartner/prune-java/actions/workflows/scorecards.yml/badge.svg?branch=main)](https://github.com/patbaumgartner/prune-java/actions/workflows/scorecards.yml)

`prune-java` is a conservative Java dead-code analysis tool inspired by [knip](https://knip.dev).

It is designed to help teams safely identify unused production code while accounting for Java ecosystem realities such as reflection, framework conventions, generated code, and external API contracts.

> **Availability:** prune-java has not published a release yet. The commands below build the
> current source checkout; the Maven and Gradle plugins are not available from a remote
> repository yet.

## Start here

Use the [CLI](#cli-and-ci-usage) for a local check or CI job, the [Maven plugin](#maven-plugin)
or [Gradle plugin](#gradle-plugin) to run analysis from a build, and the
[language server](#language-server) for editor diagnostics. See [What it detects](#what-it-detects)
before acting on findings, and [SECURITY.md](SECURITY.md) for the rewrite and trust model.

The fastest way to try it from source is:

```bash
git clone https://github.com/patbaumgartner/prune-java.git
cd prune-java
./mvnw install
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --root=/path/to/your/project
```

This requires JDK 21 or later. `check` only reads the project. Exit code `1` means findings were
reported; inspect the paths and messages before deciding whether to remove code, add a baseline,
or adjust an exclusion. Use `fix` only after reviewing the rules below: it edits eligible members,
visibility modifiers, and dependency declarations. To explore the expected output, run the CLI
against [`samples/`](samples/README.md).

## Contents

- [What it detects](#what-it-detects)
- [Guards, explanations, and baselines](#guards-explanations-and-baselines)
- [Build and test the project](#building)
- [CLI and CI usage](#cli-and-ci-usage)
- [Maven plugin](#maven-plugin)
- [Gradle plugin](#gradle-plugin)
- [Language server](#language-server)
- [Contributing](CONTRIBUTING.md)
- [Sample projects](samples/README.md)
- [Security policy](SECURITY.md)

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
| `UNUSED_CLASS` | A **package-private** top-level type, or a **non-public nested** type, is never mentioned outside its own declaration: not by an identifier in any Java file (main or test), not in a string literal, not in a resource or configuration file. Public types are never reported because an external caller cannot be ruled out, and neither are nested types of interfaces (implicitly public) or of annotated outer types (a framework may pick them up). Types carrying an annotation or a non-private `main` method are treated as framework entry points and kept. | No. Deleting a type is left to a human. |
| `UNUSED_METHOD` | A **private** method has no identifier reference anywhere in its file outside its own body (a recursive call does not keep it alive) and its name appears in no string literal or resource. Constructors, `native` methods, and the serialization hooks (`readObject`, `writeReplace`, ...) are never reported. Annotated members are kept, and an annotation on a parameter counts (`private void onStop(@Observes ShutdownEvent e)` is a CDI observer). | Removes the member, its attached comments, and imports that only it needed. |
| `UNUSED_FIELD` | A **private** field meets the same conditions. Fields of annotated or `Serializable` types are kept, `serialVersionUID` is kept, and instance fields are kept project-wide as soon as a reflective serialization library (Gson, Jackson, JPA, JAXB, ...) is imported anywhere. | Same, for single-declarator fields. |
| `UNUSED_VISIBILITY` | A **public class** that cannot be instantiated from outside (all constructors private, no non-static members), carries no Javadoc, and is referenced only from Java files in its own package. Reported at `INFO` severity. Skipped when the class's own module has a `module-info.java`, when the class is annotated, or when any other package, string literal, or resource mentions it. | Drops the `public` modifier. |
| `UNUSED_DEPENDENCY` | A compile- or provided-scope jar dependency in `pom.xml` or `build.gradle(.kts)` whose packages are imported nowhere (imports, qualified names, string literals, and resources all count; a Lombok `@Slf4j`-style annotation counts for the logging API it generates against). Libraries with a known package root (Commons, Guava, Gson, Jackson, SLF4J, ...) are matched exactly; others by distinctive coordinate tokens. Runtime-only artifacts such as JDBC drivers, logging backends, starters, BOMs, annotation processors, and migration tools are never reported. A Gradle declaration may interpolate its version (`"org.slf4j:slf4j-api:${slf4jVersion}"`); one whose group or artifact is interpolated cannot be resolved and is skipped. | Removes the declaration together with an attached comment. |

Unused-class findings are suppressed for a type's private members and nested types, so a dead
class produces one finding rather than one per member. Findings are sorted by file, line, and
column, and locations are `path[:line[:column]]` relative to the analyzed root.

References are collected from the **whole enclosing build**: when the analyzed root sits inside a
Maven reactor or a Gradle multi-project build, sibling modules count as callers, so a module
analyzed on its own does not report a class that another module uses. A Maven module belongs to
the pom above it that lists it as a `<module>` or is its `<parent>`; a Gradle project belongs to
the nearest `settings.gradle(.kts)` above it unless it has its own. A pom that merely sits above
a directory does not pull that directory into its build, so an `examples/` tree with its own
aggregator is analyzed as its own reactor, not as part of the repository around it. Candidates
always come from
`src/main/java` trees only; code under `src/test` and under any other source set whose name
starts or ends with `test` (`integrationTest`, `testFixtures`, `functionalTest`) is read as a
source of test references but never reported, and every remaining source set (`jmh`, a
generated tree) counts as production code that references but is not reported either. Test
references can be switched off (`--no-test-references`, `prune.testReferences=false`,
`testReferences = false`) to find production code that only tests still reach; such findings say
`is only referenced from tests`. `UNUSED_VISIBILITY` always counts tests, because a test in
another package would stop compiling if the class lost `public`. Besides Java files, a class name
counts as referenced when it appears in a text
resource under any `src` tree, in a build file, or in machine-read configuration outside the
source trees — `*.xml`, `*.properties`, `*.yml`, `*.yaml`, and `*.MF`, so an Eclipse
`plugin.xml` or a `META-INF/MANIFEST.MF` at the project root keeps the classes it names.
Documentation does not count: a README naming a class is not a caller. Sources that the
structural parser cannot read are skipped as candidates while their
identifiers still count as references; the summary line names every skipped file so the gap is
visible (`Skipped 1 file(s) the parser could not follow: ...`). The parser reads Java as written
and does not translate `\uXXXX` escapes outside literals and comments, so a file that spells any
part of its code that way is skipped. Exclude patterns (`--exclude`, `prune.excludes`, `excludes`)
take further files out of the candidate set, for example committed generated code; excluded files
still count as references.

Dependency removal has one known blind spot: a declaration that exists only to pin a transitive
version looks unused. Review dependency findings before applying them in a build that relies on
such pins.

## Guards, explanations, and baselines

Every exception in the table above is a **guard**: a small rule that looks at one candidate the
identifier search did not clear and decides whether something outside the Java sources could
still reach it. The built-in guards run in a fixed order — `entry-point`, `annotation`,
`nested-type`, `serialization`, `native`, `annotated-owner`, `reflective-serialization`,
`javadoc`, `module-info`, `literal` — and the first one that keeps a candidate names itself.
Dependencies that are never candidates carry a reason the same way: `dependency-scope`,
`dependency-type`, `runtime-only`, or `ambiguous-coordinates`.

**Explanations** make those decisions visible. With `--explain` (`prune.explain=true`,
`explain = true`) the report also lists every candidate a guard kept, as
`+ [KEPT] path:line:col :: <Label> <name> is kept: <reason> [<guard>]` lines in terminal output
and as a `kept` array in JSON. Code that an identifier references is never listed: it was never
in doubt. Use it to answer "why was this not reported?" and to review which framework conventions
the analyzer is trusting in a given project.

```text
+ [KEPT] src/main/java/com/example/GreetingService.java:30:18 :: Private method warmUp is kept: it carries an annotation on itself or a parameter, which frameworks and reflection act on [annotation]
+ [KEPT] src/main/java/com/example/StartupListener.java:7:13 :: Class StartupListener is kept: it is named in a string literal, resource, build file, or configuration file, which reflection or a framework may resolve [literal]
```

A **baseline** records findings a project has decided to live with, so `check` only reports what
is new and `fix` never touches an accepted symbol. It is a plain text file, `prune-baseline.txt`
in the analyzed root by default (`--baseline=<file>`, `prune.baseline`, `baseline = file(...)`
choose another), with one `TYPE symbol` per line and `#` comments; a missing file is an empty
baseline. Matching ignores the location, so a moved line does not resurrect an accepted finding.
The `baseline` command, the `prune:baseline` goal, and the `pruneBaseline` task write the file
from the current findings, ignoring any existing baseline; commit the result and review the list
whenever it shrinks. Suppressed findings show up in the summary
(`N issue(s) suppressed by prune-baseline.txt`) and, with explanations on, as `[baseline]` lines.

```text
# prune-java baseline: findings this project accepts, one "TYPE symbol" per line.
UNUSED_CLASS com.example.LegacyReportBuilder
UNUSED_DEPENDENCY org.apache.commons:commons-lang3
```

**Custom guards** plug in through `java.util.ServiceLoader`. Implement
`com.patbaumgartner.prune.core.analyzer.Guard` — `id()` returns a stable name for explanations,
`keep(Candidate)` returns the reason to keep a candidate or `Optional.empty()` — list the class
in `META-INF/services/com.patbaumgartner.prune.core.analyzer.Guard`, and put the jar next to
`prune-core` (on the CLI classpath, as a plugin dependency in Maven, or on the buildscript
classpath in Gradle). Discovered guards run after the built-in ones, sorted by id; a duplicate id
fails fast. A `Candidate` is either a `TypeCandidate` or a `MemberCandidate` and exposes the parsed
declaration, the enclosing file, and an `AnalysisContext` with the project-wide facts the built-in
guards use (module descriptor present, reflective serialization in use, name mentioned in a
literal or resource). Guards can only keep candidates; they cannot add findings, so a plug-in can
never make the analyzer less conservative.

## Coordinates and packages

- Group: `com.patbaumgartner`
- Base packages: `com.patbaumgartner.prune.*`
- Root project name: `prune-java`

## Module layout

- `prune-core`
  - Analysis configuration, baseline, and report model
  - `ConservativeUnusedCodeAnalyzer`: source scanner, structural Java parser, reference index, and
    the rules above, with the exceptions as a pipeline of `Guard`s that plug-ins can extend
  - `SourceAutofixEngine`: plans text edits from a report and applies them only while the file
    still matches what was planned
- `prune-cli`
  - Console entry point for local and CI usage
- `prune-lsp`
  - Language server that publishes `prune-core` findings as editor diagnostics over stdio and
    offers the autofixes as quick-fix code actions
- `prune-gradle-plugin`
  - Gradle plugin with `pruneCheck`, `pruneFix`, and `pruneBaseline` tasks
  - Built by its own Gradle build, not by the Maven reactor (see below)
- `prune-maven-plugin`
  - Maven mojos with `check`, `fix`, and `baseline` goals, under the `prune` goal prefix
- `samples/`
  - `maven-sample` and `gradle-sample` (plain Java), `spring-boot-sample`, `quarkus-sample`,
    `helidon-sample`, and `micronaut-sample`: realistic fixture projects with deliberate dead
    code and one deliberately unused dependency each, plus an `expected-findings.txt`
    ground-truth manifest that the test suite asserts exactly, down to the guard that keeps each
    framework-owned symbol (see [samples/README.md](samples/README.md))

## Building

The repository contains two builds:

- a **Maven reactor at the root** for `prune-core`, `prune-cli`, `prune-lsp`, and `prune-maven-plugin`
- a **standalone Gradle build under `prune-gradle-plugin/`**, because a Gradle plugin
  needs the real Gradle API and Maven cannot supply it

Requires JDK 21 or later; all modules compile to Java 17 bytecode. Both builds use
the active JDK, so a separate JDK 17 installation is not needed. CI runs both builds
on Linux with JDK 21 and 25, and the Maven build once more with Maven 4 so the Maven
plugin is exercised under Maven 3.9 and 4; other operating systems and JDK releases are
not currently covered by CI. A further CI job rebuilds every published jar twice and
fails if the two are not byte-for-byte identical, which is what lets anyone rebuild a
release tag and compare it against Maven Central. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the conventions and the release procedure.

The separate [Dependency Review workflow](.github/workflows/dependency-review.yml) checks
dependency changes on every pull request and push to `main`. It fails on newly introduced
high- or critical-severity vulnerabilities; pull requests also receive a summary comment.

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

Both wrappers pin their distribution by version and SHA-256 checksum, in
[.mvn/wrapper/maven-wrapper.properties](.mvn/wrapper/maven-wrapper.properties) and
[prune-gradle-plugin/gradle/wrapper/gradle-wrapper.properties](prune-gradle-plugin/gradle/wrapper/gradle-wrapper.properties),
and refuse a download that does not match.

Both builds also check that every source is in Spring Java Format and every `pom.xml` in
sortpom order before compiling anything; `./mvnw spring-javaformat:apply sortpom:sort` and
`./gradlew format` fix a stray file. Then they run Checkstyle, Error Prone with NullAway, PMD,
SpotBugs with Find Security Bugs, forbidden-apis, and the Maven enforcer rules from the shared
rule files under [config/](config/), each failing the build on its first finding. The hand-rolled
parsers are fuzzed with [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) through
`./mvnw -pl prune-core -Pfuzz test-compile exec:exec`, and every crash it ever found is replayed
as a regular test. Finally, both builds dogfood the tool: CI analyzes every module of this
repository with the Maven plugin and the Gradle plugin it just built, so prune-java is its own
largest fixture and a private method that loses its last caller fails the build.
See [CONTRIBUTING.md](CONTRIBUTING.md#static-analysis) for all of it.

## CLI and CI usage

After `./mvnw package`:

```bash
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar --help                      # usage; `help` and no arguments do the same
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check                       # analyze the working directory
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --root=path/to/project
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --exclude='**/generated/**'  # repeatable glob, relative to the root
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --explain             # also list what a guard kept, and why
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --no-test-references  # code only tests reach is reported
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --ci                  # GitHub annotations
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --format=json
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar fix --root=path/to/project  # apply autofixes, report the rest
java -jar prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar baseline --root=path/to/project  # accept the current findings
```

`check` only reads. `fix` analyzes, applies every autofixable finding to the sources and
build files under the root, and then renders only the findings that still need a human. A
file that changed between planning and applying aborts the whole run without writing anything;
each rewritten file is replaced atomically with its permissions intact, and a symlink that
points outside the root is never written through. See [SECURITY.md](SECURITY.md) for the trust
model. `baseline` writes every current finding to `prune-baseline.txt` under the root
(`--baseline=<file>` names another file, for all three commands) and prints
`Wrote N finding(s) to <file>`; `check` and `fix` then leave those findings alone.

Output modes:

- `terminal` (default local output): one `- [SEVERITY] path[:line[:column]] :: message` line per finding, then a `Summary:` line; with `--explain`, `+ [KEPT] ... [guard]` lines first
- `github` (default with `--ci`): `::warning file=...,line=...,col=...::message` workflow commands; kept symbols are not annotations and are omitted
- `json` (machine-readable output): `{"summary","conservativeMode","issues":[{type,severity,symbol,location,message,autoFixable}],"kept":[{type,symbol,location,guard,message}]}` — `kept` is always present and empty unless `--explain` is given
- `sarif` (GitHub code scanning): one [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html) run with a rule per issue type, a result per finding whose `partialFingerprints.symbol` keeps an alert stable when its line moves, `level` mapped `INFO→note`, `WARNING→warning`, `ERROR→error`, paths as `%SRCROOT%`-relative URIs, and the summary in the run's `properties`; kept symbols are omitted like in `github`. Upload it with `github/codeql-action/upload-sarif`

Exit codes:

- `0`: no issues (for `fix`: nothing left after applying fixes; for `baseline`: the file was written)
- `1`: issues reported (`check`), or issues remain that need a human (`fix`)
- `2`: usage error (unknown command, unknown option, unsupported `--format`, `--root` is not a directory, empty or invalid `--exclude`, empty `--baseline`)
- `3`: the analysis or rewrite failed (unreadable root, malformed baseline file, file changed during `fix`, baseline file not writable)

### Maven plugin

Add the plugin to a named Maven profile so you can opt in with `-Pprune`. Put the version in your
existing `<properties>` section, then add this profile under `<profiles>` in your project's
`pom.xml`:

```xml
<properties>
  <prune-java.version>0.1.0-SNAPSHOT</prune-java.version>
</properties>

<profiles>
  <profile>
    <id>prune</id>
    <build>
      <plugins>
        <plugin>
          <groupId>com.patbaumgartner</groupId>
          <artifactId>prune-maven-plugin</artifactId>
          <version>${prune-java.version}</version>
          <executions>
            <execution>
              <id>check-unused-code</id>
              <goals>
                <goal>check</goal>
              </goals>
            </execution>
          </executions>
          <configuration>
            <format>terminal</format>
            <testReferences>true</testReferences>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

The `check` goal is bound to `verify`, so run `mvn -Pprune verify` locally or in CI. Without the
profile, your normal Maven build does not run prune-java. Add `<failOnIssues>false</failOnIssues>`
inside `<configuration>` to report findings without failing the build. Other options include
`<excludes>**/generated/**</excludes>`, `<baseline>prune-baseline.txt</baseline>`,
`<testReferences>false</testReferences>`, and `<explain>true</explain>`; the corresponding
`prune.*` properties can also be set on the command line.

The current `0.1.0-SNAPSHOT` is not published remotely. To try it, build this repository with JDK
21 or later and install the Maven plugin into your local repository:

```bash
./mvnw install
```

Then run the profile from your project directory. `install` also installs the plugin's `prune-core`
dependency. Once a release is available, use its released version instead of
`0.1.0-SNAPSHOT`.

For a one-off run without editing the project's POM, invoke the goal directly:

```bash
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check -Dprune.failOnIssues=false -Dprune.format=json
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check -Dprune.excludes='**/generated/**,**/*Stub.java'
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check -Dprune.explain=true -Dprune.testReferences=false
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:fix
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:baseline
```

`check` (bound to `verify` by default) fails the build with `prune-java found N unused-code
issue(s).` and logs every finding as a warning. Parameters: `failOnIssues` (`prune.failOnIssues`,
default `true`, `check` only), `format` (`prune.format`, default `terminal`), `excludes`
(`prune.excludes`, comma-separated globs), `baseline` (`prune.baseline`, default
`prune-baseline.txt` in the module), `testReferences` (`prune.testReferences`, default `true`),
`explain` (`prune.explain`, default `false`, `check` only), `skip` (`prune.skip`). All goals skip
`pom`-packaging modules, so in a reactor each module is analyzed exactly once. `fix` rewrites the
module's sources and `pom.xml` and logs what remains; `baseline` writes the module's current
findings to its baseline file.

### Gradle plugin

Gradle has no Maven-style profiles. Apply the plugin in `build.gradle` (or `build.gradle.kts`) and
run its tasks when you want analysis. To add the current unpublished snapshot, first build and
install the core and plugin from this repository:

```bash
./mvnw install
cd prune-gradle-plugin
./gradlew publishToMavenLocal
```

In the consuming build, add `mavenLocal()` to `pluginManagement` in `settings.gradle` so Gradle can
resolve the locally published plugin marker. Keep `gradlePluginPortal()` for other plugins:

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

```groovy
plugins {
    id 'com.patbaumgartner.prune-java' version '0.1.0-SNAPSHOT'
}

pruneCheck {
    ignoreFailures = false        // default; true logs the findings without failing the build
    format = 'terminal'           // terminal, github, json, or sarif
    excludes = ['**/generated/**'] // globs relative to the project directory, default empty
    baseline = layout.projectDirectory.file('prune-baseline.txt')  // default
    testReferences = true         // default; false reports code that only tests reach
    explain = false               // default; true also logs what each guard kept
}
```

The plugin is applied when Gradle configures the project, but analysis runs only when you request
a prune task. Run `./gradlew pruneCheck` to check, `./gradlew pruneFix` to apply available fixes,
or `./gradlew pruneBaseline` to write the baseline. To make the check part of the standard
verification lifecycle, add this to `build.gradle`:

```groovy
tasks.named('check') {
    dependsOn('pruneCheck')
}
```

Now `./gradlew check` runs both the project's checks and prune-java. Omit this task wiring if you
prefer to run `pruneCheck` explicitly.

`pruneCheck` (group `verification`) fails the build with the same message as the Maven goal
unless `ignoreFailures` is set, and takes `format`, `excludes`, `baseline`, `testReferences`,
and `explain` as shown above; `pruneFix` rewrites the project and accepts the same `format`,
`excludes`, `baseline`, and `testReferences`; `pruneBaseline` takes `excludes`, `baseline`, and
`testReferences`, writes the current findings to the `baseline` file, and always runs, because
its real input is every source of the build. All three are
configuration-cache compatible and analyze the applying project's directory, with sibling
projects of the same build counted as callers. Apply the plugin to the root project to analyze
every subproject in one task, or to individual subprojects for one task each; doing both reports
the same findings twice.

To run the tasks against a build that does not apply the plugin, pass
[prune-java.init.gradle](prune-gradle-plugin/prune-java.init.gradle) as an init script:
`gradle -I path/to/prune-gradle-plugin/prune-java.init.gradle pruneCheck`. It applies the plugin to
the root project from the local repository, so `publishToMavenLocal` has to have run first; see
[samples/README.md](samples/README.md#running-the-tools-by-hand).

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
diagnostic but no code action. The server analyzes with the default configuration, which applies a
`prune-baseline.txt` in the workspace root when present; exclude patterns, explanations, and the
test-reference switch are CLI and build-plugin options.
