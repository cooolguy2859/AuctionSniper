package com.lifestealmarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class MarketConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lifestealmarket.json");
	private static MarketConfig INSTANCE;

	private static final java.util.Properties BUILD_INFO = loadBuildInfo();
	private static java.util.Properties loadBuildInfo() {
		var p = new java.util.Properties();
		try (var in = MarketConfig.class.getResourceAsStream("/lifestealmarket.buildinfo")) {
			if (in != null) p.load(in);
		} catch (Exception ignored) {}
		return p;
	}
	private static String buildProp(String key, String def) {
		String v = BUILD_INFO.getProperty(key, "").trim();
		return (!v.isEmpty() && !v.startsWith("$")) ? v : def;
	}

	private static final String CLOUD_API_BASE = "https://auction-sniper-api.firewolfjm10.workers.dev";
	private static final String DEFAULT = buildProp("apibase", CLOUD_API_BASE);

	private static final String DISCOVERY = buildProp("discovery", "");
	public static String discoveryUrls() { return DISCOVERY; }

	public static final String API_KEY = buildProp("apikey", "728abd90e57d5d6b7f93f4746eb5765b8df17d470a7f14eabfa705b643b811ba");

	public static final String BUILT_AT = buildProp("built", "unknown");

	public static String userName() {
		try { return net.minecraft.client.MinecraftClient.getInstance().getSession().getUsername(); }
		catch (Exception e) { return ""; }
	}

	public String accessToken = "";
	public static String token() { String t = get().accessToken; return t == null ? "" : t; }

	private static final String[] OWNERS = { "TheyHating_" };
	public static boolean isOwner(String name) {
		if (name == null) return false;
		for (String o : OWNERS) if (o.equalsIgnoreCase(name)) return true;
		return false;
	}

	private static final String[] BETA_TESTERS = {
			"_Kikie", "wydwww", "Rubuhhhh", "sheerdegree", "_Snapiyy", "Aqua_D_Beast", "BunTrate", "Kingitis",
			"GladiatorLive", "T_David", "Truxicglazer",
	};
	private static boolean isBeta(String name) {
		if (name == null) return false;
		for (String b : BETA_TESTERS) if (b.equalsIgnoreCase(name)) return true;
		return false;
	}

	private static final String[] DONATORS = { "aeneas999", "Twrtle_", "sheerdegree", "Freckl3z" };
	private static boolean isDonator(String name) {
		if (name == null) return false;
		for (String d : DONATORS) if (d.equalsIgnoreCase(name)) return true;
		return false;
	}

	public static String roleTag(String name) {
		if (name == null) return null;
		if (isOwner(name))                        return "§6✦ OWNER §r";
		if (name.equalsIgnoreCase("Fullendymion") || name.equalsIgnoreCase("Kaesitius")) return "§c✦ Manager §r";
		if (name.equalsIgnoreCase("Pinkesttt"))    return "§d✦ Marketing §r";
		if (isDonator(name))                       return "§a✦ Donator §r";
		if (isBeta(name))                          return "§3✦ Beta Tester §r";
		return null;
	}

	public String apiBase = DEFAULT;

	public static volatile String localBase = null;

	public static volatile String requestUrl = "https://discord.gg/E6aS3vuAd";

	public static void setRequestUrl(String u) {
		if (u == null) return;
		u = u.trim();
		if (u.length() > 200) return;
		if (u.startsWith("https://discord.com/") || u.startsWith("https://discord.gg/")) requestUrl = u;
	}

	public static String apiBase() { return CLOUD_API_BASE; }

	public String role = "seller";
	public double guiSize = 1.0;
	public String theme = "cyan";
	public String background = "";
	public String customAccent = "";
	public String clickSound = "click";
	public boolean netPrivate = false;

	public boolean shareEnderchest = false;

	public int ecCmdFails = 0;
	public boolean ecCmdDenied = false;
	public boolean ecAutoRefresh = false;
	public boolean tosAccepted = false;
	public boolean tutorialDone = false;

	public java.util.Map<String, Integer> lastRanks = new java.util.HashMap<>();
	public long lastRanksAt = 0L;

	public boolean use12hTime = false;
	public boolean discordAlerts = true;
	public int lastReferrals = -1;
	public int onboardResetSeen = 0;

	public boolean showNetworthTags = true;

	public boolean seenViewAh = false;
	public boolean seenPartners = false;

	public static String timePattern() { return get().use12hTime ? "h:mm a" : "HH:mm"; }

	public static String dateTimePattern() { return get().use12hTime ? "MMM d, h:mm a" : "MMM d, HH:mm"; }

	public static double guiFrac() {
		double g = get().guiSize;
		if (g >= 1.1) return 0.82;
		if (g >= 0.9) return 0.66;
		return 0.48;
	}

	public static final String[] THEMES = { "cyan", "purple", "green", "amber", "pink", "blue", "red" };

	public static final java.util.Set<String> ART_THEMES = java.util.Set.of("blossom", "goldengalaxy");

	public static boolean isArtTheme(String id) {
		return id != null && ART_THEMES.contains(id.toLowerCase(java.util.Locale.ROOT));
	}

	public static volatile long dataAgeMs = -1L;
	private static volatile long dataAgeAt = 0L;

	public static void noteDataAge(java.net.http.HttpResponse<?> resp) {
		if (resp == null) return;
		try {
			resp.headers().firstValue("X-Data-Age-Ms").ifPresent(v -> {
				dataAgeMs = Long.parseLong(v.trim());
				dataAgeAt = System.currentTimeMillis();
			});
		} catch (Exception ignored) {}
	}

	public static long dataAgeNow() {
		if (dataAgeMs < 0 || dataAgeAt == 0L) return -1L;
		return dataAgeMs + (System.currentTimeMillis() - dataAgeAt);
	}
	public static final String[] SOUNDS = { "click", "thock", "tick", "pop", "marimba", "coin", "soft", "ui", "drip", "pling", "bell", "off" };

	public String lineColor = "auto";
	public static final String[] LINE_COLORS = { "auto", "cyan", "green", "purple", "amber", "pink", "blue", "red", "white" };
	public static int lineColorValue(String s) {
		return switch (s == null ? "auto" : s.toLowerCase()) {
			case "cyan" -> 0xFF39D0FF; case "green" -> 0xFF22E88A; case "purple" -> 0xFF9D7BFF;
			case "amber" -> 0xFFFFC23D; case "pink" -> 0xFFFF6FB5; case "blue" -> 0xFF4D8DFF;
			case "red" -> 0xFFFF556E; case "white" -> 0xFFFFFFFF; default -> 0;
		};
	}

	public record OwnedTheme(String id, String name, int argb, String bg) {}
	private static volatile java.util.Map<String, OwnedTheme> OWNED = java.util.Map.of();

	public static void setOwnedThemes(java.util.Map<String, OwnedTheme> m) { OWNED = (m == null) ? java.util.Map.of() : m; }

	public static volatile int loyaltyDays = -1;
	public static volatile boolean loyaltyGranted = false;
	public static java.util.Map<String, OwnedTheme> ownedThemes() { return OWNED; }
	public static OwnedTheme ownedTheme(String id) {
		OwnedTheme t = tryTheme;
		if (id != null && t != null && System.currentTimeMillis() < themeRevertAt
				&& t.id().equalsIgnoreCase(id)) return t;
		return (id == null) ? null : OWNED.get(id.toLowerCase(java.util.Locale.ROOT));
	}

	public record OwnedItem(String id, String type, String name, String payload) {}
	private static volatile java.util.List<OwnedItem> OWNED_ITEMS = java.util.List.of();
	public static void setOwnedItems(java.util.List<OwnedItem> l) { OWNED_ITEMS = (l == null) ? java.util.List.of() : l; }
	public static java.util.List<OwnedItem> ownedItems(String type) {
		java.util.List<OwnedItem> out = new java.util.ArrayList<>();
		for (OwnedItem i : OWNED_ITEMS) if (i.type().equalsIgnoreCase(type)) out.add(i);
		return out;
	}
	public static boolean ownsItem(String id) {
		for (OwnedItem i : OWNED_ITEMS) if (i.id().equalsIgnoreCase(id)) return true;
		return false;
	}
	public static boolean ownsType(String type) { return !ownedItems(type).isEmpty(); }

	private static volatile String themeRevertTo = null;
	private static volatile long themeRevertAt = 0L;

	private static volatile OwnedTheme tryTheme = null;

	public static void previewThemeFor(String id, String name, String accentHex, long ms) {
		if (themeRevertTo == null) themeRevertTo = get().theme;
		get().theme = id;
		themeRevertAt = System.currentTimeMillis() + ms;
		tryTheme = OWNED.get(id.toLowerCase(java.util.Locale.ROOT));
		if (tryTheme == null) {
			int argb = 0xFF39D0FF;
			try { argb = 0xFF000000 | (int) Long.parseLong(accentHex.replace("#", "").trim(), 16); }
			catch (Exception badHex) {  }
			tryTheme = new OwnedTheme(id, (name == null || name.isEmpty()) ? id : name, argb, "");
		}
	}

	public static int themeTrySecs() {
		return themeRevertTo == null ? 0 : (int) Math.max(1, (themeRevertAt - System.currentTimeMillis() + 999) / 1000);
	}

	public static void tickThemeTry() {
		String back = themeRevertTo;
		if (back != null && System.currentTimeMillis() >= themeRevertAt) {
			themeRevertTo = null;
			tryTheme = null;
			get().theme = back;

			net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
			if (mc != null && mc.currentScreen != null && mc.getWindow() != null) {
				try {
					mc.currentScreen.resize(mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
				} catch (Exception ignored) {}
			}
		}
	}

	public static volatile int previewAccent = 0;
	public static volatile long previewAccentUntil = 0L;
	public static volatile String previewBg = null;
	public static volatile long previewBgUntil = 0L;

	public static String activeBackground() {
		long now = System.currentTimeMillis();
		if (previewBg != null && now < previewBgUntil) return previewBg;
		String id = get().background;
		if (id != null && !id.isEmpty()) {
			for (OwnedItem i : OWNED_ITEMS) if (i.type().equals("bg") && i.id().equalsIgnoreCase(id)) return i.payload();
		}

		OwnedTheme t = ownedTheme(get().theme);
		if (t != null && t.bg() != null && !t.bg().isEmpty()) return t.bg();
		return null;
	}

	public static int hsv(float h, float s, float v) {
		float r = 0, g = 0, b = 0;
		int i = (int) (h * 6) % 6;
		float f = h * 6 - (int) (h * 6), p = v * (1 - s), q = v * (1 - f * s), t2 = v * (1 - (1 - f) * s);
		switch (i) {
			case 0 -> { r = v; g = t2; b = p; } case 1 -> { r = q; g = v; b = p; } case 2 -> { r = p; g = v; b = t2; }
			case 3 -> { r = p; g = q; b = v; } case 4 -> { r = t2; g = p; b = v; } default -> { r = v; g = p; b = q; }
		}
		return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
	}

	public static java.util.List<String> selectableThemes() {
		java.util.List<String> l = new java.util.ArrayList<>(java.util.Arrays.asList(THEMES));
		for (OwnedTheme o : OWNED.values()) if (!l.contains(o.id())) l.add(o.id());
		if (ownsType("anygui") && !l.contains("custom")) l.add("custom");
		return l;
	}

	public static String themeLabel(String id) {
		OwnedTheme o = ownedTheme(id);
		return o != null ? o.name() : (id == null ? "cyan" : id);
	}

	public static int themeColor(String t) {
		long nowP = System.currentTimeMillis();
		if (nowP < previewAccentUntil && previewAccent != 0) {

			return previewAccent == -1 ? hsv((nowP % 3000L) / 3000f, 0.6f, 1f) : previewAccent;
		}
		if ("custom".equalsIgnoreCase(t) && ownsType("anygui")) {
			try { return 0xFF000000 | (int) Long.parseLong(get().customAccent.replace("#", ""), 16); }
			catch (Exception ignored) {  }
		}
		OwnedTheme o = ownedTheme(t);
		if (o != null) return o.argb();
		return switch (t == null ? "" : t.toLowerCase()) {
			case "purple" -> 0xFF9D7BFF; case "green" -> 0xFF22E88A; case "amber" -> 0xFFFFC23D;
			case "pink" -> 0xFFFF6FB5; case "blue" -> 0xFF4D8DFF; case "red" -> 0xFFFF556E;
			default -> 0xFF39D0FF;
		};
	}

	public static int[] themeBackground(String t) {
		OwnedTheme o = ownedTheme(t);
		if (o == null) return null;
		int accent = o.argb();
		return new int[]{ blend(accent, 0xFFFFFFFF, 0.66f), blend(accent, 0xFFFFFFFF, 0.24f) };
	}

	static int blend(int a, int b, float t) {
		int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int ra = Math.round(aa + (ba - aa) * t), rr = Math.round(ar + (br - ar) * t);
		int rg = Math.round(ag + (bg - ag) * t), rb = Math.round(ab + (bb - ab) * t);
		return (ra << 24) | (rr << 16) | (rg << 8) | rb;
	}
	public java.util.List<String> favorites = new java.util.ArrayList<>();

	public static final String[] ALERT_SOUNDS = { "coin", "chord", "marimba", "pop", "pling", "bell", "chime", "ding", "levelup", "off" };

	public static class Alert {
		public String ticker; public long target; public boolean below;

		public transient boolean triggered;
		public String sound = "pling";
		public Alert() {}
		public Alert(String t, long target, boolean below) { this.ticker = t; this.target = target; this.below = below; }
		public Alert(String t, long target, boolean below, String sound) { this(t, target, below); this.sound = sound; }
	}
	public java.util.List<Alert> alerts = new java.util.ArrayList<>();
	public Alert findAlert(String t) { for (Alert a : alerts) if (a.ticker.equalsIgnoreCase(t)) return a; return null; }

	public boolean isBuyer() { return "buyer".equalsIgnoreCase(role); }
	public boolean isFav(String t) { return favorites.stream().anyMatch(f -> f.equalsIgnoreCase(t)); }
	public void toggleFav(String t) {
		if (isFav(t)) favorites.removeIf(f -> f.equalsIgnoreCase(t)); else favorites.add(t);
		save();
	}

	public static MarketConfig get() {
		if (INSTANCE == null) load();
		return INSTANCE;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) INSTANCE = GSON.fromJson(Files.readString(PATH), MarketConfig.class);
		} catch (Exception e) {
			System.err.println("[LifestealMarket] config load failed: " + e);
		}
		if (INSTANCE == null) INSTANCE = new MarketConfig();
		if (INSTANCE.favorites == null) INSTANCE.favorites = new java.util.ArrayList<>();
		if (INSTANCE.alerts == null) INSTANCE.alerts = new java.util.ArrayList<>();
		if (INSTANCE.role == null || INSTANCE.role.isBlank()) INSTANCE.role = "seller";
		if (INSTANCE.guiSize < 0.6 || INSTANCE.guiSize > 1.5) INSTANCE.guiSize = 1.0;
		if (INSTANCE.theme == null || INSTANCE.theme.isBlank()) INSTANCE.theme = "cyan";
		if (INSTANCE.clickSound == null || INSTANCE.clickSound.isBlank()) INSTANCE.clickSound = "click";

		String a = INSTANCE.apiBase;
		if (a == null || a.isBlank() || a.contains("localhost") || a.contains("127.0.0.1")
				|| a.contains("trycloudflare.com") || a.contains("ngrok")) {
			INSTANCE.apiBase = DEFAULT;
		}
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(INSTANCE));
		} catch (Exception e) {
			System.err.println("[LifestealMarket] config save failed: " + e);
		}
	}
}

