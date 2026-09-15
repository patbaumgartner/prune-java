# Sample projects

Six small but realistic projects used as analyzer fixtures and integration-test inputs. Each contains deliberate dead code and exactly one deliberately unused dependency, and each carries a ground-truth manifest the test suite asserts exactly.

| Sample | Build | Stands in for | Proves |
| --- | --- | --- | --- |
| `maven-sample` | Maven | A plain Java library (Gson 2.14, Commons Lang 3.20) | The five rules on unannotated code, driven end to end through the Maven plugin |
| `gradle-sample` | Gradle | A plain Java application (SLF4J 2.0.19, Commons IO 2.22) | The same rules through the Gradle plugin, plus a class only a test still reaches |
| `spring-boot-sample` | Maven, `spring-boot-starter-parent` 4.1.1 | A Spring Boot 4 web application (Commons Text 1.15) | Component-scanned `@RestController`/`@Configuration`/`@Service` beans, private `@PostConstruct`/`@PreDestroy` callbacks, a listener registered only in `META-INF/spring.factories`, starters that are never candidates |
| `quarkus-sample` | Maven, `quarkus-bom` 3.39.3 | A Quarkus REST application | Package-private `@ApplicationScoped` beans, a private CDI observer that is only annotated on its **parameter** (`@Observes`), extensions that are never candidates |
| `helidon-sample` | Maven, `helidon-se` 4.5.4 parent (Java 21) | A Helidon SE 4 application with a `module-info.java` | Plain Java wired by method references, a class loaded by name from `application.yaml`, and the module-descriptor guard that turns `UNUSED_VISIBILITY` off |
| `micronaut-sample` | Gradle, `io.micronaut.application` 5.0.2 and `micronautVersion=5.1.5`, both pinned in `gradle.properties` (Java 25 toolchain) | A Micronaut 5 HTTP application | Compile-time `@Controller`/`@Factory`/`@Singleton` beans, `annotationProcessor`, `runtimeOnly`, and `testImplementation` declarations that are not analyzed, a nested class only a test still reaches |

They exist because a dead-code analyzer cannot be trusted on the strength of unit tests alone. Unit tests prove the renderer escapes a string correctly; only a real project proves the Maven goal is invocable, the Gradle task runs under the configuration cache, and the analyzer does not flag live code. The four framework samples exist for the last point in particular: frameworks own code that no Java identifier ever names, and every one of those symbols has to be kept for a reason the analyzer can state.

**These projects are never built by the reactor.** The four Maven samples are listed by [`samples/pom.xml`](pom.xml), a pure aggregator (`com.example:samples`, `pom` packaging, parent of nothing) so that one `mvn` invocation reaches all of them; the Gradle samples keep their own `settings.gradle` and stay separate builds. The repository's root pom does not list the aggregator, so nothing compiles the samples, and the integration tests invoke prune goals and tasks *directly* rather than through a lifecycle phase, so the samples' own dependencies are never resolved. That keeps the whole suite offline-safe. The declared dependencies are there to be *read* as unused-dependency input, not to be downloaded. Their versions are declared the way a real project declares them — `<properties>` entries in the Maven samples, `gradle.properties` entries interpolated as `"group:artifact:${version}"` in the Gradle samples — so the dependency parsers are exercised on `${...}` versions, not only on literals; the two framework parents stay literal because Maven does not allow an expression in `<parent><version>`.

The aggregator has one consequence for the fixtures themselves. prune-java collects references from the whole enclosing build, and a Maven module's build is the pom that lists it, so analyzing any Maven sample reads all of `samples/`. A sample's dead code therefore has to be dead under that shared scan too: no other sample may import the package of its deliberately unused dependency (which is why `spring-boot-sample` uses Commons Text, not the Commons Lang that `maven-sample` leaves unused), mention the name of a type it marks as guarded or dead, or import a reflective serialization library if the sample has a dead instance field (`maven-sample` imports Gson, so the dead field in `helidon-sample` is static). `SampleProjectFixtureTest` enforces the rule by analyzing every sample once inside `samples/` and once copied out on its own, and requiring identical findings and explanations.

