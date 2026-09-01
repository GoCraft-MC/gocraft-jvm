package fr.gocraft.api.command;

import fr.gocraft.api.CommandHandler;
import java.util.ArrayList;
import java.util.List;

/// A command declared by extending something.
///
/// The weakest of the three facades, and supported rather than promoted. It
/// exists because a codebase being moved off Bukkit is full of classes that
/// extend a command base, and rewriting all of them before anything runs is how
/// a migration stalls. It costs almost nothing: it accumulates into the builder
/// and calls it at registration, so it inherits every rule the builder enforces
/// and adds no second description of what a command is.
///
///     public final class ShopCommands extends SubCommand {
///         public ShopCommands(ShopStore store) {
///             super("shop");
///             permission("shop.use");
///             add(Command.literal("sell")
///                 .then(Command.arg("price", ArgType.decimal(0.01, 1e6))
///                     .executes(context -> sell(store, context.decimal("price")))));
///         }
///     }
///
/// Prefer the annotations for new code: they are checked at compile time and
/// the tree reaches the bundle without this class ever being constructed.
public abstract class SubCommand {

    private final String name;
    private final List<Command.Builder<?>> branches = new ArrayList<>();
    private String permission = "";
    private CommandHandler handler;
    private boolean built;

    protected SubCommand(String name) {
        this.name = Names.checked(name, "literal");
    }

    /// The node a sender must hold to see this command at all.
    protected final void permission(String node) {
        this.permission = node == null ? "" : node.trim();
    }

    /// Adds one branch below the command.
    protected final void add(Command.Builder<?> branch) {
        if (built) {
            throw new IllegalStateException(name + " was already built; its tree cannot change");
        }
        if (branch == null) {
            throw new IllegalArgumentException("a command branch cannot be null");
        }
        branches.add(branch);
    }

    /// What runs when the command is typed with nothing after it.
    protected final void executes(CommandHandler handler) {
        if (built) {
            throw new IllegalStateException(name + " was already built; its tree cannot change");
        }
        if (this.handler != null) {
            throw new IllegalArgumentException(name + " already runs something on its own");
        }
        this.handler = handler;
    }

    /// Finishes what the constructor accumulated.
    ///
    /// Once, because the ids inside a set are assigned as it is built and two
    /// sets from one instance would hand the same handler two identities — with
    /// only one of them ever reached.
    public final CommandSet build() {
        if (built) {
            throw new IllegalStateException(name + " was already built");
        }
        built = true;
        Command.Literal root = Command.literal(name);
        if (!permission.isEmpty()) {
            root.permission(permission);
        }
        if (handler != null) {
            root.executes(handler);
        }
        for (Command.Builder<?> branch : branches) {
            root.then(branch);
        }
        return Command.tree(root);
    }
}
