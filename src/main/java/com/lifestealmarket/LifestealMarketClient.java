package com.lifestealmarket;

import com.lifestealmarket.gui.StockScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;

public class LifestealMarketClient implements ClientModInitializer {
	static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
			.connectTimeout(java.time.Duration.ofSeconds(6)).build();
	private long nextAlertCheck = 0L;
	private volatile boolean alertFetching = false;

	private final java.util.ArrayDeque<Object[]> toastQueue = new java.util.ArrayDeque<>();
	private long nextToastAt = 0L;

	private PriceStream priceStream;

	public static volatile long DATA_VERSION = 0L;

	private long nextPresencePost = 0L, nextPresencePoll = 0L, nextAlive = 0L;
	private String lastPostedTuple = "";
	private boolean updateChecked = false;
	private long nextAccessCheck = 0L;
	private boolean provisionTried = false;

	private long nextUpdateRetry = 0L, nextProvisionRetry = 0L;
	private int updateFails = 0, provisionFails = 0;
	private static final long RETRY_BASE_MS = 5_000L, RETRY_MAX_MS = 300_000L;
	private static long backoff(int fails) {
		return Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(fails, 6));
	}
	private boolean alertsSynced = false;
	private boolean welcomed = false;
	private long welcomeAt = 0L;
	public static volatile boolean forceWelcome = false;

	public static volatile String refTagNow = null;
	public static volatile int refNextAt = 0;
	public static volatile String refNextTag = null;

	private static volatile boolean onboardAlways = false;
	private boolean onboardApplied = false;
	private boolean inGame = false;
	public static volatile boolean updateAvailable = false;
	public static volatile String updateVersion = "";
	public static volatile String updateUrl = "";

	@Override
	public void onInitializeClient() {
		MarketConfig.load();
		ModSounds.register();

		Discovery.resolve(true);


		priceStream = new PriceStream(prices -> {
			DATA_VERSION++;
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.execute(() -> {
				MarketConfig cfg = MarketConfig.get();
				if (mc.player != null && !cfg.alerts.isEmpty()) evaluateAlerts(mc, cfg, prices);
			});
		});
		priceStream.start();

		var cat = net.minecraft.client.option.KeyBinding.Category.create(net.minecraft.util.Identifier.of("lifestealmarket", "main"));
		net.minecraft.client.option.KeyBinding openKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
				new net.minecraft.client.option.KeyBinding("key.lifestealmarket.open",
						net.minecraft.client.util.InputUtil.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_M, cat));
		net.minecraft.client.option.KeyBinding netKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
				new net.minecraft.client.option.KeyBinding("key.lifestealmarket.networth",
						net.minecraft.client.util.InputUtil.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_N, cat));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				if (client.currentScreen == null) client.setScreen(new StockScreen(null, ""));
			}
			while (netKey.wasPressed()) {
				if (client.currentScreen == null) client.setScreen(new com.lifestealmarket.gui.NetworthScreen(null));
			}
			MarketConfig.tickThemeTry();
			Discovery.flushNotice();
			trackJoin(client);
			rememberOpenContainer(client);
			NetworthTracker.INSTANCE.tick(client);
			TeamTracker.INSTANCE.tick(client, System.currentTimeMillis());

			com.lifestealmarket.gui.OrdersNav.tick(client, System.currentTimeMillis());
			tickAlerts(client);
			tickPresence(client);
			checkForUpdate(client);
			checkAccess(client);
			applyAlwaysOnboard(client);
			checkCosmetics(client);
			Flair.poll(client, System.currentTimeMillis());
			maybeWelcome(client);
			maybeProvision(client);

			if (NetworthTracker.INSTANCE.pendingTos && client.currentScreen == null
					&& client.player != null && NetworthTracker.isHomeServer(client)) {
				client.setScreen(new com.lifestealmarket.gui.TermsScreen());
			}
		});

		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
				(handler, client) -> ServerCommands.clear());

		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) NetworthTracker.INSTANCE.onChat(message.getString());
		});

		net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.COMMAND.register(c -> {

			if (SilentCmd.wasSelf(c)) return;
			NetworthTracker.INSTANCE.notePlayerSent();
			TeamTracker.INSTANCE.notePlayerRan(c);
		});
		net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.CHAT.register(c -> NetworthTracker.INSTANCE.notePlayerSent());

		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
				overlay || (TeamTracker.INSTANCE.onMessage(message)
						&& NetworthTracker.INSTANCE.allowMessage(message.getString())));

		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) ->
				overlay ? message : decorateChatNetworth(message));

		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (com.lifestealmarket.gui.NavBar.idOf(screen) == null) return;
			net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.afterKeyPress(screen).register((sc, key) -> {

				if (sc.getFocused() instanceof net.minecraft.client.gui.widget.TextFieldWidget tf
						&& !tf.getText().isEmpty()) return;
				if (sc instanceof com.lifestealmarket.gui.ShopScreen shop && shop.typing()) return;
				if (key.key() == 262) com.lifestealmarket.gui.NavBar.arrow(sc, 1);
				else if (key.key() == 263) com.lifestealmarket.gui.NavBar.arrow(sc, -1);
			});
		});

		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			String title = (screen != null && screen.getTitle() != null) ? screen.getTitle().getString().toLowerCase() : "";

			boolean ec = title.contains("ender chest")
					&& screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>;
			boolean inv = screen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen;

			if (!ec && !inv) return;
			net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register((scr, ctx, mx, my, delta) -> {
				if (!NetworthTracker.INSTANCE.onHome() || client.player == null) return;

				String s = ec
						? "§6✦ Ender Chest: §f" + Coins.fmt(NetworthTracker.INSTANCE.enderchestValue())
						: "§6✦ Inventory Value: §f" + Coins.fmt(NetworthTracker.INSTANCE.cachedInventoryValue());
				var tr = client.textRenderer;
				int tw = tr.getWidth(net.minecraft.text.Text.literal(s));
				ctx.drawTextWithShadow(tr, net.minecraft.text.Text.literal(s), scr.width - tw - 8, 6, 0xFFFFFFFF);
			});
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
			dispatcher.register(ClientCommandManager.literal("ahsniper")
				.then(ClientCommandManager.literal("market").executes(ctx -> {
					MinecraftClient mc = MinecraftClient.getInstance();
					mc.execute(() -> mc.setScreen(new com.lifestealmarket.gui.MarketScreen(null)));
					return 1;
				}))
				.then(ClientCommandManager.literal("lsmarket").executes(ctx -> {
					MinecraftClient mc = MinecraftClient.getInstance();
					mc.execute(() -> mc.setScreen(new com.lifestealmarket.gui.MarketScreen(null)));
					return 1;
				}))
				.then(ClientCommandManager.literal("stock")
					.then(ClientCommandManager.argument("item", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
						.executes(ctx -> {
							String item = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item").trim();
							MinecraftClient mc = MinecraftClient.getInstance();
						mc.execute(() -> mc.setScreen(new StockScreen(null, item)));
							return 1;
					}))
					.executes(ctx -> {
						MinecraftClient mc = MinecraftClient.getInstance();
						mc.execute(() -> mc.setScreen(new StockScreen(null, "")));
						return 1;
					}))
				.then(ClientCommandManager.literal("networth")
					.then(ClientCommandManager.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((ctx, builder) -> {
							for (String n : NetworthTracker.INSTANCE.publicPlayerNames())
								if (n.toLowerCase().startsWith(builder.getRemainingLowerCase())) builder.suggest(n);
							return builder.buildFuture();
						})
						.executes(ctx -> {
							lookupNetworth(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"));
							return 1;
						}))
					.executes(ctx -> {
						MinecraftClient mc = MinecraftClient.getInstance();
						mc.execute(() -> mc.setScreen(new com.lifestealmarket.gui.NetworthScreen(null)));
						return 1;
					}))
				.then(ClientCommandManager.literal("buy")
					.then(ClientCommandManager.argument("item", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
						.executes(ctx -> {
							String q = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item").trim();
							MinecraftClient mc = MinecraftClient.getInstance();
						mc.execute(() -> mc.setScreen(new com.lifestealmarket.gui.BuyNowScreen(null, q)));
							return 1;
					}))
					.executes(ctx -> {
						MinecraftClient mc = MinecraftClient.getInstance();
						mc.execute(() -> mc.setScreen(new com.lifestealmarket.gui.BuyNowScreen(null, "")));
						return 1;
					}))
				.then(ClientCommandManager.literal("sell").executes(ctx -> {
					MinecraftClient mc = MinecraftClient.getInstance();
					mc.execute(() -> mc.setScreen(new com.lifestealmarket.gui.SellNowScreen(null)));
					return 1;
				}))
				.then(ClientCommandManager.literal("shop").executes(ctx -> {
					MinecraftClient mc = MinecraftClient.getInstance();
					mc.execute(() -> mc.setScreen(new com.lifestealmarket.gui.ShopScreen(null)));
					return 1;
				}))
				.then(ClientCommandManager.literal("customize").executes(ctx -> {
					MinecraftClient mc = MinecraftClient.getInstance();
					mc.execute(() -> mc.setScreen(new com.lifestealmarket.gui.CustomizeScreen(null)));
					return 1;
				}))
				.then(ClientCommandManager.literal("leaderboard").executes(ctx -> {
					MinecraftClient mc = MinecraftClient.getInstance();
					mc.execute(() -> mc.setScreen(new com.lifestealmarket.gui.LeaderboardScreen(null)));
					return 1;
				}))
				.then(ClientCommandManager.literal("link")
					.then(ClientCommandManager.argument("code", com.mojang.brigadier.arguments.StringArgumentType.word())
						.executes(ctx -> {
							linkDiscord(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "code").trim());
							return 1;
					})))
				.then(ClientCommandManager.literal("referral")
					.then(ClientCommandManager.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
						.executes(ctx -> {
							creditReferrer(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player").trim());
							return 1;
					}))
					.executes(ctx -> { showReferralStatus(); return 1; }))
				.then(ClientCommandManager.literal("help").executes(ctx -> {
					MinecraftClient mc = MinecraftClient.getInstance();
					if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal(
							"§b[AuctionSniper] §fmarket, stock [item], networth [player], buy [item], sell, shop, customize, leaderboard, link <code>, referral [player]"), false);
					return 1;
				}))
			);
		});
	}

	private static void shortcut(com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher,
			String name, java.util.function.Supplier<net.minecraft.client.gui.screen.Screen> screen) {
		dispatcher.register(ClientCommandManager.literal(name).executes(ctx -> {
			if (serverOwns(name)) throw passToServer();
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.execute(() -> mc.setScreen(screen.get()));
			return 1;
		}));
	}

	private static boolean serverOwns(String name) {
		return ServerCommands.has(name);
	}

	private static com.mojang.brigadier.exceptions.CommandSyntaxException passToServer() {
		return com.mojang.brigadier.exceptions.CommandSyntaxException.BUILT_IN_EXCEPTIONS
				.dispatcherUnknownCommand().create();
	}

	private void maybeProvision(MinecraftClient client) {
		if (client.player == null || !NetworthTracker.isHomeServer(client)) return;
		if (!MarketConfig.token().isBlank()) { provisionTried = true; return; }
		// Worker account tokens are issued only after Mojang session verification.
		// Never fall back to username-only provisioning.
		MojangAuth.ensureToken(client);
	}

	private long nextCosmeticsCheck = 0L;

	private void checkCosmetics(MinecraftClient client) {
		if (client.player == null || !NetworthTracker.isHomeServer(client)) return;
		long now = System.currentTimeMillis();
		if (now < nextCosmeticsCheck) return;
		nextCosmeticsCheck = now + 60_000L;
		String url = MarketConfig.apiBase() + "/api/cosmetics";
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8))
				.header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId())
				.header("X-User", MarketConfig.userName())
				.header("X-Token", MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) return;
			try {
				var root = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
				var arr = root.getAsJsonArray("themes");
				java.util.Map<String, MarketConfig.OwnedTheme> m = new java.util.HashMap<>();
				for (var el : arr) {
					var o = el.getAsJsonObject();
					String id = o.get("id").getAsString();
					String name = o.has("name") ? o.get("name").getAsString() : id;
					String hex = o.get("hex").getAsString().replace("#", "").trim();
					long v = Long.parseLong(hex, 16);
					int argb = hex.length() > 6 ? (int) v : (0xFF000000 | (int) v);
					String bg = o.has("bg") ? o.get("bg").getAsString() : "";
					m.put(id.toLowerCase(java.util.Locale.ROOT), new MarketConfig.OwnedTheme(id, name, argb, bg));
				}
				MarketConfig.setOwnedThemes(m);

				if (root.has("items")) {
					java.util.List<MarketConfig.OwnedItem> items = new java.util.ArrayList<>();
					for (var el : root.getAsJsonArray("items")) {
						var o = el.getAsJsonObject();
						items.add(new MarketConfig.OwnedItem(o.get("id").getAsString(), o.get("type").getAsString(),
								o.has("name") ? o.get("name").getAsString() : o.get("id").getAsString(),
								o.has("payload") ? o.get("payload").getAsString() : ""));
					}
					MarketConfig.setOwnedItems(items);
				}
				if (root.has("loyalty")) {
					var lo = root.getAsJsonObject("loyalty");
					MarketConfig.loyaltyDays = lo.get("days").getAsInt();
					MarketConfig.loyaltyGranted = lo.get("granted").getAsBoolean();
				}
			} catch (Exception ignored) {}
		});
	}

	private void checkAccess(MinecraftClient client) {
		if (client.player == null || !NetworthTracker.isHomeServer(client)) return;
		long now = System.currentTimeMillis();
		if (now < nextAccessCheck) return;
		nextAccessCheck = now + 30_000L;
		String ign = client.getSession().getUsername();
		String url = MarketConfig.apiBase() + "/api/access?user="
				+ java.net.URLEncoder.encode(ign, java.nio.charset.StandardCharsets.UTF_8);
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8))
				.header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId()).header("X-Token", MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) return;
			try {
				var obj = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();

				if (obj.has("tokenOwner") && !obj.get("tokenOwner").isJsonNull()) {
					String tokenOwner = obj.get("tokenOwner").getAsString();
					if (!tokenOwner.equalsIgnoreCase(ign)) {
						System.out.println("[LifestealMarket] this token belongs to " + tokenOwner
								+ ", not " + ign + " - re-verifying with Mojang");
						MarketConfig.get().accessToken = "";
						MarketConfig.save();
						MojangAuth.ensureToken(MinecraftClient.getInstance());
					}
				}
				forceWelcome = obj.has("welcome") && obj.get("welcome").getAsBoolean();
				onboardAlways = obj.has("onboardAlways") && obj.get("onboardAlways").getAsBoolean();
				refNextAt = obj.has("refNext") ? obj.get("refNext").getAsInt() : 0;
				refNextTag = obj.has("refNextTag") && !obj.get("refNextTag").isJsonNull()
						? obj.get("refNextTag").getAsString() : null;

				if (obj.has("onboardReset")) {
					int epoch = obj.get("onboardReset").getAsInt();
					if (epoch > MarketConfig.get().onboardResetSeen) {
						MarketConfig.get().onboardResetSeen = epoch;
						MarketConfig.get().tosAccepted = false;
						MarketConfig.get().tutorialDone = false;
						MarketConfig.save();
						NetworthTracker.INSTANCE.pendingTos = true;
						welcomed = false;
					}
				}

				if (obj.has("refs")) {
					int refs = obj.get("refs").getAsInt();
					String tag = obj.has("refTag") && !obj.get("refTag").isJsonNull() ? obj.get("refTag").getAsString() : null;
					refTagNow = tag;
					int last = MarketConfig.get().lastReferrals;
					if (last >= 0 && refs > last) {
						String tier = tag != null ? "  §7You're now " + tag.trim() : "";
						final String msg = "§b[Market] §fNew referral! §7You have §f" + refs + "§7 invited friend" + (refs == 1 ? "" : "s") + " linked." + tier;
						MinecraftClient mc = MinecraftClient.getInstance();
						mc.execute(() -> { if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal(msg), false); });
					}
					if (refs != last) { MarketConfig.get().lastReferrals = refs; MarketConfig.save(); }
				}
			} catch (Exception ignored) {}
		});
	}

	private long nextRemember = 0L;
	private net.minecraft.client.gui.screen.Screen lastRememberScreen = null;

	private void rememberOpenContainer(MinecraftClient client) {

		long now = System.currentTimeMillis();
		if (client.currentScreen == lastRememberScreen && now < nextRemember) return;
		lastRememberScreen = client.currentScreen;
		nextRemember = now + 500L;
		if (client.player == null || !NetworthTracker.INSTANCE.onHome()) return;
		if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs)) return;
		try {
			for (net.minecraft.screen.slot.Slot slot : hs.getScreenHandler().slots) {
				net.minecraft.item.ItemStack st = slot.getStack();
				if (st == null || st.isEmpty()) continue;
				com.lifestealmarket.gui.ItemIcons.remember(NetworthTracker.INSTANCE.itemTicker(st), st);
			}
		} catch (Exception ignored) {}
	}

	private void trackJoin(MinecraftClient client) {
		boolean now = client.player != null;
		if (now == inGame) return;
		inGame = now;
		if (!now) return;
		welcomed = false; welcomeAt = 0L;
		onboardApplied = false;
	}

	private void applyAlwaysOnboard(MinecraftClient client) {
		if (!onboardAlways || onboardApplied || client.player == null
				|| !NetworthTracker.isHomeServer(client)) return;
		onboardApplied = true;
		MarketConfig.get().tutorialDone = false;
		MarketConfig.get().tosAccepted = false;
		com.lifestealmarket.gui.Tutorial.step = 0;
		NetworthTracker.INSTANCE.pendingTos = true;
		welcomed = false; welcomeAt = 0L;
	}

	private void maybeWelcome(MinecraftClient client) {
		if (welcomed || client.player == null || !NetworthTracker.isHomeServer(client)) return;
		if (MarketConfig.get().tutorialDone && !forceWelcome) { welcomed = true; return; }
		long now = System.currentTimeMillis();
		if (welcomeAt == 0L) { welcomeAt = now + 8000L; return; }
		if (now < welcomeAt) return;
		welcomed = true;
		long nw = NetworthTracker.INSTANCE.netWorth();
		String worth = nw > 0 ? " We already tracked your net worth: §a" + Coins.fmt(nw) + "§7." : "";
		net.minecraft.text.Text tour = net.minecraft.text.Text.literal(" §b§n[Start tour]")
				.setStyle(net.minecraft.text.Style.EMPTY
						.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/stock"))
						.withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(net.minecraft.text.Text.literal("§7Opens /stock and starts a 30-second tour"))));
		client.player.sendMessage(net.minecraft.text.Text.literal(
				"§b[Lifesteal Market] §fWelcome, §e" + client.getSession().getUsername() + "§f!§7" + worth).append(tour), false);

		net.minecraft.text.Text credit = net.minecraft.text.Text.literal("§b§n[Say who invited you]")
				.setStyle(net.minecraft.text.Style.EMPTY
						.withClickEvent(new net.minecraft.text.ClickEvent.SuggestCommand("/referral "))
						.withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(net.minecraft.text.Text.literal(
								"§7Type their name after the command.\n§8They earn a nametag tag — you keep everything."))));
		client.player.sendMessage(net.minecraft.text.Text.literal(
				"§b[Lifesteal Market] §7A friend told you about the mod? ").append(credit), false);
	}

	private void checkForUpdate(MinecraftClient client) {
		if (updateChecked || client.player == null || !NetworthTracker.isHomeServer(client)) return;
		if (System.currentTimeMillis() < nextUpdateRetry) return;
		updateChecked = true;
		final String cur = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("lifestealmarket")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("1.0.0");
		String ign = client.getSession().getUsername();
		String base = MarketConfig.apiBase();
		String url = base + "/api/version?user="
				+ java.net.URLEncoder.encode(ign, java.nio.charset.StandardCharsets.UTF_8)
				+ "&current=" + java.net.URLEncoder.encode(cur, java.nio.charset.StandardCharsets.UTF_8);
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8))
				.header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId()).GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) {

				Discovery.noteFail();
				nextUpdateRetry = System.currentTimeMillis() + backoff(updateFails++);
				updateChecked = false;
				return;
			}
			Discovery.noteOk();
			updateFails = 0;
			try {
				var o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();

				if (o.has("request_url")) MarketConfig.setRequestUrl(o.get("request_url").getAsString());
				String ver = o.get("version").getAsString();
				boolean announce = o.has("announce") && o.get("announce").getAsBoolean();

				String curBase = cur.contains("+") ? cur.substring(0, cur.indexOf('+')) : cur;
				boolean same = ver == null || ver.isBlank() || ver.equals(cur) || ver.equals(curBase);
				if (!announce && same) return;
				updateAvailable = true; updateVersion = (ver == null || ver.isBlank()) ? cur : ver;
				if (o.has("url")) updateUrl = base + o.get("url").getAsString();
				if (same) return;
				String dl = updateUrl;
				boolean beta = o.has("beta") && o.get("beta").getAsBoolean();
				String notes = o.has("notes") ? o.get("notes").getAsString() : "";
				MinecraftClient mc = MinecraftClient.getInstance();
				mc.execute(() -> {
					if (mc.player == null) return;
					net.minecraft.text.Text link = net.minecraft.text.Text.literal("§a§n[Download v" + ver + "]")
							.setStyle(net.minecraft.text.Style.EMPTY
									.withClickEvent(new net.minecraft.text.ClickEvent.OpenUrl(java.net.URI.create(dl)))
									.withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(
											net.minecraft.text.Text.literal("§7Click to download" + (notes.isBlank() ? "" : "\n§8" + notes)))));
					net.minecraft.text.Text msg = net.minecraft.text.Text.literal(
							"§b[Lifesteal Market] §f" + (beta ? "Beta " : "") + "update available: §e" + ver + " ").append(link);
					mc.player.sendMessage(msg, false);
				});
			} catch (Exception ignored) {}
		});
	}

	private void lookupNetworth(String player) {
		String url = MarketConfig.apiBase() + "/api/networth?player="
				+ java.net.URLEncoder.encode(player, java.nio.charset.StandardCharsets.UTF_8);
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(java.time.Duration.ofSeconds(8)).header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId()).GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.execute(() -> {
				if (mc.player == null) return;
				String msg;
				try {
					if (err != null || resp.statusCode() != 200) throw new Exception();
					var o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
					if (!o.get("found").getAsBoolean()) msg = "§7No net worth data for §f" + player;
					else if (o.has("private") && o.get("private").getAsBoolean()) msg = "§f" + player + " §7keeps their net worth private.";
					else msg = "§e" + o.get("name").getAsString() + "'s net worth: §a" + Coins.fmt(o.get("networth").getAsLong())
							+ " §8(#" + o.get("rank").getAsInt() + ")";
				} catch (Exception e) { msg = "§cCouldn't reach the net worth server."; }
				mc.player.sendMessage(net.minecraft.text.Text.literal(msg), false);
			});
		});
	}

	private static Long nzp(long v) { return v > 0 ? v : null; }

	private static net.minecraft.text.Text decorateChatNetworth(net.minecraft.text.Text message) {
		try {
			NetworthTracker nt = NetworthTracker.INSTANCE;
			if (!nt.onHome()) return message;
			String raw = message.getString();
			if (raw == null || raw.isEmpty()) return message;

			String sender = senderSegment(raw);
			if (sender == null) return message;
			MinecraftClient mc = MinecraftClient.getInstance();
			String self = mc.getSession() != null ? mc.getSession().getUsername() : null;
			java.util.List<String> names = new java.util.ArrayList<>(nt.publicPlayerNames());
			if (self != null) names.add(self);
			String bestName = null; int bestIdx = Integer.MAX_VALUE; Long bestNw = null;
			for (String n : names) {
				if (n == null || n.isEmpty()) continue;
				int idx = indexOfWord(sender, n);
				if (idx < 0 || idx >= bestIdx) continue;
				Long nw = (self != null && n.equalsIgnoreCase(self)) ? nzp(nt.netWorth()) : nt.publicNetworth(n);
				if (nw == null || nw <= 0) continue;
				bestIdx = idx; bestName = n; bestNw = nw;
			}
			if (bestNw == null) return message;
			return net.minecraft.text.Text.literal("§a✦ " + Coins.fmt(bestNw) + " §r").append(message);
		} catch (Exception e) { return message; }
	}

	private static String senderSegment(String raw) {
		int colon = raw.indexOf(':');
		if (colon < 0 || colon > 40) return null;
		return raw.substring(0, colon);
	}

	private static int indexOfWord(String s, String word) {
		String ls = s.toLowerCase(java.util.Locale.ROOT), lw = word.toLowerCase(java.util.Locale.ROOT);
		int from = 0;
		while (true) {
			int i = ls.indexOf(lw, from);
			if (i < 0) return -1;
			boolean lok = i == 0 || !Character.isLetterOrDigit(ls.charAt(i - 1));
			int e = i + lw.length();
			boolean rok = e >= ls.length() || !Character.isLetterOrDigit(ls.charAt(e));
			if (lok && rok) return i;
			from = i + 1;
		}
	}

	private static final net.minecraft.text.Text[] SPACE_BARS = new net.minecraft.text.Text[65];
	static {
		for (int i = 1; i < SPACE_BARS.length; i++) SPACE_BARS[i] = net.minecraft.text.Text.literal(" ".repeat(i));
	}

	private static void fillPx(net.minecraft.client.font.TextRenderer tr, org.joml.Matrix4f mat,
			net.minecraft.client.render.VertexConsumerProvider cons,
			net.minecraft.client.font.TextRenderer.TextLayerType layer, int x, int y, int w, int h, int argb, int light) {
		int spaces = Math.max(1, Math.min(SPACE_BARS.length - 1, Math.round(w / 4f)));
		net.minecraft.text.Text bar = SPACE_BARS[spaces];

		for (int yy = 0; yy < h; yy += 8)
			tr.draw(bar, x, y + yy, 0, false, mat, cons, layer, argb, light);
	}

	private void tickPresence(MinecraftClient client) {
		if (client.player == null || !NetworthTracker.isHomeServer(client)) return;
		long now = System.currentTimeMillis();

		if (now >= nextAlive) {
			nextAlive = now + 30_000L;
			if (!alertsSynced) { alertsSynced = true; syncAlerts(); }
			String url = MarketConfig.apiBase() + "/api/alive";
			var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(6))
					.header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId())
					.header("X-Token", MarketConfig.token())
					.POST(java.net.http.HttpRequest.BodyPublishers.ofString(client.getSession().getUsername())).build();
			HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString());
		}
	}

	public static void syncAlerts() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.getSession() == null) return;
		String ign = mc.getSession().getUsername();

		StringBuilder body = new StringBuilder(ign).append('\t').append(MarketConfig.get().discordAlerts ? "1" : "0");
		for (MarketConfig.Alert a : MarketConfig.get().alerts)
			body.append('\n').append(a.ticker).append('\t').append(a.target).append('\t').append(a.below ? "1" : "0");
		String url = MarketConfig.apiBase() + "/api/alerts";

		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(6))
				.header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId())
				.header("X-User", MarketConfig.userName())
				.header("X-Token", MarketConfig.token())
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString());
	}

	private static void linkDiscord(String code) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.getSession() == null) return;
		String ign = mc.getSession().getUsername();
		String url = MarketConfig.apiBase() + "/api/link";
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8))
				.header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId())

				.header("X-User", MarketConfig.userName())
				.header("X-Token", MarketConfig.token())
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(code + "\t" + ign)).build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {

			int sc = err != null ? -1 : resp.statusCode();
			String msg;
			if (sc == 200)
				msg = "§a[Lifesteal Market] §7Discord linked! You'll now get DM price alerts. Also sync your alerts by opening /stock.";
			else if (sc == -1)
				msg = "§c[Lifesteal Market] §7Couldn't reach the market server — check your connection and try again in a minute.";
			else if (sc == 403)
				msg = "§c[Lifesteal Market] §7Your account isn't set up on the market yet, so the code can't be used. "
						+ "§7Rejoin the server once, then run §f/lsmlink§7 again. Still stuck? Open a ticket in our Discord.";
			else if (sc == 503 || sc == 502)
				msg = "§c[Lifesteal Market] §7Our Discord bot is offline right now — nothing wrong with your code. Try again shortly.";
			else
				msg = "§c[Lifesteal Market] §7That code is invalid or expired (they last 10 minutes). Run §f/link§7 in Discord for a fresh one.";
			mc.execute(() -> { if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal(msg), false); });
		});

		syncAlerts();
	}

	private static void creditReferrer(String referrer) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.getSession() == null || referrer.isEmpty()) return;

		if (referrer.equalsIgnoreCase(mc.getSession().getUsername())) {
			if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal(
					"§c[Market] §7You can't credit yourself — put in the name of whoever got you the mod."), false);
			return;
		}
		String url = MarketConfig.apiBase() + "/api/referral";
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8))
				.header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId())
				.header("X-User", MarketConfig.userName())
				.header("X-Token", MarketConfig.token())
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(referrer)).build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			String body = (err == null && resp != null) ? resp.body().trim() : "err";
			int code = (err == null && resp != null) ? resp.statusCode() : 0;
			String msg = switch (body) {
				case "ok"     -> "§a[Market] §7Referral recorded — thanks for crediting §f" + referrer + "§7!";
				case "oklink" -> "§a[Market] §7Credited §f" + referrer + "§7! It counts once you §f/link§7 your Discord.";
				case "self"   -> "§c[Market] §7You can't credit yourself — put in the name of whoever got you the mod.";
				case "already"-> "§c[Market] §7You've already credited a referrer — it's locked in.";
				case "badname"-> "§c[Market] §7That doesn't look like a Minecraft name — check the spelling.";

				case "notnew" -> "§c[Market] §7Referrals are for new players only.";
				default       -> code == 403 ? "§c[Market] §7Get market access first, then try again."
											  : "§c[Market] §7Couldn't record that — try again in a moment.";
			};
			mc.execute(() -> { if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal(msg), false); });
		});
	}

	private static void showReferralStatus() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.getSession() == null) return;
		int refs = Math.max(0, MarketConfig.get().lastReferrals);
		String ign = mc.getSession().getUsername();
		String tag = refTagNow;
		String invite = "/referral " + ign;

		String have = tag != null && !tag.isBlank() ? "  §7You wear " + tag.trim() : "";
		mc.player.sendMessage(net.minecraft.text.Text.literal(
				"§b[Market] §fInvites: §e" + refs + have), false);

		int next = refNextAt, need = next - refs;
		if (next > 0 && need > 0) {
			String nt = refNextTag != null && !refNextTag.isBlank() ? refNextTag.trim() : "the next tag";
			mc.player.sendMessage(net.minecraft.text.Text.literal(
					"§8  " + need + " more invite" + (need == 1 ? "" : "s") + " to unlock " + nt), false);
		}

		net.minecraft.text.Text share = net.minecraft.text.Text.literal("§b§n[" + invite + "]")
				.setStyle(net.minecraft.text.Style.EMPTY
						.withClickEvent(new net.minecraft.text.ClickEvent.CopyToClipboard(invite))
						.withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(net.minecraft.text.Text.literal(
								"§7Click to copy.\n§8Your friend runs this once they have the mod, then links Discord."))));
		mc.player.sendMessage(net.minecraft.text.Text.literal("§7  Send friends: ").append(share), false);
	}

	public static net.minecraft.sound.SoundEvent alertSound(String name) {
		return switch (name == null ? "coin" : name) {
			case "bell" -> net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BELL.value();
			case "chime" -> net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value();
			case "ding" -> net.minecraft.sound.SoundEvents.ENTITY_ARROW_HIT_PLAYER;
			case "levelup" -> net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP;
			case "pling" -> net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();

			case "coin" -> ModSounds.COIN;
			case "chord" -> ModSounds.CHORD;
			case "marimba" -> ModSounds.MARIMBA;
			case "pop" -> ModSounds.POP;
			case "off" -> null;
			default -> ModSounds.COIN;
		};
	}

	private void tickAlerts(MinecraftClient client) {
		if (client.player == null) return;
		long now = System.currentTimeMillis();

		if (!toastQueue.isEmpty() && now >= nextToastAt) {
			Object[] t = toastQueue.poll();
			client.getToastManager().add((net.minecraft.client.toast.Toast) t[0]);
			net.minecraft.sound.SoundEvent se = alertSound((String) t[1]);
			if (se != null) client.player.playSound(se, 1f, 1.5f);
			nextToastAt = now + 5900;
		}
		MarketConfig cfg = MarketConfig.get();
		if (cfg.alerts.isEmpty()) return;
		if (now < nextAlertCheck || alertFetching) return;

		if (priceStream != null && priceStream.sinceLastEvent() < 20_000L) { nextAlertCheck = now + 5_000L; return; }
		nextAlertCheck = now + 3_000L;

		alertFetching = true;
		String url = MarketConfig.apiBase() + "/api/tickers";
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(java.time.Duration.ofSeconds(8))
				.header("ngrok-skip-browser-warning", "true").header("X-Market-Key", com.lifestealmarket.MarketConfig.API_KEY).header("X-Server-Id", com.lifestealmarket.MarketConfig.serverId())
				.header("X-User", com.lifestealmarket.MarketConfig.userName())
				.header("X-Token", com.lifestealmarket.MarketConfig.token()).GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString())
				.whenComplete((resp, err) -> {
					alertFetching = false;
					if (err != null || resp.statusCode() != 200) return;
					try {
						var arr = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonArray();
						java.util.Map<String, long[]> price = new java.util.HashMap<>();
						for (var e : arr) { var o = e.getAsJsonObject();
							long floor = o.has("floor") && !o.get("floor").isJsonNull() ? o.get("floor").getAsLong() : -1;
							price.put(o.get("ticker").getAsString(), new long[]{ o.get("last").getAsLong(), floor }); }
						client.execute(() -> evaluateAlerts(client, cfg, price));
					} catch (Exception ignored) {}
				});
	}

	private void evaluateAlerts(MinecraftClient client, MarketConfig cfg, java.util.Map<String, long[]> price) {
		for (MarketConfig.Alert a : cfg.alerts) {
			long[] v = price.get(a.ticker);
			if (v == null) continue;
			long last = v[0], floor = v[1];

			long p = a.below ? (floor > 0 ? floor : last) : last;
			boolean hit = a.below ? p <= a.target : p >= a.target;
			if (hit && !a.triggered) {
				a.triggered = true;

				int acc = MarketConfig.themeColor(MarketConfig.get().theme);
				toastQueue.add(new Object[]{ new com.lifestealmarket.gui.AlertToast(
						net.minecraft.text.Text.literal("§lPrice Alert")
								.setStyle(net.minecraft.text.Style.EMPTY
										.withColor(net.minecraft.text.TextColor.fromRgb(acc & 0xFFFFFF)).withBold(true)),
						net.minecraft.text.Text.literal("§f" + a.ticker + "  §7"
								+ (a.below ? "▼" : "▲") + " §e" + Coins.fmt(p)),
						p, acc, a.ticker), a.sound });
			} else if (!hit && a.triggered) {
				a.triggered = false;
			}
		}

	}
}