They also never apply prune-java. A sample that configured the plugin would no longer resemble the projects prune-java is meant to analyze, so all harness coupling lives outside: in `prune-maven-plugin/pom.xml` for Maven, and in the functional test that copies and patches a throwaway clone for Gradle. `SampleProjectFixtureTest` fails if a sample mentions `com.patbaumgartner` or commits a `prune-baseline.txt`.

## Ground truth

Each sample carries an `expected-findings.txt` manifest, one finding per line, in the form `<verdict> <type> <symbol>`:

| Verdict | Type column | Meaning |
| --- | --- | --- |
| `unused` | An `IssueType` | The analyzer **must report exactly these** (type and symbol). Asserted as set equality by `SampleProjectFixtureTest`, so a missing or extra finding fails the build. |
| `test-only` | An `IssueType` | Not reported by default, because a test references the symbol. With test references excluded it **must be reported** with exactly this type, and the message must say `is only referenced from tests`. |
| `kept` | `ANY` | The analyzer **must never report** this, under any type, and with explanations on it must **not** appear as kept either: an identifier references it, so it never reached a guard. |
| `kept` | A guard id | The analyzer **must never report** this, and with explanations on it must list it as kept **by exactly this guard**. Every explained symbol must have such a row, so a manifest records every guard decision the analyzer makes in that sample. |

The split matters more than it looks. A missed finding is an inconvenience; a false positive deletes working code. So the `kept` rows are the ones that guard the philosophy, and they stay under test permanently. Naming the guard makes the manifest a precise statement of *why* each framework-owned symbol survives: `GreetingController` because of `annotation`, `StartupListener` because of `literal`, `GreetingCodes` in the Helidon sample because of `module-info`, `LifecycleLogger#onStop` in the Quarkus sample because an annotation on its parameter counts.

Only unambiguously dead symbols are marked `unused`. Public API with no in-project caller is marked `kept`, because a conservative analyzer cannot prove that no external caller exists — that is a judgement call for the user, not a finding. `PriceFormatter#describe` and `StockLevel#label` are public methods deliberately reachable only from tests; `StockAuditor`, `AuditTrail`, `GreetingSamples`, `SampleNames`, and the nested `GreetingFormatter.Samples` are package-private types in the same position, which is what the `test-only` verdict pins. `CurrencyCodes`, `WarehouseCodes`, `SupportCodes`, `StatusCodes`, and `Defaults` are public constant holders without Javadoc that only their own package reads, which is exactly the shape `UNUSED_VISIBILITY` targets; `GreetingCodes` in the Helidon sample has the same shape and is kept, because that build has a `module-info.java`. `PriceFormatter.LegacyRounding` is a private nested class nothing refers to, the nested form of `UNUSED_CLASS`.

One row documents a trade-off rather than a feature: `GreetingConfiguration#LEGACY_ZONE` in the Spring Boot sample is a dead constant inside a `@Configuration` class, and it is kept by `annotated-owner`, because the analyzer does not distinguish `@Configuration` from `@Entity` and keeps every private field of an annotated type. The manifest says so, so the limitation stays visible and deliberate.

## What is checked, and what is not

`SampleProjectFixtureTest` in `prune-core` discovers every `samples/*/expected-findings.txt`, validates the manifests themselves (known verdicts, real `IssueType` constants, real guard ids), requires the analyzer's report for each sample to equal the `unused` rows exactly, requires the explained kept symbols to equal the guard-naming `kept` rows exactly, requires `unused` plus `test-only` with test references excluded, checks that every finding and every explanation points at a line naming its symbol, enforces the kept-symbol invariant, round-trips a baseline written from each report to prove it suppresses every finding, requires each sample to report and explain the same things when copied out of `samples/` and analyzed alone, and pins the aggregator's module list to the Maven samples it discovers. `keptSymbolGuardFailsWhenAReportNamesAKeptSymbol` feeds a synthetic report through the same helper to prove the guard fails when it should.

