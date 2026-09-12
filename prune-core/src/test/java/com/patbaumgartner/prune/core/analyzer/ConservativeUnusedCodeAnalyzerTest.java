package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeUnusedCodeAnalyzerTest {

    private static final String MAIN = "src/main/java/com/example/";
    private static final String TEST = "src/test/java/com/example/";

    @TempDir
    Path root;

    @Test
    void reportsPackagePrivateClassWithoutReferences() throws IOException {
        write(MAIN + "Dead.java", "package com.example;\n\nfinal class Dead {\n    String build() { return \"x\"; }\n}\n");
        write(MAIN + "App.java", "package com.example;\n\npublic class App {\n    public static void main(String[] a) { }\n}\n");

        var report = analyze();

        assertEquals(List.of("com.example.Dead"), symbols(report, IssueType.UNUSED_CLASS));
        var issue = report.issues().get(0);
        assertEquals("src/main/java/com/example/Dead.java:3:13", issue.location());
        assertEquals("Class Dead is never referenced", issue.message());
        assertEquals(Severity.WARNING, issue.severity());
        assertFalse(issue.autoFixable());
    }

    @Test
    void keepsPackagePrivateClassReferencedFromAnotherFile() throws IOException {
        write(MAIN + "Helper.java", "package com.example;\n\nfinal class Helper {\n}\n");
        write(MAIN + "App.java", "package com.example;\n\npublic class App {\n    Object h = new Helper();\n}\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void keepsPackagePrivateClassReferencedOnlyFromTestsByDefault() throws IOException {
        write(MAIN + "Helper.java", "package com.example;\n\nfinal class Helper {\n}\n");
        write(TEST + "HelperTest.java", "package com.example;\n\nclass HelperTest {\n    Helper subject = new Helper();\n}\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void reportsPackagePrivateClassReferencedOnlyFromTestsWhenTestReferencesAreExcluded() throws IOException {
        write(MAIN + "Helper.java", "package com.example;\n\nfinal class Helper {\n}\n");
        write(TEST + "HelperTest.java", "package com.example;\n\nclass HelperTest {\n    Helper subject = new Helper();\n}\n");
        var defaults = AnalysisConfig.defaultFor(root);
        var config = new AnalysisConfig(root, defaults.includePatterns(), defaults.excludePatterns(), false, false, true);

        var report = new ConservativeUnusedCodeAnalyzer().analyze(config);

        assertEquals(List.of("com.example.Helper"), symbols(report, IssueType.UNUSED_CLASS));
    }

    @Test
    void keepsPackagePrivateClassNamedInAResourceFile() throws IOException {
        write(MAIN + "Provider.java", "package com.example;\n\nfinal class Provider implements Runnable {\n    public void run() { }\n}\n");
        write("src/main/resources/META-INF/services/java.lang.Runnable", "com.example.Provider\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void keepsPackagePrivateClassNamedInAStringLiteral() throws IOException {
        write(MAIN + "Plugin.java", "package com.example;\n\nfinal class Plugin {\n}\n");
        write(MAIN + "Loader.java", "package com.example;\n\npublic class Loader {\n"
                + "    Object load() throws Exception { return Class.forName(\"com.example.Plugin\").getConstructor().newInstance(); }\n}\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void keepsAnnotatedClassesAndEntryPoints() throws IOException {
        write(MAIN + "Bean.java", "package com.example;\n\n@Deprecated\nfinal class Bean {\n}\n");
        write(MAIN + "Main.java", "package com.example;\n\nfinal class Main {\n    public static void main(String... args) { }\n}\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void neverReportsAPublicClassAsUnused() throws IOException {
        write(MAIN + "Api.java", "package com.example;\n\npublic final class Api {\n    public void call() { }\n}\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void reportsPrivateMethodsAndFieldsWithoutReferences() throws IOException {
        write(MAIN + "Service.java", """
                package com.example;

                public class Service {

                    private static final String UNUSED_CONSTANT = "x";
                    private final int used = 1;

                    public int used() {
                        return used + helper();
                    }

                    private int helper() {
                        return 1;
                    }

                    private int orphan(int a, int b) {
                        return a + b;
                    }
                }
                """);

        var report = analyze();

        assertEquals(List.of("com.example.Service#UNUSED_CONSTANT"), symbols(report, IssueType.UNUSED_FIELD));
        assertEquals(List.of("com.example.Service#orphan"), symbols(report, IssueType.UNUSED_METHOD));
        assertEquals("src/main/java/com/example/Service.java:5:33", report.issues().get(0).location());
        assertEquals("Private field UNUSED_CONSTANT is never used", report.issues().get(0).message());
        assertEquals("src/main/java/com/example/Service.java:16:17", report.issues().get(1).location());
        assertEquals("Private method orphan is never used", report.issues().get(1).message());
        assertTrue(report.issues().stream().allMatch(AnalysisIssue::autoFixable));
    }

    @Test
    void keepsPrivateMembersReferencedFromNestedClassesLambdasAndMethodReferences() throws IOException {
        write(MAIN + "Outer.java", """
                package com.example;

                import java.util.function.Supplier;

                public class Outer {
                    private int viaNested;
                    private static int viaReference() { return 1; }
                    private static int viaLambda() { return 2; }

                    static final class Inner {
                        int read(Outer o) { return o.viaNested; }
                    }

                    Inner inner = new Inner();
                    Supplier<Integer> a = Outer::viaReference;
                    Supplier<Integer> b = () -> viaLambda();
                }
                """);

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void keepsAnnotatedMembersSerializationHooksNativeMethodsAndConstructors() throws IOException {
        write(MAIN + "Special.java", """
                package com.example;

                public class Special implements java.io.Serializable {
                    private static final long serialVersionUID = 1L;
                    @SuppressWarnings("unused")
                    private static int annotated;
                    private static native void nativeHook();
                    private void readObject(java.io.ObjectInputStream in) { }
                    private Object readResolve() { return this; }
                    private Special() { }
                }
                """);

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void keepsPrivateInstanceFieldsWhenAReflectiveSerializationLibraryIsImported() throws IOException {
        write(MAIN + "Dto.java", "package com.example;\n\npublic class Dto {\n    private String name;\n    private static int COUNTER;\n}\n");
        write(MAIN + "Json.java", "package com.example;\n\nimport com.google.gson.Gson;\n\npublic class Json {\n    Object g = new Gson();\n}\n");

        var report = analyze();

        assertEquals(List.of("com.example.Dto#COUNTER"), symbols(report, IssueType.UNUSED_FIELD));
    }

    @Test
    void keepsPrivateFieldsOfSerializableAndAnnotatedTypesButStillReportsTheirPrivateMethods() throws IOException {
        write(MAIN + "Entity.java", "package com.example;\n\n@Deprecated\npublic class Entity {\n    private String column;\n    private void orphan() { }\n}\n");
        write(MAIN + "Payload.java", "package com.example;\n\npublic class Payload implements java.io.Serializable {\n    private String field;\n}\n");

        var report = analyze();

        assertEquals(List.of(), symbols(report, IssueType.UNUSED_FIELD));
        assertEquals(List.of("com.example.Entity#orphan"), symbols(report, IssueType.UNUSED_METHOD));
    }

    @Test
    void reportsOnlyTheClassWhenAnUnusedClassAlsoHasUnusedPrivateMembers() throws IOException {
        write(MAIN + "Dead.java", "package com.example;\n\nfinal class Dead {\n    private int x;\n    private void y() { }\n}\n");

        var report = analyze();

        assertEquals(1, report.issueCount());
        assertEquals(IssueType.UNUSED_CLASS, report.issues().get(0).type());
    }

    @Test
    void reportsUnusedPrivateMembersOfNestedTypesWithDottedSymbols() throws IOException {
        write(MAIN + "Outer.java", "package com.example;\n\npublic class Outer {\n    static class Inner {\n        private int orphan;\n    }\n    Inner inner = new Inner();\n}\n");

        var report = analyze();

        assertEquals(List.of("com.example.Outer.Inner#orphan"), symbols(report, IssueType.UNUSED_FIELD));
    }

    @Test
    void reportsNonPublicNestedTypesThatNothingReferencesAndSuppressesTheirMembers() throws IOException {
        write(MAIN + "Outer.java", """
                package com.example;

                public class Outer {

                    private static final class Dead {
                        private int alsoDead;
                    }

                    static class Used {
                        private int orphan;
                    }

                    enum Kind { A, B }

                    private record Pair(int a, int b) { }

                    Used used = new Used();
                }
                """);

        var report = analyze();

        var classes = report.issues().stream().filter(issue -> issue.type() == IssueType.UNUSED_CLASS).toList();
        assertEquals(List.of("com.example.Outer.Dead", "com.example.Outer.Kind", "com.example.Outer.Pair"),
                classes.stream().map(AnalysisIssue::symbol).toList());
        assertEquals(List.of("com.example.Outer.Used#orphan"), symbols(report, IssueType.UNUSED_FIELD));
        assertEquals("src/main/java/com/example/Outer.java:5:32", classes.get(0).location());
        assertEquals("Nested class Dead is never referenced", classes.get(0).message());
        assertFalse(classes.get(0).autoFixable());
        assertEquals("Nested enum Kind is never referenced", classes.get(1).message());
        assertEquals("Nested record Pair is never referenced", classes.get(2).message());
    }

    @Test
    void keepsNestedTypesReferencedFromOtherFilesStringsOrResources() throws IOException {
        write(MAIN + "Outer.java", "package com.example;\n\npublic class Outer {\n    static class Imported { }\n    static class Qualified { }\n    static class Reflected { }\n    static class Declared { }\n}\n");
        write(MAIN + "User.java", "package com.example;\n\nimport com.example.Outer.Imported;\n\npublic class User {\n    Imported a;\n    Outer.Qualified b;\n}\n");
        write(MAIN + "Loader.java", "package com.example;\n\npublic class Loader {\n    String name = \"com.example.Outer$Reflected\";\n}\n");
        write("src/main/resources/META-INF/services/java.lang.Runnable", "com.example.Outer$Declared\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void keepsNestedTypesWhoseVisibilityCannotBeJudgedLocally() throws IOException {
        write(MAIN + "Api.java", "package com.example;\n\npublic interface Api {\n    class Impl implements Api { }\n    @interface Marker { }\n}\n");
        write(MAIN + "Base.java", "package com.example;\n\npublic class Base {\n    public static class Public { }\n    protected static class Protected { }\n    @Deprecated\n    static class Annotated { }\n    static class Launcher {\n        public static void main(String[] args) { }\n    }\n}\n");
        write(MAIN + "Entity.java", "package com.example;\n\n@Deprecated\npublic class Entity {\n    static class Builder { }\n}\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void treatsAnyNonPrivateMainMethodAsAnEntryPoint() throws IOException {
        write(MAIN + "Modern.java", "package com.example;\n\nfinal class Modern {\n    void main() { }\n}\n");
        write(MAIN + "Hidden.java", "package com.example;\n\nfinal class Hidden {\n    private static void main(String[] args) { }\n}\n");

        var report = analyze();

        assertEquals(List.of("com.example.Hidden"), symbols(report, IssueType.UNUSED_CLASS));
    }

    @Test
    void reportsVisibilityReductionForAConstantHolderUsedOnlyInsideItsPackage() throws IOException {
        write(MAIN + "Codes.java", "package com.example;\n\npublic final class Codes {\n\n    public static final String DEFAULT = \"CHF\";\n\n    private Codes() {\n    }\n}\n");
        write(MAIN + "User.java", "package com.example;\n\npublic class User {\n    String code() { return Codes.DEFAULT; }\n}\n");

        var report = analyze();

        assertEquals(List.of("com.example.Codes"), symbols(report, IssueType.UNUSED_VISIBILITY));
        var issue = report.issues().get(0);
        assertEquals(Severity.INFO, issue.severity());
        assertEquals("src/main/java/com/example/Codes.java:3:20", issue.location());
        assertEquals("Public class Codes is only used from package com.example and can be package-private", issue.message());
        assertTrue(issue.autoFixable());
    }

    @Test
    void keepsVisibilityWhenReferencedFromAnotherPackageEvenIfOnlyFromTests() throws IOException {
        write(MAIN + "Codes.java", "package com.example;\n\npublic final class Codes {\n    public static final String DEFAULT = \"CHF\";\n    private Codes() { }\n}\n");
        write(MAIN + "User.java", "package com.example;\n\npublic class User {\n    String code() { return Codes.DEFAULT; }\n}\n");
        write("src/test/java/com/other/CodesTest.java", "package com.other;\n\nimport com.example.Codes;\n\nclass CodesTest {\n    String s = Codes.DEFAULT;\n}\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void keepsVisibilityForClassesWithAPublicConstructorInstanceApiOrNoReferences() throws IOException {
        write(MAIN + "Model.java", "package com.example;\n\npublic final class Model {\n    public Model() { }\n}\n");
        write(MAIN + "Registry.java", "package com.example;\n\npublic final class Registry {\n    private Registry() { }\n    public static Registry get() { return new Registry(); }\n    public void register() { }\n}\n");
        write(MAIN + "Orphan.java", "package com.example;\n\npublic final class Orphan {\n    public static final int X = 1;\n    private Orphan() { }\n}\n");
        write(MAIN + "User.java", "package com.example;\n\npublic class User {\n    Object m = new Model();\n    Object r = Registry.get();\n}\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void keepsVisibilityOfDocumentedPublicClassesBecauseJavadocMarksIntendedApi() throws IOException {
        write(MAIN + "Codes.java", "package com.example;\n\n/**\n * Currency codes.\n */\npublic final class Codes {\n    public static final String DEFAULT = \"CHF\";\n    private Codes() { }\n}\n");
        write(MAIN + "Other.java", "package com.example;\n\n// not javadoc\npublic final class Other {\n    public static final String X = \"x\";\n    private Other() { }\n}\n");
        write(MAIN + "User.java", "package com.example;\n\npublic class User {\n    String code() { return Codes.DEFAULT + Other.X; }\n}\n");

        var report = analyze();

        assertEquals(List.of("com.example.Other"), symbols(report, IssueType.UNUSED_VISIBILITY));
    }

    @Test
    void testTreesAreNeverCandidatesEvenWhenNestedUnderAMainSourceFolderName() throws IOException {
        write("src/test/resources/fixture/src/main/java/Dead.java", "final class Dead {\n}\n");
        write(TEST + "Helper.java", "package com.example;\n\nfinal class Helper {\n    private int orphan;\n}\n");

        var report = analyze();

        assertEquals(List.of(), report.issues());
        assertTrue(report.summary().startsWith("Analyzed 0 Java file(s)"), report.summary());
    }

    @Test
    void keepsVisibilityWhenTheProjectDeclaresAModuleDescriptor() throws IOException {
        write(MAIN + "Codes.java", "package com.example;\n\npublic final class Codes {\n    public static final String DEFAULT = \"CHF\";\n    private Codes() { }\n}\n");
        write(MAIN + "User.java", "package com.example;\n\npublic class User {\n    String code() { return Codes.DEFAULT; }\n}\n");
        write("src/main/java/module-info.java", "module com.example {\n    exports com.example;\n}\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void reportsUnusedMavenDependenciesAndKeepsImportedOnes() throws IOException {
        write("pom.xml", """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.code.gson</groupId>
                            <artifactId>gson</artifactId>
                            <version>2.11.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.17.0</version>
                        </dependency>
                        <dependency>
                            <groupId>ch.qos.logback</groupId>
                            <artifactId>logback-classic</artifactId>
                            <version>1.5.6</version>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.0</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        write(MAIN + "App.java", "package com.example;\n\nimport com.google.gson.Gson;\n\npublic class App {\n    Object g = new Gson();\n}\n");

        var report = analyze();

        assertEquals(List.of("org.apache.commons:commons-lang3"), symbols(report, IssueType.UNUSED_DEPENDENCY));
        var issue = report.issues().get(0);
        assertEquals("pom.xml:14", issue.location());
        assertEquals("Dependency org.apache.commons:commons-lang3 is declared but never imported", issue.message());
        assertTrue(issue.autoFixable());
    }

    @Test
    void reportsUnusedGradleDependenciesDeclaredWithStringNotation() throws IOException {
        write("build.gradle", "plugins { id 'java' }\n\ndependencies {\n    implementation 'org.slf4j:slf4j-api:2.0.16'\n"
                + "    implementation 'commons-io:commons-io:2.17.0'\n    runtimeOnly 'com.h2database:h2:2.3.232'\n"
                + "    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'\n}\n");
        write(MAIN + "App.java", "package com.example;\n\nimport org.slf4j.Logger;\n\npublic class App {\n    Logger log;\n}\n");

        var report = analyze();

        assertEquals(List.of("commons-io:commons-io"), symbols(report, IssueType.UNUSED_DEPENDENCY));
        assertEquals("build.gradle:5", report.issues().get(0).location());
    }

    @Test
    void countsDependencyUsageFromQualifiedNamesAndResources() throws IOException {
        write("pom.xml", pomWith("<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId></dependency>"
                + "<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>"
                + "<dependency><groupId>org.apache.commons</groupId><artifactId>commons-dbcp2</artifactId></dependency>"));
        write(MAIN + "App.java", "package com.example;\n\npublic class App {\n    boolean b = org.apache.commons.lang3.StringUtils.isBlank(\"\");\n}\n");
        write("src/main/resources/beans.xml", "<bean class=\"org.apache.commons.dbcp2.BasicDataSource\"/>\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void excludedFilesAreNotCandidatesButStillCountAsReferences() throws IOException {
        write(MAIN + "Generated.java", "package com.example;\n\nfinal class Generated {\n    private int orphan;\n    Object h = new Helper();\n}\n");
        write(MAIN + "Helper.java", "package com.example;\n\nfinal class Helper {\n}\n");
        var defaults = AnalysisConfig.defaultFor(root);
        var config = new AnalysisConfig(root, defaults.includePatterns(), List.of("**/Generated.java"), false, true, true);

        var report = new ConservativeUnusedCodeAnalyzer().analyze(config);

        assertEquals(List.of(), report.issues());
    }

    @Test
    void treatsAStructurallyUnparseableFileAsOpaqueButStillReadsItsReferences() throws IOException {
        write(MAIN + "Broken.java", "package com.example;\n\nclass Broken {\n    private int orphan;\n    Helper h = ;;; {{{ \n");
        write(MAIN + "Helper.java", "package com.example;\n\nfinal class Helper {\n}\n");

        var report = analyze();

        assertEquals(List.of(), report.issues());
        assertEquals("Analyzed 2 Java file(s) under " + root + ": 0 issue(s) found (conservative mode)."
                + " Skipped 1 file(s) the parser could not follow: src/main/java/com/example/Broken.java", report.summary());
    }

    @Test
    void doesNotReportAMemberWhoseOnlyCallIsSpelledWithAUnicodeEscape() throws IOException {
        write(MAIN + "Escaped.java", "package com.example;\n\npublic class Escaped {\n    private void helper() {}\n"
                + "    public void run() { \\u0068elper(); }\n}\n");

        var report = analyze();

        assertEquals(List.of(), report.issues());
        assertEquals("Analyzed 1 Java file(s) under " + root + ": 0 issue(s) found (conservative mode)."
                + " Skipped 1 file(s) the parser could not follow: src/main/java/com/example/Escaped.java", report.summary());
    }

    @Test
    void countsReferencesFromNonJavaSourcesUnderTheSourceTree() throws IOException {
        write(MAIN + "Helper.java", "package com.example;\n\nfinal class Helper {\n}\n");
        write("src/main/kotlin/com/example/App.kt", "package com.example\n\nval helper = Helper()\n");

        assertEquals(List.of(), analyze().issues());
    }

    @Test
    void ignoresBuildOutputDirectoriesAndFilesOutsideTheSourceTree() throws IOException {
        write(MAIN + "Dead.java", "package com.example;\n\nfinal class Dead {\n}\n");
        write("target/classes/notes.txt", "Dead\n");
        write("notes/expected.txt", "com.example.Dead\n");

        assertEquals(List.of("com.example.Dead"), symbols(analyze(), IssueType.UNUSED_CLASS));
    }

    @Test
    void countsReferencesFromBuildFilesAndConfigurationOutsideTheSourceTreeButNotFromDocumentation() throws IOException {
        for (var name : List.of("Activator", "Handler", "Listener", "Tool", "Dead")) {
            write(MAIN + name + ".java", "package com.example;\n\nfinal class " + name + " {\n}\n");
        }
        write("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBundle-Activator: com.example.Activator\n");
        write("plugin.xml", "<plugin>\n  <extension point=\"org.eclipse.ui.startup\">\n    <startup class=\"com.example.Handler\"/>\n  </extension>\n</plugin>\n");
        write("config/application.yml", "listeners:\n  - com.example.Listener\n");
        write("pom.xml", "<project>\n  <modelVersion>4.0.0</modelVersion>\n  <groupId>com.example</groupId>\n"
                + "  <artifactId>app</artifactId>\n  <version>1</version>\n"
                + "  <properties><tool.class>com.example.Tool</tool.class></properties>\n</project>\n");
        write("README.md", "`Dead` is documented here, which is not a reference.\n");

        assertEquals(List.of("com.example.Dead"), symbols(analyze(), IssueType.UNUSED_CLASS));
    }

    @Test
    void reportsAPrivateMethodWhoseOnlyReferenceIsItsOwnRecursion() throws IOException {
        write(MAIN + "Maths.java", """
                package com.example;

                public class Maths {

                    public int depthOf(int n) {
                        return depth(n);
                    }

                    private int depth(int n) {
                        return n == 0 ? 0 : 1 + depth(n - 1);
                    }

                    private long factorial(int n) {
                        return n <= 1 ? 1 : n * factorial(n - 1);
                    }
                }
                """);

        var report = analyze();

        assertEquals(List.of("com.example.Maths#factorial"), symbols(report, IssueType.UNUSED_METHOD));
        assertEquals("src/main/java/com/example/Maths.java:13:18", report.issues().get(0).location());
        assertTrue(report.issues().get(0).autoFixable());
    }

    @Test
    void ordersIssuesByFileLineAndColumnAndReportsCrlfPositionsCorrectly() throws IOException {
        write(MAIN + "B.java", "package com.example;\r\n\r\npublic class B {\r\n    private static int FIRST; private int second;\r\n}\r\n");
        write(MAIN + "A.java", "package com.example;\n\nfinal class A {\n}\n");

        var report = analyze();

        assertEquals(List.of("com.example.A", "com.example.B#FIRST", "com.example.B#second"),
                report.issues().stream().map(AnalysisIssue::symbol).toList());
        assertEquals("src/main/java/com/example/B.java:4:24", report.issues().get(1).location());
        assertEquals("src/main/java/com/example/B.java:4:43", report.issues().get(2).location());
    }

    @Test
    void analyzesEveryModuleOfAMultiModuleBuildFromItsRoot() throws IOException {
        write("pom.xml", pomWith(""));
        write("core/pom.xml", pomWith("<dependency><groupId>commons-io</groupId><artifactId>commons-io</artifactId></dependency>"));
        write("core/src/main/java/com/example/core/Dead.java", "package com.example.core;\n\nfinal class Dead {\n}\n");
        write("app/src/main/java/com/example/app/App.java", "package com.example.app;\n\npublic class App {\n    private int orphan;\n}\n");

        var report = analyze();

        assertEquals(List.of("com.example.app.App#orphan", "commons-io:commons-io", "com.example.core.Dead"),
                report.issues().stream().map(AnalysisIssue::symbol).toList());
        assertEquals("core/pom.xml:1", report.issues().get(1).location());
        assertEquals("core/src/main/java/com/example/core/Dead.java:3:13", report.issues().get(2).location());
        assertTrue(report.summary().startsWith("Analyzed 2 Java file(s)"), report.summary());
    }

    @Test
    void aModuleInsideAReactorSeesReferencesFromItsSiblingModules() throws IOException {
        write("pom.xml", pomWith(""));
        write("core/pom.xml", pomWith("<dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId></dependency>"));
        write("core/src/main/java/com/example/core/Codes.java",
                "package com.example.core;\n\npublic final class Codes {\n    public static final String DEFAULT = \"x\";\n    private Codes() { }\n}\n");
        write("core/src/main/java/com/example/core/User.java", "package com.example.core;\n\npublic class User {\n    String s = Codes.DEFAULT;\n}\n");
        write("app/src/main/java/com/example/app/App.java",
                "package com.example.app;\n\nimport com.example.core.Codes;\nimport com.google.gson.Gson;\n\npublic class App {\n    String s = Codes.DEFAULT;\n}\n");

        var report = new ConservativeUnusedCodeAnalyzer().analyze(AnalysisConfig.defaultFor(root.resolve("core")));

        assertEquals(List.of(), report.issues());
        assertTrue(report.summary().startsWith("Analyzed 2 Java file(s) under " + root.resolve("core")), report.summary());
    }

    @Test
    void aModuleWithoutABuildFileAboveItIsAnalyzedOnItsOwn() throws IOException {
        write("projects/lib/pom.xml", pomWith(""));
        write("projects/lib/src/main/java/com/example/Codes.java",
                "package com.example;\n\npublic final class Codes {\n    public static final String DEFAULT = \"x\";\n    private Codes() { }\n}\n");
        write("projects/lib/src/main/java/com/example/User.java", "package com.example;\n\npublic class User {\n    String s = Codes.DEFAULT;\n}\n");
        write("projects/other/src/main/java/com/other/App.java", "package com.other;\n\nimport com.example.Codes;\n\npublic class App {\n    String s = Codes.DEFAULT;\n}\n");

        var report = new ConservativeUnusedCodeAnalyzer().analyze(AnalysisConfig.defaultFor(root.resolve("projects/lib")));

        assertEquals(List.of("com.example.Codes"), symbols(report, IssueType.UNUSED_VISIBILITY));
    }

    @Test
    void ignoresBuildFilesInsideSourceTreesAndReportsEachDeclarationSeparately() throws IOException {
        write("pom.xml", pomWith("<dependency><groupId>commons-io</groupId><artifactId>commons-io</artifactId></dependency>"));
        write("lib/pom.xml", pomWith("<dependency><groupId>commons-io</groupId><artifactId>commons-io</artifactId></dependency>"));
        write("src/test/resources/fixture/pom.xml", pomWith("<dependency><groupId>org.fixture</groupId><artifactId>fixture</artifactId></dependency>"));
        write(MAIN + "App.java", "package com.example;\n\npublic class App {\n}\n");

        var report = analyze();

        assertEquals(List.of("lib/pom.xml:1", "pom.xml:1"), report.issues().stream().map(AnalysisIssue::location).toList());
    }

    @Test
    void summaryNamesTheRootTheFileCountAndTheIssueCount() throws IOException {
        write(MAIN + "Dead.java", "package com.example;\n\nfinal class Dead {\n}\n");
        write(MAIN + "Live.java", "package com.example;\n\npublic class Live {\n}\n");

        var report = analyze();

        assertTrue(report.conservativeMode());
        assertEquals("Analyzed 2 Java file(s) under " + root + ": 1 issue(s) found (conservative mode).", report.summary());
    }

    @Test
    void emptyProjectProducesAnEmptyReport() {
        var report = analyze();

        assertEquals(0, report.issueCount());
        assertEquals("Analyzed 0 Java file(s) under " + root + ": 0 issue(s) found (conservative mode).", report.summary());
    }

    @Test
    void failsWithAnUncheckedIOExceptionWhenTheRootIsNotADirectory() {
        var config = AnalysisConfig.defaultFor(root.resolve("missing"));

        assertThrows(UncheckedIOException.class, () -> new ConservativeUnusedCodeAnalyzer().analyze(config));
    }

    private AnalysisReport analyze() {
        return new ConservativeUnusedCodeAnalyzer().analyze(AnalysisConfig.defaultFor(root));
    }

    private void write(String relativePath, String content) throws IOException {
        var file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String pomWith(String dependencies) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId><version>1</version>"
                + "<dependencies>" + dependencies + "</dependencies></project>";
    }

    private static List<String> symbols(AnalysisReport report, IssueType type) {
        return report.issues().stream().filter(issue -> issue.type() == type).map(AnalysisIssue::symbol).toList();
    }
}
