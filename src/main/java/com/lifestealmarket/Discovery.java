package com.lifestealmarket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class Discovery {
	private Discovery() {}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

	private static final String[] ALLOWED_HOSTS = { "tom.tail5c5049.ts.net", "lifesteal.net" };

	private static boolean hostAllowed(String base) {
		try {
			URI u = URI.create(base);
			if (!"https".equalsIgnoreCase(u.getScheme())) return false;
			String host = u.getHost();
			if (host == null) return false;
			host = host.toLowerCase(java.util.Locale.ROOT);

			for (String s : ALLOWED_HOSTS) if (host.equals(s) || host.endsWith("." + s)) return true;
			return false;
		} catch (Exception e) { return false; }
	}

	private static final java.util.concurrent.atomic.AtomicInteger FAILS = new java.util.concurrent.atomic.AtomicInteger();
	private static volatile long nextAllowed = 0L;
	private static volatile boolean noticeShown = false;

	public static void noteOk() { FAILS.set(0); }

	public static void noteFail() {
		if (FAILS.incrementAndGet() >= 3) { FAILS.set(0); resolve(false); }
	}

	public static void resolve(boolean startup) {
		String cfg = MarketConfig.discoveryUrls();
		if (cfg == null || cfg.isBlank()) return;
		long now = System.currentTimeMillis();
		if (now < nextAllowed) return;
		nextAllowed = now + (startup ? 60_000L : 10 * 60_000L);

		java.util.concurrent.CompletableFuture.runAsync(() -> {
			for (String raw : cfg.split(",")) {
				String url = raw.trim();
				if (url.isEmpty()) continue;
				try {
					HttpRequest req = HttpRequest.newBuilder(URI.create(url))
							.timeout(Duration.ofSeconds(6))
							.header("Cache-Control", "no-cache")
							.header("User-Agent", "LifestealMarket").GET().build();
					HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
					if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) continue;
					String body = resp.body().trim();
					String base, notice = "";
					if (body.startsWith("{")) {
						var o = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
						base = o.has("base") ? o.get("base").getAsString().trim() : "";
						if (o.has("notice")) notice = o.get("notice").getAsString();
					} else base = body.split("\\r?\\n")[0].trim();

					if (base.isEmpty()) continue;
					base = base.replaceAll("/+$", "");
					if (!hostAllowed(base)) {
						System.out.println("[LifestealMarket] discovery proposed a base outside the allowlist — ignored");
						continue;
					}
					if (base.equals(MarketConfig.get().apiBase)) { adoptNotice(notice); return; }
					if (!answers(base)) continue;

					MarketConfig.get().apiBase = base;
					MarketConfig.save();
					System.out.println("[LifestealMarket] backend moved -> " + base + " (via discovery)");
					adoptNotice(notice);
					return;
				} catch (Exception ignored) {  }
			}
		});
	}

	private static boolean answers(String base) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/health"))
					.timeout(Duration.ofSeconds(5)).GET().build();
			return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
		} catch (Exception e) { return false; }
	}

	private static volatile String pendingNotice = null;

	private static void adoptNotice(String notice) {
		if (notice == null || notice.isBlank() || noticeShown) return;
		noticeShown = true;
		pendingNotice = notice;
		flushNotice();
	}

	public static void flushNotice() {
		String n = pendingNotice;
		if (n == null) return;
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc == null || mc.player == null) return;
		pendingNotice = null;
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§b[Market] §7" + n), false);
		});
	}
}

