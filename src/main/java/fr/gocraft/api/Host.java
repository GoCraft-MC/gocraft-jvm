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

    /// Registers an object whose @Subscribe methods should receive events.
    ///
    /// Handlers rarely belong on the plugin itself. §05 keeps them on their own
    /// listener — `new ProtectionListener(config)` — precisely so they can be
    /// unit-tested with no server and no runtime, and this is how such an
    /// object reaches the dispatcher. The plugin instance is registered for you
    /// if it carries handlers of its own.
    ///
    /// Call it from `enable()`. The host reports what a plugin registered as
    /// soon as loading finishes, and checks it against the manifest; a listener
    /// registered later subscribing to a type the manifest never declared will
    /// never be dispatched, because the host routes from the manifest alone.
    ///
    /// @throws IllegalArgumentException if the object has no handler, is
    ///         already registered, or a method takes something that is not an
    ///         event this runtime knows.
    void registerListener(Object listener);

    /// Writes a line to the server console.
    ///
    /// The runtime's output is routed to the server's own, so this lands in the
    /// console and in `latest.log` beside everything else, prefixed with the
    /// plugin it came from.
    void log(String message);
}