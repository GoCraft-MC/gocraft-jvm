package fr.gocraft.api.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.gocraft.api.CommandContext;
import fr.gocraft.api.CommandHandler;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandTest {

    private static CommandHandler noop() {
        return context -> {
        };
    }

    /// A handler that captures, so two of them are two objects. A lambda with
    /// nothing to capture is instantiated once per call site and shared, which
    /// would make "these are different invokers" trivially true and useless.
    private static CommandHandler recording(StringBuilder into, String mark) {
        return context -> into.append(mark);
    }

    @Test
    void buildsTheWorkedExample() {
        CommandHandler sell = noop();
        CommandSet set = Command.tree(Command.literal("shop")
                .permission("shop.use")
                .then(Command.literal("sell")
                        .then(Command.arg("price", ArgType.decimal(0.01, 1_000_000)).executes(sell))));

        CommandNode.Literal shop = (CommandNode.Literal) set.tree().commands().getFirst();
        assertEquals("shop", shop.name());
        assertEquals("shop.use", shop.permission());
        assertEquals(0, shop.executor());

        CommandNode.Literal sellNode = (CommandNode.Literal) shop.children().getFirst();
        CommandNode.Argument price = (CommandNode.Argument) sellNode.children().getFirst();
        assertEquals(ArgType.Kind.DECIMAL, price.type().kind());
        assertNotEquals(0, price.executor());
        assertSame(sell, set.invokers().get(price.executor()));
    }

    /// An author never writes an id, so two commands in one set must not be
    /// handed the same one — that would have one silently running the other's
    /// handler.
    @Test
    void givesEveryExecutorItsOwnId() {
        StringBuilder ran = new StringBuilder();
        CommandHandler first = recording(ran, "spawn");
        CommandHandler second = recording(ran, "warp");
        CommandSet set = Command.tree(
                Command.literal("spawn").executes(first),
                Command.literal("warp").executes(second));

        assertEquals(2, set.invokers().size());
        assertEquals(List.of(1, 2), set.tree().executors());
        assertSame(first, set.invokers().get(1));
        assertSame(second, set.invokers().get(2));
    }

    /// A node that runs and still has branches is how an optional argument is
    /// spelled, and each half reaches its own code.
    @Test
    void bindsBothHalvesOfAnOptionalArgument() {
        StringBuilder ran = new StringBuilder();
        CommandHandler bare = recording(ran, "bare");
        CommandHandler aimed = recording(ran, "aimed");
        CommandSet set = Command.tree(Command.literal("kill")
                .executes(bare)
                .then(Command.arg("player", ArgType.player()).executes(aimed)));

        CommandNode.Literal kill = (CommandNode.Literal) set.tree().commands().getFirst();
        CommandNode.Argument player = (CommandNode.Argument) kill.children().getFirst();
        assertSame(bare, set.invokers().get(kill.executor()));
        assertSame(aimed, set.invokers().get(player.executor()));
    }

    @Test
    void refusesTwoExecutorsOnOneNode() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Command.literal("shop").executes(noop()).executes(noop()));
        assertTrue(thrown.getMessage().contains("already runs something"), thrown.getMessage());
    }

    /// The tree's own rules answer here too: the builder is a way of writing
    /// one, not a way around it.
    @Test
    void appliesTheTreeRulesAtBuild() {
        assertThrows(IllegalArgumentException.class,
                () -> Command.tree(Command.literal("shop").then(Command.literal("sell"))));
        assertThrows(IllegalArgumentException.class,
                () -> Command.tree(Command.literal("shop")
                        .then(Command.literal("sell").executes(noop()))
                        .then(Command.literal("sell").executes(noop()))));
        assertThrows(IllegalArgumentException.class, Command::tree);
    }

    /// A set whose tree points at an executor nothing bound is a command that
    /// looks registered and does nothing when typed.
    @Test
    void refusesAnExecutorWithNoInvoker() {
        CommandTree tree = CommandTree.of(new CommandNode.Literal("shop", "", 4, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CommandSet(tree, java.util.Map.of()));
    }

    @Test
    void refusesAnInvokerNothingReaches() {
        CommandTree tree = CommandTree.of(new CommandNode.Literal("shop", "", 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandSet(tree, java.util.Map.of(1, noop(), 2, noop())));
    }

    @Test
    void runsWhatItBound() throws Exception {
        StringBuilder ran = new StringBuilder();
        CommandSet set = Command.tree(Command.literal("ping")
                .executes(context -> ran.append("pong")));
        CommandHandler invoker = set.invokers().get(set.tree().executors().getFirst());
        invoker.handle(new CommandContext(
                new fr.gocraft.api.CommandSender("Console", fr.gocraft.api.PlayerRef.NONE, java.util.Map.of()),
                java.util.Map.of()));
        assertEquals("pong", ran.toString());
    }
}
