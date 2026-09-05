package fr.gocraft.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// What every generated event extends.
///
/// It holds the positional payload and keeps it out of sight: a handler reads
/// `event.pos()`, never `event.field(1)`. That indirection is the point —
/// because nothing outside the generated subclass names an index, the
/// serialization can change without touching a plugin.
///
/// **A cancellable event blocks the tick**, under one budget shared by every
/// subscriber rather than one each. Work that decides the outcome belongs in a
/// handler and should be quick; anything else belongs somewhere the server is
/// not waiting.
///
/// Refusing what an event announced is [EventControl], asked for as a second
/// parameter, and not a method here. A plugin-defined event is a class its
/// author wrote and nothing can add a method to it, so one mechanism serves
/// both rather than two that differ by who wrote the event.
///
/// An instance is handed to one subscriber at a time and is not thread-safe. Do
/// not keep it: the fields are a snapshot, and the server has moved on by the
/// time the handler returns.
public abstract class Event {

    private final String type;
    private final List<Value> fields;
    private final Map<String, Boolean> permissions;
    private final List<Effect> effects = new ArrayList<>();

    protected Event(String type, List<Value> fields, Map<String, Boolean> permissions) {
        this.type = type;
        this.fields = List.copyOf(fields);
        this.permissions = Map.copyOf(permissions);
    }

    public final String type() {
        return type;
    }

    /// One side effect a handler asked for, carried back in the verdict.
    public record Effect(String call, List<Value> values) {
        public Effect {
            values = List.copyOf(values);
        }
    }

    // ── For generated subclasses ──────────────────────────────────────────────
    //
    // Protected rather than public: these are the positional payload, and a
    // plugin reaching past its named accessors would be depending on a layout
    // the schema is free to extend.

    protected final Value field(int index) {
        return index >= 0 && index < fields.size() ? fields.get(index) : null;
    }

    protected final String text(int index) {
        return field(index) instanceof Value.Text(String value) ? value : "";
    }

    protected final long number(int index) {
        return field(index) instanceof Value.Int(long value) ? value : 0L;
    }

    protected final double decimal(int index) {
        return field(index) instanceof Value.Decimal(double value) ? value : 0d;
    }

    protected final boolean flag(int index) {
        return field(index) instanceof Value.Bool(boolean value) && value;
    }

    protected final byte[] bytes(int index) {
        return field(index) instanceof Value.Bytes(byte[] value) ? value : new byte[0];
    }

    /// Already resolved by the host, for every node some manifest subscribed
    /// to. A node nobody declared reads false, because the host was never asked
    /// about it — a manifest bug rather than a denial.
    protected final boolean permission(String node) {
        return permissions.getOrDefault(node, false);
    }

    /// Records a side effect. They accumulate and travel back together in the
    /// verdict, which is what keeps one event to one round trip however much a
    /// handler asks for.
    protected final void effect(String call, Value... values) {
        effects.add(new Effect(call, List.of(values)));
    }

    // ── For the runtime ───────────────────────────────────────────────────────

    public final List<Effect> effects() {
        return List.copyOf(effects);
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + "[" + type + "]";
    }
}