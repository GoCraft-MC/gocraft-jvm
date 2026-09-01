package fr.gocraft.api.command;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/// How a node is named by the path that reaches it.
///
/// One definition, because the spelling is an agreement between two things that
/// never see each other: a plugin binds a handler to "shop sell <price>", and
/// the runtime resolves that against a tree the bundle shipped. Written twice,
/// the two would agree until the day one of them decided an argument should not
/// be in brackets — and the symptom would be a command that silently never
/// runs, which is the failure §07 spends its whole design avoiding.
///
/// Angle brackets are not decoration. The executor sits on the node that runs,
/// so "home set <name>" and "home set" are different commands; and they keep an
/// argument called sell from colliding with a literal of the same name.
public final class CommandPaths {

    private CommandPaths() {
    }

    /// The one step a node contributes.
    public static String segment(String name, boolean argument) {
        return argument ? "<" + name + ">" : name;
    }

    public static String join(String prefix, String segment) {
        return prefix.isEmpty() ? segment : prefix + " " + segment;
    }

    /// Case and spacing are not the contract. "Shop  Sell" and "shop sell" are
    /// the same command to anyone typing it, so they are the same key here.
    public static String normalise(String path) {
        return path.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /// Every runnable path in a tree, to the executor it reaches.
    ///
    /// Insertion order, so a message listing what a plugin declares reads in
    /// the order the author wrote it.
    public static Map<String, Integer> of(CommandTree tree) {
        Map<String, Integer> paths = new LinkedHashMap<>();
        for (CommandNode node : tree.commands()) {
            index(node, "", paths);
        }
        return paths;
    }

    private static void index(CommandNode node, String prefix, Map<String, Integer> paths) {
        String path = join(prefix, segment(node.name(), node instanceof CommandNode.Argument));
        if (node.executor() != 0) {
            paths.put(normalise(path), node.executor());
        }
        for (CommandNode child : node.children()) {
            index(child, path, paths);
        }
    }
}
