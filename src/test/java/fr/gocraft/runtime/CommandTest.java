package fr.gocraft.runtime;

import com.google.protobuf.ByteString;
import fr.gocraft.abi.v1.CommandArgument;
import fr.gocraft.abi.v1.CommandArgumentType;
import fr.gocraft.abi.v1.CommandSender;
import fr.gocraft.abi.v1.CommandTree;
import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Invoke;
import fr.gocraft.abi.v1.Load;
import fr.gocraft.abi.v1.Value;
import fr.gocraft.abi.v1.ValueList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A command, from the wire to a handler and back.
///
/// The counterpart of [DispatchTest] for INVOKE. It exists for the same reason:
/// the host and this runtime were generated from one schema but never run
/// against each other, so the only proof that a handler reads argument "price"
/// as a decimal — rather than reading nothing and returning zero — is a payload
/// built here to match exactly what core/plugin writes.
///
/// It also pins the binding rule. A handler registers against the path through
/// the tree, "shop sell <price>", and the executor id comes out of the bundle.
/// Nothing in a plugin's source names an id: ids belong to whatever built the
/// tree, and naming one would be a second place they are written down.
class CommandTest {

    private static final UUID PLAYER = UUID.fromString("9f7e2b4a-1c3d-4e5f-8a9b-0c1d2e3f4a5b");

    // ── The payload, exactly as the Go host builds it ─────────────────────────

    private static Value text(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }

    private static Value decimal(double value) {
        return Value.newBuilder().setDoubleValue(value).build();
    }

    private static Value flag(boolean value) {
        return Value.newBuilder().setBoolValue(value).build();
    }

    private static Value list(Value... values) {
        return Value.newBuilder()
                .setListValue(ValueList.newBuilder().addAllValues(List.of(values)))
                .build();
    }

    private static Value playerRef(UUID uuid, String username, String edition) {
        ByteBuffer raw = ByteBuffer.allocate(16);
        raw.putLong(uuid.getMostSignificantBits());
        raw.putLong(uuid.getLeastSignificantBits());
        return list(Value.newBuilder().setBytesValue(ByteString.copyFrom(raw.array())).build(),
                text(username), text(edition));
    }

    /// The tree the bundle ships: `shop` with a `sell <price>` under it. The
    /// executor ids are arbitrary and deliberately not 1 and 2 — a handler that
    /// resolved them by position rather than by path would still pass with
    /// tidy numbers.
    private static CommandTree shopTree() {
        return TestBundles.tree(
                TestBundles.literal("shop", 0,
                        TestBundles.literal("sell", 0,
                                TestBundles.argument("price",
                                        CommandArgumentType.COMMAND_ARGUMENT_TYPE_DECIMAL, 41)),
                        TestBundles.literal("close", 17)));
    }

    private static CommandSender sender(Value player, String name) {
        return CommandSender.newBuilder()
                .setPlayer(player)
                .setName(name)
                .addPermissions(list(text("shop.sell"), flag(true)))
                .addPermissions(list(text("shop.admin"), flag(false)))
                .build();
    }

    private static Invoke sell(double price) {
        return Invoke.newBuilder()
                .setPluginId("dev.example.shop")
                .setExecutor(41)
                .setSender(sender(playerRef(PLAYER, "Alex", "bedrock"), "Alex"))
                .addArguments(CommandArgument.newBuilder()
                        .setName("price")
                        .setType(CommandArgumentType.COMMAND_ARGUMENT_TYPE_DECIMAL)
                        .setValue(decimal(price)))
                .build();
    }

    // ── The plugin ────────────────────────────────────────────────────────────

