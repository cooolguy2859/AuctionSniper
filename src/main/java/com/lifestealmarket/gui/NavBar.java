package com.lifestealmarket.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class NavBar {
	private NavBar() {}

	private static final String[] IDS = { "today", "stock", "market", "buy", "sell", "worth", "board", "shop", "style" };
	private static final String[] LABELS = { "Today", "Stock", "Market", "Buy", "Sell", "Worth", "Board", "Shop", "Style" };
	private static final String SEP = " · ";

	private static final List<int[]> hits = new ArrayList<>();

	private static String hitsFor = null;

	private static double ulX = -1, ulW = 0;
	private static long ulLast = System.currentTimeMillis();

	public static void draw(DrawContext ctx, TextRenderer tr, String current, int screenW, int screenH,
			int px, int py, int pw, double uiScale) {
		hits.clear(); hitsFor = current;
		int sepW = tr.getWidth(SEP);
		int total = 0;
		int[] w = new int[LABELS.length];
		for (int i = 0; i < LABELS.length; i++) { w[i] = tr.getWidth(LABELS[i]); total += w[i]; }
		total += (LABELS.length - 1) * sepW;

		int accent = com.lifestealmarket.MarketConfig.themeColor(com.lifestealmarket.MarketConfig.get().theme);
		int accentFull = 0xFF000000 | (accent & 0xFFFFFF);
		int accentSoft = 0x66000000 | (accent & 0xFFFFFF);

		double cx = screenW / 2.0, cy = screenH / 2.0;
		int panelCx = (int) Math.round(cx + (px + pw / 2.0 - cx) * uiScale);
		int panelTop = (int) Math.round(cy + (py - cy) * uiScale);
		int x = panelCx - total / 2;
		x = Math.max(9, Math.min(x, screenW - total - 9));
		int y = Math.max(6, panelTop - 17);
		ctx.fill(x - 9, y - 5, x + total + 9, y + 13, 0x99000000);
		ctx.fill(x - 9, y + 13, x + total + 9, y + 14, accentSoft);
		int curX = -1, curW = 0;
		for (int i = 0; i < LABELS.length; i++) {
			boolean cur = IDS[i].equals(current);
			if (cur) { ctx.drawTextWithShadow(tr, Text.literal(LABELS[i]), x, y, accentFull); curX = x; curW = w[i]; }
			else ctx.drawTextWithShadow(tr, Text.literal("§f" + LABELS[i]), x, y, 0xFFFFFFFF);
			if (!cur) hits.add(new int[]{ x - 2, y - 5, x + w[i] + 2, y + 13, i });
			x += w[i];
			if (i < LABELS.length - 1) {
				ctx.drawTextWithShadow(tr, Text.literal("§8" + SEP), x, y, 0xFFFFFFFF);
				x += sepW;
			}
		}

		if (curX >= 0) {
			long now = System.currentTimeMillis();
			double dt = Math.min(100, now - ulLast);
			ulLast = now;
			double k = 1 - Math.exp(-dt / 70.0);
			if (ulX < 0) { ulX = curX; ulW = curW; }
			ulX += (curX - ulX) * k;
			ulW += (curW - ulW) * k;
			ctx.fill((int) Math.round(ulX), y + 9, (int) Math.round(ulX + ulW), y + 10, accentFull);
			ctx.fill((int) Math.round(ulX + 1), y + 10, (int) Math.round(ulX + ulW - 1), y + 11, accentSoft);
		}
	}

	public static boolean click(double gx, double gy, Screen from) {
		String cur = idOf(from);
		if (hitsFor == null || (cur != null && !hitsFor.equals(cur))) return false;
		for (int[] b : hits) {
			if (gx >= b[0] && gx <= b[2] && gy >= b[1] && gy <= b[3]) { open(b[4], from); return true; }
		}
		return false;
	}

	private static void fail(Throwable t) {
		System.err.println("[lifestealmarket] nav screen failed to open: " + t);
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal(
				"§c[Market] That screen failed to open. If the mod was just updated, fully restart your client."), false);
	}

	public static String idOf(Screen s) {
		if (s instanceof TodayScreen) return "today";
		if (s instanceof StockScreen) return "stock";
		if (s instanceof MarketScreen) return "market";
		if (s instanceof BuyNowScreen) return "buy";
		if (s instanceof SellNowScreen) return "sell";
		if (s instanceof NetworthScreen) return "worth";
		if (s instanceof LeaderboardScreen) return "board";
		if (s instanceof ShopScreen) return "shop";
		if (s instanceof CustomizeScreen) return "style";
		return null;
	}

	public static boolean arrow(Screen from, int dir) {
		String cur = idOf(from);
		if (cur == null) return false;
		int idx = -1;
		for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(cur)) { idx = i; break; }
		if (idx < 0) return false;
		int next = idx + dir;
		if (next < 0 || next >= IDS.length) return false;
		open(next, from);
		return true;
	}

	private static void open(int i, Screen from) {
		Ui.navSwitchAt = System.currentTimeMillis();
		MinecraftClient mc = MinecraftClient.getInstance();

		Screen s;
		try {
			s = switch (IDS[i]) {
				case "today" -> new TodayScreen(from);
				case "stock" -> new StockScreen(from, "");
				case "market" -> new MarketScreen(from);
				case "buy" -> new BuyNowScreen(from, "");
				case "sell" -> new SellNowScreen(from);
				case "worth" -> new NetworthScreen(from);
				case "board" -> new LeaderboardScreen(from);
				case "shop" -> new ShopScreen(from);
				case "style" -> new CustomizeScreen(from);
				default -> null;
			};
		} catch (Throwable buildFail) { fail(buildFail); return; }
		if (s != null) mc.execute(() -> { try { mc.setScreen(s); } catch (Throwable openFail) { fail(openFail); } });
	}
}

