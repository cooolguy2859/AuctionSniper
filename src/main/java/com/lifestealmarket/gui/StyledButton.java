package com.lifestealmarket.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class StyledButton extends ClickableWidget {
	private final Runnable onPress;
	private boolean primary = false;
	private int accent = 0xFFE2B007;
	private List<Text> tooltip = null;
	private boolean badge = false;
	private boolean ghost = false;
	private boolean glass = false;

	private net.minecraft.util.Identifier iconTex = null;
	private static final net.minecraft.util.Identifier GEAR_TEX =
			net.minecraft.util.Identifier.of("lifestealmarket", "textures/gear.png");
	private static final net.minecraft.util.Identifier BELL_TEX =
			net.minecraft.util.Identifier.of("lifestealmarket", "textures/bell.png");

	private float hover = 0f;
	private float press = 0f;

	public StyledButton(int x, int y, int w, int h, Text message, Runnable onPress) {
		super(x, y, w, h, message);
		this.onPress = onPress;
	}

	public StyledButton(int x, int y, int w, int h, Text message, boolean primary, Runnable onPress) {
		this(x, y, w, h, message, onPress);
		this.primary = primary;
	}

	public StyledButton primary() { this.primary = true; return this; }

	public StyledButton ghost() { this.ghost = true; return this; }

	public StyledButton glass() { this.glass = true; return this; }

	public StyledButton accent(int argb) { this.accent = argb; return this; }

	public StyledButton tooltip(String... lines) {
		this.tooltip = new ArrayList<>();
		for (String l : lines) this.tooltip.add(Text.literal(l));
		return this;
	}

	public StyledButton badge(boolean show) { this.badge = show; return this; }

	public StyledButton gearIcon() { this.iconTex = GEAR_TEX; return this; }

	public StyledButton bellIcon() { this.iconTex = BELL_TEX; return this; }

	private boolean hasChip = false; private int chipColor = 0;
	private boolean hasChipGrad = false; private int chipG1 = 0, chipG2 = 0;
	private String chipTagText = null;
	private String chipThumbKey = null;

	public StyledButton chip(int argb) { this.hasChip = true; this.chipColor = argb; return this; }

	public StyledButton chipGradient(int c1, int c2) { this.hasChipGrad = true; this.chipG1 = c1; this.chipG2 = c2; return this; }

	public StyledButton chipTag(String glyph) { this.chipTagText = glyph; return this; }

	public StyledButton chipThumb(String artKey) { this.chipThumbKey = artKey; return this; }

	@Override
	public void onClick(Click click, boolean doubled) {
		onPress.run();
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		boolean handled = super.mouseClicked(click, doubled);
		if (handled) { press = 1f; playClick(); }
		return handled;
	}

	@Override
	protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {

		if (ghost) {
			if (isHovered() && tooltip != null)
				ctx.drawTooltip(MinecraftClient.getInstance().textRenderer, tooltip, mouseX, mouseY);
			return;
		}

		float k = Math.min(1f, 0.35f + delta * 0.05f);
		hover += ((isHovered() ? 1f : 0f) - hover) * k;
		press *= 0.82f;
		if (hover < 0.001f) hover = 0f;
		if (press < 0.01f) press = 0f;

		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		if (press > 0.35f) y += 1;

		int accentDarkBg  = lerpColor(accent, 0xFF000000, 0.86f);
		int accentDarkBgH = lerpColor(accent, 0xFF000000, 0.78f);
		int accentLight   = lerpColor(accent, 0xFFFFFFFF, 0.35f);
		int bg = lerpColor(primary ? accentDarkBg : 0xFF1A1F29, primary ? accentDarkBgH : 0xFF283143, hover);
		int border = lerpColor(primary ? accent : 0x55FFFFFF, primary ? accentLight : accent, hover);
		if (glass) {
			bg = (accent & 0x00FFFFFF) | (((int) (0x2E + hover * 0x2A)) << 24);
			border = lerpColor(accent, 0xFFFFFFFF, hover * 0.45f);
		}

		int sh = 0x40000000 + ((int) (hover * 40) << 24);
		ctx.fill(x + 1, y + h, x + w - 1, y + h + 1, sh);

		roundFill(ctx, x, y, w, h, bg);

		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, 0x18FFFFFF, 0x04000000);

		if (hover > 0f) roundFill(ctx, x, y, w, h, ((int) (hover * 26) << 24) | 0xFFFFFF);
		if (press > 0f) roundFill(ctx, x, y, w, h, ((int) (press * 70) << 24) | 0xFFFFFF);
		roundBorder(ctx, x, y, w, h, border);

		if (primary) {
			roundBorder(ctx, x - 1, y - 1, w + 2, h + 2, (accent & 0xFFFFFF) | 0x33000000);
			ctx.fill(x + 2, y + 1, x + w - 2, y + 2, 0x30FFFFFF);
		}

		boolean anyChip = hasChip || hasChipGrad || chipTagText != null || chipThumbKey != null;
		if (iconTex != null) {
			int gs = 14;
			ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, iconTex,
					x + (w - gs) / 2, y + (h - gs) / 2, 0f, 0f, gs, gs, 32, 32, 32, 32);
		} else if (anyChip) {
			int cxp = x + 5, cyp = y + (h - 7) / 2;
			if (chipThumbKey != null) {
				if (!ThemeBg.drawThumb(ctx, chipThumbKey, cxp, cyp, 10, 7))
					ctx.fill(cxp, cyp, cxp + 10, cyp + 7, 0xFF3B4B63);
				roundBorder(ctx, cxp - 1, cyp - 1, 12, 9, 0x44FFFFFF);
			} else if (hasChipGrad) Ui.gradStrip(ctx, cxp, cyp + 1, 8, 6, chipG1, chipG2);
			else if (chipTagText != null) {

				float ts = Ui.tagScale(chipTagText);
				var mt = ctx.getMatrices();
				mt.pushMatrix();
				mt.translate(cxp, y + (h - 8 * ts) / 2f); mt.scale(ts, ts);
				ctx.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
						Text.literal(chipTagText), 0, 0, 0xFFFFFFFF);
				mt.popMatrix();
			}
			else Ui.chip(ctx, cxp, cyp, 7, chipColor);
			int lx = chipTagText != null
					? cxp + MinecraftClient.getInstance().textRenderer.getWidth(chipTagText) + 3 : x + 16;
			ctx.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
					getMessage(), lx, y + (h - 8) / 2, 0xFFFFFFFF);
		} else ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer,
				getMessage(), x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);

		if (badge) {
			var tr = MinecraftClient.getInstance().textRenderer;
			int bw = tr.getWidth("NEW") + 6, bh = 10;
			float cx = x + w - 5, cy = y + 1;
			int bx = Math.round(cx - bw / 2f), by = Math.round(cy - bh / 2f);
			var m = ctx.getMatrices();
			m.pushMatrix();
			m.translate(cx, cy); m.rotate((float) (Math.PI / 4)); m.translate(-cx, -cy);
			roundFill(ctx, bx, by, bw, bh, 0xFFE23B3B);
			roundBorder(ctx, bx, by, bw, bh, 0xFFFF8A8A);
			ctx.drawTextWithShadow(tr, Text.literal("§f§lNEW"), bx + 3, by + 1, 0xFFFFFFFF);
			m.popMatrix();
		}

		if (isHovered() && tooltip != null) {
			ctx.drawTooltip(MinecraftClient.getInstance().textRenderer, tooltip, mouseX, mouseY);
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}

	static void roundFill(DrawContext ctx, int x, int y, int w, int h, int color) {
		ctx.fill(x + 1, y, x + w - 1, y + h, color);
		ctx.fill(x, y + 1, x + w, y + h - 1, color);
	}

	static void roundBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
		ctx.fill(x + 1, y, x + w - 1, y + 1, color);
		ctx.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
		ctx.fill(x, y + 1, x + 1, y + h - 1, color);
		ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	static int lerpColor(int a, int b, float t) {
		t = t < 0 ? 0 : (t > 1 ? 1 : t);
		int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int oa = (int) (aa + (ba - aa) * t), or = (int) (ar + (br - ar) * t);
		int og = (int) (ag + (bg - ag) * t), ob = (int) (ab + (bb - ab) * t);
		return (oa << 24) | (or << 16) | (og << 8) | ob;
	}

	static void previewClick() { lastClickSound = 0L; playClick(); }

	private static long lastClickSound = 0L;
	static void playClick() {
		long now = System.currentTimeMillis();
		if (now - lastClickSound < 80) return;
		lastClickSound = now;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.getSoundManager() == null) return;
		net.minecraft.sound.SoundEvent ev; float pitch = 1.25f;
		float vol = 0.35f;
		switch (com.lifestealmarket.MarketConfig.get().clickSound) {
			case "off"   -> { return; }
			case "pling" -> { ev = SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(); pitch = 1.5f; }
			case "bell"  -> { ev = SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(); pitch = 1.8f; }
			case "soft"  -> { ev = com.lifestealmarket.ModSounds.SOFT_CLICK; pitch = 1f; vol = 0.6f; }
			case "ui"    -> { ev = com.lifestealmarket.ModSounds.UI_CLICK; pitch = 1f; vol = 0.6f; }
			case "drip"  -> { ev = com.lifestealmarket.ModSounds.DRIP; pitch = 1f; vol = 0.6f; }
			case "thock" -> { ev = com.lifestealmarket.ModSounds.THOCK; pitch = 1f; vol = 0.7f; }
			case "tick"  -> { ev = com.lifestealmarket.ModSounds.TICK; pitch = 1f; vol = 0.55f; }
			case "pop"   -> { ev = com.lifestealmarket.ModSounds.POP; pitch = 1f; vol = 0.6f; }
			case "marimba" -> { ev = com.lifestealmarket.ModSounds.MARIMBA; pitch = 1f; vol = 0.55f; }
			case "coin"  -> { ev = com.lifestealmarket.ModSounds.COIN; pitch = 1f; vol = 0.5f; }
			default      -> { ev = SoundEvents.UI_BUTTON_CLICK.value(); }
		}
		if (ev == null) { ev = SoundEvents.UI_BUTTON_CLICK.value(); }
		mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(ev, pitch, vol));
	}
}

