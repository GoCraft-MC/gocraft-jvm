package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Load;
import fr.gocraft.abi.v1.Unload;
import fr.gocraft.abi.v1.Verdict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    PluginRegistry() {
        this(defaultWorkDirectory());
    }

    PluginRegistry(Path workDirectory) {
        this.loader = new PluginLoader(workDirectory);
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
            LoadedPlugin loaded = loader.load(id, request.getBundlePath(), request.getEntry());
            plugins.put(id, loaded);
            // No events are reported: subscriptions come from the manifest the
            // host already validated, and this runtime registers none of its
            // own yet. Claiming one it cannot deliver would be worse than
            // claiming none.
            return Envelopes.loaded(seq, id);
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

    /// Answers a dispatch.
    ///
    /// Nothing subscribes yet, so this always allows. It answers rather than
    /// staying silent because the host blocks its tick on a verdict: saying
    /// nothing burns the whole shared event budget before the event resolves,
    /// and charges it to subscribers that never ran.
    Envelope dispatch(long seq, String pluginId) {
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