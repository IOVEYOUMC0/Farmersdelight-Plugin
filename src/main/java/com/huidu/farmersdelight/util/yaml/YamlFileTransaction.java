package com.huidu.farmersdelight.util.yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * One YAML file's edits, applied to the text itself.
 *
 *
 * The entry an edit names is located by indentation, replaced or removed in place, and the result is written
 * through a temporary file that replaces the target atomically. Because everything outside the edited entry is
 * copied through unchanged, an edit keeps the rest of the file exactly as it was: operator comments, blank
 * lines, key order and indentation all survive, which is what re-serialising the whole parsed document loses.
 *
 *
 * A file whose shape the locator cannot follow is refused instead of guessed at, as is a target that would come
 * out different from what the edit asked for. A refused or failed edit leaves the target byte for byte as it
 * was: the original is only replaced once the new text exists in full.
 */
public final class YamlFileTransaction {

    /** One edit on one YAML path. The path is a list of keys, never a dotted string, so ids may contain dots. */
    public sealed interface Edit permits SetValue, RemoveValue {
    }

    /** Writes the value at the path, creating the entry and its missing parents when they are absent. */
    public record SetValue(List<String> path, Object value) implements Edit {

        public SetValue {
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            if (path.isEmpty()) {
                throw new IllegalArgumentException("An edit needs a key to write");
            }
        }
    }

    /** Removes the entry at the path, together with a comment block that belongs to it alone. */
    public record RemoveValue(List<String> path) implements Edit {

        public RemoveValue {
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            if (path.isEmpty()) {
                throw new IllegalArgumentException("An edit needs a key to remove");
            }
        }
    }

    /** Renders one block for the value; the transaction supplies the key, the indentation and the line ending. */
    public interface ValueWriter {

        String block(String key, Object value, int depth);
    }

    /** Checks the text an edit produced before it replaces the file; null means the text is good to write. */
    public interface Check {

        String problem(String text);
    }

    /** Whether the file was replaced, and why it was not. */
    public record Outcome(boolean written, String reason) {

        public static Outcome ok() {
            return new Outcome(true, null);
        }

        public static Outcome refused(String reason) {
            return new Outcome(false, reason);
        }
    }

