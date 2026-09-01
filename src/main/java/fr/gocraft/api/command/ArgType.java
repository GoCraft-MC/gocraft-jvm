package fr.gocraft.api.command;

import java.util.List;
import java.util.Set;

/// What one command argument accepts.
///
/// A closed set, and deliberately so. The tree is data that crosses to the host
/// and is rendered by every edition, so an argument type nobody can render is
/// not an argument type — §07 calls the set integer, decimal, string, greedy,
/// player, block_pos, block_state, item, duration, enum and custom, and this is
/// it.
///
/// Because it is closed, the host degrades at the boundary rather than the
/// plugin doing it: a client protocol that cannot render `block_state` gets a
/// string with server-side completion, and the plugin never learns that its
/// argument arrived by a different route on one edition than on another.
///
/// The bounds travel with the type instead of sitting beside it, so a range on
/// something that has no range cannot be expressed at all.
public sealed interface ArgType {

    /// Kind is what the wire carries. Everything else here is a constraint on
    /// it, and constraints are what the two renderers read to build completion.
    enum Kind {
        INTEGER, DECIMAL, STRING, GREEDY, PLAYER,
        BLOCK_POS, BLOCK_STATE, ITEM, DURATION, ENUM, CUSTOM
    }

    Kind kind();

    /// A whole number, optionally bounded. Null means unbounded on that side.
    record Integer(Long minimum, Long maximum) implements ArgType {
        public Integer {
            if (minimum != null && maximum != null && minimum > maximum) {
                throw new IllegalArgumentException(
                        "integer minimum " + minimum + " exceeds maximum " + maximum);
            }
        }

        @Override
        public Kind kind() {
            return Kind.INTEGER;
        }
    }

    record Decimal(Double minimum, Double maximum) implements ArgType {
        public Decimal {
            if (minimum != null && Double.isNaN(minimum) || maximum != null && Double.isNaN(maximum)) {
                throw new IllegalArgumentException("decimal range contains NaN");
            }
            if (minimum != null && maximum != null && minimum > maximum) {
                throw new IllegalArgumentException(
                        "decimal minimum " + minimum + " exceeds maximum " + maximum);
            }
        }

        @Override
        public Kind kind() {
            return Kind.DECIMAL;
        }
    }

    /// One of a fixed list of words.
    ///
    /// The options are part of the tree rather than checked by the handler,
    /// because a client completing them is the whole point of declaring them.
    record Enumeration(List<String> options) implements ArgType {
        public Enumeration {
            options = List.copyOf(options);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("an enum argument has no values");
            }
            if (options.size() != Set.copyOf(options).size()) {
                throw new IllegalArgumentException("an enum argument has duplicate values " + options);
            }
        }

        @Override
        public Kind kind() {
            return Kind.ENUM;
        }
    }

    /// A type the plugin resolves itself, named so its resolver can be found.
    ///
    /// On the wire it degrades to a single word, and the plugin turns that word
    /// into whatever it meant before the handler runs — §07's home argument.
    record Custom(String typeId) implements ArgType {
        public Custom {
            if (typeId == null || typeId.isBlank()) {
                throw new IllegalArgumentException("a custom argument has no type id");
            }
        }

        @Override
        public Kind kind() {
            return Kind.CUSTOM;
        }
    }

    /// Everything with no constraint to carry.
    record Simple(Kind kind) implements ArgType {
        public Simple {
            switch (kind) {
                case STRING, GREEDY, PLAYER, BLOCK_POS, BLOCK_STATE, ITEM, DURATION -> {
                }
                default -> throw new IllegalArgumentException(kind + " carries constraints of its own");
            }
        }
    }

    static ArgType integer() {
        return new Integer(null, null);
    }

    static ArgType integer(long minimum, long maximum) {
        return new Integer(minimum, maximum);
    }

    static ArgType decimal() {
        return new Decimal(null, null);
    }

    static ArgType decimal(double minimum, double maximum) {
        return new Decimal(minimum, maximum);
    }

    /// One word.
    static ArgType string() {
        return new Simple(Kind.STRING);
    }

    /// The rest of the line. Nothing may follow it.
    static ArgType greedy() {
        return new Simple(Kind.GREEDY);
    }

    static ArgType player() {
        return new Simple(Kind.PLAYER);
    }

    static ArgType blockPos() {
        return new Simple(Kind.BLOCK_POS);
    }

    static ArgType blockState() {
        return new Simple(Kind.BLOCK_STATE);
    }

    static ArgType item() {
        return new Simple(Kind.ITEM);
    }

    static ArgType duration() {
        return new Simple(Kind.DURATION);
    }

    static ArgType oneOf(String... options) {
        return new Enumeration(List.of(options));
    }

    static ArgType custom(String typeId) {
        return new Custom(typeId);
    }
}
