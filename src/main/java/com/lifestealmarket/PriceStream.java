package com.lifestealmarket;

import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class PriceStream {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8)).build();

	private final Consumer<Map<String, long[]>> onTick;
	private volatile long lastEventAt = 0L;
	private volatile boolean started = false;

	public PriceStream(Consumer<Map<String, long[]>> onTick) { this.onTick = onTick; }

	public long sinceLastEvent() { return lastEventAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastEventAt; }

	public synchronized void start() {
		if (started) return;
		started = true;
		Thread t = new Thread(this::runLoop, "lsm-price-stream");
		t.setDaemon(true);
		t.start();
	}

	private void runLoop() {
		while (true) {
			try { connectOnce(); } catch (Exception ignored) {}
			try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
		}
	}

	private void connectOnce() throws Exception {
		String url = MarketConfig.apiBase() + "/api/stream";
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(30))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId())
				.header("X-User", com.lifestealmarket.MarketConfig.userName())
				.header("X-Token", com.lifestealmarket.MarketConfig.token())
				.header("Accept", "text/event-stream")
				.GET().build();
		HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
		if (resp.statusCode() != 200) { resp.body().close(); return; }
		try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			String line;
			while ((line = r.readLine()) != null) {
				if (line.startsWith("data:")) {
					data.append(line.substring(5).trim());
				} else if (line.isEmpty()) {
					if (data.length() > 0) { dispatch(data.toString()); data.setLength(0); }
				}

			}
		}
	}

	private void dispatch(String json) {
		try {
			var arr = JsonParser.parseString(json).getAsJsonArray();
			Map<String, long[]> prices = new HashMap<>();
			for (var e : arr) {
				var o = e.getAsJsonObject();
				long floor = o.has("floor") && !o.get("floor").isJsonNull() ? o.get("floor").getAsLong() : -1;
				prices.put(o.get("ticker").getAsString(), new long[]{ o.get("last").getAsLong(), floor });
			}
			lastEventAt = System.currentTimeMillis();
			onTick.accept(prices);
		} catch (Exception ignored) {}
	}
}

