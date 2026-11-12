package com.lifestealmarket;

import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public final class MojangAuth {
	private MojangAuth() {}

	private static volatile boolean running = false;

	public static volatile boolean unverifiable = false;

	private static volatile long nextAttempt = 0L;
	private static volatile int fails = 0;

	private static void backOff() {
		fails++;
		long wait = Math.min(10_000L * (1L << Math.min(fails - 1, 6)), 600_000L);
		nextAttempt = System.currentTimeMillis() + wait + (long) (Math.random() * 5_000L);
	}

	public static void ensureToken(MinecraftClient client) {
		if (running || unverifiable || System.currentTimeMillis() < nextAttempt) return;
		if (!MarketConfig.token().isBlank()) return;
		if (client == null || client.getSession() == null) return;
		final UUID uuid = client.getSession().getUuidOrNull();
		final String ign = client.getSession().getUsername();
		final String accessToken = client.getSession().getAccessToken();
		if (uuid == null || ign == null || ign.isBlank() || accessToken == null || accessToken.isBlank()) return;
		running = true;
		Thread t = new Thread(() -> {
			try { verify(client, uuid, ign, accessToken); }
			catch (Exception e) { System.out.println("[LifestealMarket] verification failed: " + e); }
			finally { running = false; }
		}, "lsm-mojang-auth");
		t.setDaemon(true);
		t.start();
	}

	private static void verify(MinecraftClient client, UUID uuid, String ign, String accessToken) throws Exception {
		String base = MarketConfig.apiBase();

		var chReq = HttpRequest.newBuilder(URI.create(base + "/api/auth/challenge?user="
						+ java.net.URLEncoder.encode(ign, StandardCharsets.UTF_8)))
				.timeout(Duration.ofSeconds(10)).header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId()).GET().build();
		var chResp = LifestealMarketClient.HTTP.send(chReq, HttpResponse.BodyHandlers.ofString());
		if (chResp.statusCode() != 200) { backOff(); return; }
		String nonce = com.google.gson.JsonParser.parseString(chResp.body()).getAsJsonObject()
				.get("nonce").getAsString();

		try {
			client.getApiServices().sessionService().joinServer(uuid, accessToken, nonce);
		} catch (com.mojang.authlib.exceptions.AuthenticationUnavailableException e) {
			backOff();
			return;
		} catch (com.mojang.authlib.exceptions.AuthenticationException e) {

			unverifiable = true;
			System.out.println("[LifestealMarket] account could not be verified with Mojang: " + e.getMessage());
			return;
		}

		var vReq = HttpRequest.newBuilder(URI.create(base + "/api/auth/verify"))
				.timeout(Duration.ofSeconds(15)).header("X-Market-Key", MarketConfig.API_KEY).header("X-Server-Id", MarketConfig.serverId())
				.POST(HttpRequest.BodyPublishers.ofString(nonce, StandardCharsets.UTF_8)).build();
		var vResp = LifestealMarketClient.HTTP.send(vReq, HttpResponse.BodyHandlers.ofString());
		if (vResp.statusCode() != 200) {
			System.out.println("[LifestealMarket] verification refused (" + vResp.statusCode() + ")");
			backOff();
			return;
		}
		String token = com.google.gson.JsonParser.parseString(vResp.body()).getAsJsonObject()
				.get("token").getAsString();
		if (token == null || token.isBlank()) { backOff(); return; }
		fails = 0;
		client.execute(() -> {
			MarketConfig.get().accessToken = token;
			MarketConfig.save();
			System.out.println("[LifestealMarket] account verified with Mojang - identity restored");
		});
	}
}

