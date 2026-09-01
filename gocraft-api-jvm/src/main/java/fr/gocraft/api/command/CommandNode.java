package fr.gocraft.api.command;

import java.util.List;

/// One node of a command tree.
///
/// Sealed, so every renderer sees the complete set. That matters more here than
/// anywhere else in the API: the tree is read by the host, by the Java client's
/// Brigadier graph and by Bedrock's flat signatures, and a node kind one of
/// them had never heard of would be silently dropped from a command list.
///
/// A node is data. The code is the invoker the executor points at, and it never
/// crosses the process boundary — which is the whole reason the two are
/// separated (§07).
public sealed interface CommandNode {

    /// The word a player types, or the name an argument is read back under.
    String name();

    /// Zero when nothing runs here. A node may both run and have children: /kill
    /// runs alone and /kill <player> runs on someone else, and that is how an
    /// optional argument is spelled.
    int executor();

    List<CommandNode> children();

    /// A fixed word.
    ///
    /// Only a literal carries a permission, because only a literal is a place a
    /// player can be refused: an argument is a value, and refusing a value is
    /// the handler's business.
    record Literal(String name, String permission, int executor, List<CommandNode> children)
            implements CommandNode {

        public Literal {
            name = Names.checked(name, "literal");
            permission = permission == null ? "" : permission.trim();
            children = List.copyOf(children);
            if (executor < 0) {
                throw new IllegalArgumentException("literal " + name + " has a negative executor");
            }
        }

        public Literal(String name) {
            this(name, "", 0, List.of());
        }
    }

    /// A value the host parses before the handler runs.
    record Argument(String name, ArgType type, int executor, List<CommandNode> children)
            implements CommandNode {

        public Argument {
            name = Names.checked(name, "argument");
            if (type == null) {
                throw new IllegalArgumentException("argument " + name + " has no type");
            }
            children = List.copyOf(children);
            if (executor < 0) {
                throw new IllegalArgumentException("argument " + name + " has a negative executor");
            }
            if (type.kind() == ArgType.Kind.GREEDY && !children.isEmpty()) {
                throw new IllegalArgumentException(
                        "greedy argument " + name + " must be last; it consumes the rest of the line");
            }
        }

        public Argument(String name, ArgType type) {
            this(name, type, 0, List.of());
        }
    }
}
