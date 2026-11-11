package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import com.lifestealmarket.NetworthTracker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

public class SellNowScreen extends Screen {
	private final Screen parent;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph, cl, cr, listTop, listBot;
	private double uiScale = 1.0, effScale = 1.0;
	private final Ui.Scroll scroll = new Ui.Scroll();
	private final Ui.HoverFade hoverFade = new Ui.HoverFade();
	private final long openedAt = System.currentTimeMillis();
	private int hover = -1, lastOff = 0;
	private int tickCount = 0;
	private static final int ROW_H = 16;
	private List<NetworthTracker.SellRow> rows = List.of();

	public SellNowScreen(Screen parent) {
		super(Text.literal("Sell It Now"));
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

		listBot = listTop + Math.max(1, (listBot - listTop) / ROW_H) * ROW_H;

		addDrawableChild(new StyledButton(cr - 76, py + 7, 76, 16, Ui.label("Back", C_ACCENT),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));
		Venues.refresh();
		refresh();
	}

	private final java.util.Map<String, Venues.Venue> bestVenue = new java.util.HashMap<>();
	private final java.util.Map<String, Long> bestPayout = new java.util.HashMap<>();

	private void refresh() {
		java.util.List<NetworthTracker.SellRow> r = new java.util.ArrayList<>(NetworthTracker.INSTANCE.sellNow(this.client));
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (NetworthTracker.SellRow row : r) seen.add(NetworthTracker.norm(row.item()));
		bestVenue.clear(); bestPayout.clear();
		for (var e : NetworthTracker.INSTANCE.holdings(this.client).entrySet()) {
			String k = NetworthTracker.norm(e.getKey());
			if (!seen.add(k)) continue;
			var vs = Venues.sell(e.getKey(), 0);
			if (vs.isEmpty()) continue;
			int qty = e.getValue();
			r.add(new NetworthTracker.SellRow(e.getKey(), qty, qty, 0, vs.get(0).price() * (long) qty, 0, 0));
		}

		for (NetworthTracker.SellRow row : r) {
			String k = NetworthTracker.norm(row.item());
			var vs = Venues.sell(row.item(), row.bid());
			if (vs.isEmpty()) { bestPayout.put(k, row.total()); continue; }
			Venues.Venue best = vs.get(0);
			int units = best.instant() ? row.sellable() : row.qty();
			bestVenue.put(k, best);
			bestPayout.put(k, best.price() * (long) units);
		}
		r.sort((x, y) -> Long.compare(payoutOf(y), payoutOf(x)));
		rows = r;
	}

	private long payoutOf(NetworthTracker.SellRow r) {
		Long p = bestPayout.get(NetworthTracker.norm(r.item()));
		return p == null ? r.total() : p;
	}