    /// Binds by path, reads its argument by name, answers with reply().
    private static String pluginSource(String marker) {
        return """
                package test.plugin;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;

                public final class ShopPlugin implements Plugin {
                    private final Host host;

                    public ShopPlugin(Host host) {
                        this.host = host;
                    }

                    @Override
                    public void enable() {
                        host.registerCommand("shop sell <price>", ctx -> {
                            System.setProperty("%s.price", String.valueOf(ctx.decimal("price")));
                            System.setProperty("%s.sender", ctx.sender().name());
                            System.setProperty("%s.player",
                                    String.valueOf(ctx.sender().isPlayer()));
                            System.setProperty("%s.sell",
                                    String.valueOf(ctx.sender().can("shop.sell")));
                            System.setProperty("%s.admin",
                                    String.valueOf(ctx.sender().can("shop.admin")));
                            System.setProperty("%s.undeclared",
                                    String.valueOf(ctx.sender().can("nobody.asked")));
                            // Reading an argument as the wrong type yields the
                            // empty value, never another argument's.
                            System.setProperty("%s.wrongtype", ctx.text("price"));
                            ctx.reply("Sold.");
                        });
                        host.registerCommand("shop close", ctx -> {
                            throw new IllegalStateException("the shop is already closed");
                        });
                    }
                }
                """.formatted(marker, marker, marker, marker, marker, marker, marker);
    }

    private PluginRegistry loaded(Path directory, String marker) throws Exception {
        Path bundle = TestBundles.bundleWithCommands(
                directory, "ShopPlugin", pluginSource(marker), shopTree());
        PluginRegistry registry = new PluginRegistry(
                Files.createDirectories(directory.resolve("work")));
        Envelope reply = registry.load(1, Load.newBuilder()
                .setPluginId("dev.example.shop")
                .setBundlePath(bundle.toString())
                .setEntry("test.plugin.ShopPlugin")
                .setCommandTree("commands.pb")
                .build());
        assertTrue(reply.hasLoaded(), () -> "load failed: " + reply.getFail().getReason());
        return registry;
    }

    /// A plugin that declares its commands with a facade rather than one path
    /// at a time.
    ///
    /// The ids the builder hands out start at 1; the ids the bundle assigned
    /// are 41 and 17. Nothing reconciles them and nothing needs to: a facade
    /// describes commands, and paths are what both sides agree on.
    private static String facadeSource(String marker) {
        return """
                package test.plugin;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;
                import fr.gocraft.api.command.ArgType;
                import fr.gocraft.api.command.Command;

                public final class FacadePlugin implements Plugin {
                    private final Host host;

                    public FacadePlugin(Host host) {
                        this.host = host;
                    }

                    @Override
                    public void enable() {
                        host.registerCommands(Command.tree(Command.literal("shop")
                                .then(Command.literal("sell")
                                        .then(Command.arg("price", ArgType.decimal())
                                                .executes(ctx -> {
                                                    System.setProperty("%s.price",
                                                            String.valueOf(ctx.decimal("price")));
                                                    ctx.reply("Sold.");
                                                })))
                                .then(Command.literal("close").executes(ctx -> {
                                    System.setProperty("%s.closed", "true");
                                }))));
                    }
                }
                """.formatted(marker, marker);
    }

    // ── The tests ─────────────────────────────────────────────────────────────

    /// The whole path in one pass: the host's payload reaches a handler, every
    /// accessor reads what was sent, and the reply comes back as an effect.
    @Test
    void aHandlerReadsItsArgumentsAndAnswers(@TempDir Path directory) throws Exception {
        String marker = "gc.command.read";
        try (PluginRegistry registry = loaded(directory, marker)) {
            Envelope reply = registry.invoke(9, sell(12.5));

            assertEquals(9, reply.getSeq(), "an answer the host cannot match is an answer lost");
            assertTrue(reply.hasInvoked());
            assertEquals("", reply.getInvoked().getError(), "the handler did not fail");

            assertEquals("12.5", System.getProperty(marker + ".price"));
            assertEquals("Alex", System.getProperty(marker + ".sender"));
            assertEquals("true", System.getProperty(marker + ".player"));
            // Resolved by the host before the invocation left, because the ABI
            // has no message for asking afterwards.
            assertEquals("true", System.getProperty(marker + ".sell"));
            assertEquals("false", System.getProperty(marker + ".admin"));
            // A node no manifest declared reads false: never asked, rather than
            // denied.
            assertEquals("false", System.getProperty(marker + ".undeclared"));
            // Asking for the wrong type gives nothing, never another argument.
            assertEquals("", System.getProperty(marker + ".wrongtype"));

            assertEquals(1, reply.getInvoked().getEffectsCount());
            var effect = reply.getInvoked().getEffects(0);
            assertEquals("chat.message", effect.getType());
            assertTrue(effect.getFields(0).hasListValue(),
                    "a reply carries the sender it is addressed to");
            assertEquals("Sold.", effect.getFields(1).getStringValue());
        }
    }

