package fr.gocraft.api;

import java.nio.ByteBuffer;
import java.util.UUID;

/// The player an event is about.
///
/// A reference, not the player: it names who acted and carries nothing that can
/// go stale between the event being built and a handler reading it. Anything
/// more — inventory, position, health — is world state, and reading it from
/// here would mean a round trip taken while the tick waits.
public record PlayerRef(UUID uuid, String username, Edition edition) {

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
            new PlayerRef(new UUID(0, 0), "", Edition.UNKNOWN);

    /// Reads one out of an event payload.
    ///
    /// The host sends an empty list when there is no player, because the wire
    /// format has no null and a fixed layout would have to special-case one
    /// anyway.
    public static PlayerRef of(Value value) {
        if (!(value instanceof Value.List list) || list.size() < 3) {
            return NONE;
        }
        return new PlayerRef(
                uuid(list.at(0)),
                list.at(1) instanceof Value.Text(String name) ? name : "",
                list.at(2) instanceof Value.Text(String edition)
                        ? Edition.of(edition) : Edition.UNKNOWN);
    }

    /// Sixteen bytes, big-endian, as every edition writes them.
    private static UUID uuid(Value value) {
        if (!(value instanceof Value.Bytes(byte[] raw)) || raw.length != 16) {
            return new UUID(0, 0);
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /// Whether this is a real player rather than the server or a mechanism.
    public boolean present() {
        return !username.isEmpty();
    }

    @Override
    public String toString() {
        return present() ? username : "<none>";
    }
}
