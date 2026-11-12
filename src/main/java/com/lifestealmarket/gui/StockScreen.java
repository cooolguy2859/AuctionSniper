package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StockScreen extends Screen {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final String[] RANGES = { "1d", "3d", "7d", "30d" };
	private static final int CURVE_STEPS = 14;

	private static final int C_UP = 0xFF22E88A, C_DOWN = 0xFFFF556E, C_INK = 0xFFDCE7FF, C_MUTE = 0xFF7D92BF;
	private int C_DIM = 0xCC02040A, C_PANEL = 0xFF070C18, C_TITLE = 0xFF0B2238;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_BOX = 0xFF15294a, C_GRID = 0x1839D0FF, C_HAIR = 0x2239D0FF;
	private static final int[] PALETTE = {
			0xFF39D0FF, 0xFF22E88A, 0xFF8B7BFF, 0xFFFF556E, 0xFFFFC23D,
			0xFF4DFFE0, 0xFFFF7AD0, 0xFFB6FF5C, 0xFF5C9DFF, 0xFFFF9F40,
	};

	private final DateTimeFormatter F_DAY = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault());
	private DateTimeFormatter F_TIME, F_FULL;
	private boolean lastUse12h;
	private void refreshTimeFormatters() {
		boolean cur = MarketConfig.get().use12hTime;
		if (F_FULL != null && cur == lastUse12h) return;
		F_TIME = DateTimeFormatter.ofPattern(MarketConfig.timePattern()).withZone(ZoneId.systemDefault());
		F_FULL = DateTimeFormatter.ofPattern(MarketConfig.dateTimePattern()).withZone(ZoneId.systemDefault());
		lastUse12h = cur;
	}

	private final Screen parent;
	private String range = "7d";
	private final long openTime = System.currentTimeMillis();

	private static final class Line {
		final String ticker; final int color; final long addedAt = System.currentTimeMillis();
		volatile long[][] points = new long[0][];
		volatile long price, floor = -1, high, low, supply = -1, vwap;
		volatile int vol, sales;
		volatile double chg; volatile boolean firm, loaded;
		volatile int userColor = 0;
		volatile long[] bookPrices = new long[0];
		volatile String[] bookSellers = new String[0];
		float legendHover = 0f;

		long[][] drawSeries = null;
		long[][] drawSeriesFor = null;

		String smoothKey = "";
		long[][] smoothWin = null, smoothSm = null;
		volatile int pointsVersion = 0;

		volatile boolean orderLine = false;
		volatile long demand, orderCount;
		Line(String t, int c) { ticker = t; color = c; }
	}

	private final List<Line> lines = new ArrayList<>();

	private volatile String[] allTickers = new String[0];
	private long nextTickerRetry = 0L;
	private int colorCursor = 0;
	private String pendingAdd;

	private TextFieldWidget topSearch, addSearch;
	private StyledButton viewAhButton;
	private volatile String status = "";

	private int px, py, pw, ph, cl, cr, rightX, boxL, boxR, boxTop, boxBot, plotL, plotR, plotTop, plotBot;

	private int statMaxX = Integer.MAX_VALUE;

	private int barY, hdrY;

	private static final net.minecraft.util.Identifier BL_WINDOW = net.minecraft.util.Identifier.of("lifestealmarket", "textures/theme/blossom_window.png");
	private static final int BL_TW = 1572, BL_TH = 912;

	private boolean windowSkin = false;
	private float tipEase = -1f;
	private final List<int[]> bookRowBoxes = new ArrayList<>();

	private static boolean blossom() {
		return "blossom".equalsIgnoreCase(MarketConfig.get().theme) && MarketConfig.ownedTheme("blossom") != null;
	}
	private static void tex(DrawContext c, net.minecraft.util.Identifier id, int x, int y, int w, int h, int tw, int th) {
		c.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, tw, th, tw, th);
	}

	private double uiScale = 1.0;
	private final List<int[]> removeBoxes = new ArrayList<>();
	private final List<Object[]> suggestBoxes = new ArrayList<>();
	private final List<Object[]> statHit = new ArrayList<>();
	private final List<Object[]> starBoxes = new ArrayList<>();
	private final List<Object[]> colorBoxes = new ArrayList<>();
	private boolean showFavs = false, showFavsAdd = false;
	private int suggScroll = 0;
	private static final int SUG_VISIBLE = 8;

	private double zoomLo = 0.0, zoomHi = 1.0;
	private long zTmin = 0, zTmax = 1;

	private boolean draggingPan = false;
	private double panStartX, panStartLo, panStartHi;
	private int[] panThumb = null;

	private boolean alertMode = false, alertBelow = true, showAlerts = false;
	private String alertPrefill = "";
	private String alertSound = "pling";
	private int[] tutSkip, tutAction;

	private int g2sX(int gx) { return (int) Math.round(this.width / 2.0 + (gx - this.width / 2.0) * uiScale); }
	private int g2sY(int gy) { return (int) Math.round(this.height / 2.0 + (gy - this.height / 2.0) * uiScale); }
	private TextFieldWidget alertField;
	private final List<Object[]> alertRowBoxes = new ArrayList<>();
	private final List<int[]> alertRemoveBoxes = new ArrayList<>();
	private Line hovered;

	public StockScreen(Screen parent, String query) {
		super(Text.literal("Stock"));
		this.parent = parent;
		if (query != null && !query.isBlank()) pendingAdd = query.trim();
	}

	private void addPictured(StyledButton b) { if (blossom()) b.ghost(); addDrawableChild(b); }

	@Override

	protected void init() {
		int cx = this.width / 2, cy = this.height / 2;
		pw = 560; ph = 320;

		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = cx - pw / 2; py = cy - ph / 2;

		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a;
		C_ACC_DIM = (a & 0x00FFFFFF) | 0x55000000;
		C_GRID = (a & 0x00FFFFFF) | 0x18000000;
		C_HAIR = (a & 0x00FFFFFF) | 0x22000000;
		C_BOX = StyledButton.lerpColor(a, 0xFF0A1428, 0.55f);
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (a & 0xFFFFFF), 0.14f);
		C_TITLE = StyledButton.lerpColor(0xFF0B1A2E, 0xFF000000 | (a & 0xFFFFFF), 0.26f);
		C_DIM = 0xCC000000 | (StyledButton.lerpColor(0xFF02040A, a, 0.08f) & 0xFFFFFF);
		cl = px + 14; cr = px + pw - 14;
		rightX = cr - 150;
		boxL = cl; boxR = rightX - 12; boxTop = py + 72; boxBot = py + ph - 42;
		plotL = boxL + 46; plotR = boxR - 8; plotTop = boxTop + 10; plotBot = boxBot - 8;

		boolean skinned = MarketConfig.ownedTheme(MarketConfig.get().theme) != null;
		barY = py + (blossom() ? 37 : skinned ? 28 : 33);
		hdrY = py + (blossom() ? 10 : 5);

		int addY = blossom() ? py + (int) (ph * 230.0 / BL_TH) - 8 : boxTop;

		addDrawableChild(new Backdrop());

		topSearch = new TextFieldWidget(this.textRenderer, cl, barY, 210, 18, Text.literal("search"));
		topSearch.setPlaceholder(Text.literal("§8🔍 Search any item..."));
		topSearch.setMaxLength(48);
		addDrawableChild(topSearch);
		setInitialFocus(topSearch);

		addPictured(new StyledButton(cl + 216, barY, 20, 18, Text.literal("§e★"), showFavs,
				() -> { showFavs = !showFavs; suggScroll = 0; alertMode = false; showAlerts = false; if (showFavs) this.setFocused(null); clearAndInit(); })
				.accent(C_ACCENT).tooltip("§e★ Favorites", "§7Show your saved items.", "§7Tap ☆ on any item to save it."));

		addPictured(new StyledButton(cl + 238, barY, 20, 18, Text.literal(""), alertMode || showAlerts, this::toggleAlert)
				.bellIcon().accent(C_ACCENT).tooltip("§6Price alert", "§7Open one item to set a chat ping",
						"§7when it hits your price, or tap here", "§7with nothing open to see/manage", "§7all your alerts."));

		if (com.lifestealmarket.LifestealMarketClient.updateAvailable) {

			addDrawableChild(new StyledButton(cl + 260, barY, 20, 18,
					Text.literal("§a§l⬆"), this::openModrinth).badge(true)
					.accent(0xFF1BD96A).tooltip("§a⬆ Update available: §f" + com.lifestealmarket.LifestealMarketClient.updateVersion,
							"§7Click to open the download page", "§7then drop the new jar in your mods folder."));
		}

		if (alertMode && lines.size() == 1) {
			int ay = barY + 22, ax = cl;
			addDrawableChild(new StyledButton(ax, ay, 70, 18,
					Text.literal(alertBelow ? "§adrops below" : "§crises above"),
					() -> { alertBelow = !alertBelow; clearAndInit(); }).accent(C_ACCENT));
			alertField = new TextFieldWidget(this.textRenderer, ax + 74, ay, 78, 18, Text.literal("price"));
			alertField.setPlaceholder(Text.literal("§8e.g. 200k"));
			if (!alertPrefill.isEmpty()) alertField.setText(alertPrefill);
			addDrawableChild(alertField);
			this.setFocused(alertField);

			addDrawableChild(new StyledButton(ax + 156, ay, 58, 18, Text.literal("§b♪ " + alertSound), () -> {
				String[] s = com.lifestealmarket.MarketConfig.ALERT_SOUNDS; int idx = 0;
				for (int i = 0; i < s.length; i++) if (s[i].equals(alertSound)) idx = i;
				alertSound = s[(idx + 1) % s.length];
				previewAlertSound(alertSound);
				clearAndInit();
			}).accent(C_ACCENT).tooltip("§b§lAlert sound", "§7Sound played when this alert fires.", "§8Click to cycle + preview."));
			addDrawableChild(new StyledButton(ax + 218, ay, 40, 18, Text.literal("§a§lSet"), this::confirmAlert).accent(C_ACCENT));
			addDrawableChild(new StyledButton(ax + 260, ay, 18, 18, Text.literal("§c✕"),
					() -> { alertMode = false; clearAndInit(); }).accent(C_ACCENT));
		}

		int n = RANGES.length, gap = 4, tw = 34, tx0 = cr - (n * tw + (n - 1) * gap);
		for (int i = 0; i < n; i++) {
			String r = RANGES[i];
			addPictured(new StyledButton(tx0 + i * (tw + gap), barY, tw, 18,
					r.equals(range) ? Ui.label(r, C_ACCENT) : Text.literal("§7" + r), r.equals(range),
					() -> { range = r; zoomLo = 0; zoomHi = 1; clearAndInit(); }).accent(C_ACCENT));
		}

		addSearch = new TextFieldWidget(this.textRenderer, rightX, addY, 128, 16, Text.literal("add"));
		addSearch.setPlaceholder(Text.literal("§8+ add item..."));
		addSearch.setMaxLength(48);
		if (blossom()) {

			addSearch.setDrawsBackground(false);
			addSearch.setPlaceholder(Text.empty());
		}
		addDrawableChild(addSearch);
		addPictured(new StyledButton(rightX + 130, addY, 18, 16, Text.literal("§e★"), showFavsAdd,
				() -> { showFavsAdd = !showFavsAdd; suggScroll = 0; showFavs = false; alertMode = false; showAlerts = false;
						if (showFavsAdd) this.setFocused(null); clearAndInit(); })
				.accent(C_ACCENT).tooltip("§e★ Add from favorites", "§7Pick a saved item to overlay."));

		int doneX = blossom() ? px + (int) (pw * 1183.0 / BL_TW) : rightX + (cr - rightX - 96) / 2;
		int doneY = blossom() ? py + (int) (ph * 845.0 / BL_TH) : py + ph - 22;
		int doneW = blossom() ? (int) (pw * 259.0 / BL_TW) : 96;
		addPictured(new StyledButton(doneX, doneY, doneW, 16, Text.literal("§b§lDone"), this::close).accent(C_ACCENT));

		addPictured(new StyledButton(cr - 22, hdrY, 22, 16, Text.literal(""),
				() -> { if (this.client != null) this.client.setScreen(new SettingsScreen(this)); })
				.accent(C_ACCENT).gearIcon().tooltip("§b§lSettings"));

		addPictured(new StyledButton(cr - 104, hdrY, 78, 16, Text.literal("§eNet Worth"),
				() -> { if (this.client != null) this.client.setScreen(new NetworthScreen(this)); })
				.accent(C_ACCENT));

		viewAhButton = new StyledButton(cl + 282, barY, 94, 18, Ui.label("🛒 View on AH", C_ACCENT),
				() -> { markSeenViewAh(); openOnAh(); })
				.primary().accent(C_ACCENT).badge(!MarketConfig.get().seenViewAh)
				.tooltip("§b§lView on the AH", "§7Opens the in-game auction house", "§7searched for this item.");
		viewAhButton.visible = !lines.isEmpty();
		addDrawableChild(viewAhButton);

		if (allTickers.length == 0) loadTickerList();

		if (this.client != null && com.lifestealmarket.NetworthTracker.isHomeServer(this.client)) Tutorial.start();
	}

	private void loadTickerList() {
		get(base() + "/api/tickers", body -> {
			try {
				JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
				java.util.LinkedHashSet<String> t = new java.util.LinkedHashSet<>();
				for (int i = 0; i < arr.size(); i++) t.add(arr.get(i).getAsJsonObject().get("ticker").getAsString());

				t.addAll(com.lifestealmarket.NetworthTracker.INSTANCE.orderItemNames());
				allTickers = t.toArray(new String[0]);
				if (pendingAdd != null) { replaceWith(bestMatch(pendingAdd)); pendingAdd = null; }
			} catch (Exception e) { status = "ticker list error"; }
		}, err -> status = "Backend offline (" + err + ")");
	}

	private void replaceWith(String ticker) { lines.clear(); colorCursor = 0; if (ticker != null) addTicker(ticker); else status = "No match"; }

	private void toggleAlert() {
		showFavs = false;
		if (alertMode) { alertMode = false; clearAndInit(); return; }
		if (lines.size() != 1 || lines.get(0).price <= 0) { showAlerts = !showAlerts; clearAndInit(); return; }
		showAlerts = false;
		com.lifestealmarket.MarketConfig cfg = com.lifestealmarket.MarketConfig.get();
		Line l = lines.get(0);
		var ex = cfg.findAlert(l.ticker);
		if (ex != null) {
			cfg.alerts.remove(ex); com.lifestealmarket.MarketConfig.save();
			com.lifestealmarket.LifestealMarketClient.syncAlerts();

			net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
			if (mc.player != null) mc.player.sendMessage(Text.literal("§8[§b⏰§8] §7Alert removed §8— §f" + l.ticker), false);
			alertMode = false; clearAndInit();
		} else {
			alertMode = true; alertBelow = cfg.isBuyer(); alertPrefill = String.valueOf(l.price); alertSound = "pling";
			clearAndInit();
		}
	}

	private void previewAlertSound(String name) {
		net.minecraft.sound.SoundEvent se = com.lifestealmarket.LifestealMarketClient.alertSound(name);
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (se != null && mc.player != null) mc.player.playSound(se, 1f, 1.5f);
	}

	private void confirmAlert() {
		if (lines.size() != 1) { alertMode = false; clearAndInit(); return; }
		long tgt = parseAmount(alertField == null ? "" : alertField.getText());
		if (tgt <= 0) { status = "Enter a valid price (e.g. 200k)"; return; }
		com.lifestealmarket.MarketConfig cfg = com.lifestealmarket.MarketConfig.get();
		String t = lines.get(0).ticker;
		var ex = cfg.findAlert(t); if (ex != null) cfg.alerts.remove(ex);
		cfg.alerts.add(new com.lifestealmarket.MarketConfig.Alert(t, tgt, alertBelow, alertSound));
		com.lifestealmarket.MarketConfig.save();
		com.lifestealmarket.LifestealMarketClient.syncAlerts();

		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc.player != null) mc.player.sendMessage(Text.literal("§8[§e⏰§8] §eAlert set §8— §f" + t + " §7when it "
				+ (alertBelow ? "§adrops below §f" : "§crises above §f") + Coins.fmt(tgt)), false);
		alertMode = false; clearAndInit();
	}

	private long parseAmount(String s) {
		s = s.trim().toLowerCase(Locale.ROOT).replace(" ", "");
		if (s.isEmpty()) return -1;
		double mult = 1;
		if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
		else if (s.endsWith("m")) { mult = 1e6; s = s.substring(0, s.length() - 1); }
		else if (s.endsWith("b")) { mult = 1e9; s = s.substring(0, s.length() - 1); }
		if (mult == 1) {

			s = s.replace(",", "").replace(".", "");
		} else {

			s = s.replace(",", "");
		}
		try { return Math.round(Double.parseDouble(s) * mult); } catch (Exception e) { return -1; }
	}

	private void addTicker(String ticker) {
		if (ticker == null) return;
		for (Line l : lines) if (l.ticker.equalsIgnoreCase(ticker)) return;
		Line line = new Line(ticker, PALETTE[colorCursor++ % PALETTE.length]);
		lines.add(line); status = ""; fetchLine(line);
	}

	private void refetchAll() { zoomLo = 0; zoomHi = 1; for (Line l : lines) fetchLine(l); clearAndInit(); }

	private long lastAutoRefresh = System.currentTimeMillis();
	private long lastDataVersion = com.lifestealmarket.LifestealMarketClient.DATA_VERSION;
	@Override
	public void tick() {
		super.tick();
		long now = System.currentTimeMillis();

		long dv = com.lifestealmarket.LifestealMarketClient.DATA_VERSION;
		boolean pushed = dv != lastDataVersion;
		if (!lines.isEmpty() && (pushed || now - lastAutoRefresh >= 12_000L)
				&& now - lastAutoRefresh >= 5_000L) {
			lastDataVersion = dv; lastAutoRefresh = now;
			for (Line l : lines) fetchLine(l);
		}
	}

	private void fetchOrderLine(Line line) {
		String url;
		try { url = base() + "/api/orders/series?range=" + range + "&ticker="
				+ URLEncoder.encode(line.ticker, StandardCharsets.UTF_8); }
		catch (Exception e) { return; }
		get(url, body -> {
			try {
				JsonObject o = JsonParser.parseString(body).getAsJsonObject();
				JsonArray pa = o.getAsJsonArray("points");
				long[][] pts = new long[pa.size()][2];
				for (int i = 0; i < pa.size(); i++) {
					JsonArray p = pa.get(i).getAsJsonArray();
					pts[i][0] = p.get(0).getAsLong(); pts[i][1] = p.get(1).getAsLong();
				}
				line.orderLine = true;
				line.price = o.get("bid").getAsLong();
				line.demand = o.has("demand") ? o.get("demand").getAsLong() : 0;
				line.orderCount = o.has("orders") ? o.get("orders").getAsLong() : 0;
				line.floor = -1; line.supply = -1; line.vol = 0; line.sales = 0; line.firm = false;
				line.high = o.get("high").getAsLong(); line.low = o.get("low").getAsLong();
				line.chg = o.get("chg").getAsDouble();
				line.vwap = o.has("avg") ? o.get("avg").getAsLong() : 0;
				line.points = pts; line.pointsVersion++; line.loaded = true;

				if (pts.length == 0) status = line.ticker + " has no buy orders right now.";
			} catch (Exception ignored) {}
		}, err -> { line.loaded = true; });
	}

	private void fetchLine(Line line) { fetchLine(line, 0); }

	private void fetchLine(Line line, int attempt) {
		String url;
		try {

			url = base() + "/api/series?range=30d&ticker=" + URLEncoder.encode(line.ticker, StandardCharsets.UTF_8); }
		catch (Exception e) { return; }
		get(url, body -> {
			try {
				JsonObject o = JsonParser.parseString(body).getAsJsonObject();
				line.price = o.get("price").getAsLong();
				line.floor = o.get("floor").isJsonNull() ? -1 : o.get("floor").getAsLong();
				line.supply = o.get("supply").isJsonNull() ? -1 : o.get("supply").getAsLong();
				line.vwap = o.get("vwap").getAsLong();
				line.vol = o.get("vol").getAsInt(); line.sales = o.get("sales").getAsInt();
				line.firm = o.get("firm").getAsBoolean();
				JsonArray pa = o.getAsJsonArray("points");
				long[][] pts = new long[pa.size()][2];
				for (int i = 0; i < pa.size(); i++) { JsonArray p = pa.get(i).getAsJsonArray(); pts[i][0] = p.get(0).getAsLong(); pts[i][1] = p.get(1).getAsLong(); }

				if (pts.length == 0 && line.floor > 0) {
					long t1 = System.currentTimeMillis();
					pts = new long[][]{ { t1 - rangeMs(), line.floor }, { t1, line.floor } };
				}

				if (pts.length == 0) { fetchOrderLine(line); return; }
				line.points = pts; line.pointsVersion++; line.loaded = true;

				long[][] sm = smooth(despike(pts));
				if (sm.length >= 1) {
					long hi = Long.MIN_VALUE, lo = Long.MAX_VALUE;
					for (long[] q : sm) { hi = Math.max(hi, q[1]); lo = Math.min(lo, q[1]); }
					line.high = hi; line.low = lo;
					if (sm.length >= 2) {
						double f = sm[0][1] <= 0 ? 1 : sm[0][1], last = sm[sm.length - 1][1];
						line.chg = (last - f) / f * 100.0;
					} else line.chg = 0;
				} else {
					line.high = o.get("high").getAsLong(); line.low = o.get("low").getAsLong();
					line.chg = o.get("chg").getAsDouble();
				}
			} catch (Exception ignored) {}
		}, err -> {

			if (attempt < 2) {
				java.util.concurrent.CompletableFuture.delayedExecutor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
						.execute(() -> { var c = this.client; if (c != null) c.execute(() -> { if (!line.loaded) fetchLine(line, attempt + 1); }); });
			} else status = "Backend offline (" + err + ")";
		});
		fetchBook(line);
	}

	private void fetchBook(Line line) {
		String url;
		try { url = base() + "/api/book?ticker=" + URLEncoder.encode(line.ticker, StandardCharsets.UTF_8); }
		catch (Exception e) { return; }
		get(url, body -> {
			try {
				JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
				long[] prices = new long[arr.size()]; String[] sellers = new String[arr.size()];
				for (int i = 0; i < arr.size(); i++) {
					JsonObject o = arr.get(i).getAsJsonObject();
					prices[i] = o.get("price").getAsLong();
					sellers[i] = o.has("seller") ? o.get("seller").getAsString() : "";
				}
				line.bookPrices = prices; line.bookSellers = sellers;
			} catch (Exception ignored) {}
		}, err -> {});
	}

	private String base() { return MarketConfig.apiBase(); }

	private long rangeMs() {
		try { return Long.parseLong(range.replaceAll("[^0-9]", "")) * 86_400_000L; }
		catch (Exception e) { return 7L * 86_400_000L; }
	}

	private boolean buyer() { return MarketConfig.get().isBuyer(); }
	private int dirColor(boolean up) { return up ? (buyer() ? C_DOWN : C_UP) : (buyer() ? C_UP : C_DOWN); }

	private int colorFor(Line l, boolean primary, boolean single) {
		int lc = com.lifestealmarket.MarketConfig.lineColorValue(com.lifestealmarket.MarketConfig.get().lineColor);
		if (l.userColor != 0) return l.userColor;
		if (single) return lc != 0 ? lc : C_ACCENT;
		return (primary && lc != 0) ? lc : l.color;
	}
	private String chgCode(double chg) {
		if (chg > 0) return buyer() ? "§c" : "§a";
		if (chg < 0) return buyer() ? "§a" : "§c";
		return "§7";
	}

	private void get(String url, java.util.function.Consumer<String> ok, java.util.function.Consumer<String> fail) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId()).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId())
				.header("X-User", com.lifestealmarket.MarketConfig.userName())
				.header("X-Token", com.lifestealmarket.MarketConfig.token())
				.GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			MarketConfig.noteDataAge(resp);
			if (this.client != null) this.client.execute(() -> {
				if (err != null) fail.accept(err.getClass().getSimpleName());
				else if (resp.statusCode() == 200) ok.accept(resp.body());
				else fail.accept("HTTP " + resp.statusCode());
			});
		});
	}

	private String bestMatch(String q) {
		String ql = q.toLowerCase(Locale.ROOT); String best = null; int bs = Integer.MIN_VALUE;
		int thr = typoThreshold(ql);
		for (String t : allTickers) {
			String tl = t.toLowerCase(Locale.ROOT);
			int s;
			if (tl.equals(ql)) s = 1000;
			else if (tl.startsWith(ql)) s = 800 - tl.length();
			else { int d = editDistance(ql, tl); s = d <= thr ? 300 - d * 20 - tl.length() : Integer.MIN_VALUE; }
			if (s > bs) { bs = s; best = t; }
		}
		return bs == Integer.MIN_VALUE ? null : best;
	}

	private List<String> suggestions(String q) {
		List<String> out = new ArrayList<>();
		if (q.isBlank()) return out;
		String ql = q.toLowerCase(Locale.ROOT);
		int thr = typoThreshold(ql);
		java.util.List<int[]> scored = new ArrayList<>();
		for (int i = 0; i < allTickers.length; i++) {
			int s = suggestScore(allTickers[i].toLowerCase(Locale.ROOT), ql, thr);
			if (s > Integer.MIN_VALUE) {

				if (com.lifestealmarket.MarketConfig.get().isFav(allTickers[i])) s += 1500;
				scored.add(new int[]{ i, s });
			}
		}
		scored.sort((a, b) -> Integer.compare(b[1], a[1]));
		for (int i = 0; i < Math.min(9, scored.size()); i++) out.add(allTickers[scored.get(i)[0]]);
		return out;
	}

	private int suggestScore(String tl, String ql, int thr) {
		if (tl.equals(ql)) return 1000;
		if (tl.startsWith(ql)) return 800 - tl.length();
		for (String w : tl.split("[^a-z0-9]+")) if (!w.isEmpty() && w.startsWith(ql)) return 650 - tl.length();
		if (tl.contains(ql)) return 450 - tl.length();
		int d = editDistance(ql, tl);
		if (d <= thr) return 250 - d * 20 - tl.length();

		int dw = Integer.MAX_VALUE;
		for (String w : tl.split("[^a-z0-9]+")) if (!w.isEmpty()) dw = Math.min(dw, editDistance(ql, w));
		return dw <= thr ? 200 - dw * 20 - tl.length() : Integer.MIN_VALUE;
	}

	private static int typoThreshold(String q) { return Math.max(1, Math.min(3, q.length() / 4 + 1)); }

	private static int editDistance(String a, String b) {
		int n = a.length(), m = b.length();
		if (n == 0) return m; if (m == 0) return n;
		int[] prev = new int[m + 1], cur = new int[m + 1];
		for (int j = 0; j <= m; j++) prev[j] = j;
		for (int i = 1; i <= n; i++) {
			cur[0] = i;
			for (int j = 1; j <= m; j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] tmp = prev; prev = cur; cur = tmp;
		}
		return prev[m];
	}

	private void paint(DrawContext ctx, int mx, int my) {
		removeBoxes.clear(); suggestBoxes.clear(); statHit.clear(); starBoxes.clear(); colorBoxes.clear();
		alertRowBoxes.clear(); alertRemoveBoxes.clear(); bookRowBoxes.clear();

		if (allTickers.length == 0 && System.currentTimeMillis() > nextTickerRetry) {
			nextTickerRetry = System.currentTimeMillis() + 3000;
			loadTickerList();
		}
		if (blossom()) {

			StyledButton.roundFill(ctx, px + 3, py + 4, pw, ph, 0x55000000);
			tex(ctx, BL_WINDOW, px, py, pw, ph, BL_TW, BL_TH);
			windowSkin = false;
		} else {
			StyledButton.roundFill(ctx, px + 3, py + 4, pw, ph, 0x55000000);

			MarketConfig.OwnedTheme wot = MarketConfig.ownedTheme(MarketConfig.get().theme);
			windowSkin = wot != null && ThemeBg.drawThumb(ctx,
					wot.id().toLowerCase(java.util.Locale.ROOT) + "_window", px, py, pw, ph);
		}
		if (!blossom() && !windowSkin) {
			StyledButton.roundFill(ctx, px, py, pw, ph, C_PANEL);
			ctx.fill(px + 1, py + 1, px + pw - 1, py + 26, 0xFF16181C);

			ctx.fillGradient(px + 1, py + 1, px + pw - 1, py + 26, 0x12FFFFFF, 0x00000000);
			Ui.hGrad(ctx, px + 1, py + 1, px + 1 + (int) (pw * 0.62), py + 26,
					0x30000000 | (C_ACCENT & 0xFFFFFF), 0x00000000, 4);
			ctx.fillGradient(px + 1, py + 27, px + pw - 1, py + 70, C_GRID, 0x00000000);
			ctx.fill(px + 1, py + 26, px + pw - 1, py + 27, C_ACC_DIM);
			StyledButton.roundBorder(ctx, px, py, pw, ph, C_ACCENT);
			double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 600.0);
			int gA = (int) (0x1c + 0x22 * pulse);
			frame(ctx, px - 1, py - 1, px + pw + 1, py + ph + 1, (gA << 24) | (C_ACCENT & 0xFFFFFF));

			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§b§lLifesteal Market"), cl, py + 9, 0xFFFFFFFF);
			int tw = this.textRenderer.getWidth(Text.literal("§lLifesteal Market"));
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8by Rem"), cl + tw + 6, py + 9, 0xFFFFFFFF);

			int tick = 0xCC000000 | (C_ACCENT & 0xFFFFFF);
			ctx.fill(px + 3, py + 3, px + 10, py + 4, tick);           ctx.fill(px + 3, py + 3, px + 4, py + 10, tick);
			ctx.fill(px + pw - 10, py + 3, px + pw - 3, py + 4, tick); ctx.fill(px + pw - 4, py + 3, px + pw - 3, py + 10, tick);
			ctx.fill(px + 3, py + ph - 4, px + 10, py + ph - 3, tick); ctx.fill(px + 3, py + ph - 10, px + 4, py + ph - 3, tick);
			ctx.fill(px + pw - 10, py + ph - 4, px + pw - 3, py + ph - 3, tick); ctx.fill(px + pw - 4, py + ph - 10, px + pw - 3, py + ph - 3, tick);
			float sp = (System.currentTimeMillis() % 3800L) / 3800f;
			int gx = px + 1 + (int) ((pw - 62) * sp);
			Ui.hGrad(ctx, gx, py + 26, gx + 30, py + 27, 0x00FFFFFF, 0x88FFFFFF, 3);
			Ui.hGrad(ctx, gx + 30, py + 26, gx + 60, py + 27, 0x88FFFFFF, 0x00FFFFFF, 3);
		}

		Line stat = hovered != null ? hovered : (lines.isEmpty() ? null : lines.get(0));

		boolean editorOpen = alertMode && lines.size() == 1;
		if (!editorOpen) drawStats(ctx, stat);

		if (editorOpen) {

			int ay = barY + 22;
			ctx.fill(cl - 3, ay - 2, cl + 281, ay + 20, 0xFF0B1220);
			ctx.fill(cl - 3, ay - 2, cl + 281, ay - 1, C_ACC_DIM);
		}
		drawBoxAndChart(ctx, mx, my);
		drawRightPanel(ctx, mx, my);

		if (!status.isEmpty()) ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7" + status),
				(plotL + plotR) / 2, (plotTop + plotBot) / 2, 0xFFFFFFFF);

	}

	private static boolean enoughInfo(Line l) {
		return l != null && (l.supply >= 2 || l.sales >= 2
				|| (l.orderLine && l.points != null && l.points.length >= 2));
	}

	private void drawStats(DrawContext ctx, Line s) {
		int y = py + (MarketConfig.ownedTheme(MarketConfig.get().theme) != null ? 56 : 61);
		statMaxX = boxR;
		if (s == null) {

			if (blossom() || windowSkin) return;
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Search an item above to begin"), cl, y, 0xFFFFFFFF);
			return;
		}
		if (s.loaded && !enoughInfo(s)) {
			ItemIcons.draw(ctx, s.ticker, cl, y - 2, 0.8f);
			int xx = cl + 15;
			xx = statSeg(ctx, xx, y, "§f§l" + trim(s.ticker, 20), desc("Item", "The item this chart tracks.")) + 8;
			com.lifestealmarket.NetworthTracker nt = com.lifestealmarket.NetworthTracker.INSTANCE;

			if (nt.isChartOnly(s.ticker)) {
				long[] oi = nt.orderInfo(s.ticker);
				List<String> why = desc("Buy orders only",
						"Bulk items like building blocks aren't", "priced from the auction house - only",
						"the live buy orders are tracked.", "They don't count toward net worth.");
				if (oi != null && oi[0] > 0) {
					xx = statSeg(ctx, xx, y, "§7top bid §f" + Coins.fmt(oi[0]), why) + 7;
					statSeg(ctx, xx, y, "§8" + oi[2] + " open order" + (oi[2] == 1 ? "" : "s") + " §7- not AH-priced", why);
				} else {
					statSeg(ctx, xx, y, "§8Buy orders only §7- no AH price for bulk items", why);
				}
				return;
			}
			statSeg(ctx, xx, y, "§8Not enough info §7- fewer than 2 on AH and no sale history",
					desc("Not enough info", "This needs at least 2 currently listed", "on the AH, or past recorded sales,", "before it can be priced."));
			return;
		}

		long age = MarketConfig.dataAgeNow();
		boolean showAge = age >= STALE_WARN_MS, bad = age >= STALE_LOUD_MS;
		String ageTxt = (bad ? "§c⚠ " : "§7") + "data " + ageShort(age) + " old";
		int ageW = showAge ? this.textRenderer.getWidth(Text.literal(ageTxt)) : 0;
		statMaxX = boxR - (showAge ? ageW + 10 : 0);
		String chgC = !s.firm ? "§7" : chgCode(s.chg);
		String chg = s.firm ? (s.chg > 0 ? "+" : "") + String.format(Locale.ROOT, "%.2f", s.chg) + "%" : "-";
		ItemIcons.draw(ctx, s.ticker, cl, y - 2, 0.8f);
		int x = cl + 15;
		x = statSeg(ctx, x, y, "§f§l" + trim(s.ticker, 20), desc("Item", "The item this chart tracks.")) + 7;
		boolean bidLine = s.orderLine;
		x = statSeg(ctx, x, y, "§b" + Coins.fmt(s.price), bidLine
				? desc("Top buy order", "The best price anyone is paying", "for this right now. Bulk items are", "not sold on the auction house.")
				: desc("Price (median)", "Median of the last 5 sales -", "the current typical sale price."));
		drawCoin(ctx, x + 1, y, s.price); x += 17;
		x = statSeg(ctx, x, y, chgC + chg, desc("Change", "Price change over the range.",
				buyer() ? "§agreen = price down §8(good) §cred = up" : "§agreen = price up §cred = price down")) + 8;
		if (!bidLine) {
			String lowS = s.floor < 0 ? "§8not listed" : "§f" + Coins.fmt(s.floor);
			x = statSeg(ctx, x, y, "§7Lowest on Sale " + lowS, desc("Lowest on Sale", s.floor < 0 ? "Nothing listed right now." : "Cheapest listing for sale right now", s.floor < 0 ? "" : "- what you'd pay to buy it.")) + 7;
		}
		x = statSeg(ctx, x, y, "§7H §a" + Coins.fmt(s.high), desc("Range High", "Highest sale price in this range.")) + 7;
		x = statSeg(ctx, x, y, "§7L §c" + Coins.fmt(s.low), desc("Range Low", "Lowest sale price in this range.")) + 7;
		if (bidLine) {

			x = statSeg(ctx, x, y, "§7orders §f" + s.orderCount,
					desc("Open buy orders", "How many people currently have", "a buy order out for this.")) + 7;
			x = statSeg(ctx, x, y, "§7wanted §f" + Coins.fmt(s.demand),
					desc("Total demand", "Coins behind every live buy order", "for this item added together."));
		} else {
			x = statSeg(ctx, x, y, "§7vol §f" + s.vol, desc("Volume", "Number of detected sales in", "the selected time range.")) + 7;
			String supS = s.supply < 0 ? "§8not listed" : "§f" + s.supply;
			x = statSeg(ctx, x, y, "§7sup " + supS, desc("Supply", s.supply < 0 ? "Nothing listed right now." : "How many are currently listed", s.supply < 0 ? "" : "on the auction house."));
		}

		if (showAge) {
			statMaxX = Integer.MAX_VALUE;
			statSeg(ctx, boxR - ageW, y, ageTxt,
					desc(bad ? "Prices are STALE" : "Data age",
							bad ? "The scanner has stopped, so nothing" : "How long since the last market",
							bad ? "here is current. Don't trade on it." : "scan reached the server."));
		}
	}

	private static final long STALE_WARN_MS = 3 * 60_000L;
	private static final long STALE_LOUD_MS = 15 * 60_000L;

	private static String ageShort(long ms) {
		long s = ms / 1000;
		if (s < 90) return s + "s";
		if (s < 5400) return (s / 60) + "m";
		if (s < 86400) return (s / 3600) + "h";
		return (s / 86400) + "d";
	}

	private int statSeg(DrawContext ctx, int x, int y, String text, List<String> d) {
		int w = this.textRenderer.getWidth(Text.literal(text));
		if (x + w > statMaxX) return x;
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(text), x, y, 0xFFFFFFFF);
		statHit.add(new Object[]{ x, y - 1, x + w, y + 9, d });
		return x + w;
	}

	private List<String> desc(String title, String... body) {
		List<String> l = new ArrayList<>();
		l.add("§b§l" + title);
		for (String b : body) if (!b.isEmpty()) l.add("§7" + b);
		return l;
	}

	private Object hoverStatKey = null;
	private long hoverStatStart = 0L;

	@SuppressWarnings("unchecked")
	private void drawStatTooltip(DrawContext ctx, int mx, int my) {

		if (alertMode || showFavs || showFavsAdd) { hoverStatKey = null; return; }
		Object[] found = null;
		for (Object[] h : statHit) {
			if (mx >= (int) h[0] && mx <= (int) h[2] && my >= (int) h[1] && my <= (int) h[3]) { found = h; break; }
		}
		if (found == null) { hoverStatKey = null; return; }
		long now = System.currentTimeMillis();
		if (found[4] != hoverStatKey) { hoverStatKey = found[4]; hoverStatStart = now; }
		if (now - hoverStatStart > 2000) return;
		drawTip(ctx, mx, my + 6, (List<String>) found[4]);
	}

	private void drawBoxAndChart(DrawContext ctx, int mx, int my) {
		hovered = null;

		if (!blossom()) {
			StyledButton.roundFill(ctx, boxL, boxTop, boxR - boxL, boxBot - boxTop, 0xFF16181C);

			MarketConfig.OwnedTheme ot = MarketConfig.ownedTheme(MarketConfig.get().theme);
			if (ot != null && ThemeBg.drawThumb(ctx, ot.id().toLowerCase(java.util.Locale.ROOT) + "_graph",
					boxL + 1, boxTop + 1, boxR - boxL - 2, boxBot - boxTop - 2))
				ctx.fill(boxL + 1, boxTop + 1, boxR - 1, boxBot - 1, 0xBE0B0F18);
			else

				ctx.fillGradient(boxL + 1, boxTop + 1, boxR - 1, boxBot - 1, 0x14FFFFFF, 0x06000000);
			StyledButton.roundBorder(ctx, boxL, boxTop, boxR - boxL, boxBot - boxTop, C_BOX);
		}
		if (zoomLo > 0.001 || zoomHi < 0.999) {
			String zh = "🔍 dbl-click reset";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + zh), boxR - this.textRenderer.getWidth(zh) - 5, boxTop + 3, 0xFFFFFFFF);
		}

		if (lines.size() == 1 && lines.get(0).loaded && !enoughInfo(lines.get(0))) {

			boolean bulk = com.lifestealmarket.NetworthTracker.INSTANCE.isChartOnly(lines.get(0).ticker);
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(bulk ? "§7No auction-house price for this" : "§7Not enough information"),
					(plotL + plotR) / 2, (plotTop + plotBot) / 2 - 5, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(bulk ? "§8Bulk items are tracked by buy orders only"
							: "§8Needs 2+ on the AH, or past sales, to estimate"),
					(plotL + plotR) / 2, (plotTop + plotBot) / 2 + 6, 0xFFFFFFFF);
			return;
		}

		List<Line> ll = new ArrayList<>();
		for (Line l : lines) if (l.points.length >= 1) ll.add(l);
		if (ll.isEmpty()) {
			if (lines.isEmpty() && status.isEmpty())

				ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal((blossom() ? "§7" : "§8") + "🔍 Search any item above to see its graph"),
						(plotL + plotR) / 2, (plotTop + plotBot) / 2, 0xFFFFFFFF);
			else if (status.isEmpty()) {

				boolean stillLoading = false;
				for (Line l : lines) if (!l.loaded) { stillLoading = true; break; }
				if (stillLoading) {
					ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7Loading..."), (plotL + plotR) / 2, (plotTop + plotBot) / 2, 0xFFFFFFFF);
				} else {
					ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7No sales recorded yet"),
							(plotL + plotR) / 2, (plotTop + plotBot) / 2 - 5, 0xFFFFFFFF);
					ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8The chart starts once the scanner sees this item sell"),
							(plotL + plotR) / 2, (plotTop + plotBot) / 2 + 6, 0xFFFFFFFF);
				}
			}
			return;
		}
		boolean pct = false;
		boolean single = ll.size() == 1;

		if (single && ll.get(0).orderLine) {

			Line ol = ll.get(0);
			String lbl = "§8buy orders on /orders"
					+ (ol.orderCount > 0 ? " §7· " + ol.orderCount + " order" + (ol.orderCount == 1 ? "" : "s") : "")
					+ (ol.demand > 0 ? " §7· " + Coins.fmt(ol.demand) + " wanted" : "");
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(lbl), plotL + 6, plotTop + 4, 0xFFFFFFFF);
		} else if (single && ll.get(0).sales == 0 && ll.get(0).floor > 0)
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8no sales yet - line shows the cheapest listing"),
					plotL + 6, plotTop + 4, 0xFFFFFFFF);

		java.util.Map<Line, long[][]> DS = new java.util.HashMap<>();
		for (Line l : ll) {
			if (l.drawSeries == null || l.drawSeriesFor != l.points) {
				l.drawSeries = downsample(despike(l.points), 500);
				l.drawSeriesFor = l.points;
			}
			DS.put(l, l.drawSeries);
		}

		long nowMs = System.currentTimeMillis();
		long tmax = nowMs;
		long tmin = nowMs - rangeMs();

		if (tmax <= tmin) tmax = tmin + 1;
		zoomLo = Math.max(0, Math.min(zoomLo, 0.98)); zoomHi = Math.min(1, Math.max(zoomHi, zoomLo + 0.02));

		long gx0 = tmin + (long) (zoomLo * (tmax - tmin));
		long gx1 = tmin + (long) (zoomHi * (tmax - tmin));
		if (gx1 <= gx0) gx1 = gx0 + 1;
		this.zTmin = tmin; this.zTmax = tmax;

		java.util.Map<Line, long[][]> WIN = new java.util.HashMap<>();
		java.util.Map<Line, long[][]> SM = new java.util.HashMap<>();
		for (Line l : ll) {
			long[][] ds = DS.get(l);

			long bucket = Math.max(1000L, rangeMs() / 2000L);
			String key = (gx0 / bucket) + ":" + (gx1 / bucket) + ":" + (ds == null ? 0 : ds.length) + ":" + l.pointsVersion;
			if (!key.equals(l.smoothKey) || l.smoothWin == null || l.smoothSm == null) {
				l.smoothKey = key;
				l.smoothWin = window(ds, gx0, gx1);

				l.smoothSm = smooth(despike(l.smoothWin));

				long[][] sm = l.smoothSm;
				if (sm != null && sm.length >= 1) {
					long hi = Long.MIN_VALUE, lo = Long.MAX_VALUE;
					for (long[] q : sm) { hi = Math.max(hi, q[1]); lo = Math.min(lo, q[1]); }
					l.high = hi; l.low = lo;
					l.chg = sm.length >= 2 && sm[0][1] > 0
							? (sm[sm.length - 1][1] - sm[0][1]) / (double) sm[0][1] * 100.0 : 0;
				}

				int inWin = 0;
				if (l.points != null) for (long[] p : l.points) if (p[0] >= gx0 && p[0] <= gx1) inWin++;
				l.vol = inWin;
			}
			WIN.put(l, l.smoothWin);
			SM.put(l, l.smoothSm);
		}

		double vmin, vmax;
		if (single) {
			double dmin = Double.MAX_VALUE, dmax = -Double.MAX_VALUE;
			for (long[] p : SM.get(ll.get(0))) { dmin = Math.min(dmin, p[1]); dmax = Math.max(dmax, p[1]); }
			if (dmax < 0) { dmin = 0; dmax = 1; }
			double pad = (dmax > dmin) ? (dmax - dmin) * 0.35 : Math.max(1, dmax * 0.18);
			vmin = Math.max(0, dmin - pad); vmax = dmax + pad;
		} else {
			vmin = 0; vmax = 0;
			for (Line l : ll) for (long[] p : SM.get(l)) vmax = Math.max(vmax, p[1]);
			if (vmax <= 0) vmax = 1;
			vmax += vmax * 0.08;
		}
		if (vmax <= vmin) vmax = vmin + 1;
		final double VMIN = vmin, VRANGE = vmax - VMIN;

		for (int i = 0; i <= 4; i++) {
			int gy = plotTop + (plotBot - plotTop) * i / 4;
			ctx.fill(plotL, gy, plotR, gy + 1, 0x1EFFFFFF);
			double val = vmax - (vmax - VMIN) * i / 4;
			String lbl = pct ? "§7" + (val >= 0 ? "+" : "") + String.format(Locale.ROOT, "%.0f%%", val) : "§7" + Coins.fmt((long) val);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(lbl), boxL + 4, gy - 3, 0xFFFFFFFF);
		}

		long now = System.currentTimeMillis();

		DateTimeFormatter xf = (gx1 - gx0) <= 40L * 3600 * 1000 ? F_TIME : F_DAY;
		int ticks = 5;
		String lastLbl = "";
		for (int i = 0; i <= ticks; i++) {
			long t = gx0 + (gx1 - gx0) * i / ticks;
			int tx = plotL + (plotR - plotL) * i / ticks;
			if (i > 0 && i < ticks) ctx.fill(tx, plotTop, tx + 1, plotBot, 0x0AFFFFFF);
			String lbl = xf.format(Instant.ofEpochMilli(t));
			if (lbl.equals(lastLbl)) continue;
			lastLbl = lbl;
			int w = this.textRenderer.getWidth(lbl);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + lbl),
					Math.max(boxL, Math.min(boxR - w, tx - w / 2)), boxBot + 3, 0xFFFFFFFF);
		}

		for (Line l : ll) {
			long[][] p = WIN.get(l); if (p.length < 1) continue;
			double base = p[0][1] == 0 ? 1 : p[0][1];
			int col = colorFor(l, l == ll.get(0), single);

			if (p.length == 1) {
				int y0 = plotBot - (int) (((pct ? 0 : p[0][1]) - VMIN) / VRANGE * (plotBot - plotTop));
				ctx.fill(plotL, y0, plotR, y0 + 2, col);
				StyledButton.roundFill(ctx, plotL - 2, y0 - 2, 4, 4, col);
				continue;
			}

			long[][] sm = SM.get(l);
			int m = sm.length; int[] sxs = new int[m], sys = new int[m];
			for (int i = 0; i < m; i++) {
				sxs[i] = plotL + (int) ((sm[i][0] - gx0) * (long) (plotR - plotL) / (gx1 - gx0));
				double v = pct ? (sm[i][1] / base - 1) * 100 : sm[i][1];
				sys[i] = Math.max(plotTop, Math.min(plotBot, plotBot - (int) ((v - VMIN) / VRANGE * (plotBot - plotTop))));
			}

			List<int[]> cv = curve(sxs, sys);
			int total = cv.size();
			int drawN = Math.min(Math.max(2, (int) Math.ceil(ease(now - l.addedAt, 380f) * total)), total);
			int[] cx = new int[drawN], cy = new int[drawN];
			for (int i = 0; i < drawN; i++) { cx[i] = cv.get(i)[0]; cy[i] = Math.max(plotTop, Math.min(plotBot, cv.get(i)[1])); }
			polyline(ctx, cx, cy, drawN, col, 2);

			if (single) {

				int ex = sxs[m - 1], ey = sys[m - 1];
				float pulse = (float) (0.5 + 0.5 * Math.sin(now / 420.0));
				int ga = (int) (0x2A + 0x3C * pulse);
				ctx.fill(ex - 4, ey - 4, ex + 5, ey + 5, (ga << 24) | (col & 0xFFFFFF));
				ctx.fill(ex - 2, ey - 2, ex + 3, ey + 3, 0xFF000000 | (col & 0xFFFFFF));
				ctx.fill(ex - 1, ey - 1, ex + 2, ey + 2, 0xFFFFFFFF);

				int hi = 0, lo = 0;
				for (int i = 1; i < m; i++) { if (sm[i][1] > sm[hi][1]) hi = i; if (sm[i][1] < sm[lo][1]) lo = i; }
				marker(ctx, sxs[hi], sys[hi], C_UP, "§a▲ " + Coins.fmt(sm[hi][1]));
				marker(ctx, sxs[lo], sys[lo], C_DOWN, "§c▼ " + Coins.fmt(sm[lo][1]));

				for (long[] tr : com.lifestealmarket.NetworthTracker.INSTANCE.tradesFor(l.ticker)) {
					long tt = tr[0], pr = tr[1]; boolean buy = tr[2] == 1;
					if (tt < gx0 || tt > gx1 || pr <= 0) continue;
					int tx = plotL + (int) ((tt - gx0) * (long) (plotR - plotL) / (gx1 - gx0));
					int ty = Math.max(plotTop, Math.min(plotBot, plotBot - (int) ((pr - VMIN) / VRANGE * (plotBot - plotTop))));
					int tc = buy ? C_DOWN : C_UP;
					StyledButton.roundFill(ctx, tx - 3, ty - 3, 6, 6, 0xFF0A0E18);
					StyledButton.roundFill(ctx, tx - 2, ty - 2, 4, 4, tc);
					ctx.drawTextWithShadow(this.textRenderer, Text.literal(buy ? "§cB" : "§aS"), tx + 4, ty - 4, 0xFFFFFFFF);
				}
			}
		}

		boolean inside = mx >= plotL && mx <= plotR && my >= plotTop && my <= plotBot;
		if (inside) {
			long t = gx0 + (long) ((mx - plotL) / (double) (plotR - plotL) * (gx1 - gx0));
			ctx.fill(mx, plotTop, mx + 1, plotBot, 0x33FFFFFF);
			List<int[]> rows = new ArrayList<>();
			int bestDy = Integer.MAX_VALUE;
			for (Line l : ll) {
				long[][] p = SM.get(l); if (p.length == 0) continue;
				double base = p[0][1] == 0 ? 1 : p[0][1];
				long pr = priceAt(p, t);
				double v = pct ? (pr / base - 1) * 100 : pr;
				int sy = Math.max(plotTop, Math.min(plotBot, plotBot - (int) ((v - VMIN) / VRANGE * (plotBot - plotTop))));
				int col = colorFor(l, l == ll.get(0), single);
				StyledButton.roundFill(ctx, mx - 3, sy - 3, 6, 6, 0xFF0A0E18);
				StyledButton.roundFill(ctx, mx - 2, sy - 2, 4, 4, col);
				rows.add(new int[]{ col, (int) Math.min(Integer.MAX_VALUE, pr) });
				int dy = Math.abs(sy - my); if (dy < bestDy) { bestDy = dy; hovered = l; }
			}
			rows.sort((a, b) -> Long.compare(b[1] & 0xFFFFFFFFL, a[1] & 0xFFFFFFFFL));
			drawCursorTip(ctx, mx, my, t, rows);
		}

		if (zoomLo > 0.001 || zoomHi < 0.999) {
			int by = plotBot + 2, bh = 4;
			ctx.fill(plotL, by, plotR, by + bh, 0x28FFFFFF);
			int tx0 = plotL + (int) (zoomLo * (plotR - plotL));
			int tx1 = plotL + (int) (zoomHi * (plotR - plotL));
			StyledButton.roundFill(ctx, tx0, by - 1, tx1 - tx0, bh + 2, C_ACCENT);
			panThumb = new int[]{ tx0 - 3, by - 3, tx1 + 3, by + bh + 3 };
		} else {
			panThumb = null;
		}
	}

	private static long[][] downsample(long[][] p, int cap) {
		if (p == null || p.length <= cap || cap < 2) return p;
		long[][] out = new long[cap][];
		double step = (p.length - 1) / (double) (cap - 1);
		for (int i = 0; i < cap; i++) out[i] = p[(int) Math.round(i * step)];
		return out;
	}

	private long[][] smooth(long[][] p) {
		if (p.length < 3) return p;
		long t0 = p[0][0], tN = p[p.length - 1][0];
		if (tN <= t0) return p;
		int M = Math.max(48, Math.min(110, p.length));
		double[] pr = new double[M];
		for (int i = 0; i < M; i++) pr[i] = priceAt(p, t0 + (tN - t0) * i / (M - 1));
		int w = 4;
		long[][] out = new long[M][2];
		for (int i = 0; i < M; i++) {

			int we = Math.min(w, Math.min(i, M - 1 - i));
			double s = 0; int c = 0;
			for (int j = -we; j <= we; j++) { int k = i + j; if (k >= 0 && k < M) { s += pr[k]; c++; } }
			out[i][0] = t0 + (tN - t0) * i / (M - 1);
			out[i][1] = Math.round(s / c);
		}
		return out;
	}

	private long[][] despike(long[][] p) {
		if (p.length < 5) return p;
		long[][] out = despikePass(p, 4, 0.28);
		out = despikePass(out, 2, 0.28);
		return out;
	}

	private long[][] despikePass(long[][] p, int w, double thresh) {
		long[][] out = new long[p.length][2];
		for (int i = 0; i < p.length; i++) {
			out[i][0] = p[i][0];
			int a = Math.max(0, i - w), b = Math.min(p.length - 1, i + w);
			long[] win = new long[b - a + 1];
			for (int k = a; k <= b; k++) win[k - a] = p[k][1];
			java.util.Arrays.sort(win);
			long m = win[win.length / 2];
			long v = p[i][1];
			out[i][1] = (m > 0 && Math.abs(v - m) > m * thresh) ? m : v;
		}
		return out;
	}

	private long[][] window(long[][] p, long gx0, long gx1) {
		if (p.length == 0) return p;
		List<long[]> out = new ArrayList<>();
		out.add(new long[]{ gx0, priceAt(p, gx0) });
		for (long[] q : p) if (q[0] > gx0 && q[0] < gx1) out.add(q);
		out.add(new long[]{ gx1, priceAt(p, gx1) });
		return out.toArray(new long[0][]);
	}

	private long priceAt(long[][] p, long t) {
		if (p.length == 0) return 0;
		if (t <= p[0][0]) return p[0][1];
		if (t >= p[p.length - 1][0]) return p[p.length - 1][1];
		for (int i = 1; i < p.length; i++) {
			if (p[i][0] >= t) {
				long t0 = p[i - 1][0], t1 = p[i][0];
				double f = (t1 == t0) ? 0 : (t - t0) / (double) (t1 - t0);
				return Math.round(p[i - 1][1] + (p[i][1] - p[i - 1][1]) * f);
			}
		}
		return p[p.length - 1][1];
	}

	private void drawCursorTip(DrawContext ctx, int mx, int my, long t, List<int[]> rows) {
		boolean single = rows.size() == 1;
		String date = F_FULL.format(Instant.ofEpochMilli(t));
		int w = this.textRenderer.getWidth(date);
		List<String> vals = new ArrayList<>();
		for (int[] r : rows) {
			String v = Coins.fmt(r[1] & 0xFFFFFFFFL);
			vals.add(v);
			w = Math.max(w, (single ? 0 : 11) + this.textRenderer.getWidth(v) + 17);
		}
		int pad = 7, rowH = single ? 13 : 11;
		int h = pad + 9 + 3 + rows.size() * rowH + pad - 4;
		w += pad * 2;
		int wantTx = mx + 14, ty = my - h - 8;
		if (wantTx + w > boxR) wantTx = mx - w - 14;
		if (ty < boxTop) ty = my + 14;

		if (tipEase < 0) tipEase = wantTx;
		tipEase += (wantTx - tipEase) * 0.30f;
		int tx = Math.round(tipEase);

		StyledButton.roundFill(ctx, tx + 2, ty + 2, w, h, 0x66000000);
		StyledButton.roundFill(ctx, tx, ty, w, h, 0xFF0B1A30);
		ctx.fill(tx + 1, ty + 1, tx + w - 1, ty + 2, C_ACC_DIM);
		StyledButton.roundBorder(ctx, tx, ty, w, h, C_ACCENT);

		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7" + date), tx + pad, ty + pad - 1, 0xFFFFFFFF);
		int yy = ty + pad + 11;
		for (int i = 0; i < rows.size(); i++) {
			int col = rows.get(i)[0]; int vx;
			if (single) {
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f§l" + vals.get(i)), tx + pad, yy, 0xFFFFFFFF);
				vx = tx + pad + this.textRenderer.getWidth(Text.literal("§l" + vals.get(i)));
			} else {
				ctx.fill(tx + pad, yy + 1, tx + pad + 6, yy + 7, col);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + vals.get(i)), tx + pad + 11, yy, 0xFFFFFFFF);
				vx = tx + pad + 11 + this.textRenderer.getWidth(vals.get(i));
			}
			drawCoin(ctx, vx + 2, yy - 1, rows.get(i)[1] & 0xFFFFFFFFL);
			yy += rowH;
		}
	}

	private void marker(DrawContext ctx, int x, int y, int col, String label) {
		StyledButton.roundFill(ctx, x - 2, y - 2, 4, 4, col);
		int w = Ui.widthNoCodes(this.textRenderer, label);
		int lx = Math.max(plotL, Math.min(plotR - w, x - w / 2));
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(label), lx, y < plotTop + 12 ? y + 5 : y - 11, 0xFFFFFFFF);
	}

	private void drawTip(DrawContext ctx, int mx, int my, List<String> lines) {
		int w = 0; for (String s : lines) w = Math.max(w, Ui.widthNoCodes(this.textRenderer, s));
		w += 12; int h = lines.size() * 10 + 8;
		int tx = mx + 12, ty = my - h - 6;
		if (tx + w > boxR) tx = mx - w - 12;
		if (ty < boxTop) ty = my + 12;
		StyledButton.roundFill(ctx, tx, ty, w, h, 0xFF0A1428);
		StyledButton.roundBorder(ctx, tx, ty, w, h, C_ACC_DIM);
		int yy = ty + 5;
		for (String s : lines) { ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), tx + 6, yy, 0xFFFFFFFF); yy += 10; }
	}

	private void drawRightPanel(DrawContext ctx, int mx, int my) {

		boolean anyCompare = !lines.isEmpty();
		if (anyCompare && !blossom() && !windowSkin) {
			StyledButton.roundFill(ctx, rightX - 8, boxTop - 6, cr - rightX + 14, boxBot - boxTop + 12, 0xC20A101A);

			ctx.fillGradient(rightX - 7, boxTop - 5, cr + 5, boxBot + 5, 0x12FFFFFF, 0x00000000);
			StyledButton.roundBorder(ctx, rightX - 8, boxTop - 6, cr - rightX + 14, boxBot - boxTop + 12,
					(C_ACCENT & 0xFFFFFF) | 0x44000000);
		} else if (anyCompare && windowSkin) {

			StyledButton.roundFill(ctx, rightX - 8, boxTop - 6, cr - rightX + 14, boxBot - boxTop + 12, 0xB4060A12);
		}
		if (!anyCompare) return;

		int y = blossom() ? py + 116 : boxTop + 22;

		if (!blossom() && !windowSkin) {
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8COMPARE"), rightX, y - 1, 0xFFFFFFFF);
			ctx.fill(rightX, y + 9, cr, y + 10, C_HAIR);
		}
		y += 14;
		if (lines.isEmpty()) {
			if (!blossom() && !windowSkin) {
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Add items to"), rightX, y, 0xFFFFFFFF);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8overlay them."), rightX, y + 10, 0xFFFFFFFF);
			}
		}
		for (int i = 0; i < lines.size(); i++) {
			Line l = lines.get(i); int rt = y, rb = y + 14;
			boolean hov = mx >= rightX - 2 && mx <= cr && my >= rt && my <= rb;
			l.legendHover += ((hov ? 1f : 0f) - l.legendHover) * 0.25f;
			if (l.legendHover > 0.01f) StyledButton.roundFill(ctx, rightX - 2, rt, cr - rightX + 2, 14, ((int) (l.legendHover * 30) << 24) | 0x39D0FF);
			int col = colorFor(l, l == lines.get(0), lines.size() == 1);
			ctx.fill(rightX, y + 3, rightX + 8, y + 11, col);
			StyledButton.roundBorder(ctx, rightX - 1, y + 2, 10, 10, 0x66FFFFFF);
			colorBoxes.add(new Object[]{ rightX - 1, y + 2, rightX + 9, y + 12, l });
			ItemIcons.draw(ctx, l.ticker, rightX + 12, y + 1, 0.65f);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + trim(l.ticker, 10)), rightX + 24, y + 2, 0xFFFFFFFF);

			boolean fav = com.lifestealmarket.MarketConfig.get().isFav(l.ticker);
			int fb = cr - 22;
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(fav ? "§e★" : "§8☆"), fb, y + 2, 0xFFFFFFFF);
			starBoxes.add(new Object[]{ fb - 2, y, fb + 10, y + 12, l.ticker });

			int xb = cr - 9;
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§c✕"), xb, y + 2, 0xFFFFFFFF);
			removeBoxes.add(new int[]{ xb - 3, y, xb + 9, y + 12, i });
			y += 16;
			if (y > boxBot - 14) break;
		}

		if (lines.size() == 1) {
			Line l = lines.get(0);
			long[] bp = l.bookPrices;
			y += 6;
			boolean anyHov = false;
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8ORDER BOOK §7(" + bp.length + " listed)"), rightX, y, 0xFFFFFFFF);
			ctx.fill(rightX, y + 9, cr, y + 10, C_HAIR);
			y += 13;
			if (bp.length == 0) {
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(l.loaded ? "§8none listed now" : "§8loading..."), rightX, y, 0xFFFFFFFF);
			} else {
				long cheapest = bp[0];
				for (int i = 0; i < bp.length && y < boxBot - 10; i++) {
					String who = i < l.bookSellers.length ? l.bookSellers[i] : "";

					boolean hovR = mx >= rightX - 3 && mx <= cr + 3 && my >= y - 2 && my < y + 9;
					anyHov |= hovR;
					if (hovR) StyledButton.roundFill(ctx, rightX - 3, y - 2, cr - rightX + 6, 11,
							(C_ACCENT & 0xFFFFFF) | 0x2E000000);
					bookRowBoxes.add(new int[]{ rightX - 3, y - 2, cr + 3, y + 9 });
					String pc = i == 0 ? "§a§l" : hovR ? "§e" : "§f";
					drawCoin(ctx, rightX, y - 1, bp[i]);
					ctx.drawTextWithShadow(this.textRenderer, Text.literal(pc + Coins.fmt(bp[i])), rightX + 16, y, 0xFFFFFFFF);
					if (!who.isEmpty())
						ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + trim(who, 9)), cr - this.textRenderer.getWidth(trim(who, 9)), y, 0xFFFFFFFF);
					y += 11;
				}
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(anyHov ? "§7click §8→ buy it on the AH"
						: "§8cheapest §a" + Coins.fmt(cheapest)), rightX, Math.min(y + 1, boxBot - 2), 0xFFFFFFFF);
			}
		}
	}

	private void drawSuggestions(DrawContext ctx, int mx, int my) {
		if (alertMode || showAlerts) return;
		com.lifestealmarket.MarketConfig cfg = com.lifestealmarket.MarketConfig.get();
		List<String> list; int action, sx, sw, sy;

		if (showFavs || showFavsAdd) {
			list = new ArrayList<>(cfg.favorites);
			action = showFavsAdd ? 1 : 0;
			TextFieldWidget f = showFavsAdd ? addSearch : topSearch;
			sx = f.getX(); sw = Math.max(190, f.getWidth()); sy = f.getY() + f.getHeight() + 2;
			if (list.isEmpty()) {
				StyledButton.roundFill(ctx, sx, sy, sw, 18, 0xFF101B2E);
				StyledButton.roundBorder(ctx, sx, sy, sw, 18, C_ACC_DIM);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8No favorites - tap ☆ on an item"), sx + 5, sy + 5, 0xFFFFFFFF);
				return;
			}
		} else {
			TextFieldWidget f = (topSearch != null && topSearch.isFocused()) ? topSearch
					: (addSearch != null && addSearch.isFocused() ? addSearch : null);
			if (f == null) return;
			action = (f == topSearch) ? 0 : 1;
			list = suggestions(f.getText());
			if (list.isEmpty()) return;
			sx = f.getX(); sw = Math.max(190, f.getWidth()); sy = f.getY() + f.getHeight() + 2;
		}

		int total = list.size(), vis = Math.min(SUG_VISIBLE, total);
		suggScroll = Math.max(0, Math.min(suggScroll, total - vis));
		int boxH = vis * 14 + 4;
		StyledButton.roundFill(ctx, sx, sy, sw, boxH, 0xFF101B2E);
		StyledButton.roundBorder(ctx, sx, sy, sw, boxH, C_ACC_DIM);
		int ry = sy + 2;
		for (int i = suggScroll; i < suggScroll + vis; i++) {
			String t = list.get(i);
			boolean hov = mx >= sx && mx <= sx + sw && my >= ry && my <= ry + 14;
			if (hov) ctx.fill(sx + 1, ry, sx + sw - 1, ry + 14, 0x334DA3FF);
			ItemIcons.draw(ctx, t, sx + 4, ry + 1, 0.75f);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + trim(t, 22)), sx + 18, ry + 3, 0xFFFFFFFF);
			boolean fav = cfg.isFav(t);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(fav ? "§e★" : "§8☆"), sx + sw - 13, ry + 3, 0xFFFFFFFF);
			starBoxes.add(new Object[]{ sx + sw - 16, ry, sx + sw, ry + 14, t });
			suggestBoxes.add(new Object[]{ sx, ry, sx + sw - 16, ry + 14, t, action });
			ry += 14;
		}
		if (total > vis) ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8scroll ▲▼"), sx + sw - 46, sy - 9, 0xFFFFFFFF);
	}

	private void drawAlertsList(DrawContext ctx, int mx, int my) {
		if (!showAlerts) return;
		var cfg = com.lifestealmarket.MarketConfig.get();
		int sx = cl + 238, sy = py + 50, sw = 220;
		int total = cfg.alerts.size();
		if (total == 0) {
			StyledButton.roundFill(ctx, sx, sy, sw, 18, 0xFF101B2E);
			StyledButton.roundBorder(ctx, sx, sy, sw, 18, C_ACC_DIM);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8No alerts - open an item, tap the bell"), sx + 5, sy + 5, 0xFFFFFFFF);
			return;
		}
		int vis = Math.min(SUG_VISIBLE, total);
		suggScroll = Math.max(0, Math.min(suggScroll, total - vis));
		int boxH = vis * 14 + 4;
		StyledButton.roundFill(ctx, sx, sy, sw, boxH, 0xFF101B2E);
		StyledButton.roundBorder(ctx, sx, sy, sw, boxH, C_ACC_DIM);
		int ry = sy + 2;
		for (int i = suggScroll; i < suggScroll + vis; i++) {
			var a = cfg.alerts.get(i);
			boolean hov = mx >= sx && mx <= sx + sw && my >= ry && my <= ry + 14;
			if (hov) ctx.fill(sx + 1, ry, sx + sw - 1, ry + 14, 0x334DA3FF);
			String dir = a.below ? "§a▼" : "§c▲";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(dir), sx + 5, ry + 3, 0xFFFFFFFF);
			ItemIcons.draw(ctx, a.ticker, sx + 15, ry + 1, 0.7f);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + trim(a.ticker, 12) + " §7" + Coins.fmt(a.target)), sx + 28, ry + 3, 0xFFFFFFFF);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§c✕"), sx + sw - 13, ry + 3, 0xFFFFFFFF);
			alertRemoveBoxes.add(new int[]{ sx + sw - 16, ry, sx + sw, ry + 14, i });
			alertRowBoxes.add(new Object[]{ sx, ry, sx + sw - 16, ry + 14, a.ticker });
			ry += 14;
		}
		if (total > vis) ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8scroll ▲▼"), sx + sw - 46, sy - 9, 0xFFFFFFFF);
	}

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }
	private Click remap(Click c) { return new Click(toGuiX(c.x()), toGuiY(c.y()), c.buttonInfo()); }

	@Override
	public boolean mouseClicked(Click rawClick, boolean doubled) {

		if (Tutorial.active()) {
			double rx = rawClick.x(), ry = rawClick.y();
			if (Tutorial.hit(tutSkip, rx, ry)) { Tutorial.finish(); return true; }
			if (Tutorial.step == 5) { Tutorial.step = 6; if (this.client != null) this.client.setScreen(new NetworthScreen(this)); return true; }
			Tutorial.advance();
			return true;
		}
		if (NavBar.click(rawClick.x(), rawClick.y(), this)) return true;
		Click click = remap(rawClick);
		double cxp = click.x(), cyp = click.y();

		if (doubled && cxp >= plotL && cxp <= plotR && cyp >= plotTop && cyp <= plotBot) {
			zoomLo = 0; zoomHi = 1; return true;
		}

		if (panThumb != null && cxp >= panThumb[0] && cxp <= panThumb[2] && cyp >= panThumb[1] && cyp <= panThumb[3]) {
			draggingPan = true; panStartX = cxp; panStartLo = zoomLo; panStartHi = zoomHi; return true;
		}
		for (Object[] b : starBoxes) {
			if (cxp >= (int) b[0] && cxp <= (int) b[2] && cyp >= (int) b[1] && cyp <= (int) b[3]) {
				com.lifestealmarket.MarketConfig.get().toggleFav((String) b[4]); return true;
			}
		}
		for (Object[] b : colorBoxes) {
			if (cxp >= (int) b[0] && cxp <= (int) b[2] && cyp >= (int) b[1] && cyp <= (int) b[3]) {
				Line l = (Line) b[4]; int cur = -1;
				for (int k = 0; k < PALETTE.length; k++) if (PALETTE[k] == l.userColor) cur = k;
				l.userColor = PALETTE[(cur + 1) % PALETTE.length]; return true;
			}
		}
		for (Object[] b : suggestBoxes) {
			if (cxp >= (int) b[0] && cxp <= (int) b[2] && cyp >= (int) b[1] && cyp <= (int) b[3]) {
				String t = (String) b[4]; int action = (int) b[5];
				if (action == 0) { replaceWith(t); topSearch.setText(""); showFavs = false; }
				else { addTicker(t); addSearch.setText(""); showFavsAdd = false; }
				this.setFocused(null);
				return true;
			}
		}
		for (int[] b : removeBoxes) if (cxp >= b[0] && cxp <= b[2] && cyp >= b[1] && cyp <= b[3]) {
			int idx = b[4]; if (idx >= 0 && idx < lines.size()) lines.remove(idx); return true;
		}
		for (int[] b : alertRemoveBoxes) if (cxp >= b[0] && cxp <= b[2] && cyp >= b[1] && cyp <= b[3]) {
			int idx = b[4]; var cfg = com.lifestealmarket.MarketConfig.get();
			if (idx >= 0 && idx < cfg.alerts.size()) { cfg.alerts.remove(idx); com.lifestealmarket.MarketConfig.save();
				com.lifestealmarket.LifestealMarketClient.syncAlerts(); }
			return true;
		}
		for (Object[] b : alertRowBoxes) if (cxp >= (int) b[0] && cxp <= (int) b[2] && cyp >= (int) b[1] && cyp <= (int) b[3]) {
			showAlerts = false; replaceWith((String) b[4]); return true;
		}

		for (int[] b : bookRowBoxes) if (cxp >= b[0] && cxp <= b[2] && cyp >= b[1] && cyp <= b[3]) {
			openOnAh(); return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click rawClick, double dx, double dy) {
		if (draggingPan) {
			double cxp = toGuiX(rawClick.x());
			double width = panStartHi - panStartLo;
			double lo = panStartLo + (cxp - panStartX) / (double) (plotR - plotL);
			lo = Math.max(0, Math.min(lo, 1 - width));
			zoomLo = lo; zoomHi = lo + width;
			return true;
		}
		return super.mouseDragged(rawClick, dx, dy);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (draggingPan) { draggingPan = false; return true; }
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {

		if (showFavs || showAlerts || !suggestBoxes.isEmpty()) { suggScroll += (v < 0 ? 1 : -1); return true; }

		double gmx = toGuiX(mouseX), gmy = toGuiY(mouseY);
		if (gmx >= plotL && gmx <= plotR && gmy >= plotTop && gmy <= plotBot && !lines.isEmpty()) {
			double cursorFrac = (gmx - plotL) / (double) (plotR - plotL);
			double f = zoomLo + cursorFrac * (zoomHi - zoomLo);
			double width = zoomHi - zoomLo;
			double factor = v > 0 ? 0.82 : 1.0 / 0.82;
			double nw = Math.max(0.02, Math.min(1.0, width * factor));
			double nl = f - cursorFrac * nw;
			nl = Math.max(0, Math.min(1 - nw, nl));
			zoomLo = nl; zoomHi = nl + nw;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, h, v);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == 258) {
			TextFieldWidget f = (topSearch != null && topSearch.isFocused()) ? topSearch
					: (addSearch != null && addSearch.isFocused()) ? addSearch : null;
			if (f != null) {
				List<String> sug = suggestions(f.getText());
				if (!sug.isEmpty()) { f.setText(sug.get(0)); return true; }
			}
		}
		if (input.key() == 257 || input.key() == 335) {
			if (alertMode && alertField != null && alertField.isFocused()) { confirmAlert(); return true; }
			if (topSearch != null && topSearch.isFocused()) { String q = topSearch.getText().trim(); if (!q.isBlank()) { replaceWith(bestMatch(q)); topSearch.setText(""); } return true; }
			if (addSearch != null && addSearch.isFocused()) { String q = addSearch.getText().trim(); if (!q.isBlank()) { addTicker(bestMatch(q)); addSearch.setText(""); } return true; }
		}
		return super.keyPressed(input);
	}

	private List<int[]> curve(int[] xs, int[] ys) {
		List<int[]> out = new ArrayList<>(); int n = xs.length;
		if (n == 1) { out.add(new int[]{xs[0], ys[0]}); return out; }
		for (int i = 0; i < n - 1; i++) {
			int p0 = Math.max(0, i - 1), p1 = i, p2 = i + 1, p3 = Math.min(n - 1, i + 2);
			for (int s = 0; s < CURVE_STEPS; s++) {
				double t = s / (double) CURVE_STEPS, t2 = t * t, t3 = t2 * t;
				double x = 0.5 * ((2 * xs[p1]) + (-xs[p0] + xs[p2]) * t + (2 * xs[p0] - 5 * xs[p1] + 4 * xs[p2] - xs[p3]) * t2 + (-xs[p0] + 3 * xs[p1] - 3 * xs[p2] + xs[p3]) * t3);
				double y = 0.5 * ((2 * ys[p1]) + (-ys[p0] + ys[p2]) * t + (2 * ys[p0] - 5 * ys[p1] + 4 * ys[p2] - ys[p3]) * t2 + (-ys[p0] + 3 * ys[p1] - 3 * ys[p2] + ys[p3]) * t3);
				out.add(new int[]{(int) Math.round(x), (int) Math.round(y)});
			}
		}
		out.add(new int[]{xs[n - 1], ys[n - 1]});
		return out;
	}

	private void polyline(DrawContext ctx, int[] xs, int[] ys, int n, int col, int th) {
		for (int i = 1; i < n; i++) {
			int ax = xs[i-1], ay = ys[i-1], bx = xs[i], by = ys[i];
			if (bx <= ax) { int lo = Math.min(ay, by), hi = Math.max(ay, by); ctx.fill(ax, lo, ax + th, hi + th, col); continue; }
			for (int x = ax; x <= bx; x++) {
				int y0 = ay + (by - ay) * (x - ax) / (bx - ax);
				int y1 = ay + (by - ay) * (Math.min(bx, x + 1) - ax) / (bx - ax);
				int lo = Math.min(y0, y1), hi = Math.max(y0, y1);
				ctx.fill(x, lo, x + th, hi + th, col);
			}
		}
	}

	private void seg(DrawContext ctx, int x1, int y1, int x2, int y2, int color, int thick, int off) {
		int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), steps = Math.max(dx, dy);
		if (steps == 0) { ctx.fill(x1 + off, y1 + off, x1 + off + thick, y1 + off + thick, color); return; }
		for (int i = 0; i <= steps; i++) {
			int x = x1 + (x2 - x1) * i / steps + off, y = y1 + (y2 - y1) * i / steps + off;
			ctx.fill(x, y, x + thick, y + thick, color);
		}
	}

	private void frame(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
		ctx.fill(x1, y1, x2, y1 + 1, color); ctx.fill(x1, y2 - 1, x2, y2, color);
		ctx.fill(x1, y1, x1 + 1, y2, color); ctx.fill(x2 - 1, y1, x2, y2, color);
	}

	private String colorCode(int argb) {

		if (argb == C_UP) return "§a"; if (argb == C_DOWN) return "§c";
		int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		if (b > 180 && g > 180) return "§b"; if (r > 180 && b > 150) return "§d";
		if (g > 180) return "§a"; if (r > 180) return "§c"; if (b > 180) return "§9"; return "§f";
	}

	private static final net.minecraft.util.Identifier[] COIN_TEX = {
		net.minecraft.util.Identifier.of("lifestealmarket", "textures/coins/tier0.png"),
		net.minecraft.util.Identifier.of("lifestealmarket", "textures/coins/tier1.png"),
		net.minecraft.util.Identifier.of("lifestealmarket", "textures/coins/tier2.png"),
		net.minecraft.util.Identifier.of("lifestealmarket", "textures/coins/tier3.png"),
		net.minecraft.util.Identifier.of("lifestealmarket", "textures/coins/tier4.png"),
		net.minecraft.util.Identifier.of("lifestealmarket", "textures/coins/tier5.png"),
	};
	private static int coinTier(long v) {
		if (v < 100_000L) return 0;
		if (v < 500_000L) return 1;
		if (v < 1_000_000L) return 2;
		if (v < 4_000_000L) return 3;
		if (v < 8_000_000L) return 4;
		return 5;
	}

	private void drawCoin(DrawContext ctx, int x, int y, long value) {
		ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, COIN_TEX[coinTier(value)], x, y - 4, 0f, 0f, 15, 15, 32, 32, 32, 32);
	}

	private String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "..."; }

	private static float ease(long sinceMs, float durMs) {
		float p = Math.min(1f, Math.max(0f, sinceMs / durMs));
		return 1f - (float) Math.pow(1f - p, 3);
	}

	@Override public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { super.renderBackground(ctx, mouseX, mouseY, delta); }
	@Override public void close() { Ui.escBack(this.client, parent); }

	private static final String MODRINTH_URL = "https://modrinth.com/mod/lifesteal-market";

	private static final String AH_SEARCH_CMD = "ah %s";

	private static String ahQuery(String ticker) {
		if (ticker == null) return "";
		int cut = ticker.length();
		int dot = ticker.indexOf('·'); if (dot >= 0) cut = Math.min(cut, dot);
		int br = ticker.indexOf('['); if (br >= 0) cut = Math.min(cut, br);
		return ticker.substring(0, cut).replaceAll("(?i)§.", "").trim();
	}

	private void markSeenViewAh() {
		if (!MarketConfig.get().seenViewAh) { MarketConfig.get().seenViewAh = true; MarketConfig.save(); }
	}
	private void markSeenPartners() {
		if (!MarketConfig.get().seenPartners) { MarketConfig.get().seenPartners = true; MarketConfig.save(); }
	}

	private void openOnAh() {
		if (this.client == null || this.client.player == null || this.client.player.networkHandler == null || lines.isEmpty()) return;
		String item = ahQuery(lines.get(0).ticker);
		if (item.isEmpty()) return;
		String cmd = AH_SEARCH_CMD.contains("%s") ? AH_SEARCH_CMD.replace("%s", item) : AH_SEARCH_CMD + " " + item;
		if (cmd.startsWith("/")) cmd = cmd.substring(1);
		Venues.scannerHoldoff();
		this.client.player.networkHandler.sendChatCommand(cmd);

		if (this.client != null) this.client.setScreen(null);
	}

	private void openModrinth() {
		if (this.client == null) return;

		String u = com.lifestealmarket.LifestealMarketClient.updateUrl;
		final String url = (u != null && !u.isBlank()) ? u : MODRINTH_URL;
		this.client.setScreen(new net.minecraft.client.gui.screen.ConfirmLinkScreen(confirmed -> {
			if (confirmed) net.minecraft.util.Util.getOperatingSystem().open(url);
			this.client.setScreen(this);
		}, url, true));
	}

	private void openDiscord() {
		if (this.client == null) return;
		final String url = "https://discord.gg/E6aS3vuAd";
		this.client.setScreen(new net.minecraft.client.gui.screen.ConfirmLinkScreen(confirmed -> {
			if (confirmed) net.minecraft.util.Util.getOperatingSystem().open(url);
			this.client.setScreen(this);
		}, url, true));
	}

	private void openPatreon() {
		if (this.client == null) return;
		final String url = "https://www.patreon.com/LifestealMarket/posts/early-acces-162891223";
		this.client.setScreen(new net.minecraft.client.gui.screen.ConfirmLinkScreen(confirmed -> {
			if (confirmed) net.minecraft.util.Util.getOperatingSystem().open(url);
			this.client.setScreen(this);
		}, url, true));
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		refreshTimeFormatters();
		if (viewAhButton != null) viewAhButton.visible = !lines.isEmpty();

		int gmx = (int) Math.round(toGuiX(mouseX)), gmy = (int) Math.round(toGuiY(mouseY));

		float s = (float) uiScale;
		float cxp = this.width / 2f, cyp = this.height / 2f;
		var m = ctx.getMatrices();
		m.pushMatrix(); m.translate(cxp, cyp); m.scale(s, s); m.translate(-cxp, -cyp);
		try {
			super.render(ctx, gmx, gmy, delta);

			drawSuggestions(ctx, gmx, gmy);
			drawAlertsList(ctx, gmx, gmy);
			drawStatTooltip(ctx, gmx, gmy);
		} catch (Throwable overlayFail) {

		} finally {
			m.popMatrix();
		}
		NavBar.draw(ctx, this.textRenderer, "stock", this.width, this.height, px, py, pw, uiScale);

		tutSkip = null; tutAction = null;
		if (Tutorial.active()) {
			int n = RANGES.length, gap = 4, tw = 34, tx0 = cr - (n * tw + (n - 1) * gap);
			int[] rect; String title, desc;
			switch (Tutorial.step) {
				case 1 -> { rect = screenRect(cl, barY, 210, 18); title = "Search"; desc = "Type any item here to see its live price chart, high/low and order book."; }
				case 2 -> { rect = screenRect(tx0, barY, cr - tx0, 18); title = "Time range"; desc = "Switch the chart between 1, 3, 7 and 30 days of price history."; }
				case 3 -> { rect = screenRect(cl + 238, barY, 20, 18); title = "Price alert"; desc = "Open one item, then set a ping for when its price hits your target."; }
				case 4 -> { rect = screenRect(cr - 22, hdrY, 22, 16); title = "Settings"; desc = "Theme, GUI size, buyer/seller colours and alert options."; }
				default -> { rect = screenRect(cr - 104, hdrY, 78, 16); title = "Net Worth"; desc = "Click here to open your Net Worth - wealth, listings & the leaderboard."; }
			}
			int[][] r = Tutorial.drawSpotlight(ctx, this.textRenderer, this.width, this.height, rect, title, desc, false,
					g2sX(px + pw / 2), g2sY(py + ph));
			tutSkip = r[0]; tutAction = r[1];
		}
	}

	private int[] screenRect(int gx, int gy, int gw, int gh) {
		return new int[]{ g2sX(gx), g2sY(gy), g2sX(gx + gw), g2sY(gy + gh) };
	}

	private class Backdrop extends ClickableWidget {
		Backdrop() { super(0, 0, StockScreen.this.width, StockScreen.this.height, Text.literal("")); }
		@Override protected void renderWidget(DrawContext ctx, int mx, int my, float delta) { paint(ctx, mx, my); }
		@Override public void onClick(Click click, boolean doubled) { }
		@Override public boolean isMouseOver(double x, double y) { return false; }
		@Override protected void appendClickableNarrations(NarrationMessageBuilder b) { }
	}
}

