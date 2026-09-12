package com.patbaumgartner.prune.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void checkMojoUsesProjectDirectoryInSummary() throws Exception {
        var mojo = new PruneCheckMojo();
        var log = new CapturingLog();
        mojo.setLog(log);
        setPrivateField(mojo, "projectDir", tempDir.toFile());

        mojo.execute();

        assertTrue(log.messages.stream().anyMatch(message ->
                message.contains("prune-java check: Analyzer scaffold active for " + tempDir)));
    }

    @Test
    void pluginDescriptorDeclaresBothGoalsAndAnInjectableProjectDir() throws Exception {
        String descriptor;
        try (var in = PruneMojoTest.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
            assertNotNull(in, "plugin.xml is generated into target/classes during the build");
            descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(descriptor.contains("<goal>check</goal>"), descriptor);
        assertTrue(descriptor.contains("<goal>fix</goal>"), descriptor);

        var projectDir = descriptor.lines()
                .filter(line -> line.contains("<projectDir"))
                .findFirst()
                .orElseThrow();

        assertTrue(projectDir.contains("default-value=\"${project.basedir}\""), projectDir);
        // ${project.basedir} evaluates to a File; Maven cannot inject it into a String field.
        assertTrue(projectDir.contains("implementation=\"java.io.File\""), projectDir);
    }

    @Test
    void fixMojoLogsScaffoldMessage() throws MojoExecutionException {
        var mojo = new PruneFixMojo();
        var log = new CapturingLog();
        mojo.setLog(log);

        mojo.execute();

        assertTrue(log.messages.stream().anyMatch(message ->
                message.contains("prune-java fix scaffold active")));
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class CapturingLog implements Log {
        private final List<String> messages = new ArrayList<>();

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public void debug(CharSequence content) {
            messages.add(String.valueOf(content));
        }

        @Override
        public void debug(CharSequence content, Throwable error) {
            messages.add(String.valueOf(content));
        }

        @Override
        public void debug(Throwable error) {
            messages.add(String.valueOf(error.getMessage()));
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public void info(CharSequence content) {
            messages.add(String.valueOf(content));
        }

        @Override
        public void info(CharSequence content, Throwable error) {
            messages.add(String.valueOf(content));
        }

        @Override
        public void info(Throwable error) {
            messages.add(String.valueOf(error.getMessage()));
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public void warn(CharSequence content) {
            messages.add(String.valueOf(content));
        }

        @Override
        public void warn(CharSequence content, Throwable error) {
            messages.add(String.valueOf(content));
        }

        @Override
        public void warn(Throwable error) {
            messages.add(String.valueOf(error.getMessage()));
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public void error(CharSequence content) {
            messages.add(String.valueOf(content));
        }

        @Override
        public void error(CharSequence content, Throwable error) {
            messages.add(String.valueOf(content));
        }

        @Override
        public void error(Throwable error) {
            messages.add(String.valueOf(error.getMessage()));
        }
    }
}
