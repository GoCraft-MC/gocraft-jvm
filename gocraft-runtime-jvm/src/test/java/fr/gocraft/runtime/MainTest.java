package fr.gocraft.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The command line is written by the host and read by nobody else, so the only
/// way a mistake in it surfaces is a runtime that fails to start with a message
/// about something unrelated.
class MainTest {

    @Test
    void parsesWhatTheHostSends() {
        Main.Arguments arguments = Main.Arguments.parse(
                new String[]{"--sock", "/tmp/gc-jvm-4f2a.sock", "--abi", "1"});

        assertEquals("/tmp/gc-jvm-4f2a.sock", arguments.socket());
        assertEquals(1, arguments.abi());
    }

    @Test
    void acceptsEitherOrder() {
        Main.Arguments arguments = Main.Arguments.parse(
                new String[]{"--abi", "1", "--sock", "/tmp/s.sock"});

        assertEquals("/tmp/s.sock", arguments.socket());
        assertEquals(1, arguments.abi());
    }

    /// A Windows socket path carries a drive letter and backslashes, and gets
    /// passed straight through. Nothing here may treat it as a flag or split
    /// it.
    @Test
    void keepsAWindowsPathIntact() {
        String path = "C:\\Users\\x\\AppData\\Local\\Temp\\gc-jvm-1.sock";
        assertEquals(path, Main.Arguments.parse(new String[]{"--sock", path, "--abi", "1"}).socket());
    }

    @Test
    void refusesAMissingSocket() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Main.Arguments.parse(new String[]{"--abi", "1"}));
        assertTrue(failure.getMessage().contains("--sock"), failure.getMessage());
    }

    @Test
    void refusesAMissingAbi() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Main.Arguments.parse(new String[]{"--sock", "/tmp/s.sock"}));
        assertTrue(failure.getMessage().contains("--abi"), failure.getMessage());
    }

    /// A flag with nothing after it must not silently consume the end of the
    /// array, which is how "--sock --abi" becomes a socket literally named
    /// "--abi".
    @Test
    void refusesAFlagWithNoValue() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.Arguments.parse(new String[]{"--sock"}));
    }

    @Test
    void refusesAnAbiThatIsNotANumber() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Main.Arguments.parse(new String[]{"--sock", "/tmp/s.sock", "--abi", "one"}));
        assertTrue(failure.getMessage().contains("not a number"), failure.getMessage());
    }

    @Test
    void refusesAnUnknownFlag() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.Arguments.parse(new String[]{"--sock", "/tmp/s.sock", "--abi", "1", "--verbose", "y"}));
    }
}