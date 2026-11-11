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
		"§a§lThis was personally approved by the developers of the server.",
		"Welcome to the Lifesteal Market mod. Please read these terms before using it. By continuing you confirm that you understand and accept how the mod works.",
		"The mod reads publicly visible auction house data and your own account information while you are connected to the Lifesteal server, and uses it to show you live prices, price history, order books, net worth and market statistics. It only ever reads: it is a viewer and a personal net worth tracker. It does not buy, sell, or list items for you, and never acts in the world without you choosing to.",
		"To power the shared price charts and the public net worth leaderboard, the mod uploads anonymised market observations such as item names, prices and sale timing, and, if you keep your profile public, your calculated net worth total and username. You can hide your net worth from the leaderboard at any time in Settings. Price data is pooled across everyone who runs the mod so the whole community gets a more accurate picture.",
		"§b§lYour net worth is now kept as a history, not just a current figure. §7So the mod can show how wealth changes over time, your total is recorded periodically — at most a few times an hour, and only when it has actually moved — and roughly the last 300 readings are kept. While your profile is public, other players can see that history; the moment you switch net worth to private it is hidden from everyone except you. It is a running total only: it never records which items you own.",
		"§b§lYour ender chest stays private unless you say otherwise. §7What is inside it is never uploaded, and nobody can look in it, unless you switch \"Ender chest: Shared\" on yourself in Settings. It is off until you do. While it is on, other players can open your chest from your leaderboard card and see what the mod last read in it. Switching it back off deletes the shared copy straight away.",
		"To calculate the most accurate estimates possible, the mod runs ordinary game commands for you, quietly and in the background. This includes reading your past listings and sales, your coin balance when the sidebar cannot be read, and your ender chest contents. These are the same commands you could type yourself; the mod simply runs them automatically, spaced out over time, and hides their chat output. If you do not agree, these commands will not run and your estimates will be less accurate or unavailable.",
		"§b§lPrivacy and your connection. §7The mod talks to the Lifesteal Market server to fetch prices and share market data. Like any server you connect to (including the game itself), that connection shows your IP address. It is used only to limit abuse: it is hashed the instant it arrives, is never written to a log or a file, never shown to anyone, and never sold or shared. The mod also saves a random ID on your PC so the server can tell accounts apart. It contains nothing about you. The mod does not read your files, run other programs, or collect anything from your computer beyond the Minecraft data described above.",

		"§b§lWhere else the mod connects. §7Two other connections happen automatically, and you should know about them. On startup the mod checks a small public file on GitHub to find the market server's current address — GitHub sees your IP for that one request. Player faces on the leaderboard are fetched through the market server, which gets them from a face service on your behalf, so that service never sees your IP or which names you look at. When you click a link in the mod — Discord, the store, a partner — it opens in your own browser, and only then does that site see you. The mod never opens a link without you clicking it.",
		"§b§lLinking Discord is optional. §7Nothing here requires it. If you choose to run /link, the market server stores which Discord account belongs to your username, so the bot can send you the price alerts you set and let you manage them with Discord commands. That link is never shown publicly and is used for nothing else. There is no self-serve unlink yet, so if you want it removed, ask the developer and it will be deleted.",
		"The mod paces its commands and requests to avoid spamming the server. Please do not modify it to bypass those limits.",
		"All prices, estimates and net worth figures are approximations for convenience only, with no guarantee of accuracy. Your trading decisions are your own, and the mod authors are not liable for any losses in game.",
		"These terms may change as the mod develops. Continued use after a change means you accept the updated terms.",
		"Choose below. \"I Agree\" enables the accurate estimate commands described above. \"I Don't Agree\" keeps them disabled, and this screen will appear again next time you join.");

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
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§b§lTERMS OF AGREEMENT"), px + pw / 2, py + 9, 0xFFFFFFFF);

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

