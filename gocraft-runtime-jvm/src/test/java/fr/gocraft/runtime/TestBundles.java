package fr.gocraft.runtime;

import fr.gocraft.abi.v1.CommandArgumentType;
import fr.gocraft.abi.v1.CommandNode;
import fr.gocraft.abi.v1.CommandNodeKind;
import fr.gocraft.abi.v1.CommandTree;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Builds a real `.gcpkg` around a real compiled plugin.
///
/// The plugin is compiled at test time with the JDK's own compiler rather than
/// checked in as a fixture jar. A committed jar is opaque in review, goes stale
/// against the API it implements, and hides exactly the failure these tests
/// exist to catch — a plugin compiled against one `fr.gocraft.api.Plugin` and
/// loaded next to another.
final class TestBundles {

    private TestBundles() {
    }

    /// Source for a plugin that records its lifecycle in a system property, so
    /// a test can observe it across a classloader boundary. A static field
    /// would live in the plugin's own loader and be unreachable from here,
    /// which is the whole point of the isolation being tested.
    static String pluginSource(String className, String marker) {
        return """
                package test.plugin;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;

                public final class %s implements Plugin {
                    private final Host host;

                    public %s(Host host) {
                        this.host = host;
                    }

                    @Override
                    public void enable() {
                        System.setProperty("%s.enabled", host.pluginId());
                    }

                    @Override
                    public void disable() {
                        System.setProperty("%s.disabled", "yes");
                    }
                }
                """.formatted(className, className, marker, marker);
    }

    /// Compiles one source file and packs the class into `payload/plugin.jar`
    /// inside a `.gcpkg`.
    ///
    /// The compile classpath is this test run's own, which is what puts the
    /// same `fr.gocraft.api.Plugin` in front of the plugin that the runtime
    /// will later hand it.
    static Path bundle(Path directory, String className, String source) throws IOException {
        Path sources = Files.createDirectories(directory.resolve("src/test/plugin"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Path file = sources.resolve(className + ".java");
        Files.writeString(file, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no javac; these tests need a JDK, not a JRE");
        }
        // javac's own diagnostics, captured rather than dropped. Without them a
        // failure here says only that something did not compile, and the source
        // is a text block in another file — which is a long way to walk for a
        // missing method the compiler already named.
        var diagnostics = new java.io.ByteArrayOutputStream();
        int status = compiler.run(null, null, diagnostics,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                file.toString());
        if (status != 0) {
            throw new IllegalStateException("compiling the test plugin failed:\n"
                    + diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
        }

        // Every class the compiler produced, not just the named one: a plugin
        // that keeps its handlers on a nested listener — which §05 recommends,
        // because it can then be tested with no server — compiles to more than
        // one file, and jarring only the first would load it half-formed.
        Path jar = directory.resolve("plugin.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar));
             var walk = Files.walk(classes)) {
            for (Path compiled : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new ZipEntry(
                        classes.relativize(compiled).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(compiled));
                out.closeEntry();
            }
        }
        return pack(directory.resolve(className + ".gcpkg"), List.of(entry("payload/plugin.jar", jar)));
    }

    /// The same, plus a serialised command tree at `commands.pb`.
    ///
    /// The tree is built here rather than checked in because the executor ids
    /// are the whole point of the file: a handler binds by path and the runtime
    /// resolves the id out of this, so a fixture with stale ids would test the
    /// fixture rather than the resolution.
    static Path bundleWithCommands(Path directory, String className, String source,
            CommandTree tree) throws IOException {
        Path bundle = bundle(directory, className, source);
        Path treeFile = directory.resolve("commands.pb");
        Files.write(treeFile, tree.toByteArray());
        // Repacked rather than appended: a zip entry cannot be added to a
        // finished archive without rewriting it, and the jar is already inside.
        Path jar = directory.resolve("plugin.jar");
        return pack(bundle, List.of(entry("payload/plugin.jar", jar),
                entry("commands.pb", treeFile)));
    }

    /// One literal with an executor, optionally under a parent literal, which
    /// is the shape `gocraft-cli` writes for `[commands] tree = "commands.pb"`.
    static CommandNode literal(String name, int executor, CommandNode... children) {
        CommandNode.Builder node = CommandNode.newBuilder()
                .setKind(CommandNodeKind.COMMAND_NODE_KIND_LITERAL)
                .setName(name)
                .setExecutor(executor);
        for (CommandNode child : children) {
            node.addChildren(child);
        }
        return node.build();
    }

    static CommandNode argument(String name, CommandArgumentType type, int executor) {
        return CommandNode.newBuilder()
                .setKind(CommandNodeKind.COMMAND_NODE_KIND_ARGUMENT)
                .setName(name)
                .setArgumentType(type)
                .setExecutor(executor)
                .build();
    }

    static CommandTree tree(CommandNode... roots) {
        CommandTree.Builder builder = CommandTree.newBuilder().setVersion(1);
        for (CommandNode root : roots) {
            builder.addChildren(root);
        }
        return builder.build();
    }

    /// A bundle with a manifest and no payload at all, which is what the host's
    /// own tripwire looks like.
    static Path emptyBundle(Path directory) throws IOException {
        Path bundle = directory.resolve("empty.gcpkg");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bundle))) {
            out.putNextEntry(new ZipEntry("plugin.toml"));
            out.write("id = \"dev.example.empty\"\n".getBytes());
            out.closeEntry();
        }
        return bundle;
    }

    record Entry(String name, Path source) {
    }

    static Entry entry(String name, Path source) {
        return new Entry(name, source);
    }

    static Path pack(Path bundle, List<Entry> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bundle))) {
            out.putNextEntry(new ZipEntry("plugin.toml"));
            out.write("id = \"dev.example.test\"\n".getBytes());
            out.closeEntry();
            for (Entry entry : entries) {
                out.putNextEntry(new ZipEntry(entry.name()));
                copy(Files.readAllBytes(entry.source()), out);
                out.closeEntry();
            }
        }
        return bundle;
    }

    private static void copy(byte[] bytes, OutputStream out) throws IOException {
        out.write(bytes);
    }
}