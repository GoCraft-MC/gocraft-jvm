package fr.gocraft.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// One command invocation, as the handler sees it.
///
/// The arguments are already parsed. The host walked the command tree, matched
/// the line against it, and resolved every argument to a value of the type the
/// tree declared, so a handler reads `ctx.text("name")` and never a raw string.
/// That is the point of the tree being data: the parsing happens once, in the
/// host, for every runtime and every edition.
///
/// **A sender is waiting.** Unlike an event this does not hold the tick, but
/// somebody typed a line and is looking at a chat prompt, so work that takes
/// seconds belongs on a thread of your own with a reply that says so.
///
/// One context is handed to one handler and is not thread-safe. Do not keep it:
/// the values are a snapshot taken when the line was typed.
public final class CommandContext {

    /// One side effect a handler asked for, carried back with the answer.
    public record Effect(String call, List<Value> values) {
        public Effect {
            values = List.copyOf(values);
        }
    }

    private final CommandSender sender;
    private final Map<String, Argument> arguments;
    private final List<Effect> effects = new ArrayList<>();

    /// One parsed argument: the value and what the host parsed it as.
    ///
    /// The type is kept because a value carries several readings at once — an
    /// [Value.Int] is an integer, a duration in milliseconds or nothing at all
    /// depending on what the tree declared — and reading the wrong one gives a
    /// number rather than a failure.
    public record Argument(Type type, Value value) {

        /// The closed set from §07. A tree cannot declare anything else, so a
        /// handler that covers these covers every argument that can arrive.
        public enum Type {
            INTEGER,
            DECIMAL,
            STRING,
            GREEDY,
            PLAYER,
            BLOCK_POS,
            BLOCK_STATE,
            ITEM,
            DURATION,
            ENUM,
            CUSTOM,
            /// An argument type this build was written before. Reading it gives
            /// the empty value for whatever you ask for, rather than a guess.
            UNKNOWN
        }
    }

    public CommandContext(CommandSender sender, Map<String, Argument> arguments) {
        this.sender = sender;
        this.arguments = new LinkedHashMap<>(arguments);
    }

    public CommandSender sender() {
        return sender;
    }

    /// Whether an argument was supplied. Optional arguments are absent rather
    /// than empty, so this is how to tell "not given" from "given as nothing".
    public boolean has(String name) {
        return arguments.containsKey(name);
    }

    /// The argument names this invocation carried, in the order the host sent
    /// them. For diagnostics; a handler knows its own arguments by name.
    public List<String> names() {
        return List.copyOf(arguments.keySet());
    }

    // ── Reading arguments ─────────────────────────────────────────────────────
    //
    // Each accessor names the types it accepts and returns an empty value for
    // anything else, matching how a generated event reads its fields. A handler
    // that asks for the wrong type has a bug the tree already disagreed with,
    // and throwing here would turn it into a failed command rather than a
    // wrong one — no easier to diagnose, and worse for whoever typed it.

    /// A string, greedy string, enum choice or custom argument.
    public String text(String name) {
        Argument argument = typed(name, Argument.Type.STRING, Argument.Type.GREEDY,
                Argument.Type.ENUM, Argument.Type.CUSTOM);
        return argument != null && argument.value() instanceof Value.Text(String value)
                ? value : "";
    }

    public long number(String name) {
        Argument argument = typed(name, Argument.Type.INTEGER);
        return argument != null && argument.value() instanceof Value.Int(long value) ? value : 0L;
    }

    public double decimal(String name) {
        Argument argument = typed(name, Argument.Type.DECIMAL);
        return argument != null && argument.value() instanceof Value.Decimal(double value)
                ? value : 0d;
    }

    public PlayerRef player(String name) {
        Argument argument = typed(name, Argument.Type.PLAYER);
        return argument == null ? PlayerRef.NONE : PlayerRef.of(argument.value());
    }

    public BlockPos position(String name) {
        Argument argument = typed(name, Argument.Type.BLOCK_POS);
        return argument == null ? new BlockPos(0, 0, 0) : BlockPos.of(argument.value());
    }

    public Block block(String name) {
        Argument argument = typed(name, Argument.Type.BLOCK_STATE);
        return argument == null ? Block.AIR : Block.of(argument.value());
    }

    public ItemRef item(String name) {
        Argument argument = typed(name, Argument.Type.ITEM);
        return argument == null ? ItemRef.NONE : ItemRef.of(argument.value());
    }

    /// A duration. The wire carries milliseconds because no two runtimes agree
    /// on a finer unit and a tick is fifty of them.
    public Duration duration(String name) {
        Argument argument = typed(name, Argument.Type.DURATION);
        return argument != null && argument.value() instanceof Value.Int(long millis)
                ? Duration.ofMillis(millis) : Duration.ZERO;
    }

    private Argument typed(String name, Argument.Type... accepted) {
        Argument argument = arguments.get(name);
        if (argument == null) {
            return null;
        }
        for (Argument.Type type : accepted) {
            if (argument.type() == type) {
                return argument;
            }
        }
        return null;
    }

    // ── Answering ─────────────────────────────────────────────────────────────

    /// Sends a line back to whoever typed the command.
    ///
    /// Queued rather than sent: a handler runs in this process and the world
    /// belongs to the server's tick, so replies accumulate and travel back with
    /// the answer. Order is kept.
    ///
    /// A command typed at the console has no player to deliver to, and the host
    /// drops the message. Check [CommandSender#isPlayer()] before relying on a
    /// reply being seen.
    public void reply(String message) {
        effect("chat.message", playerValue(sender.player()), Values.text(message));
    }

    /// Records a side effect by name, for calls this API has no method for yet.
    ///
    /// Public because the set of host calls grows faster than the wrappers over
    /// it. A call the host does not know is logged and ignored rather than
    /// failing the command.
    public void effect(String call, Value... values) {
        effects.add(new Effect(call, List.of(values)));
    }

    /// The PlayerRef shape the host reads back: uuid, username, edition. It has
    /// to match what the host wrote, so it is built here rather than by each
    /// caller.
    private static Value playerValue(PlayerRef player) {
        if (!player.present()) {
            return Values.list();
        }
        java.nio.ByteBuffer uuid = java.nio.ByteBuffer.allocate(16);
        uuid.putLong(player.uuid().getMostSignificantBits());
        uuid.putLong(player.uuid().getLeastSignificantBits());
        return Values.list(
                Values.bytes(uuid.array()),
                Values.text(player.username()),
                Values.text(switch (player.edition()) {
                    case JAVA -> "java";
                    case BEDROCK -> "bedrock";
                    case UNKNOWN -> "";
                }));
    }

    // ── For the runtime ───────────────────────────────────────────────────────

    public List<Effect> effects() {
        return List.copyOf(effects);
    }

    @Override
    public String toString() {
        return "CommandContext[" + sender + ", " + names() + "]";
    }
}