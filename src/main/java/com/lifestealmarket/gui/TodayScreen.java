package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TodayScreen extends Screen {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private record Row(String ticker, double chg, long price, int vol) {}

	private final Screen parent;
	private int C_ACCENT = 0xFF39D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph, cl, cr, listTop, listBot;
	private double uiScale = 1.0, effScale = 1.0;
	private final long openedAt = System.currentTimeMillis();
	private final Ui.Scroll scroll = new Ui.Scroll();

	private volatile List<Row> gainers = new ArrayList<>(), losers = new ArrayList<>(), traded = new ArrayList<>();
	private volatile String status = "Loading...";
	private long revealAt = 0L;
	private int lastOffDraw = 0;

	private final List<int[]> hits = new ArrayList<>();
	private final List<String> hitTickers = new ArrayList<>();

	private static final int ROW_H = 17, HEAD_H = 15, GAP = 7;
	private static final int SHOWN = 5;

	public TodayScreen(Screen parent) {
		super(Text.literal("Today"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a;
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (a & 0xFFFFFF), 0.14f);
		pw = 420; ph = 320;
		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		cl = px + 14; cr = px + pw - 14;
		listTop = py + 46; listBot = py + ph - 26;

		addDrawableChild(new StyledButton(cr - 76, py + 7, 76, 16, Ui.label("Back", C_ACCENT),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));
		fetch();
	}

	private void fetch() {
		String url = MarketConfig.apiBase() + "/api/movers";
		var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true")
				.header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId()).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (this.client == null) return;
			this.client.execute(() -> {
				try {
					if (err != null || resp.statusCode() != 200) { status = "Couldn't reach the server."; return; }
					var o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
					gainers = credible(parse(o, "gainers"));
					losers = credible(parse(o, "losers"));
					traded = parse(o, "traded");

					status = (gainers.isEmpty() && losers.isEmpty() && traded.isEmpty())
							? "Nothing traded enough in the last 24 hours." : "";
				} catch (Exception ex) { status = "Bad response."; }
			});
		});
	}

	private static List<Row> parse(com.google.gson.JsonObject o, String key) {
		List<Row> out = new ArrayList<>();
		if (!o.has(key)) return out;
		for (var e : o.getAsJsonArray(key)) {
			var r = e.getAsJsonObject();
			out.add(new Row(r.get("ticker").getAsString(), r.get("chg").getAsDouble(),
					r.get("price").getAsLong(), r.get("vol").getAsInt()));
		}
		return out;
	}

	private static final int MIN_VOL = 20;
	private static final long MIN_PRICE = 1000;

	private static List<Row> credible(List<Row> rows) {
		List<Row> out = new ArrayList<>();
		for (Row r : rows) if (r.vol() >= MIN_VOL && r.price() >= MIN_PRICE) out.add(r);

		return out.size() >= 2 ? out : rows.subList(0, Math.min(3, rows.size()));
	}

	@Override public void renderBackground(DrawContext ctx, int mx, int my, float d) { super.renderBackground(ctx, mx, my, d); }
	@Override public void close() { Ui.escBack(this.client, parent); }

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public void render(DrawContext ctx, int mx, int my, float delta) {

		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;
		effScale = uiScale * Ui.openPop(openedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) effScale, (float) effScale); m.translate(-cxp, -cyp);
		int gmx = (int) Math.round(toGuiX(mx)), gmy = (int) Math.round(toGuiY(my));
		try {
			renderInner(ctx, gmx, gmy);
			super.render(ctx, gmx, gmy, delta);
		} finally { m.popMatrix(); }
		NavBar.draw(ctx, this.textRenderer, "today", this.width, this.height, px, py, pw, uiScale);
	}

	private void renderInner(DrawContext ctx, int mx, int my) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ Today", "§7the last 24 hours");
		hits.clear(); hitTickers.clear();

		if (!status.isEmpty()) {
			if (status.startsWith("Loading")) {
				for (int i = 0; i < 6; i++) Ui.shimmerCard(ctx, cl, listTop + i * 20, cr - cl, 15);
			} else {
				ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7" + status),
						px + pw / 2, py + ph / 2 - 8, 0xFFFFFFFF);
			}
			return;
		}
		if (revealAt == 0L) revealAt = System.currentTimeMillis();

		int contentH = sectionH(gainers) + sectionH(losers) + sectionH(traded);
		int viewH = listBot - listTop;
		scroll.clamp(Math.max(0, contentH - viewH));
		int off = scroll.tick();
		lastOffDraw = off;

		ctx.enableScissor(cl - 4, listTop, cr + 4, listBot);
		try {
			int y = listTop - off;
			y = section(ctx, y, "§a▲ BIGGEST GAINERS", gainers, mx, my, true);
			y = section(ctx, y, "§c▼ BIGGEST DROPS", losers, mx, my, true);
			section(ctx, y, "§b◆ MOST TRADED", traded, mx, my, false);
		} finally { ctx.disableScissor(); }

		if (contentH > viewH) {
			ctx.fill(cr + 6, listTop, cr + 8, listBot, 0x28000000);
			int thumbH = Math.max(16, viewH * viewH / contentH);
			int thumbY = listTop + (int) Math.round((viewH - thumbH) * (off / (double) (contentH - viewH)));
			ctx.fill(cr + 6, thumbY, cr + 8, thumbY + thumbH, 0xCC000000 | (C_ACCENT & 0xFFFFFF));
		}
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("§8click any item to open its chart"), px + pw / 2, py + ph - 17, 0xFFFFFFFF);
	}

	private int sectionH(List<Row> rows) {
		return rows.isEmpty() ? 0 : HEAD_H + Math.min(SHOWN, rows.size()) * ROW_H + GAP;
	}

	private int section(DrawContext ctx, int y, String title, List<Row> rows, int mx, int my, boolean showChange) {
		if (rows.isEmpty()) return y;
		Ui.sectionHead(ctx, this.textRenderer, cl, y, cr, title, null, C_ACCENT);
		y += HEAD_H;
		int n = Math.min(SHOWN, rows.size());
		for (int i = 0; i < n; i++) {
			Row r = rows.get(i);

			int alpha = Ui.stagger(revealAt, i);
			if (alpha <= 4) { y += ROW_H; continue; }

			boolean hov = mx >= cl && mx <= cr && my >= y && my < y + ROW_H - 2 && my >= listTop && my < listBot;
			if (hov) StyledButton.roundFill(ctx, cl - 2, y - 1, cr - cl + 4, ROW_H - 1, 0x22FFFFFF);

			final int colPriceR = cr - 148, colChgR = cr - 74, colVolR = cr - 2;
			ctx.drawTextWithShadow(this.textRenderer, Ui.fitText(this.textRenderer,
					Text.literal(r.ticker()), colPriceR - (cl + 2) - 8), cl + 2, y + 3, 0xFFFFFFFF);

			String price = Coins.fmt(r.price());
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7" + price),
					colPriceR - this.textRenderer.getWidth(price), y + 3, 0xFFFFFFFF);

			if (showChange) {

				double c = r.chg();
				String chg = (c >= 0 ? "+" : "") + (Math.abs(c) >= 100
						? String.format(Locale.ROOT, "%.0f", c) : String.format(Locale.ROOT, "%.1f", c)) + "%";
				ctx.drawTextWithShadow(this.textRenderer, Text.literal((c >= 0 ? "§a" : "§c") + chg),
						colChgR - this.textRenderer.getWidth(chg), y + 3, 0xFFFFFFFF);
			}
			String vol = r.vol() + (r.vol() == 1 ? " sale" : " sales");
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + vol),
					colVolR - this.textRenderer.getWidth(vol), y + 3, 0xFFFFFFFF);

			if (my >= listTop && my < listBot) {
				hits.add(new int[]{ cl - 2, y - 1, cr + 2, y + ROW_H - 2 });
				hitTickers.add(r.ticker());
			}
			y += ROW_H;
		}
		return y + GAP;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		if (NavBar.click(rc.x(), rc.y(), this)) return true;
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());
		if (gy >= listTop && gy <= listBot) {
			for (int i = 0; i < hits.size(); i++) {
				int[] b = hits.get(i);
				if (gx >= b[0] && gx <= b[2] && gy >= b[1] && gy <= b[3]) {
					StyledButton.playClick();
					if (this.client != null) this.client.setScreen(new StockScreen(this, hitTickers.get(i)));
					return true;
				}
			}
		}
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
		int contentH = sectionH(gainers) + sectionH(losers) + sectionH(traded);
		scroll.by(-vAmount * 26, Math.max(0, contentH - (listBot - listTop)));
		return true;
	}
}

