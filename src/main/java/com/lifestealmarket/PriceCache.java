package com.lifestealmarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class PriceCache {
	private PriceCache() {}

	public static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lifestealmarket_pricecache.json");

	private static Map<String, long[]> ALL;
	private static boolean dirty = false;

	private static void load() {
		if (ALL != null) return;
		ALL = new HashMap<>();
		try {
			if (Files.exists(PATH)) {
				var type = new com.google.gson.reflect.TypeToken<Map<String, long[]>>() {}.getType();
				Map<String, long[]> m = GSON.fromJson(Files.readString(PATH), type);
				if (m != null) ALL = m;
			}
		} catch (Exception e) {
			System.err.println("[LifestealMarket] price cache load failed: " + e);
		}
	}

	public static synchronized void put(String normTicker, long price, long now) {
		if (normTicker == null || normTicker.isEmpty() || price <= 0) return;
		load();
		long[] cur = ALL.get(normTicker);
		if (cur != null && cur[0] == price) { cur[1] = now; }
		else ALL.put(normTicker, new long[]{ price, now });
		dirty = true;
	}

	public static synchronized long get(String normTicker, long now) {
		if (normTicker == null) return -1;
		load();
		long[] v = ALL.get(normTicker);
		if (v == null) return -1;
		if (now - v[1] > MAX_AGE_MS) return -1;
		return v[0];
	}

	public static synchronized void save() {
		if (ALL == null || !dirty) return;
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(ALL));
			dirty = false;
		} catch (Exception e) {
			System.err.println("[LifestealMarket] price cache save failed: " + e);
		}
	}
}

