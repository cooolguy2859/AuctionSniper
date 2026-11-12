package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LeaderboardScreen extends Screen {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final Identifier[] COIN_TEX = {
		Identifier.of("lifestealmarket", "textures/coins/tier0.png"), Identifier.of("lifestealmarket", "textures/coins/tier1.png"),
		Identifier.of("lifestealmarket", "textures/coins/tier2.png"), Identifier.of("lifestealmarket", "textures/coins/tier3.png"),
		Identifier.of("lifestealmarket", "textures/coins/tier4.png"), Identifier.of("lifestealmarket", "textures/coins/tier5.png"),
	};

	private final Screen parent;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph, cl, cr, listTop, listBot;
	private double uiScale = 1.0;
	private final Ui.Scroll scroll = new Ui.Scroll();
	private final Ui.HoverFade hover = new Ui.HoverFade();
	private final long openedAt = System.currentTimeMillis();

	private String[] cardRow = null;
	private int[] cardFrom;
	private long cardAt, cardCloseAt;
	private boolean cardClosing;
	private int[] cardR, cardShopBtn, cardEcBtn;

	private com.lifestealmarket.EnderShare.Chest cardChest;
	private String cardChestFor = "";
	private boolean cardChestFailed = false;

	private volatile long[][] cardSeries = null;
	private volatile String cardSeriesFor = "";
	private volatile int cardSeriesState = 0;
	private int lastOffDraw = 0;
	private double effScale = 1.0;
	private long revealAt = 0L;

	private java.util.Map<String, Integer> prevRanks = new java.util.HashMap<>();

	private final java.util.Map<String, Integer> moved = new java.util.HashMap<>();

	private final java.util.List<String> passed = new java.util.ArrayList<>();
	private long slideAt = 0L;
	private static final long SLIDE_MS = 900;

	private static final long REBASE_MS = 20 * 60 * 1000L;
	private boolean rebaseOnClose = false;
	private volatile List<String[]> rows = new ArrayList<>();
	private net.minecraft.client.gui.widget.TextFieldWidget search;
	private String query = "";

	private List<String[]> view() {
		if (query.isEmpty()) return rows;
		List<String[]> out = new ArrayList<>();
		for (String[] r : rows) if (r[1].toLowerCase(Locale.ROOT).contains(query)) out.add(r);
		return out;
	}
	private volatile int myRankCached = -1;
	private volatile String status = "Loading...";
	private String me = "";

	public LeaderboardScreen(Screen parent) {
		super(Text.literal("Leaderboard"));
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
		listTop = py + 56; listBot = py + ph - 34;

		listBot = listTop + Math.max(1, (listBot - listTop) / 16) * 16;
		if (this.client != null) me = this.client.getSession().getUsername().toLowerCase(Locale.ROOT);

		addDrawableChild(new StyledButton(cr - 76, py + 7, 76, 16, Ui.label("Back", C_ACCENT),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));

		addDrawableChild(new StyledButton(cl, py + 35, 92, 16, Text.literal("§6✦ Team board"),
				() -> { if (this.client != null) this.client.setScreen(new TeamBoardScreen(this)); })
				.accent(C_ACCENT).tooltip("§b§lTeam leaderboard", "§7Teams ranked by combined net worth",
						"§7Members' worth + the team's spawners"));

		search = new net.minecraft.client.gui.widget.TextFieldWidget(
				this.textRenderer, cr - 120, py + 35, 120, 16, Text.literal("search players"));
		search.setPlaceholder(Text.literal("§8search players…"));
		search.setMaxLength(24);
		search.setText(query);
		search.setChangedListener(s -> {
			query = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
			scroll.reset();
		});
		addDrawableChild(search);

		if (MarketConfig.isOwner(me))
			addDrawableChild(new StyledButton(cr - 76 - 88, py + 7, 84, 16, Text.literal("§d✦ Mod Users"),
					() -> { if (this.client != null) this.client.setScreen(new ModUsersScreen(this)); }).accent(C_ACCENT));
		fetch();
	}

	private void fetchSeries(String who) {
		String url = MarketConfig.apiBase() + "/api/networth/series?user="
				+ java.net.URLEncoder.encode(who, java.nio.charset.StandardCharsets.UTF_8);
		var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId())
				.header("X-User", com.lifestealmarket.MarketConfig.userName())
				.header("X-Token", com.lifestealmarket.MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (this.client == null) return;
			this.client.execute(() -> {
				if (!who.equals(cardSeriesFor)) return;
				try {
					if (err != null || resp.statusCode() != 200) { cardSeriesState = 2; return; }
					MarketConfig.noteDataAge(resp);
					var root = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
					var arr = root.getAsJsonArray("points");
					long[][] pts = new long[arr.size()][2];
					for (int i = 0; i < arr.size(); i++) {
						var p = arr.get(i).getAsJsonArray();
						pts[i][0] = p.get(0).getAsLong();
						pts[i][1] = p.get(1).getAsLong();
					}
					cardSeries = pts;
					cardSeriesState = pts.length >= 2 ? 1 : 2;
				} catch (Exception e) { cardSeriesState = 2; }
			});
		});
	}

	private void fetch() {
		String url = MarketConfig.apiBase() + "/api/networth/top";
		var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId()).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (this.client == null) return;
			this.client.execute(() -> {
				try {
					if (err != null || resp.statusCode() != 200) { status = "Couldn't reach the server."; return; }
					var arr = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonArray();
					List<String[]> r = new ArrayList<>();
					for (var e : arr) { var o = e.getAsJsonObject();
						r.add(new String[]{ String.valueOf(o.get("rank").getAsInt()), o.get("name").getAsString(), String.valueOf(o.get("networth").getAsLong()) }); }
					rows = r; status = r.isEmpty() ? "No one on the board yet." : "";
					computeMovement(r);
					int mr = -1;
					for (String[] row : r) if (row[1].toLowerCase(Locale.ROOT).equals(me)) {
						try { mr = Integer.parseInt(row[0]); } catch (NumberFormatException ignored) {}
					}
					myRankCached = mr;
				} catch (Exception ex) { status = "Bad response."; }
			});
		});
	}

	private void computeMovement(List<String[]> r) {
		prevRanks = new java.util.HashMap<>(MarketConfig.get().lastRanks);
		moved.clear(); passed.clear();
		for (String[] row : r) {
			String key = row[1].toLowerCase(Locale.ROOT);
			Integer was = prevRanks.get(key);
			if (was == null) continue;
			int now;
			try { now = Integer.parseInt(row[0]); } catch (NumberFormatException e) { continue; }
			if (was != now) moved.put(key, was - now);
		}

		Integer myWas = prevRanks.get(me);
		int myNow = -1;
		for (String[] row : r) if (row[1].toLowerCase(Locale.ROOT).equals(me)) {
			try { myNow = Integer.parseInt(row[0]); } catch (NumberFormatException ignored) {}
		}
		if (myWas != null && myNow > 0 && myNow < myWas) {
			for (String[] row : r) {
				String key = row[1].toLowerCase(Locale.ROOT);
				if (key.equals(me)) continue;
				Integer theirWas = prevRanks.get(key);
				int theirNow;
				try { theirNow = Integer.parseInt(row[0]); } catch (NumberFormatException e) { continue; }

				if (theirWas != null && theirWas < myWas && theirNow > myNow) passed.add(row[1]);
			}
		}
		slideAt = System.currentTimeMillis();
		rebaseOnClose = System.currentTimeMillis() - MarketConfig.get().lastRanksAt > REBASE_MS
				|| MarketConfig.get().lastRanks.isEmpty();
	}

	private void saveBaseline() {
		if (!rebaseOnClose || rows.isEmpty()) return;
		java.util.Map<String, Integer> snap = new java.util.HashMap<>();
		for (String[] row : rows) {
			try { snap.put(row[1].toLowerCase(Locale.ROOT), Integer.parseInt(row[0])); } catch (NumberFormatException ignored) {}
		}
		MarketConfig.get().lastRanks = snap;
		MarketConfig.get().lastRanksAt = System.currentTimeMillis();
		MarketConfig.save();
		rebaseOnClose = false;
	}

	private double slideOffsetRows(String nameKey) {
		int delta = moved.getOrDefault(nameKey, 0);
		if (delta == 0) return 0;
		long dt = System.currentTimeMillis() - slideAt;
		if (dt >= SLIDE_MS) return 0;
		double t = Math.max(0, Math.min(1, dt / (double) SLIDE_MS));
		double eased = 1 - Math.pow(1 - t, 3);
		return delta * (1 - eased);
	}

	@Override public void renderBackground(DrawContext ctx, int mx, int my, float d) { super.renderBackground(ctx, mx, my, d); }
	@Override public void close() { Ui.escBack(this.client, parent); }

	@Override public void removed() { saveBaseline(); super.removed(); }

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		if (NavBar.click(rc.x(), rc.y(), this)) return true;
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());
		if (cardRow != null) {
			if (!cardClosing) {
				if (cardShopBtn != null && gx >= cardShopBtn[0] && gy >= cardShopBtn[1] && gx <= cardShopBtn[2] && gy <= cardShopBtn[3]) {
					if (this.client != null) this.client.setScreen(new ShopScreen(this));
					return true;
				}

				if (cardEcBtn != null && gx >= cardEcBtn[0] && gy >= cardEcBtn[1] && gx <= cardEcBtn[2] && gy <= cardEcBtn[3]) {
					if (cardChest != null && cardChest.shared() && this.client != null) {
						StyledButton.playClick();
						this.client.setScreen(new EnderChestScreen(this, cardRow[1]));
					}
					return true;
				}
				if (cardR == null || !(gx >= cardR[0] && gy >= cardR[1] && gx <= cardR[2] && gy <= cardR[3])) closeCard();
			}
			return true;
		}
		if (gx >= cl - 2 && gx <= cr + 2 && gy >= listTop && gy <= listBot && !view().isEmpty() && status.isEmpty()) {
			List<String[]> vw = view();
			int idx = (int) ((gy - listTop + lastOffDraw) / 16);
			if (idx >= 0 && idx < vw.size()) {
				int ry = listTop + idx * 16 - lastOffDraw;
				cardRow = vw.get(idx);
				cardFrom = new int[]{ cl - 2, ry - 1, cr + 2, ry + 13 };
				cardAt = System.currentTimeMillis(); cardClosing = false;

				cardChest = null; cardChestFailed = false; cardChestFor = cardRow[1];
				cardSeries = null; cardSeriesState = 0; cardSeriesFor = cardRow[1];
				fetchSeries(cardRow[1]);
				final String who = cardChestFor;

				com.lifestealmarket.EnderShare.fetch(who, c -> {
					if (!who.equals(cardChestFor)) return;
					cardChest = c;
					if (c == null) cardChestFailed = true;
				});
				return true;
			}
		}
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	private void closeCard() {
		if (cardRow != null && !cardClosing) { cardClosing = true; cardCloseAt = System.currentTimeMillis(); }
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		if (cardRow != null) return true;
		double gmy = toGuiY(my), gmx = toGuiX(mx);
		if (gmx >= px && gmx <= px + pw && gmy >= py && gmy <= py + ph) {
			scroll.byRows(-(int) Math.signum(v) * 2, 16, view().size(), listBot - listTop);
			return true;
		}
		return super.mouseScrolled(mx, my, h, v);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == 256 && cardRow != null) { closeCard(); return true; }
		if (search != null && search.isFocused()) return super.keyPressed(input);
		if (input.key() == 264) { scroll.byRows(2, 16, view().size(), listBot - listTop); return true; }
		if (input.key() == 265) { scroll.byRows(-2, 16, view().size(), listBot - listTop); return true; }
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
		NavBar.draw(ctx, this.textRenderer, "board", this.width, this.height, px, py, pw, uiScale);
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ Leaderboard", "§7top 100 net worth");
		if (revealAt == 0L && !rows.isEmpty()) revealAt = System.currentTimeMillis();

		if (!status.isEmpty()) {
			if (status.startsWith("Loading")) {
				for (int i = 0; i < 5; i++) Ui.shimmerCard(ctx, cl, listTop + i * 18, cr - cl, 14);
			} else {
				ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8" + status), px + pw / 2, py + ph / 2, 0xFFFFFFFF);
			}
			super.render(ctx, mx, my, delta);
			return;
		}

		int rowH = 16, viewH = listBot - listTop;
		List<String[]> vw = view();
		int off = scroll.tickRows(rowH, vw.size(), viewH);
		lastOffDraw = off;
		int drawBot = listTop + Math.max(1, viewH / rowH) * rowH;
		ctx.fill(cl - 2, listTop - 3, cr + 2, listTop - 2, 0x1AFFFFFF);

		int myRank = myRankCached;

		ctx.enableScissor(cl - 3, listTop, cr + 3, drawBot);
		try {

			boolean gliding = query.isEmpty() && System.currentTimeMillis() - slideAt < SLIDE_MS && !moved.isEmpty();
			int first = gliding ? 0 : Math.max(0, off / rowH);
			for (int i = first; i < vw.size(); i++) {
				String[] r = vw.get(i);
				String nameKey = r[1].toLowerCase(Locale.ROOT);

				int y = listTop + i * rowH - off + (int) Math.round(slideOffsetRows(nameKey) * rowH);
				if (y >= drawBot) { if (gliding) continue; else break; }
				if (y + rowH <= listTop) continue;
				boolean mine = nameKey.equals(me);
				boolean hov = mx >= cl - 2 && mx <= cr + 2 && my >= y - 1 && my < y + rowH - 1;
				float hf = hover.tick(i, hov);
				int a = Ui.stagger(revealAt, i - first);

				float vis = y < listTop ? Math.max(0f, 1f - (listTop - y) / (float) rowH)
						: (y + rowH > drawBot ? Math.max(0f, (drawBot - y) / (float) rowH) : 1f);
				a = Math.min(a, (int) (vis * 255));
				if (a <= 8) continue;
				if (mine) StyledButton.roundFill(ctx, cl - 2, y - 1, cr - cl + 4, rowH, (C_ACCENT & 0xFFFFFF) | 0x33000000);
				else if (hf > 0.02f) StyledButton.roundFill(ctx, cl - 2, y - 1, cr - cl + 4, rowH,
						(C_ACCENT & 0xFFFFFF) | ((int) (hf * 0x26) << 24));
				else if ((i & 1) == 0) ctx.fill(cl, y - 1, cr, y + rowH - 2, 0x14000000);
				int col = (a << 24) | 0xFFFFFF;
				String rank = r[0];
				String rankCol = rank.equals("1") ? "§6" : rank.equals("2") ? "§7" : rank.equals("3") ? "§c" : "§8";
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(rankCol + "#" + rank), cl + 2, y + 4, col);
				PlayerHeads.draw(ctx, r[1], cl + 32, y + 2, 12);

				String[] fl = com.lifestealmarket.Flair.of(r[1]);
				var nameTxt = Text.empty();
				if (fl != null && !fl[1].isEmpty() && !fl[1].replaceAll("(?i)§.", "").trim().matches(".*[A-Za-z]{3,}.*"))
					nameTxt.append(Text.literal(fl[1]));
				nameTxt.append(Ui.colouredName(trim(r[1], 18),
						fl == null || fl[0].isEmpty() ? null : fl[0]));
				if (mine) nameTxt = Text.empty().append(Text.literal("§l")).append(nameTxt);
				ctx.drawTextWithShadow(this.textRenderer, nameTxt, cl + 47, y + 4, col);
				long nw = Long.parseLong(r[2]);
				String tot = "§e" + Coins.fmt(nw);
				int tw = Ui.widthNoCodes(this.textRenderer, tot);
				drawCoin(ctx, cr - 15, y + 3, nw);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(tot), cr - 17 - tw, y + 4, col);

				int moveDelta = moved.getOrDefault(nameKey, 0);
				if (moveDelta != 0) {
					long dt = System.currentTimeMillis() - slideAt;
					int aa = (int) (Math.max(0, Math.min(1, (dt - SLIDE_MS * 0.55) / 400.0)) * (a / 255.0) * 255);
					if (aa > 8) {
						String arrow = (moveDelta > 0 ? "§a▲" : "§c▼") + Math.abs(moveDelta);
						int aw = Ui.widthNoCodes(this.textRenderer, arrow);
						ctx.drawTextWithShadow(this.textRenderer, Text.literal(arrow),
								cr - 17 - tw - 8 - aw, y + 4, (aa << 24) | 0xFFFFFF);
					}
				}
			}
		} finally { ctx.disableScissor(); }

		if (vw.isEmpty() && !rows.isEmpty())
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§8no player on the board matches §7" + query),
					(cl + cr) / 2, listTop + Math.max(8, (drawBot - listTop) / 2 - 4), 0xFFFFFFFF);
		Ui.scrollEdges(ctx, cl - 3, listTop, cr + 3, drawBot, C_PANEL);

		int myDelta = moved.getOrDefault(me, 0);
		if (myDelta != 0) {
			long dt = System.currentTimeMillis() - slideAt;
			float in = (float) Math.max(0, Math.min(1, (dt - SLIDE_MS * 0.6) / 500.0));
			if (in > 0.02f) {
				String line;
				if (myDelta > 0) {
					line = passed.isEmpty()
							? "§a▲ You climbed " + myDelta + (myDelta == 1 ? " place" : " places")
							: "§a▲ You passed §f" + trim(passed.get(0), 16)
									+ (passed.size() > 1 ? " §aand " + (passed.size() - 1) + " more" : "");
				} else {
					line = "§c▼ You dropped " + (-myDelta) + ((-myDelta) == 1 ? " place" : " places");
				}
				int lw = Ui.widthNoCodes(this.textRenderer, line);
				int bx = px + pw / 2 - lw / 2 - 8, by = py + ph - 30 - (int) ((1 - in) * 6);
				int ba = (int) (in * 0xCC);
				StyledButton.roundFill(ctx, bx, by - 3, lw + 16, 15, (ba << 24) | (myDelta > 0 ? 0x20C060 : 0xC03030));
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(line),
						px + pw / 2 - lw / 2, by, ((int) (in * 255) << 24) | 0xFFFFFF);
			}
		}

		String foot = myRank > 0 ? "§7Your rank: §f#" + myRank : (MarketConfig.get().netPrivate ? "§8You're private - not ranked" : "§8You're not on the board yet");
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(foot), cl, py + ph - 12, 0xFFFFFFFF);
		int maxScroll = Math.max(0, vw.size() * rowH - viewH);
		if (maxScroll > 0) {
			int firstVis = off / rowH + 1, lastVis = Math.min(vw.size(), (off + viewH) / rowH);
			String s = "§8" + firstVis + "-" + lastVis + " / " + vw.size() + " ↕";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), cr - Ui.widthNoCodes(this.textRenderer, s), py + ph - 12, 0xFFFFFFFF);
		}
		super.render(ctx, mx, my, delta);
		drawCard(ctx, mx, my);
	}

	private void drawCard(DrawContext ctx, int mx, int my) {
		if (cardRow == null) return;
		long now = System.currentTimeMillis();
		double t;
		if (!cardClosing) t = Ui.easeOut((now - cardAt) / 170.0);
		else {
			double tc = (now - cardCloseAt) / 150.0;
			if (tc >= 1) { cardRow = null; cardR = cardShopBtn = null; return; }
			t = 1 - Ui.easeOut(tc);
		}
		ctx.fill(px, py, px + pw, py + ph, ((int) (0x99 * t)) << 24);

		int tw = 292, th = 138;
		int tx = px + (pw - tw) / 2, ty = py + (ph - th) / 2;
		int x1 = (int) Math.round(cardFrom[0] + (tx - cardFrom[0]) * t);
		int y1 = (int) Math.round(cardFrom[1] + (ty - cardFrom[1]) * t);
		int x2 = (int) Math.round(cardFrom[2] + (tx + tw - cardFrom[2]) * t);
		int y2 = (int) Math.round(cardFrom[3] + (ty + th - cardFrom[3]) * t);
		StyledButton.roundFill(ctx, x1 + 3, y1 + 4, x2 - x1, y2 - y1, 0x66000000);
		StyledButton.roundFill(ctx, x1, y1, x2 - x1, y2 - y1, 0xFF090D14);
		StyledButton.roundBorder(ctx, x1, y1, x2 - x1, y2 - y1, 0xFF000000 | (C_ACCENT & 0xFFFFFF));
		cardR = new int[]{ x1, y1, x2, y2 };
		if (t < 0.92) { cardShopBtn = cardEcBtn = null; return; }

		String name = cardRow[1];
		String rank = cardRow[0];
		long nw = Long.parseLong(cardRow[2]);

		boolean shares = cardChest != null && cardChest.shared();

		String ecLbl = cardChest != null ? (shares ? "§b✦ Ender chest" : "§8Ender chest · private")
				: cardChestFailed ? "§8Ender chest · unavailable" : "§8Ender chest…";
		int ecW = this.textRenderer.getWidth(Ui.strip(ecLbl)) + 14;
		int ecX = x2 - ecW - 8, ecY = y1 + 8;
		cardEcBtn = new int[]{ ecX, ecY, ecX + ecW, ecY + 16 };
		boolean hovEc = shares && mx >= ecX && mx <= ecX + ecW && my >= ecY && my <= ecY + 16;
		StyledButton.roundFill(ctx, ecX, ecY, ecW, 16,
				shares ? ((C_ACCENT & 0xFFFFFF) | (hovEc ? 0x66000000 : 0x33000000)) : 0x22000000);
		StyledButton.roundBorder(ctx, ecX, ecY, ecW, 16, shares ? (0xFF000000 | (C_ACCENT & 0xFFFFFF)) : 0x33FFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(ecLbl), ecX + 7, ecY + 4, 0xFFFFFFFF);

		PlayerHeads.draw(ctx, name, x1 + 12, y1 + 14, 44);
		StyledButton.roundBorder(ctx, x1 + 11, y1 + 13, 46, 46, 0x33FFFFFF);
		int rx = x1 + 66;
		String rankCol = rank.equals("1") ? "§6" : rank.equals("2") ? "§7" : rank.equals("3") ? "§c" : "§8";
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(rankCol + "#" + rank + " §8on the board"), rx, y1 + 12, 0xFFFFFFFF);

		String[] flair = com.lifestealmarket.Flair.of(name);
		var nameText = Text.empty();
		boolean hasLook = flair != null && (!flair[0].isEmpty() || !flair[1].isEmpty());
		if (flair != null && !flair[1].isEmpty()) nameText.append(Text.literal(flair[1]));
		nameText.append(Ui.colouredName(name, flair == null || flair[0].isEmpty() ? null : flair[0]));
		var m = ctx.getMatrices();
		m.pushMatrix(); m.translate(rx, y1 + 24); m.scale(1.25f, 1.25f);
		ctx.drawTextWithShadow(this.textRenderer, nameText, 0, 0, 0xFFFFFFFF);
		m.popMatrix();
		String worth = "§e" + Coins.fmt(nw);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(worth), rx, y1 + 42, 0xFFFFFFFF);
		drawCoin(ctx, rx + Ui.widthNoCodes(this.textRenderer, worth) + 4, y1 + 46, nw);
		drawWealthGraph(ctx, x1 + 12, y1 + 64, (x2 - x1) - 24, 44);

		String btn = hasLook ? "§6✦ Get this look" : "§6✦ Browse the Shop";
		int bw = Ui.widthNoCodes(this.textRenderer, btn) + 16;
		int bx = x1 + 66, by = y2 - 24;
		cardShopBtn = new int[]{ bx, by, bx + bw, by + 17 };
		boolean hovB = mx >= bx && mx <= bx + bw && my >= by && my <= by + 17;
		StyledButton.roundFill(ctx, bx, by, bw, 17, hovB ? 0x66FFA132 : 0x3AFFA132);
		StyledButton.roundBorder(ctx, bx, by, bw, 17, hovB ? 0xFFFFC46B : 0xAAFFA132);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(btn), bx + 8, by + 5, 0xFFFFFFFF);
	}

	private void drawCoin(DrawContext ctx, int x, int y, long v) {
		ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, COIN_TEX[coinTier(v)], x, y - 4, 0f, 0f, 15, 15, 32, 32, 32, 32);
	}
	private static int coinTier(long v) {
		if (v < 100_000L) return 0; if (v < 500_000L) return 1; if (v < 1_000_000L) return 2;
		if (v < 4_000_000L) return 3; if (v < 8_000_000L) return 4; return 5;
	}
	private String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "..."; }

	private void drawWealthGraph(DrawContext ctx, int gx, int gy, int gw, int gh) {
		StyledButton.roundFill(ctx, gx, gy, gw, gh, 0x40000000);
		StyledButton.roundBorder(ctx, gx, gy, gw, gh, 0x22FFFFFF);
		long[][] pts = cardSeries;
		if (cardSeriesState == 0) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8net worth history…"),
					gx + gw / 2, gy + gh / 2 - 4, 0xFFFFFFFF);
			return;
		}
		if (cardSeriesState == 2 || pts == null || pts.length < 2) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8no wealth history yet"),
					gx + gw / 2, gy + gh / 2 - 8, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8it builds up as they play"),
					gx + gw / 2, gy + gh / 2 + 2, 0xFFFFFFFF);
			return;
		}
		long t0 = pts[0][0], t1 = pts[pts.length - 1][0];
		if (t1 <= t0) t1 = t0 + 1;

		long[][] sm = spSmooth(pts, t0, t1);
		long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
		for (long[] p : sm) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
		long span = Math.max(1, hi - lo);
		int pad = 5, x0 = gx + pad, x1p = gx + gw - pad, plotTop = gy + pad + 8, plotBot = gy + gh - pad;
		boolean up = pts[pts.length - 1][1] >= pts[0][1];
		int col = up ? 0xFF3BE08A : 0xFFFF5E6E;

		int m = sm.length;
		int[] sxs = new int[m], sys = new int[m];
		for (int i = 0; i < m; i++) {
			sxs[i] = x0 + (int) Math.round((x1p - x0) * (sm[i][0] - t0) / (double) (t1 - t0));
			sys[i] = hi == lo ? (plotTop + plotBot) / 2
					: plotBot - (int) Math.round((plotBot - plotTop) * (sm[i][1] - lo) / (double) span);
		}
		java.util.List<int[]> cv = spCurve(sxs, sys);
		int[] cx = new int[cv.size()], cy = new int[cv.size()];
		for (int i = 0; i < cv.size(); i++) {
			cx[i] = cv.get(i)[0];
			cy[i] = Math.max(plotTop, Math.min(plotBot, cv.get(i)[1]));
		}
		spPolyline(ctx, cx, cy, cv.size(), col, 2);

		int ex = sxs[m - 1], ey = Math.max(plotTop, Math.min(plotBot, sys[m - 1]));
		ctx.fill(ex - 2, ey - 2, ex + 3, ey + 3, 0xFF000000 | (col & 0xFFFFFF));
		ctx.fill(ex - 1, ey - 1, ex + 2, ey + 2, 0xFFFFFFFF);

		long delta = pts[pts.length - 1][1] - pts[0][1];
		String pct = pts[0][1] > 0
				? String.format(Locale.ROOT, "%+.1f%%", delta * 100.0 / pts[0][1]) : (delta >= 0 ? "+" : "-");
		String head = (up ? "§a" : "§c") + pct + " §8over " + ageShort(t1 - t0);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(head), gx + 5, gy + 3, 0xFFFFFFFF);
		String range = "§8" + Coins.fmt(lo) + " – " + Coins.fmt(hi);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(range),
				gx + gw - 5 - Ui.widthNoCodes(this.textRenderer, range), gy + 3, 0xFFFFFFFF);
	}

	private static long[][] spSmooth(long[][] p, long t0, long t1) {
		int M = Math.max(24, Math.min(64, p.length));
		double[] pr = new double[M];
		for (int i = 0; i < M; i++) {
			long t = t0 + (t1 - t0) * i / (M - 1);
			pr[i] = spValueAt(p, t);
		}
		int w = 3;
		long[][] out = new long[M][2];
		for (int i = 0; i < M; i++) {
			int we = Math.min(w, Math.min(i, M - 1 - i));
			double s = 0; int c = 0;
			for (int j = -we; j <= we; j++) { int k = i + j; if (k >= 0 && k < M) { s += pr[k]; c++; } }
			out[i][0] = t0 + (t1 - t0) * i / (M - 1);
			out[i][1] = Math.round(s / c);
		}
		return out;
	}

	private static double spValueAt(long[][] p, long t) {
		if (t <= p[0][0]) return p[0][1];
		if (t >= p[p.length - 1][0]) return p[p.length - 1][1];
		for (int i = 1; i < p.length; i++) {
			if (p[i][0] >= t) {
				long dt = p[i][0] - p[i - 1][0];
				double f = dt <= 0 ? 0 : (t - p[i - 1][0]) / (double) dt;
				return p[i - 1][1] + (p[i][1] - p[i - 1][1]) * f;
			}
		}
		return p[p.length - 1][1];
	}

	private static java.util.List<int[]> spCurve(int[] xs, int[] ys) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		int n = xs.length;
		if (n == 1) { out.add(new int[]{ xs[0], ys[0] }); return out; }
		for (int i = 0; i < n - 1; i++) {
			int p0 = Math.max(0, i - 1), p1 = i, p2 = i + 1, p3 = Math.min(n - 1, i + 2);
			for (int s = 0; s < 6; s++) {
				double t = s / 6.0, t2 = t * t, t3 = t2 * t;
				double x = 0.5 * ((2 * xs[p1]) + (-xs[p0] + xs[p2]) * t + (2 * xs[p0] - 5 * xs[p1] + 4 * xs[p2] - xs[p3]) * t2 + (-xs[p0] + 3 * xs[p1] - 3 * xs[p2] + xs[p3]) * t3);
				double y = 0.5 * ((2 * ys[p1]) + (-ys[p0] + ys[p2]) * t + (2 * ys[p0] - 5 * ys[p1] + 4 * ys[p2] - ys[p3]) * t2 + (-ys[p0] + 3 * ys[p1] - 3 * ys[p2] + ys[p3]) * t3);
				out.add(new int[]{ (int) Math.round(x), (int) Math.round(y) });
			}
		}
		out.add(new int[]{ xs[n - 1], ys[n - 1] });
		return out;
	}

	private static void spPolyline(DrawContext ctx, int[] xs, int[] ys, int n, int col, int th) {
		for (int i = 1; i < n; i++) {
			int ax = xs[i - 1], ay = ys[i - 1], bx = xs[i], by = ys[i];
			if (bx <= ax) { int lo = Math.min(ay, by), hi = Math.max(ay, by); ctx.fill(ax, lo, ax + th, hi + th, col); continue; }
			for (int x = ax; x <= bx; x++) {
				int y0 = ay + (by - ay) * (x - ax) / (bx - ax);
				int y1 = ay + (by - ay) * (Math.min(bx, x + 1) - ax) / (bx - ax);
				int lo = Math.min(y0, y1), hi = Math.max(y0, y1);
				ctx.fill(x, lo, x + th, hi + th, col);
			}
		}
	}

	private static String ageShort(long ms) {
		long s = ms / 1000;
		if (s < 5400) return Math.max(1, s / 60) + "m";
		if (s < 86400) return (s / 3600) + "h";
		return (s / 86400) + "d";
	}

}

