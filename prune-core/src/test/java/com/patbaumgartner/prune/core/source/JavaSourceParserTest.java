package com.patbaumgartner.prune.core.source;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceParserTest {

	@Test
	void readsPackageImportsAndTopLevelTypeModifiers() {
		var source = JavaSourceParser.parse("A.java", """
				@Deprecated
				package com.example;

				import java.util.List;
				import static java.util.Map.of;
				import com.other.*;

				public final class A {
				}

				@FunctionalInterface
				interface B {
				}

				enum C { X, Y(1) { }; C() { } C(int i) { } }

				record D(int x) implements java.io.Serializable { }

				@interface E { }
				""");

		assertEquals("com.example", source.packageName());
		assertEquals(List.of("java.util.List", "java.util.Map.of", "com.other.*"), source.imports());
		assertEquals(List.of("A", "B", "C", "D", "E"), source.types().stream().map(TypeDeclaration::name).toList());
		assertTrue(source.types().get(0).isPublic());
		assertEquals(List.of("public", "final"), source.types().get(0).modifiers());
		assertFalse(source.types().get(0).annotated());
		assertFalse(source.types().get(0).documented());
		assertTrue(source.types().get(1).annotated());
		assertEquals(TypeDeclaration.TypeKind.ENUM, source.types().get(2).kind());
		assertEquals(2, source.types().get(2).constructors().size());
		assertEquals(TypeDeclaration.TypeKind.RECORD, source.types().get(3).kind());
		assertEquals(List.of("java", "io", "Serializable"), source.types().get(3).superTypes());
		assertEquals(TypeDeclaration.TypeKind.ANNOTATION, source.types().get(4).kind());
		assertFalse(source.opaque());
	}

	@Test
	void readsMembersWithKindsModifiersAnnotationsAndSpans() {
		var text = """
				package p;

				class A<T extends Comparable<T>> {
				    private static final int X = 1, Y = 2;
				    private int[] arr = {1, 2};
				    @Deprecated
				    private String annotated;
				    java.util.List<java.util.Map<String, int[]>> generic;
				    A() { }
				    private A(int x) { this(); }
				    <R> R method(java.util.function.Function<T, R> f) throws Exception { return null; }
				    private static native void nativeOne();
				    { arr[0] = 1; }
				    static { }
				    private void foo(int a, String... rest) {
				        Runnable r = () -> { int foo = 1; };
				    }
				}
				""";

		var type = JavaSourceParser.parse("A.java", text).types().get(0);

		assertEquals(MemberDeclaration.MemberKind.FIELD, member(type, "X").kind());
		assertEquals(2, member(type, "X").declaratorCount());
		assertEquals(2, member(type, "Y").declaratorCount());
		assertEquals(List.of("private", "static", "final"), member(type, "X").modifiers());
		assertEquals(MemberDeclaration.MemberKind.FIELD, member(type, "arr").kind());
		assertTrue(member(type, "annotated").annotated());
		assertEquals(MemberDeclaration.MemberKind.FIELD, member(type, "generic").kind());
		assertEquals(MemberDeclaration.MemberKind.CONSTRUCTOR, member(type, "A").kind());
		assertEquals(2, type.constructors().size());
		assertEquals(MemberDeclaration.MemberKind.METHOD, member(type, "method").kind());
		assertTrue(member(type, "nativeOne").hasModifier("native"));
		assertEquals(2,
				type.members().stream().filter(m -> m.kind() == MemberDeclaration.MemberKind.INITIALIZER).count());
		var foo = member(type, "foo");
		assertEquals("private void foo", text.substring(foo.start(), foo.start() + 16));
		assertEquals('}', text.charAt(foo.end() - 1));
		assertEquals(text.indexOf("foo(int"), foo.nameOffset());
	}

	@Test
	void anAnnotationOnAParameterMarksTheMethodAnnotatedButNotItsNeighbours() {
		var type = JavaSourceParser.parse("A.java", """
				package p;

				class A {
				    private void onStart(@Observes StartupEvent event) { }
				    private void plain(StartupEvent event) { }
				    private void annotatedInsideBody() { @SuppressWarnings("x") int local = 1; }
				    A(@Named("x") String name) { }
				}
				""").types().get(0);

		assertTrue(member(type, "onStart").annotated());
		assertFalse(member(type, "plain").annotated());
		assertFalse(member(type, "annotatedInsideBody").annotated());
		assertTrue(member(type, "A").annotated());
	}

	@Test
	void recognisesRecordCompactConstructorsAndNestedTypes() {
		var source = JavaSourceParser.parse("R.java", """
				package p;

				public record R(int x, String y) {
				    private static final int LIMIT = 3;
				    public R {
				        if (x < 0) throw new IllegalArgumentException();
				    }
				    record Inner(int z) { }
				    enum Kind { A, B }
				    sealed interface Shape permits Circle { }
				    record Circle() implements Shape { }
				    non-sealed class Open { }
				}
				""");

		var record = source.types().get(0);
		assertEquals(1, record.constructors().size());
		assertEquals(List.of("Inner", "Kind", "Shape", "Circle", "Open"),
				record.nestedTypes().stream().map(TypeDeclaration::name).toList());
		assertEquals(List.of("sealed"), record.nestedTypes().get(2).modifiers());
		assertEquals(List.of("non-sealed"), record.nestedTypes().get(4).modifiers());
	}

	@Test
	void marksTypesPrecededByJavadocAsDocumentedEvenAcrossAnnotationsAndOtherComments() {
		var source = JavaSourceParser.parse("A.java", """
				package p;

				/** Documented. */
				@Deprecated
				public class A { }

				/* plain block comment */
				public class B { }

				/**
				 * Also documented.
				 */
				// then a line comment
				class C { }

				class D { /** member doc, not type doc */ int x; }
				""");

		assertTrue(source.types().get(0).documented());
		assertFalse(source.types().get(1).documented());
		assertTrue(source.types().get(2).documented());
		assertFalse(source.types().get(3).documented());
	}

	@Test
	void detectsAnyNonPrivateMainMethodAsAnEntryPoint() {
		var classic = JavaSourceParser.parse("A.java", "class A { public static void main(String... a) { } }");
		var instance = JavaSourceParser.parse("B.java", "class B { void main() { } }");
		var hidden = JavaSourceParser.parse("C.java", "class C { private static void main(String[] a) { } }");

		assertTrue(classic.types().get(0).hasMainMethod());
		assertTrue(instance.types().get(0).hasMainMethod());
		assertFalse(hidden.types().get(0).hasMainMethod());
	}

	@Test
	void marksStructurallyBrokenSourcesOpaqueButKeepsTheirTokens() {
		var source = JavaSourceParser.parse("A.java", "package p;\nclass A {\n  Helper h = ;;; {{{ \"str\" \n");

		assertTrue(source.opaque());
		assertEquals("p", source.packageName());
		assertTrue(source.types().isEmpty());
		assertTrue(source.mentions("Helper"));
		assertTrue(source.stringLiteralsContainWord("str"));
	}

	@Test
	void marksSourcesWithAUnicodeEscapeOutsideALiteralOpaque() {
		var escapedCall = JavaSourceParser.parse("A.java",
				"package p;\nclass A {\n    private void helper() {}\n    void run() { \\u0068elper(); }\n}\n");
		var escapedInLiterals = JavaSourceParser.parse("B.java",
				"package p;\nclass B {\n    String s = \"\\u0068elper\";\n    char c = '\\u0041';\n    // \\u0068elper in a comment\n}\n");

		assertTrue(escapedCall.opaque());
		assertFalse(escapedInLiterals.opaque());
		assertEquals("B", escapedInLiterals.types().get(0).name());
	}

	@Test
	void recognisesModuleDescriptors() {
		var source = JavaSourceParser.parse("module-info.java",
				"open module com.example {\n    requires java.sql;\n    exports com.example;\n}\n");

		assertTrue(source.moduleDescriptor());
		assertTrue(source.types().isEmpty());
	}

	@Test
	void aByteOrderMarkDoesNotHideTheDeclarations() {
		var source = JavaSourceParser.parse("A.java", "\uFEFFpackage p;\n\nclass A {\n    private int x;\n}\n");

		assertFalse(source.opaque());
		assertEquals("p", source.packageName());
		assertEquals("A", source.types().get(0).name());
	}

	@Test
	void typeArgumentsWithCommasInsideInitializersDoNotEndTheDeclarator() {
		var source = JavaSourceParser.parse("A.java",
				"""
						class A {
						    private Map<String, Integer> counts = new HashMap<String, Integer>();
						    private Map<String, List<Map<Integer, String>>> nested = new HashMap<String, List<Map<Integer, String>>>();
						    private boolean empty = o instanceof Map<?, ?> m && m.isEmpty();
						    private List<? extends Number> bounded = new ArrayList<? extends Number>();
						    private Object[] witness = Collections.<Object>emptyList().toArray(new Object[0]);
						    private Supplier<Map<String, Integer>> ref = HashMap<String, Integer>::new;
						    private Object qualified = new java.util.HashMap<String, Integer>();
						    private int orphan;
						}
						""");

		assertFalse(source.opaque());
		assertEquals(List.of("counts", "nested", "empty", "bounded", "witness", "ref", "qualified", "orphan"),
				source.types().get(0).members().stream().map(MemberDeclaration::name).toList());
	}

	@Test
	void comparisonsAndShiftsInInitializersStillSeparateDeclarators() {
		var source = JavaSourceParser.parse("A.java", """
				class A {
				    private boolean b = 1 < 2, c = 3 > 2;
				    private boolean p = a < b, q = c > d;
				    private boolean r = x < y, s = z > w, t = v > u;
				    private int x = a < b ? 1 : 2, y = 3;
				    private int s2 = 1 << 2, t2 = 8 >> 1, u2 = -1 >>> 1;
				    private int orphan;
				}
				""");

		assertFalse(source.opaque());
		assertEquals(List.of("b", "c", "p", "q", "r", "s", "t", "x", "y", "s2", "t2", "u2", "orphan"),
				source.types().get(0).members().stream().map(MemberDeclaration::name).toList());
	}

	@Test
	void collectsQualifiedNameChainsAndExcludesDeclarationTokensFromReferences() {
		var text = "package p;\nclass A {\n    private int count;\n    int read() { return org.example.Util.count(this.count); }\n}\n";

		var source = JavaSourceParser.parse("A.java", text);

		assertEquals(List.of("org.example.Util.count"), source.qualifiedNames());
		var count = source.types().get(0).members().get(0);
		assertTrue(source.mentionsOutside("count", count.nameOffset(), count.nameOffset() + "count".length()));
		assertFalse(source.mentionsOutside("read", text.indexOf("read"), text.indexOf("read") + 4));
	}

	@Test
	void lexerHandlesTextBlocksCharLiteralsCommentsAndNumbers() {
		var tokens = JavaLexer.tokenize("""
				String s = \"""
				    not // a comment "quote"
				    \""";
				char c = '"'; int x = 0x1F_FFp+3; double d = .5e-3; // Trailing
				/* block Helper */ Helper h;
				""");

		var strings = tokens.stream().filter(t -> t.kind() == TokenKind.STRING).toList();
		assertEquals(1, strings.size());
		assertTrue(strings.get(0).text().contains("// a comment"));
		assertEquals(List.of("'\"'"),
				tokens.stream().filter(t -> t.kind() == TokenKind.CHAR).map(Token::text).toList());
		assertEquals(List.of("0x1F_FFp+3", ".5e-3"),
				tokens.stream().filter(t -> t.kind() == TokenKind.NUMBER).map(Token::text).toList());
		assertEquals(List.of("// Trailing", "/* block Helper */"),
				tokens.stream().filter(t -> t.kind() == TokenKind.COMMENT).map(Token::text).toList());
		assertEquals(1, tokens.stream().filter(t -> t.isIdentifier("Helper")).count());
	}

	@Test
	void lineMapConvertsOffsetsForLfCrLfAndCrEndings() {
		var map = new LineMap("ab\ncd\r\nef\rgh");

		assertEquals(4, map.lineCount());
		assertEquals(1, map.lineOf(0));
		assertEquals(2, map.lineOf(3));
		assertEquals(2, map.columnOf(4));
		assertEquals(3, map.lineOf(7));
		assertEquals(4, map.lineOf(10));
		assertEquals(4, map.lineOf(99));
		assertEquals(7, map.startOf(3));
		assertEquals(12, map.startOf(5));
	}

	@Test
	void wholeWordSearchIgnoresPartialMatchesButAcceptsDotAndDollarBoundaries() {
		assertTrue(TextSearch.containsWord("com.example.Foo", "Foo"));
		assertTrue(TextSearch.containsWord("Outer$Foo", "Foo"));
		assertTrue(TextSearch.containsWord("\"Foo\"", "Foo"));
		assertFalse(TextSearch.containsWord("FooBar", "Foo"));
		assertFalse(TextSearch.containsWord("MyFoo", "Foo"));
		assertFalse(TextSearch.containsWord("Foo_", "Foo"));
		assertFalse(TextSearch.containsWord("", "Foo"));
		assertFalse(TextSearch.containsWord("Foo", ""));
	}

	@Test
	void wordIndexSplitsExactlyWhereWholeWordSearchSeesBoundaries() {
		var words = new HashSet<String>();

		TextSearch.words("com.example.Foo Outer$Inner \"Bar\" MyFoo Foo_ x1 ünï", words);

		assertEquals(Set.of("com", "example", "Foo", "Outer", "Inner", "Bar", "MyFoo", "Foo_", "x1", "ünï"), words);
	}

	private static MemberDeclaration member(TypeDeclaration type, String name) {
		return type.members().stream().filter(member -> member.name().equals(name)).findFirst().orElseThrow();
	}

}
