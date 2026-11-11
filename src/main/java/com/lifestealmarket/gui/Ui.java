package com.lifestealmarket.gui;

import com.lifestealmarket.MarketConfig;
import com.lifestealmarket.Nameplate;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

final class Ui {
	private Ui() {}

	static final int GOLD = 0xFFFFC23D;
	static final int GREEN = 0xFF22E88A;

	static void hGrad(DrawContext ctx, int x, int y, int x2, int y2, int c1, int c2, int step) {
		int w = x2 - x;
		if (w <= 0) return;
		for (int i = 0; i < w; i += step) {
			int end = Math.min(i + step, w);
			ctx.fill(x + i, y, x + end, y2, StyledButton.lerpColor(c1, c2, w <= 1 ? 0f : (float) i / (w - 1)));
		}
	}

	static void panel(DrawContext ctx, TextRenderer tr, int px, int py, int pw, int ph,
			int panelColor, int accent, String titleIconAndName, String subtitle) {
		int acc = accent & 0xFFFFFF;
		long now = System.currentTimeMillis();
		StyledButton.roundFill(ctx, px + 4, py + 5, pw, ph, 0x66000000);

		StyledButton.roundFill(ctx, px, py, pw, ph, panelColor | 0xFF000000);
		ctx.fillGradient(px + 1, py + 1, px + pw - 1, py + ph - 1, 0x0AFFFFFF, 0x1E000000);

		hGrad(ctx, px + 1, py + 31, px + 11, py + ph - 1, 0x24000000, 0x00000000, 3);
		hGrad(ctx, px + pw - 11, py + 31, px + pw - 1, py + ph - 1, 0x00000000, 0x24000000, 3);
		ctx.fillGradient(px + 1, py + ph - 11, px + pw - 1, py + ph - 1, 0x00000000, 0x24000000);
		ctx.fill(px + 1, py + 1, px + pw - 1, py + 30, 0xFF0C1220);
		hGrad(ctx, px + 1, py + 1, px + (int) (pw * 0.55), py + 30, 0x2E000000 | acc, 0x00000000, 4);
		ctx.fill(px + 1, py + 30, px + pw - 1, py + 31, 0xAA000000 | acc);
		ctx.fillGradient(px + 1, py + 31, px + pw - 1, py + 35, 0x33000000 | acc, 0x00000000);

		float sp = (now % 3800L) / 3800f;
		int sx = px + 1 + (int) ((pw - 62) * sp);
		hGrad(ctx, sx, py + 30, sx + 30, py + 31, 0x00FFFFFF, 0x88FFFFFF, 3);
		hGrad(ctx, sx + 30, py + 30, sx + 60, py + 31, 0x88FFFFFF, 0x00FFFFFF, 3);
		StyledButton.roundBorder(ctx, px, py, pw, ph, 0xFF000000 | acc);
		StyledButton.roundBorder(ctx, px - 1, py - 1, pw + 2, ph + 2, 0x2A000000 | acc);
		int tick = 0xCC000000 | acc;
		ctx.fill(px + 3, py + 3, px + 10, py + 4, tick);           ctx.fill(px + 3, py + 3, px + 4, py + 10, tick);
		ctx.fill(px + pw - 10, py + 3, px + pw - 3, py + 4, tick); ctx.fill(px + pw - 4, py + 3, px + pw - 3, py + 10, tick);
		ctx.fill(px + 3, py + ph - 4, px + 10, py + ph - 3, tick); ctx.fill(px + 3, py + ph - 10, px + 4, py + ph - 3, tick);
		ctx.fill(px + pw - 10, py + ph - 4, px + pw - 3, py + ph - 3, tick); ctx.fill(px + pw - 4, py + ph - 10, px + pw - 3, py + ph - 3, tick);
		Text title = Text.literal(titleIconAndName)
				.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(acc)).withBold(true));

		var m = ctx.getMatrices();
		m.pushMatrix(); m.translate(px + 12, py + 10); m.scale(1.25f, 1.25f);
		ctx.drawTextWithShadow(tr, title, 0, 0, 0xFFFFFFFF);
		m.popMatrix();
		int titleRight = px + 12 + (int) Math.ceil(tr.getWidth(title) * 1.25f);
		ctx.drawTextWithShadow(tr, Text.literal(subtitle), titleRight + 6, py + 12, 0xFFFFFFFF);
	}

	static void sectionHead(DrawContext ctx, TextRenderer tr, int x, int y, int xEnd, String label, String right, int accent) {
		ctx.drawTextWithShadow(tr, Text.literal(label), x, y, 0xFFFFFFFF);
		int rEnd = xEnd - (right != null ? tr.getWidth(right) + 7 : 0);
		hGrad(ctx, x + tr.getWidth(label) + 7, y + 4, rEnd, y + 5, 0x66000000 | (accent & 0xFFFFFF), 0x14FFFFFF, 4);
		if (right != null) ctx.drawTextWithShadow(tr, Text.literal(right), xEnd - tr.getWidth(right), y, 0xFFFFFFFF);
	}

	static int pricePill(DrawContext ctx, TextRenderer tr, int rightX, int y, String label) {
		int w = tr.getWidth(label) + 14, h = 13, slant = 4;
		for (int r = 0; r < h; r++) {
			int off = Math.round(slant * (h - 1 - r) / (float) (h - 1));
			ctx.fill(rightX - w + off + 1, y + r + 1, rightX + off + 1, y + r + 2, 0x66000000);
		}
		for (int r = 0; r < h; r++) {
			int off = Math.round(slant * (h - 1 - r) / (float) (h - 1));
			int c = r == 0 ? 0xFFFFE29A : StyledButton.lerpColor(GOLD, 0xFF6E5210, (r - 1) / (float) (h - 1));
			ctx.fill(rightX - w + off, y + r, rightX + off, y + r + 1, c);
		}
		ctx.drawText(tr, Text.literal(label).setStyle(Style.EMPTY.withBold(true)),
				rightX - w + slant / 2 + 6, y + 3, 0xFF3A2A00, false);
		return w;
	}

	static void ribbon(DrawContext ctx, TextRenderer tr, int x, int y, String label) {
		Text bold = Text.literal(label).setStyle(Style.EMPTY.withBold(true));
		int w = tr.getWidth(bold) + 14, h = 11;
		StyledButton.roundFill(ctx, x + 1, y + 1, w, h, 0x66000000);
		ctx.fillGradient(x, y, x + w, y + h, GOLD, StyledButton.lerpColor(GOLD, 0xFF000000, 0.25f));
		StyledButton.roundBorder(ctx, x, y, w, h, 0xFFFFE29A);
		ctx.drawText(tr, bold, x + 7, y + 2, 0xFF3A2A00, false);
	}

	static Text fitText(TextRenderer tr, Text t, int maxW) {
		if (tr.getWidth(t) <= maxW) return t;
		java.util.List<Object[]> runs = new java.util.ArrayList<>();
		t.visit((style, str) -> { runs.add(new Object[]{ str, style }); return java.util.Optional.empty(); },
				Style.EMPTY);
		var out = Text.empty();
		int used = 0, ell = tr.getWidth("…");
		for (Object[] r : runs) {
			String s = (String) r[0];
			Style st = (Style) r[1];
			for (int i = 0; i < s.length(); i++) {
				String ch = String.valueOf(s.charAt(i));
				int cw = tr.getWidth(ch);
				if (used + cw + ell > maxW) { out.append(Text.literal("…")); return out; }
				out.append(Text.literal(ch).setStyle(st));
				used += cw;
			}
		}
		return out;
	}

	static String fit(TextRenderer tr, String s, int maxW) {
		if (tr.getWidth(s) <= maxW) return s;
		String out = s;
		while (out.length() > 1 && tr.getWidth(out + "…") > maxW) out = out.substring(0, out.length() - 1);
		return out + "…";
	}

	static void shimmerCard(DrawContext ctx, int x, int y, int w, int h) {
		StyledButton.roundFill(ctx, x, y, w, h, 0x8010161F);
		StyledButton.roundBorder(ctx, x, y, w, h, 0x22FFFFFF);
		float t = (System.currentTimeMillis() % 1600L) / 1600f;
		int bx = x + (int) ((w + 40) * t) - 40;
		int b0 = Math.max(x + 1, bx), b1 = Math.min(x + w - 1, bx + 34);
		if (b1 > b0 + 1) {
			int mid = (b0 + b1) / 2;
			hGrad(ctx, b0, y + 1, mid, y + h - 1, 0x00FFFFFF, 0x12FFFFFF, 3);
			hGrad(ctx, mid, y + 1, b1, y + h - 1, 0x12FFFFFF, 0x00FFFFFF, 3);
		}
	}

	static void savePill(DrawContext ctx, TextRenderer tr, int x, int y, String label) {
		int w = tr.getWidth(label) + 8;
		StyledButton.roundFill(ctx, x, y, w, 11, 0x3322E88A);
		StyledButton.roundBorder(ctx, x, y, w, 11, 0x5522E88A);
		ctx.drawTextWithShadow(tr, Text.literal(label), x + 4, y + 2, 0xFFFFFFFF);
	}

	static int typePill(DrawContext ctx, TextRenderer tr, int rightX, int y, String label, int tint) {
		int w = tr.getWidth(label) + 8, h = 9;
		StyledButton.roundFill(ctx, rightX - w, y, w, h, StyledButton.lerpColor(tint | 0xFF000000, 0xFF000000, 0.72f) & 0xE0FFFFFF);
		StyledButton.roundBorder(ctx, rightX - w, y, w, h, (tint & 0xFFFFFF) | 0x66000000);
		ctx.drawTextWithShadow(tr, Text.literal(label), rightX - w + 4, y + 1, 0xFFFFFFFF);
		return w;
	}

	static void card(DrawContext ctx, int x, int y, int w, int h, int borderColor) {
		StyledButton.roundFill(ctx, x + 2, y + 2, w, h, 0x50000000);
		StyledButton.roundFill(ctx, x, y, w, h, 0xE00E141F);
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, 0x0CFFFFFF, 0x20000000);
		StyledButton.roundBorder(ctx, x, y, w, h, borderColor);
	}

	static void chip(DrawContext ctx, int x, int y, int size, int color) {
		ctx.fill(x, y, x + size, y + size, color);
		StyledButton.roundBorder(ctx, x - 1, y - 1, size + 2, size + 2, 0x44FFFFFF);
	}

	static void gradStrip(DrawContext ctx, int x, int y, int w, int h, int c1, int c2) {
		hGrad(ctx, x, y, x + w, y + h, c1, c2, 1);
		StyledButton.roundBorder(ctx, x - 1, y - 1, w + 2, h + 2, 0x44FFFFFF);
	}

	static int[] rainbowPair() {
		float t = (System.currentTimeMillis() % 3000L) / 3000f;
		return new int[]{ MarketConfig.hsv(t, 0.6f, 1f), MarketConfig.hsv((t + 0.33f) % 1f, 0.6f, 1f) };
	}

	static void rainbowBand(DrawContext ctx, int x, int y, int x2, int y2) {
		float t = (System.currentTimeMillis() % 6000L) / 6000f;
		int w = x2 - x;
		for (int i = 0; i < w; i += 2) {
			int end = Math.min(i + 2, w);
			ctx.fill(x + i, y, x + end, y2, MarketConfig.hsv((t + 0.85f * i / w) % 1f, 0.5f, 0.95f));
		}
	}

	static int argb(String hex, int fallback) {
		try { return 0xFF000000 | (int) Long.parseLong(hex.replace("#", "").trim(), 16); }
		catch (Exception e) { return fallback; }
	}

	static Text colouredName(String text, String spec) {
		if (spec == null || spec.isEmpty()) return Text.literal(text);

		int n = Math.max(1, text.length());
		if (!Nameplate.perLetter(spec))
			return Text.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(Nameplate.colourAt(spec, 0, n))));
		var out = Text.empty();
		for (int i = 0; i < text.length(); i++)
			out.append(Text.literal(String.valueOf(text.charAt(i)))
					.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(Nameplate.colourAt(spec, i, n)))));
		return out;
	}

	static void radialGlow(DrawContext ctx, int cx, int cy, int maxR, int color, int maxAlpha) {
		for (int i = 5; i >= 1; i--) {
			int r = maxR * i / 5;
			int a = maxAlpha * (6 - i) / 8;
			StyledButton.roundFill(ctx, cx - r, cy - r * 2 / 3, r * 2, r * 4 / 3, (a << 24) | (color & 0xFFFFFF));
		}
	}

	static void scanlines(DrawContext ctx, int x, int y, int x2, int y2) {
		for (int yy = y; yy < y2; yy += 3) ctx.fill(x, yy, x2, yy + 1, 0x05FFFFFF);
	}

	static void starDrift(DrawContext ctx, int x, int y, int w, int h, int seed, int count) {
		starDrift(ctx, x, y, w, h, seed, count, 0xFFFFFF);
	}

	static void starDrift(DrawContext ctx, int x, int y, int w, int h, int seed, int count, int rgb) {
		long now = System.currentTimeMillis();
		int col = rgb & 0xFFFFFF;
		for (int i = 0; i < count; i++) {
			int hsh = (seed * 31 + i) * 0x9E3779B9;
			int bx = Math.abs(hsh) % w;
			int by = Math.abs(hsh >> 8) % h;
			float drift = (now % 14000L) / 14000f * w;
			int sx = x + (int) ((bx + drift * ((i % 3) + 1) / 3f) % w);
			int a = 0x2E + (Math.abs(hsh >> 16) % 0x38);
			ctx.fill(sx, y + by, sx + 1, y + by + 1, (a << 24) | col);
		}
	}

	static float tagScale(String glyph) {
		String g = strip(glyph);
		for (char c : "♛♚♕♔♦♣♥♠♪◉☾⚔☯✧".toCharArray()) if (g.indexOf(c) >= 0) return 1.3f;
		return 1f;
	}

	private static final java.util.regex.Pattern SECTION = java.util.regex.Pattern.compile("(?i)§.");

	static String strip(String s) { return s == null ? "" : SECTION.matcher(s).replaceAll(""); }

	static boolean imageWithinBounds(byte[] bytes, int maxW, int maxH) {
		if (bytes == null || bytes.length == 0 || bytes.length > 12_000_000) return false;
		try (javax.imageio.stream.ImageInputStream iis =
				javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
			if (iis == null) return false;
			java.util.Iterator<javax.imageio.ImageReader> it = javax.imageio.ImageIO.getImageReaders(iis);
			if (!it.hasNext()) return false;
			javax.imageio.ImageReader r = it.next();
			try { r.setInput(iis); return r.getWidth(0) > 0 && r.getHeight(0) > 0
					&& r.getWidth(0) <= maxW && r.getHeight(0) <= maxH; }
			finally { r.dispose(); }
		} catch (Exception e) { return false; }
	}

	static int widthNoCodes(TextRenderer tr, String s) { return tr.getWidth(strip(s)); }
	static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

	static double easeOut(double t) { return 1 - Math.pow(1 - Math.max(0, Math.min(1, t)), 3); }

	static volatile long navSwitchAt = 0L;

	static double openPop(long openedAt) {

		return 1.0;
	}

	static int stagger(long openedAt, int i) {
		double t = (System.currentTimeMillis() - openedAt - i * 22L) / 160.0;
		return (int) (255 * easeOut(t));
	}

	static final class Scroll {
		double target, pos;
		private long last = System.currentTimeMillis();

		int tick() {
			long now = System.currentTimeMillis();
			double dt = Math.min(100, now - last);
			last = now;
			pos += (target - pos) * (1 - Math.exp(-dt / 80.0));
			if (Math.abs(pos - target) < 0.25) pos = target;
			return (int) Math.round(pos);
		}

		void reset() { target = 0; pos = 0; }
		void by(double d, int max) { target = Math.max(0, Math.min(target + d, Math.max(0, max))); }
		void clamp(int max) { target = Math.max(0, Math.min(target, Math.max(0, max))); pos = Math.min(pos, Math.max(0, max)); }

		void byRows(int dRows, int rowH, int n, int viewH) {
			int vRows = Math.max(1, viewH / rowH);
			int maxOff = Math.max(0, (n - vRows) * rowH);
			double t = Math.round(target / (double) rowH) * rowH + (long) dRows * rowH;
			target = Math.max(0, Math.min(t, maxOff));
		}

		int tickRows(int rowH, int n, int viewH) {
			int vRows = Math.max(1, viewH / rowH);
			int maxOff = Math.max(0, (n - vRows) * rowH);
			if (target > maxOff) target = maxOff;
			return tick();
		}
	}

	static Text label(String text, int accent) {
		return Text.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(accent & 0xFFFFFF)));
	}

	static void scrollEdges(DrawContext ctx, int x1, int top, int x2, int bot, int panelColor) {
		int solid = 0xFF000000 | (panelColor & 0xFFFFFF);
		ctx.fillGradient(x1, top, x2, top + 6, solid, solid & 0x00FFFFFF);
		ctx.fillGradient(x1, bot - 6, x2, bot, solid & 0x00FFFFFF, solid);
	}

	private static long lastEscAt = 0L;
	static void escBack(net.minecraft.client.MinecraftClient client, net.minecraft.client.gui.screen.Screen parent) {
		if (client == null) return;
		long now = System.currentTimeMillis();
		boolean doubleTap = now - lastEscAt < 450;
		lastEscAt = now;
		client.setScreen(doubleTap ? null : parent);
	}

	static final class HoverFade {
		private final java.util.Map<Integer, double[]> f = new java.util.HashMap<>();

		float tick(int key, boolean hovered) {
			long now = System.currentTimeMillis();
			double[] v = f.computeIfAbsent(key, k -> new double[]{ 0d, now });
			double dt = Math.min(120, now - v[1]);
			v[1] = now;

			v[0] += ((hovered ? 1d : 0d) - v[0]) * (1 - Math.exp(-dt / 45.0));
			if (f.size() > 400) f.clear();
            return (float) v[0];
		}
	}

	private static final java.util.Map<String, double[]> COUNTERS = new java.util.concurrent.ConcurrentHashMap<>();
	static long counter(String key, long target) {
		double[] v = COUNTERS.computeIfAbsent(key, k -> new double[]{ target });
		v[0] += (target - v[0]) * 0.14;
		if (Math.abs(v[0] - target) < Math.max(1, Math.abs(target) * 0.0004)) v[0] = target;
		return Math.round(v[0]);
	}

	static String shortName(String name, String type) {
		String n = strip(name).trim();
		String[] kill = switch (type) {
			case "bg" -> new String[]{ "background" };
			case "nametag" -> new String[]{ "nametag colour", "name colour", "nametag color", "name color", "colour", "color", "name" };
			case "gradientname" -> new String[]{ "gradient name", "gradient", "name" };
			case "tag" -> new String[]{ "tag" };
			default -> new String[0];
		};
		boolean again = true;
		while (again) {
			again = false;
			for (String k : kill) {
				if (n.toLowerCase(java.util.Locale.ROOT).endsWith(" " + k)) {
					n = n.substring(0, n.length() - k.length() - 1).trim();
					again = true;
				}
			}
		}
		return n.isEmpty() ? strip(name) : n;
	}
}

