package com.patbaumgartner.prune.core.fix;

import com.patbaumgartner.prune.core.dependency.DeclaredDependency;
import com.patbaumgartner.prune.core.dependency.GradleBuildParser;
import com.patbaumgartner.prune.core.dependency.MavenPomParser;
import com.patbaumgartner.prune.core.report.AnalysisIssue;
import com.patbaumgartner.prune.core.report.AnalysisReport;
import com.patbaumgartner.prune.core.report.IssueType;
import com.patbaumgartner.prune.core.report.SourceLocation;
import com.patbaumgartner.prune.core.source.JavaLexer;
import com.patbaumgartner.prune.core.source.JavaSourceFile;
import com.patbaumgartner.prune.core.source.JavaSourceParser;
import com.patbaumgartner.prune.core.source.LineMap;
import com.patbaumgartner.prune.core.source.MemberDeclaration;
import com.patbaumgartner.prune.core.source.Token;
import com.patbaumgartner.prune.core.source.TokenKind;
import com.patbaumgartner.prune.core.source.TypeDeclaration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Plans edits by re-locating each finding in the current source text, and applies them only while
// the text under every edit is still exactly what was planned.
public final class SourceAutofixEngine implements AutofixEngine {

    private final Path projectRoot;

    public SourceAutofixEngine(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    @Override
    public FixPlan plan(AnalysisReport report) {
        Map<String, String> contents = new HashMap<>();
        List<FixAction> actions = new ArrayList<>();
        for (AnalysisIssue issue : report.issues()) {
            if (!issue.autoFixable()) {
                continue;
            }
            FixAction action = planFor(issue, contents);
            if (action != null) {
                actions.add(action);
            }
        }
        actions.sort(Comparator.comparing((FixAction action) -> action.file().toString())
                .thenComparingInt(FixAction::startOffset));
        List<FixAction> disjoint = new ArrayList<>();
        for (FixAction action : actions) {
            FixAction previous = disjoint.isEmpty() ? null : disjoint.get(disjoint.size() - 1);
            if (previous == null || !previous.file().equals(action.file()) || previous.endOffset() <= action.startOffset()) {
                disjoint.add(action);
            }
        }
        List<FixAction> withImports = new ArrayList<>();
        Map<Path, List<FixAction>> byFile = new LinkedHashMap<>();
        for (FixAction action : disjoint) {
            byFile.computeIfAbsent(action.file(), key -> new ArrayList<>()).add(action);
        }
        for (Map.Entry<Path, List<FixAction>> entry : byFile.entrySet()) {
            List<FixAction> fileActions = entry.getValue();
            String content = contents.get(SourceLocation.parse(fileActions.get(0).issue().location()).path());
            if (content != null && entry.getKey().getFileName().toString().endsWith(".java")) {
                withImports.addAll(ImportCleanup.plan(entry.getKey(), content, fileActions));
            }
            withImports.addAll(fileActions);
        }
        return new FixPlan(withImports);
    }

    @Override
    public FixResult apply(FixPlan plan) {
        Map<Path, List<FixAction>> byFile = new LinkedHashMap<>();
        for (FixAction action : plan.actions()) {
            byFile.computeIfAbsent(action.file(), key -> new ArrayList<>()).add(action);
        }
        Map<Path, String> rewritten = new LinkedHashMap<>();
        for (Map.Entry<Path, List<FixAction>> entry : byFile.entrySet()) {
            String content = readUtf8(entry.getKey());
            if (content == null) {
                throw new IllegalStateException("Cannot rewrite " + entry.getKey() + ": not valid UTF-8");
            }
            List<FixAction> actions = new ArrayList<>(entry.getValue());
            actions.sort(Comparator.comparingInt(FixAction::startOffset));
            int previousEnd = -1;
            for (FixAction action : actions) {
                if (action.startOffset() < previousEnd) {
                    throw new IllegalStateException("Overlapping fixes in " + entry.getKey());
                }
                if (action.endOffset() > content.length()
                        || !content.startsWith(action.original(), action.startOffset())) {
                    throw new IllegalStateException(entry.getKey() + " changed since the fix was planned; re-run the analysis");
                }
                previousEnd = action.endOffset();
            }
            StringBuilder edited = new StringBuilder(content);
            for (int i = actions.size() - 1; i >= 0; i--) {
                FixAction action = actions.get(i);
                edited.replace(action.startOffset(), action.endOffset(), action.replacement());
            }
            rewritten.put(entry.getKey(), edited.toString());
        }
        for (Map.Entry<Path, String> entry : rewritten.entrySet()) {
            writeAtomically(entry.getKey(), entry.getValue());
        }
        return new FixResult(rewritten.size(), plan.actions().size());
    }

    // A crash between truncating and rewriting a source file would lose it; a sibling temp file
    // moved over the original is either fully written or not there at all.
    private static void writeAtomically(Path file, String content) {
        try {
            Path target = file.toRealPath();
            Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".prune");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                copyPermissions(target, temp);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private FixAction planFor(AnalysisIssue issue, Map<String, String> contents) {
        SourceLocation location = SourceLocation.parse(issue.location());
        Path file = projectRoot.resolve(location.path()).normalize();
        if (!file.startsWith(projectRoot) || !Files.isRegularFile(file) || !location.hasLine() || escapesRoot(file)) {
            return null;
        }
        String content = contents.computeIfAbsent(location.path(), key -> readUtf8(file));
        if (content == null) {
            return null;
        }
        return switch (issue.type()) {
            case UNUSED_METHOD, UNUSED_FIELD -> planMemberRemoval(issue, location, file, content);
            case UNUSED_VISIBILITY -> planVisibilityReduction(issue, location, file, content);
            case UNUSED_DEPENDENCY -> planDependencyRemoval(issue, location, file, content);
            case UNUSED_CLASS -> null;
        };
    }

    // createTempFile makes the file owner-only; the original's mode must survive the swap.
    private static void copyPermissions(Path from, Path to) throws IOException {
        try {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
        } catch (UnsupportedOperationException notPosix) {
            // Windows and other non-POSIX stores keep the default permissions of the new file.
        }
    }

    // A symlink inside the project may point anywhere; only files that really live under the
    // root are rewritten.
    private boolean escapesRoot(Path file) {
        try {
            return !file.toRealPath().startsWith(projectRoot.toRealPath());
        } catch (IOException unreadable) {
            return true;
        }
    }

    private static FixAction planMemberRemoval(AnalysisIssue issue, SourceLocation location, Path file, String content) {
        int hash = issue.symbol().lastIndexOf('#');
        if (hash < 0) {
            return null;
        }
        String memberName = issue.symbol().substring(hash + 1);
        JavaSourceFile source = JavaSourceParser.parse(location.path(), content);
        LineMap map = source.lineMap();
        MemberDeclaration member = findMember(source.types(), memberName, location, map);
        if (member == null || member.declaratorCount() != 1) {
            return null;
        }
        int[] span;
        if (LineEdits.aloneOnLines(content, map, member.start(), member.end())) {
            int leadStart = attachedCommentStart(content, map, member.start());
            span = LineEdits.deleteLines(content, map, map.lineOf(leadStart), map.lineOf(member.end() - 1), line -> false);
        } else {
            int end = member.end();
            if (end < content.length() && content.charAt(end) == ' ') {
                end++;
            }
            span = new int[]{member.start(), end};
        }
        String kind = issue.type() == IssueType.UNUSED_FIELD ? "field" : "method";
        return new FixAction(issue, file, span[0], span[1], content.substring(span[0], span[1]), "",
                "Remove unused private " + kind + " " + memberName);
    }

    private static MemberDeclaration findMember(List<TypeDeclaration> types, String name, SourceLocation location,
                                                LineMap map) {
        for (TypeDeclaration type : types) {
            for (MemberDeclaration member : type.members()) {
                if (member.name().equals(name) && map.lineOf(member.nameOffset()) == location.line()
                        && (!location.hasColumn() || map.columnOf(member.nameOffset()) == location.column())) {
                    return member;
                }
            }
            MemberDeclaration nested = findMember(type.nestedTypes(), name, location, map);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    // Comments directly above a member, each starting its own line with no blank line in between,
    // describe that member and leave with it.
    private static int attachedCommentStart(String content, LineMap map, int memberStart) {
        List<Token> tokens = JavaLexer.tokenize(content);
        int index = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).start() == memberStart) {
                index = i;
                break;
            }
        }
        int start = memberStart;
        for (int i = index - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.kind() != TokenKind.COMMENT) {
                break;
            }
            String gap = content.substring(token.end(), start);
            if (!gap.isBlank() || map.lineOf(start) - map.lineOf(token.end() - 1) != 1) {
                break;
            }
            if (!content.substring(map.startOf(map.lineOf(token.start())), token.start()).isBlank()) {
                break;
            }
            start = token.start();
        }
        return start;
    }

