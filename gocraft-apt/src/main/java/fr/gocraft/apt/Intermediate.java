package fr.gocraft.apt;

import java.util.List;

/// The command trees, written where the bundle build can read them.
///
/// §15 gives the processor two jobs and this is the second: it extracts what
/// only javac can see, and hands it to `gocraft build`, which is the one
/// implementation of the bundle format. Without this half the annotations would
/// describe commands that never reach the manifest, and the host would learn
/// about them only once the JVM had started — losing the property §07 is built
/// on, that the tree is known before a plugin runs.
///
/// JSON, hand-written, because the alternative is a protobuf runtime on the
/// processor path and this file is a handful of fields. It is read by exactly
/// one program, in the same build.
///
/// **No executor ids.** They are minted by whatever writes the wire tree, once,
/// and the generated builder mints its own at runtime. Putting them here would
/// be a third set to keep in step with the other two — and paths, not ids, are
/// what the two sides agree on.
final class Intermediate {

    static final String PATH = "gocraft/commands.json";

    private final StringBuilder out = new StringBuilder();

    String render(List<Tree> commands) {
        out.append("{\n");
        out.append("  \"version\": 1,\n");
        out.append("  \"commands\": [\n");
        for (int index = 0; index < commands.size(); index++) {
            node(2, commands.get(index), index + 1 == commands.size());
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private void node(int depth, Tree tree, boolean last) {
        String pad = "  ".repeat(depth);
        out.append(pad).append("{\n");
        field(depth + 1, "name", tree.name);
        if (tree.argument) {
            out.append(pad).append("  \"argument\": true,\n");
            out.append(pad).append("  ").append(tree.json).append(",\n");
        }
        if (!tree.permission.isEmpty()) {
            field(depth + 1, "permission", tree.permission);
        }
        if (tree.invoke != null) {
            out.append(pad).append("  \"runs\": true,\n");
        }
        out.append(pad).append("  \"children\": [");
        if (tree.children.isEmpty()) {
            out.append("]\n");
        } else {
            out.append('\n');
            int remaining = tree.children.size();
            for (Tree child : tree.children.values()) {
                node(depth + 2, child, --remaining == 0);
            }
            out.append(pad).append("  ]\n");
        }
        out.append(pad).append('}').append(last ? "" : ",").append('\n');
    }

    private void field(int depth, String name, String value) {
        out.append("  ".repeat(depth)).append(quote(name)).append(": ").append(quote(value)).append(",\n");
    }

    /// Escapes what a command name or a permission node can legally hold.
    ///
    /// Names are checked before they get here — no whitespace, no slash — so
    /// the only characters left to worry about are the ones an ArgType
    /// expression carries, and a backslash or a quote in one of those would
    /// otherwise produce JSON nothing can read.
    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
