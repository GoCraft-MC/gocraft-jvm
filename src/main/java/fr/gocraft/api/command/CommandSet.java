package fr.gocraft.api.command;

import fr.gocraft.api.CommandHandler;
import java.util.List;
import java.util.Map;

/// What every facade produces: a tree, and the code each of its executors runs.
///
/// The two halves part company at registration, which is the whole design of
/// §07. The tree is data — it is written into the bundle, read by the host
/// before this JVM starts, and rendered by both editions. The invokers are
/// code, they stay in this process, and a lambda never crosses a boundary.
///
/// Facades differ in how an author writes this and in nothing else. That is
/// what a conformance test can assert, and what stops three ways of declaring a
/// command from becoming three sets of bugs.
public record CommandSet(CommandTree tree, Map<Integer, CommandHandler> invokers) {

    public CommandSet {
        invokers = Map.copyOf(invokers);
        List<Integer> executors = tree.executors();
        for (int executor : executors) {
            if (!invokers.containsKey(executor)) {
                throw new IllegalArgumentException("executor " + executor + " has no invoker");
            }
        }
        if (invokers.size() != executors.size()) {
            throw new IllegalArgumentException(
                    "the tree points at " + executors.size() + " executors and " + invokers.size()
                            + " invokers were bound; one of them runs nothing");
        }
    }
}
