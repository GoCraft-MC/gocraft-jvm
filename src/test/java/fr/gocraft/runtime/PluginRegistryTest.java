package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Load;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// This slice loads nothing, so what is under test is the answering — every
/// LOAD gets a reply, carrying the seq the host is waiting on and a reason an
/// admin can act on.
///
/// Silence is the failure that matters here. The host registers a pending seq
/// before it writes, so a LOAD nobody answers holds the boot open until it
/// times out, with nothing said about why.
class PluginRegistryTest {

    private static Load load(String id, String bundle) {
        return Load.newBuilder().setPluginId(id).setBundlePath(bundle).build();
    }

    @Test
    void echoesTheSequenceItWasAsked() {
        Envelope reply = new PluginRegistry().load(42, load("fr.oreo.hello", ""));

        assertEquals(42, reply.getSeq(),
                "the host matches a reply to its request by seq; a wrong one is never delivered");
    }

    @Test
    void refusesAPluginItCannotLoadYetAndSaysSo() throws Exception {
        Path bundle = Files.createTempFile("hello", ".gcpkg");
        try {
            Envelope reply = new PluginRegistry().load(1, load("fr.oreo.hello", bundle.toString()));

            assertTrue(reply.hasFail());
            assertEquals("fr.oreo.hello", reply.getFail().getPluginId());
            assertTrue(reply.getFail().getReason().contains("next milestone"),
                    reply.getFail().getReason());
        } finally {
            Files.deleteIfExists(bundle);
        }
    }

    /// The reason reaches an admin verbatim, so a missing bundle has to name
    /// the path rather than the exception that noticed it.
    @Test
    void namesTheBundleItCouldNotRead() {
        Envelope reply = new PluginRegistry().load(1, load("fr.oreo.hello", "/no/such/bundle.gcpkg"));

        assertTrue(reply.hasFail());
        assertTrue(reply.getFail().getReason().contains("/no/such/bundle.gcpkg"),
                reply.getFail().getReason());
    }

    @Test
    void refusesALoadWithNoIdentity() {
        Envelope reply = new PluginRegistry().load(1, load("", "/tmp/x.gcpkg"));

        assertTrue(reply.hasFail());
        assertTrue(reply.getFail().getReason().contains("no plugin id"),
                reply.getFail().getReason());
    }

    @Test
    void loadsNothing() {
        PluginRegistry registry = new PluginRegistry();
        registry.load(1, load("fr.oreo.hello", "/no/such/bundle.gcpkg"));

        assertEquals(0, registry.size());
        assertFalse(registry.isLoaded("fr.oreo.hello"));
    }

    /// The host blocks its tick on a verdict. Answering an unknown plugin
    /// without cancelling leaves the outcome to the other subscribers; saying
    /// nothing would burn the whole shared event budget first.
    @Test
    void answersADispatchForAPluginItDoesNotHave() {
        Envelope reply = new PluginRegistry().dispatch(9, "fr.oreo.hello");

        assertEquals(9, reply.getSeq());
        assertTrue(reply.hasVerdict());
        assertFalse(reply.getVerdict().getCancelled());
    }
}