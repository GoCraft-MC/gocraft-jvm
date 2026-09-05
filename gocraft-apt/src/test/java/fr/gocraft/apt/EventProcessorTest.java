package fr.gocraft.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/// What @PluginEvent means, proved by compiling it.
///
/// Every valid case here also proves the generated codec is code: javac
/// compiles what the processor emitted in the same task, so an emitter that
/// produced something unparsable fails here rather than in the first plugin
/// that publishes an event.
class EventProcessorTest {

    private static final String PROCESSOR = EventProcessor.class.getName();

    /// §10's event, with the two things the layout is derived from: declaration
    /// order, and final.
    private static final String PURCHASE = """
            import fr.gocraft.api.PluginEvent;

            @PluginEvent(value = "fr.oreo.shop/purchase", cancellable = true)
            public final class PurchaseEvent {

                private static final int VERSION = 1;

                private final String player;
                private int quantity;
                private double price;
                private final byte[] token;

                public PurchaseEvent(String player, int quantity, double price, byte[] token) {
                    this.player = player;
                    this.quantity = quantity;
                    this.price = price;
                    this.token = token;
                }

                public String player() { return player; }
                public int quantity() { return quantity; }
                public double price() { return price; }
                public byte[] token() { return token; }

                public void setQuantity(int quantity) { this.quantity = quantity; }
                public void setPrice(double price) { this.price = price; }
            }
            """;

    @Test
    void derivesTheLayoutFromDeclarationOrder() throws IOException {
        Javac.Result result = Javac.compile("PurchaseEvent", PURCHASE, PROCESSOR);
        assertEquals("", result.firstError());

        String codec = result.source("PurchaseEventLayout");
        assertTrue(codec.contains("return \"fr.oreo.shop/purchase\";"), codec);
        int player = codec.indexOf("target.player()");
        int quantity = codec.indexOf("target.quantity()");
        int price = codec.indexOf("target.price()");
        int token = codec.indexOf("target.token()");
        assertTrue(player < quantity && quantity < price && price < token,
                "the wire order is not the declaration order:\n" + codec);
    }

    /// A constant has the same value in every instance, so carrying it would be
    /// paying per emission for something the subscriber already has.
    @Test
    void leavesStaticFieldsOutOfTheEvent() throws IOException {
        Javac.Result result = Javac.compile("PurchaseEvent", PURCHASE, PROCESSOR);
        assertFalse(result.source("PurchaseEventLayout").contains("VERSION"),
                "a static field reached the wire");
    }

    /// final is how an author says read-only, so nothing writes those back —
    /// the host refused any mutation against them before the codec was called.
    @Test
    void writesBackOnlyWhatIsNotFinal() throws IOException {
        String codec = Javac.compile("PurchaseEvent", PURCHASE, PROCESSOR)
                .source("PurchaseEventLayout");
        assertTrue(codec.contains("setQuantity"), codec);
        assertTrue(codec.contains("setPrice"), codec);
        assertFalse(codec.contains("setPlayer"), "a final field was written back");
        assertFalse(codec.contains("setToken"), "a final field was written back");
    }

    /// A Value.Int carries a long. A field that holds an int has to be narrowed
    /// on the way back in, or the codec would not compile — which is how this
    /// test would fail if the cast were dropped.
    @Test
    void narrowsToTheFieldsOwnType() throws IOException {
        String codec = Javac.compile("PurchaseEvent", PURCHASE, PROCESSOR)
                .source("PurchaseEventLayout");
        assertTrue(codec.contains("setQuantity((int) quantity)"), codec);
        assertTrue(codec.contains("setPrice(price)"), "a double needed no cast:\n" + codec);
    }

    @Test
    void refusesATypeThatWouldShadowANativeEvent() throws IOException {
        Javac.Result result = Javac.compile("BlockBreak", """
                import fr.gocraft.api.PluginEvent;

                @PluginEvent("block.break")
                public final class BlockBreak {
                }
                """, PROCESSOR);
        assertTrue(result.firstError().contains("namespace/name"), result.firstError());
    }

    @Test
    void refusesAMutableFieldWithNoSetter() throws IOException {
        Javac.Result result = Javac.compile("Loose", """
                import fr.gocraft.api.PluginEvent;

                @PluginEvent("fr.oreo.shop/loose")
                public final class Loose {
                    private double price;
                    public double price() { return price; }
                }
                """, PROCESSOR);
        assertTrue(result.firstError().contains("setPrice"), result.firstError());
    }

    @Test
    void refusesAFieldWithNoAccessor() throws IOException {
        Javac.Result result = Javac.compile("Hidden", """
                import fr.gocraft.api.PluginEvent;

                @PluginEvent("fr.oreo.shop/hidden")
                public final class Hidden {
                    private final int secret = 1;
                }
                """, PROCESSOR);
        assertTrue(result.firstError().contains("no accessor"), result.firstError());
    }

    /// The wire has no null, so a boxed field would have to become a zero, an
    /// absent field or a refusal. Choosing silently is how a subscriber reads a
    /// price nobody set.
    @Test
    void refusesATypeItCannotCarry() throws IOException {
        Javac.Result result = Javac.compile("Rich", """
                import fr.gocraft.api.PluginEvent;
                import java.util.List;

                @PluginEvent("fr.oreo.shop/rich")
                public final class Rich {
                    private final List<String> tiers = List.of();
                    public List<String> tiers() { return tiers; }
                }
                """, PROCESSOR);
        assertTrue(result.firstError().contains("an event cannot carry"), result.firstError());
    }

    /// The codec is resolved by name in the same package, so a nested class
    /// would need one no top-level file can carry.
    @Test
    void refusesANestedEvent() throws IOException {
        Javac.Result result = Javac.compile("Outer", """
                import fr.gocraft.api.PluginEvent;

                public final class Outer {
                    @PluginEvent("fr.oreo.shop/inner")
                    public static final class Inner {
                    }
                }
                """, PROCESSOR);
        assertTrue(result.firstError().contains("top-level"), result.firstError());
    }

    /// An event that carries nothing is a notification, and a perfectly good
    /// one. The codec still has to compile.
    @Test
    void acceptsAnEventThatCarriesNothing() throws IOException {
        Javac.Result result = Javac.compile("Opened", """
                import fr.gocraft.api.PluginEvent;

                @PluginEvent("fr.oreo.shop/opened")
                public final class Opened {
                }
                """, PROCESSOR);
        assertEquals("", result.firstError());
        String codec = result.source("OpenedLayout");
        assertTrue(codec.contains("return List.of();"), codec);
        assertTrue(codec.contains("carries nothing"), codec);
    }
}