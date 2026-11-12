package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BuyNowScreen extends Screen {
	private final Screen parent;
	private final String initialQuery;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph, cl, cr;
	private double uiScale = 1.0, effScale = 1.0;
	private final long openedAt = System.currentTimeMillis();
	private TextFieldWidget search;
	private String lastQ = null;
	private List<String> matches = List.of();
	private String selected = "";
	private List<Venues.Venue> venues = List.of();
	private long nextVenueRecalc = 0;

	private final List<int[]> hitBoxes = new ArrayList<>();

	public BuyNowScreen(Screen parent, String query) {
		super(Text.literal("Buy Now"));
		this.parent = parent;
		this.initialQuery = query == null ? "" : query.trim();
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

		addDrawableChild(new StyledButton(cr - 76, py + 7, 76, 16, Ui.label("Back", C_ACCENT),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));
		search = new TextFieldWidget(this.textRenderer, cl, py + 34, 210, 16, Text.literal("search"));
		search.setMaxLength(48);
		search.setText(initialQuery);
		addDrawableChild(search);
		this.setInitialFocus(search);
		Venues.refresh();
	}

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		if (NavBar.click(rc.x(), rc.y(), this)) return true;
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());
		for (int[] b : hitBoxes) {
			if (gx >= b[0] && gx <= b[2] && gy >= b[1] && gy <= b[3]) {
				if (b[4] >= 100) goBuy(b[4] - 100);
				else pick(matches.get(b[4]));
				return true;
			}
		}
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {

		if (input.key() == 257 && search != null && search.isFocused() && !matches.isEmpty()) {
			pick(matches.get(0));
			return true;
		}
		return super.keyPressed(input);
	}

	private void pick(String ticker) {
		selected = ticker;
		if (search != null) search.setText(ticker);
		nextVenueRecalc = 0;
	}

	private void goBuy(int idx) {
		if (idx < 0 || idx >= venues.size()) return;
		if (this.client == null || this.client.player == null || this.client.player.networkHandler == null) return;
		Venues.Venue v = venues.get(idx);
		this.client.player.sendMessage(Text.literal(
				"§b[Market] §7Buying §f" + selected + " §7at §e" + v.label() + " §7— §a" + Coins.fmt(v.price())), false);
		String cmd = v.cmd();
		if (cmd.startsWith("/")) cmd = cmd.substring(1);

		Venues.scannerHoldoff();
		OrdersNav.cancel();
		this.client.player.networkHandler.sendChatCommand(cmd);

		if (this.client != null) this.client.setScreen(null);
	}

	private List<String> suggest(String q) {
		String ql = q.toLowerCase(Locale.ROOT).trim();
		if (ql.isEmpty()) return List.of();
		List<String> fav = new ArrayList<>(), pre = new ArrayList<>(), word = new ArrayList<>(), in = new ArrayList<>();
		for (String t : Venues.allNames()) {
			String tl = t.toLowerCase(Locale.ROOT);
			boolean matches = tl.startsWith(ql) || tl.contains(" " + ql) || tl.contains(ql);
			if (!matches) continue;
			if (MarketConfig.get().isFav(t)) fav.add(t);
			else if (tl.startsWith(ql)) pre.add(t);
			else if (tl.contains(" " + ql)) word.add(t);
			else in.add(t);
			if (fav.size() >= 7) break;
		}
		List<String> out = new ArrayList<>(fav);
		for (String t : pre) if (out.size() < 7) out.add(t);
		for (String t : word) if (out.size() < 7) out.add(t);
		for (String t : in) if (out.size() < 7) out.add(t);
		return out;
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
		NavBar.draw(ctx, this.textRenderer, "buy", this.width, this.height, px, py, pw, uiScale);
	}

	@Override
	public void close() { Ui.escBack(this.client, parent); }

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {
		hitBoxes.clear();
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ Buy Now", "§7where it's cheapest");

		String q = search != null ? search.getText() : "";
		if (!java.util.Objects.equals(q, lastQ)) {
			lastQ = q;
			matches = suggest(q);
			if (!q.trim().isEmpty() && !q.equalsIgnoreCase(selected)) {

				for (String t : Venues.allNames()) if (t.equalsIgnoreCase(q.trim())) { selected = t; break; }
			}
		}
		long now = System.currentTimeMillis();
		if (!selected.isEmpty() && now >= nextVenueRecalc) { nextVenueRecalc = now + 1000; venues = Venues.buy(selected); }

		int y = py + 62;
		if (selected.isEmpty() || !q.equalsIgnoreCase(selected)) {

			if (!Venues.ready()) {
				for (int i = 0; i < 4; i++) Ui.shimmerCard(ctx, cl, y + i * 17, 220, 13);
			} else if (Venues.allNames().length == 0) {
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + (Venues.lastError().isEmpty() ? "No shared items yet — scan auctions to collect prices." : "Backend unavailable: " + Venues.lastError())), cl, y, 0xFFFFFFFF);
			} else if (q.trim().isEmpty()) {
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Type an item name above…"), cl, y, 0xFFFFFFFF);
			} else if (matches.isEmpty()) {
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8No item matches \"" + trim(q, 20) + "\""), cl, y, 0xFFFFFFFF);
			}
			for (int i = 0; i < matches.size(); i++) {
				boolean hov = mx >= cl && mx <= cr && my >= y && my < y + 13;
				if (hov) StyledButton.roundFill(ctx, cl - 2, y - 2, cr - cl + 4, 13, (C_ACCENT & 0xFFFFFF) | 0x33000000);
				ItemIcons.draw(ctx, matches.get(i), cl, y - 2, 0.7f);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal((hov ? "§e" : "§f") + trim(matches.get(i), 28)), cl + 14, y, 0xFFFFFFFF);
				hitBoxes.add(new int[]{ cl, y - 2, cr, y + 11, i });
				y += 14;
			}
			if (!matches.isEmpty())
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8ENTER = top result · click = pick"), cl, py + ph - 12, 0xFFFFFFFF);
			super.render(ctx, mx, my, delta);
			return;
		}

		ItemIcons.draw(ctx, selected, cl, y - 3, 0.85f);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + trim(selected, 26) + " §8— buy it at:"), cl + 17, y, 0xFFFFFFFF);
		y += 16;
		if (venues.isEmpty()) {
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Nowhere right now — no live listings or asks."), cl, y + 4, 0xFFFFFFFF);
		}
		for (int i = 0; i < venues.size(); i++) {
			Venues.Venue v = venues.get(i);
			boolean hov = mx >= cl && mx <= cr && my >= y && my < y + 24;
			boolean best = i == 0;
			if (hov) StyledButton.roundFill(ctx, cl - 2, y - 2, cr - cl + 4, 25, (C_ACCENT & 0xFFFFFF) | 0x33000000);
			else if (best) ctx.fill(cl, y - 1, cr, y + 22, 0x1439D0FF);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal((best ? "§a▶ " : "§8· ") + "§f" + v.label()), cl + 2, y + 2, 0xFFFFFFFF);
			String price = (best ? "§a" : "§e") + Coins.fmt(v.price());
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(price),
					cr - this.textRenderer.getWidth(price.replaceAll("§.", "")), y + 2, 0xFFFFFFFF);
			String sub = v.qty() > 0 ? "§8" + v.qty() + " on sale · click to open" : "§8click to open";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(sub), cl + 14, y + 13, 0xFFFFFFFF);
			hitBoxes.add(new int[]{ cl, y - 2, cr, y + 22, 100 + i });
			y += 27;
		}
		if (!venues.isEmpty()) {
			ctx.drawTextWithShadow(this.textRenderer,
					Text.literal("§8cheapest first · AH floor + /market ask, live"), cl, py + ph - 12, 0xFFFFFFFF);
		}
		super.render(ctx, mx, my, delta);
	}

	private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
}

