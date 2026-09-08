package fr.gocraft.api;

/// The host calls a plugin can ask for.
///
/// Named once because both ends spell them: a plugin asks by this string and
/// the host dispatches on it. The contract states the same names in
/// abi.EffectMessage, and this jar deliberately cannot import the contract —
/// so this is the one place on the Java side that says them, rather than a
/// literal at every call site.
///
/// An effect the host does not recognise is dropped, not refused. A rename that
/// missed a copy would show up as a plugin whose messages silently stop
/// arriving, which is why there is only one copy.
public final class Effects {

    private Effects() {
    }

    /// Delivers one line to the player a call names.
    public static final String MESSAGE = "chat.message";
}
