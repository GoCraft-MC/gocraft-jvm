package fr.gocraft.api;

import fr.gocraft.api.event.PlayerChatEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventSnapshotTest {
    private static List<Value> fields() {
        return List.of(new Value.List(List.of()), new Value.Text("original"),
                new Value.List(List.of()));
    }

    @Test
    void setterDoesNotOverwriteTheRuntimeBaseline() {
        List<Value> baseline = new ArrayList<>(fields());
        PlayerChatEvent event = new PlayerChatEvent(baseline, (call, values) -> {});
        List<Value> snapshot = event.snapshotFields();

        event.setMessage("rewritten");

        assertEquals(new Value.Text("original"), baseline.get(1));
        assertEquals(new Value.Text("original"), snapshot.get(1));
        assertEquals("rewritten", event.message());
        baseline.set(1, new Value.Text("external edit"));
        assertEquals("rewritten", event.message());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.set(1, new Value.Text("invalid")));
    }

    @Test
    void immutableInputStillAllowsSchemaDeclaredMutation() {
        List<Value> baseline = fields();
        PlayerChatEvent event = new PlayerChatEvent(baseline, (call, values) -> {});
        event.setMessage("rewritten");
        assertEquals("rewritten", event.message());
        assertEquals(new Value.Text("original"), baseline.get(1));
    }
}
