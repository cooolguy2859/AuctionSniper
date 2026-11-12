package com.auctionsniper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/** Optional shared price service. Network work always runs off the Minecraft client thread. */
public final class MarketApi {
    private static final Map<String, RemotePrice> REMOTE = new ConcurrentHashMap<>();
    private static final Map<String, RemotePrice> REMOTE_BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, RemoteOrder> REMOTE_ORDERS = new ConcurrentHashMap<>();
    private static volatile boolean requestInFlight;
    private static volatile long lastSync;
    private static volatile String lastError = "";
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> COMPLETIONS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public record RemotePrice(long average, long low, long high, int samples, long lastSeen) {}
    /** Current shared buy-order snapshot. bid is a liquidity signal, never a confirmed sale price. */
    public record RemoteOrder(String ticker, String material, long bid, long demand,
                              long orders, long listed, long fetchedAt) {}

    private MarketApi() {}

    public static void tick() {
        if (!configured()) return;
        long now = System.currentTimeMillis();
        if (now - lastSync >= 300_000L && !requestInFlight) sync();
    }

    public static boolean configured() {
        return AuctionSniperClient.SETTINGS.cloudApiKey != null
                && !AuctionSniperClient.SETTINGS.cloudApiKey.isBlank()
                && AuctionSniperClient.SETTINGS.cloudApiUrl != null
                && !AuctionSniperClient.SETTINGS.cloudApiUrl.isBlank();
    }

    public static void observe(String itemId, String itemName, long price) {
        if (!configured() || price <= 0 || itemId == null || itemName == null) return;
        CompletableFuture.runAsync(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("serverId", AuctionSniperClient.SETTINGS.serverId);
            body.addProperty("itemId", itemId);
            body.addProperty("itemName", itemName);
            body.addProperty("price", price);
            try {
                request("POST", "/v1/observations", body.toString());
            } catch (Exception e) {
                lastError = safeError(e);
            }
        });
    }

    public static void sync() { sync(null); }

    public static void sync(Runnable completion) {
        if (!configured()) { if (completion != null) completion.run(); return; }
        if (completion != null) COMPLETIONS.add(completion);
        if (requestInFlight) return;
        requestInFlight = true;
        lastSync = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            try {
                String server = URLEncoder.encode(AuctionSniperClient.SETTINGS.serverId, StandardCharsets.UTF_8);
                String response = request("GET", "/v1/prices?serverId=" + server + "&limit=250", null);
                JsonObject root = JsonParser.parseString(response).getAsJsonObject();
                JsonArray prices = root.getAsJsonArray("prices");
                if (prices != null) {
                    for (JsonElement element : prices) {
                        if (!element.isJsonObject()) continue;
                        JsonObject item = element.getAsJsonObject();
                        if (!item.has("itemId")) continue;
                        String id = item.get("itemId").getAsString();
                        RemotePrice value = new RemotePrice(
                                number(item, "average"), number(item, "low"), number(item, "high"),
                                (int) number(item, "samples"), number(item, "lastSeen"));
                        REMOTE.put(id, value);
                        if (item.has("itemName")) REMOTE_BY_NAME.put(normalize(item.get("itemName").getAsString()), value);
                    }
                }
                // Pull the shared order book in the same background sync. A failed order
                // request must not discard a successful price refresh.
                try {
                    String orderResponse = request("GET", "/v1/orders?serverId=" + server, null);
                    JsonObject orderRoot = JsonParser.parseString(orderResponse).getAsJsonObject();
                    JsonArray orders = orderRoot.getAsJsonArray("orders");
                    if (orders != null) {
                        long fetchedAt = System.currentTimeMillis();
                        Map<String, RemoteOrder> fresh = new ConcurrentHashMap<>();
                        for (JsonElement element : orders) {
                            if (!element.isJsonObject()) continue;
                            JsonObject order = element.getAsJsonObject();
                            String ticker = order.has("ticker") ? order.get("ticker").getAsString() : "";
                            if (ticker.isBlank()) continue;
                            String material = order.has("material") && !order.get("material").isJsonNull()
                                    ? order.get("material").getAsString() : "";
                            RemoteOrder value = new RemoteOrder(ticker, material,
                                    number(order, "bid"), number(order, "demand"), number(order, "orders"),
                                    number(order, "listed"), fetchedAt);
                            fresh.put(normalize(ticker), value);
                            fresh.putIfAbsent(normalize(stripNamespace(ticker)), value);
                            if (!material.isBlank()) {
                                fresh.put(normalize(material), value);
                                fresh.putIfAbsent(normalize(stripNamespace(material)), value);
                            }
                        }
                        REMOTE_ORDERS.clear();
                        REMOTE_ORDERS.putAll(fresh);
                    }
                } catch (Exception orderError) {
                    lastError = "Order book: " + safeError(orderError);
                }
                if (lastError.startsWith("Order book:")) {
                    // Keep the useful price sync status visible while retaining the last order snapshot.
                } else {
                    lastError = "";
                }
            } catch (Exception e) {
                lastError = safeError(e);
            } finally {
                requestInFlight = false;
                for (Runnable callback : COMPLETIONS) {
                    try { callback.run(); } catch (Exception ignored) {}
                }
                COMPLETIONS.clear();
            }
        });
    }

    public static Map<String, RemotePrice> snapshot() {
        return Map.copyOf(REMOTE);
    }

    public static RemotePrice price(String itemId) {
        RemotePrice found = REMOTE.get(itemId);
        return found != null ? found : REMOTE_BY_NAME.get(normalize(itemId));
    }

    /** Finds a recent shared order row by item id, display name, ticker, or material. */
    public static RemoteOrder order(String itemId, String displayName) {
        long now = System.currentTimeMillis();
        for (String candidate : new String[]{itemId, displayName, stripNamespace(itemId), stripNamespace(displayName)}) {
            if (candidate == null || candidate.isBlank()) continue;
            RemoteOrder found = REMOTE_ORDERS.get(normalize(candidate));
            if (found != null && now - found.fetchedAt() <= 30L * 60L * 1000L) return found;
        }
        return null;
    }

    public static int cachedOrders() { return REMOTE_ORDERS.size(); }

    private static long number(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? 0L : Math.max(0L, value.getAsLong());
        } catch (RuntimeException ignored) { return 0L; }
    }

    private static String stripNamespace(String value) {
        if (value == null) return "";
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    public static int cachedItems() { return REMOTE.size(); }
    public static String lastError() { return lastError; }

    private static String request(String method, String path, String json) throws Exception {
        String base = AuctionSniperClient.SETTINGS.cloudApiUrl.trim().replaceAll("/+$", "");
        // Older config files may store an API path instead of the Worker origin.
        // This Worker exposes /v1/* directly from its root.
        for (String suffix : new String[]{"/api/v1", "/api", "/v1"}) {
            if (base.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length()).replaceAll("/+$", "");
                break;
            }
        }
        HttpURLConnection connection = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + AuctionSniperClient.SETTINGS.cloudApiKey);
        if (json != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = "";
        if (stream != null) {
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                response = out.toString(StandardCharsets.UTF_8);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IllegalStateException("Market API HTTP " + status);
        return response;
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        return message.length() > 100 ? message.substring(0, 100) : message;
    }
}
