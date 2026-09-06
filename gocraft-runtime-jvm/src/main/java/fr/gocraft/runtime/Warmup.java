package fr.gocraft.runtime;

import java.lang.reflect.Method;

/// Warms the one part of a dispatch the host's warm-up cannot reach.
///
/// Everything else is warmed by a real DISPATCH the host sends between LOAD and
/// READY, marked `warm` on the wire: the reader loop, the codecs, the event
/// class, the writer thread, protobuf on both sides. That is deliberately not
/// replicated here — this class used to do exactly that, and every piece the
/// replica forgot stayed cold on the path that mattered. The measurements are
/// in [PluginRegistry#warm].
///
/// What a warm dispatch stops short of is the handler, because the values are
/// placeholders and an author's code must not decide about a purchase nobody
/// made. But a handler is reached *through* [Method#invoke], and that call is
/// not the author's — it is this runtime's, and it can be warmed on a method of
/// this class that does nothing.
///
/// It has to be warmed on the right shape. Reflection reaches a method through
/// machinery specialised per signature, so warming a static method taking
/// nothing — which is what this class used to do — warms a path no handler ever
/// takes. A handler is an instance method taking the event, and an EventControl
/// beside it when it cancels.
///
/// Worth its keep, measured rather than assumed. Three runs of the reference
/// plugin's `block.break` handler — the one that writes nothing, so the reading
/// is the dispatch and not an author's I/O — with the host's own warm-up on in
/// both columns:
///
///     without this class   1.92 ms   2.75 ms   2.30 ms
///     with it              0.51 ms   1.07 ms   1.31 ms
///
/// About 1.2 ms, which is the difference between fitting in the shared 2 ms
/// budget and not. Delete this and the first block a player breaks after every
/// restart is decided by the budget rather than by the plugin.
///
/// Best effort throughout, and once per process: the shapes are this class's
/// own, so a second plugin would pay again and warm nothing.
final class Warmup {

    /// How many times each shape is called.
    ///
    /// Once is not a warm-up. A single pass sets up the machinery behind a
    /// reflective call, but the call stays interpreted until HotSpot has been
    /// through it a few hundred times, and it was crossing that threshold —
    /// not touching the path — that moved the first dispatch of `block.break`
    /// from 2.07 ms to 0.51 ms.
    private static final int ROUNDS = 2000;

    private static final java.util.concurrent.atomic.AtomicBoolean DONE =
            new java.util.concurrent.atomic.AtomicBoolean();

    private Warmup() {
    }

    /// A stand-in with a handler's shape. Neither method does anything: what is
    /// being warmed is the reflective call, not what it reaches.
    private static final class Shape {

        void one(Object event) {
        }

        void two(Object event, Object control) {
        }
    }

    /// Runs on the thread that loaded the plugin, which is the reader loop and
    /// not the tick: the host is waiting for a LOAD reply either way, and it
    /// waits for that without a budget.
    static void run() {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        try {
            Shape shape = new Shape();
            Method one = Shape.class.getDeclaredMethod("one", Object.class);
            Method two = Shape.class.getDeclaredMethod("two", Object.class, Object.class);
            one.setAccessible(true);
            two.setAccessible(true);
            for (int round = 0; round < ROUNDS; round++) {
                one.invoke(shape, shape);
                two.invoke(shape, shape, shape);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Nothing here is load-bearing. A warm-up that failed has cost the
            // load nothing, and reporting it would be reporting an
            // optimisation.
        }
    }
}
