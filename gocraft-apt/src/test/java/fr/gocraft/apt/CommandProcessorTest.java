package fr.gocraft.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/// What the annotations mean, proved by compiling them.
///
/// Every valid case here also proves the generated source is code: javac
/// compiles what the processor emitted in the same task, so an emitter that
/// produced something unparsable fails the test rather than the first plugin
/// that hits it.
class CommandProcessorTest {

    private static final String SHOP = """
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

                @Sub("admin reload") @Permission("shop.admin")
                void reload(CommandSender sender) {
                }
            }
            """;

    @Test
    void compilesTheWorkedExampleIntoBuilderCalls() throws IOException {
        Javac.Result result = Javac.compile("ShopCommands", SHOP);
        assertEquals("", result.firstError());

        String generated = result.source("ShopCommandsTree");
        assertTrue(generated.contains("Command.literal(\"shop\")"), generated);
        assertTrue(generated.contains(".permission(\"shop.use\")"), generated);
        assertTrue(generated.contains("Command.arg(\"price\", new ArgType.Decimal(0.01d, 1000.0d))"), generated);
        assertTrue(generated.contains("target.sell(context.sender(), context.decimal(\"price\"))"), generated);
        assertTrue(generated.contains(".permission(\"shop.admin\")"), generated);
        assertTrue(generated.contains("target.reload(context.sender())"), generated);
    }

    /// Two methods sharing a prefix are two paths through one command, not two
    /// commands. Merging them is the only reason this is a tree rather than a
    /// list.
    @Test
    void mergesPathsThatSharePrefixes() throws IOException {
        Javac.Result result = Javac.compile("Warp", """
                import fr.gocraft.api.command.Cmd;
                import fr.gocraft.api.command.Sub;

                @Cmd("warp")
                public final class Warp {
                    @Sub("set <name>")
                    void set(String name) {
                    }

                    @Sub("delete <name>")
                    void delete(String name) {
                    }
                }
                """);
        assertEquals("", result.firstError());
        String generated = result.source("WarpTree");
        assertEquals(1, count(generated, "Command.literal(\"warp\")"), generated);
        assertTrue(generated.contains("Command.literal(\"set\")"), generated);
        assertTrue(generated.contains("Command.literal(\"delete\")"), generated);
    }

    @Test
    void readsEveryArgumentTypeOffTheSignature() throws IOException {
        Javac.Result result = Javac.compile("Every", """
                import fr.gocraft.api.Block;
                import fr.gocraft.api.BlockPos;
                import fr.gocraft.api.ItemRef;
                import fr.gocraft.api.PlayerRef;
                import fr.gocraft.api.command.Cmd;
                import fr.gocraft.api.command.Greedy;
                import fr.gocraft.api.command.Sub;
                import java.time.Duration;

                @Cmd("every")
                public final class Every {
                    enum Mode { SURVIVAL, CREATIVE }

                    @Sub("count <amount>")   void count(int amount) {}
                    @Sub("who <target>")     void who(PlayerRef target) {}
                    @Sub("at <where>")       void at(BlockPos where) {}
                    @Sub("of <block>")       void of(Block block) {}
                    @Sub("give <item>")      void give(ItemRef item) {}
                    @Sub("wait <how>")       void wait(Duration how) {}
                    @Sub("mode <mode>")      void mode(Mode mode) {}
                    @Sub("say <message>")    void say(@Greedy String message) {}
                }
                """);
        assertEquals("", result.firstError());
        String generated = result.source("EveryTree");
        assertTrue(generated.contains("ArgType.integer()"), generated);
        assertTrue(generated.contains("ArgType.player()"), generated);
        assertTrue(generated.contains("ArgType.blockPos()"), generated);
        assertTrue(generated.contains("ArgType.blockState()"), generated);
        assertTrue(generated.contains("ArgType.item()"), generated);
        assertTrue(generated.contains("ArgType.duration()"), generated);
        assertTrue(generated.contains("ArgType.greedy()"), generated);
        // An enum completes its own constants; the author wrote the list once,
        // as a type.
        assertTrue(generated.contains("ArgType.oneOf(\"survival\", \"creative\")"), generated);
        assertTrue(generated.contains("(int) context.number(\"amount\")"), generated);
    }

