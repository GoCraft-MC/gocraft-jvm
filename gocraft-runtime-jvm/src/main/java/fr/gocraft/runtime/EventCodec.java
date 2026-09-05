package fr.gocraft.runtime;

import fr.gocraft.abi.v1.HostCall;
import fr.gocraft.abi.v1.Mutation;
import fr.gocraft.abi.v1.Value;
import fr.gocraft.abi.v1.ValueList;
import fr.gocraft.abi.v1.Verdict;

import java.util.ArrayList;
import java.util.List;

/// Converts between the wire types and the ones a plugin sees.
///
/// The generated protobuf classes stop here. Everything above works on
/// fr.gocraft.api types, so the serialization can change without a plugin
/// noticing — which is the whole reason the generated event classes keep their
/// payload private.
final class EventCodec {

    private EventCodec() {
    }

    static List<fr.gocraft.api.Value> fields(List<Value> wire) {
        List<fr.gocraft.api.Value> read = new ArrayList<>(wire.size());
        for (Value value : wire) {
            read.add(value(value));
        }
        return read;
    }

    /// One value, in. Package-visible because commands read the same
    /// shapes events do, and a second reader would be a second definition
    /// of the vocabulary types free to drift from this one.
    static fr.gocraft.api.Value value(Value wire) {
        return switch (wire.getKindCase()) {
            case BOOL_VALUE -> new fr.gocraft.api.Value.Bool(wire.getBoolValue());
            case INT64_VALUE -> new fr.gocraft.api.Value.Int(wire.getInt64Value());
            case DOUBLE_VALUE -> new fr.gocraft.api.Value.Decimal(wire.getDoubleValue());
            case STRING_VALUE -> new fr.gocraft.api.Value.Text(wire.getStringValue());
            case BYTES_VALUE -> new fr.gocraft.api.Value.Bytes(wire.getBytesValue().toByteArray());
            case LIST_VALUE -> new fr.gocraft.api.Value.List(fields(wire.getListValue().getValuesList()));
            // A value with no kind set is not an empty value: it means the host
            // built a message it never filled in. An empty list is the reading
            // that cannot be mistaken for real data.
            case KIND_NOT_SET -> new fr.gocraft.api.Value.List(List.of());
        };
    }

    /// Everything a dispatch decided, from the one place it accumulated.
    ///
    /// Effects are batched rather than sent as they happen, which keeps one
    /// event to one round trip however much a handler does.
    ///
    /// The control is both the verdict channel and the sink every handle in the
    /// payload was bound to, so there is nothing to merge: a message asked of a
    /// player and a cancellation asked of the control arrive here together, in
    /// the order the handlers asked for them.
    static Verdict verdict(Control control, List<Mutation> mutations) {
        Verdict.Builder verdict = Verdict.newBuilder()
                .setCancelled(control.cancelled())
                .addAllMutations(mutations);
        for (Control.Effect effect : control.seal()) {
            verdict.addEffects(HostCall.newBuilder()
                    .setType(effect.call())
                    .addAllFields(wire(effect.values()))
                    .build());
        }
        return verdict.build();
    }

    public static List<Value> wire(List<fr.gocraft.api.Value> values) {
        List<Value> encoded = new ArrayList<>(values.size());
        for (fr.gocraft.api.Value value : values) {
            encoded.add(wire(value));
        }
        return encoded;
    }

    private static Value wire(fr.gocraft.api.Value value) {
        Value.Builder builder = Value.newBuilder();
        switch (value) {
            case fr.gocraft.api.Value.Bool(boolean flag) -> builder.setBoolValue(flag);
            case fr.gocraft.api.Value.Int(long number) -> builder.setInt64Value(number);
            case fr.gocraft.api.Value.Decimal(double number) -> builder.setDoubleValue(number);
            case fr.gocraft.api.Value.Text(String text) -> builder.setStringValue(text);
            case fr.gocraft.api.Value.Bytes(byte[] raw) ->
                    builder.setBytesValue(com.google.protobuf.ByteString.copyFrom(raw));
            case fr.gocraft.api.Value.List(List<fr.gocraft.api.Value> items) ->
                    builder.setListValue(ValueList.newBuilder().addAllValues(wire(items)));
        }
        return builder.build();
    }

    /// What the handlers changed on a plugin-defined event, as a positional
    /// diff.
    ///
    /// Compared rather than recorded, because on this side the handler holds
    /// its own typed object and writes through its own setters: there is
    /// nowhere to hook a recorder without making an author call one. The Go SDK
    /// records instead, since a subscriber there works positionally already —
    /// the wire carries the same mutations either way, and how they were
    /// produced is each runtime's own business.
    ///
    /// One entry per top-level field, which is exact today: an event carries
    /// primitives, String and byte[], so a change is always a whole field. When
    /// nested values arrive the paths will need to go deeper, and this is where.
    ///
    /// A byte[] is compared by content. Value.Bytes is a record, so its equals
    /// is the array's — identity — and a getter that hands back a defensive copy
    /// would otherwise report a change on every dispatch.
    static List<Mutation> changes(List<fr.gocraft.api.Value> before,
            List<fr.gocraft.api.Value> after) {
        List<Mutation> mutations = new ArrayList<>();
        int shared = Math.min(before.size(), after.size());
        for (int index = 0; index < shared; index++) {
            if (same(before.get(index), after.get(index))) {
                continue;
            }
            mutations.add(Mutation.newBuilder()
                    .addPath(index)
                    .setValue(wire(after.get(index)))
                    .build());
        }
        return mutations;
    }

    private static boolean same(fr.gocraft.api.Value before, fr.gocraft.api.Value after) {
        if (before instanceof fr.gocraft.api.Value.Bytes(byte[] left)
                && after instanceof fr.gocraft.api.Value.Bytes(byte[] right)) {
            return java.util.Arrays.equals(left, right);
        }
        return before.equals(after);
    }
}