package com.patbaumgartner.prune.core.analyzer;

import com.patbaumgartner.prune.core.source.JavaSourceFile;

// Project-wide facts a guard may consult. Implemented by the analyzer's reference index.
public interface AnalysisContext {

	// The module the file belongs to (the tree above its src directory) declares a
	// module-info.java. A descriptor in a sibling module says nothing about this one.
	boolean moduleDescriptorPresent(JavaSourceFile file);

	// A reflective serialization library (Gson, Jackson, JPA, ...) is imported somewhere
	// in the build.
	boolean reflectiveSerializationInUse();

	// The word appears in a string literal, a text resource, a build file, or a
	// configuration file.
	boolean mentionedInLiteralsOrResources(String word);

}
