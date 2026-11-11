package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class MarketChartScreen extends Screen {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final String[] RANGES = { "1d", "7d", "30d", "all" };
	private static final String[] RANGE_LABELS = { "1D", "7D", "30D", "All" };
	private static final int C_UP = 0xFF22E88A, C_DOWN = 0xFFFF556E;

	private final Screen parent;
	private final String ticker;
	private String range = "7d";

	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph, cl, cr, boxL, boxR, boxTop, boxBot, plotL, plotR, plotTop, plotBot;
	private double uiScale = 1.0, effScale = 1.0;
	private final long openedAt = System.currentTimeMillis();
	private long nextRefresh = 0L;

	private volatile long[][] points = new long[0][];
	private volatile long ask = 0, supply = -1, orders = -1, high = 0, low = 0, avg = 0, updated = 0;
	private volatile double chg = 0;
	private volatile String status = "Loading…";

	public MarketChartScreen(Screen parent, String ticker) {
		super(Text.literal("Market · " + ticker));
		this.parent = parent;
		this.ticker = ticker;
	}

	@Override
	protected void init() {
		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a; C_ACC_DIM = (a & 0x00FFFFFF) | 0x55000000;
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (a & 0xFFFFFF), 0.14f);
		pw = 420; ph = 292;
		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		cl = px + 14; cr = px + pw - 14;
		boxL = cl; boxR = cr; boxTop = py + 79; boxBot = py + ph - 46;
		plotL = boxL + 44; plotR = boxR - 8; plotTop = boxTop + 10; plotBot = boxBot - 14;

		addDrawableChild(new StyledButton(cr - 52, py + 7, 52, 16, Ui.label("Back", C_ACCENT),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));

		addDrawableChild(new StyledButton(cr - 52 - 88, py + 7, 84, 16, Text.literal("§fOpen /market"),
				this::openMarket).accent(C_ACCENT)
				.tooltip("§b§lThe server's own shop", "§7Runs /market and closes this screen"));

		int bw = 40;
		for (int i = 0; i < RANGES.length; i++) {
			final String r = RANGES[i];
			addDrawableChild(new StyledButton(cl + i * (bw + 4), py + ph - 40, bw, 16,
					Text.literal((r.equals(range) ? "§l" : "§7") + RANGE_LABELS[i]), r.equals(range),

					() -> { if (!r.equals(range)) { range = r; status = "Loading…"; points = new long[0][]; clearAndInit(); } })
					.accent(C_ACCENT));
		}
		fetch();
	}

	private void openMarket() {
		if (this.client == null || this.client.player == null) return;
		Venues.scannerHoldoff();
		OrdersNav.cancel();
		this.client.player.networkHandler.sendChatCommand("market");
		this.client.setScreen(null);
	}

	private void fetch() {
		String url = MarketConfig.apiBase() + "/api/market/series?ticker="
				+ URLEncoder.encode(ticker, StandardCharsets.UTF_8) + "&range=" + range;
		var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", MarketConfig.API_KEY)
				.header("X-User", MarketConfig.userName()).header("X-Token", MarketConfig.token()).GET().build();
		nextRefresh = System.currentTimeMillis() + 30_000L;
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (this.client == null) return;
			if (err != null || resp.statusCode() != 200) {
				this.client.execute(() -> status = "Couldn't load — is the market up?");
				return;
			}
			try {
				var o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
				var arr = o.getAsJsonArray("points");
				long[][] p = new long[arr.size()][2];
				for (int i = 0; i < arr.size(); i++) {
					var pair = arr.get(i).getAsJsonArray();
					p[i][0] = pair.get(0).getAsLong();
					p[i][1] = pair.get(1).getAsLong();
				}
				this.client.execute(() -> {
					points = p;
					ask = o.get("ask").getAsLong();
					supply = o.has("supply") ? o.get("supply").getAsLong() : -1;
					orders = o.has("orders") ? o.get("orders").getAsLong() : -1;
					high = o.get("high").getAsLong(); low = o.get("low").getAsLong(); avg = o.get("avg").getAsLong();
					updated = o.has("updated") ? o.get("updated").getAsLong() : 0;
					chg = o.get("chg").getAsDouble();
					status = p.length == 0
							? (ask > 0 ? "Only today's price so far — the line starts filling from now."
							           : "No /market price recorded for this item yet.")
							: "";
				});
			} catch (Exception e) { this.client.execute(() -> status = "Bad data."); }
		});
	}

	@Override
	public void tick() {
		if (System.currentTimeMillis() >= nextRefresh) fetch();
		super.tick();
	}

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public void close() { Ui.escBack(this.client, parent); }

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		if (NavBar.click(rc.x(), rc.y(), this)) return true;
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		int mx = (int) Math.round(toGuiX(mouseX)), my = (int) Math.round(toGuiY(mouseY));
		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;
		effScale = uiScale * Ui.openPop(openedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) effScale, (float) effScale); m.translate(-cxp, -cyp);
		try { renderInner(ctx, mx, my, delta); } finally { m.popMatrix(); }
		NavBar.draw(ctx, this.textRenderer, "market", this.width, this.height, px, py, pw, uiScale);
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ Market price", "§7server shop");

		ItemIcons.draw(ctx, ticker, cl, py + 37, 1.0f);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + Ui.fit(this.textRenderer, ticker, 210)),
				cl + 20, py + 41, 0xFFFFFFFF);
		String price = ask > 0 ? Coins.fmt(ask) : "—";
		var mm = ctx.getMatrices();
		int pw2 = (int) (this.textRenderer.getWidth(price) * 1.3f);
		mm.pushMatrix(); mm.translate(cr - pw2, py + 36f); mm.scale(1.3f, 1.3f);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§e" + price), 0, 0, 0xFFFFFFFF);
		mm.popMatrix();
		String chgS = (chg >= 0 ? "▲ +" : "▼ ") + String.format(java.util.Locale.ROOT, "%.2f", chg) + "%";
		int chgCol = chg >= 0 ? C_UP : C_DOWN;
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(chgS),
				cr - this.textRenderer.getWidth(chgS), py + 53, 0xFF000000 | (chgCol & 0xFFFFFF));

		String strip = "§8on sale §f" + (supply < 0 ? "—" : String.valueOf(supply))
				+ (orders > 0 ? " §8in §f" + orders + " §8orders" : "")
				+ "   §8high §f" + (high > 0 ? Coins.fmt(high) : "—")
				+ "   §8low §f" + (low > 0 ? Coins.fmt(low) : "—")
				+ "   §8avg §f" + (avg > 0 ? Coins.fmt(avg) : "—");
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(strip), cl, py + 65, 0xFFFFFFFF);

		StyledButton.roundFill(ctx, boxL, boxTop, boxR - boxL, boxBot - boxTop, 0x66000000);
		StyledButton.roundBorder(ctx, boxL, boxTop, boxR - boxL, boxBot - boxTop, C_ACC_DIM);
		long[][] p = points;
		if (p.length < 2) {
			String s = status.isEmpty() ? "Not enough history yet — one point so far." : status;
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8" + s),
					(boxL + boxR) / 2, (boxTop + boxBot) / 2 - 4, 0xFFFFFFFF);
		} else {
			drawChart(ctx, p, mx, my);
		}

		String when = updated > 0 ? ago(System.currentTimeMillis() - updated) : "—";
		String foot = (p.length >= 2 && !status.isEmpty()) ? "§c" + status
				: "§8what /market charges · updated " + when;
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(foot), cl, py + ph - 14, 0xFFFFFFFF);
		super.render(ctx, mx, my, delta);
	}

	private void drawChart(DrawContext ctx, long[][] p, int mx, int my) {
		long tMin = p[0][0], tMax = p[p.length - 1][0];
		if (tMax <= tMin) tMax = tMin + 1;
		long vMin = Long.MAX_VALUE, vMax = 0;
		for (long[] pt : p) { vMin = Math.min(vMin, pt[1]); vMax = Math.max(vMax, pt[1]); }
		if (vMax == vMin) { vMin = Math.max(0, (long) (vMin * 0.95)); vMax = (long) (vMax * 1.05) + 1; }
		double span = Math.max(1, vMax - vMin), pad = span * 0.12;
		double lo = Math.max(0, vMin - pad), hi = vMax + pad;
		if (hi <= lo) hi = lo + 1;

		for (int i = 0; i <= 3; i++) {
			int y = plotBot - i * (plotBot - plotTop) / 3;
			ctx.fill(plotL, y, plotR, y + 1, 0x1EFFFFFF);
			long v = (long) (lo + (hi - lo) * i / 3.0);
			String lbl = "§7" + Coins.fmt(v);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(lbl),
					plotL - 6 - this.textRenderer.getWidth(Ui.strip(lbl)), y - 4, 0xFFFFFFFF);
		}

		int n = p.length;
		int M = Math.max(80, Math.min(240, n * 2));
		double[] gx = new double[M], gy = new double[M];
		double[] raw = new double[M];
		for (int i = 0; i < M; i++) raw[i] = askAt(p, tMin + (tMax - tMin) * i / (M - 1));
		for (int i = 0; i < M; i++) {
			double s = 0; int c = 0;
			for (int j = -1; j <= 1; j++) { int k = i + j; if (k >= 0 && k < M) { s += raw[k]; c++; } }
			gx[i] = plotL + (plotR - plotL) * (double) i / (M - 1);
			gy[i] = yAt(Math.round(s / c), lo, hi);
		}
		int[][] sp = spline(gx, gy, M);
		int[] xs = sp[0], ys = sp[1];

		int lc = MarketConfig.lineColorValue(MarketConfig.get().lineColor);
		int line = lc != 0 ? lc : (chg >= 0 ? C_UP : C_DOWN);
		polyline(ctx, xs, ys, xs.length, 0xFF000000 | (line & 0xFFFFFF), 2);

		if (mx >= plotL && mx <= plotR && my >= plotTop - 6 && my <= plotBot + 6) {
			int hx = mx;
			long tc = tMin + (long) ((mx - plotL) / (double) (plotR - plotL) * (tMax - tMin));
			int hy = ys[ys.length - 1];
			for (int i = 1; i < xs.length; i++) {
				if (xs[i] >= mx) {
					double segW = xs[i] - xs[i - 1];
					double f = segW <= 0 ? 0 : (mx - xs[i - 1]) / segW;
					hy = (int) Math.round(ys[i - 1] + (ys[i] - ys[i - 1]) * f);
					break;
				}
			}
			hy = Math.max(plotTop, Math.min(plotBot, hy));
			ctx.fill(hx, plotTop, hx + 1, plotBot, 0x44FFFFFF);
			ctx.fill(hx - 2, hy - 2, hx + 3, hy + 3, 0xFF000000 | (line & 0xFFFFFF));
			String t1 = Coins.fmt(askAt(p, tc));
			String t2 = fmtTime(tc);
			int w = Math.max(this.textRenderer.getWidth(t1), this.textRenderer.getWidth(t2)) + 10;
			int cxp = Math.min(hx + 8, plotR - w), cyp = Math.max(plotTop, hy - 24);
			ctx.fill(cxp, cyp, cxp + w, cyp + 22, 0xE6000000);
			StyledButton.roundBorder(ctx, cxp, cyp, w, 22, C_ACC_DIM);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§e" + t1), cxp + 5, cyp + 3, 0xFFFFFFFF);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + t2), cxp + 5, cyp + 12, 0xFFFFFFFF);
		}

		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + fmtTime(tMin)), plotL, plotBot + 3, 0xFFFFFFFF);
		String last = "§8" + fmtTime(tMax);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(last),
				plotR - this.textRenderer.getWidth(Ui.strip(last)), plotBot + 3, 0xFFFFFFFF);
	}

	private static long askAt(long[][] p, long t) {
		if (t <= p[0][0]) return p[0][1];
		int lo = 0, hi = p.length - 1;
		if (t >= p[hi][0]) return p[hi][1];
		while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (p[mid][0] <= t) lo = mid; else hi = mid - 1; }
		return p[lo][1];
	}

	private static int[][] spline(double[] xs, double[] ys, int n) {
		final int STEPS = 14;
		int cap = Math.max(2, (n - 1) * STEPS + 1);
		int[] ox = new int[cap], oy = new int[cap];
		int k = 0;
		for (int i = 0; i < n - 1; i++) {
			int p0 = Math.max(0, i - 1), p1 = i, p2 = i + 1, p3 = Math.min(n - 1, i + 2);
			for (int s = 0; s < STEPS && k < cap; s++) {
				double t = s / (double) STEPS, t2 = t * t, t3 = t2 * t;
				ox[k] = (int) Math.round(0.5 * ((2 * xs[p1]) + (-xs[p0] + xs[p2]) * t
						+ (2 * xs[p0] - 5 * xs[p1] + 4 * xs[p2] - xs[p3]) * t2
						+ (-xs[p0] + 3 * xs[p1] - 3 * xs[p2] + xs[p3]) * t3));
				oy[k] = (int) Math.round(0.5 * ((2 * ys[p1]) + (-ys[p0] + ys[p2]) * t
						+ (2 * ys[p0] - 5 * ys[p1] + 4 * ys[p2] - ys[p3]) * t2
						+ (-ys[p0] + 3 * ys[p1] - 3 * ys[p2] + ys[p3]) * t3));
				k++;
			}
		}
		if (k < cap) { ox[k] = (int) Math.round(xs[n - 1]); oy[k] = (int) Math.round(ys[n - 1]); k++; }
		return new int[][]{ java.util.Arrays.copyOf(ox, k), java.util.Arrays.copyOf(oy, k) };
	}

	private int xAt(long t, long tMin, long tMax) {
		return plotL + (int) Math.round((plotR - plotL) * (t - tMin) / (double) (tMax - tMin));
	}

	private int yAt(long v, double lo, double hi) {
		return plotBot - (int) Math.round((plotBot - plotTop) * (v - lo) / (hi - lo));
	}

	private void polyline(DrawContext ctx, int[] xs, int[] ys, int n, int col, int th) {
		for (int i = 1; i < n; i++) {
			int ax = xs[i - 1], ay = ys[i - 1], bx = xs[i], by = ys[i];
			if (bx <= ax) { int a = Math.min(ay, by), b = Math.max(ay, by); ctx.fill(ax, a, ax + th, b + th, col); continue; }
			for (int x = ax; x <= bx; x++) {
				int y0 = ay + (by - ay) * (x - ax) / (bx - ax);
				int y1 = ay + (by - ay) * (Math.min(bx, x + 1) - ax) / (bx - ax);
				ctx.fill(x, Math.min(y0, y1), x + th, Math.max(y0, y1) + th, col);
			}
		}
	}

	private String fmtTime(long ts) {
		boolean longRange = !range.equals("1d");
		DateTimeFormatter f = DateTimeFormatter
				.ofPattern(longRange ? MarketConfig.dateTimePattern() : MarketConfig.timePattern())
				.withZone(ZoneId.systemDefault());
		return f.format(java.time.Instant.ofEpochMilli(ts));
	}

	private static String ago(long ms) {
		long s = Math.max(0, ms / 1000);
		if (s < 90) return s + "s ago";
		if (s < 5400) return (s / 60) + "m ago";
		if (s < 86400) return (s / 3600) + "h ago";
		return (s / 86400) + "d ago";
	}
}

