package fr.gocraft.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/// Turns a Java project into a GoCraft plugin build.
///
/// The whole reason it exists is in the dependencies it declares. An author who
/// writes those coordinates by hand writes versions that have to agree and
/// nothing checks that they do; here they come from this plugin's own version,
/// so moving forward is one number.
///
/// It also keeps the JitPack coordinates out of every plugin's build file. They
/// are an interim host, and the day these artefacts move to Maven Central, an
/// author who wrote them out has to edit their build and an author who applied
/// this plugin does not notice.
public final class GoCraftPlugin implements Plugin<Project> {

    /// Where the API and the processor come from while Maven Central is out of
    /// reach: fr.gocraft needs a proven claim to gocraft.fr, JitPack needs only
    /// a tag.
    private static final String JITPACK = "https://jitpack.io";
    private static final String GROUP = "com.github.GoCraft-MC.gocraft-jvm";

    /// The packer is a different repository on a different clock.
    ///
    /// It would be tidy to give it this plugin's version and wrong: gocraft-cli
    /// was split out precisely so a plugin author does not update their build
    /// tool because a server fixed something. What ties them is this constant —
    /// the release this plugin was built and tested against — moved
    /// deliberately when a new one is worth having.
    private static final String TOOL_REPOSITORY = "https://github.com/GoCraft-MC/gocraft-cli";
    private static final String TOOL_VERSION = "v0.1.1";

    /// Java 25, because the runtime that loads this plugin is on 25 and a class
    /// file it cannot read is a plugin that fails at load rather than at build.
    /// A convention rather than a decree: an author who knows better can say so.
    private static final int JAVA = 25;

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        GoCraftExtension extension = project.getExtensions()
                .create("gocraft", GoCraftExtension.class);
        extension.getBundleName().convention(project.getName());
        extension.getToolVersion().convention(TOOL_VERSION);
        extension.getToolRepository().convention(TOOL_REPOSITORY);
        extension.getIncludeDependencies().convention(true);

        project.getRepositories().maven(repository -> {
            repository.setName("jitpack");
            repository.setUrl(JITPACK);
        });

        String artefacts = artefactVersion();
        project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                GROUP + ":gocraft-api-jvm:" + artefacts);
        project.getDependencies().add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME,
                GROUP + ":gocraft-apt:" + artefacts);

        project.getExtensions().getByType(JavaPluginExtension.class)
                .getToolchain().getLanguageVersion().convention(JavaLanguageVersion.of(JAVA));

        var tool = project.getTasks().register("gocraftTool", ResolveToolTask.class, task -> {
            task.setGroup("gocraft");
            task.setDescription("Downloads and verifies the bundle packer for this machine.");
            task.getVersion().set(extension.getToolVersion());
            task.getRepository().set(extension.getToolRepository());
            task.getLocal().set(extension.getToolPath());
            task.getTool().set(project.getLayout().getBuildDirectory()
                    .file(extension.getToolVersion().map(name -> "gocraft/tool/gocraft-cli-" + name)));
        });

        project.getTasks().register("gocraftBundle", BundleTask.class, task -> {
            task.setGroup("gocraft");
            task.setDescription("Packs this plugin into a .gcpkg bundle.");
            task.getTool().set(tool.flatMap(ResolveToolTask::getTool));
            task.getManifest().set(project.getLayout().getProjectDirectory().file("plugin.toml"));
            task.getStaging().set(project.getLayout().getBuildDirectory().dir("gocraft/staging"));
            task.getBundle().set(project.getLayout().getBuildDirectory()
                    .file(extension.getBundleName().map(name -> "gocraft/" + name + ".gcpkg")));

            var jar = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
            task.getPayload().from(jar.flatMap(Jar::getArchiveFile));
            task.getPayload().from(project.provider(() -> extension.getIncludeDependencies().get()
                    ? project.getConfigurations()
                            .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                    : project.files()));

            // Where gocraft-apt wrote while javac ran. Absent for a plugin that
            // declares no commands, which the task tolerates.
            task.getCommands().set(project.getLayout().getBuildDirectory()
                    .file("classes/java/main/gocraft/commands.json"));
        });
    }

    /// This plugin's version, spelled as the tag that published it.
    ///
    /// JitPack serves a tag verbatim, and the tags here are the version with a
    /// `v`. That convention is load-bearing: get it wrong and every plugin
    /// build fails to resolve an artefact that exists.
    private static String artefactVersion() {
        Properties properties = new Properties();
        try (InputStream source = GoCraftPlugin.class.getResourceAsStream("/gocraft-plugin.properties")) {
            if (source == null) {
                throw new IllegalStateException("gocraft-plugin.properties is missing from the plugin jar");
            }
            properties.load(source);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        String version = properties.getProperty("version", "").trim();
        if (version.isEmpty()) {
            throw new IllegalStateException("the plugin jar declares no version");
        }
        return version.startsWith("v") ? version : "v" + version;
    }
}