	@Override
	public void tick() {
		if (++tickCount % 10 == 0) refresh();
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
		if (gx >= cl && gx <= cr && gy >= listTop && gy <= listBot && !rows.isEmpty()) {
			int idx = (int) ((gy - listTop + lastOff) / ROW_H);
			if (idx >= 0 && idx < rows.size()) { goSell(rows.get(idx)); return true; }
		}
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	private void goSell(NetworthTracker.SellRow r) {
		if (this.client == null || this.client.player == null || this.client.player.networkHandler == null) return;
		var options = Venues.sell(r.item(), r.bid());
		if (options.isEmpty()) return;
		Venues.Venue best = options.get(0);

		Venues.Venue instant = null;
		for (Venues.Venue v : options) if (v.instant()) { instant = v; break; }
		this.client.player.sendMessage(Text.literal(best.instant()
				? "§b[Market] §7Sell §f" + r.item() + " §7now: §e" + best.label()
						+ " §7pays §a" + Coins.fmt(best.price()) + " §8(instant — deliver to the order)"
				: "§b[Market] §7Highest price for §f" + r.item() + "§7: §e" + best.label()
						+ " §7at §a" + Coins.fmt(best.price()) + " §8(you list it there and wait)"), false);
		if (!best.instant() && instant != null)
			this.client.player.sendMessage(Text.literal("§8           or sell instantly: §7" + instant.label()
					+ " §8pays §7" + Coins.fmt(instant.price())), false);
		String cmd = best.cmd();
		if (cmd.startsWith("/")) cmd = cmd.substring(1);
		Venues.scannerHoldoff();

		if (cmd.equals("orders")) OrdersNav.want(r.item(), "order"); else OrdersNav.cancel();
		this.client.player.networkHandler.sendChatCommand(cmd);

		if (this.client != null) this.client.setScreen(null);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		double gmy = toGuiY(my), gmx = toGuiX(mx);
		if (gmx >= px && gmx <= px + pw && gmy >= py && gmy <= py + ph) {
			scroll.byRows(-(int) Math.signum(v) * 2, ROW_H, rows.size(), listBot - listTop);
			return true;
		}
		return super.mouseScrolled(mx, my, h, v);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == 264) { scroll.byRows(2, ROW_H, rows.size(), listBot - listTop); return true; }
		if (input.key() == 265) { scroll.byRows(-2, ROW_H, rows.size(), listBot - listTop); return true; }
		return super.keyPressed(input);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		int mx = (int) Math.round(toGuiX(mouseX)), my = (int) Math.round(toGuiY(mouseY));
		hover = (mx >= cl && mx <= cr && my >= listTop && my <= listBot) ? (my - listTop + lastOff) / ROW_H : -1;
		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;
		effScale = uiScale * Ui.openPop(openedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) effScale, (float) effScale); m.translate(-cxp, -cyp);
		try { renderInner(ctx, mx, my, delta); } finally { m.popMatrix(); }
		NavBar.draw(ctx, this.textRenderer, "sell", this.width, this.height, px, py, pw, uiScale);
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✦ Sell It Now", "§7click item = sell it");

