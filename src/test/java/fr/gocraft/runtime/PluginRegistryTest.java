package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Load;
import fr.gocraft.abi.v1.Unload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// How the registry answers, as opposed to what it loads — that is
/// [PluginLoadingTest].
///
/// Silence is the failure that matters here. The host registers a pending seq
/// before it writes, so a LOAD nobody answers holds the boot open until it
/// times out, with nothing said about why.
class PluginRegistryTest {

    private static Load load(String id, String bundle, String entry) {
        return Load.newBuilder().setPluginId(id).setBundlePath(bundle).setEntry(entry).build();
    }

    private PluginRegistry registry(Path directory) throws Exception {
        return new PluginRegistry(Files.createDirectories(directory.resolve("work")));
    }

    @Test
    void echoesTheSequenceItWasAsked(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = registry(directory)) {
            Envelope reply = registry.load(42, load("fr.oreo.hello", "/no/such.gcpkg", "x.Y"));

            assertEquals(42, reply.getSeq(),
                    "the host matches a reply to its request by seq; a wrong one is never delivered");
        }
    }

    /// The reason reaches an admin verbatim, so a missing bundle names the path
    /// rather than the exception that noticed it — and names it exactly as the
    /// host sent it. Round-tripping through Path would rewrite the separators,
    /// handing a Windows admin a string that no longer matches their own
    /// configuration or the server's log.
    @Test
    void namesTheBundleItCouldNotReadAsTheHostSpelledIt(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = registry(directory)) {
            Envelope reply = registry.load(1, load("fr.oreo.hello", "/no/such/bundle.gcpkg", "x.Y"));

            assertTrue(reply.hasFail());
            assertEquals("fr.oreo.hello", reply.getFail().getPluginId());
            assertTrue(reply.getFail().getReason().contains("/no/such/bundle.gcpkg"),
                    reply.getFail().getReason());
        }
    }

    @Test
    void refusesALoadWithNoIdentity(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = registry(directory)) {
            Envelope reply = registry.load(1, load("", "/tmp/x.gcpkg", "x.Y"));

            assertTrue(reply.hasFail());
            assertTrue(reply.getFail().getReason().contains("no plugin id"),
                    reply.getFail().getReason());
        }
    }

    /// §05 makes the main class optional, but only once the runtime can find
    /// annotated classes by itself. Until then, saying so beats loading a
    /// plugin that would do nothing.
    @Test
    void refusesAManifestWithNoEntryClass(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = registry(directory)) {
            Envelope reply = registry.load(1, load("fr.oreo.hello", "/tmp/x.gcpkg", ""));

            assertTrue(reply.hasFail());
            assertTrue(reply.getFail().getReason().contains("entry class"),
                    reply.getFail().getReason());
        }
    }

    @Test
    void forgetsNothingItNeverLoaded(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = registry(directory)) {
            registry.load(1, load("fr.oreo.hello", "/no/such/bundle.gcpkg", "x.Y"));

            assertEquals(0, registry.size());
            assertFalse(registry.isLoaded("fr.oreo.hello"));
            // Unloading something that never loaded is how a rolled-back boot
            // unwinds, and must not throw.
            registry.unload(Unload.newBuilder().setPluginId("fr.oreo.hello").build());
        }
    }

    /// The host blocks its tick on a verdict. Answering an unknown plugin
    /// without cancelling leaves the outcome to the other subscribers; saying
    /// nothing would burn the whole shared event budget first, and charge it to
    /// subscribers that never ran.
    @Test
    void answersADispatchForAPluginItDoesNotHave(@TempDir Path directory) throws Exception {
        try (PluginRegistry registry = registry(directory)) {
            Envelope reply = registry.dispatch(9, "fr.oreo.hello");

            assertEquals(9, reply.getSeq());
            assertTrue(reply.hasVerdict());
            assertFalse(reply.getVerdict().getCancelled());
        }
    }
}