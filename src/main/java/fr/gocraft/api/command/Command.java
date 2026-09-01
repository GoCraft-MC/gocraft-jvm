package fr.gocraft.api.command;

import fr.gocraft.api.CommandHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The canonical way to declare a command.
///
///     Command.literal("shop")
///         .permission("shop.use")
///         .then(Command.literal("sell")
///             .then(Command.arg("price", ArgType.decimal(0.01, 1e6))
///                 .executes(ctx -> sell(ctx.sender(), ctx.decimal("price")))));
///
/// Canonical in the sense §07 means it: the other facades are constructors of
/// the same tree, and they reach it through this one. An annotation processor
/// compiles to these calls; a base class accumulates into them and builds at
/// registration. None of them touches the wire format, so a fourth way of
/// writing a command in two years costs a facade and nothing else.
///
/// Executor ids are assigned here, walking the tree in declaration order. An
/// author never writes one: an id is an artefact of the tree, and naming one in
/// plugin source would be a second place it is written down.
public final class Command {

    private Command() {
    }

    /// A fixed word: the command's name, or a branch of it.
    public static Literal literal(String name) {
        return new Literal(name);
    }

    /// A value the host parses before the handler runs.
    public static Argument arg(String name, ArgType type) {
        return new Argument(name, type);
    }

    /// Finishes one or more commands into the pair a host is given.
    public static CommandSet tree(Literal... commands) {
        if (commands.length == 0) {
            throw new IllegalArgumentException("a command tree declares no commands");
        }
        Assembler assembler = new Assembler();
        List<CommandNode> nodes = new ArrayList<>(commands.length);
        for (Literal command : commands) {
            nodes.add(command.assemble(assembler));
        }
        return new CommandSet(new CommandTree(nodes), assembler.invokers);
    }

    /// Assembler hands out executor ids and collects what each one runs.
    ///
    /// One per tree rather than one per builder, so two commands in the same
    /// set cannot be given the same id — which would have one of them silently
    /// running the other's handler.
    private static final class Assembler {
        private final Map<Integer, CommandHandler> invokers = new HashMap<>();
        private int next;

        private int bind(CommandHandler handler) {
            if (handler == null) {
                return 0;
            }
            invokers.put(++next, handler);
            return next;
        }
    }

    /// Builder is what the two node kinds share: children, and something to run.
    public abstract static sealed class Builder<SELF extends Builder<SELF>> permits Literal, Argument {
        final List<Builder<?>> children = new ArrayList<>();
        CommandHandler handler;

        @SuppressWarnings("unchecked")
        private SELF self() {
            return (SELF) this;
        }

        /// Adds a branch below this node.
        public SELF then(Builder<?> child) {
            if (child == null) {
                throw new IllegalArgumentException("a command branch cannot be null");
            }
            children.add(child);
            return self();
        }

        /// What runs when a line stops here.
        ///
        /// A node may run and still have branches: /kill runs alone and /kill
        /// <player> runs on someone else.
        public SELF executes(CommandHandler handler) {
            if (handler == null) {
                throw new IllegalArgumentException("a command executor cannot be null");
            }
            if (this.handler != null) {
                throw new IllegalArgumentException("this node already runs something");
            }
            this.handler = handler;
            return self();
        }

        abstract CommandNode assemble(Assembler assembler);

        final List<CommandNode> assembleChildren(Assembler assembler) {
            List<CommandNode> nodes = new ArrayList<>(children.size());
            for (Builder<?> child : children) {
                nodes.add(child.assemble(assembler));
            }
            return nodes;
        }
    }

    public static final class Literal extends Builder<Literal> {
        private final String name;
        private String permission = "";

        private Literal(String name) {
            this.name = name;
        }

        /// The node a sender must hold to see this branch at all.
        ///
        /// Guarding a literal rather than an argument is deliberate: a literal
        /// is a place a player can be refused, and the host prunes it out of
        /// the command list it sends them. An argument is a value, and refusing
        /// a value is the handler's business.
        public Literal permission(String node) {
            this.permission = node == null ? "" : node.trim();
            return this;
        }

        @Override
        CommandNode assemble(Assembler assembler) {
            // Children first, so ids run in declaration order down the tree
            // rather than in the order the assembler happened to be asked.
            int executor = assembler.bind(handler);
            return new CommandNode.Literal(name, permission, executor, assembleChildren(assembler));
        }
    }

    public static final class Argument extends Builder<Argument> {
        private final String name;
        private final ArgType type;

        private Argument(String name, ArgType type) {
            this.name = name;
            this.type = type;
        }

        @Override
        CommandNode assemble(Assembler assembler) {
            int executor = assembler.bind(handler);
            return new CommandNode.Argument(name, type, executor, assembleChildren(assembler));
        }
    }
}
