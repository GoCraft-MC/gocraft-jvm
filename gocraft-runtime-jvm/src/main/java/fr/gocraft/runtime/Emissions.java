package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Emit;
import fr.gocraft.abi.v1.Emitted;
import fr.gocraft.abi.v1.Mutation;
import fr.gocraft.api.CustomEvent;
import fr.gocraft.api.EventLayout;
import fr.gocraft.api.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/// Publishing one plugin-defined event, from the plugin's side of the socket.
///
/// The host does the dispatching. What happens here is the encoding, the wait,
/// and the replay: the mutations that come back are applied to the values the
/// author published, and handed to the event so its own fields end up where the
/// dispatch got to. That last step is what makes a cross-process, cross-language
/// dispatch read like an in-process listener.
final class Emissions {

    private Emissions() {
    }

    static boolean publish(String pluginId, EventBindings bindings, Emitter emitter,
            Object event) {
        if (event == null) {
            throw new IllegalArgumentException("no event to emit");
        }
        Published published = describe(event);
        String type = published.type();
        Integer typeId = bindings == null ? null : bindings.id(type);
        if (typeId == null) {
            // The host builds the table from the manifests it scanned, so a
            // missing name means this plugin's own manifest never declared the
            // event. Naming the file is what makes that actionable.
            throw new IllegalArgumentException(type
                    + " is not declared in this plugin's plugin.toml under [[events.provides]]");
        }
        if (emitter == null) {
            throw new IllegalStateException("this runtime has no connection to publish " + type);
        }

        List<Value> fields = List.copyOf(published.fields());
        Emitted answer;
        try {
            answer = emitter.emit(Emit.newBuilder()
                    .setPluginId(pluginId)
                    .setTypeId(typeId)
                    .addAllFields(EventCodec.wire(fields))
                    .build());
        } catch (IOException lost) {
            throw new IllegalStateException("the host went away while publishing " + type, lost);
        }
        if (!answer.getError().isEmpty()) {
            throw new IllegalStateException("the host refused " + type + ": " + answer.getError());
        }
        replay(published, fields, answer.getMutationsList());
        return !answer.getCancelled();
    }

    /// One published event, whichever way its author declared it.
    ///
    /// Two facades, one shape underneath — the same fan-in §07 uses for
    /// commands. @PluginEvent is a shorthand the build expands; CustomEvent is
    /// the same three answers written by hand. Neither reaches the wire
    /// differently, so nothing below this line knows which was used.
    private record Published(String type, List<Value> fields, java.util.function.Consumer<List<Value>> write) {
    }

    private static Published describe(Object event) {
        if (event instanceof CustomEvent custom) {
            return new Published(custom.eventType(), custom.fields(), custom::setFields);
        }
        EventLayout layout = EventLayouts.of(event.getClass());
        if (layout == null) {
            throw new IllegalArgumentException(event.getClass().getName()
                    + " is not an event. Annotate it with @PluginEvent and let gocraft-apt write "
                    + "its codec, or implement " + CustomEvent.class.getName() + " by hand");
        }
        return new Published(layout.eventType(), layout.fields(event),
                fields -> layout.setFields(event, fields));
    }

    /// Applies what the subscribers changed to the values that were published.
    ///
    /// The host answered with the mutations precisely so the emitter's own
    /// object can be updated without shipping the whole event back. A mutation
    /// this end cannot apply is a disagreement about the layout, which is worth
    /// stopping for: the alternative is an object that silently keeps the value
    /// a subscriber replaced.
    private static void replay(Published event, List<Value> fields, List<Mutation> mutations) {
        if (mutations.isEmpty()) {
            return;
        }
        List<Value> updated = fields;
        for (Mutation mutation : mutations) {
            List<Integer> path = new ArrayList<>(mutation.getPathCount());
            for (int index = 0; index < mutation.getPathCount(); index++) {
                path.add(mutation.getPath(index));
            }
            updated = ValuePaths.apply(updated, path, EventCodec.value(mutation.getValue()));
        }
        event.write().accept(List.copyOf(updated));
    }
}