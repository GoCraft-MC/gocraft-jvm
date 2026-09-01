package fr.gocraft.api.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/// A constraint travels with the type it constrains, so a range on something
/// with no range cannot be written down at all.
class ArgTypeTest {

    @Test
    void carriesItsBounds() {
        ArgType.Integer count = (ArgType.Integer) ArgType.integer(1, 64);
        assertEquals(1L, count.minimum());
        assertEquals(64L, count.maximum());
        assertEquals(ArgType.Kind.INTEGER, count.kind());

        ArgType.Integer unbounded = (ArgType.Integer) ArgType.integer();
        assertEquals(null, unbounded.minimum());
    }

    @Test
    void refusesAnImpossibleRange() {
        assertThrows(IllegalArgumentException.class, () -> ArgType.integer(64, 1));
        assertThrows(IllegalArgumentException.class, () -> ArgType.decimal(1, 0));
        assertThrows(IllegalArgumentException.class, () -> ArgType.decimal(Double.NaN, 1));
    }

    @Test
    void refusesAnEnumThatCompletesNothing() {
        assertThrows(IllegalArgumentException.class, ArgType::oneOf);
        assertThrows(IllegalArgumentException.class, () -> ArgType.oneOf("red", "red"));
    }

    @Test
    void refusesACustomTypeWithNoResolverToFind() {
        assertThrows(IllegalArgumentException.class, () -> ArgType.custom(" "));
    }

    /// The simple kinds are the ones with nothing to carry. Building one for a
    /// kind that does have constraints would drop them silently.
    @Test
    void refusesASimpleKindThatHasConstraints() {
        assertThrows(IllegalArgumentException.class, () -> new ArgType.Simple(ArgType.Kind.INTEGER));
        assertThrows(IllegalArgumentException.class, () -> new ArgType.Simple(ArgType.Kind.ENUM));
        assertEquals(ArgType.Kind.GREEDY, ArgType.greedy().kind());
    }
}
