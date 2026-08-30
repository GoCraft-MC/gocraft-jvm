package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Fail;
import fr.gocraft.abi.v1.Load;
import fr.gocraft.abi.v1.Unload;
import fr.gocraft.abi.v1.Verdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Holds the loaded plugins.
///
/// This slice loads none. Classloading, the constructor injection of §05 and
/// the dispatch path are the next milestone; what exists here is the part that
/// has to be right first — every LOAD is answered, and answered with a reason
/// an admin can act on.
///
/// Per-plugin FAIL rather than a global crash is what makes an external runtime
/// tolerable to operate: one plugin with a bad bundle disables itself and the
/// others still run.
final class PluginRegistry {

    private final Map<String, String> loaded = new ConcurrentHashMap<>();

    /// Brings up one plugin, or explains why it could not.
    ///
    /// The reason travels to the admin verbatim, so it names the bundle and the
    /// thing that was missing rather than the exception type.
    Envelope load(long seq, Load request) {
        String id = request.getPluginId();
        String reason = reject(request);
        if (reason != null) {
            return Envelopes.fail(seq, id, reason);
        }
        loaded.put(id, request.getBundlePath());
        // Unreachable in this slice: reject() has no path that accepts a
        // bundle yet. It is written this way so the shape of the answer is
        // settled before the loading is.
        return Envelopes.loaded(seq, id);
    }

    private String reject(Load request) {
        if (request.getPluginId().isBlank()) {
            return "the host sent a LOAD with no plugin id";
        }
        String path = request.getBundlePath();
        if (path.isBlank()) {
            return "no bundle path";
        }
        if (!Files.isRegularFile(Path.of(path))) {
            return "bundle " + path + " is not a readable file";
        }
        return "this runtime build loads no plugins yet: it speaks the ABI, "
                + "but classloading and the plugin API are the next milestone";
    }

    void unload(Unload request) {
        loaded.remove(request.getPluginId());
    }

    /// Answers a dispatch for a plugin that is not loaded.
    ///
    /// Nothing can be dispatched in this slice, because nothing loads — but the
    /// host blocks its tick on a verdict, so silence here would burn the whole
    /// event budget before the event resolved. Answering without cancelling
    /// leaves the outcome to the other subscribers.
    Envelope dispatch(long seq, String pluginId) {
        return Envelopes.verdict(seq, Verdict.newBuilder().setCancelled(false).build());
    }

    boolean isLoaded(String pluginId) {
        return loaded.containsKey(pluginId);
    }

    int size() {
        return loaded.size();
    }

    static Fail failure(String pluginId, String reason) {
        return Fail.newBuilder().setPluginId(pluginId).setReason(reason).build();
    }
}