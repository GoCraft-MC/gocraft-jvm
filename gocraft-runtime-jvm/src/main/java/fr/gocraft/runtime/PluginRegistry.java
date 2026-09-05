package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Dispatch;
import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Invoke;
import fr.gocraft.abi.v1.Load;
import fr.gocraft.abi.v1.Unload;
import fr.gocraft.abi.v1.Verdict;
import fr.gocraft.api.CommandContext;
import fr.gocraft.api.CommandHandler;
import fr.gocraft.api.Event;
import fr.gocraft.api.event.GeneratedEvents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Holds the loaded plugins.
///
/// Per-plugin FAIL rather than a global crash is what makes an external runtime
/// tolerable to operate: one plugin with a bad bundle disables itself and the
/// others still run. So every LOAD is answered, and answered with a reason an
/// admin can act on — silence would hold the host's boot open until it timed
/// out, saying nothing about why.
final class PluginRegistry implements AutoCloseable {

    private final Map<String, LoadedPlugin> plugins = new ConcurrentHashMap<>();
    private final PluginLoader loader;
    private final Emitter emitter;

    PluginRegistry(Emitter emitter) {
        this(defaultWorkDirectory(), emitter);
    }

    PluginRegistry(Path workDirectory) {
        this(workDirectory, null);
    }

    PluginRegistry(Path workDirectory, Emitter emitter) {
        this.loader = new PluginLoader(workDirectory);
        this.emitter = emitter;
    }

    /// The publisher this runtime's plugins emit through. Null in a test that
    /// builds a registry with no connection behind it; a plugin that tries to
    /// emit then learns so by name rather than by NullPointerException.
    Emitter emitter() {
        return emitter;
    }

    private static Path defaultWorkDirectory() {
        try {
            return Files.createTempDirectory("gocraft-runtime");
        } catch (IOException failure) {
            throw new IllegalStateException("no writable temporary directory", failure);
        }
    }

    /// Brings up one plugin, or explains why it could not.
    ///
    /// LOAD is handled inline on the reader thread rather than handed to a
    /// virtual thread, because load order is derived from the dependency graph
    /// and a plugin may rely on an earlier one already being up. The host sends
    /// them one at a time and waits for each.
    Envelope load(long seq, Load request) {
        String id = request.getPluginId();
        if (id.isBlank()) {
            return Envelopes.fail(seq, id, "the host sent a LOAD with no plugin id");
        }
        if (plugins.containsKey(id)) {
            return Envelopes.fail(seq, id, "already loaded");
        }
        try {
            LoadedPlugin loaded = loader.load(id, request.getBundlePath(), request.getEntry(),
                    request.getDataDirectory(), request.getCommandTree(),
                    EventBindings.of(request.getEventTypesList()), emitter);
            plugins.put(id, loaded);
            // What the plugin actually registered. The host checks it against
            // the manifest it validated and refuses anything undeclared, which
            // it would otherwise never route — leaving the author with a
            // handler that is simply never called.
            return Envelopes.loaded(seq, id,
                    loaded.subscriptions().types().toArray(String[]::new));
        } catch (PluginLoader.LoadFailure failure) {
            return Envelopes.fail(seq, id, failure.getMessage());
        }
    }

    /// Drops one plugin and everything it holds.
    ///
    /// The schema carries no acknowledgement for UNLOAD, so a failure here goes
    /// to the console: the host is not waiting on an answer and inventing one
    /// would be inventing protocol.
    void unload(Unload request) {
        LoadedPlugin loaded = plugins.remove(request.getPluginId());
        if (loaded == null) {
            return;
        }
        try {
            loaded.close();
        } catch (IOException failure) {
            System.err.println("gocraft-runtime: unloading " + request.getPluginId()
                    + ": " + failure.getMessage());
        }
    }