		if (!NetworthTracker.INSTANCE.onHome()) {
			center(ctx, "§8Only available on the Lifesteal server.");
			super.render(ctx, mx, my, delta); return;
		}
		if (!NetworthTracker.INSTANCE.hasOrderData()) {
			for (int i = 0; i < 5; i++) Ui.shimmerCard(ctx, cl, listTop + i * 18, cr - cl, 14);
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8fetching live buy orders…"),
					px + pw / 2, py + ph - 12, 0xFFFFFFFF);
			super.render(ctx, mx, my, delta); return;
		}
		if (rows.isEmpty()) {
			center(ctx, "§8Nothing you're holding can be sold anywhere right now.");
			super.render(ctx, mx, my, delta); return;
		}

		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Item"), cl + 2, py + 36, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Can sell"), cl + 168, py + 36, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Best sell"), cl + 236, py + 36, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8Payout"), cr - this.textRenderer.getWidth("Payout"), py + 36, 0xFFFFFFFF);

		int rowH = ROW_H, viewH = listBot - listTop;
		lastOff = scroll.tickRows(rowH, rows.size(), viewH);
		int drawBot = listTop + Math.max(1, viewH / rowH) * rowH;
		ctx.fill(cl - 2, listTop - 3, cr + 2, listTop - 2, 0x1AFFFFFF);

		ctx.enableScissor(cl - 3, listTop, cr + 3, drawBot);
		try {
			int first = Math.max(0, lastOff / rowH);
			for (int i = first; i < rows.size(); i++) {
				int y = listTop + i * rowH - lastOff;
				if (y >= drawBot) break;
				NetworthTracker.SellRow r = rows.get(i);
				float hf = hoverFade.tick(i, i == hover);

				float vis = y < listTop ? Math.max(0f, 1f - (listTop - y) / (float) rowH)
						: (y + rowH > drawBot ? Math.max(0f, (drawBot - y) / (float) rowH) : 1f);
				int a = (int) (vis * 255);
				if (a <= 8) continue;
				int col = (a << 24) | 0xFFFFFF;
				if (hf > 0.02f) StyledButton.roundFill(ctx, cl - 2, y - 1, cr - cl + 4, rowH - 1,
						(C_ACCENT & 0xFFFFFF) | ((int) (hf * 0x33) << 24));
				else if ((i & 1) == 0) ctx.fill(cl, y - 1, cr, y + rowH - 2, 0x14000000);
				ItemIcons.draw(ctx, r.item(), cl, y + 1, 0.75f);
				ctx.drawTextWithShadow(this.textRenderer, Text.literal((hf > 0.5f ? "§e" : "§f") + trim(r.item(), 22)), cl + 14, y + 3, col);

				String have = r.sellable() < r.qty() ? "§a" + r.sellable() + "§8/" + r.qty() : "§ax" + r.sellable();
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(have), cl + 168, y + 3, col);

				Venues.Venue ev = bestVenue.get(NetworthTracker.norm(r.item()));
				String where = ev == null ? "" : " §8" + ("/orders".equals(ev.label()) ? "ord"
						: "/market".equals(ev.label()) ? "mkt" : "AH");
				String priceCol = ev != null ? "§7" + Coins.fmt(ev.price()) + where : "§8" + Coins.fmt(r.bid());
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(priceCol), cl + 236, y + 3, col);
				String tot = "§e" + Coins.fmt(payoutOf(r));
				ctx.drawTextWithShadow(this.textRenderer, Text.literal(tot),
						cr - this.textRenderer.getWidth(tot.replaceAll("§.", "")), y + 3, col);
			}
		} finally { ctx.disableScissor(); }
		Ui.scrollEdges(ctx, cl - 3, listTop, cr + 3, drawBot, C_PANEL);

		long grand = 0;
		for (NetworthTracker.SellRow r : rows) grand += payoutOf(r);

		if (hover >= 0 && hover < rows.size()) {
			NetworthTracker.SellRow hr = rows.get(hover);
			var opts = Venues.sell(hr.item(), hr.bid());
			if (!opts.isEmpty()) {
				Venues.Venue b = opts.get(0);
				String more = opts.size() > 1 ? " §8· then " + opts.get(1).label() + " " + Coins.fmt(opts.get(1).price()) : "";

				ctx.drawTextWithShadow(this.textRenderer,
						Text.literal((b.instant() ? "§7pays now: §e" : "§7top ask: §e") + b.label()
								+ " §a" + Coins.fmt(b.price()) + more + " §8— click to go"),
						cl, py + ph - 12, 0xFFFFFFFF);
			} else {
				ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8no live venue for this right now"), cl, py + ph - 12, 0xFFFFFFFF);
			}
		} else {
			ctx.drawTextWithShadow(this.textRenderer,
					Text.literal("§7Sell everything: §e" + Coins.fmt(grand) + " §8(" + rows.size() + " item" + (rows.size() == 1 ? "" : "s") + ")"),
					cl, py + ph - 12, 0xFFFFFFFF);
		}
		int maxScroll = Math.max(0, rows.size() * ROW_H - viewH);
		if (maxScroll > 0) {
			int firstVis = lastOff / ROW_H + 1, lastVis = Math.min(rows.size(), (lastOff + viewH) / ROW_H);
			String s = "§8" + firstVis + "-" + lastVis + " / " + rows.size() + " ↕";
			ctx.drawTextWithShadow(this.textRenderer, Text.literal(s),
					cr - this.textRenderer.getWidth(s.replaceAll("§.", "")), py + ph - 12, 0xFFFFFFFF);
		}
		super.render(ctx, mx, my, delta);
	}

	private void center(DrawContext ctx, String s) {
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(s), px + pw / 2, py + ph / 2, 0xFFFFFFFF);
	}

	private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

}

