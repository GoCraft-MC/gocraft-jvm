package fr.gocraft.api;

import java.util.Arrays;

/// Builders for the values an effect carries.
///
/// Generated event classes call these, which is why they are public. A plugin
/// uses the verb on the noun it happens to — `player.sendMessage(...)` — and never
/// needs to name a Value at all.
public final class Values {

    private Values() {
    }

    public static Value text(String value) {
        return new Value.Text(value);
    }

    public static Value number(long value) {
        return new Value.Int(value);
    }

    public static Value decimal(double value) {
        return new Value.Decimal(value);
    }

    public static Value flag(boolean value) {
        return new Value.Bool(value);
    }

    public static Value bytes(byte[] value) {
        return new Value.Bytes(value);
    }

    public static Value list(Value... values) {
        return new Value.List(Arrays.asList(values));
    }
}
