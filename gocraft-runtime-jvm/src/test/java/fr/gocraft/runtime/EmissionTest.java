package fr.gocraft.runtime;

import fr.gocraft.abi.v1.EventBinding;
import fr.gocraft.api.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The two pieces of publishing that can be wrong on their own: the id table
/// the host sends with LOAD, and applying what came back to the values that
/// went out.
class EmissionTest {

    /// §10's event: a player, a fixed list of tiers each carrying a price, and
    /// a price of its own.
    private static List<Value> purchase() {
        return List.of(
                new Value.Text("oreo"),
                new Value.List(List.of(
                        new Value.List(List.of(new Value.Text("gold"), new Value.Decimal(19.99))),
                        new Value.List(List.of(new Value.Text("iron"), new Value.Decimal(4.50))))),
                new Value.Decimal(1500));
    }

    @Test
    void replacesAField() {
        List<Value> updated = ValuePaths.apply(purchase(), List.of(2), new Value.Decimal(1200));
        assertEquals(new Value.Decimal(1200), updated.get(2));
    }

    /// The deep write a Lua or Java subscriber makes with `e.tiers[0].price`,
    /// against a field the manifest declares immutable. A fixed list of mutable
    /// records is the common case, not an exotic one.
    @Test
    void reachesInsideAList() {
        List<Value> updated = ValuePaths.apply(purchase(), List.of(1, 0, 1), new Value.Decimal(15.99));
        Value.List tiers = (Value.List) updated.get(1);
        Value.List first = (Value.List) tiers.at(0);
        Value.List second = (Value.List) tiers.at(1);
        assertEquals(new Value.Decimal(15.99), first.at(1));
        assertEquals(new Value.Decimal(4.50), second.at(1), "a sibling was disturbed");
    }

    @Test
    void neverTouchesTheInput() {
        List<Value> before = purchase();
        ValuePaths.apply(before, List.of(1, 0, 1), new Value.Decimal(15.99));
        assertEquals(purchase(), before, "the published values were modified in place");
    }

    @Test
    void refusesWhatItCannotWrite() {
        assertThrows(IllegalArgumentException.class,
                () -> ValuePaths.apply(purchase(), List.of(), new Value.Decimal(1)),
                "a mutation with no path");
        assertThrows(IllegalArgumentException.class,
                () -> ValuePaths.apply(purchase(), List.of(3), new Value.Decimal(1)),
                "an index past the declared layout");
        assertThrows(IllegalArgumentException.class,
                () -> ValuePaths.apply(purchase(), List.of(1, 7), new Value.Decimal(1)),
                "an index past the end of a nested list");
        assertThrows(IllegalArgumentException.class,
                () -> ValuePaths.apply(purchase(), List.of(2, 0), new Value.Decimal(1)),
                "a path descending into a scalar");
    }

    @Test
    void readsTheHostsIdTable() {
        EventBindings bindings = EventBindings.of(List.of(
                EventBinding.newBuilder().setTypeId(3).setType("fr.oreo.shop/purchase").build(),
                EventBinding.newBuilder().setTypeId(4).setType("fr.oreo.shop/refund").build()));
        assertEquals(Integer.valueOf(3), bindings.id("fr.oreo.shop/purchase"));
        assertEquals(Integer.valueOf(4), bindings.id("fr.oreo.shop/refund"));
        assertNull(bindings.id("fr.oreo.shop/unknown"));
    }

    /// Zero is what abi/v1 puts in Event.type_id for a native event, so an
    /// entry carrying it could not be told from one.
    @Test
    void dropsAnUnusableEntryWithoutFailingTheLoad() {
        EventBindings bindings = EventBindings.of(List.of(
                EventBinding.newBuilder().setTypeId(0).setType("fr.oreo.shop/purchase").build(),
                EventBinding.newBuilder().setTypeId(5).setType("").build(),
                EventBinding.newBuilder().setTypeId(6).setType("fr.oreo.shop/refund").build()));
        assertNull(bindings.id("fr.oreo.shop/purchase"));
        assertEquals(Integer.valueOf(6), bindings.id("fr.oreo.shop/refund"));
    }

    @Test
    void aPluginWithNoDeclaredEventsHasAnEmptyTable() {
        assertNull(EventBindings.of(List.of()).id("fr.oreo.shop/purchase"));
    }

    /// The message has to name the file the author must edit. Blaming the host
    /// would send them looking in the wrong place.
    @Test
    void refusesAnEventTheManifestNeverDeclared() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Emissions.publish("fr.oreo.shop", EventBindings.of(List.of()), null,
                        new TestEvent()));
        assertTrue(refused.getMessage().contains("events.provides"),
                "the refusal did not name the manifest section: " + refused.getMessage());
    }

    /// The event, and beside it the codec gocraft-apt would have written for
    /// it.
    ///
    /// Written by hand here because the processor runs in another module, and
    /// nested on purpose: the runtime resolves a codec by appending "Layout" to
    /// the event's binary name, so TestEvent nested here is answered by
    /// TestEventLayout nested here. A test that could not stand in for the
    /// generated class would only be testing itself.
    static final class TestEvent {
    }

    static final class TestEventLayout implements fr.gocraft.api.CustomEvent {
        @Override
        public String eventType() {
            return "fr.oreo.shop/purchase";
        }

        @Override
        public boolean cancellable() {
            return true;
        }

        @Override
        public List<Value> fields(Object event) {
            return purchase();
        }

        @Override
        public void setFields(Object event, List<Value> fields) {
        }

        @Override
        public Object create(List<Value> fields, fr.gocraft.api.EffectSink sink) {
            return new TestEvent();
        }
    }
}