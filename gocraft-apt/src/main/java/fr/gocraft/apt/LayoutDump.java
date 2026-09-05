package fr.gocraft.apt;

import fr.gocraft.api.PluginEvent;

import java.util.List;

/// The event layouts, written where the bundle build can read them.
///
/// The same trade §15 makes for commands, one level along: the processor
/// extracts what only javac can see and hands it to `gocraft-cli build`, which
/// is the one implementation of the manifest format. Without it the annotation
/// would describe an event and the manifest would describe it again, and the
/// two would be free to disagree in the one place nothing compares them — which
/// is exactly what §10 says deriving the block exists to prevent.
///
/// JSON, hand-written, for the reason Intermediate gives: the alternative is a
/// TOML encoder on the annotation processor path to describe four fields, and
/// this file is read by one program in the same build.
final class LayoutDump {

    static final String PATH = "gocraft/events.json";

    /// The version gocraft-cli checks. Bumped when a field is added, so a dump
    /// from a newer processor is refused by number rather than read as far as
    /// it happens to parse — a field that silently did not reach the manifest
    /// would be a layout nobody declared.
    private static final int VERSION = 1;

    private final StringBuilder out = new StringBuilder();

    /// One event and the layout derived from it.
    record Declared(PluginEvent annotation, List<EventProcessor.Field> layout) {
    }

    String render(List<Declared> events, java.util.Map<String, List<EventProcessor.Field>> records) {
        out.append("{\n");
        out.append("  \"version\": ").append(VERSION).append(",\n");
        out.append("  \"types\": [\n");
        int remaining = records.size();
        for (java.util.Map.Entry<String, List<EventProcessor.Field>> record : records.entrySet()) {
            out.append("    {\n");
            out.append("      \"name\": ").append(quote(record.getKey())).append(",\n");
            out.append("      \"fields\": [\n");
            List<EventProcessor.Field> layout = record.getValue();
            for (int index = 0; index < layout.size(); index++) {
                field(layout.get(index), index + 1 == layout.size());
            }
            out.append("      ]\n");
            out.append("    }").append(--remaining == 0 ? "\n" : ",\n");
        }
        out.append("  ],\n");
        out.append("  \"events\": [\n");
        for (int index = 0; index < events.size(); index++) {
            event(events.get(index), index + 1 == events.size());
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private void event(Declared declared, boolean last) {
        out.append("    {\n");
        out.append("      \"type\": ").append(quote(declared.annotation().value())).append(",\n");
        out.append("      \"cancellable\": ").append(declared.annotation().cancellable()).append(",\n");
        out.append("      \"failClosed\": ").append(declared.annotation().failClosed()).append(",\n");
        out.append("      \"fields\": [\n");
        List<EventProcessor.Field> layout = declared.layout();
        for (int index = 0; index < layout.size(); index++) {
            field(layout.get(index), index + 1 == layout.size());
        }
        out.append("      ]\n");
        out.append("    }").append(last ? "\n" : ",\n");
    }

    private void field(EventProcessor.Field field, boolean last) {
        out.append("        { \"name\": ").append(quote(field.name()))
                .append(", \"type\": ").append(quote(field.carried().manifest()))
                .append(", \"mutable\": ").append(field.mutable())
                .append(" }").append(last ? "\n" : ",\n");
    }

    /// A JSON string. Names and types have been through the processor, which
    /// accepts identifiers and dotted names only, so nothing needs escaping
    /// today — done anyway, because "the input cannot contain a quote" stays
    /// true until it does not.
    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}