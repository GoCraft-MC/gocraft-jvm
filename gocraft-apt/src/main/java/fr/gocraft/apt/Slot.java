package fr.gocraft.apt;

/// One argument, as the processor decided to render it.
///
/// Two expressions rather than a type: the tree needs to say what the argument
/// accepts, and the generated invoker needs to read it back out. Deriving both
/// from one parameter in one place is what keeps them from disagreeing — a tree
/// that says decimal and an invoker that reads text would compile.
record Slot(String name, String type, String read, boolean greedy) {
}
