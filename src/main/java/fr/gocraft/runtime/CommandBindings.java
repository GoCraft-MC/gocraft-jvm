package fr.gocraft.runtime;

import fr.gocraft.abi.v1.CommandNode;
import fr.gocraft.abi.v1.CommandNodeKind;
import fr.gocraft.abi.v1.CommandTree;
import fr.gocraft.api.CommandHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The invokers one plugin registered, by executor id.
///
/// §07 splits a command in two: the tree is data, serialised into the bundle so
/// the host knows every command before this JVM starts, and the executor is
/// code, which cannot cross a process boundary. This is the map that stays on
/// this side — `Map<NodeId, Invoker>`, never serialised.
///
/// A plugin registers against the **path** through the tree — "shop sell
/// <price>", arguments in angle brackets as §07 writes them — and this resolves
/// that to the executor id the tree assigned. Ids belong to
/// whatever built the bundle; a plugin naming one would be a second place they
/// are written down, free to disagree with the first the day the tree is
/// rebuilt.
///
/// **This holds lambdas defined by plugin code**, which retain their declaring
/// class and therefore the plugin's classloader. It is one of the references
/// §13 names: left alive after unload it leaks every class the plugin defined.
/// [#clear()] exists for that, and LoadedPlugin calls it.
final class CommandBindings {

    /// Path through the tree, lowercased and space separated, to the executor
    /// the tree gave it. Empty when the plugin declares no commands.
    private final Map<String, Integer> executors;
    private final Map<Integer, CommandHandler> handlers = new LinkedHashMap<>();

    private CommandBindings(Map<String, Integer> executors) {
        this.executors = executors;
    }

    /// A plugin with no command tree. Registering anything is then an error
    /// with a reason, rather than a handler that silently never runs.
    static CommandBindings none() {
        return new CommandBindings(Map.of());
    }

    /// Indexes a serialised tree by path.
    ///
    /// The tree is read, not validated: the host validated it when it scanned
    /// the bundle and refused to load anything that failed. Re-deciding here
    /// would be a second validator free to disagree with the first.
    static CommandBindings of(CommandTree tree) {
        Map<String, Integer> executors = new LinkedHashMap<>();
        for (CommandNode child : tree.getChildrenList()) {
            index(child, "", executors);
        }
        return new CommandBindings(executors);
    }

    private static void index(CommandNode node, String prefix, Map<String, Integer> executors) {
        // An argument contributes to the path in angle brackets, the way §07
        // writes it — "shop sell <price>". It has to contribute something: the
        // executor sits on the node that runs, so "home set <name>" and
        // "home set" are two different commands and a handler binds to one of
        // them, not to their parent. The brackets are what keeps an argument
        // named "sell" from colliding with a literal of the same name.
        String name = node.getKind() == CommandNodeKind.COMMAND_NODE_KIND_ARGUMENT
                ? "<" + node.getName() + ">"
                : node.getName();
        String path = prefix.isEmpty() ? name : prefix + " " + name;
        if (node.getExecutor() != 0) {
            executors.put(normalise(path), node.getExecutor());
        }
        for (CommandNode child : node.getChildrenList()) {
            index(child, path, executors);
        }
    }

    /// Binds a handler to a path.
    ///
    /// Refusing an unknown path rather than accepting it: a handler registered
    /// against a path the tree does not contain would never run, and a command
    /// that silently does nothing is the failure that costs an afternoon. The
    /// message lists what the tree does offer, because the mistake is almost
    /// always a typo or a stale bundle.
    void register(String path, CommandHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("a command handler cannot be null");
        }
        String normalised = normalise(path == null ? "" : path);
        Integer executor = executors.get(normalised);
        if (executor == null) {
            throw new IllegalArgumentException("no command \"" + normalised
                    + "\" in this plugin's tree; it declares " + known());
        }
        if (handlers.putIfAbsent(executor, handler) != null) {
            throw new IllegalArgumentException("\"" + normalised
                    + "\" already has a handler; the second would never run");
        }
    }

    /// The handler for one executor, or null when the plugin registered none.
    CommandHandler handler(int executor) {
        return handlers.get(executor);
    }

    /// The paths this plugin declared but never bound.
    ///
    /// Reported once at load rather than discovered by a player typing one: the
    /// bundle promised a command the plugin does not implement, and the host
    /// has already told every client it exists.
    List<String> unbound() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : executors.entrySet()) {
            if (!handlers.containsKey(entry.getValue())) {
                missing.add(entry.getKey());
            }
        }
        return missing;
    }

    boolean isEmpty() {
        return executors.isEmpty();
    }

    int size() {
        return handlers.size();
    }

    private String known() {
        return executors.isEmpty() ? "no commands at all" : String.join(", ", executors.keySet());
    }

    /// Case and spacing are not the contract. "Shop  Sell" and "shop sell" are
    /// the same command to anyone typing it, so they are the same key here.
    private static String normalise(String path) {
        return path.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    /// Drops every reference to plugin code.
    ///
    /// A lambda retains the class that declared it, and that retains the
    /// classloader. Without this, unloading would release the plugin's jars and
    /// keep every class in them alive.
    void clear() {
        handlers.clear();
    }
}