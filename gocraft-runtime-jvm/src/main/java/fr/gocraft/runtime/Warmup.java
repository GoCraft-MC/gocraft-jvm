package fr.gocraft.runtime;

import fr.gocraft.api.Value;

import java.util.List;

/// Runs the dispatch machinery once, at load, so the first real event does not
/// pay for it.
///
/// The event budget is a couple of milliseconds and shared by every subscriber.
/// A first dispatch into a cold JVM does not fit: it pays the first virtual
/// thread, the first pass of the JIT over the whole path, and the first
/// conversion of every value shape. Every dispatch after it does fit, which is
/// exactly what makes the first one worth removing rather than tolerating.
///
/// The cost of not doing this is not the warning in the log. An event whose
/// provider declared `fail_closed` is **cancelled** when its budget runs out —
/// so a protection plugin would refuse the first action after every restart,
/// once, invisibly. That is the failure this exists to prevent.
///
/// **No plugin code runs here.** Invoking a handler would mean running an
/// author's code against values nobody sent: a discount applied to a purchase
/// that never happened, a protection decision about a block nobody broke. So
/// what is warmed is the machinery every dispatch shares and nothing that
/// belongs to a plugin.
///
/// Which leaves the author's own codec cold, and there is no way around that
/// today: exercising it needs a payload of the right shape, and the runtime is
/// told an event's name and id but never its layout. Sending the layout with
/// the binding would close it — the same addition that would let a subscriber
/// notice its copy has diverged — and is worth doing when either need is real.
final class Warmup {

    private Warmup() {
    }

    /// A payload with one of every shape, so no conversion meets its first
    /// value while the tick is waiting.
    private static final List<Value> SHAPES = List.of(
            new Value.Bool(true),
            new Value.Int(1),
            new Value.Decimal(1),
            new Value.Text("warm"),
            new Value.Bytes(new byte[16]),
            new Value.List(List.of(new Value.Text("warm"), new Value.Decimal(1))));

    /// Runs on the thread that loaded the plugin, which is the read loop and
    /// not the tick: the host is waiting for a LOAD reply either way, and it
    /// waits for that without a budget.
    ///
    /// Best effort throughout. A warm-up that failed has cost a load nothing,
    /// and reporting it would be reporting an optimisation.
    static void run() {
        try {
            Control control = new Control();
            List<fr.gocraft.abi.v1.Value> wire = EventCodec.wire(SHAPES);
            List<Value> back = EventCodec.fields(wire);
            EventCodec.changes(SHAPES, back);
            // Through a handle, which is where a verb lives: this warms the
            // player it is asked of as well as the queue it lands in.
            control.player(new byte[16]).sendMessage("warm");
            EventCodec.verdict(control, List.of());

            // The first virtual thread costs more than the ones after it, and
            // every dispatch is given one.
            Thread thread = Thread.ofVirtual().start(() -> EventCodec.wire(SHAPES));
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // Nothing here is load-bearing.
        }
    }
}