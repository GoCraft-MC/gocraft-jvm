package fr.gocraft.apt;

import java.util.LinkedHashMap;
import java.util.Map;

/// The command being assembled, while it is still being assembled.
///
/// One class declares many methods and they share prefixes: `sell <price>` and
/// `admin reload` are two paths through one /shop. Merging them is what turns a
/// list of annotated methods into a tree, and it is the only place two methods
/// can be found to disagree — about a permission, or about what `<price>` is.
final class Tree {

    final String name;
    final boolean argument;

    /// The ArgType expression, for an argument. Null for a literal.
    final String type;

    /// The same type described without Java, for the bundle build.
    final String json;

    String permission = "";

    /// The call the invoker makes. Null when nothing runs here.
    String invoke;

    boolean greedy;

    /// Insertion order, so generated code reads in the order the author wrote
    /// their methods rather than in whatever order a hash produced.
    final Map<String, Tree> children = new LinkedHashMap<>();

    Tree(String name, boolean argument, String type, String json) {
        this.name = name;
        this.argument = argument;
        this.type = type;
        this.json = json;
    }

    static Tree literal(String name) {
        return new Tree(name, false, null, null);
    }

    /// child finds or creates one step, and reports a step two methods spelled
    /// differently.
    ///
    /// Keyed by kind and name together, because a literal and an argument may
    /// share a level — /warp home and /warp <target> — and only a repeat within
    /// one kind is a conflict.
    Tree child(String name, boolean argument, String type, String json) {
        String key = (argument ? "<" : "") + name;
        Tree existing = children.get(key);
        if (existing == null) {
            Tree created = new Tree(name, argument, type, json);
            children.put(key, created);
            return created;
        }
        if (argument && !existing.type.equals(type)) {
            throw new Conflict("argument <" + name + "> is declared as " + existing.type
                    + " on one path and " + type + " on another");
        }
        return existing;
    }

    void runs(String call) {
        if (invoke != null) {
            throw new Conflict("two methods answer the same path");
        }
        invoke = call;
    }

    void guard(String node) {
        if (node == null || node.isBlank()) {
            return;
        }
        if (!permission.isEmpty() && !permission.equals(node)) {
            throw new Conflict("guarded by " + permission + " on one path and " + node + " on another");
        }
        permission = node;
    }

    /// Conflict is what one method says that another already contradicted. It
    /// carries only the sentence: the processor knows which element to hang it
    /// on, and this class does not.
    static final class Conflict extends RuntimeException {
        Conflict(String message) {
            super(message);
        }
    }
}
