package com.lifestealmarket.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.Text;

public class AlertToast implements Toast {
	private static final long DURATION = 5500;
	private static final net.minecraft.util.Identifier BELL_TEX =
			net.minecraft.util.Identifier.of("lifestealmarket", "textures/bell.png");

	private final Text title, sub;
	private final long price;
	private final int accent;
	private final String item;
	private long elapsed = 0;
	private Toast.Visibility visibility = Toast.Visibility.SHOW;

	public AlertToast(Text title, Text sub, long price, int accent) {
		this(title, sub, price, accent, "");
	}

	public AlertToast(Text title, Text sub, long price, int accent, String item) {
		this.title = title; this.sub = sub; this.price = price; this.accent = accent;
		this.item = item == null ? "" : item;
	}

	@Override public Toast.Visibility getVisibility() { return visibility; }

	@Override
	public void update(ToastManager m, long time) {
		elapsed = time;
		visibility = time >= DURATION ? Toast.Visibility.HIDE : Toast.Visibility.SHOW;
	}

	@Override public int getWidth() { return 196; }
	@Override public int getHeight() { return 40; }

	@Override
	public void draw(DrawContext ctx, TextRenderer tr, long startTime) {
		int w = getWidth(), h = getHeight();
		int acc = accent & 0xFFFFFF;

		StyledButton.roundFill(ctx, 3, 4, w - 3, h - 3, 0x66000000);
		StyledButton.roundFill(ctx, 0, 0, w, h, 0xFF070B12);
		ctx.fillGradient(1, 1, w - 1, h - 1, 0x0EFFFFFF, 0x22000000);

		Ui.hGrad(ctx, 1, 1, (int) (w * 0.72), 15, 0x38000000 | acc, 0x00000000, 4);

		ctx.fill(0, 1, 3, h - 1, 0xFF000000 | acc);
		ctx.fill(3, 1, 4, h - 1, 0x55000000 | acc);
		StyledButton.roundBorder(ctx, 0, 0, w, h, 0xFF000000 | acc);
		StyledButton.roundBorder(ctx, -1, -1, w + 2, h + 2, 0x33000000 | acc);

		int ix = 10, iy = 11;
		StyledButton.roundFill(ctx, ix - 3, iy - 3, 22, 22, 0x66000000);
		StyledButton.roundBorder(ctx, ix - 3, iy - 3, 22, 22, 0x44FFFFFF);
		boolean drewIcon = !item.isEmpty() && ItemIcons.draw(ctx, item, ix, iy, 1.0f);
		if (!drewIcon) {

			float swing = elapsed < 700 ? (float) Math.sin(elapsed / 55.0) * (1f - elapsed / 700f) * 0.28f : 0f;
			var mtx = ctx.getMatrices();
			mtx.pushMatrix();
			mtx.translate(ix + 8, iy + 2); mtx.rotate(swing); mtx.translate(-(ix + 8), -(iy + 2));
			ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, BELL_TEX, ix, iy, 0f, 0f, 16, 16, 32, 32, 32, 32);
			mtx.popMatrix();
		}

		int tx = ix + 26;
		drawFit(ctx, tr, title, tx, 8, w - tx - 10);
		drawFit(ctx, tr, sub, tx, 21, w - tx - 10);

		double left = Math.max(0, 1 - elapsed / (double) DURATION);
		int barW = (int) ((w - 8) * left);
		if (barW > 0) {
			Ui.hGrad(ctx, 4, h - 3, 4 + barW, h - 1, 0xFF000000 | acc, 0x66000000 | acc, 4);
		}
	}

	private void drawFit(DrawContext ctx, TextRenderer tr, Text t, int x, int y, int maxW) {
		int tw = tr.getWidth(t);
		if (tw <= maxW) { ctx.drawText(tr, t, x, y, 0xFFFFFFFF, true); return; }
		float s = maxW / (float) tw;
		var mtx = ctx.getMatrices();
		mtx.pushMatrix();
		mtx.translate(x, y); mtx.scale(s, s); mtx.translate(-x, -y);
		ctx.drawText(tr, t, x, y, 0xFFFFFFFF, true);
		mtx.popMatrix();
	}
}

