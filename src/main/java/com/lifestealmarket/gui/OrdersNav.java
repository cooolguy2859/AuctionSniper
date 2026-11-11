package com.lifestealmarket.gui;

import com.lifestealmarket.NetworthTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.Locale;

public final class OrdersNav {
	private OrdersNav() {}

	private static final long WINDOW_MS = 6_000;
	private static final long SETTLE_MS = 350;

	private static volatile String wanted = null;
	private static volatile String wantedRaw = "";
	private static volatile String needle = "order";
	private static volatile long deadline = 0L;
	private static volatile long sawScreenAt = 0L;
	private static volatile Object lastScreen = null;

	public static void want(String item, String titleNeedle) {
		wantedRaw = item == null ? "" : item;
		wanted = item == null ? null : NetworthTracker.norm(item);
		needle = titleNeedle == null || titleNeedle.isBlank() ? "order" : titleNeedle.toLowerCase(Locale.ROOT);
		deadline = System.currentTimeMillis() + WINDOW_MS;
		sawScreenAt = 0L;
		lastScreen = null;
	}

	public static void cancel() { wanted = null; }

	public static void tick(MinecraftClient client, long now) {
		String target = wanted;
		if (target == null) return;
		if (now > deadline) { wanted = null; return; }
		if (!(client.currentScreen instanceof HandledScreen<?> hs) || client.player == null) return;
		String title = hs.getTitle().getString().toLowerCase(Locale.ROOT);
		if (!title.contains(needle)) return;
		if (hs != lastScreen) { lastScreen = hs; sawScreenAt = now; return; }
		if (now - sawScreenAt < SETTLE_MS) return;

		var handler = hs.getScreenHandler();
		var inv = client.player.getInventory();
		for (Slot s : handler.slots) {
			if (s.inventory == inv) continue;
			ItemStack st = s.getStack();
			if (st == null || st.isEmpty()) continue;
			if (!NetworthTracker.norm(st.getName().getString()).equals(target)) continue;
			wanted = null;
			if (client.interactionManager != null)
				client.interactionManager.clickSlot(handler.syncId, s.id, 0, SlotActionType.PICKUP, client.player);
			return;
		}

		wanted = null;
		client.player.sendMessage(Text.literal(
				"§b[Market] §7Couldn't find §f" + wantedRaw + "§7 on this page — pick it from the list."), false);
	}
}

