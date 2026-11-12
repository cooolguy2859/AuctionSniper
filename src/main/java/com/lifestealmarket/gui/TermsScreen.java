package com.lifestealmarket.gui;

import com.lifestealmarket.MarketConfig;
import com.lifestealmarket.NetworthTracker;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class TermsScreen extends Screen {

	private static final int C_ACCENT = 0xFF39D0FF, C_PANEL = 0xFF0A1220;
	private int pw, ph, px, py;
	private double uiScale = 1.0;
	private final long lsmOpenedAt = System.currentTimeMillis();
	private int scroll = 0;
	private List<OrderedText> body = new ArrayList<>();
	private int bodyTop, bodyBot, bodyL, bodyR;

	private static final String TERMS = String.join("\n\n",
		"§b§lAuctionSniper — Terms of Use",
		"Made by Rem, Timtim, and Rizzuku. These terms explain what AuctionSniper does and what information its features may use. Please read them before enabling optional features.",
		"§b§l1. What the mod does. §7AuctionSniper is a Minecraft client-side utility. It can help scan visible auction listings, track item prices, show price history, display balance information, and provide market and net-worth screens. Results depend on what the server exposes and may be incomplete or incorrect.",
		"§b§l2. Game data and price sharing. §7When market syncing is configured, the mod may send item identifiers, item names, observed listing prices, the selected server identifier, and an optional in-game seller name to the AuctionSniper market API. This data is used to build shared price averages and price history. Do not enable syncing if you do not want these observations shared.",
		"§b§l3. Local settings. §7Your preferences, scan settings, and API configuration are stored in your local Minecraft config folder. Keep your API key private. Never share a config file that contains your key, and do not post the key in chat or commit it to a public repository.",
		"§b§l4. Net-worth and inventory features. §7Some Lifesteal Market-derived features may read information shown by the game, such as your inventory, ender chest, balance, or public player information, to calculate estimates. If a feature offers a privacy or sharing setting, review it before enabling that feature. Net-worth and inventory estimates can be inaccurate.",
		"§b§l5. Network connections. §7Features that use online market, account-linking, cosmetic, leaderboard, or update services connect to the service configured for that feature. Those services may receive your IP address and the request information needed to respond. Online features may be unavailable, changed, or discontinued. Only use services you trust.",
		"§b§l6. Your choices. §7You are responsible for choosing which features to enable and for following the rules of the Minecraft server you join. The mod is not affiliated with or endorsed by Mojang or Microsoft. Do not use it to bypass server rules, rate limits, or access controls.",
		"§b§l7. No guarantee. §7Prices, charts, balances, net-worth totals, and scan results are estimates provided for convenience. They are not guaranteed to be accurate or available. You are responsible for your in-game decisions and any trades you make.",
		"§b§l8. Changes. §7These terms may be updated when the mod changes. If you do not agree with the terms, choose I Don't Agree and do not use the optional features that require acceptance.",
		"By choosing I Agree, you confirm that you have read these terms and agree to use the mod responsibly. Choosing I Don't Agree leaves the optional accurate-estimate features disabled.");

	public TermsScreen() { super(Text.literal("Terms of Agreement")); }

	@Override
	protected void init() {
		int cx = this.width / 2, cy = this.height / 2;

		pw = 300; ph = 360;
		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = cx - pw / 2; py = cy - ph / 2;

		bodyL = px + 14; bodyR = px + pw - 14; bodyTop = py + 34; bodyBot = py + ph - 40;
		body = new ArrayList<>();
		String[] paras = TERMS.split("\n\n", -1);
		for (int i = 0; i < paras.length; i++) {
			body.addAll(this.textRenderer.wrapLines(Text.literal("§7" + paras[i]), bodyR - bodyL));
			if (i < paras.length - 1) body.add(OrderedText.EMPTY);
		}

		int gap = 10, bw = (pw - 28 - gap) / 2, by = py + ph - 30;
		addDrawableChild(new StyledButton(px + 14, by, bw, 20, Text.literal("§a§lI Agree"),
				this::agree).accent(0xFF22E88A));
		addDrawableChild(new StyledButton(px + 14 + bw + gap, by, bw, 20, Text.literal("§c§lI Don't Agree"),
				this::decline).accent(0xFFFF556E));
	}

	private void agree() {
		NetworthTracker.accurateEnabled = true;
		NetworthTracker.INSTANCE.pendingTos = false;
		MarketConfig.get().tosAccepted = true; MarketConfig.save();
		if (this.client != null) this.client.setScreen(null);
	}

	private void decline() {
		NetworthTracker.accurateEnabled = false;
		NetworthTracker.INSTANCE.pendingTos = false;
		MarketConfig.get().tosAccepted = false; MarketConfig.save();
		if (this.client != null) this.client.setScreen(null);
	}

	@Override public boolean shouldCloseOnEsc() { return false; }
	@Override public void renderBackground(DrawContext ctx, int mx, int my, float d) { super.renderBackground(ctx, mx, my, d); }

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public boolean mouseClicked(Click c, boolean d) {
		return super.mouseClicked(new Click(toGuiX(c.x()), toGuiY(c.y()), c.buttonInfo()), d);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		int lineH = 9, maxScroll = Math.max(0, body.size() * lineH - (bodyBot - bodyTop));
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(v) * lineH * 3));
		return true;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		int gmx = (int) Math.round(toGuiX(mouseX)), gmy = (int) Math.round(toGuiY(mouseY));
		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;
		double lsmEff = uiScale * Ui.openPop(lsmOpenedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) lsmEff, (float) lsmEff); m.translate(-cxp, -cyp);

		StyledButton.roundFill(ctx, px, py, pw, ph, C_PANEL);
		StyledButton.roundBorder(ctx, px, py, pw, ph, C_ACCENT);
		ctx.fill(px + 1, py + 1, px + pw - 1, py + 26, 0xFF16181C);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§b§lAUCTIONSNIPER TERMS"), px + pw / 2, py + 9, 0xFFFFFFFF);

		ctx.enableScissor(bodyL, bodyTop, bodyR, bodyBot);
		int y = bodyTop - scroll;
		for (OrderedText line : body) {
			if (y > bodyBot - 2 || y < bodyTop - 12) { y += 9; continue; }
			ctx.drawTextWithShadow(this.textRenderer, line, bodyL, y, 0xFFFFFFFF);
			y += 9;
		}
		ctx.disableScissor();

		int lineH = 9, maxScroll = Math.max(0, body.size() * lineH - (bodyBot - bodyTop));
		if (maxScroll > 0) ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8▲▼ scroll"), bodyR - 46, bodyTop - 11, 0xFFFFFFFF);

		super.render(ctx, gmx, gmy, delta);
		m.popMatrix();
	}
}

