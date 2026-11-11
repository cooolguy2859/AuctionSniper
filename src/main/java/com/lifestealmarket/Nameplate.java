package com.lifestealmarket;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

public final class Nameplate {
	private Nameplate() {}

	private static volatile String pvTag = "", pvSpec = "";
	private static volatile long pvUntil = 0L;

	private static volatile Text selfRaw = null;

	public static void armClear(long until) { pvTag = ""; pvSpec = ""; pvUntil = until; }
	public static void armTag(String tag, long until) { pvTag = tag == null ? "" : tag; pvUntil = until; }
	public static void armSpec(String spec, long until) { pvSpec = spec == null ? "" : spec; pvUntil = until; }
	public static boolean previewLive() {
		return System.currentTimeMillis() < pvUntil && (!pvTag.isEmpty() || !pvSpec.isEmpty());
	}

	public static long previewEndsAt() { return previewLive() ? pvUntil : 0L; }

	public static String selfName() {
		String n = MarketConfig.userName();
		return n == null || n.isEmpty() ? "You" : n;
	}

	public static Text selfPlateSource() {
		Text r = selfRaw;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (r == null && mc.player != null) {

			mc.player.getDisplayName();
			r = selfRaw;
		}
		if (r != null) return r;
		return mc.player != null ? mc.player.getName() : Text.literal(selfName());
	}

	public static Text decorate(Text original, PlayerEntity who) {
		if (original == null || who == null) return original;
		MinecraftClient mc = MinecraftClient.getInstance();

		boolean self = mc.player == who;
		if (self) selfRaw = original;
		long now = System.currentTimeMillis();
		boolean prev = self && now < pvUntil && (!pvTag.isEmpty() || !pvSpec.isEmpty());
		boolean home = NetworthTracker.INSTANCE.onHome();
		if (!prev && !home) return original;
		int secs = prev ? (int) Math.max(1, (pvUntil - now + 999) / 1000) : 0;

		if (!home) {
			var out = Text.empty();
			if (!pvTag.isEmpty()) out.append(Text.literal(pvTag));
			out.append(pvSpec.isEmpty() ? original : recolourIgn(original, who.getName().getString(), pvSpec));
			return out.append(Text.literal(" §8preview " + secs + "s"));
		}
		return compose(original, who.getName().getString(), self,
				prev ? pvTag : "", prev ? pvSpec : "", secs);
	}

	public static Text compose(Text original, String name, boolean self,
			String ovTag, String ovSpec, int previewSecs) {
		if (original == null) return original;
		NetworthTracker nt = NetworthTracker.INSTANCE;

		String tag = Flair.loaded() ? null : MarketConfig.roleTag(name);
		String refTag = nt.referralTag(name);

		String[] flair = Flair.of(name);
		String flairTag = (flair != null && !flair[1].isEmpty()) ? flair[1] : null;

		String roleTag = (flair != null && flair.length > 3 && !flair[3].isEmpty()) ? flair[3] : null;
		if (roleTag != null) tag = roleTag;

		String nameColor = (flair != null && !flair[0].isEmpty()) ? flair[0] : null;

		if (ovTag != null && !ovTag.isEmpty()) flairTag = ovTag;
		if (ovSpec != null && !ovSpec.isEmpty()) nameColor = ovSpec;

		String worth = null;
		if (MarketConfig.get().showNetworthTags) {
			Long nw = self
					? (!MarketConfig.get().netPrivate && nt.netWorth() > 0 ? nt.netWorth() : null)
					: nt.publicNetworth(name);
			if (nw != null && nw > 0) worth = " §a✦ " + Coins.fmt(nw);
		}

		if (tag == null && refTag == null && worth == null && flairTag == null && nameColor == null
				&& roleTag == null && previewSecs <= 0) return original;
		var out = Text.empty();
		if (tag != null) out.append(Text.literal(tag));
		if (refTag != null) out.append(Text.literal(refTag));
		if (flairTag != null) out.append(Text.literal(flairTag));
		if (nameColor != null) {

			out.append(recolourIgn(original, name, nameColor));
		} else out.append(original);
		if (worth != null) out.append(Text.literal(worth));

		if (previewSecs > 0) out.append(Text.literal(" §8preview " + previewSecs + "s"));
		return out;
	}

