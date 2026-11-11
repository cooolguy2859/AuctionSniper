const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };

function reply(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}

function authorized(request, env) {
  const expected = env.API_KEY;
  const supplied = request.headers.get("authorization") || "";
  return Boolean(expected && supplied === `Bearer ${expected}`);
}

function validText(value, max = 96) {
  return typeof value === "string" && value.length > 0 && value.length <= max && !/[\u0000-\u001f]/.test(value);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return reply({ ok: true, service: "auction-sniper-market-api", version: 1 });
    }

    if (!authorized(request, env)) return reply({ error: "Unauthorized" }, 401);
    if (!env.DB) return reply({ error: "Database binding DB is missing" }, 503);

    if (request.method === "POST" && url.pathname === "/v1/observations") {
      let body;
      try { body = await request.json(); } catch { return reply({ error: "Invalid JSON" }, 400); }
      const { serverId, itemId, itemName, price, player } = body || {};
      if (!validText(serverId, 80) || !validText(itemId, 128) ||
          !validText(itemName, 96) || !Number.isSafeInteger(price) ||
          price < 0 || price > 9_000_000_000_000_000 ||
          (player !== undefined && !validText(player, 32))) {
        return reply({ error: "Invalid observation fields" }, 400);
      }
      await env.DB.prepare(
        "INSERT INTO observations (server_id, item_id, item_name, price, player, observed_at) VALUES (?, ?, ?, ?, ?, ?)"
      ).bind(serverId, itemId, itemName, price, player || null, Date.now()).run();
      return reply({ ok: true });
    }

    if (request.method === "GET" && url.pathname === "/v1/prices") {
      const serverId = url.searchParams.get("serverId");
      if (!validText(serverId, 80)) return reply({ error: "serverId is required" }, 400);
      const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || 100), 1), 250);
      const result = await env.DB.prepare(
        `SELECT item_id AS itemId, MAX(item_name) AS itemName,
          ROUND(AVG(price)) AS average, MIN(price) AS low, MAX(price) AS high,
          COUNT(*) AS samples, MAX(observed_at) AS lastSeen
         FROM observations WHERE server_id = ?
         GROUP BY item_id ORDER BY lastSeen DESC LIMIT ?`
      ).bind(serverId, limit).all();
      return reply({ serverId, prices: result.results || [] });
    }

    if (request.method === "GET" && url.pathname === "/v1/history") {
      const serverId = url.searchParams.get("serverId");
      const itemId = url.searchParams.get("itemId");
      if (!validText(serverId, 80) || !validText(itemId, 128)) {
        return reply({ error: "serverId and itemId are required" }, 400);
      }
      const result = await env.DB.prepare(
        "SELECT price, player, observed_at AS observedAt FROM observations WHERE server_id = ? AND item_id = ? ORDER BY observed_at DESC LIMIT 100"
      ).bind(serverId, itemId).all();
      return reply({ serverId, itemId, history: result.results || [] });
    }

    return reply({ error: "Not found" }, 404);
  }
};
