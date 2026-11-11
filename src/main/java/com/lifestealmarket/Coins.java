package com.lifestealmarket;

public final class Coins {
	private Coins() {}

	public static String fmt(long v) {
		if (v < 0) return "-";
		if (v >= 1_000_000_000L) return String.format(java.util.Locale.ROOT, "%.2fB", v / 1e9);
		if (v >= 1_000_000L) return String.format(java.util.Locale.ROOT, "%.2fM", v / 1e6);
		if (v >= 10_000L) return String.format(java.util.Locale.ROOT, "%.1fK", v / 1e3);
		if (v >= 1_000L) return String.format(java.util.Locale.ROOT, "%.2fK", v / 1e3);
		return String.valueOf(v);
	}
}

