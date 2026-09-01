package fr.gocraft.runtime;

import com.google.protobuf.InvalidProtocolBufferException;
import fr.gocraft.abi.v1.CommandTree;
import fr.gocraft.api.Host;
import fr.gocraft.api.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Turns a validated bundle into a running plugin.
///
/// The host has already read and checked the manifest, so nothing here parses
/// one: this is told which id, which archive and which entry class, and its job
/// is to produce an instance or a reason it could not.
///
/// Every failure is a [LoadFailure] carrying a sentence meant for an admin. A
/// stack trace in a server console tells the person reading it nothing they can
/// act on, and the reason travels back over the wire as the FAIL a plugin gets
/// instead of a crash.
final class PluginLoader {

    /// Where a bundle keeps the code. §04: a manifest, a `payload/` directory,
    /// assets. `payload/lib/` is the air-gapped alternative to resolving
    /// libraries from Maven, and both go on the same classpath.
    private static final String PAYLOAD = "payload/";

    /// The same 4 MiB the host reads a tree under. A bundle is untrusted input
    /// and a tree is data, so it is bounded before it is parsed.
    private static final int MAXIMUM_COMMAND_TREE = 4 << 20;

    private final Path workDirectory;

    PluginLoader(Path workDirectory) {
        this.workDirectory = workDirectory;
    }

    /// Failure with a reason an admin can act on, and nothing else.
    static final class LoadFailure extends Exception {
        LoadFailure(String reason) {
            super(reason);
        }

        LoadFailure(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    LoadedPlugin load(String pluginId, String bundlePath, String entry, String dataDirectory,
            String commandTree) throws LoadFailure {
        if (entry == null || entry.isBlank()) {
            // §05 makes the main class optional, but only once the runtime can
            // find annotated classes on its own. Until then there is nothing to
            // instantiate, and saying so beats loading a plugin that does
            // nothing.
            throw new LoadFailure("the manifest declares no entry class, which this runtime "
                    + "build still requires");
        }

        Path extracted = null;
        PluginClassLoader loader = null;
        try {
            extracted = extractPayload(pluginId, bundlePath);
            loader = new PluginClassLoader(pluginId, classpath(extracted), getClass().getClassLoader());
            // Built before the plugin, because the Host it is constructed with
            // has to be able to take a listener during enable().
            Subscriptions subscriptions = new Subscriptions();
            CommandBindings commands = readCommands(bundlePath, commandTree);
            Plugin instance = instantiate(loader, pluginId, entry, subscriptions, dataDirectory,
                    commands);
            registerOwnHandlers(instance, subscriptions);
            LoadedPlugin loaded = new LoadedPlugin(pluginId, loader, extracted, instance,
                    subscriptions, commands);
            enable(loaded);
            reportUnbound(pluginId, commands);
            return loaded;
        } catch (LoadFailure failure) {
            // Nothing half-loaded survives: a classloader left open on a plugin
            // that never started is the same leak as one left open on unload.
            discard(loader, extracted);
            throw failure;
        } catch (IOException failure) {
            discard(loader, extracted);
            throw new LoadFailure("reading bundle " + bundlePath + ": " + failure.getMessage(), failure);
        }
    }

    /// Reads the command tree out of the bundle and indexes it by path.
    ///
    /// The tree is not sent over the wire — the host sends where it is, the way
    /// it sends the entry class — because this process opens the bundle anyway
    /// and a second copy would be free to disagree with the one handlers bind
    /// against.
    ///
    /// A tree the host named and the bundle does not contain is a failure. The
    /// host validated that bundle before sending it, so the mismatch means the
    /// archive changed underneath, and loading a plugin whose commands cannot
    /// bind would advertise commands that do nothing.
    private CommandBindings readCommands(String bundlePath, String commandTree)
            throws LoadFailure, IOException {
        if (commandTree == null || commandTree.isBlank()) {
            return CommandBindings.none();
        }
        try (ZipFile archive = new ZipFile(Path.of(bundlePath).toFile())) {
            ZipEntry entry = archive.getEntry(commandTree);
            if (entry == null) {
                throw new LoadFailure("the manifest names a command tree at " + commandTree
                        + ", which bundle " + bundlePath + " does not contain");
            }
            if (entry.getSize() > MAXIMUM_COMMAND_TREE) {
                throw new LoadFailure("the command tree at " + commandTree + " is larger than "
                        + MAXIMUM_COMMAND_TREE + " bytes");
            }
            try (InputStream source = archive.getInputStream(entry)) {
                return CommandBindings.of(CommandTree.parseFrom(
                        source.readNBytes(MAXIMUM_COMMAND_TREE)));
            } catch (InvalidProtocolBufferException malformed) {
                throw new LoadFailure("the command tree at " + commandTree
                        + " is not a readable tree: " + malformed.getMessage(), malformed);
            }
        }
    }

    /// Names the commands a bundle promised and the plugin never bound.
    ///
    /// A warning rather than a failure: the host has already advertised them to
    /// every connected client, so refusing the load now would take working
    /// commands down with the missing ones. Somebody has to be told, though —
    /// the alternative is a player typing a command that silently does nothing.
    private static void reportUnbound(String pluginId, CommandBindings commands) {
        List<String> unbound = commands.unbound();
        if (!unbound.isEmpty()) {
            System.err.println("gocraft-runtime: " + pluginId + " declares commands it never "
                    + "registered a handler for: " + String.join(", ", unbound));
        }
    }

    private void enable(LoadedPlugin loaded) throws LoadFailure {
        try {
            loaded.instance().enable();
        } catch (RuntimeException | Error thrown) {
            throw new LoadFailure("enable() threw " + thrown, thrown);
        }
    }

    private void discard(PluginClassLoader loader, Path extracted) {
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // Already failing, and the reason for that failure is the one
                // worth reporting.
            }
        }
        if (extracted != null) {
            deleteQuietly(extracted);
        }
    }