	public static Text recolourIgn(Text original, String ign, String spec) {
		java.util.List<Object[]> runs = new java.util.ArrayList<>();
		original.visit((style, str) -> { runs.add(new Object[]{ str, style }); return java.util.Optional.empty(); },
				net.minecraft.text.Style.EMPTY);
		StringBuilder flat = new StringBuilder();
		for (Object[] r : runs) flat.append((String) r[0]);
		int at = flat.lastIndexOf(ign);
		if (at < 0) return original;
		int end = at + ign.length(), n = ign.length();

		var out = Text.empty();
		int pos = 0;
		for (Object[] r : runs) {
			String s = (String) r[0];
			net.minecraft.text.Style st = (net.minecraft.text.Style) r[1];
			int runStart = pos, runEnd = pos + s.length();
			pos = runEnd;
			int ovStart = Math.max(at, runStart), ovEnd = Math.min(end, runEnd);
			if (ovStart >= ovEnd) { out.append(Text.literal(s).setStyle(st)); continue; }
			if (runStart < ovStart) out.append(Text.literal(s.substring(0, ovStart - runStart)).setStyle(st));
			if (!perLetter(spec)) {

				out.append(Text.literal(s.substring(ovStart - runStart, ovEnd - runStart))
						.setStyle(st.withColor(net.minecraft.text.TextColor.fromRgb(colourAt(spec, 0, n)))));
			} else for (int i = ovStart; i < ovEnd; i++) {
				int rgb = colourAt(spec, i - at, n);
				out.append(Text.literal(String.valueOf(s.charAt(i - runStart)))
						.setStyle(st.withColor(net.minecraft.text.TextColor.fromRgb(rgb))));
			}
			if (runEnd > ovEnd) out.append(Text.literal(s.substring(ovEnd - runStart)).setStyle(st));
		}
		return out;
	}

	public static int colourAt(String spec, int i, int n) {
		try {
			long now = System.currentTimeMillis();
			if ("rainbow".equals(spec)) return MarketConfig.hsv((now % 3000L) / 3000f, 0.6f, 1f) & 0xFFFFFF;
			if ("rainbowgrad".equals(spec)) {
				float base = (now % 3000L) / 3000f;
				return MarketConfig.hsv((base + 0.6f * i / Math.max(1, n)) % 1f, 0.6f, 1f) & 0xFFFFFF;
			}
			String s = spec.replace("#", "");
			if (s.contains("-")) {
				String[] p = s.split("-", 2);
				int c1 = (int) Long.parseLong(p[0].trim(), 16) & 0xFFFFFF;
				int c2 = (int) Long.parseLong(p[1].trim(), 16) & 0xFFFFFF;
				float t = n <= 1 ? 0f : (float) i / (n - 1);
				int r = lerp((c1 >> 16) & 0xFF, (c2 >> 16) & 0xFF, t);
				int g = lerp((c1 >> 8) & 0xFF, (c2 >> 8) & 0xFF, t);
				int b = lerp(c1 & 0xFF, c2 & 0xFF, t);
				return (r << 16) | (g << 8) | b;
			}
			return (int) Long.parseLong(s, 16) & 0xFFFFFF;
		} catch (Exception e) { return 0xFFFFFF; }
	}

	public static boolean perLetter(String spec) {
		return spec != null && (spec.equals("rainbowgrad") || spec.replace("#", "").contains("-"));
	}

	private static int lerp(int a, int b, float t) { return Math.round(a + (b - a) * t); }
}

