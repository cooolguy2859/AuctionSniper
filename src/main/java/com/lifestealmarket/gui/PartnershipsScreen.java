package com.lifestealmarket.gui;

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

public class PartnershipsScreen extends Screen {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final int ROW_H = 24;

	private final Screen parent;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph, cl, cr, listTop, listBot;
	private double uiScale = 1.0;
	private final long lsmOpenedAt = System.currentTimeMillis();
	private int scroll = 0;
	private volatile List<String[]> rows = new ArrayList<>();
	private volatile String status = "Loading...";

	public PartnershipsScreen(Screen parent) {
		super(Text.literal("Partnerships"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a; C_ACC_DIM = (a & 0x00FFFFFF) | 0x55000000;
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (a & 0xFFFFFF), 0.14f);
		pw = 420; ph = 300;
		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		cl = px + 14; cr = px + pw - 14;
		listTop = py + 40; listBot = py + ph - 16;

		addDrawableChild(new StyledButton(cr - 76, py + 6, 76, 16, Text.literal("§bBack"),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));
		fetch();
	}

	private void fetch() {
		String url = MarketConfig.apiBase() + "/api/partners";
		var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId()).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (this.client == null) return;
			this.client.execute(() -> {
				try {
					if (err != null || resp.statusCode() != 200) { status = "Couldn't reach the server."; return; }
					var arr = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonArray();
					List<String[]> r = new ArrayList<>();
					for (var e : arr) {
						var o = e.getAsJsonObject();
						r.add(new String[]{ o.get("name").getAsString(), o.get("url").getAsString(),
								o.has("desc") && !o.get("desc").isJsonNull() ? o.get("desc").getAsString() : "" });
					}
					rows = r; status = r.isEmpty() ? "No partners yet." : "";
				} catch (Exception ex) { status = "Bad response."; }
			});
		});
	}

	@Override public void renderBackground(DrawContext ctx, int mx, int my, float d) { super.renderBackground(ctx, mx, my, d); }
	@Override public void close() { Ui.escBack(this.client, parent); }

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());

		if (status.isEmpty() && gx >= cl && gx <= cr && gy >= listTop && gy <= listBot) {

			int drawn = Math.max(0, (listBot - listTop) / ROW_H);
			int idx = scroll + (int) ((gy - listTop) / ROW_H);
			if (idx >= 0 && idx < rows.size() && idx - scroll < drawn) { openInvite(rows.get(idx)[1]); return true; }
		}
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		double gmy = toGuiY(my);
		if (gmy >= listTop && gmy <= listBot) { scroll = Math.max(0, scroll - (int) Math.signum(v)); return true; }
		return super.mouseScrolled(mx, my, h, v);
	}

	private void openInvite(String url) {
		if (this.client == null || url == null || url.isEmpty()) return;
		this.client.setScreen(new net.minecraft.client.gui.screen.ConfirmLinkScreen(confirmed -> {
			if (confirmed) net.minecraft.util.Util.getOperatingSystem().open(url);
			this.client.setScreen(this);
		}, url, true));
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		int mx = (int) Math.round(toGuiX(mouseX)), my = (int) Math.round(toGuiY(mouseY));
		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;
		double lsmEff = uiScale * Ui.openPop(lsmOpenedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) lsmEff, (float) lsmEff); m.translate(-cxp, -cyp);
		try { renderInner(ctx, mx, my, delta); } finally { m.popMatrix(); }
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {
		StyledButton.roundFill(ctx, px, py, pw, ph, C_PANEL);
		ctx.fill(px + 1, py + 1, px + pw - 1, py + 26, 0xFF16181C);
		StyledButton.roundBorder(ctx, px, py, pw, ph, C_ACC_DIM | 0xFF000000);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§l✦ Partnerships"), cl, py + 9, 0xFFFFFFFF);

		if (!status.isEmpty()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8" + status), px + pw / 2, py + ph / 2 - 4, 0xFFFFFFFF);
			super.render(ctx, mx, my, delta);
			return;
		}

		int visible = (listBot - listTop) / ROW_H;
		int maxScroll = Math.max(0, rows.size() - visible);
		if (scroll > maxScroll) scroll = maxScroll;

		int y = listTop;
		for (int i = scroll; i < rows.size() && y + ROW_H <= listBot; i++) {
			String[] r = rows.get(i);
			boolean hover = mx >= cl && mx <= cr && my >= y && my < y + ROW_H;
			StyledButton.roundFill(ctx, cl - 2, y, cr - cl + 4, ROW_H - 3, hover ? ((C_ACCENT & 0xFFFFFF) | 0x33000000) : 0x22000000);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f§l" + trim(r[0], 30)), cl + 6, y + 3, 0xFFFFFFFF);
			if (!r[2].isEmpty())
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7" + trim(r[2], 42)), cl + 6, y + 13, 0xFFFFFFFF);
			String join = "§b§lJoin ↗";
			int jw = this.textRenderer.getWidth(join.replaceAll("§.", ""));
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(join), cr - jw - 6, y + 7, 0xFFFFFFFF);
			y += ROW_H;
		}

		if (maxScroll > 0) {
			String s = "§8" + (scroll + 1) + "-" + Math.min(rows.size(), scroll + visible) + " / " + rows.size() + " ↕";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), cr - this.textRenderer.getWidth(s.replaceAll("§.", "")), py + ph - 12, 0xFFFFFFFF);
		}
		super.render(ctx, mx, my, delta);
	}

	private String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "..."; }
}

