package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import com.lifestealmarket.NetworthData;
import com.lifestealmarket.NetworthTracker;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class NetworthScreen extends Screen {
	private static final int C_UP = 0xFF22E88A, C_DOWN = 0xFFFF556E, C_INK = 0xFFDCE7FF, C_MUTE = 0xFF7D92BF;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18, C_BOX = 0xFF15294a, C_GRID = 0x1839D0FF, C_TITLE = 0xFF0B2238;

	private static final Identifier[] COIN_TEX = {
		Identifier.of("lifestealmarket", "textures/coins/tier0.png"), Identifier.of("lifestealmarket", "textures/coins/tier1.png"),
		Identifier.of("lifestealmarket", "textures/coins/tier2.png"), Identifier.of("lifestealmarket", "textures/coins/tier3.png"),
		Identifier.of("lifestealmarket", "textures/coins/tier4.png"), Identifier.of("lifestealmarket", "textures/coins/tier5.png"),
	};
	private static final String[] RANGES = { "1H", "6H", "1D", "1W", "ALL" };
	private static final long[] RANGE_MS = { 3600_000L, 6*3600_000L, 86_400_000L, 7*86_400_000L, Long.MAX_VALUE };

	private final DateTimeFormatter F_DAY = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault());
	private DateTimeFormatter F_TIME, F_FULL;
	private boolean lastUse12h;
	private int[] refBox;
	private long refCopiedAt = 0L;
	private void refreshTimeFormatters() {
		boolean cur = MarketConfig.get().use12hTime;
		if (F_FULL != null && cur == lastUse12h) return;
		F_TIME = DateTimeFormatter.ofPattern(MarketConfig.timePattern()).withZone(ZoneId.systemDefault());
		F_FULL = DateTimeFormatter.ofPattern(MarketConfig.dateTimePattern()).withZone(ZoneId.systemDefault());
		lastUse12h = cur;
	}

	private final Screen parent;
	private int rangeIdx = 2;
	private final long openTime = System.currentTimeMillis();

	private int px, py, pw, ph, cl, cr, chL, chR, chTop, chBot, listTop, listBot;
	private double uiScale = 1.0, effScale = 1.0;
	private final long openedAt = System.currentTimeMillis();
	private float tipEase = -1f;
	private double[] curveXs = new double[0], curveYs = new double[0];
	private String curveKey = "";

	private double zoomLo = 0.0, zoomHi = 1.0;
	private boolean panning = false;
	private double panStartX, panStartLo, panStartHi;
	private int plotL, plotR, plotT, plotB;
	private int listScroll = 0;
	private int[] tutSkip, tutDone;
	private int g2sX(int gx) { return (int) Math.round(this.width / 2.0 + (gx - this.width / 2.0) * uiScale); }
	private int g2sY(int gy) { return (int) Math.round(this.height / 2.0 + (gy - this.height / 2.0) * uiScale); }

	public NetworthScreen(Screen parent) {
		super(Text.literal("Net Worth"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a;
		C_ACC_DIM = (a & 0x00FFFFFF) | 0x55000000;
		C_GRID = (a & 0x00FFFFFF) | 0x18000000;
		C_BOX = StyledButton.lerpColor(a, 0xFF0A1428, 0.55f);
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (a & 0xFFFFFF), 0.14f);
		C_TITLE = StyledButton.lerpColor(0xFF0B1A2E, 0xFF000000 | (a & 0xFFFFFF), 0.26f);

		pw = 560; ph = 346;
		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		cl = px + 14; cr = px + pw - 14;

		chL = cl; chR = cr; chTop = py + 92; chBot = py + 92 + 96;
		listTop = chBot + 46; listBot = py + ph - 12;

		boolean locked = this.client == null || !NetworthTracker.isHomeServer(this.client);

		int tw = 78, th = 18;
		addDrawableChild(new StyledButton(cr - tw, py + 4, tw, th, Text.literal("Market"),
				() -> { if (this.client != null) this.client.setScreen(new StockScreen(parent, "")); }).accent(C_ACCENT));
		if (locked) return;

		addDrawableChild(new StyledButton(cr - tw - 90, py + 4, 86, th, Text.literal("§e✦ Leaderboard"),
				() -> { if (this.client != null) this.client.setScreen(new LeaderboardScreen(this)); }).accent(C_ACCENT));

		int rw = 40, gap = 6, ry = chBot + 10, rx = chL;
		for (int i = 0; i < RANGES.length; i++) {
			final int idx = i;
			addDrawableChild(new StyledButton(rx, ry, rw, 18, Text.literal(RANGES[i]), i == rangeIdx,

					() -> { rangeIdx = idx; zoomLo = 0; zoomHi = 1; clearAndInit(); }).accent(C_ACCENT));
			rx += rw + gap;
		}
	}

	private List<NetworthData.Snap> rangeHistory() {
		List<NetworthData.Snap> h = NetworthTracker.INSTANCE.data().history;
		if (RANGE_MS[rangeIdx] == Long.MAX_VALUE) return h;
		long cut = System.currentTimeMillis() - RANGE_MS[rangeIdx];
		List<NetworthData.Snap> out = new ArrayList<>();
		for (NetworthData.Snap s : h) if (s.t >= cut) out.add(s);
		return out;
	}

	@Override public void renderBackground(DrawContext ctx, int mx, int my, float d) { super.renderBackground(ctx, mx, my, d); }
	@Override public void close() { Ui.escBack(this.client, parent); }

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		refreshTimeFormatters();
		int gmx = (int) Math.round(toGuiX(mouseX)), gmy = (int) Math.round(toGuiY(mouseY));
		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;
		effScale = uiScale * Ui.openPop(openedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) effScale, (float) effScale); m.translate(-cxp, -cyp);
		renderInner(ctx, gmx, gmy, delta);
		m.popMatrix();
		NavBar.draw(ctx, this.textRenderer, "worth", this.width, this.height, px, py, pw, uiScale);

		tutSkip = null; tutDone = null;
		if (Tutorial.active() && Tutorial.step == 6) {
			int[] rect = { g2sX(cl), g2sY(py + 30), g2sX(cl + 170), g2sY(py + 66) };
			int[][] r = Tutorial.drawSpotlight(ctx, this.textRenderer, this.width, this.height, rect,
					"Net Worth", "Your cash, listings and ender chest total lives here. Tap Leaderboard to compare.", true,
					g2sX(px + pw / 2), g2sY(py + ph));
			tutSkip = r[0]; tutDone = r[1];
		}
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {

		StyledButton.roundFill(ctx, px, py, pw, ph, C_PANEL | 0xFF000000);
		ctx.fill(px + 1, py + 1, px + pw - 1, py + 26, 0xFF16181C);
		StyledButton.roundBorder(ctx, px, py, pw, ph, C_ACC_DIM | 0xFF000000);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§lNet Worth"), cl, py + 9, C_INK);

		com.lifestealmarket.MarketConfig.OwnedTheme wot =
				com.lifestealmarket.MarketConfig.ownedTheme(com.lifestealmarket.MarketConfig.get().theme);
		if (wot != null) ThemeBg.drawThumb(ctx,
				wot.id().toLowerCase(java.util.Locale.ROOT) + "_window_networth", px, py, pw, ph);

		if (this.client == null || !NetworthTracker.isHomeServer(this.client)) {
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§7Net Worth is only available on §flifesteal.net"),
					px + pw / 2, py + ph / 2 - 10, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§8Join the server to view and track your net worth."),
					px + pw / 2, py + ph / 2 + 4, 0xFFFFFFFF);
			super.render(ctx, mx, my, delta);
			return;
		}

		NetworthTracker t = NetworthTracker.INSTANCE;
		long coins = t.coins();
		long listVal = t.listingsValue();
		long net = t.netWorth();

		String big = Coins.fmt(Ui.counter("networth.big", net));
		var m = ctx.getMatrices();
		m.pushMatrix(); m.translate(cl, py + 34); m.scale(2.0f, 2.0f);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + big), 0, 0, 0xFFFFFFFF);
		m.popMatrix();
		int bigW = this.textRenderer.getWidth(big) * 2;
		drawCoin(ctx, cl + bigW + 6, py + 38, net);

		List<NetworthData.Snap> rh = rangeHistory();
		if (rh.size() >= 2) {
			long start = rh.get(0).value, dv = net - start;
			double pctv = start == 0 ? 0 : 100.0 * dv / start;
			String col = dv > 0 ? "§a" : dv < 0 ? "§c" : "§7";
			String sign = dv > 0 ? "+" : "";
			ctx.drawTextWithShadow(this.textRenderer,
					Text.literal(col + sign + Coins.fmt(Math.abs(dv)) + " (" + sign + String.format(Locale.ROOT, "%.1f", pctv) + "%)"),
					cl, py + 56, 0xFFFFFFFF);
		} else {
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8building history..."), cl, py + 56, 0xFFFFFFFF);
		}

		int sx = cr - 150, sy = py + 34;
		long ecv = t.enderchestValue();

		long ordv = t.orderEscrowValue() + t.orderClaimsValue();
		statChip(ctx, sx, sy, "§7Cash", Coins.fmt(Math.max(0, coins)), Math.max(0, coins));
		statChip(ctx, sx, sy + 12, "§7Listings", Coins.fmt(listVal), listVal);
		statChip(ctx, sx, sy + 24, "§7Enderchest", t.data().enderchestSeenAt == 0 ? "§8-" : Coins.fmt(ecv), ecv);
		statChip(ctx, sx, sy + 36, "§7Orders", t.data().myOrdersSeenAt == 0 ? "§8-" : Coins.fmt(ordv), ordv);

		drawChart(ctx, rh, mx, my);

		drawHoldings(ctx, mx, my, t);

		String sync;
		if (t.lastSyncAt == 0) sync = "§8auto-sync: waiting for first sync...";
		else {
			long agoS = (System.currentTimeMillis() - t.lastSyncAt) / 1000;
			String how = t.lastSyncHeadless ? "§aauto" : "§emanual";
			sync = "§8last sync: §7" + agoS + "s ago §8(" + how + "§8, " + t.lastSyncCount + " entries)";
		}
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(sync), cl, py + ph - 10, 0xFFFFFFFF);
		drawReferral(ctx, mx, my);

		super.render(ctx, mx, my, delta);
	}

	private void drawReferral(DrawContext ctx, int gmx, int gmy) {
		int refs = Math.max(0, MarketConfig.get().lastReferrals);
		String tag = com.lifestealmarket.LifestealMarketClient.refTagNow;
		int next = com.lifestealmarket.LifestealMarketClient.refNextAt;

		String hint;
		if (System.currentTimeMillis() - refCopiedAt < 1800L) hint = "§acopied - send it to a friend";
		else if (tag != null && !tag.isBlank()) hint = "§8wearing " + tag.trim();
		else if (next > refs) hint = "§8" + (next - refs) + " more to earn a tag";
		else hint = "§8click to invite";
		String line = "§7✦ Invites: §e" + refs + " §8· " + hint;
		int w = this.textRenderer.getWidth(line.replaceAll("§.", ""));
		int x = cr - w, y = py + ph - 10;
		boolean hot = gmx >= x - 2 && gmx <= cr + 2 && gmy >= y - 2 && gmy <= y + 10;
		if (hot) ctx.fill(x - 3, y - 2, cr + 3, y + 9, 0x22FFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(line), x, y, 0xFFFFFFFF);
		refBox = new int[]{ x - 3, y - 2, cr + 3, y + 9 };
	}

	private void statChip(DrawContext ctx, int x, int y, String label, String value, long coinVal) {
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(label), x, y, C_INK);
		String v = value.startsWith("§") ? value : "§f" + value;
		int w = this.textRenderer.getWidth(v.replaceAll("§.", ""));
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(v), cr - w - 16, y, 0xFFFFFFFF);
		drawCoin(ctx, cr - 15, y, coinVal);
	}

	private void drawChart(DrawContext ctx, List<NetworthData.Snap> rh, int mx, int my) {
		ctx.fill(chL, chTop, chR, chBot, 0xFF16181C);
		StyledButton.roundBorder(ctx, chL, chTop - 1, chR - chL + 1, chBot - chTop + 2, C_ACC_DIM | 0x88000000);
		int pl = chL + 48, pr = chR - 8, pt = chTop + 8, pb = chBot - 18;
		plotL = pl; plotR = pr; plotT = pt; plotB = pb;

		if (rh.size() < 2) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8Not enough history yet - check back soon"),
					(chL + chR) / 2, (chTop + chBot) / 2 - 4, 0xFFFFFFFF);
			return;
		}

		long fullT0 = rh.get(0).t, fullT1 = rh.get(rh.size() - 1).t; if (fullT1 <= fullT0) fullT1 = fullT0 + 1;
		long fullSpan = fullT1 - fullT0;
		long t0 = fullT0 + (long) (fullSpan * zoomLo), t1 = fullT0 + (long) (fullSpan * zoomHi);
		if (t1 <= t0) t1 = t0 + 1;
		long vmin = 0, vmax = Long.MIN_VALUE;

		vmax = Math.max(valueAt(rh, t0), valueAt(rh, t1));
		for (NetworthData.Snap s : rh) if (s.t >= t0 && s.t <= t1) vmax = Math.max(vmax, s.value);
		if (vmax <= 0) vmax = 1;
		vmax += vmax / 12;

		int gridCol = 0x1EFFFFFF;
		for (int i = 0; i <= 3; i++) {
			int gy = pt + (pb - pt) * i / 3;
			ctx.fill(pl, gy, pr, gy + 1, gridCol);
			long val = vmax - (vmax - vmin) * i / 3;
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7" + Coins.fmt(val)), chL + 3, gy - 3, 0xFFFFFFFF);
		}

		DateTimeFormatter xf = (t1 - t0) <= 40L * 3600_000L ? F_TIME : F_DAY;
		int ly = pb + 4;
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + xf.format(Instant.ofEpochMilli(t0))), pl, ly, 0xFFFFFFFF);
		String endL = xf.format(Instant.ofEpochMilli(t1));
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + endL), pr - this.textRenderer.getWidth(endL), ly, 0xFFFFFFFF);

		if (zoomHi - zoomLo < 0.999) {
			String z = "§7" + Math.round((zoomHi - zoomLo) * 100) + "% §8· right-click to reset";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(z),
					(pl + pr) / 2 - this.textRenderer.getWidth(Ui.strip(z)) / 2, ly, 0xFFFFFFFF);
			int bx1 = pl + (int) ((pr - pl) * zoomLo), bx2 = pl + (int) ((pr - pl) * zoomHi);
			ctx.fill(pl, pt - 3, pr, pt - 2, 0x22FFFFFF);
			ctx.fill(bx1, pt - 4, Math.max(bx1 + 2, bx2), pt - 1, C_ACCENT);
		}

		String key = rangeIdx + "|" + rh.size() + "|" + rh.get(rh.size() - 1).t + "|" + rh.get(0).t
				+ "|" + pl + "," + pr + "," + pt + "," + pb + "|" + vmax
				+ "|" + Math.round(zoomLo * 1e6) + "," + Math.round(zoomHi * 1e6);
		if (!key.equals(curveKey)) {
			curveKey = key;
			int M = Math.max(80, Math.min(240, rh.size() * 2));
			double[] pv = new double[M];
			for (int i = 0; i < M; i++) pv[i] = valueAt(rh, t0 + (t1 - t0) * i / (M - 1));
			int win = 1;
			double[] sxsB = new double[M], sysB = new double[M];
			for (int i = 0; i < M; i++) {
				double s = 0; int c = 0;
				for (int j = -win; j <= win; j++) { int k = i + j; if (k >= 0 && k < M) { s += pv[k]; c++; } }
				double v = s / c;
				sxsB[i] = pl + (pr - pl) * (double) i / (M - 1);
				sysB[i] = pb - (v - vmin) / (double) (vmax - vmin) * (pb - pt);
			}
			double[][] built = smoothCurve(sxsB, sysB, M);
			curveXs = built[0]; curveYs = built[1];
		}
		double[] xs = curveXs, ys = curveYs;
		int nn = xs.length;

		int lc = MarketConfig.lineColorValue(MarketConfig.get().lineColor);
		int col = lc != 0 ? lc : C_ACCENT;

		double prog = Ui.easeOut((System.currentTimeMillis() - openedAt - 120) / 450.0);
		int xLimit = pl + (int) ((pr - pl) * prog);

		double[] cxs = xs, cys = ys;
		float cxp = this.width / 2f, cyp = this.height / 2f;
		var mm = ctx.getMatrices();
		mm.pushMatrix();
		mm.translate(cxp, cyp); mm.scale((float) (1 / effScale), (float) (1 / effScale)); mm.translate(-cxp, -cyp);
		double sLimit = cxp + (xLimit - cxp) * effScale;
		int prevSx = Integer.MIN_VALUE, prevSy = 0;
		for (int i = 0; i < cxs.length; i++) {
			double gy = Math.max(pt, Math.min(pb, cys[i]));
			int sx = (int) Math.round(cxp + (cxs[i] - cxp) * effScale);
			int sy = (int) Math.round(cyp + (gy - cyp) * effScale);
			if (sx > sLimit) break;
			if (prevSx != Integer.MIN_VALUE) {
				int ax = prevSx, ay = prevSy, bx = sx, by = sy;
				int th = Math.max(2, (int) Math.round(effScale));
				if (bx <= ax) {
					int lo = Math.min(ay, by), hi = Math.max(ay, by);
					ctx.fill(ax, lo, ax + th, hi + th, col);
				} else for (int x = ax; x <= bx; x++) {
					int y0 = ay + (by - ay) * (x - ax) / (bx - ax);
					int y1 = ay + (by - ay) * (Math.min(bx, x + 1) - ax) / (bx - ax);
					int lo = Math.min(y0, y1), hi = Math.max(y0, y1);
					ctx.fill(x, lo, x + th, hi + th, col);

					ctx.fill(x, lo - 1, x + th, lo, (col & 0xFFFFFF) | 0x55000000);
					ctx.fill(x, hi + th, x + th, hi + th + 1, (col & 0xFFFFFF) | 0x55000000);
				}
			}
			prevSx = sx; prevSy = sy;
		}
		mm.popMatrix();

		if (prog >= 1) {
			int ex = (int) Math.round(xs[nn-1]), ey = (int) Math.round(ys[nn-1]);
			float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 420.0));
			int ga = (int) (0x2A + 0x3C * pulse);
			ctx.fill(ex - 4, ey - 4, ex + 5, ey + 5, (ga << 24) | (col & 0xFFFFFF));
			ctx.fill(ex - 2, ey - 2, ex + 3, ey + 3, 0xFF000000 | (col & 0xFFFFFF));
			ctx.fill(ex - 1, ey - 1, ex + 2, ey + 2, 0xFFFFFFFF);
		}

		if (mx >= pl && mx <= pr && my >= pt && my <= pb) {
			long tc = t0 + (long) ((mx - pl) / (double) (pr - pl) * (t1 - t0));

			double curveY = cys[cys.length - 1];
			for (int i = 1; i < cxs.length; i++) {
				if (cxs[i] >= mx) {
					double span = cxs[i] - cxs[i-1];
					double f = span <= 0 ? 0 : (mx - cxs[i-1]) / span;
					curveY = cys[i-1] + (cys[i] - cys[i-1]) * f;
					break;
				}
			}
			curveY = Math.max(pt, Math.min(pb, curveY));
			int cy = (int) Math.round(curveY);
			long val = Math.round(vmin + (pb - curveY) / (double) (pb - pt) * (vmax - vmin));
			ctx.fill(mx, pt, mx + 1, pb, 0x33FFFFFF);
			ctx.fill(mx - 2, cy - 2, mx + 3, cy + 3, 0xFF000000 | (col & 0xFFFFFF));
			ctx.fill(mx - 1, cy - 1, mx + 2, cy + 2, 0xFFFFFFFF);
			String vLabel = "§f§l" + Coins.fmt(val), tLabel = "§7" + F_FULL.format(Instant.ofEpochMilli(tc));
			int tw = Math.max(this.textRenderer.getWidth(vLabel.replaceAll("§.", "")) + 17,
					this.textRenderer.getWidth(tLabel.replaceAll("§.", ""))) + 12;

			int wantTx = Math.min(mx + 10, chR - tw - 2);
			if (tipEase < 0) tipEase = wantTx;
			tipEase += (wantTx - tipEase) * 0.30f;
			int tx = Math.round(tipEase), ty = pt + 2;
			StyledButton.roundFill(ctx, tx, ty, tw, 26, 0xFF0B1420);
			StyledButton.roundBorder(ctx, tx, ty, tw, 26, C_ACC_DIM | 0xFF000000);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(vLabel), tx + 6, ty + 5, 0xFFFFFFFF);
			drawCoin(ctx, tx + 6 + this.textRenderer.getWidth(vLabel.replaceAll("§.", "")) + 2, ty + 5, val);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(tLabel), tx + 6, ty + 15, 0xFFFFFFFF);
		}
	}

	private static double[][] smoothCurve(double[] xs, double[] ys, int n) {
		if (n < 3) return new double[][]{ java.util.Arrays.copyOf(xs, n), java.util.Arrays.copyOf(ys, n) };
		final int STEPS = 14;
		java.util.List<double[]> pts = new ArrayList<>();
		for (int i = 0; i < n - 1; i++) {
			int p0 = Math.max(0, i - 1), p1 = i, p2 = i + 1, p3 = Math.min(n - 1, i + 2);
			for (int s = 0; s < STEPS; s++) {
				double t = s / (double) STEPS, t2 = t * t, t3 = t2 * t;
				double x = 0.5 * ((2 * xs[p1]) + (-xs[p0] + xs[p2]) * t
						+ (2 * xs[p0] - 5 * xs[p1] + 4 * xs[p2] - xs[p3]) * t2
						+ (-xs[p0] + 3 * xs[p1] - 3 * xs[p2] + xs[p3]) * t3);
				double y = 0.5 * ((2 * ys[p1]) + (-ys[p0] + ys[p2]) * t
						+ (2 * ys[p0] - 5 * ys[p1] + 4 * ys[p2] - ys[p3]) * t2
						+ (-ys[p0] + 3 * ys[p1] - 3 * ys[p2] + ys[p3]) * t3);
				pts.add(new double[]{ x, y });
			}
		}
		pts.add(new double[]{ xs[n - 1], ys[n - 1] });
		double[] ox = new double[pts.size()], oy = new double[pts.size()];
		for (int i = 0; i < pts.size(); i++) { ox[i] = pts.get(i)[0]; oy[i] = pts.get(i)[1]; }
		return new double[][]{ ox, oy };
	}

	private long valueAt(List<NetworthData.Snap> rh, long t) {
		if (rh.isEmpty()) return 0;
		int n = rh.size();
		if (t <= rh.get(0).t) return rh.get(0).value;
		if (t >= rh.get(n - 1).t) return rh.get(n - 1).value;

		int lo = 1, hi = n - 1;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (rh.get(mid).t >= t) hi = mid; else lo = mid + 1;
		}
		long t0 = rh.get(lo - 1).t, t1 = rh.get(lo).t;
		double f = t1 == t0 ? 0 : (t - t0) / (double) (t1 - t0);
		return Math.round(rh.get(lo - 1).value + (rh.get(lo).value - rh.get(lo - 1).value) * f);
	}

	private final List<Object[]> holdRows = new ArrayList<>();
	private final List<Object[]> rmBoxes = new ArrayList<>();

	private void drawHoldings(DrawContext ctx, int mx, int my, NetworthTracker t) {
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7Your Listings"), cl, listTop - 12, C_MUTE);

		List<NetworthData.Listing> ls = new ArrayList<>(t.data().listings);
		List<NetworthData.Listing> priced = new ArrayList<>(), unpriced = new ArrayList<>();
		for (NetworthData.Listing l : ls) { if (t.unitPrice(l.item) > 0) priced.add(l); else unpriced.add(l); }
		priced.sort(Comparator.comparingLong((NetworthData.Listing l) -> -t.unitPrice(l.item) * l.qty));
		List<NetworthData.Listing> order = new ArrayList<>(priced); order.addAll(unpriced);

		int rowH = 13, visible = Math.max(1, (listBot - listTop) / rowH);
		int maxScroll = Math.max(0, order.size() - visible);
		if (listScroll > maxScroll) listScroll = maxScroll;

		if (order.isEmpty()) {
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8No active listings detected yet. List an item on AH to begin."),
					cl, listTop + 2, 0xFFFFFFFF);
			return;
		}

		rmBoxes.clear();

		int nameLeft = cl + 24, widest = 0;
		for (int i = listScroll; i < order.size(); i++) {
			NetworthData.Listing l = order.get(i);
			widest = Math.max(widest, this.textRenderer.getWidth(
					(l.qty > 1 ? l.qty + "x " : "") + l.item));
			if (listTop + (i - listScroll + 1) * rowH > listBot) break;
		}
		int priceCol = nameLeft + Math.min(widest, (int) ((cr - nameLeft) * 0.55)) + 14;
		int y = listTop;
		for (int i = listScroll; i < order.size() && y + rowH <= listBot + 2; i++) {
			NetworthData.Listing l = order.get(i);
			long unit = t.unitPrice(l.item);

			if (((i - listScroll) & 1) == 0) ctx.fill(cl, y - 1, cr, y + rowH - 2, 0x18000000);

			boolean overX = mx >= cl && mx <= cl + 9 && my >= y - 1 && my <= y + rowH - 2;
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(overX ? "§c§l✕" : "§8✕"), cl + 1, y + 1, 0xFFFFFFFF);
			rmBoxes.add(new Object[]{ cl, y - 1, cl + 10, y + rowH - 2, l });
			ItemIcons.draw(ctx, l.item, cl + 11, y, 0.7f);

			int nameX = nameLeft, priceX = priceCol;
			String qty = l.qty > 1 ? "§7" + l.qty + "x " : "";
			int qtyW = this.textRenderer.getWidth(Ui.strip(qty));
			ctx.drawTextWithShadow(this.textRenderer,
					Text.literal(qty + "§f" + Ui.fit(this.textRenderer, l.item, priceX - nameX - qtyW - 8)),
					nameX, y + 1, 0xFFFFFFFF);
			if (unit > 0) {
				long total = unit * l.qty;
				String totS = "§f" + Coins.fmt(total);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(totS), priceX, y + 1, 0xFFFFFFFF);
				int x = priceX + this.textRenderer.getWidth(Ui.strip(totS));
				drawCoin(ctx, x + 3, y, total);
				if (l.qty > 1)
					ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8@" + Coins.fmt(unit)),
							x + 20, y + 1, 0xFFFFFFFF);
			} else {
				ctx.drawTextWithShadow(this.textRenderer,
						Text.literal("§8" + Ui.fit(this.textRenderer, "no price yet", cr - priceX - 2)),
						priceX, y + 1, 0xFFFFFFFF);
			}
			y += rowH;
		}

		if (maxScroll > 0) {
			String s = "§8" + (listScroll + 1) + "-" + Math.min(order.size(), listScroll + visible) + " / " + order.size() + "  ↕scroll";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), cr - this.textRenderer.getWidth(s.replaceAll("§.", "")) - 2, listTop - 12, 0xFFFFFFFF);
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {

		if (Tutorial.active() && Tutorial.step == 6) {
			if (Tutorial.hit(tutSkip, click.x(), click.y()) || Tutorial.hit(tutDone, click.x(), click.y())) Tutorial.finish();
			return true;
		}

		if (NavBar.click(click.x(), click.y(), this)) return true;
		click = new Click(toGuiX(click.x()), toGuiY(click.y()), click.buttonInfo());
		double cxp = click.x(), cyp = click.y();

		if (cxp >= plotL && cxp <= plotR && cyp >= plotT && cyp <= plotB && plotR > plotL) {
			if (click.button() == 1) { zoomLo = 0; zoomHi = 1; StyledButton.playClick(); return true; }
			if (click.button() == 0 && (zoomHi - zoomLo) < 0.999) {
				panning = true; panStartX = cxp; panStartLo = zoomLo; panStartHi = zoomHi;
				return true;
			}
		}

		if (refBox != null && cxp >= refBox[0] && cxp <= refBox[2] && cyp >= refBox[1] && cyp <= refBox[3]
				&& this.client != null && this.client.getSession() != null) {
			this.client.keyboard.setClipboard("/referral " + this.client.getSession().getUsername());
			refCopiedAt = System.currentTimeMillis();
			return true;
		}
		for (Object[] b : rmBoxes) {
			if (cxp >= (int) b[0] && cxp <= (int) b[2] && cyp >= (int) b[1] && cyp <= (int) b[3]) {
				NetworthTracker.INSTANCE.data().listings.remove((NetworthData.Listing) b[4]);
				NetworthData.save();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		double gmx = toGuiX(mx), gmy = toGuiY(my);
		if (gmy >= listTop - 4 && gmy <= listBot) {
			listScroll = Math.max(0, listScroll - (int) Math.signum(v));
			return true;
		}

		if (gmx >= plotL && gmx <= plotR && gmy >= plotT && gmy <= plotB && plotR > plotL) {
			double cursor = (gmx - plotL) / (double) (plotR - plotL);
			double at = zoomLo + cursor * (zoomHi - zoomLo);
			double width = zoomHi - zoomLo;
			double nw = Math.max(0.02, Math.min(1.0, width * (v > 0 ? 0.82 : 1 / 0.82)));
			double lo = Math.max(0, Math.min(1 - nw, at - cursor * nw));
			zoomLo = lo; zoomHi = lo + nw;
			return true;
		}
		return super.mouseScrolled(mx, my, h, v);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.gui.Click click, double dx, double dy) {
		if (panning && plotR > plotL) {
			double width = panStartHi - panStartLo;
			double lo = panStartLo - (toGuiX(click.x()) - panStartX) / (double) (plotR - plotL) * width;
			lo = Math.max(0, Math.min(1 - width, lo));
			zoomLo = lo; zoomHi = lo + width;
			return true;
		}
		return super.mouseDragged(click, dx, dy);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.gui.Click click) {
		if (panning) { panning = false; return true; }
		return super.mouseReleased(click);
	}

	private void drawCoin(DrawContext ctx, int x, int y, long value) {
		ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, COIN_TEX[coinTier(value)], x, y - 4, 0f, 0f, 15, 15, 32, 32, 32, 32);
	}
	private static int coinTier(long v) {
		if (v < 100_000L) return 0; if (v < 500_000L) return 1; if (v < 1_000_000L) return 2;
		if (v < 4_000_000L) return 3; if (v < 8_000_000L) return 4; return 5;
	}
	private String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "..."; }
}

