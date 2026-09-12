package com.patbaumgartner.prune.core.dependency;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MavenPomParser {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

	private MavenPomParser() {
	}

	// A pom that cannot be parsed yields no dependencies rather than a guess.
	public static List<DeclaredDependency> parse(String buildFile, String pom) {
		Handler handler = new Handler(buildFile);
		return read(pom, handler) ? handler.resolved() : List.of();
	}

	// The reactor shape of a pom: its own coordinates, the parent it inherits from, and
	// every
	// <module> it aggregates, including those declared inside profiles. Unparseable poms
	// aggregate nothing.
	public static Structure structure(String pom) {
		Handler handler = new Handler("");
		return read(pom, handler) ? handler.structure() : Structure.NONE;
	}

	private static boolean read(String pom, Handler handler) {
		try {
			SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setNamespaceAware(false);
			factory.setValidating(false);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			SAXParser parser = factory.newSAXParser();
			parser.parse(new InputSource(new StringReader(pom.startsWith("\uFEFF") ? pom.substring(1) : pom)), handler);
			return true;
		}
		catch (ParserConfigurationException | SAXException | IOException malformed) {
			return false;
		}
	}

	public record Structure(String groupId, String artifactId, String parentGroupId, String parentArtifactId,
			List<String> modules) {

		static final Structure NONE = new Structure("", "", "", "", List.of());

		public Structure {
			modules = List.copyOf(modules);
		}

		public boolean isParentOf(Structure child) {
			return !groupId.isEmpty() && !artifactId.isEmpty() && groupId.equals(child.parentGroupId)
					&& artifactId.equals(child.parentArtifactId);
		}
	}

	private static final class Handler extends DefaultHandler {

		private final String buildFile;

		private final Deque<String> path = new ArrayDeque<>();

		private final Map<String, String> properties = new HashMap<>();

		private final List<Pending> pending = new ArrayList<>();

		private final List<String> modules = new ArrayList<>();

		private final StringBuilder text = new StringBuilder();

		private Locator locator;

		private Pending current;

		Handler(String buildFile) {
			this.buildFile = buildFile;
		}

		@Override
		public void setDocumentLocator(Locator locator) {
			this.locator = locator;
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) {
			path.addLast(stripPrefix(qName));
			text.setLength(0);
			if (isPath("project", "dependencies", "dependency")) {
				current = new Pending(locator == null ? 0 : locator.getLineNumber());
			}
		}

		@Override
		public void characters(char[] ch, int start, int length) {
			text.append(ch, start, length);
		}

		@Override
		public void endElement(String uri, String localName, String qName) {
			String value = text.toString().trim();
			int depth = path.size();
			if (depth == 3 && isPath("project", "properties", path.peekLast())) {
				properties.put(path.peekLast(), value);
			}
			else if (depth == 2 && isPath("project", "groupId")) {
				properties.put("project.groupId", value);
			}
			else if (depth == 2 && isPath("project", "artifactId")) {
				properties.put("project.artifactId", value);
			}
			else if (depth == 3 && isPath("project", "parent", "groupId")) {
				properties.putIfAbsent("project.groupId", value);
				properties.put("project.parent.groupId", value);
			}
			else if (depth == 3 && isPath("project", "parent", "artifactId")) {
				properties.put("project.parent.artifactId", value);
			}
			else if (depth == 3 && isPath("project", "parent", "version")) {
				properties.putIfAbsent("project.version", value);
			}
			else if (depth == 2 && isPath("project", "version")) {
				properties.put("project.version", value);
			}
			else if (isModule(depth) && !value.isEmpty()) {
				modules.add(value);
			}
			else if (current != null && depth == 4
					&& isPath("project", "dependencies", "dependency", path.peekLast())) {
				current.set(path.peekLast(), value, locator == null ? current.startLine : locator.getLineNumber());
			}
			else if (current != null && depth == 3 && isPath("project", "dependencies", "dependency")) {
				current.endLine = locator == null ? current.startLine : locator.getLineNumber();
				pending.add(current);
				current = null;
			}
			text.setLength(0);
			path.removeLast();
		}

		List<DeclaredDependency> resolved() {
			List<DeclaredDependency> dependencies = new ArrayList<>();
			for (Pending dependency : pending) {
				String groupId = resolve(dependency.groupId);
				String artifactId = resolve(dependency.artifactId);
				if (groupId == null || artifactId == null || groupId.isEmpty() || artifactId.isEmpty()) {
					continue;
				}
				dependencies.add(new DeclaredDependency(groupId, artifactId, dependency.scope, dependency.type,
						dependency.classifier, buildFile, dependency.artifactLine, dependency.startLine,
						dependency.endLine));
			}
			return dependencies;
		}

		Structure structure() {
			return new Structure(properties.getOrDefault("project.groupId", ""),
					properties.getOrDefault("project.artifactId", ""),
					properties.getOrDefault("project.parent.groupId", ""),
					properties.getOrDefault("project.parent.artifactId", ""), modules);
		}

		// project/modules/module and project/profiles/profile/modules/module.
		private boolean isModule(int depth) {
			return (depth == 3 && isPath("project", "modules", "module"))
					|| (depth == 5 && isPath("project", "profiles", "profile", "modules", "module"));
		}

		private String resolve(String value) {
			Matcher matcher = PLACEHOLDER.matcher(value);
			StringBuilder resolved = new StringBuilder();
			int last = 0;
			while (matcher.find()) {
				String replacement = properties.get(matcher.group(1));
				if (replacement == null || PLACEHOLDER.matcher(replacement).find()) {
					return null;
				}
				resolved.append(value, last, matcher.start()).append(replacement);
				last = matcher.end();
			}
			return resolved.append(value.substring(last)).toString();
		}

		private boolean isPath(String... expected) {
			if (path.size() != expected.length) {
				return false;
			}
			int i = 0;
			for (String element : path) {
				if (!element.equals(expected[i++])) {
					return false;
				}
			}
			return true;
		}

		private static String stripPrefix(String qName) {
			int colon = qName.indexOf(':');
			return colon < 0 ? qName : qName.substring(colon + 1);
		}

	}

	private static final class Pending {

		final int startLine;

		int endLine;

		int artifactLine;

		String groupId = "";

		String artifactId = "";

		String scope = "";

		String type = "";

		String classifier = "";

		Pending(int startLine) {
			this.startLine = startLine;
			this.endLine = startLine;
			this.artifactLine = startLine;
		}

		void set(String element, String value, int line) {
			switch (element) {
				case "groupId" -> groupId = value;
				case "artifactId" -> {
					artifactId = value;
					artifactLine = line;
				}
				case "scope" -> scope = value;
				case "type" -> type = value;
				case "classifier" -> classifier = value;
				default -> {
				}
			}
		}

	}

}
