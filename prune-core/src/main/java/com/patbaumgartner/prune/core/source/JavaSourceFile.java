package com.patbaumgartner.prune.core.source;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JavaSourceFile {

	private final String relativePath;

	private final String content;

	private final LineMap lineMap;

	private final String packageName;

	private final List<String> imports;

	private final List<String> qualifiedNames;

	private final List<TypeDeclaration> types;

	private final List<Token> identifiers;

	private final List<Token> strings;

	private final Map<String, int[]> identifierOffsets;

	private final boolean opaque;

	private final boolean moduleDescriptor;

	JavaSourceFile(String relativePath, String content, String packageName, List<String> imports,
			List<String> qualifiedNames, List<TypeDeclaration> types, List<Token> identifiers, List<Token> strings,
			boolean opaque, boolean moduleDescriptor) {
		this.relativePath = relativePath;
		this.content = content;
		this.lineMap = new LineMap(content);
		this.packageName = packageName;
		this.imports = List.copyOf(imports);
		this.qualifiedNames = List.copyOf(qualifiedNames);
		this.types = List.copyOf(types);
		this.identifiers = List.copyOf(identifiers);
		this.strings = List.copyOf(strings);
		this.identifierOffsets = index(identifiers);
		this.opaque = opaque;
		this.moduleDescriptor = moduleDescriptor;
	}

	private static Map<String, int[]> index(List<Token> identifiers) {
		Map<String, int[]> counts = new HashMap<>();
		for (Token token : identifiers) {
			counts.merge(token.text(), new int[] { 1 }, (a, b) -> {
				a[0]++;
				return a;
			});
		}
		Map<String, int[]> offsets = new HashMap<>();
		counts.forEach((name, count) -> offsets.put(name, new int[count[0]]));
		Map<String, Integer> filled = new HashMap<>();
		for (Token token : identifiers) {
			int next = filled.merge(token.text(), 1, Integer::sum) - 1;
			Objects.requireNonNull(offsets.get(token.text()))[next] = token.start();
		}
		return offsets;
	}

	public String relativePath() {
		return relativePath;
	}

	public String content() {
		return content;
	}

	public LineMap lineMap() {
		return lineMap;
	}

	public String packageName() {
		return packageName;
	}

	public List<String> imports() {
		return imports;
	}

	public List<String> qualifiedNames() {
		return qualifiedNames;
	}

	public List<TypeDeclaration> types() {
		return types;
	}

	public List<Token> identifiers() {
		return identifiers;
	}

	public List<Token> strings() {
		return strings;
	}

	public Set<String> identifierNames() {
		return identifierOffsets.keySet();
	}

	public boolean opaque() {
		return opaque;
	}

	public boolean moduleDescriptor() {
		return moduleDescriptor;
	}

	public boolean mentions(String identifier) {
		return identifierOffsets.containsKey(identifier);
	}

	public boolean mentionsOutside(String identifier, int excludeStart, int excludeEnd) {
		int[] offsets = identifierOffsets.get(identifier);
		if (offsets == null) {
			return false;
		}
		for (int offset : offsets) {
			if (offset < excludeStart || offset >= excludeEnd) {
				return true;
			}
		}
		return false;
	}

	public boolean stringLiteralsContainWord(String word) {
		for (Token string : strings) {
			if (TextSearch.containsWord(string.text(), word)) {
				return true;
			}
		}
		return false;
	}

	public String qualifiedName(TypeDeclaration type) {
		return packageName.isEmpty() ? type.name() : packageName + "." + type.name();
	}

}
