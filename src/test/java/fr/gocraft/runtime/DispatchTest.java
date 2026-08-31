package fr.gocraft.runtime;

import com.google.protobuf.ByteString;
import fr.gocraft.abi.v1.Dispatch;
import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Event;
import fr.gocraft.abi.v1.Load;
import fr.gocraft.abi.v1.Value;
import fr.gocraft.abi.v1.ValueList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// An event, from the wire to a handler and back.
///
/// The payload below is built by hand, field for field, to match exactly what
/// core/plugin writes — because that agreement is the one thing the wire format
/// never carries. §20.4 settled that events travel as positional values, so the
/// host and this runtime agree about what index 3 holds only because both were
/// generated from the same schema. If that ever stops being true, a handler
/// reads the wrong field and nothing throws; this is the test that notices.
class DispatchTest {

    private static final UUID PLAYER = UUID.fromString("9f7e2b4a-1c3d-4e5f-8a9b-0c1d2e3f4a5b");

    // ── The payload, exactly as the Go host builds it ─────────────────────────

    private static Value text(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }

    private static Value number(long value) {
        return Value.newBuilder().setInt64Value(value).build();
    }

    private static Value flag(boolean value) {
        return Value.newBuilder().setBoolValue(value).build();
    }

    private static Value list(Value... values) {
        return Value.newBuilder()
                .setListValue(ValueList.newBuilder().addAllValues(java.util.List.of(values)))
                .build();
    }

    /// PlayerRef: sixteen bytes big-endian, username, edition.
    private static Value player(UUID uuid, String username, String edition) {
        ByteBuffer raw = ByteBuffer.allocate(16);
        raw.putLong(uuid.getMostSignificantBits());
        raw.putLong(uuid.getLeastSignificantBits());
        return list(Value.newBuilder().setBytesValue(ByteString.copyFrom(raw.array())).build(),
                text(username), text(edition));
    }

    /// block.break, in declaration order: player, pos, block, tool, perms.
    private static Event blockBreak() {
        return Event.newBuilder()
                .setType("block.break")
                .addFields(player(PLAYER, "Alex", "bedrock"))
                .addFields(list(number(10), number(64), number(-30)))
                .addFields(list(text("minecraft:oak_log"),
                        list(list(text("axis"), text("y")))))
                .addFields(text("minecraft:iron_axe"))
                .addFields(list(list(text("spawn.bypass"), flag(false)),
                        list(text("spawn.notify"), flag(true))))
                .build();
    }

    // ── The plugin ────────────────────────────────────────────────────────────

    /// Handlers on their own listener, registered through the Host — the shape
    /// §05 recommends, because such a listener can be unit-tested with `new`
    /// and no server at all.
    ///
    /// It writes what it saw into system properties: a static field would live
    /// in the plugin's own classloader and be unreachable from here, which is
    /// the isolation working rather than a problem.
    private static String pluginSource(String marker) {
        return """
                package test.plugin;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;
                import fr.gocraft.api.Priority;
                import fr.gocraft.api.Subscribe;
                import fr.gocraft.api.event.BlockBreakEvent;

                public final class ProtectPlugin implements Plugin {
                    private final Host host;

                    public ProtectPlugin(Host host) {
                        this.host = host;
                    }

                    @Override
                    public void enable() {
                        host.registerListener(new Listener());
                    }

                    public static final class Listener {
                        @Subscribe(priority = Priority.HIGH)
                        public void onBlockBreak(BlockBreakEvent e) {
                            System.setProperty("%s.player", e.player().username());
                            System.setProperty("%s.edition", e.player().edition().name());
                            System.setProperty("%s.uuid", e.player().uuid().toString());
                            System.setProperty("%s.pos", e.pos().toString());
                            System.setProperty("%s.block", e.block().id());
                            System.setProperty("%s.axis", e.block().property("axis"));
                            System.setProperty("%s.tool", e.tool());
                            System.setProperty("%s.bypass", String.valueOf(e.can("spawn.bypass")));
                            System.setProperty("%s.notify", String.valueOf(e.can("spawn.notify")));
                            System.setProperty("%s.undeclared", String.valueOf(e.can("nobody.asked")));
                            if (!e.can("spawn.bypass")) {
                                e.cancel();
                                e.sendMessage("Protected area.");
                            }
                        }
                    }
                }
                """.formatted(marker, marker, marker, marker, marker, marker, marker,
                marker, marker, marker);
    }

    private PluginRegistry loaded(Path directory, String marker) throws Exception {
        Path bundle = TestBundles.bundle(directory, "ProtectPlugin", pluginSource(marker));
        PluginRegistry registry = new PluginRegistry(
                Files.createDirectories(directory.resolve("work")));
        Envelope reply = registry.load(1, Load.newBuilder()
                .setPluginId("dev.example.protect")
                .setBundlePath(bundle.toString())
                .setEntry("test.plugin.ProtectPlugin")
                .build());
        assertTrue(reply.hasLoaded(), () -> "load failed: " + reply.getFail().getReason());
        return registry;
    }

