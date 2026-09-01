package fr.gocraft.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/// Fetches the bundle packer for whatever machine the build runs on.
///
/// Downloaded rather than resolved as a dependency, because gocraft-cli is a
/// native program and not a jar. Dressing it as a Maven artefact with a
/// platform classifier would buy a coordinate nobody reads and a repository
/// that cannot serve it anyway.
///
/// The download is verified against the release's `checksums.txt` before it is
/// ever executed. A build that runs an unverified binary it just pulled off the
/// network is a supply chain with a hole in it, and this one runs on other
/// people's machines.
public abstract class ResolveToolTask extends DefaultTask {

    @Input
    public abstract Property<String> getVersion();

    @Input
    public abstract Property<String> getRepository();

    /// A packer the author supplied. Set means nothing is fetched.
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getLocal();

    @OutputFile
    public abstract RegularFileProperty getTool();

    @TaskAction
    public void resolve() {
        if (getLocal().isPresent() && !getLocal().get().isBlank()) {
            Path supplied = Path.of(getLocal().get());
            if (!Files.isRegularFile(supplied)) {
                throw new GradleException("gocraft.toolPath is " + supplied + ", which is not a file");
            }
            copyLocal(supplied);
            return;
        }
        String version = getVersion().get();
        String asset = assetName(version);
        Path target = getTool().get().getAsFile().toPath();

        String expected = expectedDigest(asset, version);
        if (Files.isRegularFile(target) && expected.equals(digest(target))) {
            // Already here and still what it claims to be. The download is a
            // network round trip on a machine that may not have one.
            return;
        }
        download(url(version, asset), target);
        String actual = digest(target);
        if (!expected.equals(actual)) {
            silentlyDelete(target);
            throw new GradleException("gocraft-cli " + version + " does not match its published checksum. "
                    + "Expected " + expected + ", got " + actual + ". Nothing was run.");
        }
        target.toFile().setExecutable(true, true);
    }

    /// The naming is a contract with the release workflow, constructed here and
    /// therefore not free to change there.
    private static String assetName(String version) {
        return "gocraft-cli_" + version + "_" + operatingSystem() + "_" + architecture() + extension();
    }

    private static String operatingSystem() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) {
            return "darwin";
        }
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("linux")) {
            return "linux";
        }
        throw new GradleException("no gocraft-cli build for " + System.getProperty("os.name")
                + "; set gocraft.toolRepository at a mirror that has one");
    }

    private static String architecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "amd64";
        }
        throw new GradleException("no gocraft-cli build for " + System.getProperty("os.arch"));
    }

    private static String extension() {
        return operatingSystem().equals("windows") ? ".exe" : "";
    }

    private String url(String version, String asset) {
        return getRepository().get() + "/releases/download/" + version + "/" + asset;
    }

    private String expectedDigest(String asset, String version) {
        String listing = read(url(version, "checksums.txt"));
        for (String line : listing.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            // sha256sum writes "<digest>  <name>", the name possibly marked
            // with a leading * for binary mode.
            if (parts.length == 2 && parts[1].replaceFirst("^\\*", "").equals(asset)) {
                return parts[0].toLowerCase(Locale.ROOT);
            }
        }
        throw new GradleException("release " + version + " lists no checksum for " + asset
                + "; it publishes: " + listing.trim().replace("\n", ", "));
    }

    private static String read(String from) {
        try (InputStream stream = URI.create(from).toURL().openStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new GradleException("cannot read " + from + ": " + failure.getMessage(), failure);
        }
    }

    private static void download(String from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            try (InputStream stream = URI.create(from).toURL().openStream()) {
                Files.copy(stream, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new GradleException("cannot download " + from + ": " + failure.getMessage(), failure);
        }
    }

    private static String digest(Path file) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /// Copied rather than used where it lies, so the task has the output it
    /// declares and Gradle can tell whether it changed.
    private void copyLocal(Path supplied) {
        Path target = getTool().get().getAsFile().toPath();
        try {
            Files.createDirectories(target.getParent());
            Files.copy(supplied, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new GradleException("cannot use " + supplied + ": " + failure.getMessage(), failure);
        }
        target.toFile().setExecutable(true, true);
    }

    private static void silentlyDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The checksum failure is what matters; a leftover file is not.
        }
    }
}
