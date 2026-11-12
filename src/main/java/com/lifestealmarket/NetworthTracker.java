package com.lifestealmarket;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NetworthTracker {
	public static final NetworthTracker INSTANCE = new NetworthTracker();
	private NetworthTracker() {}

	private static final Pattern LISTED = Pattern.compile("Listed\\s+(?:(\\d+)x\\s+)?(.+?)\\s+for\\s+([\\d,]+)\\s+Coins", Pattern.CASE_INSENSITIVE);

	private static final Pattern SOLD = Pattern.compile("You sold an auction item for\\s+([\\d,]+)\\s+Coins", Pattern.CASE_INSENSITIVE);

	private static final Pattern ENDED = Pattern.compile("expired|cancel(?:led|ed)?|returned|(?:removed|took).*auction|claimed.*(?:back|listing)", Pattern.CASE_INSENSITIVE);

	private static final Pattern BAL = Pattern.compile("Coins:\\s*([\\d.,]+)\\s*([KMB])?", Pattern.CASE_INSENSITIVE);

	private static final Pattern BAL_CHAT = Pattern.compile("You have\\s+([\\d,.]+)\\s+Coins", Pattern.CASE_INSENSITIVE);

	private static final Pattern CMD_ERR = Pattern.compile(
			"unknown( or incomplete)? command|in combat|can(no|')?t.*combat|combat.*can(no|')?t|please wait|no permission|don't have permission|on cooldown|able to use commands in|must be in a .*team",
			Pattern.CASE_INSENSITIVE);
	private volatile long silentCmdUntil = 0L;
	private volatile long lastUserCmd = 0L;
	public void notePlayerSent() { lastUserCmd = System.currentTimeMillis(); }
	private boolean userTypedRecently() { return System.currentTimeMillis() - lastUserCmd < 1800; }

	private long chatGuardUntil = 0L;
	private boolean chatGuarded(long now) { return now < chatGuardUntil; }

	private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
			.connectTimeout(java.time.Duration.ofSeconds(6)).build();

	private long coins = -1;
	private long lastCoins = -1;
	private volatile Map<String, long[]> prices = new HashMap<>();
	private volatile Map<String, long[]> priceByBase = new HashMap<>();
	private long nextPriceFetch = 0L;
	private volatile boolean fetching = false;
	private long nextSnap = 0L;
	private long nextBalRead = 0L;
	private volatile long invValue = 0L;

	public long cachedInventoryValue() { return invValue; }
	private long nextInvVal = 0L;
	private String account = "default";

	private static final long JOIN_GRACE_MS = 30_000L;

	private static final long LOBBY_SETTLE_MS = 3_000L;
	private boolean fastSyncArmed = false;
	private net.minecraft.world.World lastWorld = null;
	private long worldJoinAt = 0L;
	private boolean wasHome = false;

	public long coins() { return coins; }
	public Map<String, long[]> prices() { return prices; }
	public NetworthData data() { return NetworthData.forAccount(account); }

	public static volatile boolean accurateEnabled = false;

	public volatile boolean pendingTos = false;

	private long nextUpload = 0L;

	public void uploadNow() { nextUpload = 0L; }

	private void maybeUpload(long now) {
		if (now < nextUpload) return;
		nextUpload = now + 300_000L;
		boolean priv = MarketConfig.get().netPrivate;

		if (!priv && (coins < 0 || prices.isEmpty())) return;
		long v = netWorth();
		String body = account + "\t" + v + "\t" + (priv ? "1" : "0");
		String url = MarketConfig.apiBase() + "/api/networth/report";
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(java.time.Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId())

				.header("X-User", com.lifestealmarket.MarketConfig.userName())
				.header("X-Token", com.lifestealmarket.MarketConfig.token())
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {

			if (err != null || resp.statusCode() != 200) nextUpload = System.currentTimeMillis() + 20_000L;
		});
	}

	private volatile java.util.Map<String, Long> board = new java.util.HashMap<>();
	private volatile java.util.List<String> boardNames = new java.util.ArrayList<>();
	private long nextBoardFetch = 0L;

	private volatile java.util.Map<String, String> refTags = new java.util.HashMap<>();
	private long nextRefFetch = 0L;

	public String referralTag(String name) {
		if (!onHome || name == null) return null;
		return refTags.get(name.toLowerCase(Locale.ROOT));
	}

	private void fetchRefTags(long now) {
		if (now < nextRefFetch) return;
		nextRefFetch = now + 60_000L;
		String url = MarketConfig.apiBase() + "/api/referral/tags";
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId()).GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) return;
			try {
				var o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
				java.util.Map<String, String> m = new java.util.HashMap<>();
				for (var e : o.entrySet()) m.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
				refTags = m;
			} catch (Exception ignored) {}
		});
	}

	private volatile java.util.Map<String, long[]> orderBids = new java.util.HashMap<>();

	private volatile java.util.Map<String, String> orderNames = new java.util.HashMap<>();

	private volatile java.util.Map<String, java.util.List<long[]>> orderDepth = new java.util.HashMap<>();

	private volatile java.util.Set<String> chartOnly = new java.util.HashSet<>();
	private long nextOrderFetch = 0L;

	public boolean isChartOnly(String ticker) { return chartOnly.contains(norm(ticker)); }

	public java.util.Collection<String> orderItemNames() { return orderNames.values(); }

	private void fetchOrderBids(long now) {
		if (now < nextOrderFetch) return;
		nextOrderFetch = now + 60_000L;
		String url = MarketConfig.apiBase() + "/api/orders";
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId()).GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) return;
			try {
				var arr = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonArray();
				java.util.Map<String, long[]> m = new java.util.HashMap<>();
				java.util.Map<String, String> names = new java.util.HashMap<>();
				java.util.Map<String, java.util.List<long[]>> depth = new java.util.HashMap<>();
				java.util.Set<String> chartsOnly = new java.util.HashSet<>();
				for (var el : arr) {
					var o = el.getAsJsonObject();
					String t = o.get("ticker").getAsString();
					String k = norm(t);

					if (o.has("listed") && !o.get("listed").getAsBoolean()) chartsOnly.add(k);
					m.put(k, new long[]{
							o.has("bid") ? o.get("bid").getAsLong() : 0,
							o.has("demand") ? o.get("demand").getAsLong() : 0,
							o.has("orders") ? o.get("orders").getAsLong() : 0 });
					names.put(k, t);
					if (o.has("material")) com.lifestealmarket.gui.Venues.noteMaterial(k, o.get("material").getAsString());
					java.util.List<long[]> rungs = new java.util.ArrayList<>();
					if (o.has("depth") && o.get("depth").isJsonArray()) {
						for (var d : o.getAsJsonArray("depth")) {
							var dd = d.getAsJsonObject();
							long price = dd.has("price") ? dd.get("price").getAsLong() : 0;
							long qty = dd.has("qty") ? dd.get("qty").getAsLong() : 0;
							if (price > 0 && qty > 0) rungs.add(new long[]{ price, qty });
						}
					}
					rungs.sort((a, b) -> Long.compare(b[0], a[0]));
					depth.put(k, rungs);
				}
				orderBids = m; orderNames = names; orderDepth = depth; chartOnly = chartsOnly;
			} catch (Exception ignored) {}
		});
	}

	public java.util.Map<String, Integer> holdings(MinecraftClient client) {
		java.util.Map<String, Integer> agg = new java.util.LinkedHashMap<>();
		if (client != null && client.player != null) {
			var inv = client.player.getInventory();
			for (int i = 0; i < inv.size(); i++) addStack(agg, inv.getStack(i));
		}
		for (NetworthData.Stack s : data().enderchest) agg.merge(s.item, s.qty, Integer::sum);
		return agg;
	}

	public java.util.List<long[]> depthFor(String ticker) {
		return orderDepth.getOrDefault(norm(ticker), java.util.List.of());
	}

	public int sellableQty(String ticker) {
		long n = 0;
		for (long[] r : depthFor(ticker)) n += r[1];
		return (int) Math.min(n, Integer.MAX_VALUE);
	}

	public long fillValue(String ticker, int qty) {
		long total = 0;
		int left = qty;
		for (long[] rung : depthFor(ticker)) {
			if (left <= 0) break;
			int take = (int) Math.min(left, rung[1]);
			total += rung[0] * (long) take;
			left -= take;
		}
		return total;
	}

	public long stackValue(String ticker, int qty) {
		if (ticker == null || qty <= 0) return 0;

		if (isChartOnly(ticker)) return 0;
		long total = 0;
		int left = qty;
		long med = unitPrice(ticker);
		for (long[] rung : depthFor(ticker)) {
			if (left <= 0) break;
			if (med > 0 && rung[0] <= med) break;
			int take = (int) Math.min(left, rung[1]);
			total += rung[0] * (long) take;
			left -= take;
		}
		if (left > 0 && med > 0) total += med * (long) left;
		return total;
	}

	public record SellRow(String item, int qty, int sellable, long bid, long total, long demand, int orders) {}

	public java.util.List<SellRow> sellNow(MinecraftClient client) {
		java.util.List<SellRow> rows = new java.util.ArrayList<>();
		java.util.Map<String, long[]> bids = orderBids;
		for (var e : holdings(client).entrySet()) {
			String k = norm(e.getKey());
			long[] b = bids.get(k);
			if (b == null || b[0] <= 0) continue;
			int qty = e.getValue();
			int sellable = Math.min(qty, Math.max(sellableQty(e.getKey()), 0));
			if (sellable <= 0) continue;
			long payout = fillValue(e.getKey(), sellable);
			rows.add(new SellRow(orderNames.getOrDefault(k, e.getKey()), qty, sellable, b[0], payout, b[1], (int) b[2]));
		}
		rows.sort((x, y) -> Long.compare(y.total(), x.total()));
		return rows;
	}

	public boolean hasOrderData() { return !orderBids.isEmpty(); }

	public long[] orderInfo(String ticker) { return orderBids.get(norm(ticker)); }

	public Long publicNetworth(String name) {
		if (!onHome || name == null) return null;
		return board.get(name.toLowerCase(Locale.ROOT));
	}

	public java.util.List<String> publicPlayerNames() { return boardNames; }

	private void fetchBoard(long now) {
		if (now < nextBoardFetch) return;
		nextBoardFetch = now + 60_000L;
		String url = MarketConfig.apiBase() + "/api/networth/top";
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(java.time.Duration.ofSeconds(8)).header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId()).GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) return;
			try {
				var arr = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonArray();
				java.util.Map<String, Long> b = new java.util.HashMap<>();
				java.util.List<String> names = new java.util.ArrayList<>();
				for (var e : arr) { var o = e.getAsJsonObject(); String nm = o.get("name").getAsString();
					b.put(nm.toLowerCase(Locale.ROOT), o.get("networth").getAsLong()); names.add(nm); }
				board = b; boardNames = names;
			} catch (Exception ignored) {}
		});
	}

	public static final String HOME_SERVER = "lifesteal.net";
	private volatile boolean onHome = false;
	public boolean onHome() { return onHome; }

	public static boolean isHomeServer(MinecraftClient client) {
		var s = client.getCurrentServerEntry();
		if (s == null || s.address == null) return false;
		String address = s.address.toLowerCase(Locale.ROOT);
		String configured = MarketConfig.serverHost().toLowerCase(Locale.ROOT);
		if (configured.isBlank()) configured = HOME_SERVER;
		return address.contains(configured);
	}

	public static String tabFooter(MinecraftClient client) {
		try {
			var hud = client.inGameHud.getPlayerListHud();
			net.minecraft.text.Text f = ((com.lifestealmarket.mixin.PlayerListHudAccessor) (Object) hud).getFooter();
			return f == null ? "" : f.getString().toLowerCase(java.util.Locale.ROOT);
		} catch (Exception e) { return ""; }
	}

	public static boolean isMainLobby(MinecraftClient client) {
		if (!isHomeServer(client)) return false;
		String f = tabFooter(client);
		if (f.contains("hub") || f.contains("duel")) return false;
		return true;
	}

	public void tick(MinecraftClient client) {
		if (client.player == null || client.world == null) return;
		onHome = isHomeServer(client);

		boolean joinedServer = onHome && !wasHome;
		wasHome = onHome;
		if (!onHome) return;
		account = client.getSession().getUsername();
		long now = System.currentTimeMillis();

		if (joinedServer) {

			if (MarketConfig.get().tosAccepted) {
				accurateEnabled = true;
				pendingTos = false;
			} else {
				accurateEnabled = false;
				pendingTos = true;
			}
		}

		if (client.world != lastWorld) {
			lastWorld = client.world;
			worldJoinAt = now;
			nextHistOpen = now + JOIN_GRACE_MS;
			nextEcOpen = now + JOIN_GRACE_MS;
			nextBalCmd = now + JOIN_GRACE_MS;
			fastSyncArmed = true;
		}

		if (fastSyncArmed) {
			String f = tabFooter(client);
			if (f.contains("spawn") || f.contains("lifesteal")) {
				fastSyncArmed = false;
				nextHistOpen = Math.min(nextHistOpen, now + LOBBY_SETTLE_MS);
				nextBalCmd = Math.min(nextBalCmd, now + LOBBY_SETTLE_MS);
			} else if (f.contains("hub") || f.contains("duel")) {
				fastSyncArmed = false;
			}
		}

		if (client.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) chatGuardUntil = now + 2_000L;

		if (now >= nextBalRead) { nextBalRead = now + 250L; readBalance(client); }
		if (now >= nextInvVal) {
			nextInvVal = now + 1000L;
			long v = inventoryValue(client.player.getInventory());
			if (v != invValue) { invValue = v; snapshot(now); }
		}
		scanEnderChest(client, now);
		scanClaims(client, now);
		scanMyOrders(client, now);
		scanPastListings(client, now);
		handleHeadless(client, now);
		maybeBalFallback(client, now);
		maybeUpload(now);
		fetchBoard(now);
		fetchRefTags(now);
		fetchOrderBids(now);

		if (!fetching && now >= nextPriceFetch) { nextPriceFetch = now + 30_000L; fetchPrices(client); }

		if (now >= nextSnap) { nextSnap = now + 180_000L; snapshot(now); }
	}

	private void readBalance(MinecraftClient client) {
		try {
			Scoreboard sb = client.world.getScoreboard();
			ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
			if (obj == null) return;
			for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
				if (e.hidden()) continue;
				Team team = sb.getScoreHolderTeam(e.owner());
				String line = (team != null ? team.decorateName(e.name()) : e.name()).getString();
				Matcher m = BAL.matcher(line);
				if (m.find()) {
					long v = parseCoins(m.group(1), m.group(2));
					if (v >= 0 && v != coins) { lastCoins = coins; coins = v; onBalanceChanged(); }
					return;
				}
			}
		} catch (Exception ignored) {}
	}

	private void onBalanceChanged() { snapshot(System.currentTimeMillis()); }

	private boolean balPending = false;
	private long balSince = 0L, nextBalCmd = 0L, balHideUntil = 0L;

	private void maybeBalFallback(MinecraftClient client, long now) {
		if (balPending) { if (now - balSince > 3000) balPending = false; return; }
		if (!accurateEnabled) return;
		if (!isMainLobby(client)) return;
		if (coins >= 0 || now < nextBalCmd) return;
		if (client.currentScreen != null || chatGuarded(now)) return;
		if (userTypedRecently()) return;
		if (client.player == null || client.player.networkHandler == null) return;
		nextBalCmd = now + 30_000L; balPending = true; balSince = now; silentCmdUntil = now + 2500L;
		balHideUntil = now + 6000L;
		SilentCmd.send(client, "bal");
	}

	public boolean autoCommandsOk(MinecraftClient client, long now) {
		return accurateEnabled && onHome && isMainLobby(client)
				&& client.currentScreen == null && !chatGuarded(now) && !userTypedRecently()
				&& client.player != null && client.player.networkHandler != null;
	}

	public void markSilentCommand(long ms) { silentCmdUntil = System.currentTimeMillis() + ms; }

	public boolean allowMessage(String raw) {
		if (raw == null) return true;
		long now = System.currentTimeMillis();

		if (now < balHideUntil || balPending) {
			Matcher m = BAL_CHAT.matcher(raw);
			if (m.find()) {
				try { double d = Double.parseDouble(m.group(1).replace(",", ""));
					if (d >= 0) { coins = (long) Math.floor(d); snapshot(now); } } catch (Exception ignore) {}
				balPending = false;
				return false;
			}
		}
		if (now < silentCmdUntil && CMD_ERR.matcher(raw).find()) return false;
		return true;
	}

	private Object lastEcScreen = null;
	private long ecOpenAt = 0L;

	private void scanEnderChest(MinecraftClient client, long now) {
		net.minecraft.client.gui.screen.Screen cs = client.currentScreen;
		if (!(cs instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs)
				|| !hs.getTitle().getString().toLowerCase().contains("ender chest")) {
			lastEcScreen = null;
			return;
		}
		if (cs != lastEcScreen) { lastEcScreen = cs; ecOpenAt = now; }
		if (now - ecOpenAt < 400) return;
		commitEnderChest(hs.getScreenHandler(), client.player.getInventory(), now);
	}

	private void addStack(java.util.Map<String, Integer> agg, net.minecraft.item.ItemStack st) {
		if (st == null || st.isEmpty()) return;

		String ticker = itemTicker(st);
		agg.merge(ticker, st.getCount(), Integer::sum);

		com.lifestealmarket.gui.ItemIcons.remember(ticker, st);
		var container = st.get(net.minecraft.component.DataComponentTypes.CONTAINER);
		if (container != null) for (net.minecraft.item.ItemStack inner : container.iterateNonEmpty()) addStack(agg, inner);
	}

	private void commitEnderChest(net.minecraft.screen.ScreenHandler handler, net.minecraft.inventory.Inventory pinv, long now) {
		java.util.Map<String, Integer> agg = new java.util.LinkedHashMap<>();
		java.util.List<NetworthData.SlotStack> slots = new java.util.ArrayList<>();
		int idx = 0;
		for (net.minecraft.screen.slot.Slot s : handler.slots) {
			if (s.inventory == pinv) continue;
			net.minecraft.item.ItemStack st = s.getStack();
			addStack(agg, st);
			if (st != null && !st.isEmpty())
				slots.add(new NetworthData.SlotStack(idx, net.minecraft.registry.Registries.ITEM.getId(st.getItem()).toString(),
						st.getCount(), itemTicker(st), st.getName().getString(), encodeStack(st)));
			idx++;
		}
		NetworthData d = data();
		java.util.List<NetworthData.Stack> snap = new java.util.ArrayList<>();
		for (var e : agg.entrySet()) snap.add(new NetworthData.Stack(e.getKey(), e.getValue()));
		boolean changed = !sameStacks(d.enderchest, snap);

		boolean slotsChanged = !sameSlots(d.ecSlots, slots);
		d.ecSlots = slots;
		if (changed) { d.enderchest = snap; d.enderchestSeenAt = now; snapshot(now); }
		if (changed || slotsChanged) NetworthData.save();

		if (MarketConfig.get().ecCmdFails != 0) { MarketConfig.get().ecCmdFails = 0; MarketConfig.save(); }

		EnderShare.publish(this);
	}

	private static boolean sameSlots(java.util.List<NetworthData.SlotStack> a, java.util.List<NetworthData.SlotStack> b) {
		if (a == null || a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			NetworthData.SlotStack x = a.get(i), y = b.get(i);
			if (x.slot != y.slot || x.qty != y.qty || !java.util.Objects.equals(x.id, y.id)
					|| !java.util.Objects.equals(x.name, y.name)) return false;
		}
		return true;
	}

	private static boolean sameStacks(java.util.List<NetworthData.Stack> a, java.util.List<NetworthData.Stack> b) {
		if (a == null || a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (!a.get(i).item.equals(b.get(i).item) || a.get(i).qty != b.get(i).qty) return false;
		}
		return true;
	}

	public long enderchestValue() {
		long total = 0;
		for (NetworthData.Stack s : data().enderchest) total += stackValue(s.item, s.qty);
		return total;
	}

	private long lastClaimsScan = 0L;

	private void scanClaims(MinecraftClient client, long now) {
		net.minecraft.client.gui.screen.Screen cs = client.currentScreen;
		if (!(cs instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs)
				|| !hs.getTitle().getString().toLowerCase().contains("claim")) return;
		if (now - lastClaimsScan < 500) return;
		lastClaimsScan = now;
		commitClaims(hs.getScreenHandler(), client.player.getInventory(), now);
	}

	private void commitClaims(net.minecraft.screen.ScreenHandler handler, net.minecraft.inventory.Inventory pinv, long now) {
		java.util.Map<String, Integer> agg = new java.util.LinkedHashMap<>();
		for (net.minecraft.screen.slot.Slot s : handler.slots) {
			if (s.inventory == pinv) continue;
			addStack(agg, s.getStack());
		}
		NetworthData d = data();
		java.util.List<NetworthData.Stack> snap = new java.util.ArrayList<>();
		for (var e : agg.entrySet()) snap.add(new NetworthData.Stack(e.getKey(), e.getValue()));
		if (!sameStacks(d.unclaimed, snap)) {
			d.unclaimed = snap; d.unclaimedSeenAt = now; NetworthData.save(); snapshot(now);
		}
	}

	private long lastOrdersScan = 0L;
	private static final java.util.regex.Pattern ORD_PROGRESS =
			java.util.regex.Pattern.compile("(?i)progress:\\s*([\\d,]+)\\s*/\\s*([\\d,]+)");
	private static final java.util.regex.Pattern ORD_PER_UNIT =
			java.util.regex.Pattern.compile("(?i)price:\\s*([\\d,]+(?:\\.\\d+)?)\\s*coins?\\s*per\\s*unit");
	private static final long ORDER_STALE_MS = 7L * 24 * 3600 * 1000;

	private void scanMyOrders(MinecraftClient client, long now) {
		net.minecraft.client.gui.screen.Screen cs = client.currentScreen;
		if (!(cs instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs)) return;
		String title = hs.getTitle().getString().toLowerCase();
		if (!title.contains("order")) return;
		if (now - lastOrdersScan < 500) return;
		lastOrdersScan = now;
		boolean personal = title.contains("your") || title.contains("my");
		String me = account == null ? "" : account.toLowerCase();

		java.util.List<NetworthData.OrderEntry> found = new java.util.ArrayList<>();
		for (net.minecraft.screen.slot.Slot s : hs.getScreenHandler().slots) {
			if (s.inventory == client.player.getInventory()) continue;
			net.minecraft.item.ItemStack st = s.getStack();
			if (st == null || st.isEmpty()) continue;
			var lore = st.get(net.minecraft.component.DataComponentTypes.LORE);
			if (lore == null) continue;
			int done = -1, total = -1, claim = 0; long perUnit = -1;
			boolean named = false, claimable = false;
			for (net.minecraft.text.Text line : lore.lines()) {
				String ln = line.getString().trim();
				var pm = ORD_PROGRESS.matcher(ln);
				if (pm.find()) { done = (int) parsePlain(pm.group(1)); total = (int) parsePlain(pm.group(2)); continue; }
				var um = ORD_PER_UNIT.matcher(ln);
				if (um.find()) { perUnit = Math.round(Double.parseDouble(um.group(1).replace(",", ""))); continue; }
				String low = ln.toLowerCase();

				if (!me.isEmpty() && ATTRIBUTION.matcher(low).find() && namedIn(low, me)) named = true;
				if (low.contains("claim")) {
					claimable = true;
					var cm = java.util.regex.Pattern.compile("([\\d,]+)").matcher(ln);
					if (cm.find()) claim = (int) parsePlain(cm.group(1));
				}
			}
			if (done < 0 || total <= 0 || perUnit <= 0) continue;
			if (!personal && !named) continue;
			if (claimable && claim == 0) claim = done;
			found.add(new NetworthData.OrderEntry(itemTicker(st), done, total, perUnit, claimable ? claim : 0, now));
		}

		NetworthData d = data();
		boolean changed = false;
		if (personal) {
			if (!sameOrders(d.myOrders, found)) { d.myOrders = new java.util.ArrayList<>(found); changed = true; }
		} else {
			for (NetworthData.OrderEntry oe : found) {
				boolean merged = false;
				for (int i = 0; i < d.myOrders.size(); i++) {
					if (d.myOrders.get(i).key().equals(oe.key())) {
						NetworthData.OrderEntry old = d.myOrders.get(i);
						if (old.done != oe.done || old.total != oe.total || old.claim != oe.claim) changed = true;
						d.myOrders.set(i, oe); merged = true; break;
					}
				}
				if (!merged) { d.myOrders.add(oe); changed = true; }
			}
		}

		if (d.myOrders.removeIf(oe -> now - oe.seenAt > ORDER_STALE_MS)) changed = true;
		if (changed || (!found.isEmpty() && d.myOrdersSeenAt == 0)) {
			d.myOrdersSeenAt = now; NetworthData.save(); snapshot(now);
		}
	}

	private static boolean sameOrders(java.util.List<NetworthData.OrderEntry> a, java.util.List<NetworthData.OrderEntry> b) {
		if (a == null || a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			NetworthData.OrderEntry x = a.get(i), y = b.get(i);
			if (!x.key().equals(y.key()) || x.done != y.done || x.total != y.total || x.claim != y.claim) return false;
		}
		return true;
	}

	public long orderEscrowValue() {
		long total = 0;
		for (NetworthData.OrderEntry oe : data().myOrders)
			total += Math.max(0, oe.total - oe.done) * oe.perUnit;
		return total;
	}

	public long orderClaimsValue() {
		long total = 0;
		for (NetworthData.OrderEntry oe : data().myOrders)
			if (oe.claim > 0) total += stackValue(oe.item, oe.claim);
		return total;
	}

	public long unclaimedValue() {
		long total = 0;

		for (NetworthData.Stack s : data().unclaimed) total += stackValue(s.item, s.qty);
		return total;
	}

	private static final String[] CUSTOM_ENCHANTS = {
			"adrenaline", "artemis", "ascent", "athletic", "autoloading holster", "autosmelt", "berserk",
			"bolt", "bulwark", "clapback", "cleaving", "compound", "concuss", "counter", "critical",
			"deflector", "delusional strength", "demolitionist", "endurance", "extinguish", "firefly",
			"first strike", "fling", "frenzy", "frost", "hardhat", "harmonic", "headless", "heavenly",
			"homing", "hook", "immunity", "impale", "infernal", "knockout", "lifebloom", "lifesteal",
			"low ground", "necromancer", "ninja", "patient swing", "planter", "pull", "quarry", "rally",
			"reaper", "reinforce", "replant", "sandpaper", "shock", "slash", "slayer", "sustenance",
			"tank", "timber", "trickster", "unbroken chain", "underdog", "unstable", "vein miner",
			"volley", "vortex", "weightless",
	};

	private static String matchEnchantLine(String rawLine) {
		String n = norm(rawLine);
		if (n.isEmpty()) return null;
		for (String enc : CUSTOM_ENCHANTS) if (n.equals(enc) || n.startsWith(enc + " ")) return enc;
		return null;
	}

	private static String encodeStack(net.minecraft.item.ItemStack st) {
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc == null || mc.world == null) return "";
			var ops = mc.world.getRegistryManager().getOps(net.minecraft.nbt.NbtOps.INSTANCE);
			return net.minecraft.item.ItemStack.CODEC.encodeStart(ops, st)
					.result().map(Object::toString).orElse("");
		} catch (Throwable t) { return ""; }
	}

	public String itemTicker(net.minecraft.item.ItemStack st) {
		boolean gear = st.contains(net.minecraft.component.DataComponentTypes.MAX_DAMAGE);
		if (!gear) return st.getName().getString();
		String base = st.getItem().getName().getString();
		var lore = st.get(net.minecraft.component.DataComponentTypes.LORE);
		if (lore == null) return base;
		java.util.TreeSet<String> found = new java.util.TreeSet<>();
		for (net.minecraft.text.Text line : lore.lines()) {
			String enc = matchEnchantLine(line.getString());
			if (enc != null) found.add(titleCase(enc));
		}
		return found.isEmpty() ? base : base + " · " + String.join(" + ", found);
	}

	private static String titleCase(String s) {
		StringBuilder sb = new StringBuilder();
		for (String w : s.split(" ")) { if (sb.length() > 0) sb.append(' '); sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
		return sb.toString();
	}

	public long inventoryValue(net.minecraft.inventory.Inventory inv) {
		if (inv == null) return 0;
		java.util.Map<String, Integer> agg = new java.util.LinkedHashMap<>();
		for (int i = 0; i < inv.size(); i++) addStack(agg, inv.getStack(i));
		long total = 0;
		for (var e : agg.entrySet()) total += stackValue(e.getKey(), e.getValue());
		return total;
	}

	public void onChat(String raw) {
		if (!onHome || raw == null || raw.isEmpty()) return;
		Matcher listed = LISTED.matcher(raw);
		if (listed.find()) {
			int qty = listed.group(1) != null ? safeInt(listed.group(1), 1) : 1;
			String item = listed.group(2).trim();
			long price = parsePlain(listed.group(3));
			NetworthData d = data();
			d.listings.add(new NetworthData.Listing(item, qty, price, System.currentTimeMillis()));
			NetworthData.save();
			snapshot(System.currentTimeMillis());
		}
	}

	private long lastHistScan = 0L;

	public volatile long lastSyncAt = 0L;
	public volatile int lastSyncCount = 0;
	public volatile boolean lastSyncHeadless = false;

	private long nextHistOpen = 0L, nextEcOpen = 0L, nextClaimsOpen = 30_000L;
	private boolean ecTried = false;

	public static volatile long playerMenuUntil = 0L;
	private volatile boolean pendingHeadless = false;
	private long pendingSince = 0L;
	private int headlessKind = 0;
	private String headlessTitle = "";
	private volatile net.minecraft.screen.ScreenHandler headlessHandler = null;
	private int headlessSyncId = -1;
	private long headlessSince = 0L;

	private final java.util.LinkedHashMap<String, HistEntry> histEntries = new java.util.LinkedHashMap<>();
	private int histPages = 0;
	private String histLastSig = "";
	private boolean histAwaitingPage = false;
	private long histPageDeadline = 0L;
	private long histSettleUntil = 0L;
	private static final int HIST_MAX_PAGES = 50;
	private static final long HIST_PAGE_SETTLE_MS = 250;
	private static final long HIST_PAGE_TIMEOUT_MS = 3000;

	private static final long HEADLESS_MAX_MS = 20_000L;
	private static final long HEADLESS_COOLDOWN_MS = 10_000L;
	private long nextHeadlessAllowed = 0L;

	private volatile long soundSuppressUntil = 0L;
	public boolean isHeadlessSoundSuppressed() {

		return pendingHeadless || headlessHandler != null || System.currentTimeMillis() < soundSuppressUntil;
	}

	public boolean interceptScreen(net.minecraft.client.gui.screen.Screen screen) {
		boolean cycleActive = pendingHeadless || (headlessHandler != null && headlessKind == 1);
		boolean target = cycleActive
				&& screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs
				&& hs.getTitle().getString().toLowerCase().contains(headlessTitle);
		if (target) {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.player == null) return false;
			net.minecraft.screen.ScreenHandler h =
					((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler();
			mc.player.currentScreenHandler = h;
			headlessHandler = h; headlessSyncId = h.syncId; headlessSince = System.currentTimeMillis();
			pendingHeadless = false;
			return true;
		}

		if (headlessHandler != null) abortHeadlessForPlayer();
		return false;
	}

	private void abortHeadlessForPlayer() {
		MinecraftClient mc = MinecraftClient.getInstance();
		long now = System.currentTimeMillis();
		if (headlessKind == 1) reconcileHistory(histEntries.values(), false, now);

		if (headlessHandler != null && mc.player != null && mc.player.networkHandler != null
				&& mc.player.currentScreenHandler == headlessHandler) {
			mc.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket(headlessSyncId));
			mc.player.currentScreenHandler = mc.player.playerScreenHandler;
		}
		histEntries.clear(); histPages = 0; histLastSig = ""; histAwaitingPage = false;
		headlessHandler = null; headlessSyncId = -1; headlessKind = 0; pendingHeadless = false;
		nextHeadlessAllowed = now + HEADLESS_COOLDOWN_MS;
		soundSuppressUntil = now + 300L;
		advertiseMenuBusy(0L);
	}

	private void closeHeadless(MinecraftClient client, long now) {

		if (headlessHandler != null && client.player != null && client.player.networkHandler != null
				&& client.player.currentScreenHandler == headlessHandler) {
			client.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket(headlessSyncId));
			if (client.currentScreen == null) client.player.currentScreenHandler = client.player.playerScreenHandler;
		}
		headlessHandler = null; headlessSyncId = -1; headlessKind = 0;
		histAwaitingPage = false;
		nextHeadlessAllowed = now + HEADLESS_COOLDOWN_MS;
		soundSuppressUntil = now + 300L;
		advertiseMenuBusy(0L);
	}

	private void finishHistory(MinecraftClient client, long now, boolean complete) {
		lastSyncHeadless = true;
		reconcileHistory(histEntries.values(), complete, now);
		histEntries.clear(); histPages = 0; histLastSig = ""; histAwaitingPage = false;
		closeHeadless(client, now);
	}

	private void handleHeadless(MinecraftClient client, long now) {
		if (client.player == null || client.player.networkHandler == null) return;

		if (now < playerMenuUntil && (headlessHandler != null || pendingHeadless)) {
			abortHeadlessForPlayer();
			return;
		}

		if (headlessHandler != null) {

			if (now - headlessSince > HEADLESS_MAX_MS) { abortHeadlessForPlayer(); return; }
			advertiseMenuBusy(now + 10_000L);

			if (client.currentScreen != null) {
				if (headlessKind == 1) reconcileHistory(histEntries.values(), false, now);
				closeHeadless(client, now);
				return;
			}
			if (headlessKind == 2 || headlessKind == 3) {
				if (now - headlessSince >= 700) {
					if (headlessKind == 2) commitEnderChest(headlessHandler, client.player.getInventory(), now);
					else commitClaims(headlessHandler, client.player.getInventory(), now);
					closeHeadless(client, now);
				}
				return;
			}

			if (now - headlessSince < 700) return;
			String sig = headlessSyncId + "|" + pageSig(headlessHandler);
			if (histAwaitingPage) {
				if (sig.equals(histLastSig)) {

					if (now >= histPageDeadline) finishHistory(client, now, true);
					return;
				}
				histAwaitingPage = false;
				histSettleUntil = now + HIST_PAGE_SETTLE_MS;
				histPageDeadline = now + HIST_PAGE_TIMEOUT_MS;
				return;
			}
			if (now < histSettleUntil) return;
			java.util.List<HistEntry> page = collectHistEntries(headlessHandler, client.player.getInventory());
			if (page.isEmpty()) {

				if (now >= (histPages == 0 ? headlessSince + 4000 : histPageDeadline)) finishHistory(client, now, histPages > 0);
				return;
			}
			for (HistEntry e : page) histEntries.putIfAbsent(e.fp(), e);
			histPages++;
			histLastSig = sig;
			int next = nextPageSlot(headlessHandler);
			if (next < 0) { finishHistory(client, now, true); return; }
			if (histPages >= HIST_MAX_PAGES || client.interactionManager == null) { finishHistory(client, now, false); return; }

			if (client.currentScreen != null || client.player.currentScreenHandler != headlessHandler) {
				abortHeadlessForPlayer();
				return;
			}

			net.minecraft.screen.slot.Slot pageBtn = headlessHandler.slots.get(next);
			if (pageBtn.inventory instanceof net.minecraft.entity.player.PlayerInventory
					|| !pageBtn.getStack().getName().getString().toLowerCase(Locale.ROOT).contains("next page")) {
				finishHistory(client, now, false);
				return;
			}
			soundSuppressUntil = now + HIST_PAGE_TIMEOUT_MS + 500L;
			client.interactionManager.clickSlot(headlessHandler.syncId, next, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, client.player);
			histAwaitingPage = true;
			histPageDeadline = now + HIST_PAGE_TIMEOUT_MS;
			return;
		}

		if (pendingHeadless) {
			if (now - pendingSince > 5000) {

				if (headlessKind == 2) {
					MarketConfig cfg = MarketConfig.get();
					if (++cfg.ecCmdFails >= 2) cfg.ecCmdDenied = true;
					MarketConfig.save();
				}
				pendingHeadless = false; headlessKind = 0; nextHeadlessAllowed = now + HEADLESS_COOLDOWN_MS;
				advertiseMenuBusy(0L);
			}
			return;
		}

		if (client.currentScreen != null || now < nextHeadlessAllowed) return;
		if (chatGuarded(now)) return;
		if (userTypedRecently()) return;
		if (!accurateEnabled) return;
		if (!isMainLobby(client)) return;
		if (sniperMenuBusy()) return;
		if (System.currentTimeMillis() < playerMenuUntil) return;

		if (!MarketConfig.get().ecCmdDenied && !ecTried
				&& data().ecSlots.isEmpty()) {
			ecTried = true;
			startHeadless(client, now, 2, "ender chest", "ec");
			return;
		}
		if (now >= nextHistOpen) {
			nextHistOpen = now + 60_000L;
			startHeadless(client, now, 1, "past listings", "ah history");
		} else if (now >= nextClaimsOpen) {

			nextClaimsOpen = now + 90_000L;
			startHeadless(client, now, 3, "claim", "ah claims");
		}
	}

	private static boolean sniperMenuBusy() {
		try {
			String v = System.getProperty("ahsniper.menuBusyUntil");
			return v != null && System.currentTimeMillis() < Long.parseLong(v);
		} catch (Exception e) { return false; }
	}
	private static void advertiseMenuBusy(long until) {
		System.setProperty("lsmarket.menuBusyUntil", String.valueOf(until));
	}

	private void startHeadless(MinecraftClient client, long now, int kind, String title, String command) {
		advertiseMenuBusy(now + 15_000L);
		pendingHeadless = true; pendingSince = now; headlessKind = kind; headlessTitle = title;
		histEntries.clear(); histPages = 0; histLastSig = ""; histAwaitingPage = false; histSettleUntil = 0L;
		soundSuppressUntil = now + 5500L;
		silentCmdUntil = now + 2500L;
		SilentCmd.send(client, command);
	}

	private static String pageSig(net.minecraft.screen.ScreenHandler handler) {
		StringBuilder sb = new StringBuilder();
		for (net.minecraft.screen.slot.Slot s : handler.slots) {
			if (s.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			net.minecraft.item.ItemStack st = s.getStack();
			if (st.isEmpty()) { sb.append('.'); continue; }
			sb.append(st.getName().getString()).append(st.getCount()).append(';');
		}
		return Integer.toHexString(sb.toString().hashCode());
	}

	private static int nextPageSlot(net.minecraft.screen.ScreenHandler handler) {
		var slots = handler.slots;
		for (int i = 0; i < slots.size(); i++) {
			net.minecraft.screen.slot.Slot s = slots.get(i);
			if (s.inventory instanceof net.minecraft.entity.player.PlayerInventory) continue;
			net.minecraft.item.ItemStack st = s.getStack();
			if (st.isEmpty()) continue;
			if (st.getName().getString().toLowerCase(Locale.ROOT).contains("next page")) return i;
		}
		return -1;
	}

	private void scanPastListings(MinecraftClient client, long now) {
		net.minecraft.client.gui.screen.Screen cs = client.currentScreen;
		if (!(cs instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs)
				|| !hs.getTitle().getString().toLowerCase().contains("past listings")) return;
		if (now - lastHistScan < 500) return;
		lastHistScan = now;
		lastSyncHeadless = false;
		reconcileHistory(collectHistEntries(hs.getScreenHandler(), client.player.getInventory()), false, now);
	}

	private record HistEntry(String type, String item, int qty, long price, String createdAt) {
		String fp() { return type + "|" + item + "|" + price + "|" + createdAt; }
	}

	private java.util.List<HistEntry> collectHistEntries(net.minecraft.screen.ScreenHandler handler, net.minecraft.inventory.Inventory pinv) {
		java.util.List<HistEntry> out = new java.util.ArrayList<>();
		for (net.minecraft.screen.slot.Slot s : handler.slots) {
			if (s.inventory == pinv) continue;
			net.minecraft.item.ItemStack st = s.getStack();
			if (st == null || st.isEmpty()) continue;
			var lore = st.get(net.minecraft.component.DataComponentTypes.LORE);
			if (lore == null) continue;
			String type = null, createdAt = ""; long price = -1;
			for (net.minecraft.text.Text line : lore.lines()) {
				String ln = line.getString().trim(), low = ln.toLowerCase();
				if (low.startsWith("type:")) type = low.substring(5).trim();
				else if (low.startsWith("price:")) { var m = java.util.regex.Pattern.compile("([\\d,]+)").matcher(ln); if (m.find()) price = parsePlain(m.group(1)); }
				else if (low.startsWith("created at:")) createdAt = ln.substring(ln.indexOf(':') + 1).trim();
			}
			if (type == null) continue;
			out.add(new HistEntry(type, st.getName().getString(), Math.max(1, st.getCount()), price, createdAt));
		}
		return out;
	}

	private void reconcileHistory(java.util.Collection<HistEntry> entries, boolean complete, long now) {
		if (entries.isEmpty()) return;
		NetworthData d = data();
		java.util.List<NetworthData.Listing> actives = new java.util.ArrayList<>();
		java.util.List<String[]> closers = new java.util.ArrayList<>();
		boolean changed = false;

		for (HistEntry e : entries) {
			String fp = e.fp();
			if (e.type().contains("created listing")) {
				actives.add(new NetworthData.Listing(e.item(), e.qty(), e.price(), 0));
			} else if (e.type().contains("sold item") || e.type().contains("deleted listing")) {
				closers.add(new String[]{ e.item(), String.valueOf(e.price()) });
				if (e.type().contains("sold item") && e.price() > 0 && !d.processed.contains(fp)) {
					d.realizedProfit += e.price(); d.processed.add(fp); changed = true;
					d.trades.add(new NetworthData.Trade(e.item(), e.price() / e.qty(), false, estTradeTime(e.createdAt(), now)));
				}
			} else if (e.type().contains("purchased item")) {
				if (e.price() > 0 && !d.processed.contains(fp)) { d.spent += e.price(); d.processed.add(fp); changed = true;
					d.trades.add(new NetworthData.Trade(e.item(), e.price() / e.qty(), true, estTradeTime(e.createdAt(), now)));
				}
			}
		}
		lastSyncAt = now; lastSyncCount = entries.size();

		if (complete) {

			for (String[] c : closers) {
				for (java.util.Iterator<NetworthData.Listing> it = actives.iterator(); it.hasNext(); ) {
					NetworthData.Listing l = it.next();
					if (l.item.equals(c[0]) && String.valueOf(l.price).equals(c[1])) { it.remove(); break; }
				}
			}
			if (!sameListings(d.listings, actives)) { d.listings = actives; changed = true; }
		}
		if (d.processed.size() > 4000) d.processed.subList(0, d.processed.size() - 4000).clear();
		if (d.trades.size() > 2000) d.trades.subList(0, d.trades.size() - 2000).clear();
		if (changed) { NetworthData.save(); snapshot(now); }
	}

	private static boolean sameListings(java.util.List<NetworthData.Listing> a, java.util.List<NetworthData.Listing> b) {
		if (a == null || a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (!a.get(i).item.equals(b.get(i).item) || a.get(i).price != b.get(i).price || a.get(i).qty != b.get(i).qty) return false;
		}
		return true;
	}

	public long unitPrice(String item) {
		long now = System.currentTimeMillis();

		long v = resolve(prices, norm(item), now);
		if (v >= 0) return v;

		int cut = item.indexOf('·');
		if (cut > 0) {
			long composed = 0; boolean any = false;
			long base = resolve(priceByBase, norm(baseName(item)), now);
			if (base >= 0) { composed += base; any = true; }
			for (String enc : item.substring(cut + 1).split("\\+")) {
				String e = enc.trim();
				if (e.isEmpty()) continue;
				long ev = resolve(prices, norm(e), now);
				if (ev >= 0) { composed += ev; any = true; }
			}
			if (any) return composed;
		}

		long basePrice = resolve(priceByBase, norm(baseName(item)), now);
		if (basePrice >= 0) return basePrice;

		com.auctionsniper.MarketApi.RemotePrice shared = com.auctionsniper.MarketApi.price(item);
		if (shared == null && cut > 0) shared = com.auctionsniper.MarketApi.price(baseName(item));
		if (shared != null && shared.average() > 0 && shared.samples() > 0) return shared.average();
		return -1;
	}

	private long resolve(Map<String, long[]> map, String key, long now) {
		long[] p = map.get(key);

		boolean realSales = p != null && p.length > 3 && p[3] > 0;
		long live = (p != null && p[0] > 0 && realSales) ? p[0] : -1;
		long saved = PriceCache.get(key, now);
		if (p != null && p[2] >= 2) return live >= 0 ? live : saved;
		if (saved >= 0) return saved;
		return live;
	}

	private static final String FANCY_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡʏᴢғ";
	private static final String PLAIN_CAPS = "abcdefghijklmnopqrstuvwyzf";
	private static String foldFancy(String s) {
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			int j = FANCY_CAPS.indexOf(c);
			b.append(j >= 0 ? PLAIN_CAPS.charAt(j) : c);
		}
		return b.toString();
	}

	public static String norm(String s) {
		if (s == null) return "";
		s = foldFancy(s.replaceAll("(?i)§.", ""));
		s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}
	private static String baseName(String ticker) {
		int i = ticker.indexOf('·');
		return i > 0 ? ticker.substring(0, i) : ticker;
	}

	private static long estTradeTime(String createdAt, long now) {
		if (createdAt == null || createdAt.isBlank()) return now;
		Matcher m = Pattern.compile("(\\d+)\\s*([smhd])").matcher(createdAt.toLowerCase(Locale.ROOT));
		long off = 0; boolean any = false;
		while (m.find()) {
			any = true; long n = safeInt(m.group(1), 0);
			off += switch (m.group(2)) { case "s" -> n * 1000L; case "m" -> n * 60_000L; case "h" -> n * 3_600_000L; default -> n * 86_400_000L; };
		}
		return any ? now - off : now;
	}

	public java.util.List<long[]> tradesFor(String ticker) {
		java.util.List<long[]> out = new java.util.ArrayList<>();
		String tn = norm(ticker), base = norm(baseName(ticker));
		for (NetworthData.Trade tr : data().trades) {
			String in = norm(tr.item);
			if (in.equals(tn) || in.equals(base)) out.add(new long[]{ tr.t, tr.price, tr.buy ? 1 : 0 });
		}
		return out;
	}

	public long netWorth() {
		long total = Math.max(0, coins) + listingsValue() + enderchestValue() + invValue + unclaimedValue()
				+ orderEscrowValue() + orderClaimsValue();
		return total;
	}

	public long listingsValue() {
		long total = 0;

		for (NetworthData.Listing l : data().listings) total += stackValue(l.item, l.qty);
		return total;
	}

	private void snapshot(long now) {
		if (coins < 0 || prices.isEmpty()) return;
		NetworthData d = data();
		long v = netWorth();

		if (!d.history.isEmpty()) {
			NetworthData.Snap last = d.history.get(d.history.size() - 1);
			if (last.value == v && now - last.t < 5_000L) return;
		}
		d.history.add(new NetworthData.Snap(now, v));

		if (d.history.size() > 5000) d.history.subList(0, d.history.size() - 5000).clear();
		NetworthData.save();
	}

	private void fetchPrices(MinecraftClient client) {
		fetching = true;
		String url = MarketConfig.apiBase() + "/api/tickers";
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(java.time.Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId())
				.header("X-User", com.lifestealmarket.MarketConfig.userName())
				.header("X-Token", com.lifestealmarket.MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString())
				.whenComplete((resp, err) -> {
					fetching = false;
					if (err != null || resp.statusCode() != 200) return;
					try {
						var arr = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonArray();
						Map<String, long[]> m = new HashMap<>();
						Map<String, long[]> mb = new HashMap<>();
						long now = System.currentTimeMillis();
						for (var e : arr) {
							var o = e.getAsJsonObject();
							String t = o.get("ticker").getAsString();
							long last = o.get("last").getAsLong();
							boolean firm = o.has("firm") && o.get("firm").getAsBoolean();

							long supply = o.has("supply") && !o.get("supply").isJsonNull() ? o.get("supply").getAsLong() : 0;

							long sales = o.has("sales") && !o.get("sales").isJsonNull() ? o.get("sales").getAsLong() : 0;
							long[] val = new long[]{ last, firm ? 1 : 0, supply, sales };
							m.put(norm(t), val);

							if (supply >= 2 && last > 0 && sales > 0) PriceCache.put(norm(t), last, now);
							String base = norm(baseName(t));
							long[] prev = mb.get(base);

							if (prev == null || (val[1] == 1 && prev[1] == 0) || (val[1] == prev[1] && val[0] > prev[0])) mb.put(base, val);
						}
						prices = m;
						priceByBase = mb;
						PriceCache.save();
					} catch (Exception ignored) {}
				});
	}

	private static final java.util.regex.Pattern ATTRIBUTION = java.util.regex.Pattern.compile(
			"seller|owner|ordered by|listed by|placed by|created by|buyer|player:");

	static boolean namedIn(String line, String name) {
		if (line == null || name == null || name.isEmpty()) return false;
		int from = 0, i;
		while ((i = line.indexOf(name, from)) >= 0) {
			boolean leftOk = i == 0 || !isNameChar(line.charAt(i - 1));
			int end = i + name.length();
			boolean rightOk = end >= line.length() || !isNameChar(line.charAt(end));
			if (leftOk && rightOk) return true;
			from = i + 1;
		}
		return false;
	}

	private static boolean isNameChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
	}

	private static long parsePlain(String s) {
		try { return Long.parseLong(s.replace(",", "").trim()); } catch (Exception e) { return 0; }
	}
	private static int safeInt(String s, int def) {
		try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
	}

	private static long parseCoins(String num, String suffix) {
		try {
			double v = Double.parseDouble(num.replace(",", "").trim());
			if (suffix != null) v *= switch (suffix.toUpperCase()) {
				case "K" -> 1_000d; case "M" -> 1_000_000d; case "B" -> 1_000_000_000d; default -> 1d;
			};
			return Math.round(v);
		} catch (Exception e) { return -1; }
	}
}

