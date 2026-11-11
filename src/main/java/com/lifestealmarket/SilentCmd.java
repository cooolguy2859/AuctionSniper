package com.lifestealmarket;

import net.minecraft.client.MinecraftClient;

public final class SilentCmd {
	private SilentCmd() {}

	private static volatile String last = null;
	private static volatile long at = 0L;

	public static void send(MinecraftClient client, String command) {
		if (client == null || client.player == null || client.player.networkHandler == null) return;
		last = strip(command);
		at = System.currentTimeMillis();
		client.player.networkHandler.sendChatCommand(command);
	}

	public static boolean wasSelf(String command) {
		String l = last;
		if (l == null || command == null) return false;
		if (System.currentTimeMillis() - at > 250) { last = null; return false; }
		if (!strip(command).equalsIgnoreCase(l)) return false;
		last = null;
		return true;
	}

	private static String strip(String c) {
		String s = c == null ? "" : c.trim();
		return s.startsWith("/") ? s.substring(1).trim() : s;
	}
}

