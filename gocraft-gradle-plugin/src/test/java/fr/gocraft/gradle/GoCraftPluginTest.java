package fr.gocraft.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/// What applying the plugin does, without a network or a published anything.
class GoCraftPluginTest {

    private static Project applied() {
        Project project = ProjectBuilder.builder().withName("shop").build();
        project.getPluginManager().apply(GoCraftPlugin.class);
        return project;
    }

    /// The test for the bug that shipped.
    ///
    /// The plugin names the API and the processor from its own version, so an
    /// author never writes coordinates that have to agree. When the version it
    /// reads back is not the version it was built as, it pairs itself with
    /// another release — and nothing complains, because that release exists and
    /// resolves.
    @Test
    void namesTheApiAtItsOwnVersion() throws IOException {
        String expected = "v" + declaredVersion();

        Project project = applied();
        assertTrue(coordinates(project, JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME)
                        .contains("gocraft-api-jvm:" + expected),
                coordinates(project, JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME));
        assertTrue(coordinates(project, JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME)
                        .contains("gocraft-apt:" + expected),
                coordinates(project, JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME));
    }

    /// compileOnly and not implementation: the runtime already carries the API,
    /// and a plugin shipping its own copy loads classes the host does not
    /// recognise as its own — the mistake that costs an afternoon in a
    /// debugger, because both classes look right.
    @Test
    void keepsTheApiOffTheRuntimeClasspath() {
        Project project = applied();
        assertTrue(coordinates(project, JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME).isEmpty(),
                "the API leaked onto implementation");
    }

    @Test
    void registersTheTwoTasksAnAuthorRuns() {
        Project project = applied();
        assertNotNull(project.getTasks().findByName("gocraftBundle"));
        assertNotNull(project.getTasks().findByName("gocraftTool"));
    }

    @Test
    void defaultsTheBundleToTheProjectName() {
        GoCraftExtension extension = applied().getExtensions().getByType(GoCraftExtension.class);
        assertEquals("shop", extension.getBundleName().get());
        assertTrue(extension.getIncludeDependencies().get());
    }

    /// The naming the release workflow produces, asserted for every platform it
    /// builds rather than only the one this test runs on. Change either side
    /// and this is where they stop agreeing.
    @Test
    void constructsTheAssetNamesTheWorkflowPublishes() {
        assertEquals("gocraft-cli_v0.1.1_linux_amd64",
                ResolveToolTask.assetName("v0.1.1", "linux", "amd64"));
        assertEquals("gocraft-cli_v0.1.1_darwin_arm64",
                ResolveToolTask.assetName("v0.1.1", "darwin", "arm64"));
        assertEquals("gocraft-cli_v0.1.1_windows_amd64.exe",
                ResolveToolTask.assetName("v0.1.1", "windows", "amd64"));
    }

    /// This machine is one the packer is published for. A build on anything
    /// else says so by name instead of failing on a 404.
    @Test
    void recognisesThisMachine() {
        assertTrue(ResolveToolTask.operatingSystem().matches("linux|darwin|windows"));
        assertTrue(ResolveToolTask.architecture().matches("amd64|arm64"));
    }

    private static String coordinates(Project project, String configuration) {
        StringBuilder names = new StringBuilder();
        for (Dependency dependency : project.getConfigurations().getByName(configuration).getDependencies()) {
            names.append(dependency.getGroup()).append(':').append(dependency.getName())
                    .append(':').append(dependency.getVersion()).append(' ');
        }
        return names.toString().trim();
    }

    private static String declaredVersion() throws IOException {
        Properties properties = new Properties();
        try (InputStream source = GoCraftPlugin.class.getResourceAsStream("/gocraft-plugin.properties")) {
            properties.load(source);
        }
        return properties.getProperty("version");
    }
}
