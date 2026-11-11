package com.lifestealmarket;

import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TeamTracker {
	public static final TeamTracker INSTANCE = new TeamTracker();
	private TeamTracker() {}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
	private static final long EVERY_MS = 300_000L;
	private static final long FIRST_RUN_MS = 45_000L;
	private static final long NO_TEAM_BACKOFF_MS = 1_800_000L;

	private static final long WINDOW_MS = 6_000L;

	private static final long NAME_GRACE_MS = 2_000L;

	private static final int NONE = 0, INFO = 1, MEMBERS = 2, SPAWNERS = 3;
	private static final String BULLETS = "•·▪◦-*";

	private static final java.util.regex.Pattern NO_TEAM = java.util.regex.Pattern.compile(
			"must be in a .*team|not in a .*team|do(n't| not) have a team|aren'?t in a team",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	private long nextRun = 0L;
	private long captureUntil = 0L;
	private boolean hideOutput = false;
	private int section = NONE;
	private String team = null;

	private String pendingName = null;

	private boolean sawBlock = false;

	private int msgsSeen = 0;
	private long beganAt = 0L;

	private net.minecraft.text.Text currentMsg = null;

	private net.minecraft.text.Text heldName = null;
	private final Map<String, List<String>> roster = new LinkedHashMap<>();
	private final Map<String, Integer> spawners = new LinkedHashMap<>();

	public static volatile String myTeam = null;

	public void tick(MinecraftClient client, long now) {
		if (captureUntil > 0 && now > captureUntil) finish(now);
		if (nextRun == 0L) { nextRun = now + FIRST_RUN_MS; return; }
		if (now < nextRun || captureUntil > 0) return;
		NetworthTracker nt = NetworthTracker.INSTANCE;
		if (!nt.autoCommandsOk(client, now)) return;
		nextRun = now + EVERY_MS;
		begin(now, true);
		nt.markSilentCommand(3_000L);
		SilentCmd.send(client, "team info");
	}

	public void notePlayerRan(String command) {
		String c = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
		if (c.startsWith("/")) c = c.substring(1);
		if (c.equals("team info") || c.startsWith("team info ")) {
			begin(System.currentTimeMillis(), false);
			nextRun = System.currentTimeMillis() + EVERY_MS;
		}
	}

	private void begin(long now, boolean hide) {
		captureUntil = now + WINDOW_MS;
		beganAt = now;
		hideOutput = hide;
		section = NONE;
		team = null;
		pendingName = null;
		sawBlock = false;
		msgsSeen = 0;
		heldName = null;
		roster.clear();
		spawners.clear();
	}

	public boolean onMessage(net.minecraft.text.Text message) {
		if (captureUntil == 0L || message == null) return true;
		String raw = message.getString();
		long now = System.currentTimeMillis();
		if (now > captureUntil) { finish(now); return true; }
		currentMsg = message;
		msgsSeen++;

		String[] parts = raw.replaceAll("(?i)§.", "").split("\\R");
		boolean recognised = false;
		for (String p : parts) recognised |= feed(p.trim(), now);

		if (!recognised && hideOutput && looksLikeTeamInfo(parts)) recognised = true;

		return !(recognised && hideOutput);
	}

	private static boolean looksLikeTeamInfo(String[] parts) {
		for (String p : parts) {
			String t = p.trim();
			if (t.isEmpty()) continue;
			String low = t.toLowerCase(Locale.ROOT);
			if (low.startsWith("info:") || low.startsWith("members:") || low.startsWith("spawners:")) return true;
			if (BULLETS.indexOf(t.charAt(0)) >= 0 && t.indexOf(':') > 1) return true;
		}
		return false;
	}

	private boolean feed(String line, long now) {
		if (line.isEmpty()) return false;

		if (NO_TEAM.matcher(line).find()) {
			finish(now);
			myTeam = null;
			nextRun = now + NO_TEAM_BACKOFF_MS;
			return true;
		}
		boolean bullet = BULLETS.indexOf(line.charAt(0)) >= 0;
		String body = bullet ? line.substring(1).trim() : line;
		if (body.isEmpty()) return false;

		if (!bullet) {
			String low = body.toLowerCase(Locale.ROOT);

			if (low.startsWith("info:") || low.startsWith("members:") || low.startsWith("spawners:")) {
				sawBlock = true;
				if (team == null && pendingName != null) team = pendingName;
				section = low.startsWith("info:") ? INFO : low.startsWith("members:") ? MEMBERS : SPAWNERS;
				return true;
			}

			if (hideOutput && myTeam != null && !myTeam.isBlank() && body.equalsIgnoreCase(myTeam)) {
				pendingName = body;
				return true;
			}

			if (team == null && section == NONE && !body.contains(":") && body.length() <= 32) {
				pendingName = body;

				if (hideOutput && now - beganAt <= NAME_GRACE_MS && heldName == null) {
					heldName = currentMsg;
					return true;
				}
				return false;
			}
			return false;
		}

		int colon = body.indexOf(':');
		if (colon <= 0) return false;
		String key = body.substring(0, colon).trim();
		String val = body.substring(colon + 1).trim();
		if (section == MEMBERS) {
			if (!val.equalsIgnoreCase("None")) {
				List<String> names = new ArrayList<>();
				for (String n : val.split(",")) {
					String who = n.trim();
					if (!who.isEmpty() && who.matches("[A-Za-z0-9_]{1,16}")) names.add(who);
				}
				if (!names.isEmpty()) roster.computeIfAbsent(key, k -> new ArrayList<>()).addAll(names);
			}
			return true;
		}
		if (section == SPAWNERS) {
			try {
				int n = Integer.parseInt(val.replaceAll("[^0-9]", ""));
				if (n > 0) spawners.put(key, n);
			} catch (Exception ignored) {}
			return true;
		}
		return section == INFO;
	}

	private void finish(long now) {
		captureUntil = 0L;

		if (!sawBlock && heldName != null) {
			net.minecraft.text.Text back = heldName;
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc != null) mc.execute(() -> { if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(back); });
		}
		heldName = null;
		if (team == null || roster.isEmpty()) {

			if (sawBlock) return;

			nextRun = Math.max(nextRun, now + NO_TEAM_BACKOFF_MS);
			myTeam = null;
			return;
		}
		myTeam = team;
		StringBuilder sb = new StringBuilder("TEAM\t").append(team).append('\n');
		for (var e : roster.entrySet())
			sb.append("R\t").append(e.getKey()).append('\t').append(String.join(",", e.getValue())).append('\n');
		for (var e : spawners.entrySet())
			sb.append("S\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
		upload(sb.toString());
	}

	private void upload(String body) {
		if (MarketConfig.token().isBlank()) return;
		try {
			var req = HttpRequest.newBuilder(URI.create(MarketConfig.apiBase() + "/api/team/report"))
					.timeout(Duration.ofSeconds(8))
					.header("X-Market-Key", MarketConfig.API_KEY)
					.header("X-User", MarketConfig.userName())
					.header("X-Token", MarketConfig.token())
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
			HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		} catch (Exception ignored) {}
	}
}