    /// Runs one event through one plugin's handlers and answers.
    ///
    /// It always answers, whatever happens. The host blocks its tick waiting
    /// for this, under a budget shared by every subscriber, so silence does not
    /// merely lose one verdict — it burns what was left of the budget and
    /// charges it to plugins that never ran.
    Envelope dispatch(long seq, Dispatch request) {
        String pluginId = request.getPluginId();
        LoadedPlugin loaded = plugins.get(pluginId);
        if (loaded == null) {
            return allow(seq);
        }
        var fields = EventCodec.fields(request.getEvent().getFieldsList());
        Event event = GeneratedEvents.create(request.getEvent().getType(), fields);
        if (event == null) {
            // An event this build does not know. Allowing is the only honest
            // answer: refusing something we cannot inspect would prevent
            // gameplay on the strength of a version mismatch.
            System.err.println("gocraft-runtime: " + pluginId + " was sent "
                    + request.getEvent().getType() + ", which this build does not know");
            return allow(seq);
        }
        loaded.subscriptions().dispatch(event.type(), event, (handler, thrown) ->
                System.err.println("gocraft-runtime: " + pluginId + " handler " + handler
                        + " threw " + thrown));
        return Envelopes.verdict(seq, EventCodec.verdict(event));
    }

    /// Runs one command in one plugin and answers.
    ///
    /// It always answers, like dispatch, and for a plainer reason: somebody is
    /// looking at a chat prompt waiting for the line to do something. Silence
    /// would leave them there until the host gave up.
    ///
    /// A handler that throws becomes the error the sender reads. That is why
    /// the API documents throwing as a legitimate way to refuse — the message
    /// travels — and why what is reported here is the exception's own message
    /// rather than a stack trace nobody typing a command can act on. The trace
    /// still goes to the runtime's output, where an admin can find it.
    Envelope invoke(long seq, Invoke request) {
        String pluginId = request.getPluginId();
        LoadedPlugin loaded = plugins.get(pluginId);
        if (loaded == null) {
            return Envelopes.invoked(seq, CommandCodec.invoked(
                    "plugin " + pluginId + " is not loaded in this runtime", List.of()));
        }
        CommandHandler handler = loaded.commands().handler(request.getExecutor());
        if (handler == null) {
            // The host routed a command this plugin declared and never bound.
            // Reported by name because the manifest and the code disagree, and
            // only one of them can be fixed by whoever reads this.
            return Envelopes.invoked(seq, CommandCodec.invoked(
                    "plugin " + pluginId + " has no handler for this command", List.of()));
        }
        CommandContext context = CommandCodec.context(request);
        try {
            handler.handle(context);
        } catch (Exception | Error thrown) {
            System.err.println("gocraft-runtime: " + pluginId + " command handler threw " + thrown);
            return Envelopes.invoked(seq, CommandCodec.invoked(
                    reason(thrown), context.effects()));
        }
        return Envelopes.invoked(seq, CommandCodec.invoked(null, context.effects()));
    }

    /// What the sender is shown. A message when the handler wrote one, the
    /// class name when it threw something wordless — an NPE's message is null,
    /// and "null" on a chat line tells nobody anything.
    private static String reason(Throwable thrown) {
        String message = thrown.getMessage();
        return message == null || message.isBlank() ? thrown.getClass().getSimpleName() : message;
    }

    private static Envelope allow(long seq) {
        return Envelopes.verdict(seq, Verdict.newBuilder().setCancelled(false).build());
    }

    boolean isLoaded(String pluginId) {
        return plugins.containsKey(pluginId);
    }

    LoadedPlugin get(String pluginId) {
        return plugins.get(pluginId);
    }

    int size() {
        return plugins.size();
    }

    /// Unloads everything, in the face of a plugin that throws on the way out.
    ///
    /// One bad `disable()` must not keep the others loaded: the process is
    /// going away either way, and a classloader left alive matters less than a
    /// plugin never told to stop.
    @Override
    public void close() {
        for (String id : Map.copyOf(plugins).keySet()) {
            unload(Unload.newBuilder().setPluginId(id).build());
        }
    }
}