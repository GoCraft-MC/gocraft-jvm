package fr.gocraft.runtime;

import fr.gocraft.abi.v1.EventBinding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The plugin-defined event types one plugin was loaded with, name to id.
///
/// The host assigns the ids from the full set of installed manifests, which is
/// a fact only it knows: another server with another set of plugins numbers the
/// same event differently. So the table arrives with LOAD and this runtime
/// never invents one.
///
/// Only this plugin's own types are in it — what it provides and what it
/// subscribes to. A name that is not here was never declared in its manifest.
record EventBindings(Map<String, Integer> byName) {

    private static final EventBindings EMPTY = new EventBindings(Map.of());

    EventBindings {
        byName = Map.copyOf(byName);
    }

    static EventBindings of(List<EventBinding> bindings) {
        if (bindings.isEmpty()) {
            return EMPTY;
        }
        Map<String, Integer> table = new HashMap<>(bindings.size());
        for (EventBinding binding : bindings) {
            // Zero is what abi/v1 puts in Event.type_id for a native event, so
            // an entry carrying it could not be told from one. Dropped rather
            // than failing the load: the plugin can still subscribe and run,
            // and an emit that finds no id says so by name.
            if (binding.getTypeId() == 0 || binding.getType().isBlank()) {
                System.err.println("gocraft-runtime: ignoring an unusable event binding "
                        + binding.getType());
                continue;
            }
            table.put(binding.getType(), binding.getTypeId());
        }
        return new EventBindings(table);
    }

    /// The id for a type, or null when this plugin never declared it.
    Integer id(String type) {
        return byName.get(type);
    }
}