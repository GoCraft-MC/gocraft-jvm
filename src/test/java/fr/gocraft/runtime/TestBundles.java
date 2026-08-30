package fr.gocraft.runtime;

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
        int status = compiler.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                file.toString());
        if (status != 0) {
            throw new IllegalStateException("compiling the test plugin failed");
        }

        Path jar = directory.resolve("plugin.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            Path compiled = classes.resolve("test/plugin/" + className + ".class");
            out.putNextEntry(new ZipEntry("test/plugin/" + className + ".class"));
            out.write(Files.readAllBytes(compiled));
            out.closeEntry();
        }
        return pack(directory.resolve(className + ".gcpkg"), List.of(entry("payload/plugin.jar", jar)));
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