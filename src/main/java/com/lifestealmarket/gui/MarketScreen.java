package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import com.auctionsniper.MarketApi;
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

public class MarketScreen extends Screen {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private final Screen parent;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph, cl, cr, listTop, listBot, rowH = 16;
	private double uiScale = 1.0, effScale = 1.0;
	private final Ui.Scroll scroll = new Ui.Scroll();
	private final Ui.HoverFade hoverFade = new Ui.HoverFade();
	private final long openedAt = System.currentTimeMillis();
	private long revealAt = 0L;
	private int hover = -1, lastOff = 0;
	private volatile List<String> tickers = new ArrayList<>();
	private volatile List<long[]> vals = new ArrayList<>();
	private volatile String status = "Loading market…";

	public MarketScreen(Screen parent) {
		super(Text.literal("Market"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a; C_ACC_DIM = (a & 0x00FFFFFF) | 0x55000000;
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (a & 0xFFFFFF), 0.14f);
		pw = 420; ph = 320;
		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		cl = px + 14; cr = px + pw - 14;
		listTop = py + 56; listBot = py + ph - 30;

		listBot = listTop + Math.max(1, (listBot - listTop) / rowH) * rowH;

		addDrawableChild(new StyledButton(cr - 76, py + 7, 76, 16, Ui.label("Back", C_ACCENT),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));
		fetch();
	}

	private void fetch() {
		if (!MarketApi.configured()) {
			status = "Cloud API key missing — configure auction-sniper.properties";
			return;
		}
		status = "Loading shared market prices…";
		MarketApi.sync(() -> {
			if (this.client == null) return;
			this.client.execute(() -> {
				var snapshot = MarketApi.snapshot();
				List<String> t = new ArrayList<>();
				List<long[]> v = new ArrayList<>();
				for (var entry : snapshot.entrySet()) {
					t.add(entry.getKey());
					var price = entry.getValue();
					v.add(new long[]{ price.average(), price.samples(), 0 });
				}
				java.util.List<Integer> order = new java.util.ArrayList<>();
				for (int i = 0; i < t.size(); i++) order.add(i);
				order.sort((x, y) -> t.get(x).compareToIgnoreCase(t.get(y)));
				List<String> sortedT = new ArrayList<>();
				List<long[]> sortedV = new ArrayList<>();
				for (int i : order) { sortedT.add(t.get(i)); sortedV.add(v.get(i)); }
				tickers = sortedT;
				vals = sortedV;
				status = sortedT.isEmpty()
						? (MarketApi.lastError().isBlank() ? "No shared prices yet — scan auctions to collect prices." : "Backend unavailable: " + MarketApi.lastError())
					: "";
			});
		});
	}
	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public void close() { Ui.escBack(this.client, parent); }

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		if (NavBar.click(rc.x(), rc.y(), this)) return true;
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());
		if (gx >= cl && gx <= cr && gy >= listTop && gy <= listBot && !tickers.isEmpty()) {
			int idx = (int) ((gy - listTop + lastOff) / rowH);
			if (idx >= 0 && idx < tickers.size() && this.client != null) {

				this.client.setScreen(new MarketChartScreen(this, tickers.get(idx)));
				return true;
			}
		}
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		double gmx = toGuiX(mx), gmy = toGuiY(my);
		if (gmx >= px && gmx <= px + pw && gmy >= py && gmy <= py + ph) {
			scroll.byRows(-(int) Math.signum(v) * 2, rowH, tickers.size(), listBot - listTop);
			return true;
		}
		return super.mouseScrolled(mx, my, h, v);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == 264) { scroll.byRows(2, rowH, tickers.size(), listBot - listTop); return true; }
		if (input.key() == 265) { scroll.byRows(-2, rowH, tickers.size(), listBot - listTop); return true; }
		return super.keyPressed(input);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		int mx = (int) Math.round(toGuiX(mouseX)), my = (int) Math.round(toGuiY(mouseY));
		hover = (mx >= cl && mx <= cr && my >= listTop && my <= listBot) ? (my - listTop + lastOff) / rowH : -1;
		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;
		effScale = uiScale * Ui.openPop(openedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) effScale, (float) effScale); m.translate(-cxp, -cyp);
		try { renderInner(ctx, mx, my, delta); } finally { m.popMatrix(); }
		NavBar.draw(ctx, this.textRenderer, "market", this.width, this.height, px, py, pw, uiScale);
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ Market", "§7season goods");
		if (revealAt == 0L && !tickers.isEmpty()) revealAt = System.currentTimeMillis();

		if (!status.isEmpty()) {
			if (status.startsWith("Loading")) {
				for (int i = 0; i < 5; i++) Ui.shimmerCard(ctx, cl, listTop + i * 20, cr - cl, 15);
			} else {
				ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8" + status), px + pw / 2, py + ph / 2, 0xFFFFFFFF);
			}
			super.render(ctx, mx, my, delta);
			return;
		}

		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Item"), cl + 2, py + 36, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8On sale"), cl + 210, py + 36, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Price"), cr - this.textRenderer.getWidth("Price"), py + 36, 0xFFFFFFFF);

		int viewH = listBot - listTop;
		lastOff = scroll.tickRows(rowH, tickers.size(), viewH);
		int drawBot = listTop + Math.max(1, viewH / rowH) * rowH;
		ctx.fill(cl - 2, listTop - 3, cr + 2, listTop - 2, 0x1AFFFFFF);

		ctx.enableScissor(cl - 3, listTop, cr + 3, drawBot);
		try {
			int first = Math.max(0, lastOff / rowH);
			for (int i = first; i < tickers.size(); i++) {
				int y = listTop + i * rowH - lastOff;
				if (y >= drawBot) break;
				long[] v = vals.get(i);
				float hf = hoverFade.tick(i, i == hover);
				int a = Ui.stagger(revealAt, i - first);

				float vis = y < listTop ? Math.max(0f, 1f - (listTop - y) / (float) rowH)
						: (y + rowH > drawBot ? Math.max(0f, (drawBot - y) / (float) rowH) : 1f);
				a = Math.min(a, (int) (vis * 255));
				if (a <= 8) continue;
				int col = (a << 24) | 0xFFFFFF;
				if (hf > 0.02f) StyledButton.roundFill(ctx, cl - 2, y, cr - cl + 4, rowH - 1,
						(C_ACCENT & 0xFFFFFF) | ((int) (hf * 0x33) << 24));
				else if ((i & 1) == 0) ctx.fill(cl, y, cr, y + rowH - 2, 0x14000000);
				ItemIcons.draw(ctx, tickers.get(i), cl, y + 1, 0.75f);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal((hf > 0.5f ? "§e" : "§f") + trim(tickers.get(i), 24)), cl + 15, y + 3, col);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7" + v[1] + (v[2] > 0 ? " §8/ " + v[2] + "ord" : "")), cl + 210, y + 3, col);
				String price = "§e" + Coins.fmt(v[0]);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(price), cr - Ui.widthNoCodes(this.textRenderer, price), y + 3, col);
			}
		} finally { ctx.disableScissor(); }
		Ui.scrollEdges(ctx, cl - 3, listTop, cr + 3, drawBot, C_PANEL);

		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + tickers.size() + " items · click one for its price history"), cl, py + ph - 12, 0xFFFFFFFF);
		int maxScroll = Math.max(0, tickers.size() * rowH - viewH);
		if (maxScroll > 0) {
			int firstVis = lastOff / rowH + 1, lastVis = Math.min(tickers.size(), (lastOff + viewH) / rowH);
			String s = "§8" + firstVis + "-" + lastVis + " / " + tickers.size() + " ↕";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), cr - Ui.widthNoCodes(this.textRenderer, s), py + ph - 12, 0xFFFFFFFF);
		}
		super.render(ctx, mx, my, delta);
	}

	private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

}

