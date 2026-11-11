package com.lifestealmarket;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class EnderShare {
	private EnderShare() {}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static volatile long lastSentAt = 0L;
	private static volatile String lastBody = "";

	public record Slot(int slot, String id, int count, String ticker, String name, String nbt) {}

	public record Chest(boolean shared, String player, long updated, long value, List<Slot> items) {}

	private static final Map<String, Object[]> CACHE = new ConcurrentHashMap<>();
	private static final long CACHE_MS = 30_000L;

	private static String oneLineNbt(String s) { return s.replace((char) 9, (char) 32).replace((char) 10, (char) 32); }

	public static boolean neverSent() { return lastSentAt == 0L; }

	public static void publish(NetworthTracker nt) {
		if (!MarketConfig.get().shareEnderchest) return;
		NetworthData d = nt.data();

		boolean haveSlots = d.ecSlots != null && !d.ecSlots.isEmpty();
		StringBuilder sb = new StringBuilder("SHARE\t1\t")
				.append(haveSlots ? Math.max(0, nt.enderchestValue()) : 0).append('\n');
		if (d.ecSlots != null) for (NetworthData.SlotStack s : d.ecSlots) {
			if (s == null || s.id == null || s.id.isBlank()) continue;
			sb.append(s.slot).append('\t').append(s.id).append('\t').append(Math.max(1, s.qty)).append('\t')
			  .append(s.ticker == null ? "" : s.ticker).append('\t').append(s.name == null ? "" : s.name).append((char) 9).append(s.nbt == null ? "" : oneLineNbt(s.nbt)).append((char) 10);
		}
		send(sb.toString());
	}

	public static void setShared(boolean on, NetworthTracker nt) {
		MarketConfig.get().shareEnderchest = on;
		MarketConfig.save();
		lastBody = "";
		if (!on) { send("SHARE\t0\n"); CACHE.clear(); return; }
		publish(nt);

		NetworthData d = nt.data();
		if (d.ecSlots == null || d.ecSlots.isEmpty()) {
			var mc = net.minecraft.client.MinecraftClient.getInstance();
			if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal(
					"§b[Market] §7Open an ender chest once §8(or /ec if your rank has it) §7and it'll show on your card."), false);
		}
	}

	private static final long HEARTBEAT_MS = 60_000L;

	private static void send(String body) {
		if (body.equals(lastBody) && System.currentTimeMillis() - lastSentAt < HEARTBEAT_MS) return;
		if (MarketConfig.token().isBlank()) return;
		try {
			var req = HttpRequest.newBuilder(URI.create(MarketConfig.apiBase() + "/api/ec/report"))
					.timeout(Duration.ofSeconds(8))
					.header("X-Market-Key", MarketConfig.API_KEY)
					.header("X-User", MarketConfig.userName())
					.header("X-Token", MarketConfig.token())
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
			HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
				if (err == null && resp.statusCode() == 200) { lastSentAt = System.currentTimeMillis(); lastBody = body; }
			});
		} catch (Exception ignored) {}
	}

	public static void fetch(String player, Consumer<Chest> done) {
		String key = player == null ? "" : player.toLowerCase(Locale.ROOT);
		Object[] c = CACHE.get(key);
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		if (c != null && System.currentTimeMillis() - (Long) c[1] < CACHE_MS) {
			mc.execute(() -> done.accept((Chest) c[0]));
			return;
		}
		String url = MarketConfig.apiBase() + "/api/ec?player="
				+ URLEncoder.encode(player == null ? "" : player, StandardCharsets.UTF_8);
		var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
				.header("X-Market-Key", MarketConfig.API_KEY)
				.header("X-User", MarketConfig.userName())
				.header("X-Token", MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			Chest out;
			try {
				if (err != null || resp.statusCode() != 200) out = null;
				else {
					var o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
					if (!o.get("shared").getAsBoolean()) out = new Chest(false, player, 0, 0, List.of());
					else {
						List<Slot> items = new ArrayList<>();
						for (var el : o.getAsJsonArray("items")) {
							var i = el.getAsJsonObject();
							items.add(new Slot(i.get("slot").getAsInt(), i.get("id").getAsString(),
									i.get("count").getAsInt(),
									i.has("ticker") ? i.get("ticker").getAsString() : "",
									i.has("name") ? i.get("name").getAsString() : "",
									i.has("nbt") && !i.get("nbt").isJsonNull() ? i.get("nbt").getAsString() : ""));
						}
						out = new Chest(true, o.get("player").getAsString(), o.get("updated").getAsLong(),
								o.get("value").getAsLong(), items);
					}
					CACHE.put(key, new Object[]{ out, System.currentTimeMillis() });
				}
			} catch (Exception e) { out = null; }
			final Chest fin = out;
			mc.execute(() -> done.accept(fin));
		});
	}
}

