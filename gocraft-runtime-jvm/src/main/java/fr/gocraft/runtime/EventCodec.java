package fr.gocraft.runtime;

import fr.gocraft.abi.v1.HostCall;
import fr.gocraft.abi.v1.Mutation;
import fr.gocraft.abi.v1.Value;
import fr.gocraft.abi.v1.ValueList;
import fr.gocraft.abi.v1.Verdict;
import fr.gocraft.api.Event;

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

    /// Builds the answer: what the handlers decided, and everything they asked
    /// for, in one message.
    ///
    /// Effects are batched rather than sent as they happen, which is what keeps
    /// one event to one round trip however much a handler does. Mutations are
    /// not produced yet — nothing in the API offers them.
    static Verdict verdict(Event event) {
        Verdict.Builder verdict = Verdict.newBuilder().setCancelled(event.cancelled());
        for (Event.Effect effect : event.effects()) {
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

    /// Mutations exist in the schema and nothing produces one yet. Kept named so
    /// the day something does, it is obvious where it belongs.
    static List<Mutation> mutations() {
        return List.of();
    }
}