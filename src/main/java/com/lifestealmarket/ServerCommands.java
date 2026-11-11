package com.lifestealmarket;

import java.util.Locale;
import java.util.Set;

public final class ServerCommands {
	private ServerCommands() {}

	private static volatile Set<String> NAMES = Set.of();

	public static void set(Set<String> names) { NAMES = names == null ? Set.of() : names; }

	public static boolean has(String name) {
		return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
	}

	public static void clear() { NAMES = Set.of(); }
}

