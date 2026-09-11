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

    /// Initializes mutation construction and encoding without plugin callbacks.
    ///
    /// An unchanged shaped payload exercises decoding and diff traversal, but
    /// never constructs a Mutation or serializes a replacement value. These
    /// local string/decimal changes cover those paths for native mutable fields.
    /// The resulting bytes are discarded: neither the warmed event nor its
    /// response gains synthetic mutations, cancellation or effects.
    /// Plugin-owned first-use initialization still belongs to the first handler
    /// call and may require the host's existing bounded cold-start grace.
    static void warmMutationEncoding() {
        var before = List.<fr.gocraft.api.Value>of(new fr.gocraft.api.Value.Text(""), new fr.gocraft.api.Value.Decimal(0));
        var after = List.<fr.gocraft.api.Value>of(new fr.gocraft.api.Value.Text("warm"), new fr.gocraft.api.Value.Decimal(1));
        verdict(new Control(), changes(before, after)).toByteArray();
    }

    /// Decodes the incoming payload used as the before-dispatch baseline.
    ///
    /// Native Event instances copy this list before allowing setter calls;
    /// mutating the baseline itself would hide changes from [#changes].
    ///
    /// @param wire positional values received from the host
    /// @return a decoded list that dispatch retains unchanged for comparison
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

    /// What the handlers changed on a native or plugin-defined event, as a
    /// positional diff over an unchanged baseline and the final snapshot.
    ///
    /// Compared rather than recorded, because on this side the handler holds
    /// its own typed object and writes through its own setters: there is
    /// nowhere to hook a recorder without making an author call one. The Go
    /// custom-event path records positional writes; its typed native path
    /// compares mutable fields. Both use the same Mutation transport.
    ///
    /// As deep as the change went, which is not a refinement but a requirement.
    /// The host authorises a write by the depth of its path — MutablePath
    /// answers a length-one path from the field's own mutability and anything
    /// deeper from the field existing at all — so emitting a whole-field
    /// mutation for a record changed inside an immutable list gets it refused,
    /// while the same author code running on the Go side, which walks in, gets
    /// it applied. The rule belongs to the contract; abi.Diff states it, and
    /// this is its second reading.
    ///
    /// A byte[] is compared by content, at any depth. Value.Bytes is a record,
    /// so its equals is the array's — identity — and that reaches further than
    /// it looks: a PlayerRef travels as a list whose first element is one, so a
    /// field nobody touched was reported as changed on every single dispatch,
    /// and the host logged a write to a read-only field for it.
    ///
    /// @param before the unmodified incoming values
    /// @param after the values after handler execution
    /// @return positional mutations; the host still enforces write permissions
    static List<Mutation> changes(List<fr.gocraft.api.Value> before,
            List<fr.gocraft.api.Value> after) {
        List<Mutation> mutations = new ArrayList<>();
        changesInto(mutations, List.of(), before, after);
        return mutations;
    }

    private static void changesInto(List<Mutation> mutations, List<Integer> path,
            List<fr.gocraft.api.Value> before, List<fr.gocraft.api.Value> after) {
        if (before.size() != after.size()) {
            return;
        }
        for (int index = 0; index < before.size(); index++) {
            fr.gocraft.api.Value left = before.get(index);
            fr.gocraft.api.Value right = after.get(index);
            // Lists first, and without asking same(): it would walk the
            // children to answer and the recursion walks them again.
            if (left instanceof fr.gocraft.api.Value.List(List<fr.gocraft.api.Value> from)
                    && right instanceof fr.gocraft.api.Value.List(List<fr.gocraft.api.Value> to)
                    && from.size() == to.size()) {
                List<Integer> deeper = new ArrayList<>(path);
                deeper.add(index);
                changesInto(mutations, deeper, from, to);
                continue;
            }
            if (same(left, right)) {
                continue;
            }
            Mutation.Builder mutation = Mutation.newBuilder();
            for (Integer step : path) {
                mutation.addPath(step);
            }
            mutations.add(mutation.addPath(index).setValue(wire(right)).build());
        }
    }

    private static boolean same(fr.gocraft.api.Value before, fr.gocraft.api.Value after) {
        if (before instanceof fr.gocraft.api.Value.Bytes(byte[] left)
                && after instanceof fr.gocraft.api.Value.Bytes(byte[] right)) {
            return java.util.Arrays.equals(left, right);
        }
        // Recursive, because a record and a PlayerRef both travel as lists and
        // either may hold bytes somewhere inside.
        if (before instanceof fr.gocraft.api.Value.List(List<fr.gocraft.api.Value> left)
                && after instanceof fr.gocraft.api.Value.List(List<fr.gocraft.api.Value> right)) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!same(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return before.equals(after);
    }
}
