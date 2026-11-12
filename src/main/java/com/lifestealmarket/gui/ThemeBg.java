package com.lifestealmarket.gui;

import com.lifestealmarket.MarketConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ThemeBg {
	private ThemeBg() {}

	private static final Map<String, Object[]> BG = Map.of();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private record Dyn(Identifier id, int w, int h) {}
	private static final Map<String, Dyn> DYN = new ConcurrentHashMap<>();

	private static final Map<String, Long> RETRY = new ConcurrentHashMap<>();

	public static void draw(DrawContext ctx, int width, int height) {

		String bgKey = MarketConfig.activeBackground();
		if (bgKey != null) {
			String key = bgKey.toLowerCase(Locale.ROOT);
			Object[] bundled = BG.get(key);
			if (bundled != null) {
				drawStretched(ctx, (Identifier) bundled[0], (int) bundled[1], (int) bundled[2], width, height);
				return;
			}
			Dyn d = DYN.get(key);
			if (d != null) {
				drawStretched(ctx, d.id(), d.w(), d.h(), width, height);
				return;
			}

			long now = System.currentTimeMillis();
			Long next = RETRY.get(key);
			if (next == null || now >= next) { RETRY.put(key, now + 15_000L); requestArt(key); }
			ctx.fill(0, 0, width, height, 0xFF120810);
			return;
		}

	}

	public static boolean drawFitted(DrawContext ctx, String key, int x, int y, int w, int h) {
		float ar = aspectOf(key);
		if (ar <= 0f) return drawThumb(ctx, key, x, y, w, h);
		int fw = w, fh = Math.round(w / ar);
		if (fh > h) { fh = h; fw = Math.round(h * ar); }
		return drawThumb(ctx, key, x + (w - fw) / 2, y + (h - fh) / 2, Math.max(1, fw), Math.max(1, fh));
	}

	public static float aspectOf(String key) {
		if (key == null || key.isEmpty()) return 0f;
		String k = key.toLowerCase(Locale.ROOT);
		Object[] b = BG.get(k);
		if (b != null) return (int) b[1] / (float) (int) b[2];
		Dyn d = DYN.get(k);
		return d == null ? 0f : d.w() / (float) d.h();
	}

	public static boolean drawThumb(DrawContext ctx, String key, int x, int y, int w, int h) {
		if (key == null || key.isEmpty()) return false;
		String k = key.toLowerCase(Locale.ROOT);
		Object[] bundled = BG.get(k);
		if (bundled != null) {
			drawCropped(ctx, (Identifier) bundled[0], (int) bundled[1], (int) bundled[2], x, y, w, h);
			return true;
		}
		Dyn d = DYN.get(k);
		if (d != null) {
			drawCropped(ctx, d.id(), d.w(), d.h(), x, y, w, h);
			return true;
		}
		long now = System.currentTimeMillis();
		Long next = RETRY.get(k);
		if (next == null || now >= next) { RETRY.put(k, now + 15_000L); requestArt(k); }
		return false;
	}

	private static void drawCropped(DrawContext ctx, Identifier id, int tw, int th, int x, int y, int w, int h) {
		int srcW = tw, srcH = th;
		if ((long) tw * h > (long) th * w) srcW = Math.max(1, Math.round(th * (w / (float) h)));
		else srcH = Math.max(1, Math.round(tw * (h / (float) w)));
		float u = (tw - srcW) / 2f, v = (th - srcH) / 2f;
		ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, srcW, srcH, tw, th);
	}

	private static void drawStretched(DrawContext ctx, Identifier id, int tw, int th, int width, int height) {
		ctx.fill(0, 0, width, height, 0xFF120810);
		ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, width, height, tw, th, tw, th);
	}

	private static void requestArt(String key) {
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				String url = MarketConfig.apiBase() + "/api/art?key=" + key;
				HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
						.header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId()).GET().build();
				HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
				if (resp.statusCode() == 404) { RETRY.put(key, System.currentTimeMillis() + 600_000L); return; }
				if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) return;
				byte[] bytes = resp.body();

				if (!Ui.imageWithinBounds(bytes, 4096, 4096)) { RETRY.put(key, System.currentTimeMillis() + 600_000L); return; }
				MinecraftClient mc = MinecraftClient.getInstance();
				mc.execute(() -> {
					try {

						NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
						NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "lsm_bg_" + key, img);
						Identifier id = Identifier.of("lifestealmarket", "dynamic/bg_" + key);
						mc.getTextureManager().registerTexture(id, tex);
						DYN.put(key, new Dyn(id, img.getWidth(), img.getHeight()));
					} catch (Exception decodeFail) {
						RETRY.put(key, System.currentTimeMillis() + 600_000L);
					}
				});
			} catch (Exception ignored) {}
		});
	}
}

