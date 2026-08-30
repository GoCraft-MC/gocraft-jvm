package fr.gocraft.api;

/// The server, as a plugin sees it.
///
/// It is deliberately small. Writes never originate from a plugin: the host
/// queues them and applies them on the simulation tick, so this grows a method
/// only when the ABI grows a message to carry it. What is here works today and
/// nothing else is promised.
///
/// There is no static accessor and there never will be. In Bukkit,
/// `Bukkit.getX()` is callable from anywhere, so internal architecture drifts
/// toward static access; a class that needs the host here must be given it, and
/// that makes dependencies visible in signatures and the code testable with an
/// ordinary fake.
public interface Host {

    /// The plugin's own id, from its manifest.
    String pluginId();

    /// Writes a line to the server console.
    ///
    /// The runtime's output is routed to the server's own, so this lands in the
    /// console and in `latest.log` beside everything else, prefixed with the
    /// plugin it came from.
    void log(String message);
}