package fr.gocraft.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final EffectSink sink;

    /// Creates an event-owned working copy of the positional payload.
    ///
    /// The runtime keeps the incoming list as its before-dispatch baseline.
    /// Aliasing even a mutable input list would make setters change that
    /// baseline too, hiding mutations when the verdict is computed. Copying
    /// also allows callers to supply an immutable list. A shallow copy suffices:
    /// Value records protect their nested lists and byte arrays from mutation.
    ///
    /// @param type the schema's event name
    /// @param fields incoming values, retained unchanged by the caller
    /// @param permissions permission answers resolved by the host
    /// @param sink the per-dispatch effect collector
    protected Event(String type, List<Value> fields, Map<String, Boolean> permissions,
            EffectSink sink) {
        this.type = type;
        this.fields = new ArrayList<>(fields);
        this.permissions = Map.copyOf(permissions);
        this.sink = sink;
    }

    /// @return the event name used by the ABI and plugin subscriptions
    public final String type() {
        return type;
    }

    // ── For generated subclasses ──────────────────────────────────────────────
    //
    // Protected rather than public: these are the positional payload, and a
    // plugin reaching past its named accessors would be depending on a layout
    // the schema is free to extend.

    /// Reads a position without exposing the working list itself.
    ///
    /// @param index the zero-based ABI field position
    /// @return the value, or null when this payload has no such position
    protected final Value field(int index) {
        return index >= 0 && index < fields.size() ? fields.get(index) : null;
    }

    /// Replaces one value in this dispatch's working copy.
    ///
    /// Generated accessors expose this only for schema-declared mutable fields.
    /// Later handlers see the replacement; the runtime returns the diff and the
    /// host validates the mutation before applying the original action.
    ///
    /// @param index the zero-based ABI field position
    /// @param value the non-null replacement value
    /// @throws IndexOutOfBoundsException if the field position is invalid
    /// @throws NullPointerException if the replacement is null
    protected final void field(int index, Value value) {
        fields.set(index, Objects.requireNonNull(value));
    }

    /// Captures the current values for the runtime's native mutation diff.
    ///
    /// This immutable list is independent of later setter calls. It is a
    /// serialization snapshot, not another plugin-facing mutation API.
    ///
    /// @return the positional values at the time of this call
    public final List<Value> snapshotFields() {
        return List.copyOf(fields);
    }

    /// @param index the zero-based ABI field position
    /// @return the text value, or an empty string for a missing or different kind
    protected final String text(int index) {
        return field(index) instanceof Value.Text(String value) ? value : "";
    }

    /// @param index the zero-based ABI field position
    /// @return the integer value, or zero for a missing or different kind
    protected final long number(int index) {
        return field(index) instanceof Value.Int(long value) ? value : 0L;
    }

    /// @param index the zero-based ABI field position
    /// @return the decimal value, or zero for a missing or different kind
    protected final double decimal(int index) {
        return field(index) instanceof Value.Decimal(double value) ? value : 0d;
    }

    /// @param index the zero-based ABI field position
    /// @return the boolean value, or false for a missing or different kind
    protected final boolean flag(int index) {
        return field(index) instanceof Value.Bool(boolean value) && value;
    }

    /// @param index the zero-based ABI field position
    /// @return a byte-array copy, or an empty array for a missing or different kind
    protected final byte[] bytes(int index) {
        return field(index) instanceof Value.Bytes(byte[] value) ? value : new byte[0];
    }

    /// Already resolved by the host, for every node some manifest subscribed
    /// to. A node nobody declared reads false, because the host was never asked
    /// about it — a manifest bug rather than a denial.
    ///
    /// @param node the permission name declared by the subscription
    /// @return the host's answer, or false if the node was not provided
    protected final boolean permission(String node) {
        return permissions.getOrDefault(node, false);
    }

    /// Where the handles in this payload send what they are asked to do.
    ///
    /// Protected and named rather than public: it is what a generated accessor
    /// hands a [PlayerRef] as it decodes one, so the handle can act without
    /// being given a channel. A plugin reaches effects through the noun they
    /// happen to, never through this.
    ///
    /// @return the collector bound to this event's player handles
    protected final EffectSink sink() {
        return sink;
    }

    // ── For the runtime ───────────────────────────────────────────────────────

    @Override
    public final String toString() {
        return getClass().getSimpleName() + "[" + type + "]";
    }
}