    private static FixAction planVisibilityReduction(AnalysisIssue issue, SourceLocation location, Path file,
                                                     String content) {
        String simpleName = issue.symbol().substring(issue.symbol().lastIndexOf('.') + 1);
        JavaSourceFile source = JavaSourceParser.parse(location.path(), content);
        for (TypeDeclaration type : source.types()) {
            if (!type.name().equals(simpleName) || type.publicModifierOffset() < 0
                    || source.lineMap().lineOf(type.nameOffset()) != location.line()) {
                continue;
            }
            int start = type.publicModifierOffset();
            int end = start + "public".length();
            if (!content.startsWith("public", start)) {
                return null;
            }
            while (end < content.length() && (content.charAt(end) == ' ' || content.charAt(end) == '\t')) {
                end++;
            }
            return new FixAction(issue, file, start, end, content.substring(start, end), "",
                    "Make class " + simpleName + " package-private");
        }
        return null;
    }

    private static FixAction planDependencyRemoval(AnalysisIssue issue, SourceLocation location, Path file,
                                                   String content) {
        String fileName = file.getFileName().toString();
        boolean maven = fileName.equals("pom.xml");
        List<DeclaredDependency> declared = maven
                ? MavenPomParser.parse(location.path(), content)
                : GradleBuildParser.parse(location.path(), content);
        LineMap map = new LineMap(content);
        for (DeclaredDependency dependency : declared) {
            if (!dependency.coordinates().equals(issue.symbol()) || !dependency.spans(location.line())) {
                continue;
            }
            String first = LineEdits.lineText(content, map, dependency.startLine()).strip();
            String last = LineEdits.lineText(content, map, dependency.endLine()).strip();
            if (maven && !(first.startsWith("<dependency>") && last.endsWith("</dependency>"))) {
                return null;
            }
            int firstLine = maven ? xmlCommentBlockStart(content, map, dependency.startLine()) : dependency.startLine();
            int[] span = LineEdits.deleteLines(content, map, firstLine, dependency.endLine(),
                    maven ? line -> false : line -> line.startsWith("//"));
            return new FixAction(issue, file, span[0], span[1], content.substring(span[0], span[1]), "",
                    "Remove unused dependency " + issue.symbol());
        }
        return null;
    }

    // XML comments directly above a dependency, single- or multi-line, describe it and go with it.
    private static int xmlCommentBlockStart(String content, LineMap map, int line) {
        int first = line;
        while (first > 1 && LineEdits.lineText(content, map, first - 1).strip().endsWith("-->")) {
            int open = first - 1;
            while (open > 0 && !LineEdits.lineText(content, map, open).strip().startsWith("<!--")) {
                open--;
            }
            if (open == 0) {
                break;
            }
            first = open;
        }
        return first;
    }

    private static String readUtf8(Path file) {
        try {
            ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(file));
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return null;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
