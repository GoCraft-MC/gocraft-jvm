package fr.gocraft.api;

import java.util.LinkedHashMap;
import java.util.Map;

/// Helpers the generated event classes call.
///
/// Public because generated code lives in a subpackage, not because a plugin
/// has any reason to name it.
public final class Events {

    private Events() {
    }

    /// Reads the injected permission map out of a payload, at the index the
    /// schema put it.
    ///
    /// The index is generated rather than searched for. Permissions arrive as a
    /// list of pairs — the payload is a flat list of Values and has no map —
    /// and so does a block's property list, so a runtime hunting for "the field
    /// that looks like permissions" would eventually find the wrong one. The
    /// generator knows which field carries them; it says so.
    public static Map<String, Boolean> permissions(java.util.List<Value> fields, int index) {
        if (index < 0 || index >= fields.size()
                || !(fields.get(index) instanceof Value.List entries)) {
            return Map.of();
        }
        Map<String, Boolean> resolved = new LinkedHashMap<>();
        for (Value entry : entries.values()) {
            if (entry instanceof Value.List pair && pair.size() >= 2
                    && pair.at(0) instanceof Value.Text(String node)
                    && pair.at(1) instanceof Value.Bool(boolean allowed)) {
                resolved.put(node, allowed);
            }
        }
        return resolved;
    }
}
