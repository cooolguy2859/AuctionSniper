package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.MarketConfig;
import com.lifestealmarket.NetworthTracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Venues {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static volatile Map<String, long[]> ah = Map.of();

	private static volatile Map<String, long[]> market = Map.of();

	private static final Map<String, String> names = new java.util.concurrent.ConcurrentHashMap<>();

	private static final Map<String, String> materials = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile String[] allNames = new String[0];
	private static volatile long lastFetch = 0;

	private Venues() {}

	public record Venue(String label, String cmd, long price, long qty, boolean instant) {}

	public static String[] allNames() { return allNames; }
	public static boolean ready() { return !names.isEmpty(); }
	public static String materialFor(String ticker) { return materials.get(NetworthTracker.norm(ticker)); }
	public static void noteMaterial(String normTicker, String material) {
		if (normTicker != null && material != null && !material.isEmpty()) materials.putIfAbsent(normTicker, material);
	}

	public static List<Venue> buy(String ticker) {
		String k = NetworthTracker.norm(ticker);
		List<Venue> v = new ArrayList<>();
		long[] a = ah.get(k);
		if (a != null && a[0] > 0) v.add(new Venue("Auction House", "ah " + plain(ticker), a[0], a[1], true));
		long[] m = market.get(k);
		if (m != null && m[0] > 0) v.add(new Venue("/market", "market", m[0], m[1], true));
		v.sort(Comparator.comparingLong(Venue::price));
		return v;
	}

	public static List<Venue> sell(String ticker, long topBid) {
		String k = NetworthTracker.norm(ticker);
		List<Venue> v = new ArrayList<>();
		if (topBid > 0) v.add(new Venue("/orders", "orders", topBid, 0, true));

		long value = NetworthTracker.INSTANCE.unitPrice(ticker);
		long[] m = market.get(k);
		if (m != null && m[0] > 0) {
			long px = value > 0 ? Math.min(m[0], value) : m[0];
			v.add(new Venue("/market", "market", px, m[1], false));
		}
		long[] a = ah.get(k);
		if (a != null && a[0] > 0) {
			long px = value > 0 ? Math.min(a[0], value) : a[0];
			v.add(new Venue("AH", "ah " + plain(ticker), px, a[1], false));
		}
		v.sort((x, y) -> Long.compare(y.price(), x.price()));
		return v;
	}

	public static void refresh() {
		long now = System.currentTimeMillis();
		if (now - lastFetch < 30_000) return;
		lastFetch = now;
		get("/api/tickers", body -> {
			var arr = com.google.gson.JsonParser.parseString(body).getAsJsonArray();
			Map<String, long[]> m = new HashMap<>();
			for (var el : arr) {
				var o = el.getAsJsonObject();
				String t = o.get("ticker").getAsString();
				long floor = o.has("floor") && !o.get("floor").isJsonNull() ? o.get("floor").getAsLong() : 0;
				long supply = o.has("supply") && !o.get("supply").isJsonNull() ? o.get("supply").getAsLong() : 0;
				m.put(NetworthTracker.norm(t), new long[]{ floor, supply });
				names.put(NetworthTracker.norm(t), t);
			}
			ah = m;
			rebuildNames();
		});
		get("/api/market", body -> {
			var arr = com.google.gson.JsonParser.parseString(body).getAsJsonArray();
			Map<String, long[]> m = new HashMap<>();
			for (var el : arr) {
				var o = el.getAsJsonObject();
				String t = o.get("ticker").getAsString();
				long ask = o.get("ask").getAsLong();
				long supply = o.has("supply") ? o.get("supply").getAsLong() : 0;
				m.put(NetworthTracker.norm(t), new long[]{ ask, supply });
				names.putIfAbsent(NetworthTracker.norm(t), t);
				if (o.has("material")) noteMaterial(NetworthTracker.norm(t), o.get("material").getAsString());
			}
			market = m;
			rebuildNames();
		});
	}

	private static void rebuildNames() {
		allNames = names.values().stream().distinct().sorted().toArray(String[]::new);
	}

	public static String plain(String ticker) {
		int cut = ticker.indexOf('·');
		String s = cut > 0 ? ticker.substring(0, cut) : ticker;
		return s.replaceAll("(?i)§.", "").trim();
	}

	public static void scannerHoldoff() {
		long until = System.currentTimeMillis() + 15_000L;
		System.setProperty("ahsniper.playerMenuUntil", String.valueOf(until));
		com.lifestealmarket.NetworthTracker.playerMenuUntil = until;
	}

	private static void get(String path, java.util.function.Consumer<String> onBody) {
		String url = MarketConfig.apiBase() + path;
		var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId())
				.header("X-User", MarketConfig.userName()).header("X-Token", MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) return;
			try { onBody.accept(resp.body()); } catch (Exception ignored) {}
		});
	}
}

