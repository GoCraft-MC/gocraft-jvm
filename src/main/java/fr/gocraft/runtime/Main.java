package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Dispatch;
import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Welcome;

import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/// The GoCraft Java plugin runtime.
///
/// It is spawned by the server, never run by hand: the host opens a Unix socket
/// first, then starts this process with the path to it, so there is no window
/// in which the runtime could race a listener that does not exist yet.
///
///     java -jar gocraft-runtime.jar --sock /run/gc-jvm-4f2a.sock --abi 1
///
/// Plugin memory state is not durable. This process can be killed and restarted
/// while the server keeps running — three missed pings are enough — so anything
/// a plugin keeps in a field is gone on respawn. That belongs at the top of the
/// plugin documentation, not in a footnote.
public final class Main {

    /// Refusing rather than negotiating. A runtime that guesses which ABI the
    /// host meant is worse than one that stops, because the failure surfaces
    /// later and somewhere else.
    private static final int SUPPORTED_ABI = 1;

    /// EX_CONFIG. The host reads the exit status to tell a mismatch from a
    /// crash, and 78 is what §13 pins for this one.
    private static final int EXIT_ABI_MISMATCH = 78;

    private static final int EXIT_USAGE = 64;
    private static final int EXIT_IO = 74;

    public static void main(String[] args) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException invalid) {
            System.err.println("gocraft-runtime: " + invalid.getMessage());
            System.err.println("usage: java -jar gocraft-runtime.jar --sock <path> --abi <version>");
            System.exit(EXIT_USAGE);
            return;
        }
        System.exit(run(arguments));
    }

    private static int run(Arguments arguments) {
        if (arguments.abi() != SUPPORTED_ABI) {
            System.err.printf("gocraft-runtime: host speaks ABI %d, this runtime speaks %d%n",
                    arguments.abi(), SUPPORTED_ABI);
            return EXIT_ABI_MISMATCH;
        }

        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Path.of(arguments.socket()));
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            try (Connection connection = new Connection(channel)) {
                return serve(connection);
            }
        } catch (IOException failure) {
            System.err.println("gocraft-runtime: " + failure.getMessage());
            return EXIT_IO;
        }
    }

    private static int serve(Connection connection) throws IOException {
        connection.send(Envelopes.hello(SUPPORTED_ABI, Runtime.version().toString()));

        Envelope greeting = connection.receive();
        if (!greeting.hasWelcome()) {
            System.err.println("gocraft-runtime: host opened with " + greeting.getBodyCase() + " instead of WELCOME");
            return EXIT_IO;
        }
        Welcome welcome = greeting.getWelcome();
        if (welcome.getAbi() != SUPPORTED_ABI) {
            System.err.printf("gocraft-runtime: host answered with ABI %d, this runtime speaks %d%n",
                    welcome.getAbi(), SUPPORTED_ABI);
            return EXIT_ABI_MISMATCH;
        }

        try (PluginRegistry registry = new PluginRegistry()) {
            return loop(connection, registry);
        }
    }

    /// The reader loop routes and nothing else.
    ///
    /// Anything that can take time has to leave this thread. A slow handler
    /// running here would stop the socket being read, so the host would time
    /// out on every subsequent event rather than on the one that was slow —
    /// which is how one misbehaving plugin takes the whole runtime down with
    /// it. LOAD is the deliberate exception: it is sequential by contract, and
    /// the host waits for each one before sending the next.
    private static int loop(Connection connection, PluginRegistry registry) throws IOException {
        while (true) {
            Envelope envelope;
            try {
                envelope = connection.receive();
            } catch (EOFException closed) {
                // The host went away without saying goodbye. Not an error to
                // report: it is how a killed server ends, and there is nobody
                // left to tell.
                return 0;
            }
            long seq = envelope.getSeq();
            switch (envelope.getBodyCase()) {
                // Inline, because load order is derived from the dependency
                // graph and a plugin may rely on an earlier one being up.
                case LOAD -> connection.send(registry.load(seq, envelope.getLoad()));
                case UNLOAD -> registry.unload(envelope.getUnload());
                // On a virtual thread, because a handler is plugin code and
                // may take as long as it likes. Running it here would stop the
                // socket being read, so the host would time out on every
                // subsequent event rather than on the one that was slow —
                // which is how one bad plugin takes the runtime down with it.
                case DISPATCH -> {
                    Dispatch request = envelope.getDispatch();
                    Thread.ofVirtual().start(() -> {
                        Envelope reply = registry.dispatch(seq, request);
                        try {
                            connection.send(reply);
                        } catch (IOException lost) {
                            System.err.println("gocraft-runtime: could not answer a dispatch: "
                                    + lost.getMessage());
                        }
                    });
                }
                // Answered here rather than by a handler, so a runtime busy
                // running plugin code still pongs. Silence has to mean stuck,
                // not merely busy, or the heartbeat measures the wrong thing.
                case PING -> connection.send(Envelopes.pong(seq));
                case READY -> {
                    // The load phase is over. Nothing to do until there are
                    // plugins to let loose.
                }
                case SHUTDOWN -> {
                    return 0;
                }
                default -> System.err.println(
                        "gocraft-runtime: ignoring unexpected " + envelope.getBodyCase());
            }
        }
    }

    /// The command line, which the host writes and no human does.
    record Arguments(String socket, int abi) {

        static Arguments parse(String[] args) {
            String socket = null;
            Integer abi = null;
            for (int index = 0; index < args.length; index++) {
                String flag = args[index];
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException(flag + " needs a value");
                }
                String value = args[++index];
                switch (flag) {
                    case "--sock" -> socket = value;
                    case "--abi" -> abi = parseAbi(value);
                    default -> throw new IllegalArgumentException("unknown flag " + flag);
                }
            }
            if (socket == null || socket.isBlank()) {
                throw new IllegalArgumentException("--sock is required");
            }
            if (abi == null) {
                throw new IllegalArgumentException("--abi is required");
            }
            return new Arguments(socket, abi);
        }

        private static int parseAbi(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("--abi " + value + " is not a number");
            }
        }
    }
}