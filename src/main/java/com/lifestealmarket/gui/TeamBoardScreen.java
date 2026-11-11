package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import com.lifestealmarket.TeamTracker;
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
import java.util.ArrayList;
import java.util.List;

public class TeamBoardScreen extends Screen {
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

	private volatile List<String[]> rows = new ArrayList<>();
	private volatile String status = "Loading teams…";

	private volatile String openTeam = null;
	private volatile String[] detail = null;
	private volatile List<String[]> members = new ArrayList<>();
	private volatile String detailStatus = "";

	public TeamBoardScreen(Screen parent) {
		super(Text.literal("Team leaderboard"));
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

		addDrawableChild(new StyledButton(cr - 76, py + 7, 76, 16, Ui.label(openTeam == null ? "Back" : "Teams", C_ACCENT),
				() -> {
					if (openTeam != null) { openTeam = null; detail = null; members = new ArrayList<>(); clearAndInit(); }
					else if (this.client != null) this.client.setScreen(parent);
				}).accent(C_ACCENT));
		if (rows.isEmpty()) fetch();
	}

	private void fetch() {
		var req = HttpRequest.newBuilder(URI.create(MarketConfig.apiBase() + "/api/team/top"))
				.timeout(Duration.ofSeconds(8)).header("X-Market-Key", MarketConfig.API_KEY)
				.header("X-User", MarketConfig.userName()).header("X-Token", MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (this.client == null) return;
			if (err != null || resp.statusCode() != 200) {
				this.client.execute(() -> status = "Couldn't reach the market."); return;
			}
			try {
				var arr = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonArray();
				List<String[]> r = new ArrayList<>();
				for (var el : arr) {
					var o = el.getAsJsonObject();
					r.add(new String[]{ String.valueOf(o.get("rank").getAsInt()), o.get("team").getAsString(),
							o.get("leader").getAsString(), String.valueOf(o.get("value").getAsLong()),
							String.valueOf(o.get("tracked").getAsInt()), String.valueOf(o.get("size").getAsInt()),
							String.valueOf(o.get("verified").getAsBoolean()), String.valueOf(o.get("spawnerValue").getAsLong()) });
				}
				this.client.execute(() -> {
					rows = r;
					status = r.isEmpty() ? "No teams yet — they appear as members run the mod." : "";
				});
			} catch (Exception e) { this.client.execute(() -> status = "Bad response."); }
		});
	}

	private void openTeam(String team) {
		openTeam = team; detail = null; detailStatus = "Loading…";
		members = new ArrayList<>();
		clearAndInit();
		String url = MarketConfig.apiBase() + "/api/team?name=" + URLEncoder.encode(team, StandardCharsets.UTF_8);
		var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("X-Market-Key", MarketConfig.API_KEY).header("X-User", MarketConfig.userName())
				.header("X-Token", MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (this.client == null) return;
			if (err != null || resp.statusCode() != 200) { this.client.execute(() -> detailStatus = "Couldn't load."); return; }
			try {
				var o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
				if (!o.get("found").getAsBoolean()) { this.client.execute(() -> detailStatus = "That team is gone."); return; }
				List<String[]> ms = new ArrayList<>();
				for (var el : o.getAsJsonArray("members")) {
					var m = el.getAsJsonObject();
					ms.add(new String[]{ m.get("role").getAsString(), m.get("name").getAsString(),
							String.valueOf(m.get("worth").getAsLong()), String.valueOf(m.get("tracked").getAsBoolean()) });
				}
				String[] d = { o.get("team").getAsString(), o.get("leader").getAsString(),
						String.valueOf(o.get("value").getAsLong()), String.valueOf(o.get("spawnerValue").getAsLong()),
						String.valueOf(o.get("tracked").getAsInt()), String.valueOf(o.get("size").getAsInt()),
						String.valueOf(o.get("verified").getAsBoolean()) };
				this.client.execute(() -> { detail = d; members = ms; detailStatus = ""; });
			} catch (Exception e) { this.client.execute(() -> detailStatus = "Bad response."); }
		});
	}

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override public void renderBackground(DrawContext ctx, int mx, int my, float d) { super.renderBackground(ctx, mx, my, d); }
	@Override public void close() { Ui.escBack(this.client, parent); }

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == 256 && openTeam != null) {
			openTeam = null; detail = null; clearAndInit(); return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		if (NavBar.click(rc.x(), rc.y(), this)) return true;
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());
		if (openTeam == null && gx >= cl && gx <= cr && gy >= listTop && gy <= listBot && !rows.isEmpty()) {
			int idx = (int) ((gy - listTop + lastOff) / rowH);
			if (idx >= 0 && idx < rows.size()) { StyledButton.playClick(); openTeam(rows.get(idx)[1]); return true; }
		}
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		double gmx = toGuiX(mx), gmy = toGuiY(my);
		if (gmx >= px && gmx <= px + pw && gmy >= py && gmy <= py + ph) {
			scroll.byRows(-(int) Math.signum(v) * 2, rowH, openTeam == null ? rows.size() : members.size(), listBot - listTop);
			return true;
		}
		return super.mouseScrolled(mx, my, h, v);
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
		try { if (openTeam == null) renderList(ctx, mx, my, delta); else renderTeam(ctx, mx, my, delta); }
		finally { m.popMatrix(); }
		NavBar.draw(ctx, this.textRenderer, "board", this.width, this.height, px, py, pw, uiScale);
	}

	private void renderList(DrawContext ctx, int mx, int my, float delta) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ Teams", "§7combined net worth");
		if (revealAt == 0L && !rows.isEmpty()) revealAt = System.currentTimeMillis();

		if (!status.isEmpty()) {
			if (status.startsWith("Loading")) for (int i = 0; i < 5; i++) Ui.shimmerCard(ctx, cl, listTop + i * 20, cr - cl, 15);
			else ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8" + status), px + pw / 2, py + ph / 2, 0xFFFFFFFF);
			super.render(ctx, mx, my, delta);
			return;
		}

		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Team"), cl + 24, py + 36, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Tracked"), cl + 200, py + 36, 0xFFFFFFFF);
		String h = "§8Worth";
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(h), cr - this.textRenderer.getWidth("Worth"), py + 36, 0xFFFFFFFF);

		int viewH = listBot - listTop;
		lastOff = scroll.tickRows(rowH, rows.size(), viewH);
		int drawBot = listTop + Math.max(1, viewH / rowH) * rowH;
		ctx.fill(cl - 2, listTop - 3, cr + 2, listTop - 2, 0x1AFFFFFF);
		String mine = TeamTracker.myTeam;

		ctx.enableScissor(cl - 3, listTop, cr + 3, drawBot);
		try {
			int first = Math.max(0, lastOff / rowH);
			for (int i = first; i < rows.size(); i++) {
				int y = listTop + i * rowH - lastOff;
				if (y >= drawBot) break;
				String[] r = rows.get(i);
				boolean isMine = mine != null && mine.equalsIgnoreCase(r[1]);
				float hf = hoverFade.tick(i, i == hover);
				int a = Ui.stagger(revealAt, i - first);
				float vis = y < listTop ? Math.max(0f, 1f - (listTop - y) / (float) rowH)
						: (y + rowH > drawBot ? Math.max(0f, (drawBot - y) / (float) rowH) : 1f);
				a = Math.min(a, (int) (vis * 255));
				if (a <= 8) continue;
				int col = (a << 24) | 0xFFFFFF;
				if (isMine) StyledButton.roundFill(ctx, cl - 2, y, cr - cl + 4, rowH - 1, (C_ACCENT & 0xFFFFFF) | 0x33000000);
				else if (hf > 0.02f) StyledButton.roundFill(ctx, cl - 2, y, cr - cl + 4, rowH - 1, (C_ACCENT & 0xFFFFFF) | ((int) (hf * 0x26) << 24));
				else if ((i & 1) == 0) ctx.fill(cl, y, cr, y + rowH - 2, 0x14000000);
				String rank = r[0];
				String rankCol = rank.equals("1") ? "§6" : rank.equals("2") ? "§7" : rank.equals("3") ? "§c" : "§8";
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(rankCol + "#" + rank), cl + 2, y + 3, col);
				String nm = (isMine ? "§f§l" : "§f") + Ui.fit(this.textRenderer, r[1], 165);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(nm), cl + 24, y + 3, col);

				boolean ver = Boolean.parseBoolean(r[6]);
				String cov = (r[4].equals(r[5]) ? "§a" : "§7") + r[4] + "§8/" + r[5] + (ver ? "" : " §8?");
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(cov), cl + 200, y + 3, col);
				String tot = "§e" + Coins.fmt(Long.parseLong(r[3]));
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(tot),
						cr - this.textRenderer.getWidth(Ui.strip(tot)), y + 3, col);
			}
		} finally { ctx.disableScissor(); }
		Ui.scrollEdges(ctx, cl - 3, listTop, cr + 3, drawBot, C_PANEL);

		ctx.drawTextWithShadow(this.textRenderer,
				Text.literal("§8" + rows.size() + " teams · tracked = members running the mod"), cl, py + ph - 12, 0xFFFFFFFF);
		super.render(ctx, mx, my, delta);
	}

	private void renderTeam(DrawContext ctx, int mx, int my, float delta) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ " + Ui.trim(openTeam, 20), "§7team roster");
		if (detail == null) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8" + detailStatus),
					px + pw / 2, py + ph / 2, 0xFFFFFFFF);
			super.render(ctx, mx, my, delta);
			return;
		}
		boolean ver = Boolean.parseBoolean(detail[6]);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(
				"§7Leader: §f" + (detail[1].isEmpty() ? "—" : detail[1])), cl, py + 30, 0xFFFFFFFF);
		String tot = "§e" + Coins.fmt(Long.parseLong(detail[2]));
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(tot),
				cr - this.textRenderer.getWidth(Ui.strip(tot)), py + 30, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + detail[4] + " of " + detail[5]
				+ " members tracked"), cl, py + 42, 0xFFFFFFFF);
		String sp = "§8spawners §7" + Coins.fmt(Long.parseLong(detail[3])) + (ver ? " §8✔" : " §8· unverified");
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(sp),
				cr - this.textRenderer.getWidth(Ui.strip(sp)), py + 42, 0xFFFFFFFF);

		int viewH = listBot - listTop;
		lastOff = scroll.tickRows(rowH, members.size(), viewH);
		int drawBot = listTop + Math.max(1, viewH / rowH) * rowH;
		ctx.fill(cl - 2, listTop - 3, cr + 2, listTop - 2, 0x1AFFFFFF);
		ctx.enableScissor(cl - 3, listTop, cr + 3, drawBot);
		try {
			int first = Math.max(0, lastOff / rowH);
			for (int i = first; i < members.size(); i++) {
				int y = listTop + i * rowH - lastOff;
				if (y >= drawBot) break;
				String[] m = members.get(i);
				if ((i & 1) == 0) ctx.fill(cl, y, cr, y + rowH - 2, 0x14000000);
				PlayerHeads.draw(ctx, m[1], cl + 2, y + 1, 12);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + Ui.fit(this.textRenderer, m[1], 130)),
						cl + 17, y + 3, 0xFFFFFFFF);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + Ui.fit(this.textRenderer, m[0], 80)),
						cl + 150, y + 3, 0xFFFFFFFF);
				long worth = Long.parseLong(m[2]);
				boolean tracked = Boolean.parseBoolean(m[3]);
				String right = worth < 0 ? "§8private" : (tracked ? "§e" + Coins.fmt(worth) : "§8no mod");
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(right),
						cr - this.textRenderer.getWidth(Ui.strip(right)), y + 3, 0xFFFFFFFF);
			}
		} finally { ctx.disableScissor(); }
		Ui.scrollEdges(ctx, cl - 3, listTop, cr + 3, drawBot, C_PANEL);
		ctx.drawTextWithShadow(this.textRenderer,
				Text.literal(ver ? "§8spawner count confirmed by 2+ members"
						: "§8only one member reports this team's spawners"), cl, py + ph - 12, 0xFFFFFFFF);
		super.render(ctx, mx, my, delta);
	}
}