    /** Two edits of one file never overlap: the whole read-edit-check-write runs under this file's monitor. */
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_.\\-]+");
    private static final Pattern BLOCK_SCALAR = Pattern.compile("[|>][+\\-0-9]*");
    private static final Pattern DOCUMENT_MARKER = Pattern.compile("(---|\\.\\.\\.)\\s*(#.*)?");

    private YamlFileTransaction() {
    }

    /** Applies every edit in order, then writes the result atomically. A null check skips the verification. */
    public static Outcome apply(Path file, List<Edit> edits, ValueWriter writer, Check check) {
        if (file == null || edits == null || edits.isEmpty() || writer == null) {
            return Outcome.refused("nothing to write");
        }
        Path target = file.toAbsolutePath().normalize();
        Object lock = LOCKS.computeIfAbsent(target, ignored -> new Object());
        synchronized (lock) {
            try {
                String original = Files.isRegularFile(target)
                        ? Files.readString(target, StandardCharsets.UTF_8) : "";
                String eol = eolOf(original);
                String unsupported = unsupportedShape(original);
                if (unsupported != null) {
                    return Outcome.refused(unsupported);
                }
                String produced = original;
                for (Edit edit : edits) {
                    produced = applyEdit(produced, edit, writer, eol);
                }
                if (check != null) {
                    String problem = check.problem(produced);
                    if (problem != null) {
                        return Outcome.refused(problem);
                    }
                }
                if (produced.equals(original)) {
                    // The file already says exactly this, so a repeated edit writes nothing at all.
                    return Outcome.ok();
                }
                writeAtomically(target, produced);
                return Outcome.ok();
            } catch (Refused refused) {
                return Outcome.refused(refused.getMessage());
            } catch (IOException | RuntimeException failure) {
                String reason = failure.getMessage();
                return Outcome.refused(reason == null ? failure.getClass().getSimpleName() : reason);
            }
        }
    }

    private static String applyEdit(String text, Edit edit, ValueWriter writer, String eol) {
        if (edit instanceof RemoveValue remove) {
            return removeEntry(text, remove.path());
        }
        SetValue set = (SetValue) edit;
        List<String> path = set.path();
        String key = path.get(path.size() - 1);
        List<String> parents = path.subList(0, path.size() - 1);
        String prepared = ensureParents(text, parents, eol);
        List<String> lines = splitLines(prepared);
        int[] parentRange = parents.isEmpty() ? fileRange(lines) : locate(lines, parents);
        if (parentRange == null) {
            throw new Refused("no section to write " + key + " into");
        }
        if (!parents.isEmpty() && !isBlockKey(lines.get(parentRange[0]))) {
            throw new Refused("section " + String.join(".", parents) + " is not written as a block");
        }
        String block = writer.block(key, set.value(), (parentRange[2] + 2));
        if (block == null || block.isBlank()) {
            throw new Refused("nothing was written for " + key);
        }
        block = block.replace("\r\n", "\n").replace("\n", eol);
        if (!block.endsWith(eol)) {
            block = block + eol;
        }
        int[] existing = locateIn(lines, parentRange, key);
        if (existing == null) {
            return spliceIn(lines, parentRange[1], block, eol);
        }
        if (!isBlockKey(lines.get(existing[0]))) {
            throw new Refused(key + " is not written as a block");
        }
        return splice(lines, existing[0], existing[1], block);
    }

    private static String removeEntry(String text, List<String> path) {
        List<String> lines = splitLines(text);
        int[] found = locate(lines, path);
        if (found == null) {
            return text;
        }
        int depth = found[2];
        int from = found[0];
        // A comment block directly above the entry belongs to it only when no other entry of the same section
        // sits above it and none follows: anything else makes it a heading for the section, which stays.
        while (from - 1 >= 0 && isComment(lines.get(from - 1)) && indentOf(lines.get(from - 1)) == depth) {
            from--;
        }
        if (from != found[0] && commentsShared(lines, from, found[1], depth)) {
            from = found[0];
        }
        return splice(lines, from, found[1], "");
    }

    private static boolean commentsShared(List<String> lines, int commentFrom, int entryEnd, int depth) {
        for (int index = entryEnd; index < lines.size(); index++) {
            String key = keyOf(lines.get(index));
            if (key == null) {
                continue;
            }
            if (indentOf(lines.get(index)) <= depth) {
                return true;
            }
        }
        int above = commentFrom - 1;
        while (above >= 0 && lines.get(above).isBlank()) {
            above--;
        }
        if (above < 0) {
            return false;
        }
        return keyOf(lines.get(above)) != null && indentOf(lines.get(above)) >= depth;
    }

    /** Creates the section path when it is absent, appending each missing key at the end of its parent. */
    private static String ensureParents(String text, List<String> parents, String eol) {
        String current = text;
        for (int size = 1; size <= parents.size(); size++) {
            List<String> prefix = parents.subList(0, size);
            List<String> lines = splitLines(current);
            if (locate(lines, prefix) != null) {
                continue;
            }
            List<String> above = prefix.subList(0, size - 1);
            String key = prefix.get(size - 1);
            int[] range = above.isEmpty() ? fileRange(lines) : locate(lines, above);
            if (range == null) {
                throw new Refused("no section to write " + String.join(".", above) + " into");
            }
            if (!above.isEmpty() && !isBlockKey(lines.get(range[0]))) {
                throw new Refused("section " + String.join(".", above) + " is not written as a block");
            }
            current = spliceIn(lines, range[1], indent(range[2] + 2) + key + ":" + eol, eol);
        }
        return current;
    }

    private static String unsupportedShape(String text) {
        for (String line : splitLines(text)) {
            String body = line.strip();
            if (body.isEmpty() || body.startsWith("#")) {
                continue;
            }
            if (indentOf(line) == 0 && DOCUMENT_MARKER.matcher(body).matches()) {
                return "the file holds more than one YAML document";
            }
            String key = keyOf(line);
            String value = key == null ? null : valueOf(line);
            if (value != null && BLOCK_SCALAR.matcher(value).matches()) {
                return "the file uses a block scalar";
            }
        }
        return null;
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
    }

    // ---------------------------------------------------------------- text handling

    private static String splice(List<String> lines, int from, int to, String replacement) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < from; index++) {
            out.append(lines.get(index));
        }
        out.append(replacement);
        for (int index = to; index < lines.size(); index++) {
            out.append(lines.get(index));
        }
        return out.toString();
    }

    /** Inserts a block at a line boundary, ending the previous line first when it had no line ending. */
    private static String spliceIn(List<String> lines, int at, String block, String eol) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < at; index++) {
            out.append(lines.get(index));
        }
        if (at > 0 && !endsWithLineBreak(lines.get(at - 1))) {
            out.append(eol);
        }
        out.append(block);
        for (int index = at; index < lines.size(); index++) {
            out.append(lines.get(index));
        }
        return out.toString();
    }

    private static int[] locate(List<String> lines, List<String> path) {
        int parentIndent = -2;
        int searchFrom = 0;
        int[] last = null;
        for (String name : path) {
            int depth = parentIndent + 2;
            int start = -1;
            for (int index = searchFrom; index < lines.size(); index++) {
                String key = keyOf(lines.get(index));
                if (key == null) {
                    continue;
                }
                int indent = indentOf(lines.get(index));
                if (indent <= parentIndent) {
                    break;
                }
                if (indent == depth && key.equals(name)) {
                    start = index;
                    break;
                }
            }
            if (start < 0) {
                return null;
            }
            last = new int[]{start, blockEnd(lines, start, depth), depth};
            searchFrom = start + 1;
            parentIndent = depth;
        }
        return last;
    }

    private static int[] locateIn(List<String> lines, int[] range, String key) {
        int depth = range[2] + 2;
        for (int index = range[0] + 1; index < range[1]; index++) {
            String found = keyOf(lines.get(index));
            if (found != null && found.equals(key) && indentOf(lines.get(index)) == depth) {
                return new int[]{index, blockEnd(lines, index, depth), depth};
            }
        }
        return null;
    }

    private static int blockEnd(List<String> lines, int start, int depth) {
        int end = lines.size();
        for (int index = start + 1; index < lines.size(); index++) {
            if (keyOf(lines.get(index)) == null) {
                continue;
            }
            if (indentOf(lines.get(index)) <= depth) {
                end = index;
                break;
            }
        }
        // A comment block directly above the next key is a heading for that key, not part of this entry: an
        // edit of this entry must not swallow it, and removing this entry must not take it along.
        int cut = end;
        while (cut - 1 > start && isComment(lines.get(cut - 1)) && indentOf(lines.get(cut - 1)) == depth) {
            cut--;
        }
        return cut;
    }

    private static int[] fileRange(List<String> lines) {
        return new int[]{0, lines.size(), -2};
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines.add(text.substring(start, index + 1));
                start = index + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static String eolOf(String text) {
        int newline = text.indexOf('\n');
        if (newline > 0 && text.charAt(newline - 1) == '\r') {
            return "\r\n";
        }
        return "\n";
    }

    private static boolean endsWithLineBreak(String line) {
        return line.endsWith("\n");
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    private static boolean isComment(String line) {
        return line.stripLeading().startsWith("#");
    }

    /** The key of a block mapping line, or null for blank lines, comments, sequence entries and inline values. */
    private static String keyOf(String line) {
        String body = line.strip();
        if (body.isEmpty() || body.startsWith("#") || body.startsWith("-")) {
            return null;
        }
        int colon = body.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String key = body.substring(0, colon).strip();
        return PLAIN_KEY.matcher(key).matches() ? key : null;
    }

    /** The inline value of a key line, with a trailing comment removed, or null when the key opens a block. */
    private static String valueOf(String line) {
        String body = line.strip();
        if (body.isEmpty() || body.startsWith("#") || body.startsWith("-")) {
            return null;
        }
        int colon = body.indexOf(':');
        if (colon <= 0 || !PLAIN_KEY.matcher(body.substring(0, colon).strip()).matches()) {
            return null;
        }
        String value = body.substring(colon + 1).strip();
        if (value.startsWith("#")) {
            // The line carries only a comment after the colon, so the key opens a block.
            return "";
        }
        int comment = value.indexOf(" #");
        if (comment >= 0) {
            value = value.substring(0, comment).strip();
        }
        return value;
    }

    private static boolean isBlockKey(String line) {
        return keyOf(line) != null && "".equals(valueOf(line));
    }

    private static String indent(int depth) {
        return " ".repeat(Math.max(0, depth));
    }

    /** Thrown for a file the locator refuses to touch; its message is the reason the caller reports. */
    private static final class Refused extends RuntimeException {

        private Refused(String reason) {
            super(reason);
        }
    }
}
