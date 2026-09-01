package fr.gocraft.apt;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/// Where a malformed command is reported.
///
/// On the element, through the messager, so it lands where the author is
/// looking: underlined in the IDE, on the line that is wrong. §07 counts that
/// as the reason annotations are the fastest facade rather than the fragile
/// one — the alternative is a line in a server log, hours later, on a machine
/// the author does not have open.
final class Diagnostics {

    private final Messager messager;
    private boolean failed;

    Diagnostics(Messager messager) {
        this.messager = messager;
    }

    void error(String message, Element element) {
        failed = true;
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    boolean failed() {
        return failed;
    }
}