`SampleProjectFunctionalTest` runs the Gradle plugin against `gradle-sample` (`pruneCheck` fails the build with the manifest's findings, `testReferences = false` adds the `test-only` rows, `ignoreFailures` plus `format = 'json'` yields a report equal to the manifest, and `pruneFix` rewrites a copy), and `maven-invoker-plugin` runs `check`, `fix`, `baseline`, and `check` again against `maven-sample` with `prune.failOnIssues=false`, so the last run shows the two remaining findings suppressed by the baseline the third one wrote. Neither is decorative: the Maven one is what surfaced a `@Parameter` declared as `String` that Maven could not inject from `${project.basedir}`, a bug no unit test had caught.

The framework samples are analyzed by the fixture test and by hand; no framework is downloaded by the test suite and no application is started. What is proven is that the analyzer, reading the sources exactly as a developer committed them, reports the dead code and nothing the framework owns. Running the plugins by hand inside those builds (below) does resolve the framework parents and Gradle plugins, because Maven and Gradle read them before any goal or task runs. Every sample pins the newest release of its framework and libraries, every coordinate resolves from Maven Central or the Gradle Plugin Portal, and every sample's main and test sources compile against the classpath its build resolves — checked by hand whenever the versions move, so a fixture never drifts into code that no longer exists. Micronaut 5 publishes its modules for JVM 25 and Helidon 4 for Java 21; the two samples declare exactly that, as a generated project would.

## Running the tools by hand

The `check` runs below are supposed to end in a failed build. The samples exist to be caught,
and both plugins fail the build on findings by default; the findings themselves are printed right
before the failure. Pass `-Dprune.failOnIssues=false` to the Maven goal to keep the build green.

```bash
./mvnw install                                              # CLI jar, Maven plugin
(cd prune-gradle-plugin && ./gradlew publishToMavenLocal)   # Gradle plugin

cd samples
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check -Dprune.failOnIssues=false
# one reactor run over all four Maven samples: 6, 5, 5, and 4 issue(s); the aggregator itself is
# skipped as pom packaging. Without -Dprune.failOnIssues=false the first module fails the build.

cd maven-sample
mvn com.patbaumgartner:prune-maven-plugin:0.1.0-SNAPSHOT:check
# BUILD FAILURE: prune-java found 6 unused-code issue(s).

cd ../gradle-sample
../../prune-gradle-plugin/gradlew -I ../../prune-gradle-plugin/prune-java.init.gradle pruneCheck
# BUILD FAILED: prune-java found 4 unused-code issue(s).

java -jar ../../prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check
# exit 1: Analyzed 5 Java file(s) under .: 4 issue(s) found (conservative mode).

java -jar ../../prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --no-test-references
# exit 1: 5 issue(s), the fifth being "Class StockAuditor is only referenced from tests"

cd ..
for s in spring-boot-sample quarkus-sample helidon-sample micronaut-sample; do
  java -jar ../prune-cli/target/prune-cli-0.1.0-SNAPSHOT.jar check --explain --root=$s
done
# exit 1 each: 5, 5, 4, and 5 issue(s); the [KEPT] lines name the guard behind every framework-owned symbol
```

The Gradle sample does not apply the plugin, for the reason given above, so
[prune-java.init.gradle](../prune-gradle-plugin/prune-java.init.gradle) applies it from the outside —
the Gradle counterpart of invoking a Maven plugin by its coordinates. The init script resolves the
plugin from the local repository, which is why `publishToMavenLocal` has to run first. The plugin's
wrapper is used only because the sample has none; any Gradle installation works in its place, and
`pruneFix` runs the same way. The Maven framework samples accept the same `mvn ...:check` invocation
as `maven-sample` (5, 5, and 4 findings for Spring Boot, Quarkus, and Helidon), and `micronaut-sample`
the same init-script invocation as `gradle-sample` (5 findings); their framework parents, BOMs, and
Gradle plugins are resolved by the build tool on the way in, which is the one moment these samples
need the network.

The CLI analyzes its working directory unless `--root=<dir>` is given. Run `fix`, `pruneFix`, and
the `baseline` entry points only on a copy: they write into the sample, and the manifests describe
the samples as committed.
