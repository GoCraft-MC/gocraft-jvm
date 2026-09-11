package fr.gocraft.runtime;

import com.google.protobuf.ByteString;
import fr.gocraft.abi.v1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeMutationTest {
    @Test
    void returnsTypedMutationAndCancellationWithoutWarmingHandlers(@TempDir Path directory) throws Exception {
        String source = """
                package test.plugin;
                import fr.gocraft.api.*;
                import fr.gocraft.api.event.PlayerChatEvent;
                public final class ChatPlugin implements Plugin {
                    private final Host host;
                    public ChatPlugin(Host host) { this.host = host; }
                    public void enable() { host.registerListener(new Listener()); }
                    public static final class Listener {
                        private int calls;
                        @Subscribe public void chat(PlayerChatEvent event, EventControl control) {
                            calls++;
                            event.setMessage("rewritten-" + calls);
                            control.cancel();
                        }
                    }
                }
                """;
        Path bundle = TestBundles.bundle(directory, "ChatPlugin", source);
        try (PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")))) {
            Envelope loaded = registry.load(1, Load.newBuilder().setPluginId("test.chat")
                    .setBundlePath(bundle.toString()).setEntry("test.plugin.ChatPlugin").build());
            assertTrue(loaded.hasLoaded(), () -> loaded.getFail().getReason());
            Value player = Value.newBuilder().setListValue(ValueList.newBuilder().addAllValues(List.of(
                    Value.newBuilder().setBytesValue(ByteString.copyFrom(new byte[16])).build(),
                    text("Alex"), text("java")))).build();
            Event event = Event.newBuilder().setType("player.chat").addFields(player)
                    .addFields(text("original"))
                    .addFields(Value.newBuilder().setListValue(ValueList.getDefaultInstance())).build();
            Dispatch request = Dispatch.newBuilder().setPluginId("test.chat").setEvent(event).build();
            Verdict warmed = registry.dispatch(2, request.toBuilder().setWarm(true).build()).getVerdict();
            assertFalse(warmed.getCancelled());
            assertEquals(0, warmed.getMutationsCount());
            assertEquals(0, warmed.getEffectsCount());
            // Serialize the reply again: the assertion is against real wire values.
            Verdict reply = Envelope.parseFrom(registry.dispatch(3, request).toByteArray()).getVerdict();
            assertTrue(reply.getCancelled());
            assertEquals(1, reply.getMutationsCount());
            assertEquals(List.of(1), reply.getMutations(0).getPathList());
            assertEquals("rewritten-1", reply.getMutations(0).getValue().getStringValue());
        }
    }

    private static Value text(String text) {
        return Value.newBuilder().setStringValue(text).build();
    }
}
