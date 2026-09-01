package fr.gocraft.api.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Every command one plugin declares.
///
/// It is the only thing the facades produce and the only thing the host is ever
/// given. §07 puts three ways of writing a command in front of an author — a
/// builder, annotations, a base class to extend — and lets none of them touch
/// the wire format: they all construct one of these, and a conformance test
/// asserts the three produce the same one.
///
/// Validation happens here rather than at registration, so it happens once,
/// at the moment the tree is finished, and the same rules answer at build time
/// and at load time. A tree that exists is a tree that is well formed.
public record CommandTree(List<CommandNode> commands) {

    public CommandTree {
        commands = List.copyOf(commands);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("a command tree declares no commands");
        }
        for (CommandNode node : commands) {
            if (!(node instanceof CommandNode.Literal)) {
                throw new IllegalArgumentException(
                        "a command tree may only hold literals at the top; " + node.name() + " is an argument");
            }
        }
        validate("/", commands);
    }

    public static CommandTree of(CommandNode... commands) {
        return new CommandTree(List.of(commands));
    }

    /// The executors this tree points at, in ascending order.
    ///
    /// Whoever registers the tree needs them to check that every node has an
    /// invoker: a node pointing at an executor nobody bound is a command that
    /// looks registered and does nothing when typed.
    public List<Integer> executors() {
        Set<Integer> unique = new HashSet<>();
        collectExecutors(commands, unique);
        List<Integer> ordered = new ArrayList<>(unique);
        ordered.sort(Integer::compare);
        return List.copyOf(ordered);
    }

    private static void collectExecutors(List<CommandNode> nodes, Set<Integer> into) {
        for (CommandNode node : nodes) {
            if (node.executor() != 0) {
                into.add(node.executor());
            }
            collectExecutors(node.children(), into);
        }
    }

    /// validate applies the rules that make a tree renderable and parsable.
    ///
    /// They are the ones the host applies when it opens a bundle. Stating them
    /// twice is the price of two languages; a conformance test is what keeps
    /// the two statements the same.
    private static void validate(String path, List<CommandNode> nodes) {
        Set<String> seen = new HashSet<>();
        for (CommandNode node : nodes) {
            String kind = node instanceof CommandNode.Literal ? "literal" : "argument";
            if (!seen.add(kind + ":" + node.name())) {
                throw new IllegalArgumentException(
                        "command tree " + path + ": duplicate " + kind + " " + Names.quoted(node.name()));
            }
            String reached = path.endsWith("/") ? path + node.name() : path + "/" + node.name();
            if (node.executor() == 0 && node.children().isEmpty()) {
                throw new IllegalArgumentException(
                        "command tree " + reached + ": nothing runs here and nothing follows");
            }
            validate(reached, node.children());
        }
    }
}
