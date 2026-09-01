package fr.gocraft.api.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/// The rules a tree has to satisfy, stated where an author trips over them.
///
/// They are the same rules the host applies when it opens a bundle. Stating
/// them twice is the price of two languages; what makes it safe is that both
/// statements are about the same tree, and a tree that fails here never reaches
/// the host to be refused a second time.
class CommandTreeTest {

    private static CommandNode.Literal literal(String name, int executor, CommandNode... children) {
        return new CommandNode.Literal(name, "", executor, List.of(children));
    }

    private static CommandNode.Argument argument(String name, ArgType type, int executor, CommandNode... children) {
        return new CommandNode.Argument(name, type, executor, List.of(children));
    }

    @Test
    void buildsTheWorkedExample() {
        CommandTree tree = CommandTree.of(new CommandNode.Literal("shop", "shop.use", 0, List.of(
                literal("sell", 0, argument("price", ArgType.decimal(0.01, 1_000_000), 1)),
                new CommandNode.Literal("admin", "shop.admin", 0, List.of(literal("reload", 2))))));

        assertEquals(List.of(1, 2), tree.executors());
    }

    /// A node may run and still have children: /kill runs alone and /kill
    /// <player> runs on someone else. Refusing that would make the tree unable
    /// to say what half of vanilla already says.
    @Test
    void allowsAnOptionalArgument() {
        CommandTree tree = CommandTree.of(literal("kill", 1, argument("player", ArgType.player(), 2)));
        assertEquals(List.of(1, 2), tree.executors());
    }

    @Test
    void refusesAnArgumentAtTheTop() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CommandTree.of(argument("price", ArgType.decimal(), 1)));
        assertTrue(thrown.getMessage().contains("only hold literals at the top"), thrown.getMessage());
    }

    @Test
    void refusesAnEmptyTree() {
        assertThrows(IllegalArgumentException.class, CommandTree::of);
    }

    @Test
    void refusesTwoSiblingsWithOneName() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CommandTree.of(literal("shop", 0, literal("sell", 1), literal("sell", 2))));
        assertTrue(thrown.getMessage().contains("duplicate literal"), thrown.getMessage());
    }

    /// A literal and an argument may share a level — that is what backtracking
    /// is for — so only a name repeated within one kind is a conflict.
    @Test
    void allowsALiteralBesideAnArgument() {
        CommandTree.of(literal("warp", 0, literal("home", 1), argument("home", ArgType.string(), 2)));
    }

    @Test
    void refusesSomethingAfterAGreedyArgument() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> argument("message", ArgType.greedy(), 0, literal("later", 1)));
        assertTrue(thrown.getMessage().contains("must be last"), thrown.getMessage());
    }

    /// A leaf that runs nothing is a command an author meant to finish. Saying
    /// so here is the difference between a compile error and a command that is
    /// silently never called.
    @Test
    void refusesALeafThatRunsNothing() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CommandTree.of(literal("shop", 0, literal("sell", 0))));
        assertTrue(thrown.getMessage().contains("nothing runs here"), thrown.getMessage());
    }

    @Test
    void namesTheBranchThatFailed() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CommandTree.of(literal("shop", 0, literal("admin", 0, literal("reload", 0)))));
        assertTrue(thrown.getMessage().contains("/shop/admin/reload"), thrown.getMessage());
    }

    @Test
    void refusesANameAPlayerCouldNotType() {
        assertThrows(IllegalArgumentException.class, () -> literal("", 1));
        assertThrows(IllegalArgumentException.class, () -> literal(" shop", 1));
        assertThrows(IllegalArgumentException.class, () -> literal("two words", 1));
        assertThrows(IllegalArgumentException.class, () -> literal("shop/sell", 1));
    }

    @Test
    void reportsEachExecutorOnce() {
        CommandTree tree = CommandTree.of(literal("gamemode", 0,
                literal("creative", 7), literal("survival", 7), literal("adventure", 3)));
        assertEquals(List.of(3, 7), tree.executors());
    }
}