    /// An empty path is the command itself.
    @Test
    void bindsTheCommandItself() throws IOException {
        Javac.Result result = Javac.compile("Ping", """
                import fr.gocraft.api.command.Cmd;
                import fr.gocraft.api.command.Sub;

                @Cmd("ping")
                public final class Ping {
                    @Sub("")
                    void ping() {
                    }
                }
                """);
        assertEquals("", result.firstError());
        assertTrue(result.source("PingTree").contains(".executes(context -> target.ping())"));
    }

    @Test
    void refusesAPathThatNamesAnArgumentTheMethodDoesNotTake() throws IOException {
        Javac.Result result = failing("""
                @Cmd("shop")
                public final class Broken {
                    @Sub("sell <price>")
                    void sell() {
                    }
                }
                """);
        assertTrue(result.firstError().contains("no parameter called price"), result.firstError());
    }

    @Test
    void refusesAParameterThePathNeverAsksFor() throws IOException {
        Javac.Result result = failing("""
                @Cmd("shop")
                public final class Broken {
                    @Sub("sell")
                    void sell(double price) {
                    }
                }
                """);
        assertTrue(result.firstError().contains("never asks for <price>"), result.firstError());
    }

    @Test
    void refusesSomethingAfterAGreedyArgument() throws IOException {
        Javac.Result result = failing("""
                @Cmd("shop")
                public final class Broken {
                    @Sub("say <message> loudly")
                    void say(@fr.gocraft.api.command.Greedy String message) {
                    }
                }
                """);
        assertTrue(result.firstError().contains("nothing may follow it"), result.firstError());
    }

    @Test
    void refusesTwoMethodsOnOnePath() throws IOException {
        Javac.Result result = failing("""
                @Cmd("shop")
                public final class Broken {
                    @Sub("sell")
                    void sell() {
                    }

                    @Sub("sell")
                    void sellAgain() {
                    }
                }
                """);
        assertTrue(result.firstError().contains("same path"), result.firstError());
    }

    @Test
    void refusesOneArgumentDeclaredTwoWays() throws IOException {
        Javac.Result result = failing("""
                @Cmd("pay")
                public final class Broken {
                    @Sub("<amount> now")
                    void now(double amount) {
                    }

                    @Sub("<amount> later")
                    void later(String amount) {
                    }
                }
                """);
        assertTrue(result.firstError().contains("declared as"), result.firstError());
    }

    @Test
    void refusesATypeNoEditionCanRender() throws IOException {
        Javac.Result result = failing("""
                @Cmd("shop")
                public final class Broken {
                    @Sub("sell <price>")
                    void sell(java.util.List<String> price) {
                    }
                }
                """);
        assertTrue(result.firstError().contains("command argument can carry"), result.firstError());
    }

    @Test
    void refusesAPrivateOrStaticMethod() throws IOException {
        assertTrue(failing("""
                @Cmd("shop")
                public final class Broken {
                    @Sub("sell")
                    private void sell() {
                    }
                }
                """).firstError().contains("cannot be private"));
        assertTrue(failing("""
                @Cmd("shop")
                public final class Broken {
                    @Sub("sell")
                    static void sell() {
                    }
                }
                """).firstError().contains("cannot be static"));
    }

    @Test
    void refusesAGuardOnAValue() throws IOException {
        Javac.Result result = failing("""
                @Cmd("shop")
                public final class Broken {
                    @Sub("sell <price>") @fr.gocraft.api.command.Permission("shop.sell")
                    void sell(double price) {
                    }
                }
                """);
        assertTrue(result.firstError().contains("guards a literal"), result.firstError());
    }

