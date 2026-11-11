package com.lifestealmarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NetworthData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lifestealmarket_networth.json");

	public static class Listing {
		public String item;
		public int qty;
		public long price;
		public long listedAt;
		public Listing() {}
		public Listing(String item, int qty, long price, long listedAt) {
			this.item = item; this.qty = qty; this.price = price; this.listedAt = listedAt;
		}
	}

	public static class Snap {
		public long t; public long value;
		public Snap() {}
		public Snap(long t, long value) { this.t = t; this.value = value; }
	}

	public static class Stack {
		public String item; public int qty;
		public Stack() {}
		public Stack(String item, int qty) { this.item = item; this.qty = qty; }
	}

	public static class SlotStack {
		public int slot;
		public String id;
		public int qty;
		public String ticker;
		public String name;
		public String nbt;
		public SlotStack() {}
		public SlotStack(int slot, String id, int qty, String ticker, String name) {
			this(slot, id, qty, ticker, name, "");
		}
		public SlotStack(int slot, String id, int qty, String ticker, String name, String nbt) {
			this.slot = slot; this.id = id; this.qty = qty; this.ticker = ticker; this.name = name;  this.nbt = nbt;
		}
	}

	public static class Trade {
		public String item;
		public long price;
		public boolean buy;
		public long t;
		public Trade() {}
		public Trade(String item, long price, boolean buy, long t) { this.item = item; this.price = price; this.buy = buy; this.t = t; }
	}

	public List<Listing> listings = new ArrayList<>();
	public long realizedProfit = 0L;
	public long spent = 0L;
	public List<Snap> history = new ArrayList<>();
	public List<Stack> enderchest = new ArrayList<>();
	public long enderchestSeenAt = 0L;
	public List<SlotStack> ecSlots = new ArrayList<>();
	public List<Stack> unclaimed = new ArrayList<>();
	public long unclaimedSeenAt = 0L;

	public static class OrderEntry {
		public String item;
		public int done, total;
		public long perUnit;
		public int claim;
		public long seenAt;
		public OrderEntry() {}
		public OrderEntry(String item, int done, int total, long perUnit, int claim, long seenAt) {
			this.item = item; this.done = done; this.total = total; this.perUnit = perUnit;
			this.claim = claim; this.seenAt = seenAt;
		}
		public String key() { return item + "|" + perUnit; }
	}
	public List<OrderEntry> myOrders = new ArrayList<>();
	public long myOrdersSeenAt = 0L;
	public List<String> processed = new ArrayList<>();
	public List<Trade> trades = new ArrayList<>();

	private static Map<String, NetworthData> ALL;

	private static void loadAll() {
		if (ALL != null) return;
		ALL = new HashMap<>();
		try {
			if (Files.exists(PATH)) {
				var type = new com.google.gson.reflect.TypeToken<Map<String, NetworthData>>() {}.getType();
				Map<String, NetworthData> m = GSON.fromJson(Files.readString(PATH), type);
				if (m != null) ALL = m;
			}
		} catch (Exception e) {
			System.err.println("[LifestealMarket] networth load failed: " + e);
		}
	}

	public static NetworthData forAccount(String account) {
		loadAll();
		NetworthData d = ALL.computeIfAbsent(account == null ? "default" : account, k -> new NetworthData());
		if (d.listings == null) d.listings = new ArrayList<>();
		if (d.history == null) d.history = new ArrayList<>();
		if (d.enderchest == null) d.enderchest = new ArrayList<>();
		if (d.ecSlots == null) d.ecSlots = new ArrayList<>();
		if (d.unclaimed == null) d.unclaimed = new ArrayList<>();
		if (d.myOrders == null) d.myOrders = new ArrayList<>();
		if (d.processed == null) d.processed = new ArrayList<>();
		if (d.trades == null) d.trades = new ArrayList<>();
		return d;
	}

	public static void save() {
		if (ALL == null) return;
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(ALL));
		} catch (Exception e) {
			System.err.println("[LifestealMarket] networth save failed: " + e);
		}
	}

	public Listing removeSoldByPrice(long amount) {
		Listing best = null;
		for (Listing l : listings) if (l.price == amount && (best == null || l.listedAt < best.listedAt)) best = l;
		if (best != null) listings.remove(best);
		return best;
	}
}

