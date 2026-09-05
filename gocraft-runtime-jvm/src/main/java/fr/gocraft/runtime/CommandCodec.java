package fr.gocraft.runtime;

import fr.gocraft.abi.v1.CommandArgument;
import fr.gocraft.abi.v1.Invoke;
import fr.gocraft.abi.v1.Invoked;
import fr.gocraft.abi.v1.HostCall;
import fr.gocraft.api.CommandContext;
import fr.gocraft.api.CommandSender;
import fr.gocraft.api.PlayerRef;
import fr.gocraft.api.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Converts between the wire types and the ones a command handler sees.
///
/// The generated protobuf classes stop here, exactly as they do for events in
/// [EventCodec]. A handler works on fr.gocraft.api types alone, so the
/// serialization can change without any plugin noticing.
final class CommandCodec {

    private CommandCodec() {
    }

    static CommandContext context(Invoke invoke) {
        return new CommandContext(sender(invoke), arguments(invoke.getArgumentsList()));
    }

    private static CommandSender sender(Invoke invoke) {
        var wire = invoke.getSender();
        Map<String, Boolean> permissions = new LinkedHashMap<>();
        for (fr.gocraft.abi.v1.Value pair : wire.getPermissionsList()) {
            // [node, allowed], the shape the host writes for events too. A pair
            // that is not that shape is skipped rather than guessed at: reading
            // it wrong would grant or deny a permission on a malformed message.
            if (EventCodec.value(pair) instanceof Value.List(List<Value> parts)
                    && parts.size() == 2
                    && parts.get(0) instanceof Value.Text(String node)
                    && parts.get(1) instanceof Value.Bool(boolean allowed)) {
                permissions.put(node, allowed);
            }
        }
        // Unbound: the context that carries this command's effects does not
        // exist yet, and binds the handle itself once it does.
        PlayerRef player = PlayerRef.of(EventCodec.value(wire.getPlayer()), null);
        return new CommandSender(wire.getName(), player, permissions);
    }

    private static Map<String, CommandContext.Argument> arguments(List<CommandArgument> wire) {
        Map<String, CommandContext.Argument> parsed = new LinkedHashMap<>(wire.size());
        for (CommandArgument argument : wire) {
            parsed.put(argument.getName(), new CommandContext.Argument(
                    type(argument.getType()),
                    EventCodec.value(argument.getValue())));
        }
        return parsed;
    }

    /// An unrecognised type becomes UNKNOWN rather than a guess.
    ///
    /// The alternative is reading a value as whatever this build happens to
    /// list at that number, which for a plugin built against a newer host means
    /// a handler quietly receiving the wrong thing. UNKNOWN reads as empty
    /// everywhere, which is wrong in a way somebody notices.
    private static CommandContext.Argument.Type type(fr.gocraft.abi.v1.CommandArgumentType wire) {
        return switch (wire) {
            case COMMAND_ARGUMENT_TYPE_INTEGER -> CommandContext.Argument.Type.INTEGER;
            case COMMAND_ARGUMENT_TYPE_DECIMAL -> CommandContext.Argument.Type.DECIMAL;
            case COMMAND_ARGUMENT_TYPE_STRING -> CommandContext.Argument.Type.STRING;
            case COMMAND_ARGUMENT_TYPE_GREEDY -> CommandContext.Argument.Type.GREEDY;
            case COMMAND_ARGUMENT_TYPE_PLAYER -> CommandContext.Argument.Type.PLAYER;
            case COMMAND_ARGUMENT_TYPE_BLOCK_POS -> CommandContext.Argument.Type.BLOCK_POS;
            case COMMAND_ARGUMENT_TYPE_BLOCK_STATE -> CommandContext.Argument.Type.BLOCK_STATE;
            case COMMAND_ARGUMENT_TYPE_ITEM -> CommandContext.Argument.Type.ITEM;
            case COMMAND_ARGUMENT_TYPE_DURATION -> CommandContext.Argument.Type.DURATION;
            case COMMAND_ARGUMENT_TYPE_ENUM -> CommandContext.Argument.Type.ENUM;
            case COMMAND_ARGUMENT_TYPE_CUSTOM -> CommandContext.Argument.Type.CUSTOM;
            case COMMAND_ARGUMENT_TYPE_UNSPECIFIED, UNRECOGNIZED ->
                    CommandContext.Argument.Type.UNKNOWN;
        };
    }

    /// The answer: what went wrong if anything, and everything the handler
    /// asked for, in one message.
    ///
    /// Effects travel even when the handler failed. A handler that refused and
    /// said why has already queued that sentence, and dropping it because it
    /// also reported an error would leave the sender staring at nothing.
    static Invoked invoked(String failure, List<CommandContext.Effect> effects) {
        Invoked.Builder invoked = Invoked.newBuilder();
        if (failure != null) {
            invoked.setError(failure);
        }
        for (CommandContext.Effect effect : effects) {
            invoked.addEffects(HostCall.newBuilder()
                    .setType(effect.call())
                    .addAllFields(EventCodec.wire(effect.values()))
                    .build());
        }
        return invoked.build();
    }
}