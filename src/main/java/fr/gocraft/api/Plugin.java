package fr.gocraft.api;

/// What a plugin implements.
///
/// A flat interface with constructor injection, rather than the two shapes that
/// suggest themselves and are both wrong. A `static main` does not work: the
/// runtime hosts many plugins in one JVM, and a `main` means "I own the
/// process", so ten of them leaves nowhere to receive the [Host] — hence a
/// static field, hence the singleton everyone regrets. `extends JavaPlugin` is
/// no better: a god object that forces inheritance and makes unit testing
/// painful.
///
/// Dependencies arrive through the constructor, so fields are `final` and there
/// is no half-initialised window. The `onEnable` pattern always has a period
/// where fields are null, which is the source of one NPE in two in Bukkit
/// plugins.
///
///     public final class ShopPlugin implements Plugin {
///         private final Host host;
///
///         public ShopPlugin(Host host) {
///             this.host = host;
///         }
///
///         @Override public void enable()  { host.log("open for business"); }
///         @Override public void disable() { /* only your own resources */ }
///     }
///
/// The injectable set is closed and defined by the ABI. Today it is [Host]
/// alone; a config record, a data store and a scheduler join it as the ABI
/// grows the messages to carry them. Parameters are resolved by type, in any
/// order — this is a fixed list, not a dependency-injection container.
///
/// **Nothing a plugin keeps in a field survives a respawn.** The runtime is a
/// separate process and the server can kill and restart it — three missed pings
/// are enough — while it keeps running. In-memory state is a cache, never a
/// record.
public interface Plugin {

    /// Called once, after construction, before the server opens its listeners.
    ///
    /// Registration is not done here. The host already knows every command and
    /// subscription from the manifest, which is what lets it build the
    /// Brigadier packet while this JVM is still booting.
    default void enable() {
    }

    /// Called once, on unload or shutdown.
    ///
    /// Only for resources this plugin opened itself — connections, files,
    /// threads. The host owns every registration and revokes commands,
    /// listeners and subscriptions on its own, which removes the class of leak
    /// Bukkit suffers on `/reload` where each plugin must unregister by hand
    /// and half of them forget.
    default void disable() {
    }
}