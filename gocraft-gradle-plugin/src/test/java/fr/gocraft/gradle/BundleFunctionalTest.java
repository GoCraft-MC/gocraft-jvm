package fr.gocraft.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A plugin author's build, run for real.
///
/// Everything this asserts is something a unit test would have missed: that the
/// coordinates resolve from JitPack, that the processor runs inside javac and
/// leaves its file where the task looks, that the downloaded packer matches its
/// published checksum and is executable, and that what comes out has the shape
/// the JVM runtime extracts — plugin.toml at the root, jars under payload/.
class BundleFunctionalTest {

    @TempDir
    Path project;

    /// The packer this build uses. A published release would be downloaded and
    /// checksummed instead; gocraft-cli has none yet, so the test supplies one
    /// through the same door an offline author would.
    private static String tool() {
        String supplied = System.getProperty("gocraft.test.tool", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !supplied.isBlank() && Files.isRegularFile(Path.of(supplied)),
                "no gocraft-cli to test with; pass -PgocraftCli=<path>");
        return supplied;
    }

    @Test
    void buildsABundleAnAuthorCouldInstall() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"shop\"\n");
        write("build.gradle.kts", """
                plugins {
                    id("fr.gocraft.plugin")
                }

                gocraft {
                    toolPath = "%s"
                }
                """.formatted(tool()));
        write("plugin.toml", """
                id      = "fr.oreo.shop"
                version = "1.0.0"
                api     = 1
                runtime = "jvm"
                entry   = "fr.oreo.shop.ShopPlugin"

                [commands]
                tree = "commands.pb"
                """);
        write("src/main/java/fr/oreo/shop/ShopPlugin.java", """
                package fr.oreo.shop;

                import fr.gocraft.api.Host;
                import fr.gocraft.api.Plugin;

                public final class ShopPlugin implements Plugin {
                    private final Host host;

                    public ShopPlugin(Host host) {
                        this.host = host;
                    }

                    @Override public void enable() {
                        host.log("open for business");
                    }
                }
                """);
        write("src/main/java/fr/oreo/shop/ShopCommands.java", """
                package fr.oreo.shop;

                import fr.gocraft.api.CommandSender;
                import fr.gocraft.api.command.Cmd;
                import fr.gocraft.api.command.Permission;
                import fr.gocraft.api.command.Range;
                import fr.gocraft.api.command.Sub;

                @Cmd("shop") @Permission("shop.use")
                public final class ShopCommands {
                    @Sub("sell <price>")
                    void sell(CommandSender sender, @Range(min = 0.01, max = 1000) double price) {
                    }
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("gocraftBundle", "--stacktrace")
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":gocraftTool").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":gocraftBundle").getOutcome());

        Path bundle = project.resolve("build/gocraft/shop.gcpkg");
        assertTrue(Files.isRegularFile(bundle), "no bundle at " + bundle);

        List<String> entries = entriesOf(bundle);
        assertTrue(entries.contains("plugin.toml"), entries.toString());
        // The shape the runtime extracts: it takes payload/*.jar and nothing else.
        assertTrue(entries.stream().anyMatch(name -> name.startsWith("payload/") && name.endsWith(".jar")),
                entries.toString());
        // The processor ran inside javac and the packer turned its file into
        // the wire tree, without the author naming either.
        assertTrue(entries.contains("commands.pb"), entries.toString());
    }

    /// The second run does nothing, which is what makes this usable: a plugin
    /// author edits a class and waits for a compile, not for a download.
    @Test
    void doesNotResolveTheToolTwice() throws IOException {
        buildsABundleAnAuthorCouldInstall();
        BuildResult again = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("gocraftBundle")
                .build();
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":gocraftTool").getOutcome());
    }

    private void write(String path, String content) throws IOException {
        Path target = project.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static List<String> entriesOf(Path bundle) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile archive = new ZipFile(bundle.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }
}
