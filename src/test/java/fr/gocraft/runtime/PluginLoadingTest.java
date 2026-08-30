package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Load;
import fr.gocraft.abi.v1.Unload;
import fr.gocraft.api.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLoadingTest {

    private static Load load(String id, Path bundle, String entry) {
        return load(id, bundle, entry, "");
    }

    private static Load load(String id, Path bundle, String entry, String data) {
        return Load.newBuilder()
                .setPluginId(id)
                .setBundlePath(bundle.toString())
                .setEntry(entry)
                .setDataDirectory(data)
                .build();
    }

    @Test
    void loadsAPluginAndEnablesIt(@TempDir Path directory) throws Exception {
        String marker = "gc.test.enable";
        Path bundle = TestBundles.bundle(directory, "EnablePlugin",
                TestBundles.pluginSource("EnablePlugin", marker));
        System.clearProperty(marker + ".enabled");

        try (PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1, load("dev.example.test", bundle, "test.plugin.EnablePlugin"));

            assertTrue(reply.hasLoaded(), () -> "load failed: " + reply.getFail().getReason());
            assertEquals("dev.example.test", reply.getLoaded().getPluginId());
            assertEquals(1, registry.size());
            // enable() ran inside the plugin's own classloader, and the Host it
            // was given knows which plugin it belongs to.
            assertEquals("dev.example.test", System.getProperty(marker + ".enabled"));
        }
    }

    /// The failure §13 calls the least legible in Java: if `fr.gocraft.api` is
    /// not shared, the `Plugin` the runtime casts to is a different class from
    /// the `Plugin` the plugin implements, and the cast fails naming the same
    /// type twice.
    @Test
    void givesThePluginTheSameApiClassTheRuntimeUses(@TempDir Path directory) throws Exception {
        Path bundle = TestBundles.bundle(directory, "SharedApiPlugin",
                TestBundles.pluginSource("SharedApiPlugin", "gc.test.shared"));

        try (PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")))) {
            registry.load(1, load("dev.example.test", bundle, "test.plugin.SharedApiPlugin"));
            LoadedPlugin loaded = registry.get("dev.example.test");

            Class<?> apiSeenByPlugin = loaded.loader().loadClass(Plugin.class.getName());
            assertSame(Plugin.class, apiSeenByPlugin,
                    "the plugin sees a different fr.gocraft.api.Plugin from the runtime");
            // The plugin's own class is emphatically not shared: that is what
            // lets two plugins carry different versions of the same library.
            assertSame(loaded.loader(), loaded.instance().getClass().getClassLoader());
        }
    }

    @Test
    void unloadDisablesThePluginAndForgetsIt(@TempDir Path directory) throws Exception {
        String marker = "gc.test.disable";
        Path bundle = TestBundles.bundle(directory, "DisablePlugin",
                TestBundles.pluginSource("DisablePlugin", marker));
        System.clearProperty(marker + ".disabled");

        PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")));
        registry.load(1, load("dev.example.test", bundle, "test.plugin.DisablePlugin"));

        registry.unload(Unload.newBuilder().setPluginId("dev.example.test").build());

        assertEquals("yes", System.getProperty(marker + ".disabled"));
        assertEquals(0, registry.size());
        assertFalse(registry.isLoaded("dev.example.test"));
    }

    /// The test §13 says is worth writing early. A single retained reference —
    /// a handler, a MethodHandle, a scheduler task — keeps the classloader
    /// alive and with it every class the plugin defined. That is Bukkit's
    /// classic `/reload` leak, and it is invisible until a server has been up
    /// for a week.
    @Test
    void releasesEveryClassloaderItLoads(@TempDir Path directory) throws Exception {
        Path bundle = TestBundles.bundle(directory, "CyclePlugin",
                TestBundles.pluginSource("CyclePlugin", "gc.test.cycle"));
        Path work = Files.createDirectories(directory.resolve("work"));

        WeakReference<?>[] loaders = new WeakReference<?>[25];
        try (PluginRegistry registry = new PluginRegistry(work)) {
            for (int round = 0; round < loaders.length; round++) {
                Envelope reply = registry.load(round, load("dev.example.test", bundle, "test.plugin.CyclePlugin"));
                assertTrue(reply.hasLoaded(), () -> "load failed: " + reply.getFail().getReason());
                loaders[round] = new WeakReference<>(registry.get("dev.example.test").loader());
                registry.unload(Unload.newBuilder().setPluginId("dev.example.test").build());
            }
        }

        assertTrue(collected(loaders),
                "an unloaded plugin's classloader was still reachable: something kept a "
                        + "reference to plugin code after unload");
    }

    /// Closed explicitly: an open directory stream holds a handle, and on
    /// Windows that alone can stop the directory being removed — which is the
    /// very thing these tests assert.
    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    /// Collection is not deterministic, so this asks repeatedly rather than
    /// once. A loader that is genuinely unreachable is collected within a few
    /// rounds; one that is retained never is, however long the loop runs.
    private static boolean collected(WeakReference<?>[] references) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            System.gc();
            boolean allGone = true;
            for (WeakReference<?> reference : references) {
                if (reference.get() != null) {
                    allGone = false;
                    break;
                }
            }
            if (allGone) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void unloadingRemovesTheExtractedPayload(@TempDir Path directory) throws Exception {
        Path bundle = TestBundles.bundle(directory, "CleanupPlugin",
                TestBundles.pluginSource("CleanupPlugin", "gc.test.cleanup"));
        Path work = Files.createDirectories(directory.resolve("work"));

        try (PluginRegistry registry = new PluginRegistry(work)) {
            registry.load(1, load("dev.example.test", bundle, "test.plugin.CleanupPlugin"));
            assertFalse(isEmpty(work), "nothing was extracted");

            registry.unload(Unload.newBuilder().setPluginId("dev.example.test").build());

            assertTrue(isEmpty(work), "the extracted payload outlived the plugin");
        }
    }

    @Test
    void refusesABundleWithNoPayload(@TempDir Path directory) throws Exception {
        Path bundle = TestBundles.emptyBundle(directory);

        try (PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1, load("dev.example.empty", bundle, "test.plugin.Nothing"));

            assertTrue(reply.hasFail());
            assertTrue(reply.getFail().getReason().contains("payload"), reply.getFail().getReason());
        }
    }

    @Test
    void refusesAnEntryClassThatIsNotThere(@TempDir Path directory) throws Exception {
        Path bundle = TestBundles.bundle(directory, "PresentPlugin",
                TestBundles.pluginSource("PresentPlugin", "gc.test.absent"));

        try (PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1, load("dev.example.test", bundle, "test.plugin.Missing"));

            assertTrue(reply.hasFail());
            assertTrue(reply.getFail().getReason().contains("test.plugin.Missing"),
                    reply.getFail().getReason());
        }
    }

    /// An author who asks for something the ABI cannot carry yet has to be told
    /// what is missing, not shown a NoSuchMethodException.
    @Test
    void refusesAConstructorItCannotInject(@TempDir Path directory) throws Exception {
        String source = """
                package test.plugin;

                import fr.gocraft.api.Plugin;

                public final class GreedyPlugin implements Plugin {
                    public GreedyPlugin(java.sql.Connection database) {
                    }
                }
                """;
        Path bundle = TestBundles.bundle(directory, "GreedyPlugin", source);

        try (PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1, load("dev.example.test", bundle, "test.plugin.GreedyPlugin"));

            assertTrue(reply.hasFail());
            assertTrue(reply.getFail().getReason().contains("java.sql.Connection"),
                    reply.getFail().getReason());
        }
    }

    @Test
    void refusesTheSamePluginTwice(@TempDir Path directory) throws Exception {
        Path bundle = TestBundles.bundle(directory, "TwicePlugin",
                TestBundles.pluginSource("TwicePlugin", "gc.test.twice"));

        try (PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")))) {
            registry.load(1, load("dev.example.test", bundle, "test.plugin.TwicePlugin"));
            Envelope second = registry.load(2, load("dev.example.test", bundle, "test.plugin.TwicePlugin"));

            assertTrue(second.hasFail());
            assertTrue(second.getFail().getReason().contains("already loaded"));
            assertEquals(1, registry.size());
        }
    }

    /// A plugin that throws on the way in must leave nothing behind. A
    /// classloader left open on a plugin that never started is the same leak as
    /// one left open on unload.
    @Test
    void leavesNothingBehindWhenEnableThrows(@TempDir Path directory) throws Exception {
        String source = """
                package test.plugin;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;

                public final class ExplodingPlugin implements Plugin {
                    public ExplodingPlugin(Host host) {
                    }

                    @Override
                    public void enable() {
                        throw new IllegalStateException("config.yml:12 taxRate < 0");
                    }
                }
                """;
        Path bundle = TestBundles.bundle(directory, "ExplodingPlugin", source);
        Path work = Files.createDirectories(directory.resolve("work"));

        try (PluginRegistry registry = new PluginRegistry(work)) {
            Envelope reply = registry.load(1, load("dev.example.test", bundle, "test.plugin.ExplodingPlugin"));

            assertTrue(reply.hasFail());
            assertTrue(reply.getFail().getReason().contains("taxRate"), reply.getFail().getReason());
            assertEquals(0, registry.size());
            assertNull(registry.get("dev.example.test"));
            assertTrue(isEmpty(work),
                    "a plugin that never started left its payload extracted");
        }
    }

    @Test
    void closingTheRegistryUnloadsEverything(@TempDir Path directory) throws Exception {
        String marker = "gc.test.shutdown";
        Path bundle = TestBundles.bundle(directory, "ShutdownPlugin",
                TestBundles.pluginSource("ShutdownPlugin", marker));
        System.clearProperty(marker + ".disabled");

        PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")));
        registry.load(1, load("dev.example.test", bundle, "test.plugin.ShutdownPlugin"));
        assertNotNull(registry.get("dev.example.test"));

        registry.close();

        assertEquals("yes", System.getProperty(marker + ".disabled"));
        assertEquals(0, registry.size());
    }

    /// The host creates the directory, seeds it from the bundle and hands over
    /// the path. A plugin deriving its own would derive a different one the day
    /// either side changed its mind, and would then read a configuration nobody
    /// is editing.
    @Test
    void aPluginIsToldWhereItsFilesLive(@TempDir Path directory) throws Exception {
        String marker = "gc.test.data";
        Path data = Files.createDirectories(directory.resolve("plugin-data"));
        String source = """
                package test.plugin;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;

                public final class DataPlugin implements Plugin {
                    private final Host host;

                    public DataPlugin(Host host) {
                        this.host = host;
                    }

                    @Override
                    public void enable() {
                        System.setProperty("%s.dir", host.dataDirectory().toString());
                    }
                }
                """.formatted(marker);
        Path bundle = TestBundles.bundle(directory, "DataPlugin", source);
        System.clearProperty(marker + ".dir");

        try (PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")))) {
            Envelope reply = registry.load(1,
                    load("dev.example.data", bundle, "test.plugin.DataPlugin", data.toString()));

            assertTrue(reply.hasLoaded(), () -> reply.getFail().getReason());
            assertEquals(data.toString(), System.getProperty(marker + ".dir"));
        }
    }

    /// A host that sent no directory gets a refusal rather than a made-up path.
    /// A plugin handed one the host does not know about would write a
    /// configuration nobody reads and lose it on the next restart.
    @Test
    void refusesToInventADataDirectory(@TempDir Path directory) throws Exception {
        String source = """
                package test.plugin;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;

                public final class NoDataPlugin implements Plugin {
                    private final Host host;

                    public NoDataPlugin(Host host) {
                        this.host = host;
                    }

                    @Override
                    public void enable() {
                        host.dataDirectory();
                    }
                }
                """;
        Path bundle = TestBundles.bundle(directory, "NoDataPlugin", source);

        try (PluginRegistry registry = new PluginRegistry(Files.createDirectories(directory.resolve("work")))) {
            // No data directory: an older host, or one that loaded without
            // preparing the plugin's files.
            Envelope reply = registry.load(1, load("dev.example.data", bundle, "test.plugin.NoDataPlugin"));

            assertTrue(reply.hasFail());
            assertTrue(reply.getFail().getReason().contains("data directory"),
                    reply.getFail().getReason());
        }
    }
}
