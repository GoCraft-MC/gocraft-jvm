package fr.gocraft.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.gocraft.api.CommandHandler;
import fr.gocraft.api.command.ArgType;
import fr.gocraft.api.command.Command;
import fr.gocraft.api.command.CommandNode;
import fr.gocraft.api.command.CommandSet;
import fr.gocraft.api.command.CommandTree;
import fr.gocraft.api.command.SubCommand;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/// The same command, declared three ways, is one tree.
///
/// §07 puts three facades in front of an author and lets none of them touch the
/// wire format: they are constructors of a single IR, and this is the test that
/// says so. Without it, three ways of writing a command become three sets of
/// bugs — one facade quietly guarding a different node, another spelling an
/// argument as a string where the first said decimal, and nobody finding out
/// until a plugin written with the wrong one reaches a server.
///
/// Executor ids are left out of the comparison on purpose. They are assigned as
/// a tree is built and belong to the set, not to the command: what has to match
/// is the structure, the permissions, the argument types and which nodes run.
class ConformanceTest {

    private static final String ANNOTATED = """
            import fr.gocraft.api.CommandSender;
            import fr.gocraft.api.command.Cmd;
            import fr.gocraft.api.command.Greedy;
            import fr.gocraft.api.command.Permission;
            import fr.gocraft.api.command.Range;
            import fr.gocraft.api.command.Sub;

            @Cmd("shop") @Permission("shop.use")
            public final class ShopCommands {

                @Sub("")
                void help(CommandSender sender) {
                }

                @Sub("sell <price>")
                void sell(CommandSender sender, @Range(min = 0.01, max = 1000) double price) {
                }

                @Sub("admin reload") @Permission("shop.admin")
                void reload(CommandSender sender) {
                }

                @Sub("say <message>")
                void say(@Greedy String message) {
                }
            }
            """;

    private static CommandHandler noop() {
        return context -> {
        };
    }

    /// The same command, written with the canonical builder.
    private static CommandSet byBuilder() {
        return Command.tree(Command.literal("shop")
                .permission("shop.use")
                .executes(noop())
                .then(Command.literal("sell")
                        .then(Command.arg("price", new ArgType.Decimal(0.01d, 1000.0d)).executes(noop())))
                // Guarded on the leaf, not on "admin": that is where @Permission
                // puts it, and it is the more precise of the two. A sibling of
                // reload needing no permission still shows, and the branch above
                // disappears on its own once every child under it is hidden.
                .then(Command.literal("admin")
                        .then(Command.literal("reload").permission("shop.admin").executes(noop())))
                .then(Command.literal("say")
                        .then(Command.arg("message", ArgType.greedy()).executes(noop()))));
    }

    /// The same command again, by extending a base class.
    private static final class ShopShim extends SubCommand {
        ShopShim() {
            super("shop");
            permission("shop.use");
            executes(noop());
            add(Command.literal("sell")
                    .then(Command.arg("price", new ArgType.Decimal(0.01d, 1000.0d)).executes(noop())));
            add(Command.literal("admin")
                    .then(Command.literal("reload").permission("shop.admin").executes(noop())));
            add(Command.literal("say")
                    .then(Command.arg("message", ArgType.greedy()).executes(noop())));
        }
    }

    @Test
    void threeFacadesProduceOneTree() throws Exception {
        try (Javac.Loaded compiled = Javac.compileAndLoad("ShopCommands", ANNOTATED)) {
            assertEquals("", compiled.result().firstError());

            Class<?> target = compiled.loader().loadClass("ShopCommands");
            Class<?> generated = compiled.loader().loadClass("ShopCommandsTree");
            Method of = generated.getMethod("of", target);
            CommandSet annotated = (CommandSet) of.invoke(null,
                    target.getDeclaredConstructor().newInstance());

            String expected = shape(byBuilder().tree());
            assertEquals(expected, shape(annotated.tree()), "annotations differ from the builder");
            assertEquals(expected, shape(new ShopShim().build().tree()), "inheritance differs from the builder");
        }
    }

    /// shape is the tree as the host cares about it: everything but the ids.
    ///
    /// Rendered as text rather than compared node by node so a failure names
    /// the branch that differs instead of reporting that two objects are not
    /// equal.
    private static String shape(CommandTree tree) {
        StringBuilder text = new StringBuilder();
        render(text, "", tree.commands());
        return text.toString();
    }

    private static void render(StringBuilder text, String indent, Iterable<CommandNode> nodes) {
        for (CommandNode node : nodes) {
            text.append(indent);
            switch (node) {
                case CommandNode.Literal literal -> {
                    text.append("literal ").append(literal.name());
                    if (!literal.permission().isEmpty()) {
                        text.append(" guarded-by ").append(literal.permission());
                    }
                }
                case CommandNode.Argument argument ->
                        text.append("argument ").append(argument.name()).append(' ').append(argument.type());
            }
            text.append(node.executor() == 0 ? "" : " runs").append('\n');
            render(text, indent + "  ", node.children());
        }
    }
}
