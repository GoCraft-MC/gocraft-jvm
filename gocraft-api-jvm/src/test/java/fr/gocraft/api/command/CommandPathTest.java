package fr.gocraft.api.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandPathTest {

    @Test
    void readsLiteralsAndArguments() {
        CommandPath path = CommandPath.parse("sell <price>");
        assertEquals(2, path.segments().size());
        assertTrue(path.segments().get(0) instanceof CommandPath.Word);
        assertTrue(path.segments().get(1) instanceof CommandPath.Slot);
        assertEquals(List.of("price"), path.arguments());
        assertEquals("sell <price>", path.toString());
    }

    @Test
    void readsAPathOfLiteralsOnly() {
        assertEquals(List.of(), CommandPath.parse("admin reload").arguments());
    }

    /// An empty path is the command itself: /shop with nothing after it.
    @Test
    void readsAnEmptyPath() {
        assertTrue(CommandPath.parse("").isEmpty());
        assertTrue(CommandPath.parse("   ").isEmpty());
    }

    @Test
    void toleratesRepeatedSpaces() {
        assertEquals("give <player> <item>", CommandPath.parse("  give   <player>  <item> ").toString());
    }

    @Test
    void refusesAnUnbalancedBracket() {
        assertThrows(IllegalArgumentException.class, () -> CommandPath.parse("sell <price"));
        assertThrows(IllegalArgumentException.class, () -> CommandPath.parse("sell price>"));
    }

    /// Two arguments of one name would leave the handler unable to say which
    /// value it was given.
    @Test
    void refusesTheSameArgumentTwice() {
        assertThrows(IllegalArgumentException.class, () -> CommandPath.parse("pay <amount> <amount>"));
    }

    @Test
    void refusesASegmentAPlayerCouldNotType() {
        assertThrows(IllegalArgumentException.class, () -> CommandPath.parse("sell <>"));
        assertThrows(IllegalArgumentException.class, () -> CommandPath.parse("shop/sell"));
    }
}
