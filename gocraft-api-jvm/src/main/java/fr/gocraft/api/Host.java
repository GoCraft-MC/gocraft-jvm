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

    /// Where this plugin's own files live.
    ///
    /// The host creates it, seeds it from the bundle's `config/` on first load,
    /// and never overwrites what an admin edited afterwards. It belongs to this
    /// plugin alone: two plugins shipping a `config.yml` do not collide.
    ///
    /// The path is given, not derived. A plugin computing its own would compute
    /// a different one from the host the day either changed its mind, and would
    /// then read a configuration nobody is editing.
    ///
    /// Unlike memory, what is written here survives a runtime respawn.
    ///
    /// @throws IllegalStateException when the host did not send one — an older
    ///         host, or one that loaded this plugin without preparing its data.
    ///         Better than a made-up path a plugin would silently write into.
    java.nio.file.Path dataDirectory();

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

    /// Binds code to one of this plugin's commands.
    ///
    /// The path is the one through the command tree the bundle shipped, with
    /// arguments in angle brackets — "shop sell <price>", "region define
    /// <name>" — and not the executor id the tree assigned.
    /// Ids belong to whatever built the bundle, so naming one here would be a
    /// second place they are written down, free to disagree with the first the
    /// day the tree is rebuilt.
    ///
    /// Call it from `enable()`. The host already knows every command from the
    /// manifest and has told every client about them before this JVM started,
    /// so registering late does not add a command — it only decides whether the
    /// one already advertised does anything.
    ///
    /// @throws IllegalArgumentException if this plugin's tree has no such path,
    ///         or something is already bound to it. Both are refused rather
    ///         than accepted: a handler that never runs is indistinguishable
    ///         from a command that does not work.
    void registerCommand(String path, CommandHandler handler);

    /// Registers everything one of the command facades produced.
    ///
    ///     host.registerCommands(ShopCommandsTree.of(new ShopCommands(store)));
    ///     host.registerCommands(new ShopCommands(store).build());
    ///
    /// The set says which handler answers each path; the tree the bundle
    /// shipped says which executor a path reaches. Only the paths have to
    /// agree — the ids belong to whatever built the bundle, and a facade that
    /// numbered its own differently is still describing the same commands.
    ///
    /// A path the bundle does not declare is refused, listing what it does
    /// declare, because it means the bundle was built from a different source
    /// than the code registering against it. That is worth stopping for: the
    /// host has already told every client the command exists.
    void registerCommands(fr.gocraft.api.command.CommandSet commands);

    /// Publishes a plugin-defined event and waits for its subscribers.
    ///
    ///     PurchaseEvent purchase = new PurchaseEvent(player, tiers, 1500);
    ///     if (host.emit(purchase)) {
    ///         charge(player, purchase.price());   // the discounted one
    ///     }
    ///
    /// False means a subscriber cancelled it, or that a fail-closed event lost
    /// one. Abandon whatever it was about to do: the host applied nothing on
    /// the plugin's behalf.
    ///
    /// The event object is updated before this returns, so the price a
    /// discount plugin set is read from the field that was published — across
    /// a process boundary and two languages, with the same feel as an
    /// in-process listener.
    ///
    /// The host does the dispatching, not this runtime. Subscribers span
    /// runtimes, they run in priority order, and cancellation has to be
    /// arbitrated by something that is not one of them. The plugin's own
    /// handlers are skipped: it is the author of that state, not an observer.
    ///
    /// It blocks the calling thread. Called from a handler, it therefore
    /// spends the event budget the host is already holding the tick on — so
    /// emit from a command or a scheduled task rather than from inside a
    /// cancellable event, unless the event is what the plugin exists to
    /// publish.
    ///
    /// @throws IllegalArgumentException if `plugin.toml` never declared this
    ///         event under `[[events.provides]]`, so the host assigned it no
    ///         id and nothing would receive it.
    /// @throws IllegalStateException if the host refused the emission or the
    ///         connection went away while it was in flight.
    /// Takes an Object rather than one interface because the two ways of
    /// declaring an event cannot share one: a @PluginEvent class implements
    /// nothing — that is the point of it — and an annotation processor cannot
    /// add an interface to a class someone else wrote. What is lost is a
    /// compile error for passing the wrong thing; what is refused at runtime
    /// names both routes rather than saying "not an event".
    boolean emit(Object event);

    /// Writes a line to the server console.
    ///
    /// The runtime's output is routed to the server's own, so this lands in the
    /// console and in `latest.log` beside everything else, prefixed with the
    /// plugin it came from.
    void log(String message);
}