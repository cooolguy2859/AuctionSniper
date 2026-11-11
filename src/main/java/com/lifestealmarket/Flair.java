package com.lifestealmarket;

import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Flair {
	private Flair() {}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static volatile Map<String, String[]> MAP = Map.of();
	private static volatile long nextPoll = 0L;
	private static volatile boolean loaded = false;

	public static String[] of(String playerName) {
		return playerName == null ? null : MAP.get(playerName.toLowerCase(Locale.ROOT));
	}

	public static boolean loaded() { return loaded; }

	public static void forcePoll() { nextPoll = 0L; }

	public static void poll(MinecraftClient client, long now) {
		if (now < nextPoll || client.player == null || !NetworthTracker.isHomeServer(client)) return;
		nextPoll = now + 60_000L;

		var req = HttpRequest.newBuilder(URI.create(MarketConfig.apiBase() + "/api/flair?roles=1"))
				.timeout(Duration.ofSeconds(8)).header("X-Market-Key", MarketConfig.API_KEY).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) return;
			try {
				var arr = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonArray("flair");
				Map<String, String[]> m = new HashMap<>();
				loaded = true;
				for (var el : arr) {
					var o = el.getAsJsonObject();
					String n = o.get("name").getAsString();
					m.put(n.toLowerCase(Locale.ROOT), new String[]{
							o.has("color") ? o.get("color").getAsString() : "",
							o.has("tag") ? o.get("tag").getAsString() : "",
							o.has("tagId") ? o.get("tagId").getAsString() : "",
							o.has("role") ? o.get("role").getAsString() : "",
							o.has("roleId") ? o.get("roleId").getAsString() : "" });
				}
				MAP = m;
			} catch (Exception ignored) {}
		});
	}

	public static void set(String colorHex, String tagId, java.util.function.Consumer<Boolean> done) {
		set(colorHex, tagId, null, done);
	}

	public static void set(String colorHex, String tagId, String roleId, java.util.function.Consumer<Boolean> done) {
		try {
			String url = MarketConfig.apiBase() + "/api/flair/set?color="
					+ URLEncoder.encode(colorHex == null ? "" : colorHex, StandardCharsets.UTF_8);
			if (tagId != null) url += "&tag=" + URLEncoder.encode(tagId, StandardCharsets.UTF_8);
			if (roleId != null) url += "&role=" + URLEncoder.encode(roleId, StandardCharsets.UTF_8);
			var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
					.header("X-Market-Key", MarketConfig.API_KEY)
					.header("X-User", MarketConfig.userName())
					.header("X-Token", MarketConfig.token())
					.POST(HttpRequest.BodyPublishers.noBody()).build();
			HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
				boolean ok = err == null && resp.statusCode() == 200;
				if (ok) nextPoll = 0L;
				if (done != null) done.accept(ok);
			});
		} catch (Exception e) { if (done != null) done.accept(false); }
	}
}

