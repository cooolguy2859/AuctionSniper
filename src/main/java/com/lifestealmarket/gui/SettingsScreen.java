package com.lifestealmarket.gui;

import com.lifestealmarket.MarketConfig;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class SettingsScreen extends Screen {
	private static final int PW = 430, RCL = 14, RCR = PW - 14;
	private static final int ROW_H = 18, ROW_GAP = 6;

	private final Screen parent;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph;
	private double uiScale = 1.0;
	private final long openedAt = System.currentTimeMillis();

	private record Dec(String kind, String text, int x, int y, int h, int col) {}
	private final List<Dec> decor = new ArrayList<>();
	private final List<ClickableWidget> pending = new ArrayList<>();
	private int labelW;

	public SettingsScreen(Screen parent) {
		super(Text.literal("Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		MarketConfig cfg = MarketConfig.get();
		C_ACCENT = MarketConfig.themeColor(cfg.theme);
		C_ACC_DIM = (C_ACCENT & 0x00FFFFFF) | 0x55000000;
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (C_ACCENT & 0xFFFFFF), 0.16f);
		pw = PW;
		decor.clear(); pending.clear();

		labelW = 0;
		for (String s : new String[]{ "Mode", "Graph line", "GUI size", "Time", "Net worth", "Ender chest", "Discord DMs" })
			labelW = Math.max(labelW, this.textRenderer.getWidth(s));
		labelW += 12;

		int cur = 42;

		int s0 = cur;
		decor.add(new Dec("head", "TRADING", RCL + 6, cur, 0, 0)); cur += 14;
		cur = segments(cur, "Mode", new String[]{ "Seller", "Buyer" }, cfg.isBuyer() ? 1 : 0,
				i -> { cfg.role = i == 1 ? "buyer" : "seller"; MarketConfig.save(); clearAndInit(); },
				"§b§lWhich way is good news?", "§7Seller: green when the price RISES", "§7Buyer: green when it FALLS");
		cur = lineColour(cur, cfg);
		cur = card(s0, cur, C_ACCENT);

		s0 = cur;
		decor.add(new Dec("head", "DISPLAY", RCL + 6, cur, 0, 0)); cur += 14;
		double[] sizes = { 0.8, 1.0, 1.2 };
		int sizeIdx = Math.abs(cfg.guiSize - 0.8) < 0.01 ? 0 : (Math.abs(cfg.guiSize - 1.2) < 0.01 ? 2 : 1);
		cur = segments(cur, "GUI size", new String[]{ "Small", "Normal", "Large" }, sizeIdx,
				i -> { cfg.guiSize = sizes[i]; MarketConfig.save(); clearAndInit(); },
				"§b§lHow big the mod's screens are");
		cur = segments(cur, "Time", new String[]{ "24-hour", "12-hour" }, cfg.use12hTime ? 1 : 0,
				i -> { cfg.use12hTime = i == 1; MarketConfig.save(); clearAndInit(); },
				"§b§lClock format on charts", "§724-hour: 15:45", "§712-hour: 3:45 PM");
		cur = card(s0, cur, C_ACCENT);

		s0 = cur;
		decor.add(new Dec("head", "ON-SCREEN HUD", RCL + 6, cur, 0, 0)); cur += 14;
		cur = segments(cur, "Display", new String[]{ "On", "Off" }, cfg.showHud ? 0 : 1,
				i -> { cfg.showHud = i == 0; MarketConfig.save(); clearAndInit(); },
				"§b§lNet-worth panel while playing", "§7Shows net worth, daily change and 7-day trend");
		cur = segments(cur, "Style", new String[]{ "Glass", "Sharp", "Bold" },
				"glass".equals(cfg.hudStyle) ? 0 : "bold".equals(cfg.hudStyle) ? 2 : 1,
				i -> { cfg.hudStyle = new String[]{ "glass", "sharp", "bold" }[i]; MarketConfig.save(); clearAndInit(); });
		cur = card(s0, cur, C_ACCENT);

		s0 = cur;
		decor.add(new Dec("head", "PRIVACY", RCL + 6, cur, 0, 0)); cur += 14;
		cur = segments(cur, "Net worth", new String[]{ "Public", "Private" }, cfg.netPrivate ? 1 : 0,
				i -> { cfg.netPrivate = i == 1; MarketConfig.save();
						com.lifestealmarket.NetworthTracker.INSTANCE.uploadNow(); clearAndInit(); },
				"§b§lYour net-worth total", "§7Public: leaderboard, nametag, lookups",
				"§7Private: hidden everywhere", "§8Team totals still include you, without a figure");
		cur = segments(cur, "Ender chest", new String[]{ "Private", "Shared" }, cfg.shareEnderchest ? 1 : 0,
				i -> { com.lifestealmarket.EnderShare.setShared(i == 1,
						com.lifestealmarket.NetworthTracker.INSTANCE); clearAndInit(); },
				"§b§lWho can look inside it", "§7Private: its contents never leave this PC",
				"§7Shared: players can open it from your leaderboard card",
				"§8Shows what the mod last read — open your chest to refresh");
		cur = card(s0, cur, 0xFFFF6FB5);

		s0 = cur;
		decor.add(new Dec("head", "NOTIFICATIONS", RCL + 6, cur, 0, 0)); cur += 14;
		cur = segments(cur, "Discord DMs", new String[]{ "On", "Off" }, cfg.discordAlerts ? 0 : 1,
				i -> { cfg.discordAlerts = i == 0; MarketConfig.save();
						com.lifestealmarket.LifestealMarketClient.syncAlerts(); clearAndInit(); },
				"§b§lPrice alerts in your DMs", "§7In-game toasts are unaffected either way",
				"§8Link with /link in Discord + /lsmlink in-game");
		cur = card(s0, cur, Ui.GOLD);

		ph = cur + 6;

		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		for (ClickableWidget w : pending) { w.setX(w.getX() + px); w.setY(w.getY() + py); addDrawableChild(w); }

		addDrawableChild(new StyledButton(px + pw - RCL - 56, py + 7, 56, 17, Ui.label("Back", C_ACCENT),
				this::close).accent(C_ACCENT));
		addDrawableChild(new StyledButton(px + pw - RCL - 120, py + 7, 60, 17, Text.literal("§d✿ Style"),
				() -> { if (this.client != null) this.client.setScreen(new CustomizeScreen(this)); })
				.accent(C_ACCENT).tooltip("§d§lStyle", "§7GUI colour, name colours, tags", "§7and the click sound"));
	}

	private int segments(int cur, String label, String[] options, int active, IntConsumer pick, String... tip) {
		decor.add(new Dec("label", label, RCL + 6, cur + 5, 0, 0));
		int x0 = RCL + 6 + labelW, total = RCR - 6 - x0;
		int gap = 4, each = (total - gap * (options.length - 1)) / options.length;
		for (int i = 0; i < options.length; i++) {
			final int idx = i;
			boolean on = i == active;
			StyledButton b = new StyledButton(x0 + i * (each + gap), cur, each, ROW_H,
					Text.literal((on ? "§f§l" : "§7") + options[i]), on, () -> { if (idx != active) pick.accept(idx); })
					.accent(C_ACCENT);
			if (tip.length > 0) b.tooltip(tip);
			pending.add(b);
		}
		return cur + ROW_H + ROW_GAP;
	}

	private int lineColour(int cur, MarketConfig cfg) {
		decor.add(new Dec("label", "Graph line", RCL + 6, cur + 5, 0, 0));
		String[] opts = MarketConfig.LINE_COLORS;
		int x0 = RCL + 6 + labelW, total = RCR - 6 - x0;
		int gap = 3, each = (total - gap * (opts.length - 1)) / opts.length;
		for (String opt : opts) {
			final String o = opt;
			boolean on = o.equalsIgnoreCase(cfg.lineColor);
			int col = MarketConfig.lineColorValue(o);

			StyledButton b = new StyledButton(x0, cur, each, ROW_H, Text.literal(""), on,
					() -> { cfg.lineColor = o; MarketConfig.save(); clearAndInit(); })
					.accent(on ? 0xFFFFFFFF : (col == 0 ? C_ACCENT : col))
					.tooltip("§7Graph line: §f" + o + (o.equals("auto") ? "  §8(green up, red down)" : ""));
			if (col == 0) b.chipGradient(0xFF22E88A, 0xFFFF556E); else b.chip(col);
			pending.add(b);
			x0 += each + gap;
		}
		return cur + ROW_H + ROW_GAP;
	}

	private int card(int start, int cur, int col) {
		decor.add(new Dec("card", null, 0, start - 6, cur - start + 4, col));
		return cur + 10;
	}

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public boolean mouseClicked(Click c, boolean d) {
		if (NavBar.click(c.x(), c.y(), this)) return true;
		return super.mouseClicked(new Click(toGuiX(c.x()), toGuiY(c.y()), c.buttonInfo()), d);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		int mx = (int) Math.round(toGuiX(mouseX)), my = (int) Math.round(toGuiY(mouseY));
		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;
		double eff = uiScale * Ui.openPop(openedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) eff, (float) eff); m.translate(-cxp, -cyp);
		try { renderInner(ctx, mx, my, delta); } finally { m.popMatrix(); }
		NavBar.draw(ctx, this.textRenderer, "settings", this.width, this.height, px, py, pw, uiScale);
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "⚙ Settings", "§7how it behaves");

		for (Dec d : decor) {
			if (!d.kind().equals("card")) continue;
			StyledButton.roundFill(ctx, px + RCL - 2, py + d.y(), RCR - RCL + 4, d.h(), 0x30000000);
			ctx.fill(px + RCL - 2, py + d.y() + 1, px + RCL - 1, py + d.y() + d.h() - 1, 0xAA000000 | (d.col() & 0xFFFFFF));
		}
		for (Dec d : decor) {
			switch (d.kind()) {
				case "card" -> {}
				case "head" -> Ui.sectionHead(ctx, this.textRenderer, px + d.x(), py + d.y(), px + RCR - 6,
						d.text(), null, C_ACCENT);
				default -> ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7" + d.text()),
						px + d.x(), py + d.y(), 0xFFFFFFFF);
			}
		}

		super.render(ctx, mx, my, delta);
	}

	@Override public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { super.renderBackground(ctx, mouseX, mouseY, delta); }
	@Override public void close() { Ui.escBack(this.client, parent); }
}