    private static Envelope dispatch(PluginRegistry registry) {
        return registry.dispatch(7, Dispatch.newBuilder()
                .setPluginId("dev.example.protect")
                .setEvent(blockBreak())
                .build());
    }

    // ── The test ──────────────────────────────────────────────────────────────

    /// Every generated accessor, read against the payload the host writes.
    ///
    /// This is the conformance check between two independently generated sides.
    /// One wrong index here and a handler silently reads the tool as the block.
    @Test
    void aHandlerReadsEveryFieldByName(@TempDir Path directory) throws Exception {
        String marker = "gc.dispatch.read";
        try (PluginRegistry registry = loaded(directory, marker)) {
            dispatch(registry);

            assertEquals("Alex", System.getProperty(marker + ".player"));
            assertEquals("BEDROCK", System.getProperty(marker + ".edition"));
            assertEquals(PLAYER.toString(), System.getProperty(marker + ".uuid"));
            assertEquals("10,64,-30", System.getProperty(marker + ".pos"));
            assertEquals("minecraft:oak_log", System.getProperty(marker + ".block"));
            assertEquals("y", System.getProperty(marker + ".axis"));
            assertEquals("minecraft:iron_axe", System.getProperty(marker + ".tool"));
        }
    }

    /// Permissions arrive resolved, so a handler never pays a round trip for
    /// one while it is holding the tick.
    @Test
    void permissionsArriveAlreadyAnswered(@TempDir Path directory) throws Exception {
        String marker = "gc.dispatch.perms";
        try (PluginRegistry registry = loaded(directory, marker)) {
            dispatch(registry);

            assertEquals("false", System.getProperty(marker + ".bypass"));
            assertEquals("true", System.getProperty(marker + ".notify"));
            // A node no manifest declared reads false: the host was never asked
            // about it, which is a manifest bug rather than a denial.
            assertEquals("false", System.getProperty(marker + ".undeclared"));
        }
    }

    /// The whole point of a cancellable event: the handler's decision reaches
    /// the host, and the message it asked for travels in the same message
    /// rather than a second one.
    @Test
    void theVerdictCarriesTheDecisionAndTheEffect(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = loaded(directory, "gc.dispatch.verdict")) {
            Envelope reply = dispatch(registry);

            assertEquals(7, reply.getSeq(), "a verdict the host cannot match is a verdict lost");
            assertTrue(reply.hasVerdict());
            assertTrue(reply.getVerdict().getCancelled(), "the handler cancelled and it did not stick");

            assertEquals(1, reply.getVerdict().getEffectsCount(),
                    "effects are batched into the verdict; one event, one round trip");
            var effect = reply.getVerdict().getEffects(0);
            assertEquals("chat.message", effect.getType());
            // The recipient travels with the message. Without it the host has a
            // line and nobody to deliver it to, and would have to guess from
            // whatever event happened to be in flight.
            assertTrue(effect.getFields(0).hasListValue(),
                    "a message carries the PlayerRef it is addressed to");
            assertEquals("Protected area.", effect.getFields(1).getStringValue());
        }
    }

    /// A plugin that registered a handler reports it, and the host checks that
    /// against the manifest it validated. Reporting nothing would let an
    /// undeclared subscription through, to be never routed and never noticed.
    @Test
    void loadReportsWhatWasRegistered(@TempDir Path directory) throws Exception {
        Path bundle = TestBundles.bundle(directory, "ProtectPlugin",
                pluginSource("gc.dispatch.report"));
        try (PluginRegistry registry = new PluginRegistry(
                Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1, Load.newBuilder()
                    .setPluginId("dev.example.protect")
                    .setBundlePath(bundle.toString())
                    .setEntry("test.plugin.ProtectPlugin")
                    .build());

            assertTrue(reply.hasLoaded(), () -> reply.getFail().getReason());
            assertEquals(java.util.List.of("block.break"), reply.getLoaded().getEventsList());
        }
    }

    /// An event nobody subscribed to still gets an answer, and that answer does
    /// not cancel. Silence would burn the budget the other subscribers share.
    @Test
    void answersAnEventWithNoHandler(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = loaded(directory, "gc.dispatch.none")) {
            Envelope reply = registry.dispatch(11, Dispatch.newBuilder()
                    .setPluginId("dev.example.protect")
                    .setEvent(Event.newBuilder()
                            .setType("player.join")
                            .addFields(player(PLAYER, "Alex", "java"))
                            .addFields(list()))
                    .build());

            assertEquals(11, reply.getSeq());
            assertFalse(reply.getVerdict().getCancelled());
        }
    }

    /// An event from a newer ABI than this build. Allowing is the only honest
    /// answer: refusing something the runtime cannot inspect would stop
    /// gameplay on the strength of a version mismatch.
    @Test
    void allowsAnEventItDoesNotKnow(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = loaded(directory, "gc.dispatch.unknown")) {
            Envelope reply = registry.dispatch(12, Dispatch.newBuilder()
                    .setPluginId("dev.example.protect")
                    .setEvent(Event.newBuilder().setType("entity.tame"))
                    .build());

            assertTrue(reply.hasVerdict());
            assertFalse(reply.getVerdict().getCancelled());
        }
    }
}