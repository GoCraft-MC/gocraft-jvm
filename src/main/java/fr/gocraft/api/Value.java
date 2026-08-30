package fr.gocraft.api;

import java.util.Collections;

/// One value inside an event payload.
///
/// A plugin should rarely name this type. The wire format is positional —
/// §20.4, settled, because field names would have to match across every
/// language and renaming one would break every runtime at once — and the
/// generated event classes put named accessors over that, so a handler writes
/// `event.pos()` rather than reaching for an index.
///
/// It is public only because the generated classes and the vocabulary types
/// below have to read it. Treat it as the wire, not the API.
public sealed interface Value {

    record Bool(boolean value) implements Value {
    }

    record Int(long value) implements Value {
    }

    record Decimal(double value) implements Value {
    }

    record Text(String value) implements Value {
    }

    /// Copied in and out. A byte array is the one mutable thing that crosses
    /// this boundary, and a handler editing one in place would be editing an
    /// event the next subscriber has not seen yet.
    record Bytes(byte[] value) implements Value {
        public Bytes {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    record List(java.util.List<Value> values) implements Value {
        public List {
            values = Collections.unmodifiableList(java.util.List.copyOf(values));
        }

        /// The value at a position, or null past the end.
        ///
        /// Null rather than an exception: a vocabulary type may gain a field,
        /// and a runtime built against the older shape should keep reading the
        /// fields it knows rather than throwing on one it does not.
        public Value at(int index) {
            return index >= 0 && index < values.size() ? values.get(index) : null;
        }

        public int size() {
            return values.size();
        }
    }
}