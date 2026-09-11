package fr.gocraft.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeEventFixtureTest {
    @Test
    void buildsPortableHostIntegrationFixture(@TempDir Path temporary) throws Exception {
        String output = System.getenv("GOCRAFT_EVENT_FIXTURE_DIR");
        Path directory = output == null ? temporary : Files.createDirectories(Path.of(output));
        Path bundle = TestBundles.bundle(directory, "BenchmarkPlugin", """
                package test.plugin;
                import fr.gocraft.api.*;
                import fr.gocraft.api.event.PlayerChatEvent;
                public final class BenchmarkPlugin implements Plugin {
                    @Subscribe public void chat(PlayerChatEvent event, EventControl control) {
                        if (event.message().equals("cancel")) control.cancel();
                        event.setMessage("rewritten");
                    }
                }
                """);
        assertTrue(Files.size(bundle) > 0);
    }
}
