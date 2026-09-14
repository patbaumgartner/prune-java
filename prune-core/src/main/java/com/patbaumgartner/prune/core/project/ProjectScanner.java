package com.patbaumgartner.prune.core.project;

import com.patbaumgartner.prune.core.config.AnalysisConfig;
import com.patbaumgartner.prune.core.config.BuildRoots;
import com.patbaumgartner.prune.core.source.JavaSourceFile;
import com.patbaumgartner.prune.core.source.JavaSourceParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.StreamSupport;

public final class ProjectScanner {

	private static final long MAX_RESOURCE_BYTES = 8L * 1024 * 1024;

	private static final Set<String> OUTPUT_DIRECTORIES = Set.of("target", "build", "out", "bin", "node_modules");

	// Machine-read configuration that names classes from outside any source tree: an
	// Eclipse plugin.xml or a META-INF/MANIFEST.MF at the project root, a
	// config/application.yml. Prose formats are deliberately absent, or a README naming a
	// class would hide its finding.
	private static final Set<String> CONFIGURATION_EXTENSIONS = Set.of("xml", "properties", "yml", "yaml", "mf");

	private static final Set<String> BINARY_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "ico", "webp",
			"pdf", "zip", "jar", "war", "ear", "class", "so", "dll", "dylib", "exe", "bin", "dat", "woff", "woff2",
			"ttf", "otf", "eot", "mp3", "mp4", "ogg", "wav", "gz", "tar", "tgz", "7z", "rar", "keystore", "jks", "p12",
			"pfx", "der", "ser");

	private ProjectScanner() {
	}

	// Candidates come from the configured root; references are read from the whole
	// enclosing build so a module inside a reactor still sees the sibling modules that
	// use its classes. Test sources are always read: the reference index decides whether
	// they count.
	public static ScannedProject scan(AnalysisConfig config) throws IOException {
		Path root = config.projectRoot().toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			throw new IOException("Project root is not a directory: " + config.projectRoot());
		}
		Path scanRoot = BuildRoots.enclosingRoot(root);
		// The baseline lists findings; reading it as a resource would hide every finding
		// it names.
		Path baseline = config.baseline().map(file -> file.toAbsolutePath().normalize()).orElse(null);
		List<GlobMatcher> includes = config.includePatterns().stream().map(GlobMatcher::compile).toList();
		List<GlobMatcher> excludes = config.excludePatterns().stream().map(GlobMatcher::compile).toList();

		List<Path> files = new ArrayList<>();
		Files.walkFileTree(scanRoot, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (dir.equals(scanRoot)) {
					return FileVisitResult.CONTINUE;
				}
				String name = fileName(dir);
				if (name.startsWith(".")) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				if (OUTPUT_DIRECTORIES.contains(name) && !underSourceTree(scanRoot.relativize(dir.getParent()))) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (Files.isRegularFile(file) && !file.equals(baseline)) {
					files.add(file);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				return FileVisitResult.CONTINUE;
			}
		});
		files.sort(Comparator.comparing(path -> relativePath(scanRoot, path)));

		List<ScannedProject.JavaFile> javaFiles = new ArrayList<>();
		List<ScannedProject.ResourceFile> resources = new ArrayList<>();
		List<ScannedProject.ResourceFile> configuration = new ArrayList<>();
		List<ScannedProject.BuildFile> buildFiles = new ArrayList<>();
		for (Path file : files) {
			boolean underRoot = file.startsWith(root);
			Path relative = scanRoot.relativize(file);
			String relativePath = relativePath(root, file);
			boolean sourceTree = underSourceTree(relative);
			String name = fileName(file);
			if (!sourceTree) {
				ScannedProject.BuildTool tool = buildTool(name);
				if (tool != null) {
					String content = read(file);
					if (underRoot) {
						buildFiles.add(new ScannedProject.BuildFile(relativePath, content, tool));
					}
					configuration.add(new ScannedProject.ResourceFile(relativePath, content, false));
				}
				else if (isConfiguration(name) && isTextResource(file, name)) {
					configuration.add(new ScannedProject.ResourceFile(relativePath, read(file), false));
				}
				continue;
			}
			boolean testSource = isTestSource(relative);
			if (relativePath.endsWith(".java")) {
				JavaSourceFile source = JavaSourceParser.parse(relativePath, read(file));
				boolean candidate = underRoot && !testSource && isDeclarationUnit(name)
						&& includes.stream().anyMatch(include -> include.matches(relativePath))
						&& excludes.stream().noneMatch(exclude -> exclude.matches(relativePath));
				javaFiles.add(new ScannedProject.JavaFile(source, candidate, testSource));
			}
			else if (isTextResource(file, name)) {
				resources.add(new ScannedProject.ResourceFile(relativePath, read(file), testSource));
			}
		}
		return new ScannedProject(root, javaFiles, resources, configuration, buildFiles);
	}

	// Only a filesystem root has no name; it is never a project file, so the empty name
	// matches nothing below.
	private static String fileName(Path file) {
		Path name = file.getFileName();
		return name == null ? "" : name.toString();
	}

	private static boolean isConfiguration(String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 && CONFIGURATION_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private static boolean isDeclarationUnit(String name) {
		return !"package-info.java".equals(name) && !"module-info.java".equals(name);
	}

	private static ScannedProject.@Nullable BuildTool buildTool(String name) {
		return switch (name) {
			case "pom.xml" -> ScannedProject.BuildTool.MAVEN;
			case "build.gradle", "build.gradle.kts" -> ScannedProject.BuildTool.GRADLE;
			default -> null;
		};
	}

	private static boolean underSourceTree(Path relative) {
		for (Path element : relative) {
			if ("src".equals(element.toString())) {
				return true;
			}
		}
		return false;
	}

	// src/test, and any other source set whose camel-case name starts or ends with the
	// word test: integrationTest, testFixtures, functionalTest. A jmh, generated, or
	// latest source set stays a main caller.
	private static boolean isTestSource(Path relative) {
		for (int i = 0; i + 1 < relative.getNameCount(); i++) {
			if ("src".equals(relative.getName(i).toString()) && isTestSourceSet(relative.getName(i + 1).toString())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isTestSourceSet(String name) {
		if ("test".equals(name) || name.endsWith("Test")) {
			return true;
		}
		return name.startsWith("test") && Character.isUpperCase(name.charAt(4));
	}

	private static boolean isTextResource(Path file, String name) throws IOException {
		int dot = name.lastIndexOf('.');
		if (dot >= 0 && BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
			return false;
		}
		if (Files.size(file) > MAX_RESOURCE_BYTES) {
			return false;
		}
		byte[] head = new byte[Math.toIntExact(Math.min(8192, Files.size(file)))];
		try (var in = Files.newInputStream(file)) {
			int read = in.readNBytes(head, 0, head.length);
			for (int i = 0; i < read; i++) {
				if (head[i] == 0) {
					return false;
				}
			}
		}
		return true;
	}

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	static String relativePath(Path root, Path file) {
		return StreamSupport.stream(root.relativize(file).spliterator(), false)
			.map(Path::toString)
			.reduce((a, b) -> a + "/" + b)
			.orElse("");
	}

}
