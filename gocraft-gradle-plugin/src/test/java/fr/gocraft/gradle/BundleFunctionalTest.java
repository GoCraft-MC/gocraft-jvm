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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A plugin author's build, run for real.
///
/// Everything this asserts is something a unit test would have missed: that the
/// coordinates resolve from JitPack, that the processor runs inside javac and
/// leaves its file where the task looks, that the packer really is fetched from
/// a release and really does match the checksum published beside it, and that
/// what comes out has the shape the JVM runtime extracts — plugin.toml at the
/// root, jars under payload/.
///
/// It reaches the network on purpose. Everything it exercises is a promise made
/// to someone else's machine, and a test that stubbed the network would only
/// assert that this code agrees with itself.
///
/// Which is also why it does not run from `build`: it resolves the API at the
/// version this tree declares, and that version exists only once the tag is
/// pushed and JitPack has built it. `./gradlew verifyRelease` is the check on a
/// release; `build` is the check on a commit, and the two cannot be the same
/// task without one of them lying.
@Tag("release")
class BundleFunctionalTest {

    @TempDir
    Path project;

    @Test
    void buildsABundleAnAuthorCouldInstall() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"shop\"\n");
        write("build.gradle.kts", """
                plugins {
                    id("fr.gocraft.plugin")
                }

                """);
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

    /// Most plugins declare no commands, and for a while none of them could be
    /// bundled: the task pointed at the file gocraft-apt writes and Gradle
    /// refused to run with an input that is not there. Every test above happened
    /// to declare commands, so nothing said so until one did not.
    @Test
    void bundlesAPluginThatDeclaresNoCommands() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"quiet\"\n");
        write("build.gradle.kts", """
                plugins {
                    id("fr.gocraft.plugin")
                }
                """);
        write("plugin.toml", """
                id      = "fr.oreo.quiet"
                version = "1.0.0"
                api     = 1
                runtime = "jvm"
                entry   = "fr.oreo.quiet.QuietPlugin"
                """);
        write("src/main/java/fr/oreo/quiet/QuietPlugin.java", """
                package fr.oreo.quiet;

                import fr.gocraft.api.Plugin;

                public final class QuietPlugin implements Plugin {
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("gocraftBundle", "--stacktrace")
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":gocraftBundle").getOutcome());
        List<String> entries = entriesOf(project.resolve("build/gocraft/quiet.gcpkg"));
        assertTrue(entries.contains("plugin.toml"), entries.toString());
        // And no tree, because there was nothing to put in one.
        assertTrue(entries.stream().noneMatch(name -> name.endsWith(".pb")), entries.toString());
    }

    /// The settings block the README tells an author to paste.
    ///
    /// Everything above resolves the plugin through withPluginClasspath(),
    /// which hands Gradle the classes directly and never touches a repository.
    /// This is the only test that goes through the door a real author uses —
    /// and the only one that can fail because the documentation is wrong.
    @Test
    void appliesByIdThroughJitpack() throws IOException {
        String version = "v" + declaredVersion();
        write("settings.gradle.kts", """
                pluginManagement {
                    repositories {
                        maven { url = uri("https://jitpack.io") }
                        gradlePluginPortal()
                    }
                    resolutionStrategy.eachPlugin {
                        if (requested.id.id == "fr.gocraft.plugin") {
                            useModule("com.github.GoCraft-MC.gocraft-jvm:gocraft-gradle-plugin:${requested.version}")
                        }
                    }
                }
                rootProject.name = "byid"
                """);
        write("build.gradle.kts", """
                plugins {
                    id("fr.gocraft.plugin") version "%s"
                }
                """.formatted(version));
        write("plugin.toml", """
                id      = "fr.oreo.byid"
                version = "1.0.0"
                api     = 1
                runtime = "jvm"
                entry   = "fr.oreo.byid.Nothing"
                """);
        write("src/main/java/fr/oreo/byid/Nothing.java", """
                package fr.oreo.byid;

                import fr.gocraft.api.Plugin;

                public final class Nothing implements Plugin {
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("gocraftBundle", "--stacktrace")
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":gocraftBundle").getOutcome());
        assertTrue(Files.isRegularFile(project.resolve("build/gocraft/byid.gcpkg")));
    }

    private static String declaredVersion() throws IOException {
        java.util.Properties properties = new java.util.Properties();
        try (var source = GoCraftPlugin.class.getResourceAsStream("/gocraft-plugin.properties")) {
            properties.load(source);
        }
        return properties.getProperty("version");
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
