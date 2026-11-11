package com.lifestealmarket.gui;

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

final class PlayerHeads {
	private PlayerHeads() {}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private record Head(Identifier id, int size) {}
	private static final Map<String, Head> LOADED = new ConcurrentHashMap<>();
	private static final Map<String, Long> RETRY = new ConcurrentHashMap<>();

	static boolean draw(DrawContext ctx, String name, int x, int y, int s) {
		if (name == null || name.isEmpty()) return false;
		String key = name.toLowerCase(Locale.ROOT);
		Head h = LOADED.get(key);
		if (h != null) {
			ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, h.id(), x, y, 0f, 0f, s, s, h.size(), h.size(), h.size(), h.size());
			return true;
		}
		long now = System.currentTimeMillis();
		Long next = RETRY.get(key);
		if (next == null || now >= next) { RETRY.put(key, now + 60_000L); fetch(key, name); }

		ctx.fill(x, y, x + s, y + s, 0x33000000);
		StyledButton.roundBorder(ctx, x, y, s, s, 0x22FFFFFF);
		return false;
	}

	private static void fetch(String key, String name) {
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {

				String url = com.lifestealmarket.MarketConfig.apiBase() + "/api/avatar?name="
						+ java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
				HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
						.header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).GET().build();
				HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
				if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) return;
				byte[] bytes = resp.body();

				if (!com.lifestealmarket.gui.Ui.imageWithinBounds(bytes, 512, 512)) { RETRY.put(key, System.currentTimeMillis() + 600_000L); return; }
				MinecraftClient mc = MinecraftClient.getInstance();
				mc.execute(() -> {
					try {
						NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
						NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "lsm_head_" + key, img);
						Identifier id = Identifier.of("lifestealmarket", "dynamic/head_" + key.replaceAll("[^a-z0-9_]", "_"));
						mc.getTextureManager().registerTexture(id, tex);
						LOADED.put(key, new Head(id, img.getWidth()));
					} catch (Exception decodeFail) {

						RETRY.put(key, System.currentTimeMillis() + 600_000L);
					}
				});
			} catch (Exception ignored) {}
		});
	}
}

