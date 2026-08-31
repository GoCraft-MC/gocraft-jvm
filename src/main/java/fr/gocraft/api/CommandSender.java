package fr.gocraft.api;

import java.util.Map;

/// Whoever typed the command.
///
/// A snapshot, like [PlayerRef] and for the same reason: it names who acted and
/// carries nothing that can go stale while a handler runs. Anything more —
/// inventory, position, health — is world state, and reading it from here would
/// be a round trip taken while somebody waits at a chat prompt.
///
/// @param name        what to call them. The console has one and no player,
///                    which is why this is not read off the [PlayerRef].
/// @param player      who they are, or [PlayerRef#NONE] when there is none —
///                    the console, or a command block.
/// @param permissions every node this plugin's manifest declared, already
///                    resolved. Read through [#can(String)] rather than
///                    directly.
public record CommandSender(String name, PlayerRef player, Map<String, Boolean> permissions) {

    public CommandSender {
        permissions = Map.copyOf(permissions);
    }

    /// Whether the sender holds a permission node.
    ///
    /// Answered from what the host resolved and sent, not by asking it: the ABI
    /// has no message for asking, and one that existed would be a round trip
    /// inside a command somebody is waiting on.
    ///
    /// A node this plugin's manifest never declared reads false. That is a
    /// manifest bug rather than a denial, and it is worth checking the manifest
    /// before concluding a player lacks a permission they appear to have.
    public boolean can(String node) {
        return permissions.getOrDefault(node, false);
    }

    /// Whether a real player typed this, rather than the console or a
    /// mechanism. Worth checking before anything that only makes sense in the
    /// world — a teleport, a selection, a position.
    public boolean isPlayer() {
        return player.present();
    }

    @Override
    public String toString() {
        return name.isEmpty() ? "<console>" : name;
    }
}