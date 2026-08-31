package fr.gocraft.runtime;

import fr.gocraft.abi.v1.Envelope;
import fr.gocraft.abi.v1.Fail;
import fr.gocraft.abi.v1.Hello;
import fr.gocraft.abi.v1.Invoked;
import fr.gocraft.abi.v1.Loaded;
import fr.gocraft.abi.v1.Pong;
import fr.gocraft.abi.v1.Verdict;

/// Builders for the envelopes this runtime sends.
///
/// They exist so the sequence-number rule lives in one place. A reply echoes
/// the seq of the request it answers — that is what wakes the caller waiting on
/// it host-side — while anything unsolicited leaves it at zero. Getting that
/// backwards does not fail loudly: the host simply waits out the whole event
/// budget for a reply it already received but could not match.
final class Envelopes {

    private Envelopes() {
    }

    /// The runtime speaks first, so a version mismatch is caught before the
    /// host sends anything it would have to take back. The seq is zero: this
    /// answers nothing, and the host echoes it back on WELCOME.
    static Envelope hello(int abi, String version) {
        return Envelope.newBuilder()
                .setHello(Hello.newBuilder().setAbi(abi).setRuntime("jvm " + version))
                .build();
    }

    static Envelope pong(long seq) {
        return Envelope.newBuilder().setSeq(seq).setPong(Pong.getDefaultInstance()).build();
    }

    static Envelope loaded(long seq, String pluginId, String... events) {
        Loaded.Builder body = Loaded.newBuilder().setPluginId(pluginId);
        for (String event : events) {
            body.addEvents(event);
        }
        return Envelope.newBuilder().setSeq(seq).setLoaded(body).build();
    }

    static Envelope fail(long seq, String pluginId, String reason) {
        return Envelope.newBuilder()
                .setSeq(seq)
                .setFail(Fail.newBuilder().setPluginId(pluginId).setReason(reason))
                .build();
    }

    static Envelope invoked(long seq, Invoked invoked) {
        return Envelope.newBuilder().setSeq(seq).setInvoked(invoked).build();
    }

    static Envelope verdict(long seq, Verdict verdict) {
        return Envelope.newBuilder().setSeq(seq).setVerdict(verdict).build();
    }
}