package com.patbaumgartner.prune.core.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Replays the fuzzer's seed corpus and every crash it ever found on each build, so a
// fixed parser bug stays fixed.
class ParserFuzzerTest {

	@Test
	void everyCommittedFuzzInputSatisfiesTheParserInvariants() throws IOException, URISyntaxException {
		List<Path> inputs = inputs();

		assertFalse(inputs.isEmpty(), "seed corpus is missing");
		for (Path input : inputs) {
			String text = Files.readString(input, StandardCharsets.UTF_8);
			assertDoesNotThrow(() -> ParserFuzzer.check(text), input.getFileName().toString());
		}
	}

	@Test
	void theInvariantsFailWhenAParserResultIsInconsistent() {
		var failure = assertThrows(IllegalStateException.class,
				() -> ParserFuzzer.checkSpans(new TypeDeclaration(TypeDeclaration.TypeKind.CLASS, "A", List.of(), false,
						false, List.of(), 5, 2, -1, 4, List.of(), List.of()), "class A {}"));

		assertDoesNotThrow(() -> ParserFuzzer.check(""));
		assertDoesNotThrow(() -> ParserFuzzer.check("class A { private int x = ; }} ]]]"));
		assertFalse(failure.getMessage().isEmpty());
	}

	private static List<Path> inputs() throws IOException, URISyntaxException {
		var corpus = Path.of(ParserFuzzerTest.class.getResource("ParserFuzzerInputs").toURI());
		try (Stream<Path> files = Files.list(corpus)) {
			return files.filter(Files::isRegularFile).sorted().toList();
		}
		catch (UncheckedIOException exception) {
			throw exception.getCause();
		}
	}

}