    private static void deleteQuietly(Path root) {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A temporary directory left behind is not worth failing a load
            // that already failed for a better reason.
        }
    }

    /// Unpacks `payload/` into a directory of its own.
    ///
    /// A URLClassLoader cannot read a jar nested inside another archive, so the
    /// jars come out first. One directory per plugin, deleted on unload,
    /// because two plugins may ship files of the same name.
    ///
    /// bundlePath is quoted back exactly as the host sent it, rather than as a
    /// Path renders it. Path prints with the platform separator, so a message
    /// about `/plugins/shop.gcpkg` would reach a Windows admin as
    /// `\plugins\shop.gcpkg` — a string they can no longer match against their
    /// own configuration or the server's log.
    private Path extractPayload(String pluginId, String bundlePath) throws IOException, LoadFailure {
        Path bundle = Path.of(bundlePath);
        if (!Files.isRegularFile(bundle)) {
            throw new LoadFailure("bundle " + bundlePath + " is not a readable file");
        }
        Path target = Files.createTempDirectory(workDirectory, "gc-" + pluginId + "-");
        int extracted = 0;
        try (ZipFile archive = new ZipFile(bundle.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.startsWith(PAYLOAD) || !name.endsWith(".jar")) {
                    continue;
                }
                Path destination = resolveInside(target, name.substring(PAYLOAD.length()));
                Files.createDirectories(destination.getParent());
                try (InputStream source = archive.getInputStream(entry)) {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                extracted++;
            }
        }
        if (extracted == 0) {
            throw new LoadFailure("bundle " + bundlePath + " contains no " + PAYLOAD + "*.jar");
        }
        return target;
    }

    /// Refuses an entry whose name climbs out of the target directory.
    ///
    /// An archive is untrusted input, and `../../../etc/whatever` inside one is
    /// old enough to have a name. The check is on the resolved path rather than
    /// on the string, because there are more ways to write it than to spell it.
    private static Path resolveInside(Path target, String name) throws LoadFailure {
        Path resolved = target.resolve(name).normalize();
        if (!resolved.startsWith(target)) {
            throw new LoadFailure("bundle entry " + name + " escapes the payload directory");
        }
        return resolved;
    }

    private static URL[] classpath(Path extracted) throws IOException {
        List<URL> jars = new ArrayList<>();
        try (var walk = Files.walk(extracted)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                if (path.getFileName().toString().endsWith(".jar")) {
                    jars.add(path.toUri().toURL());
                }
            }
        }
        return jars.toArray(URL[]::new);
    }

    /// Builds the plugin, injecting by parameter type.
    ///
    /// The injectable set is closed and defined by the ABI, so this resolves
    /// against a fixed list rather than scanning for anything constructible. A
    /// parameter it does not recognise is refused by name — an author who
    /// asked for a DataStore before the ABI can carry one deserves to be told
    /// that, not to see a NoSuchMethodException.
    private Plugin instantiate(PluginClassLoader loader, String pluginId, String entry,
            Subscriptions subscriptions, String dataDirectory, CommandBindings commands)
            throws LoadFailure {
        Class<?> type;
        try {
            type = Class.forName(entry, false, loader);
        } catch (ClassNotFoundException missing) {
            throw new LoadFailure("entry class " + entry + " is not in the bundle", missing);
        }
        if (!Plugin.class.isAssignableFrom(type)) {
            throw new LoadFailure("entry class " + entry + " does not implement "
                    + Plugin.class.getName());
        }

        Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length != 1) {
            throw new LoadFailure(entry + " declares " + constructors.length
                    + " constructors; a plugin must have exactly one so the runtime knows "
                    + "which to inject");
        }
        Constructor<?> constructor = constructors[0];
        Object[] arguments = resolve(constructor, pluginId, entry, subscriptions, dataDirectory,
                commands);
        try {
            constructor.setAccessible(true);
            return (Plugin) constructor.newInstance(arguments);
        } catch (InvocationTargetException thrown) {
            throw new LoadFailure("the constructor of " + entry + " threw "
                    + thrown.getCause(), thrown.getCause());
        } catch (ReflectiveOperationException | RuntimeException refused) {
            throw new LoadFailure("could not construct " + entry + ": " + refused, refused);
        }
    }

    private Object[] resolve(Constructor<?> constructor, String pluginId, String entry,
            Subscriptions subscriptions, String dataDirectory, CommandBindings commands)
            throws LoadFailure {
        Class<?>[] parameters = constructor.getParameterTypes();
        Object[] arguments = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            if (parameters[index] == Host.class) {
                arguments[index] = new RuntimeHost(pluginId, subscriptions, dataDirectory,
                        commands);
                continue;
            }
            throw new LoadFailure(entry + " asks for a " + parameters[index].getName()
                    + ", which this runtime build cannot inject; it supplies "
                    + Host.class.getName() + " only");
        }
        return arguments;
    }

    /// The Host handed to a plugin.
    ///
    /// It writes to the runtime's own output, which the server routes into its
    /// console and latest.log, so a plugin's line appears beside the server's
    /// with the plugin that wrote it named.
    /// The component is named `data` and not `dataDirectory` on purpose: a
    /// record's accessor has to return its component's type, and the Host
    /// contract hands a plugin a Path rather than the raw string the wire
    /// carries. Naming them alike would make dataDirectory() an illegal
    /// override rather than a conversion.
    private record RuntimeHost(String pluginId, Subscriptions subscriptions, String data,
            CommandBindings commands) implements Host {
        @Override
        public void log(String message) {
            System.out.println("[" + pluginId + "] " + message);
        }

        /// Refusing beats inventing a path. A plugin handed a directory the host
        /// does not know about would write a configuration nobody reads and lose
        /// it on the next restart.
        @Override
        public java.nio.file.Path dataDirectory() {
            if (data == null || data.isBlank()) {
                throw new IllegalStateException("the host did not send a data directory for "
                        + pluginId + "; it may be older than this runtime");
            }
            return java.nio.file.Path.of(data);
        }

        /// Refusing loudly for the same reason registerListener does: a handler
        /// bound to a path the tree does not contain would never run, and a
        /// command that does nothing is indistinguishable from a broken server.
        @Override
        public void registerCommand(String path, fr.gocraft.api.CommandHandler handler) {
            commands.register(path, handler);
        }

        @Override
        public void registerCommands(fr.gocraft.api.command.CommandSet set) {
            if (set == null) {
                throw new IllegalArgumentException("a command set cannot be null");
            }
            // Registered one path at a time, through the same door a hand-written
            // registration uses. A facade is a way of writing commands, not a
            // second way of installing them, so it inherits every refusal the
            // single registration already makes.
            fr.gocraft.api.command.CommandPaths.of(set.tree())
                    .forEach((path, executor) -> commands.register(path, set.invokers().get(executor)));
        }

        /// Refusing loudly rather than dropping the listener: a subscription
        /// that silently never fires is indistinguishable from an event that
        /// never happens, and costs an afternoon to tell apart.
        @Override
        public void registerListener(Object listener) {
            try {
                subscriptions.register(listener);
            } catch (Subscriptions.InvalidHandler refused) {
                throw new IllegalArgumentException(refused.getMessage(), refused);
            }
        }
    }

    /// Registers the plugin itself, when it carries handlers.
    ///
    /// Optional by design: a plugin that keeps its handlers on their own
    /// listener — which §05 recommends, because it can then be tested with no
    /// server at all — has none of its own, and that is not an error.
    private void registerOwnHandlers(Plugin instance, Subscriptions subscriptions)
            throws LoadFailure {
        try {
            subscriptions.register(instance);
        } catch (Subscriptions.InvalidHandler noHandlers) {
            if (subscriptions.size() == 0 && noHandlers.getMessage().contains("no @Subscribe")) {
                return;
            }
            throw new LoadFailure(noHandlers.getMessage(), noHandlers);
        }
    }
}