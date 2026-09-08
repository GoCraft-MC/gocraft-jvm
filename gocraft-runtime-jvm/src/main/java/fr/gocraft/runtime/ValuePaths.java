package fr.gocraft.runtime;

import fr.gocraft.api.Value;

import java.util.ArrayList;
import java.util.List;

/// Writing a positional mutation into a value list.
///
/// The host applies the same paths as it carries an event from one subscriber
/// to the next; this end applies them once, replaying what came back into the
/// object the plugin published. Two implementations of one rule, which is why
/// each is a handful of lines that does exactly what the other does — an index
/// past the end is refused on both sides rather than growing the list, because
/// whoever wrote there compiled against another version of the event.
final class ValuePaths {

    private ValuePaths() {
    }

    /// Returns a new list with the value at `path` replaced. The input is never
    /// modified: only the levels actually descended into are copied.
    static List<Value> apply(List<Value> values, List<Integer> path, Value replacement) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("a mutation with no path");
        }
        int index = path.get(0);
        if (index < 0 || index >= values.size()) {
            throw new IllegalArgumentException(
                    "mutation index " + index + " is past the end of " + values.size() + " values");
        }
        List<Value> updated = new ArrayList<>(values);
        if (path.size() == 1) {
            updated.set(index, replacement);
            return updated;
        }
        if (!(updated.get(index) instanceof Value.List(List<Value> values1))) {
            throw new IllegalArgumentException(
                    "a mutation descends into index " + index + ", which holds no values");
        }
        updated.set(index, new Value.List(
                apply(values1, path.subList(1, path.size()), replacement)));
        return updated;
    }
}