    @Test
    void refusesACommandThatRunsNothing() throws IOException {
        Javac.Result result = failing("""
                @Cmd("shop")
                public final class Broken {
                }
                """);
        assertTrue(result.firstError().contains("runs nothing"), result.firstError());
    }


    /// The second half of §15's job: what only javac can see, handed to the one
    /// program that writes bundles.
    ///
    /// No executor ids in it. They are minted by whoever writes the wire tree,
    /// and the generated builder mints its own at runtime; a third set here
    /// would be a third thing to keep in step.
    @Test
    void writesTheTreeForTheBundleBuild() throws IOException {
        Javac.Result result = Javac.compile("ShopCommands", SHOP);
        assertEquals("", result.firstError());

        String json = result.intermediate();
        assertTrue(json.contains("\"version\": 1"), json);
        assertTrue(json.contains("\"name\": \"shop\""), json);
        assertTrue(json.contains("\"permission\": \"shop.use\""), json);
        assertTrue(json.contains("\"permission\": \"shop.admin\""), json);
        assertTrue(json.contains("\"argument\": true"), json);
        // The type is described, not spelled as Java: the program that reads
        // this has no opinion about ArgType expressions.
        assertTrue(json.contains("\"kind\": \"decimal\", \"min\": 0.01, \"max\": 1000.0"), json);
        assertTrue(json.contains("\"runs\": true"), json);
        assertTrue(!json.contains("executor"), "the intermediate names an executor id");
        assertTrue(!json.contains("ArgType"), "the intermediate leaked Java");
    }

    @Test
    void describesEveryArgumentKindNeutrally() throws IOException {
        Javac.Result result = Javac.compile("Every", """
                import fr.gocraft.api.BlockPos;
                import fr.gocraft.api.command.Cmd;
                import fr.gocraft.api.command.Greedy;
                import fr.gocraft.api.command.Range;
                import fr.gocraft.api.command.Sub;
                import java.time.Duration;

                @Cmd("every")
                public final class Every {
                    enum Mode { SURVIVAL, CREATIVE }

                    @Sub("count <amount>") void count(@Range(min = 1) int amount) {}
                    @Sub("at <where>")     void at(BlockPos where) {}
                    @Sub("wait <how>")     void wait(Duration how) {}
                    @Sub("mode <mode>")    void mode(Mode mode) {}
                    @Sub("say <message>")  void say(@Greedy String message) {}
                }
                """);
        assertEquals("", result.firstError());
        String json = result.intermediate();
        // A bound left out is absent, not saturated: "open above" and "at most
        // the largest long" are different statements.
        assertTrue(json.contains("\"kind\": \"integer\", \"min\": 1"), json);
        assertTrue(!json.contains("\"max\": 9223372036854775807"), json);
        assertTrue(json.contains("\"kind\": \"block_pos\""), json);
        assertTrue(json.contains("\"kind\": \"duration\""), json);
        assertTrue(json.contains("\"kind\": \"greedy\""), json);
        assertTrue(json.contains("\"kind\": \"enum\", \"options\": [\"survival\", \"creative\"]"), json);
    }

    /// A compilation that failed writes nothing. A bundle built from a tree
    /// whose code did not compile would be a bundle promising commands that do
    /// not exist.
    @Test
    void writesNothingWhenTheCommandIsMalformed() throws IOException {
        assertEquals("", failing("""
                @Cmd("shop")
                public final class Broken {
                    @Sub("sell <price>")
                    void sell() {
                    }
                }
                """).intermediate());
    }

    private static Javac.Result failing(String body) throws IOException {
        Javac.Result result = Javac.compile("Broken", """
                import fr.gocraft.api.command.Cmd;
                import fr.gocraft.api.command.Sub;

                """ + body);
        assertTrue(!result.errors().isEmpty(), "the processor accepted it");
        return result;
    }

    private static int count(String haystack, String needle) {
        int seen = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            seen++;
        }
        return seen;
    }
}
