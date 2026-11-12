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
import java.util.Locale;

public class ModUsersScreen extends Screen {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final Screen parent;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph, cl, cr, listTop, listBot;
	private double uiScale = 1.0, effScale = 1.0;
	private final Ui.Scroll scroll = new Ui.Scroll();
	private final Ui.HoverFade hoverFade = new Ui.HoverFade();
	private final long openedAt = System.currentTimeMillis();
	private long revealAt = 0L;
	private int lastOff = 0;
	private volatile List<Object[]> rows = new ArrayList<>();
	private volatile int total = 0, activeNow = 0;
	private volatile String status = "Loading...";
	private String me = "";

	public ModUsersScreen(Screen parent) { super(Text.literal("Mod Users")); this.parent = parent; }

	@Override
	protected void init() {
		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a; C_ACC_DIM = (a & 0x00FFFFFF) | 0x55000000;
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (a & 0xFFFFFF), 0.14f);
		pw = 380; ph = 320;
		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		cl = px + 14; cr = px + pw - 14;
		listTop = py + 58; listBot = py + ph - 16;

		listBot = listTop + Math.max(1, (listBot - listTop) / 13) * 13;
		if (this.client != null) me = this.client.getSession().getUsername().toLowerCase(Locale.ROOT);
		addDrawableChild(new StyledButton(cr - 76, py + 7, 76, 16, Ui.label("Back", C_ACCENT),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));
		fetch();
	}

	private void fetch() {
		String url = MarketConfig.apiBase() + "/api/modusers";
		var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId()).header("X-User", MarketConfig.userName()).header("X-Token", MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (this.client == null) return;
			this.client.execute(() -> {
				try {
					if (err != null || resp.statusCode() != 200) { status = "Couldn't reach the server."; return; }
					var o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
					total = o.get("total").getAsInt(); activeNow = o.get("activeNow").getAsInt();
					var arr = o.getAsJsonArray("users");
					List<Object[]> r = new ArrayList<>();
					for (var e : arr) { var u = e.getAsJsonObject();
						r.add(new Object[]{ u.get("name").getAsString(), u.get("active").getAsBoolean(), u.get("lastSeen").getAsLong() }); }
					rows = r; status = r.isEmpty() ? "No mod users reported yet." : "";
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
		return super.mouseClicked(new net.minecraft.client.gui.Click(toGuiX(rc.x()), toGuiY(rc.y()), rc.buttonInfo()), doubled);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		double gmx = toGuiX(mx), gmy = toGuiY(my);
		if (gmx >= px && gmx <= px + pw && gmy >= py && gmy <= py + ph) {
			scroll.byRows(-(int) Math.signum(v) * 2, 13, rows.size(), listBot - listTop);
			return true;
		}
		return super.mouseScrolled(mx, my, h, v);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == 264) { scroll.byRows(2, 13, rows.size(), listBot - listTop); return true; }
		if (input.key() == 265) { scroll.byRows(-2, 13, rows.size(), listBot - listTop); return true; }
		return super.keyPressed(input);
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
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ Mod Users", "§7owner roster");
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§a● §f" + activeNow + " §7active now   §8-   §f" + total + " §7total installed"),
				cl, py + 36, 0xFFFFFFFF);
		if (revealAt == 0L && !rows.isEmpty()) revealAt = System.currentTimeMillis();

		if (!status.isEmpty()) {
			if (status.startsWith("Loading")) {
				for (int i = 0; i < 5; i++) Ui.shimmerCard(ctx, cl, listTop + i * 17, cr - cl, 13);
			} else {
				ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8" + status), px + pw / 2, py + ph / 2, 0xFFFFFFFF);
			}
			super.render(ctx, mx, my, delta);
			return;
		}

		int rowH = 13, viewH = listBot - listTop;
		lastOff = scroll.tickRows(rowH, rows.size(), viewH);
		int drawBot = listTop + Math.max(1, viewH / rowH) * rowH;
		ctx.fill(cl - 2, listTop - 3, cr + 2, listTop - 2, 0x1AFFFFFF);
		long now = System.currentTimeMillis();

		ctx.enableScissor(cl - 3, listTop, cr + 3, drawBot);
		try {
			int first = Math.max(0, lastOff / rowH);
			for (int i = first; i < rows.size(); i++) {
				int y = listTop + i * rowH - lastOff;
				if (y >= drawBot) break;
				Object[] r = rows.get(i);
				String name = (String) r[0]; boolean active = (boolean) r[1]; long last = (long) r[2];
				boolean mine = name.toLowerCase(Locale.ROOT).equals(me);
				boolean hov = mx >= cl && mx <= cr && my >= y - 1 && my < y + rowH - 1;
				float hf = hoverFade.tick(i, hov);
				int a = Ui.stagger(revealAt, i - first);

				float vis = y < listTop ? Math.max(0f, 1f - (listTop - y) / (float) rowH)
						: (y + rowH > drawBot ? Math.max(0f, (drawBot - y) / (float) rowH) : 1f);
				a = Math.min(a, (int) (vis * 255));
				if (a <= 8) continue;
				int col = (a << 24) | 0xFFFFFF;
				if (mine) StyledButton.roundFill(ctx, cl - 2, y - 1, cr - cl + 4, rowH, (C_ACCENT & 0xFFFFFF) | 0x33000000);
				else if (hf > 0.02f) StyledButton.roundFill(ctx, cl - 2, y - 1, cr - cl + 4, rowH,
						(C_ACCENT & 0xFFFFFF) | ((int) (hf * 0x26) << 24));
				else if ((i & 1) == 0) ctx.fill(cl, y - 1, cr, y + rowH - 2, 0x14000000);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(active ? "§a●" : "§8●"), cl + 2, y + 1, col);
				PlayerHeads.draw(ctx, name, cl + 13, y, 10);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal((mine ? "§f§l" : "§f") + trim(name, 18)), cl + 27, y + 1, col);
				String when = active ? "§aonline" : "§8" + ago(now - last);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(when), cr - this.textRenderer.getWidth(when.replaceAll("§.", "")), y + 1, col);
			}
		} finally { ctx.disableScissor(); }
		Ui.scrollEdges(ctx, cl - 3, listTop, cr + 3, drawBot, C_PANEL);
		int maxScroll = Math.max(0, rows.size() * rowH - viewH);
		if (maxScroll > 0) {
			int firstVis = lastOff / rowH + 1, lastVis = Math.min(rows.size(), (lastOff + viewH) / rowH);
			String s = "§8" + firstVis + "-" + lastVis + " / " + rows.size() + " ↕";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), cr - this.textRenderer.getWidth(s.replaceAll("§.", "")), py + ph - 11, 0xFFFFFFFF);
		}
		super.render(ctx, mx, my, delta);
	}

	private static String ago(long ms) {
		long s = ms / 1000;
		if (s < 90) return Math.max(0, s) + "s ago";
		if (s < 3600) return Math.max(1, s / 60) + "m ago";
		if (s < 86400) return s / 3600 + "h ago";
		return s / 86400 + "d ago";
	}
	private String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "..."; }
}

