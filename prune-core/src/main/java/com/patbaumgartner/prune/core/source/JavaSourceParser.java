package com.patbaumgartner.prune.core.source;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class JavaSourceParser {

	private static final Set<String> MODIFIERS = Set.of("public", "private", "protected", "static", "final", "abstract",
			"native", "synchronized", "transient", "volatile", "strictfp", "default");

	private static final Set<String> TYPE_KEYWORDS = Set.of("boolean", "byte", "char", "short", "int", "long", "float",
			"double", "void");

	private final List<Token> tokens;

	private final Token eof;

	private final Set<Integer> documentedStarts;

	private int pos;

	private String packageName = "";

	private final List<String> imports = new ArrayList<>();

	private final List<TypeDeclaration> types = new ArrayList<>();

	private boolean moduleDescriptor;

	private JavaSourceParser(List<Token> significant, Set<Integer> documentedStarts, int length) {
		this.tokens = significant;
		this.documentedStarts = documentedStarts;
		this.eof = new Token(TokenKind.PUNCTUATION, "", length, length);
	}

	public static JavaSourceFile parse(String relativePath, String content) {
		List<Token> all = JavaLexer.tokenize(content);
		List<Token> identifiers = new ArrayList<>();
		List<Token> strings = new ArrayList<>();
		List<Token> significant = new ArrayList<>(all.size());
		Set<Integer> documentedStarts = new HashSet<>();
		boolean javadocPending = false;
		for (Token token : all) {
			switch (token.kind()) {
				case IDENTIFIER -> identifiers.add(token);
				case STRING -> strings.add(token);
				default -> {
				}
			}
			if (token.kind() == TokenKind.COMMENT) {
				javadocPending |= token.text().startsWith("/**");
			}
			else {
				if (javadocPending) {
					documentedStarts.add(token.start());
				}
				javadocPending = false;
				significant.add(token);
			}
		}
		List<String> qualifiedNames = qualifiedNames(significant);

		JavaSourceParser parser = new JavaSourceParser(significant, documentedStarts, content.length());
		// Outside literals and comments a backslash can only start a unicode escape,
		// which
		// this parser does not translate, so an escaped identifier letter would hide a
		// real
		// reference.
		boolean unicodeEscape = significant.stream().anyMatch(token -> token.isPunctuation('\\'));
		boolean structural = !unicodeEscape && parser.parses();
		return new JavaSourceFile(relativePath, content, parser.packageName, parser.imports, qualifiedNames,
				structural ? parser.types : List.of(), identifiers, strings, !structural, parser.moduleDescriptor);
	}

	// False marks the file opaque: its identifiers still count as references, but nothing
	// in it becomes a candidate.
	private boolean parses() {
		try {
			parseCompilationUnit();
			return true;
		}
		catch (RuntimeException structurallyUnparseable) {
			return false;
		}
	}

	private static List<String> qualifiedNames(List<Token> significant) {
		List<String> chains = new ArrayList<>();
		int i = 0;
		while (i < significant.size()) {
			if (significant.get(i).kind() != TokenKind.IDENTIFIER) {
				i++;
				continue;
			}
			StringBuilder chain = new StringBuilder(significant.get(i).text());
			int j = i + 1;
			while (j + 1 < significant.size() && significant.get(j).isPunctuation('.')
					&& significant.get(j + 1).kind() == TokenKind.IDENTIFIER) {
				chain.append('.').append(significant.get(j + 1).text());
				j += 2;
			}
			if (j > i + 1) {
				chains.add(chain.toString());
			}
			i = j;
		}
		return chains;
	}

	private void parseCompilationUnit() {
		int beforeAnnotations = pos;
		skipAnnotations();
		if (peek().isKeyword("package")) {
			next();
			packageName = readQualifiedName();
			expect(';');
		}
		else {
			pos = beforeAnnotations;
		}
		while (peek().isKeyword("import")) {
			next();
			if (peek().isKeyword("static")
					|| (peek().isIdentifier("module") && peek(1).kind() == TokenKind.IDENTIFIER)) {
				next();
			}
			imports.add(readImportName());
			expect(';');
		}
		while (!atEnd()) {
			if (peek().isPunctuation(';')) {
				next();
				continue;
			}
			int start = pos;
			skipAnnotations();
			if ((peek().isIdentifier("module") && peek(1).kind() == TokenKind.IDENTIFIER)
					|| (peek().isIdentifier("open") && peek(1).isIdentifier("module"))) {
				moduleDescriptor = true;
				return;
			}
			pos = start;
			types.add(parseType());
		}
	}

	private TypeDeclaration parseType() {
		int start = peek().start();
		List<String> modifiers = new ArrayList<>();
		boolean annotated = false;
		int publicOffset = -1;
		TypeDeclaration.TypeKind kind;
		while (true) {
			Token token = peek();
			if (token.isPunctuation('@')) {
				if (peek(1).isKeyword("interface")) {
					next();
					next();
					kind = TypeDeclaration.TypeKind.ANNOTATION;
					break;
				}
				skipAnnotation();
				annotated = true;
			}
			else if (token.kind() == TokenKind.KEYWORD && MODIFIERS.contains(token.text())) {
				if ("public".equals(token.text())) {
					publicOffset = token.start();
				}
				modifiers.add(next().text());
			}
			else if (isSealedModifier()) {
				modifiers.add(readSealedModifier());
			}
			else if (token.isKeyword("class")) {
				next();
				kind = TypeDeclaration.TypeKind.CLASS;
				break;
			}
			else if (token.isKeyword("interface")) {
				next();
				kind = TypeDeclaration.TypeKind.INTERFACE;
				break;
			}
			else if (token.isKeyword("enum")) {
				next();
				kind = TypeDeclaration.TypeKind.ENUM;
				break;
			}
			else if (token.isIdentifier("record") && peek(1).kind() == TokenKind.IDENTIFIER) {
				next();
				kind = TypeDeclaration.TypeKind.RECORD;
				break;
			}
			else {
				throw new ParseException("expected a type declaration at offset " + token.start());
			}
		}
		Token name = expectIdentifier();
		if (peek().isPunctuation('<')) {
			skipBalanced('<', '>');
		}
		if (kind == TypeDeclaration.TypeKind.RECORD) {
			skipBalanced('(', ')');
		}
		List<String> superTypes = new ArrayList<>();
		while (!peek().isPunctuation('{')) {
			Token token = next();
			if (token.isPunctuation('@')) {
				skipAnnotationName();
			}
			else if (token.kind() == TokenKind.IDENTIFIER) {
				superTypes.add(token.text());
			}
		}
		List<MemberDeclaration> members = new ArrayList<>();
		List<TypeDeclaration> nested = new ArrayList<>();
		parseBody(kind, name.text(), members, nested);
		return new TypeDeclaration(kind, name.text(), modifiers, annotated, documentedStarts.contains(start),
				superTypes, start, name.start(), publicOffset, lastEnd(), members, nested);
	}

	private void parseBody(TypeDeclaration.TypeKind kind, String typeName, List<MemberDeclaration> members,
			List<TypeDeclaration> nested) {
		expect('{');
		if (kind == TypeDeclaration.TypeKind.ENUM) {
			skipEnumConstants();
		}
		while (true) {
			Token token = peek();
			if (token.isPunctuation('}')) {
				next();
				return;
			}
			if (token.isPunctuation(';')) {
				next();
				continue;
			}
			parseMember(typeName, members, nested);
		}
	}

	private void skipEnumConstants() {
		while (true) {
			Token token = peek();
			if (token.isPunctuation('}')) {
				return;
			}
			if (token.isPunctuation(';')) {
				next();
				return;
			}
			if (token.isPunctuation('(')) {
				skipBalanced('(', ')');
			}
			else if (token.isPunctuation('{')) {
				skipBalanced('{', '}');
			}
			else {
				next();
			}
		}
	}

	private void parseMember(String typeName, List<MemberDeclaration> members, List<TypeDeclaration> nested) {
		int save = pos;
		int start = peek().start();
		List<String> modifiers = new ArrayList<>();
		boolean annotated = false;
		while (true) {
			Token token = peek();
			if (token.isPunctuation('@')) {
				if (peek(1).isKeyword("interface")) {
					pos = save;
					nested.add(parseType());
					return;
				}
				skipAnnotation();
				annotated = true;
			}
			else if (token.kind() == TokenKind.KEYWORD && MODIFIERS.contains(token.text())) {
				modifiers.add(next().text());
			}
			else if (isSealedModifier()) {
				modifiers.add(readSealedModifier());
			}
			else if (token.isKeyword("class") || token.isKeyword("interface") || token.isKeyword("enum")
					|| (token.isIdentifier("record") && peek(1).kind() == TokenKind.IDENTIFIER
							&& (peek(2).isPunctuation('(') || peek(2).isPunctuation('<')))) {
				pos = save;
				nested.add(parseType());
				return;
			}
			else if (token.isPunctuation('{')) {
				skipBalanced('{', '}');
				members.add(new MemberDeclaration(MemberDeclaration.MemberKind.INITIALIZER, "<init>", modifiers,
						annotated, start, start, lastEnd(), 1));
				return;
			}
			else if (token.isPunctuation('<')) {
				skipBalanced('<', '>');
			}
			else {
				break;
			}
		}

		@Nullable Token name = null;
		int identifierCount = 0;
		boolean sawTypeKeyword = false;
		int angleDepth = 0;
		while (true) {
			Token token = peek();
			if (token.isPunctuation('@')) {
				skipAnnotation();
				annotated = true;
			}
			else if (token.isPunctuation('<')) {
				angleDepth++;
				next();
			}
			else if (token.isPunctuation('>')) {
				angleDepth--;
				next();
			}
			else if (angleDepth > 0) {
				next();
			}
			else if (token.kind() == TokenKind.IDENTIFIER) {
				name = next();
				identifierCount++;
			}
			else if (token.kind() == TokenKind.KEYWORD && TYPE_KEYWORDS.contains(token.text())) {
				sawTypeKeyword = true;
				next();
			}
			else if (token.isPunctuation('.') || token.isPunctuation('[') || token.isPunctuation(']')) {
				next();
			}
			else {
				break;
			}
		}
		if (name == null) {
			throw new ParseException("expected a member name at offset " + peek().start());
		}

		Token token = peek();
		if (token.isPunctuation('(')) {
			boolean constructor = identifierCount == 1 && !sawTypeKeyword && name.text().equals(typeName);
			// An annotated parameter (@Observes, @PathParam, @Payload) means a container
			// calls the method.
			annotated |= skipBalancedContainsAnnotation('(', ')');
			skipMethodTail();
			members.add(new MemberDeclaration(
					constructor ? MemberDeclaration.MemberKind.CONSTRUCTOR : MemberDeclaration.MemberKind.METHOD,
					name.text(), modifiers, annotated, start, name.start(), lastEnd(), 1));
			return;
		}
		if (token.isPunctuation('{')) {
			if (!name.text().equals(typeName)) {
				throw new ParseException("unexpected block after " + name.text());
			}
			skipBalanced('{', '}');
			members.add(new MemberDeclaration(MemberDeclaration.MemberKind.CONSTRUCTOR, name.text(), modifiers,
					annotated, start, name.start(), lastEnd(), 1));
			return;
		}

		List<Token> declarators = new ArrayList<>();
		declarators.add(name);
		while (true) {
			Token next = peek();
			if (next.isPunctuation('=')) {
				next();
				skipInitializer();
				next = peek();
			}
			if (next.isPunctuation(',')) {
				next();
				declarators.add(expectIdentifier());
				while (peek().isPunctuation('[') || peek().isPunctuation(']')) {
					next();
				}
			}
			else if (next.isPunctuation(';')) {
				next();
				break;
			}
			else {
				throw new ParseException("unexpected token in field declaration at offset " + next.start());
			}
		}
		for (Token declarator : declarators) {
			members.add(new MemberDeclaration(MemberDeclaration.MemberKind.FIELD, declarator.text(), modifiers,
					annotated, start, declarator.start(), lastEnd(), declarators.size()));
		}
	}

	private void skipMethodTail() {
		boolean sawDefault = false;
		while (true) {
			Token token = peek();
			if (token.isPunctuation('{')) {
				skipBalanced('{', '}');
				if (!sawDefault) {
					return;
				}
			}
			else if (token.isPunctuation(';')) {
				next();
				return;
			}
			else if (token.isPunctuation('(')) {
				skipBalanced('(', ')');
			}
			else if (token.isPunctuation('@')) {
				skipAnnotation();
			}
			else {
				if (token.isKeyword("default")) {
					sawDefault = true;
				}
				next();
			}
		}
	}

	private void skipInitializer() {
		int depth = 0;
		while (true) {
			Token token = peek();
			if (depth == 0 && (token.isPunctuation(',') || token.isPunctuation(';'))) {
				return;
			}
			if (token.isPunctuation('(') || token.isPunctuation('{') || token.isPunctuation('[')) {
				depth++;
			}
			else if (token.isPunctuation(')') || token.isPunctuation('}') || token.isPunctuation(']')) {
				depth--;
				if (depth < 0) {
					throw new ParseException("unbalanced initializer at offset " + token.start());
				}
			}
			else if (depth == 0 && token.isPunctuation('<') && looksLikeTypeArguments()) {
				skipBalanced('<', '>');
				continue;
			}
			next();
		}
	}

	// `<` in an expression is either a comparison or the start of type arguments. At
	// declarator depth, type arguments can only follow `new Type`, `instanceof Type`, an
	// explicit witness `Type.<T>method`, or precede a method reference `Type<T>::name`;
	// anything else, such as `p = a < b, q = c > d`, is a comparison whose comma ends the
	// declarator.
	private boolean looksLikeTypeArguments() {
		int close = typeArgumentsCloseAt();
		if (close < 0) {
			return false;
		}
		Token before = pos > 0 ? tokens.get(pos - 1) : eof;
		if (before.isPunctuation('.')) {
			return true;
		}
		if (before.kind() != TokenKind.IDENTIFIER) {
			return false;
		}
		Token beforeChain = tokenBeforeQualifiedChain(pos - 1);
		Token after = peek(close + 1);
		if (beforeChain.isKeyword("new")) {
			return after.isPunctuation('(') || after.isPunctuation('[');
		}
		return beforeChain.isKeyword("instanceof") || (after.isPunctuation(':') && peek(close + 2).isPunctuation(':'));
	}

	// Offset from pos of the `>` that closes the run starting at pos, or -1 when the run
	// contains anything that cannot appear inside type arguments.
	private int typeArgumentsCloseAt() {
		int depth = 0;
		for (int ahead = 0; ahead < 64; ahead++) {
			Token token = peek(ahead);
			if (token.isPunctuation('<')) {
				depth++;
			}
			else if (token.isPunctuation('>')) {
				depth--;
				if (depth == 0) {
					return ahead;
				}
			}
			else if (!(token.kind() == TokenKind.IDENTIFIER
					|| (token.kind() == TokenKind.KEYWORD && (TYPE_KEYWORDS.contains(token.text())
							|| "extends".equals(token.text()) || "super".equals(token.text())))
					|| token.isPunctuation('.') || token.isPunctuation(',') || token.isPunctuation('?')
					|| token.isPunctuation('[') || token.isPunctuation(']') || token.isPunctuation('@'))) {
				return -1;
			}
		}
		return -1;
	}

	private Token tokenBeforeQualifiedChain(int identifierIndex) {
		int index = identifierIndex;
		while (index >= 2 && tokens.get(index - 1).isPunctuation('.')
				&& tokens.get(index - 2).kind() == TokenKind.IDENTIFIER) {
			index -= 2;
		}
		return index > 0 ? tokens.get(index - 1) : eof;
	}

	private void skipAnnotations() {
		while (peek().isPunctuation('@') && !peek(1).isKeyword("interface")) {
			skipAnnotation();
		}
	}

	private void skipAnnotation() {
		expect('@');
		skipAnnotationName();
	}

	private void skipAnnotationName() {
		expectIdentifier();
		while (peek().isPunctuation('.') && peek(1).kind() == TokenKind.IDENTIFIER) {
			next();
			next();
		}
		if (peek().isPunctuation('(')) {
			skipBalanced('(', ')');
		}
	}

	private boolean isSealedModifier() {
		Token token = peek();
		boolean sealed = token.isIdentifier("sealed");
		boolean nonSealed = token.isIdentifier("non") && peek(1).isPunctuation('-') && peek(2).isIdentifier("sealed");
		if (!sealed && !nonSealed) {
			return false;
		}
		for (int i = nonSealed ? 3 : 1; i < 8; i++) {
			Token ahead = peek(i);
			if (ahead.isKeyword("class") || ahead.isKeyword("interface")) {
				return true;
			}
			if (ahead.kind() == TokenKind.PUNCTUATION && !ahead.isPunctuation('@')) {
				return false;
			}
		}
		return false;
	}

	private String readSealedModifier() {
		if (peek().isIdentifier("non")) {
			next();
			next();
			next();
			return "non-sealed";
		}
		next();
		return "sealed";
	}

	private String readQualifiedName() {
		StringBuilder name = new StringBuilder(expectIdentifier().text());
		while (peek().isPunctuation('.')) {
			next();
			name.append('.').append(expectIdentifier().text());
		}
		return name.toString();
	}

	private String readImportName() {
		StringBuilder name = new StringBuilder(expectIdentifier().text());
		while (peek().isPunctuation('.')) {
			next();
			if (peek().isPunctuation('*')) {
				next();
				name.append(".*");
				break;
			}
			name.append('.').append(expectIdentifier().text());
		}
		return name.toString();
	}

	private void skipBalanced(char open, char close) {
		skipBalancedContainsAnnotation(open, close);
	}

	private boolean skipBalancedContainsAnnotation(char open, char close) {
		if (!peek().isPunctuation(open)) {
			throw new ParseException("expected '" + open + "' at offset " + peek().start());
		}
		boolean annotation = false;
		int depth = 0;
		while (true) {
			Token token = next();
			if (token.isPunctuation(open)) {
				depth++;
			}
			else if (token.isPunctuation(close)) {
				depth--;
				if (depth == 0) {
					return annotation;
				}
			}
			else if (token.isPunctuation('@')) {
				annotation = true;
			}
		}
	}

	private Token expectIdentifier() {
		Token token = peek();
		if (token.kind() != TokenKind.IDENTIFIER) {
			throw new ParseException("expected an identifier at offset " + token.start());
		}
		return next();
	}

	private void expect(char symbol) {
		Token token = next();
		if (!token.isPunctuation(symbol)) {
			throw new ParseException("expected '" + symbol + "' at offset " + token.start());
		}
	}

	private boolean atEnd() {
		return pos >= tokens.size();
	}

	private Token peek() {
		return peek(0);
	}

	private Token peek(int ahead) {
		int index = pos + ahead;
		return index >= tokens.size() ? eof : tokens.get(index);
	}

	private Token next() {
		if (atEnd()) {
			throw new ParseException("unexpected end of file");
		}
		return tokens.get(pos++);
	}

	private int lastEnd() {
		return tokens.get(pos - 1).end();
	}

	private static final class ParseException extends RuntimeException {

		ParseException(String message) {
			super(message);
		}

	}

}
