package com.lifestealmarket.gui;

import com.lifestealmarket.MarketConfig;
import com.lifestealmarket.Nameplate;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ShopScreen extends Screen {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final long PREVIEW_MS = 8000L;

	private static final long PLATE_MS = 12_000L;

	record StoreItem(String id, String type, String name, String payload, String price,
			boolean inShop, boolean returning, int owners, String wornBy) {}

	private record Hit(int x1, int y1, int x2, int y2, StoreItem item, boolean isTry) {}

	private final Screen parent;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph, cl, cr;
	private int cardW, cardH, heroH, featH, viewTop, viewBot, bundleH;
	private double uiScale = 1.0;
	private volatile List<StoreItem> store = null;

	private volatile boolean closed = false;
	private volatile String buyUrl = "https://www.patreon.com/LifestealMarket";
	private volatile long rotatesAt = 0L;
	private boolean fetched = false;
	private volatile boolean fetchFailed = false;
	private volatile long nextFetchAt = 0L;
	private double scrollTarget = 0, scrollPos = 0;
	private long lastAnimMs = System.currentTimeMillis();
	private double effScale = 1.0;
	private final List<Hit> hits = new ArrayList<>();

	private long previewEndsAt = 0L, previewSpan = PREVIEW_MS;
	private float rawMx, rawMy;
	private static boolean modelOff = false;
	private final long openedAt = System.currentTimeMillis();

	private StoreItem inspect;
	private int[] inspectFrom;
	private long inspectAt, inspectCloseAt;
	private boolean inspectClosing;
	private int[] modalR, btnTryR, btnBuyR, btnGiftR;

	private boolean giftMode = false;

	private TextFieldWidget giftField;

	public boolean typing() { return giftMode && giftField != null; }
	private String giftName = "";

	public ShopScreen(Screen parent) {
		super(Text.literal("Shop"));
		this.parent = parent;
	}

	@Override
	protected void init() {

		long armed = Nameplate.previewEndsAt();
		if (armed > 0) { previewEndsAt = armed; previewSpan = PLATE_MS; }
		long wash = Math.max(MarketConfig.previewBgUntil, MarketConfig.previewAccentUntil);
		if (wash > System.currentTimeMillis() && wash > previewEndsAt) { previewEndsAt = wash; previewSpan = PREVIEW_MS; }
		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a; C_ACC_DIM = (a & 0x00FFFFFF) | 0x55000000;
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (a & 0xFFFFFF), 0.14f);
		pw = 560; ph = 320;
		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		cl = px + 12; cr = px + pw - 12;
		cardW = (cr - cl - 24) / 4;
		cardH = 104; heroH = 68; featH = 58;
		viewTop = py + 40; viewBot = py + ph - 22;

		bundleH = (int) ((viewBot - viewTop) * 0.86);

		addDrawableChild(new StyledButton(cr - 56, py + 7, 56, 17, Ui.label("Back", C_ACCENT),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));
		addDrawableChild(new StyledButton(cr - 120, py + 7, 60, 17, Text.literal("§d✿ Style"),
				() -> { if (this.client != null) this.client.setScreen(new CustomizeScreen(this)); }).accent(C_ACCENT)
				.tooltip("§dYour wardrobe", "§7Apply everything you own."));

		if (!fetched) { fetched = true; fetchStore(); }
	}

	private List<StoreItem> byType(String t) {
		List<StoreItem> out = new ArrayList<>();
		if (store != null) for (StoreItem s : store) if (s.type().equals(t)) out.add(s);
		return out;
	}

	private List<StoreItem> buyables() {
		List<StoreItem> out = new ArrayList<>();
		if (store != null) for (StoreItem s : store)

			if (!s.type().equals("bundle") && s.inShop()) out.add(s);
		return out;
	}

	private StoreItem find(String id) {
		if (store != null) for (StoreItem s : store) if (s.id().equalsIgnoreCase(id.trim())) return s;
		return null;
	}

	private StoreItem featured() {
		List<StoreItem> bundles = byType("bundle");
		if (bundles.isEmpty()) return null;
		StoreItem best = bundles.get(0); double bestSave = -1;
		for (StoreItem b : bundles) {
			Double s = savings(b);
			if (s != null && s > bestSave) { bestSave = s; best = b; }
		}
		return best;
	}

	private void fetchStore() {
		var req = HttpRequest.newBuilder(URI.create(MarketConfig.apiBase() + "/api/store"))
				.timeout(Duration.ofSeconds(8)).header("X-Market-Key", MarketConfig.API_KEY).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) {

				fetchFailed = true;
				nextFetchAt = System.currentTimeMillis() + 3000L;
				fetched = false;
				return;
			}
			fetchFailed = false;
			try {
				var root = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
				if (root.has("buy")) buyUrl = root.get("buy").getAsString();
				closed = root.has("closed") && root.get("closed").getAsBoolean();
				if (root.has("rotatesAt")) rotatesAt = root.get("rotatesAt").getAsLong();
				List<StoreItem> l = new ArrayList<>();
				for (var el : root.getAsJsonArray("items")) {
					var o = el.getAsJsonObject();
					l.add(new StoreItem(o.get("id").getAsString(), o.get("type").getAsString(),
							o.get("name").getAsString(), o.has("payload") ? o.get("payload").getAsString() : "",
							o.has("price") ? o.get("price").getAsString() : "",
							!o.has("inShop") || o.get("inShop").getAsBoolean(),
							o.has("returning") && o.get("returning").getAsBoolean(),
							o.has("owners") ? o.get("owners").getAsInt() : 0,
							o.has("wornBy") ? o.get("wornBy").getAsString() : ""));
				}
				l.sort((x, y) -> Integer.compare(rank(x), rank(y)));
				store = l;
			} catch (Exception ignored) {}
		});
	}

	private String rotateIn() {
		long ms = rotatesAt - System.currentTimeMillis();
		if (rotatesAt <= 0 || ms <= 0) return null;
		long d = ms / 86_400_000L, h = (ms / 3_600_000L) % 24, min = (ms / 60_000L) % 60;
		String t = d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + min + "m" : min + "m";
		return "§6⌛ rotates in " + t;
	}

	private static int rank(StoreItem s) {
		return switch (s.type()) { case "bundle" -> 0; case "bg" -> 1; case "nametag" -> 2; case "tag" -> 3;
			case "gradientname" -> 4; case "anytag", "anygui", "anygradient" -> 5; default -> 6; };
	}

	private static final String CUR = "$";

	private static final long THEME_TRY_MS = 5_000L;

	private void tryItem(StoreItem s) {
		if (s.type().equals("theme")) {
			MarketConfig.previewThemeFor(s.id(), Ui.strip(s.name()), s.payload(), THEME_TRY_MS);
			inspect = null; giftMode = false; giftField = null;
			if (this.client != null) this.client.execute(() -> this.client.setScreen(new StockScreen(null, "")));
			return;
		}

		preview(s); closeInspect();
	}

	private void preview(StoreItem s) { preview(s, true); }

	private void preview(StoreItem s, boolean fresh) {
		long now = System.currentTimeMillis();
		boolean plate = isPlate(s);
		long until = now + (plate ? PLATE_MS : PREVIEW_MS);
		if (until > previewEndsAt) { previewEndsAt = until; previewSpan = plate ? PLATE_MS : PREVIEW_MS; }

		if (fresh && plate) Nameplate.armClear(until);
		switch (s.type()) {
			case "theme" -> { MarketConfig.previewAccent = Ui.argb(s.payload(), 0xFFFF8FBF); MarketConfig.previewAccentUntil = until; }
			case "anygui" -> { MarketConfig.previewAccent = -1; MarketConfig.previewAccentUntil = until; }
			case "bg" -> { MarketConfig.previewBg = s.payload(); MarketConfig.previewBgUntil = until; }
			case "bundle" -> { for (String m : s.payload().split(",")) { StoreItem it = find(m); if (it != null) preview(it, false); } }
			default -> {
				String[] l = plateLook(s);
				if (!l[0].isEmpty()) Nameplate.armTag(l[0], until);
				if (!l[1].isEmpty()) Nameplate.armSpec(l[1], until);
			}
		}
	}

	private String[] plateLook(StoreItem it) {
		switch (it.type()) {
			case "tag" -> { return new String[]{ it.payload(), "" }; }
			case "nametag", "gradientname" -> { return new String[]{ "", it.payload().replace("#", "") }; }
			case "anytag" -> { return new String[]{ "", "rainbow" }; }
			case "anygradient" -> { return new String[]{ "", "rainbowgrad" }; }
			case "bundle" -> {
				String tg = "", sp = "";
				for (String mId : it.payload().split(",")) {
					StoreItem m = find(mId);
					if (m == null) continue;
					String[] l = plateLook(m);
					if (tg.isEmpty()) tg = l[0];
					if (sp.isEmpty()) sp = l[1];
				}
				return new String[]{ tg, sp };
			}
			default -> { return new String[]{ "", "" }; }
		}
	}

	private boolean isPlate(StoreItem it) { String[] l = plateLook(it); return !l[0].isEmpty() || !l[1].isEmpty(); }

	private void buy(StoreItem s) {
		if (this.client == null) return;
		boolean gift = giftMode && !giftName.isBlank();
		if (!gift && MarketConfig.ownsItem(s.id())) return;
		if (this.client.player != null) {

			String me = this.client.getSession() != null ? this.client.getSession().getUsername() : "your username";
			this.client.player.sendMessage(Text.literal(gift
					? "§d[Shop] §7Gifting §f" + Ui.strip(s.name()) + " §7to §b" + giftName.trim()
							+ "\n§7At checkout enter §b" + giftName.trim() + "§7 as the username — §fnot your own§7 — "
							+ "or it unlocks on your account instead. It arrives within a minute."
					: "§d[Shop] §7Buying §f" + Ui.strip(s.name())
							+ "\n§7At checkout enter your exact username §b" + me
							+ "§7. It unlocks automatically within a minute — no ticket needed."), false);
		}
		this.client.setScreen(new net.minecraft.client.gui.screen.ConfirmLinkScreen(ok -> {
			if (ok) net.minecraft.util.Util.getOperatingSystem().open(buyUrl);
			this.client.setScreen(this);
		}, buyUrl, true));
	}

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	private int toScreenX(double gx) { return (int) Math.round(this.width / 2.0 + (gx - this.width / 2.0) * effScale); }
	private int toScreenY(double gy) { return (int) Math.round(this.height / 2.0 + (gy - this.height / 2.0) * effScale); }

	@Override
	public boolean mouseScrolled(double mx2, double my2, double h, double v) {
		if (inspect != null) return true;
		double gy = toGuiY(my2), gx = toGuiX(mx2);
		if (gx >= px && gx <= px + pw && gy >= py && gy <= py + ph) {
			scrollTarget = clampScroll(scrollTarget - v * 64);
			return true;
		}
		return super.mouseScrolled(mx2, my2, h, v);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (giftMode && inspect != null) {
			if (input.key() == 256) { giftMode = false; giftName = ""; giftField = null; return true; }
			if (input.key() == 257 && !giftName.isBlank()) { StoreItem s = inspect; inspect = null; buy(s); return true; }
			if (giftField != null && giftField.keyPressed(input)) { giftName = giftField.getText(); return true; }
		}
		if (input.key() == 256 && inspect != null) { closeInspect(); return true; }
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharInput input) {
		if (giftMode && inspect != null) {
			if (giftField != null) { giftField.charTyped(input); giftName = giftField.getText(); }
			return true;
		}
		return super.charTyped(input);
	}

	@Override
	public void close() { Ui.escBack(this.client, parent); }

	private double clampScroll(double s) {
		int viewH = viewBot - viewTop;
		return Math.max(0, Math.min(s, Math.max(0, contentHeight() - viewH)));
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		if (NavBar.click(rc.x(), rc.y(), this)) return true;

		if (closed) return true;
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());
		if (inspect != null) {
			if (!inspectClosing) {
				if (in(btnTryR, gx, gy)) { tryItem(inspect); return true; }
				if (btnGiftR != null && in(btnGiftR, gx, gy)) {
					giftMode = !giftMode; giftName = "";
					if (giftMode) {
						giftField = new TextFieldWidget(this.textRenderer, 0, 0, 10, 12, Text.literal("IGN"));
						giftField.setMaxLength(16);
						giftField.setDrawsBackground(false);
						giftField.setEditable(true);
						giftField.setFocused(true);
						this.setFocused(null);
					} else giftField = null;
					return true;
				}
				if (btnBuyR != null && in(btnBuyR, gx, gy)) { StoreItem s = inspect; inspect = null; buy(s); return true; }
				if (modalR == null || !in(modalR, gx, gy)) { closeInspect(); return true; }
			}
			return true;
		}

		if (gy >= viewTop && gy <= viewBot) {

			if (in(requestRow, gx, gy)) { StyledButton.playClick(); openDiscord(); return true; }
			for (Hit hb : hits) if (gx >= hb.x1() && gx <= hb.x2() && gy >= hb.y1() && gy <= hb.y2()) {
				openInspect(hb.item(), hb.x1(), hb.y1(), hb.x2(), hb.y2());
				return true;
			}
		}
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	private static boolean in(int[] r, double x, double y) {
		return r != null && x >= r[0] && y >= r[1] && x <= r[2] && y <= r[3];
	}

	private void openInspect(StoreItem it, int x1, int y1, int x2, int y2) {
		inspect = it; inspectFrom = new int[]{ x1, y1, x2, y2 };
		inspectAt = System.currentTimeMillis(); inspectClosing = false;
		giftMode = false; giftName = "";
	}

	private void closeInspect() {
		if (inspect != null && !inspectClosing) { inspectClosing = true; inspectCloseAt = System.currentTimeMillis(); }
	}

	private int contentHeight() {
		if (store == null) return 0;
		int h = 0;
		List<StoreItem> bundles = byType("bundle");
		h += bundles.size() * (bundleH + 10);
		h += REQUEST_H + 10;
		List<StoreItem> items = buyables();
		if (items.isEmpty()) return h + 14 + 24;
		for (String[] cat : CATEGORIES) {
			int n = inCategory(items, cat[0]).size();
			if (n > 0) h += 16 + rowsOf(n) * (cardH + 8);
		}
		return h;
	}

	private static final String[][] CATEGORIES = {
		{ "theme", "GUI THEMES" }, { "nametag", "NAME COLOURS" }, { "gradientname", "GRADIENT NAMES" },
		{ "tag", "TAGS" }, { "bg", "BACKGROUNDS" }, { "unlock", "UNLOCKS" },
	};

	private List<StoreItem> inCategory(List<StoreItem> items, String key) {
		List<StoreItem> out = new ArrayList<>();
		for (StoreItem s : items) {
			boolean match = key.equals("unlock") ? s.type().startsWith("any") : s.type().equals(key);
			if (match) out.add(s);
		}
		return out;
	}

	private static int rowsOf(int n) { return (n + 3) / 4; }

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		int mx = (int) Math.round(toGuiX(mouseX)), my = (int) Math.round(toGuiY(mouseY));
		rawMx = mouseX; rawMy = mouseY;
		long nowMs = System.currentTimeMillis();
		double dt = Math.min(100, nowMs - lastAnimMs);
		lastAnimMs = nowMs;

		scrollTarget = clampScroll(scrollTarget);
		scrollPos += (scrollTarget - scrollPos) * (1 - Math.exp(-dt / 80.0));
		if (Math.abs(scrollPos - scrollTarget) < 0.25) scrollPos = scrollTarget;

		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;

		effScale = uiScale * Ui.openPop(openedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) effScale, (float) effScale); m.translate(-cxp, -cyp);
		try { renderInner(ctx, mx, my, delta); } finally { m.popMatrix(); }
		NavBar.draw(ctx, this.textRenderer, "shop", this.width, this.height, px, py, pw, uiScale);
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {

		final int stageMx = mx, stageMy = my;
		if (inspect != null) { mx = -9999; my = -9999; }
		hits.clear();
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ Shop", "§7cosmetics & flair");

		if (store == null) {
			Ui.shimmerCard(ctx, cl, viewTop + 4, cr - cl, featH);
			for (int i = 0; i < 4; i++)
				Ui.shimmerCard(ctx, cl + i * (cardW + 8), viewTop + featH + 24, cardW, cardH);
			ctx.fill(px + 1, py + ph - 20, px + pw - 1, py + ph - 1, 0x50000000);

			if (!fetched && System.currentTimeMillis() >= nextFetchAt) { fetched = true; fetchStore(); }
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(fetchFailed ? "§7couldn't reach the store §8— retrying…" : "§8fetching the catalogue…"),
					px + pw / 2, py + ph - 15, 0xFFFFFFFF);
			super.render(ctx, mx, my, delta);
			return;
		}

		ctx.enableScissor(px + 1, viewTop, px + pw - 1, viewBot);
		try {
			int y = viewTop + 4 - (int) Math.round(scrollPos);
			List<StoreItem> bundles = byType("bundle");
			StoreItem feat = featured();

			List<StoreItem> ordered = new ArrayList<>();
			if (feat != null) ordered.add(feat);
			for (StoreItem b : bundles) if (b != feat) ordered.add(b);
			for (StoreItem b : ordered) {
				drawBundlePage(ctx, b, cl, y, cr - cl, bundleH, mx, my);
				y += bundleH + 10;
			}
			List<StoreItem> items = buyables();
			if (items.isEmpty()) {
				Ui.sectionHead(ctx, this.textRenderer, cl, y, cr, "§7THIS WEEK'S STOCK", rotateIn(), C_ACCENT);
				y += 14;
				ctx.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal("§a✓ §7you own every single item — bundles above"),
						px + pw / 2, y + 8, 0xFFFFFFFF);
			} else {

				boolean firstCat = true;
				for (String[] cat : CATEGORIES) {
					List<StoreItem> group = inCategory(items, cat[0]);
					if (group.isEmpty()) continue;
					Ui.sectionHead(ctx, this.textRenderer, cl, y, cr, "§7" + cat[1],
							firstCat ? rotateIn() : null, C_ACCENT);
					firstCat = false;
					y += 16;
					for (int i = 0; i < group.size(); i++)
						drawCard(ctx, group.get(i), cl + (i % 4) * (cardW + 8), y + (i / 4) * (cardH + 8), mx, my);
					y += rowsOf(group.size()) * (cardH + 8);
				}
			}

			drawRequestRow(ctx, cl, y, cr - cl, mx, my);
		} finally { ctx.disableScissor(); }

		int viewH = viewBot - viewTop, contentH = contentHeight();
		if (contentH > viewH) {
			ctx.fill(cr + 6, viewTop, cr + 8, viewBot, 0x28000000);
			int thumbH = Math.max(16, viewH * viewH / contentH);
			int thumbY = viewTop + (int) Math.round((viewH - thumbH) * (scrollPos / (contentH - viewH)));
			ctx.fill(cr + 6, thumbY, cr + 8, thumbY + thumbH, 0xCC000000 | (C_ACCENT & 0xFFFFFF));
		}

		ctx.fill(px + 1, py + ph - 20, px + pw - 1, py + ph - 1, 0x50000000);
		ctx.fill(px + 1, py + ph - 20, px + pw - 1, py + ph - 19, 0x22FFFFFF);
		long now = System.currentTimeMillis();
		if (this.client != null && this.client.player != null && Nameplate.previewLive()) {

			var t = Ui.fitText(this.textRenderer, this.client.player.getDisplayName(), pw - 130);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8above your head:  ").copy().append(t),
					px + pw / 2, py + ph - 15, 0xFFFFFFFF);
		} else {

			String hint = "§8click anything to §finspect §8·  §7▶ try it live  ·  §6buy it";
			if (!MarketConfig.loyaltyGranted && MarketConfig.loyaltyDays >= 0) {
				int left = Math.max(0, 5 - MarketConfig.loyaltyDays);
				hint = "§7Open the market for §f" + left + " more day" + (left == 1 ? "" : "s")
						+ " §7to earn a §afree name colour §8(" + MarketConfig.loyaltyDays + "/5)";
			} else if (MarketConfig.loyaltyGranted)
				hint = "§a✓ You earned the free name colour §8— apply it in Style";
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint), px + pw / 2, py + ph - 15, 0xFFFFFFFF);
		}
		if (now < previewEndsAt) {
			int barW = (int) ((pw - 2) * Math.max(0, previewEndsAt - now) / (double) previewSpan);
			Ui.hGrad(ctx, px + 1, py + ph - 1, px + 1 + barW, py + ph, 0xFF000000 | (C_ACCENT & 0xFFFFFF),
					0x66000000 | (C_ACCENT & 0xFFFFFF), 6);
		}

		super.render(ctx, mx, my, delta);
		drawInspect(ctx, stageMx, stageMy);
		if (closed) drawClosed(ctx);
	}

	private static double easeOut(double t) { return 1 - Math.pow(1 - Math.max(0, Math.min(1, t)), 3); }

	private void drawClosed(DrawContext ctx) {
		ctx.fill(px, py, px + pw, py + ph, 0xE60A0F18);
		for (int i = 0; i < 3; i++)
			ctx.fillGradient(px, py, px + pw, py + ph, 0x300A0F18, 0x600A0F18);
		int cx = px + pw / 2, cy = py + ph / 2;
		Ui.radialGlow(ctx, cx, cy, Math.min(pw, ph) / 3, C_ACCENT, 0x22);
		var m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(cx, cy); m.scale(2f, 2f); m.translate(-cx, -cy);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§fCOMING SOON"), cx, cy - 4, 0xFFFFFFFF);
		m.popMatrix();
	}

	private void drawInspect(DrawContext ctx, int mx, int my) {
		if (inspect == null) return;
		long now = System.currentTimeMillis();
		double t;
		if (!inspectClosing) t = easeOut((now - inspectAt) / 170.0);
		else {
			double tc = (now - inspectCloseAt) / 150.0;
			if (tc >= 1) { inspect = null; modalR = btnTryR = btnBuyR = btnGiftR = null; return; }
			t = 1 - easeOut(tc);
		}
		ctx.fill(px, py, px + pw, py + ph, ((int) (0x9A * t)) << 24);

		boolean armsPlate = isPlate(inspect);
		boolean plate = armsPlate && !inspect.type().equals("bundle");

		boolean wideArt = !plate && (inspect.type().equals("theme") || inspect.type().equals("bundle"));

		int th = inspect.type().equals("bundle") ? 200 : plate ? 140 : 152;
		int tw = plate ? 440 : (wideArt ? 532 : 400);
		int tx = px + (pw - tw) / 2, ty = py + (ph - th) / 2;
		int x1 = (int) Math.round(inspectFrom[0] + (tx - inspectFrom[0]) * t);
		int y1 = (int) Math.round(inspectFrom[1] + (ty - inspectFrom[1]) * t);
		int x2 = (int) Math.round(inspectFrom[2] + (tx + tw - inspectFrom[2]) * t);
		int y2 = (int) Math.round(inspectFrom[3] + (ty + th - inspectFrom[3]) * t);
		int tier = tierColor(inspect);
		StyledButton.roundFill(ctx, x1 + 3, y1 + 4, x2 - x1, y2 - y1, 0x66000000);
		StyledButton.roundFill(ctx, x1, y1, x2 - x1, y2 - y1, 0xFF090D14);
		StyledButton.roundBorder(ctx, x1, y1, x2 - x1, y2 - y1, 0xFF000000 | (tier & 0xFFFFFF));
		modalR = new int[]{ x1, y1, x2, y2 };

		if (t < 0.92) { btnTryR = btnBuyR = btnGiftR = null; return; }

		boolean owned = MarketConfig.ownsItem(inspect.id());

		int heroW = plate ? 200 : (wideArt ? 264 : 132);
		if (plate) drawNameplateStage(ctx, inspect, x1, y1, heroW, y2 - y1 - 1);
		else drawHero(ctx, inspect, x1, y1, heroW, y2 - y1 - 1);
		ctx.fill(x1 + heroW, y1 + 1, x1 + heroW + 1, y2 - 1, 0x28FFFFFF);
		int rx = x1 + heroW + 10, rw = x2 - 10 - rx;

		String disp = inspect.type().startsWith("any") ? Ui.strip(inspect.name()) : Ui.shortName(inspect.name(), inspect.type());
		String[] lines = twoLines(disp, rw);

		int nameCol = itemColour(inspect);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§l" + lines[0]), rx, y1 + 10, nameCol);
		int cy = y1 + 24;
		if (!lines[1].isEmpty()) {
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§l" + lines[1]), rx, cy, nameCol);
			cy += 14;
		}

		String meta = inspect.owners() > 0
				? "§7" + inspect.owners() + (inspect.owners() == 1 ? " owner" : " owners") : "";
		if (!meta.isEmpty()) { ctx.drawTextWithShadow(this.textRenderer, Text.literal(meta), rx, cy, 0xFFFFFFFF); cy += 15; }
		else cy += 4;

		if (inspect.type().equals("bundle")) {

			String save = savingsLabel(inspect);
			if (save != null) { Ui.savePill(ctx, this.textRenderer, rx, cy, "§a" + save); cy += 14; }
			java.util.List<StoreItem> mem = new java.util.ArrayList<>();
			for (String mId : inspect.payload().split(",")) {
				StoreItem itm = find(mId);
				if (itm != null) mem.add(itm);
			}
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7you get all " + mem.size() + ":"), rx, cy, 0xFFFFFFFF);
			cy += 11;

			int rowsPer = Math.max(1, (y2 - 30 - cy) / 11);
			int cols = mem.size() > rowsPer ? 2 : 1;
			int colW = rw / cols, maxShow = rowsPer * cols;
            for (int i = 0; i < Math.min(mem.size(), maxShow); i++) {
				StoreItem itm = mem.get(i);
				int mx2 = rx + (i / rowsPer) * colW, my2 = cy + (i % rowsPer) * 11;
				drawMiniSwatch(ctx, itm, mx2, my2);

				String nm = Ui.fit(this.textRenderer, memberLabel(itm), colW - 16);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + nm), mx2 + 13, my2 + 1, 0xFFFFFFFF);
			}
			if (mem.size() > maxShow)
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8+" + (mem.size() - maxShow) + " more"),
						rx, cy + rowsPer * 11, 0xFFFFFFFF);
		}

		int byy = y2 - 25;

		boolean canTry = !inspect.type().equals("bundle");
		String tryLbl = inspect.type().equals("theme")
				? "▶ Try " + THEME_TRY_MS / 1000 + "s"
				: "▶ Try " + (armsPlate ? PLATE_MS : PREVIEW_MS) / 1000 + "s";
		int tryW = canTry ? this.textRenderer.getWidth(tryLbl) + 14 : 0;
		if (canTry) {
		btnTryR = new int[]{ rx, byy, rx + tryW, byy + 17 };
		boolean hovT = in(btnTryR, mx, my);
		StyledButton.roundFill(ctx, rx, byy, tryW, 17, hovT ? 0x50FFFFFF : 0x2AFFFFFF);
		StyledButton.roundBorder(ctx, rx, byy, tryW, 17, hovT ? 0xCCFFFFFF : 0x55FFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + tryLbl), rx + 7, byy + 5, 0xFFFFFFFF);
		} else btnTryR = null;

		String gl = giftMode ? "🎁 Gifting" : "🎁 Gift";
		int giftW = this.textRenderer.getWidth(gl) + 14;
		int gx2 = canTry ? rx + tryW + 8 : rx;
		btnGiftR = new int[]{ gx2, byy, gx2 + giftW, byy + 17 };
		boolean hovG = in(btnGiftR, mx, my);
		StyledButton.roundFill(ctx, gx2, byy, giftW, 17, giftMode ? 0x66D46BFF : hovG ? 0x50D46BFF : 0x2AD46BFF);
		StyledButton.roundBorder(ctx, gx2, byy, giftW, 17, giftMode || hovG ? 0xFFE09BFF : 0x88D46BFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§d" + gl), gx2 + 7, byy + 5, 0xFFFFFFFF);

		boolean canBuy = !owned || giftMode;
		if (canBuy) {
			String pl = (giftMode ? "Gift " : "Buy ") + (inspect.price().isEmpty() ? "" : CUR + inspect.price());
			int buyW = this.textRenderer.getWidth(pl) + 16;
			int bx = gx2 + giftW + 8;
			btnBuyR = new int[]{ bx, byy, bx + buyW, byy + 17 };
			boolean hovB = in(btnBuyR, mx, my);
			boolean ready = !giftMode || !giftName.isBlank();
			int fill = ready ? (hovB ? 0x66FFA132 : 0x3AFFA132) : 0x18FFA132;
			StyledButton.roundFill(ctx, bx, byy, buyW, 17, fill);
			StyledButton.roundBorder(ctx, bx, byy, buyW, 17, ready ? (hovB ? 0xFFFFC46B : 0xAAFFA132) : 0x55FFA132);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal((ready ? "§6" : "§8") + pl), bx + 8, byy + 5, 0xFFFFFFFF);
			if (!ready) btnBuyR = null;
		} else {
			btnBuyR = null;
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§a✓ Owned"), gx2 + giftW + 10, byy + 5, 0xFFFFFFFF);
		}

		if (giftMode) {
			int fy = byy - 22;
			StyledButton.roundFill(ctx, rx, fy, x2 - 10 - rx, 18, 0xFF0A0F18);
			StyledButton.roundBorder(ctx, rx, fy, x2 - 10 - rx, 18, 0x88D46BFF);
			String lead = "§d🎁 §7to: ";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(lead), rx + 6, fy + 5, 0xFFFFFFFF);
			if (giftField != null) {

				int lw = this.textRenderer.getWidth(Ui.strip(lead)) + 8;
				giftField.setX(rx + lw); giftField.setY(fy + 5);
				giftField.setWidth(Math.max(24, (x2 - 10 - rx) - lw - 6));
				giftField.render(ctx, mx, my, 0f);
				if (giftField.getText().isEmpty())
					ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8type their exact IGN…"),
							rx + lw, fy + 5, 0xFFFFFFFF);
			}
		}
	}

	private void drawBundlePage(DrawContext ctx, StoreItem b, int x, int y, int w, int h, int mx, int my) {
		boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
		boolean owned = MarketConfig.ownsItem(b.id());
		List<String> tags = new ArrayList<>(), colours = new ArrayList<>(), grads = new ArrayList<>();
		String themeArt = null;
		String accent = null;
		for (String mId : b.payload().split(",")) {
			StoreItem it = find(mId);
			if (it == null) continue;
			switch (it.type()) {
				case "tag" -> tags.add(it.payload().trim());
				case "nametag" -> colours.add(it.payload().replace("#", ""));
				case "anytag" -> colours.add("rainbow");
				case "gradientname" -> grads.add(it.payload().replace("#", ""));
				case "anygradient" -> grads.add("rainbowgrad");
				case "theme" -> { if (accent == null) accent = it.payload();
					if (themeArt == null) themeArt = "card_" + it.id().toLowerCase(Locale.ROOT); }
				case "anygui" -> { if (accent == null) accent = "rainbow"; }
			}
		}
		int acc = accent == null ? C_ACCENT : ("rainbow".equals(accent) ? Ui.rainbowPair()[0] : Ui.argb(accent, C_ACCENT));
		String me = this.client != null && this.client.getSession() != null
				? this.client.getSession().getUsername() : "You";

		Ui.card(ctx, x, y, w, h, hov ? (0xFF000000 | (acc & 0xFFFFFF)) : 0x33FFFFFF);
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF0C121D, 0xFF06080F);
		Ui.radialGlow(ctx, x + w / 2, y + h / 2, Math.max(60, w / 3), acc, 0x1E);
		Ui.starDrift(ctx, x + 2, y + 2, w - 4, h - 4, b.id().hashCode(), 14, acc);

		ctx.drawTextWithShadow(this.textRenderer,
				Text.literal("§f§l" + Ui.fit(this.textRenderer, Ui.strip(b.name()), w - 150)), x + 10, y + 8, 0xFFFFFFFF);
		String save = savingsLabel(b);
		if (save != null) Ui.savePill(ctx, this.textRenderer, x + 10, y + 21, "§a" + save);
		String chip = owned ? "§a✓ Owned"
				: (b.price().isEmpty() ? "§6Buy" : "§6" + CUR + b.price());
		int chipW = this.textRenderer.getWidth(Ui.strip(chip)) + 16;
		StyledButton.roundFill(ctx, x + w - chipW - 10, y + 7, chipW, 17, owned ? 0x3320C060 : 0x33FFA132);
		StyledButton.roundBorder(ctx, x + w - chipW - 10, y + 7, chipW, 17, owned ? 0x8820C060 : 0x99FFA132);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(chip), x + w - chipW - 2, y + 12, 0xFFFFFFFF);

		String ownArt = "card_" + b.id().toLowerCase(Locale.ROOT);
		float ar = ThemeBg.aspectOf(ownArt);
		if (ar <= 0f && themeArt != null) ar = ThemeBg.aspectOf(themeArt);
		if (ar <= 0f) ar = 1.74f;
		int aw = (int) (w * 0.44f), ah = Math.round(aw / ar), maxH = (int) (h * 0.50f);
		if (ah > maxH) { ah = maxH; aw = Math.round(ah * ar); }
		int ax = x + (w - aw) / 2, ay = y + (h - ah) / 2 + 4;

		if (ThemeBg.drawThumb(ctx, ownArt, ax, ay, aw, ah)
				|| (themeArt != null && ThemeBg.drawThumb(ctx, themeArt, ax, ay, aw, ah)))

		if (tags.size() > 0) drawTiltedTag(ctx, x + 42, y + 38, tags.get(0), -7f);
		if (colours.size() > 0) drawTiltedName(ctx, x + w - 150, y + 44, me, colours.get(0), 6f);
		if (grads.size() > 0) drawTiltedName(ctx, x + w / 2 - 45, y + h - 30, me, grads.get(0), -4f);
		if (tags.size() > 1) drawTiltedTag(ctx, x + 24, y + h - 48, tags.get(1), 6f);
		if (grads.size() > 1) drawTiltedName(ctx, x + w - 152, y + h - 54, me, grads.get(1), -6f);
		if (tags.size() > 2) drawTiltedTag(ctx, x + w / 2 - 12, y + 30, tags.get(2), 4f);
		if (grads.size() > 2) drawTiltedName(ctx, x + 28, y + 74, me, grads.get(2), 5f);

		hits.add(new Hit(x, y, x + w, y + h, b, false));
	}

	private void drawPhotoFrame(DrawContext ctx, int x, int y, int w, int h, int accent, float deg) {
		var m = ctx.getMatrices();
		m.pushMatrix();
		float cxp = x + w / 2f, cyp = y + h / 2f;
		m.translate(cxp, cyp); m.rotate((float) Math.toRadians(deg)); m.translate(-cxp, -cyp);
		StyledButton.roundFill(ctx, x + 3, y + 4, w, h, 0x77000000);
		StyledButton.roundFill(ctx, x, y, w, h, 0xFF0B111B);
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, 0x14FFFFFF, 0x00000000);
		StyledButton.roundBorder(ctx, x, y, w, h, 0xFF000000 | (accent & 0xFFFFFF));
		StyledButton.roundBorder(ctx, x + 3, y + 3, w - 6, h - 6, 0x22FFFFFF);
		m.popMatrix();
	}

	private void drawTiltedTag(DrawContext ctx, int x, int y, String tag, float deg) {

		String glyph = tag.replaceAll("(?i)§r", "").trim();
		final float s = 1.45f * Ui.tagScale(glyph);
		int gw = (int) Math.ceil(this.textRenderer.getWidth(Ui.strip(glyph)) * s);
		int tw = gw + 18, th = (int) (8 * s) + 10;
		var m = ctx.getMatrices();
		m.pushMatrix();
		float cxp = x + tw / 2f, cyp = y + th / 2f;
		m.translate(cxp, cyp); m.rotate((float) Math.toRadians(deg)); m.translate(-cxp, -cyp);
		StyledButton.roundFill(ctx, x + 2, y + 3, tw, th, 0x66000000);
		StyledButton.roundFill(ctx, x, y, tw, th, 0xE60A0F18);
		StyledButton.roundBorder(ctx, x, y, tw, th, 0x33FFFFFF);

		m.translate(cxp, cyp); m.scale(s, s);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(glyph), 0, -4, 0xFFFFFFFF);
		m.popMatrix();
	}

	private void drawTiltedName(DrawContext ctx, int x, int y, String name, String colourSpec, float deg) {
		var t = Ui.colouredName(name, colourSpec);
		int tw = this.textRenderer.getWidth(t) + 14, th = 15;
		var m = ctx.getMatrices();
		m.pushMatrix();
		float cxp = x + tw / 2f, cyp = y + th / 2f;
		m.translate(cxp, cyp); m.rotate((float) Math.toRadians(deg)); m.translate(-cxp, -cyp);
		StyledButton.roundFill(ctx, x + 2, y + 3, tw, th, 0x66000000);
		StyledButton.roundFill(ctx, x, y, tw, th, 0xE60A0F18);
		StyledButton.roundBorder(ctx, x, y, tw, th, 0x33FFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, t, x + 7, y + 4, 0xFFFFFFFF);
		m.popMatrix();
	}

	private void drawBundleShowcase(DrawContext ctx, StoreItem b, int x, int y, int w, int h) {
		List<String> tags = new ArrayList<>(), colours = new ArrayList<>(), grads = new ArrayList<>();
		String art = null, accent = null;
		for (String mId : b.payload().split(",")) {
			StoreItem it = find(mId);
			if (it == null) continue;
			switch (it.type()) {
				case "tag" -> tags.add(it.payload().trim());
				case "nametag" -> colours.add(it.payload().replace("#", ""));
				case "anytag" -> colours.add("rainbow");
				case "gradientname" -> grads.add(it.payload().replace("#", ""));
				case "anygradient" -> grads.add("rainbowgrad");

				case "theme" -> { if (accent == null) accent = it.payload(); if (art == null) art = "card_" + it.id().toLowerCase(Locale.ROOT); }
				case "bg" -> { if (art == null) art = it.payload(); }
				case "anygui" -> { if (accent == null) accent = "rainbow"; }
			}
		}
		int acc = accent == null ? C_ACCENT : ("rainbow".equals(accent) ? Ui.rainbowPair()[0] : Ui.argb(accent, C_ACCENT));
		boolean hasTop = !tags.isEmpty() || !colours.isEmpty(), hasBot = !grads.isEmpty();

		int topH = hasTop ? Math.max(14, h / 5) : 0;
		int botH = hasBot ? Math.max(12, h / 6) : 0;
		int midY = y + topH, midH = h - topH - botH;

		String ownArt = "card_" + b.id().toLowerCase(Locale.ROOT);
		boolean drew = ThemeBg.drawThumb(ctx, ownArt, x + 1, midY, w - 2, midH)
				|| (art != null && ThemeBg.drawThumb(ctx, art, x + 1, midY, w - 2, midH));
		if (!drew) drawMiniGui(ctx, x + 1, midY, w - 2, midH, acc);
		ctx.fillGradient(x + 1, midY + midH - 8, x + w - 1, midY + midH, 0x00000000, 0x77000000);

		if (hasTop) {
			ctx.fill(x + 1, y + 1, x + w - 1, y + topH, 0xCC070B12);
			int half = w / 2;
			if (!tags.isEmpty()) {
				int tx = x + 5;
				for (String g : tags) {
					if (tx > x + half - 10) break;
					ctx.drawTextWithShadow(this.textRenderer, Text.literal(g), tx, y + (topH - 8) / 2, 0xFFFFFFFF);
					tx += this.textRenderer.getWidth(Ui.strip(g)) + 3;
				}
			}

			if (!colours.isEmpty()) {
				int sw = 8, gap = 2;
				int cxr = x + w - 5 - Math.min(colours.size(), 6) * (sw + gap);
				for (int i = 0; i < Math.min(colours.size(), 6); i++) {
					int cy2 = y + (topH - sw) / 2;
					if ("rainbow".equals(colours.get(i))) { int[] rb = Ui.rainbowPair(); Ui.gradStrip(ctx, cxr, cy2, sw, sw, rb[0], rb[1]); }
					else Ui.chip(ctx, cxr, cy2, sw, Ui.argb(colours.get(i), 0xFF8899AA));
					cxr += sw + gap;
				}
			}
			ctx.fill(x + 1, y + topH, x + w - 1, y + topH + 1, (acc & 0xFFFFFF) | 0x55000000);
		}

		if (hasBot) {
			int by = y + h - botH;
			ctx.fill(x + 1, by, x + w - 1, y + h, 0xCC070B12);
			int n = Math.min(grads.size(), 3), sh = Math.max(3, (botH - 4) / n);
			int gy = by + 2;
			for (int i = 0; i < n; i++) {
				String g = grads.get(i);
				int c1, c2;
				if ("rainbow".equals(g)) { int[] rb = Ui.rainbowPair(); c1 = rb[0]; c2 = rb[1]; }
				else { String[] p = g.split("-", 2); c1 = Ui.argb(p[0], 0xFFFF8FBF); c2 = Ui.argb(p.length > 1 ? p[1] : p[0], 0xFFFFD34A); }
				Ui.hGrad(ctx, x + 4, gy, x + w - 4, gy + sh, c1, c2, 3);
				gy += sh + 1;
			}
		}
	}

	private void drawCard(DrawContext ctx, StoreItem it, int x, int y, int mx, int my) {
		boolean hov = mx >= x && mx < x + cardW && my >= y && my < y + cardH;
		int tier = tierColor(it);
		boolean owned = MarketConfig.ownsItem(it.id());
		Ui.card(ctx, x, y, cardW, cardH, hov ? (0xFF000000 | (tier & 0xFFFFFF)) : 0x24FFFFFF);
		if (it.type().equals("bundle")) drawBundleShowcase(ctx, it, x, y, cardW, heroH);
		else drawHero(ctx, it, x, y, cardW, heroH);
		if (hov) ctx.fill(x + 1, y + 1, x + cardW - 1, y + heroH, 0x10FFFFFF);
		ctx.fill(x + 2, y + 1, x + cardW - 2, y + 2, 0x1EFFFFFF);
		if (hov) StyledButton.roundBorder(ctx, x - 1, y - 1, cardW + 2, cardH + 2, (tier & 0xFFFFFF) | 0x55000000);

		ctx.fill(x + 1, y + heroH, x + cardW - 1, y + heroH + 2, 0xFF000000 | (tier & 0xFFFFFF));
		ctx.fill(x + 1, y + heroH + 2, x + cardW - 1, y + cardH - 1, 0xFF0A0E14);
		String disp = it.type().startsWith("any") ? Ui.strip(it.name()) : Ui.shortName(it.name(), it.type());
		String[] lines = twoLines(disp, cardW - 12);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + lines[0]), x + 6, y + heroH + 7, 0xFFFFFFFF);
		if (!lines[1].isEmpty())
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7" + lines[1]), x + 6, y + heroH + 17, 0xFFFFFFFF);

		String tl = "§8" + typeLabel(it.type());
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(tl), x + 6, y + cardH - 12, 0xFFFFFFFF);

		String pr;
		if (owned) pr = "§a✓";
		else {
			Double save = it.type().equals("bundle") ? savings(it) : null;
			String base = it.price().isEmpty() ? "Buy" : CUR + it.price();
			pr = save != null ? "§8§m" + fmtMoney(save + parsePrice(it.price())) + "§r §6" + base : "§6" + base;
		}
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(pr),
				x + cardW - 6 - Ui.widthNoCodes(this.textRenderer, pr), y + cardH - 12, 0xFFFFFFFF);

		if (it.returning()) {
			int bw = this.textRenderer.getWidth("BACK") + 8;
			StyledButton.roundFill(ctx, x + cardW - bw - 4, y + 4, bw, 11, 0xD0161006);
			StyledButton.roundBorder(ctx, x + cardW - bw - 4, y + 4, bw, 11, 0xCCFFA132);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§6BACK"), x + cardW - bw, y + 6, 0xFFFFFFFF);
		}
		hits.add(new Hit(x, y, x + cardW, y + cardH, it, false));
	}

	private static double parsePrice(String p) {
		try { return Double.parseDouble(p.trim()); } catch (Exception e) { return 0; }
	}

	private static String fmtMoney(double v) {
		return CUR + (v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v));
	}

	private void drawMiniSwatch(DrawContext ctx, StoreItem it, int x, int y) {
		switch (it.type()) {
			case "nametag", "theme" -> Ui.chip(ctx, x, y, 9, Ui.argb(it.payload(), 0xFF8899AA));
			case "gradientname" -> {
				String[] p = it.payload().replace("#", "").split("-", 2);
				Ui.gradStrip(ctx, x, y, 9, 9, Ui.argb(p[0], 0xFFFF8FBF), Ui.argb(p.length > 1 ? p[1] : p[0], 0xFFFFD34A));
			}
			case "anytag", "anygui", "anygradient" -> { int[] rb = Ui.rainbowPair(); Ui.gradStrip(ctx, x, y, 9, 9, rb[0], rb[1]); }
			case "bg" -> {
				if (!ThemeBg.drawThumb(ctx, it.payload(), x, y, 9, 9)) ctx.fill(x, y, x + 9, y + 9, 0xFF3B4B63);
			}
			case "tag" -> {

				String raw = it.payload();
				String vis = Ui.strip(raw).trim();
				int ci = raw.indexOf((char) 0xA7);
				String code = (ci >= 0 && ci + 1 < raw.length()) ? raw.substring(ci, ci + 2) : "";
				int gw = this.textRenderer.getWidth(vis);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(code + vis),
						x + Math.max(0, (9 - gw) / 2), y + 1, 0xFFFFFFFF);
			}
			default -> Ui.chip(ctx, x, y, 9, 0xFF8899AA);
		}
	}

	private void drawNameplateStage(DrawContext ctx, StoreItem it, int x, int y, int w, int hh) {

		{
			ctx.fillGradient(x + 1, y + 1, x + w - 1, y + hh, 0xFF141A2B, 0xFF05070E);
			Ui.radialGlow(ctx, x + w / 2, y + hh / 2 + 8, Math.min(w / 2 - 6, 44), tierColor(it), 0x30);
			Ui.starDrift(ctx, x + 2, y + 2, w - 4, hh - 4, it.id().hashCode(), 8, tierColor(it));
		}
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + hh, 0x33000000, 0x88000000);

		int mw = 52, mh = 60;
		int groundY = y + hh / 2 + (mh + 10) / 2;
		Ui.radialGlow(ctx, x + w / 2, groundY, 30, 0xFF000000, 0x4A);
		boolean drew = false;
		if (!modelOff && this.client != null && this.client.player != null && this.client.world != null) {
			int gx1 = x + w / 2 - mw / 2;
			try {

				net.minecraft.client.gui.screen.ingame.InventoryScreen.drawEntity(ctx,
						toScreenX(gx1), toScreenY(groundY - mh), toScreenX(gx1 + mw), toScreenY(groundY),
						Math.max(1, (int) Math.round(30 * effScale)), 0.0625f, rawMx, rawMy, this.client.player);
				drew = true;
			} catch (Throwable t) { modelOff = true; }

		}
		if (!drew) PlayerHeads.draw(ctx, Nameplate.selfName(), x + w / 2 - 16, groundY - 44, 32);

		String[] look = plateLook(it);
		Text plate = Nameplate.compose(Nameplate.selfPlateSource(), Nameplate.selfName(), true, look[0], look[1], 0);
		int ptw = Math.max(1, this.textRenderer.getWidth(plate));
		float s = Math.min(1f, (w - 12) / (float) ptw);
		int py2 = groundY - mh - 10;
		var m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(x + w / 2f, py2);
		m.scale(s, s);

		ctx.fill(-ptw / 2 - 2, -2, ptw / 2 + 2, 10, 0x40000000);
		ctx.drawTextWithShadow(this.textRenderer, plate, -ptw / 2, 0, 0xFFFFFFFF);
		m.popMatrix();

	}

	private void drawHero(DrawContext ctx, StoreItem it, int x, int y, int w, int hh) {
		int hy2 = y + hh;

		boolean previewOnly = switch (it.type()) {
			case "nametag", "gradientname", "tag", "anytag", "anygradient" -> true;
			default -> false;
		};

		boolean fit = it.type().equals("bundle");
		if (!previewOnly && (fit
				? ThemeBg.drawFitted(ctx, "card_" + it.id().toLowerCase(Locale.ROOT), x + 1, y + 1, w - 2, hh - 1)
				: ThemeBg.drawThumb(ctx, "card_" + it.id().toLowerCase(Locale.ROOT), x + 1, y + 1, w - 2, hh - 1))) {
			Ui.hGrad(ctx, x + 1, y + 1, x + 1 + (w - 2) * 2 / 3, hy2, 0x24FFFFFF, 0x00000000, 3);
			ctx.fillGradient(x + 1, hy2 - 8, x + w - 1, hy2, 0x00000000, 0x55000000);
			return;
		}
		switch (it.type()) {
			case "bg" -> {
				if (!ThemeBg.drawThumb(ctx, it.payload(), x + 1, y + 1, w - 2, hh - 1))
					ctx.fill(x + 1, y + 1, x + w - 1, hy2, 0xFF3B4B63);
			}

			case "theme" -> drawColourWash(ctx, x, y, w, hh, Ui.argb(it.payload(), 0xFF8899AA), it.id().hashCode());
			case "anygui" -> {
				int[] rb = Ui.rainbowPair();
				Ui.hGrad(ctx, x + 1, y + 1, x + w - 1, hy2, (rb[0] & 0xFFFFFF) | 0xCC000000,
						(rb[1] & 0xFFFFFF) | 0xCC000000, 3);
				Ui.starDrift(ctx, x + 2, y + 2, w - 4, hh - 4, 7, 8, rb[1]);
			}
			case "nametag", "gradientname" -> drawNameScene(ctx, x, y, w, hh, it.payload().replace("#", ""), "");
			case "anytag" -> drawNameScene(ctx, x, y, w, hh, "rainbow", "");
			case "anygradient" -> drawNameScene(ctx, x, y, w, hh, "rainbowgrad", "");
			case "tag" -> drawNameScene(ctx, x, y, w, hh, null, it.payload().trim());
			case "bundle" -> {

				String[] lk = bundleLook(it);
				boolean art = lk[2] != null && ThemeBg.drawThumb(ctx, lk[2], x + 1, y + 1, w - 2, hh - 1);
				if (!art) ctx.fillGradient(x + 1, y + 1, x + w - 1, hy2, 0xFF171126, 0xFF08060E);
				ctx.fillGradient(x + 1, y + 1, x + w - 1, hy2, 0x55000000, 0x99000000);
				if (!art) {
					Ui.radialGlow(ctx, x + w / 2, y + hh / 2, Math.min(w / 2 - 6, 34), 0xFFFFA132, 0x26);
					Ui.starDrift(ctx, x + 2, y + 2, w - 4, hh - 4, it.id().hashCode(), 8,
								lk[3] != null ? ("rainbow".equals(lk[3]) ? Ui.rainbowPair()[0] : Ui.argb(lk[3], 0xFFFFA132)) : 0xFFFFA132);
				}
				if (lk[0] != null || !lk[1].isEmpty()) drawNameSample(ctx, x, y, w, hh, lk[0], lk[1]);
				else if (lk[3] != null) drawMiniGui(ctx, x, y, w, hh,
						"rainbow".equals(lk[3]) ? Ui.rainbowPair()[0] : Ui.argb(lk[3], 0xFFFFD34A));
				ctx.drawTextWithShadow(this.textRenderer,
						Text.literal("§7" + it.payload().split(",").length + " items"), x + 5, y + 4, 0xFFFFFFFF);
			}
			default -> ctx.fill(x + 1, y + 1, x + w - 1, hy2, 0xFF23304A);
		}

		Ui.hGrad(ctx, x + 1, y + 1, x + 1 + (w - 2) * 2 / 3, hy2, 0x24FFFFFF, 0x00000000, 3);
		ctx.fillGradient(x + 1, hy2 - 8, x + w - 1, hy2, 0x00000000, 0x55000000);
	}

	private void drawRequestRow(DrawContext ctx, int x, int y, int w, int mx, int my) {
		final int h = REQUEST_H;
		boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;

		float pulse = 0.5f + 0.5f * (float) Math.sin((System.currentTimeMillis() % 2600L) / 2600.0 * Math.PI * 2);
		int aFill = hov ? 0x52 : (int) (0x2C + 0x10 * pulse);
		StyledButton.roundFill(ctx, x, y, w, h, (aFill << 24) | 0x5865F2);
		Ui.hGrad(ctx, x + 2, y + 2, x + w - 2, y + h - 2, 0x005865F2, ((hov ? 0x4A : 0x2A) << 24) | 0x7B86FF, 3);
		Ui.radialGlow(ctx, x + 24, y + h / 2, 26, 0x5865F2, hov ? 0x58 : (int) (0x26 + 0x1A * pulse));
		Ui.starDrift(ctx, x + 2, y + 2, w - 4, h - 4, 91, 7, 0x9AA2FF);
		StyledButton.roundBorder(ctx, x, y, w, h, (((hov ? 0xFF : 0xA0 + (int) (0x40 * pulse)) << 24)) | (hov ? 0x9AA2FF : 0x7B86FF));

		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§9§l✦"), x + 20, y + 15, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f§lREQUEST A CUSTOM COSMETIC"), x + 36, y + 9, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer,
				Text.literal("§7Make a §fticket §7in our Discord — your own tag, name colour or gradient"),
				x + 36, y + 21, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer,
				Text.literal("§8§oanything that isn't on the shelf, we can make"), x + 36, y + 32, 0xFFFFFFFF);

		String pill = "§f✎ OPEN A TICKET" + (hov ? " §f→" : "");
		int pwid = this.textRenderer.getWidth(Ui.strip(pill)) + 16, pxx = x + w - 12 - pwid, pyy = y + h / 2 - 9;
		StyledButton.roundFill(ctx, pxx, pyy, pwid, 18, hov ? 0xFF5865F2 : 0xCC454FC7);
		StyledButton.roundBorder(ctx, pxx, pyy, pwid, 18, hov ? 0xFFCFD4FF : 0x995865F2);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(pill), pxx + 8, pyy + 5, 0xFFFFFFFF);
		requestRow = new int[]{ x, y, x + w, y + h };
	}

	private static final int REQUEST_H = 46;

	private int[] requestRow = null;

	private void openDiscord() {
		if (this.client == null) return;
		final String url = MarketConfig.requestUrl;
		this.client.setScreen(new net.minecraft.client.gui.screen.ConfirmLinkScreen(ok -> {
			if (ok) net.minecraft.util.Util.getOperatingSystem().open(url);
			this.client.setScreen(this);
		}, url, true));
	}

	private static void drawColourWash(DrawContext ctx, int x, int y, int w, int hh, int colour, int seed) {
		int c = colour & 0xFFFFFF;
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + hh, 0xFF0A0E16, 0xFF06080F);
		Ui.hGrad(ctx, x + 1, y + 1, x + w - 1, y + hh, 0x33000000 | c, 0xCC000000 | c, 3);
		Ui.radialGlow(ctx, x + w / 2, y + hh / 2, Math.min(w / 2 - 4, 40), colour, 0x3A);
		Ui.starDrift(ctx, x + 2, y + 2, w - 4, hh - 4, seed, 9, colour);
		Ui.scanlines(ctx, x + 1, y + 1, x + w - 1, y + hh);
	}

	private void drawNameScene(DrawContext ctx, int x, int y, int w, int hh, String spec, String tag) {
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + hh, 0xFF0D121D, 0xFF06080F);
		Ui.radialGlow(ctx, x + w / 2, y + hh / 2 - 2, Math.min(w / 2 - 6, 34), specGlow(spec), 0x2E);
		Ui.starDrift(ctx, x + 2, y + 2, w - 4, hh - 4, (spec + "|" + tag).hashCode(), 9, specGlow(spec));
		drawNameSample(ctx, x, y, w, hh, spec, tag);
		Ui.scanlines(ctx, x + 1, y + 1, x + w - 1, y + hh);
	}

	private static int specGlow(String spec) {
		if (spec == null || spec.isEmpty()) return 0xFFC89B4A;
		if (spec.startsWith("rainbow")) return Ui.rainbowPair()[0];
		String p0 = spec.contains("-") ? spec.split("-", 2)[0] : spec;
		return Ui.argb(p0, 0xFF8899AA);
	}

	private void drawNameSample(DrawContext ctx, int x, int y, int w, int hh, String spec, String tag) {
		String me = this.client != null && this.client.player != null ? this.client.player.getName().getString() : "Steve";
		var t = Text.empty();
		if (tag != null && !tag.isEmpty()) t.append(Text.literal(tag));
		t.append(Ui.colouredName(me, spec));
		int tw2 = Math.max(1, this.textRenderer.getWidth(t));
		float s = Math.min(1.35f, (w - 14) / (float) tw2);
		var m = ctx.getMatrices();
		m.pushMatrix(); m.translate(x + w / 2f, y + hh / 2f - 3); m.scale(s, s);
		ctx.drawCenteredTextWithShadow(this.textRenderer, t, 0, -4, 0xFFFFFFFF);
		m.popMatrix();

		if (MarketConfig.get().showNetworthTags && !MarketConfig.get().netPrivate) {
			long nw = com.lifestealmarket.NetworthTracker.INSTANCE.netWorth();
			if (nw > 0) ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§a✦ " + com.lifestealmarket.Coins.fmt(nw)), x + w / 2, y + hh / 2 + 8, 0xFFFFFFFF);
		}
	}

	private String[] bundleLook(StoreItem b) {
		String spec = null, tag = "", bg = null, accent = null;
		for (String mId : b.payload().split(",")) {
			StoreItem it = find(mId);
			if (it == null) continue;
			switch (it.type()) {
				case "nametag", "gradientname" -> { if (spec == null) spec = it.payload().replace("#", ""); }
				case "anytag" -> { if (spec == null) spec = "rainbow"; }
				case "anygradient" -> { if (spec == null) spec = "rainbowgrad"; }
				case "tag" -> { if (tag.isEmpty()) tag = it.payload().trim(); }
				case "bg" -> { if (bg == null) bg = it.payload(); }
				case "theme" -> { if (accent == null) accent = it.payload(); }
				case "anygui" -> { if (accent == null) accent = "rainbow"; }
			}
		}
		return new String[]{ spec, tag, bg, accent };
	}

	static void drawMiniGui(DrawContext ctx, int x, int y, int w, int hh, int accent) {
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + hh, 0xFF0D121D, 0xFF06080F);
		Ui.radialGlow(ctx, x + w / 2, y + hh / 2, Math.min(w / 2 - 6, 34), accent, 0x2A);
		Ui.starDrift(ctx, x + 2, y + 2, w - 4, hh - 4, accent, 7, accent);
		int gx = x + 9, gy = y + 7, gw = w - 18, gh = hh - 14;
		StyledButton.roundFill(ctx, gx, gy, gw, gh, 0xFF0B111B);
		StyledButton.roundBorder(ctx, gx, gy, gw, gh, accent);
		ctx.fill(gx + 1, gy + 1, gx + gw - 1, gy + 8, 0xFF141820);
		ctx.fill(gx + 1, gy + 8, gx + gw - 1, gy + 9, accent);
		ctx.fill(gx + 3, gy + 3, gx + 3 + Math.min(26, gw / 3), gy + 6, accent);
		int bw2 = Math.max(10, gw / 4);
		StyledButton.roundBorder(ctx, gx + 4, gy + 12, bw2, 7, accent);
		StyledButton.roundBorder(ctx, gx + 7 + bw2, gy + 12, bw2, 7, (accent & 0xFFFFFF) | 0x66000000);
		int base = gy + gh - 5;
		int[] ys = { 7, 11, 5, 9, 3, 8, 2 };
		int seg = Math.max(2, (gw - 12) / (ys.length - 1));
		for (int i = 0; i < ys.length - 1; i++) {
			int xa = gx + 6 + i * seg, xb = Math.min(gx + gw - 6, xa + seg);
			int ya = base - ys[i], yb2 = base - ys[i + 1];
			ctx.fill(xa, Math.min(ya, yb2), xa + 1, Math.max(ya, yb2) + 1, accent);
			ctx.fill(xa, yb2, xb, yb2 + 1, accent);
		}
		Ui.scanlines(ctx, x + 1, y + 1, x + w - 1, y + hh);
	}

	private static String memberLabel(StoreItem it) {
		if (it.type().startsWith("any")) return Ui.strip(it.name());
		String base = Ui.shortName(it.name(), it.type());
		String kind = switch (it.type()) {
			case "nametag" -> "Name Colour";
			case "gradientname" -> "Gradient";
			case "tag" -> "Tag";
			case "theme" -> "GUI Theme";
			case "bg" -> "Background";
			default -> "";
		};
		if (kind.isEmpty()) return base;
		return base.toLowerCase(Locale.ROOT).endsWith(kind.toLowerCase(Locale.ROOT)) ? base : base + " (" + kind + ")";
	}

	private static int itemColour(StoreItem it) {
		String p = it.payload() == null ? "" : it.payload().replace("#", "").trim();
		switch (it.type()) {
			case "nametag", "theme" -> { if (p.matches("(?i)[0-9a-f]{6}")) return 0xFF000000 | Integer.parseInt(p, 16); }
			case "gradientname" -> {
				String[] q = p.split("-");
				if (q.length > 0 && q[0].matches("(?i)[0-9a-f]{6}")) return 0xFF000000 | Integer.parseInt(q[0], 16);
			}
			case "anytag", "anygradient", "anygui" -> { return Ui.rainbowPair()[0]; }
			case "tag" -> {
				int i = p.indexOf((char) 0xA7);
				if (i >= 0 && i + 1 < p.length()) {
					net.minecraft.util.Formatting fm = net.minecraft.util.Formatting.byCode(p.charAt(i + 1));
					if (fm != null && fm.getColorValue() != null) return 0xFF000000 | fm.getColorValue();
				}
			}
			default -> {}
		}
		return tierColor(it);
	}

	private static int tierColor(StoreItem it) {
		return switch (it.type()) {
			case "bundle" -> 0xFFFFA132;
			case "anytag", "anygui", "anygradient" -> 0xFFB945F5;
			case "gradientname", "bg" -> 0xFF37B5F9;
			default -> 0xFF2FC286;
		};
	}

	private Double savings(StoreItem b) {
		try {
			if (b.price().isEmpty()) return null;
			double sum = 0;
			for (String m : b.payload().split(",")) {
				StoreItem it = find(m);
				if (it == null || it.price().isEmpty()) return null;
				sum += Double.parseDouble(it.price());
			}
			double save = sum - Double.parseDouble(b.price());
			return save > 0 ? save : null;
		} catch (Exception e) { return null; }
	}

	private String savingsLabel(StoreItem b) {
		Double save = savings(b);
		if (save == null) return null;
		String s = save == Math.floor(save) ? String.valueOf(save.longValue()) : String.format(Locale.ROOT, "%.2f", save);
		return "save " + CUR + s;
	}

	private String[] twoLines(String name, int maxW) {
		if (this.textRenderer.getWidth(name) <= maxW) return new String[]{ name, "" };
		String[] words = name.split(" ");
		StringBuilder l1 = new StringBuilder();
		int i = 0;
		for (; i < words.length; i++) {
			String cand = l1.isEmpty() ? words[i] : l1 + " " + words[i];
			if (this.textRenderer.getWidth(cand) > maxW && !l1.isEmpty()) break;
			l1 = new StringBuilder(cand);
		}
		String line1 = l1.toString();
		while (this.textRenderer.getWidth(line1) > maxW && line1.length() > 2) line1 = line1.substring(0, line1.length() - 2) + "…";
		String line2 = String.join(" ", Arrays.copyOfRange(words, i, words.length));
		while (this.textRenderer.getWidth(line2) > maxW && line2.length() > 2) line2 = line2.substring(0, line2.length() - 2) + "…";
		return new String[]{ line1, line2 };
	}

	private static String typeLabel(String t) {
		return switch (t) { case "theme" -> "GUI"; case "bg" -> "BG"; case "nametag" -> "NAME";
			case "tag" -> "TAG"; case "gradientname" -> "GRADIENT"; case "anytag", "anygui", "anygradient" -> "UNLOCK";
			case "bundle" -> "BUNDLE"; default -> t.toUpperCase(Locale.ROOT); };
	}
}

