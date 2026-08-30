package fr.gocraft.api;

import java.util.LinkedHashMap;
import java.util.Map;

/// A block, with its full state.
///
/// The state travels with the event rather than a handle to re-resolve. A
/// handler that had to ask the server what it was just handed would pay a round
/// trip inside an event that is blocking the tick, which is the cost §03 spends
/// a denormalised payload to avoid.
public record Block(String id, Map<String, String> properties) {

    public Block {
        properties = Map.copyOf(properties);
    }

    public static final Block AIR = new Block("minecraft:air", Map.of());

    public static Block of(Value value) {
        if (!(value instanceof Value.List list) || list.size() < 1) {
            return AIR;
        }
        String id = list.at(0) instanceof Value.Text(String name) ? name : AIR.id();
        return new Block(id, properties(list.at(1)));
    }

    /// Properties arrive as a list of key/value pairs rather than a map: the
    /// wire format has no map, and a list keeps the payload ordered so the same
    /// block always serialises to the same bytes.
    private static Map<String, String> properties(Value value) {
        if (!(value instanceof Value.List entries)) {
            return Map.of();
        }
        Map<String, String> read = new LinkedHashMap<>();
        for (Value entry : entries.values()) {
            if (entry instanceof Value.List pair && pair.size() >= 2
                    && pair.at(0) instanceof Value.Text(String key)
                    && pair.at(1) instanceof Value.Text(String property)) {
                read.put(key, property);
            }
        }
        return read;
    }

    /// A state property, or empty when the block does not have one.
    public String property(String name) {
        return properties.getOrDefault(name, "");
    }

    public boolean isAir() {
        return AIR.id().equals(id);
    }

    @Override
    public String toString() {
        return properties.isEmpty() ? id : id + properties;
    }
}