    /// Throwing is a documented way to refuse. The message is what the sender
    /// reads, so it travels verbatim rather than as a class name or a trace.
    @Test
    void aHandlerThatThrowsBecomesTheReasonTheSenderReads(@TempDir Path directory)
            throws Exception {
        try (PluginRegistry registry = loaded(directory, "gc.command.throw")) {
            Envelope reply = registry.invoke(3, Invoke.newBuilder()
                    .setPluginId("dev.example.shop")
                    .setExecutor(17)
                    .setSender(sender(playerRef(PLAYER, "Alex", "java"), "Alex"))
                    .build());

            assertTrue(reply.hasInvoked());
            assertEquals("the shop is already closed", reply.getInvoked().getError());
        }
    }

    /// An executor the plugin never bound is answered, not ignored: whoever
    /// typed the command is waiting either way, and the host advertised it.
    @Test
    void anUnboundExecutorIsAnswered(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = loaded(directory, "gc.command.unbound")) {
            Envelope reply = registry.invoke(4, Invoke.newBuilder()
                    .setPluginId("dev.example.shop")
                    .setExecutor(999)
                    .setSender(sender(playerRef(PLAYER, "Alex", "java"), "Alex"))
                    .build());

            assertTrue(reply.hasInvoked());
            assertFalse(reply.getInvoked().getError().isEmpty(),
                    "an unbound executor has to say so");
        }
    }

    /// The console has no player. A handler has to be able to tell, and the
    /// empty PlayerRef is how every other message says so.
    @Test
    void aConsoleSenderHasNoPlayer(@TempDir Path directory) throws Exception {
        String marker = "gc.command.console";
        try (PluginRegistry registry = loaded(directory, marker)) {
            Envelope reply = registry.invoke(5, Invoke.newBuilder()
                    .setPluginId("dev.example.shop")
                    .setExecutor(41)
                    .setSender(sender(list(), "Console"))
                    .addArguments(CommandArgument.newBuilder()
                            .setName("price")
                            .setType(CommandArgumentType.COMMAND_ARGUMENT_TYPE_DECIMAL)
                            .setValue(decimal(1)))
                    .build());

            assertEquals("", reply.getInvoked().getError());
            assertEquals("false", System.getProperty(marker + ".player"));
            assertEquals("Console", System.getProperty(marker + ".sender"));
        }
    }

    /// Binding against a path the tree does not contain is refused at
    /// registration, with the paths it does contain in the message. A handler
    /// accepted here would simply never run, which is the failure that costs an
    /// afternoon to tell apart from a broken server.
    @Test
    void bindingAnUnknownPathIsRefused(@TempDir Path directory) throws Exception {
        String source = """
                package test.plugin;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;

                public final class TypoPlugin implements Plugin {
                    private final Host host;

                    public TypoPlugin(Host host) {
                        this.host = host;
                    }

                    @Override
                    public void enable() {
                        host.registerCommand("shop sel", ctx -> { });
                    }
                }
                """;
        Path bundle = TestBundles.bundleWithCommands(directory, "TypoPlugin", source, shopTree());
        try (PluginRegistry registry = new PluginRegistry(
                Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1, Load.newBuilder()
                    .setPluginId("dev.example.shop")
                    .setBundlePath(bundle.toString())
                    .setEntry("test.plugin.TypoPlugin")
                    .setCommandTree("commands.pb")
                    .build());

            assertTrue(reply.hasFail(), "a handler bound to nothing was accepted");
            assertTrue(reply.getFail().getReason().contains("shop sell <price>"),
                    () -> "the reason should name what the tree offers: "
                            + reply.getFail().getReason());
        }
    }

    /// A tree the manifest names and the bundle does not contain is a failure,
    /// not a plugin loaded with no commands. The host validated that bundle
    /// before sending it, so the mismatch means the archive changed underneath.
    @Test
    void aMissingTreeFailsTheLoad(@TempDir Path directory) throws Exception {
        Path bundle = TestBundles.bundle(directory, "ShopPlugin",
                pluginSource("gc.command.missing"));
        try (PluginRegistry registry = new PluginRegistry(
                Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1, Load.newBuilder()
                    .setPluginId("dev.example.shop")
                    .setBundlePath(bundle.toString())
                    .setEntry("test.plugin.ShopPlugin")
                    .setCommandTree("commands.pb")
                    .build());

            assertTrue(reply.hasFail());
            assertTrue(reply.getFail().getReason().contains("commands.pb"),
                    () -> "the reason should name the missing tree: "
                            + reply.getFail().getReason());
        }
    }

    /// A facade installs through the same door a hand-written registration
    /// uses, and the ids it invented are its own business.
    @Test
    void aFacadeRegistersEveryPathItDeclares(@TempDir Path directory) throws Exception {
        String marker = "gc.command.facade";
        Path bundle = TestBundles.bundleWithCommands(
                directory, "FacadePlugin", facadeSource(marker), shopTree());
        try (PluginRegistry registry = new PluginRegistry(
                Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1, Load.newBuilder()
                    .setPluginId("dev.example.shop")
                    .setBundlePath(bundle.toString())
                    .setEntry("test.plugin.FacadePlugin")
                    .setCommandTree("commands.pb")
                    .build());
            assertTrue(reply.hasLoaded(), () -> "load failed: " + reply.getFail().getReason());

            // 41 is the id the bundle gave "shop sell <price>", not the 1 the
            // builder handed out.
            Envelope sold = registry.invoke(4, sell(7.5));
            assertEquals("", sold.getInvoked().getError());
            assertEquals("7.5", System.getProperty(marker + ".price"));

            Envelope closed = registry.invoke(5, Invoke.newBuilder()
                    .setPluginId("dev.example.shop")
                    .setExecutor(17)
                    .setSender(sender(playerRef(PLAYER, "Alex", "java"), "Alex"))
                    .build());
            assertEquals("", closed.getInvoked().getError());
            assertEquals("true", System.getProperty(marker + ".closed"));
        }
    }

    /// A facade describing a command the bundle never shipped is refused at
    /// load: the bundle was built from a different source than the code.
    @Test
    void aFacadeThatDescribesAnUnknownCommandIsRefused(@TempDir Path directory) throws Exception {
        String source = """
                package test.plugin;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;
                import fr.gocraft.api.command.Command;

                public final class StalePlugin implements Plugin {
                    private final Host host;

                    public StalePlugin(Host host) {
                        this.host = host;
                    }

                    @Override
                    public void enable() {
                        host.registerCommands(Command.tree(
                                Command.literal("warp").executes(ctx -> {})));
                    }
                }
                """;
        Path bundle = TestBundles.bundleWithCommands(
                directory, "StalePlugin", source, shopTree());
        try (PluginRegistry registry = new PluginRegistry(
                Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1, Load.newBuilder()
                    .setPluginId("dev.example.shop")
                    .setBundlePath(bundle.toString())
                    .setEntry("test.plugin.StalePlugin")
                    .setCommandTree("commands.pb")
                    .build());
            assertTrue(reply.hasFail(), "a stale facade loaded");
            assertTrue(reply.getFail().getReason().contains("warp"), reply.getFail().getReason());
        }
    }

}