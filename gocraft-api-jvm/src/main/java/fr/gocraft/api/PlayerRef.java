package fr.gocraft.api;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/// The player an event is about, and what a handler acts on them through.
///
/// A reference, not the player: it names who acted and carries nothing that can
/// go stale between the event being built and a handler reading it. Anything
/// more — inventory, position, health — is world state, and reading it from
/// here would mean a round trip taken while the tick waits.
///
/// The verbs live here rather than on the channel a handler answers with,
/// because an effect belongs to the thing it happens to. The alternative puts
/// every future verb on the channel, and the channel then grows with the
/// vocabulary instead of the vocabulary growing on its own.
///
/// A class and not a record for one reason: it holds the dispatch it can act
/// through, and a record's components are its identity. Two references to the
/// same player from two events would compare unequal. Identity here is the
/// uuid, and [#equals] says so.
///
/// **Do not keep one.** The fields are a snapshot the server has already moved
/// past, and acting through a handle after its event was answered raises rather
/// than losing the effect quietly.
public final class PlayerRef {

    private final UUID uuid;
    private final String username;
    private final Edition edition;

    /// The dispatch this handle can act through, null for one read outside any
    /// — [#NONE], or a payload decoded by a test.
    private final EffectSink sink;

    public PlayerRef(UUID uuid, String username, Edition edition, EffectSink sink) {
        this.uuid = uuid;
        this.username = username;
        this.edition = edition;
        this.sink = sink;
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    public Edition edition() {
        return edition;
    }

    /// Which client the player is on. A plugin should rarely care; the one
    /// place it does is deciding what a client can render.
    public enum Edition {
        JAVA,
        BEDROCK,
        /// A player on a client this runtime was built before. Treat as
        /// neither, rather than guessing wrong.
        UNKNOWN;

        static Edition of(String name) {
            return switch (name) {
                case "java" -> JAVA;
                case "bedrock" -> BEDROCK;
                default -> UNKNOWN;
            };
        }
    }

    /// An event with no acting player — a block broken by a piston, say.
    public static final PlayerRef NONE =
            new PlayerRef(new UUID(0, 0), "", Edition.UNKNOWN, null);

    /// Reads one out of an event payload, bound to the dispatch it arrived in.
    ///
    /// The host sends an empty list when there is no player, because the wire
    /// format has no null and a fixed layout would have to special-case one
    /// anyway.
    public static PlayerRef of(Value value, EffectSink sink) {
        if (!(value instanceof Value.List list) || list.size() < 3) {
            return NONE;
        }
        return new PlayerRef(
                uuid(list.at(0)),
                list.at(1) instanceof Value.Text(String name) ? name : "",
                list.at(2) instanceof Value.Text(String edition)
                        ? Edition.of(edition) : Edition.UNKNOWN,
                sink);
    }

    /// Sixteen bytes, big-endian, as every edition writes them.
    private static UUID uuid(Value value) {
        if (!(value instanceof Value.Bytes(byte[] raw)) || raw.length != 16) {
            return new UUID(0, 0);
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /// The same player, able to act through this dispatch.
    ///
    /// For a handle decoded before the thing it will act through exists — a
    /// command's sender is read off the wire to build the context that then
    /// carries its effects. Binding late rather than threading the sink through
    /// every decoder keeps that plumbing where it belongs.
    public PlayerRef boundTo(EffectSink sink) {
        return sink == this.sink ? this : new PlayerRef(uuid, username, edition, sink);
    }

    /// Delivers one line to this player.
    ///
    /// Batched into the verdict with every other effect, so a handler that
    /// sends three lines still costs one round trip, and applied by the host on
    /// its own tick — never from the thread a handler runs on, which is in
    /// another process from the world it would be writing to.
    ///
    /// A player who logged out between the event and that tick is dropped
    /// without a word, which is common enough not to be worth reporting.
    ///
    /// @throws IllegalStateException if this handle belongs to no dispatch, or
    ///         to one that has already been answered
    public void sendMessage(String message) {
        if (sink == null) {
            throw new IllegalStateException(
                    "this player handle belongs to no dispatch, so " + this
                            + " cannot be sent anything");
        }
        sink.add("chat.message", List.of(uuidValue(), new Value.Text(message)));
    }

    /// The uuid as the wire carries it, which is how an effect names a
    /// recipient.
    private Value uuidValue() {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return new Value.Bytes(buffer.array());
    }

    /// Whether this is a real player rather than the server or a mechanism.
    public boolean present() {
        return !username.isEmpty();
    }

    /// Identity is the uuid, and only the uuid.
    ///
    /// Not the username, which a player may change, and above all not the
    /// dispatch: two references to the same person from two events are the same
    /// person. That is exactly what a record could not have expressed, its
    /// components being its identity.
    @Override
    public boolean equals(Object other) {
        return other instanceof PlayerRef player && uuid.equals(player.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uuid);
    }

    @Override
    public String toString() {
        return present() ? username : "<none>";
    }
}