import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("./worker.js", import.meta.url), "utf8");
const { default: worker } = await import(
  "data:text/javascript;base64," + Buffer.from(source).toString("base64")
);

const queries = [];
const DB = {
  async batch(statements) {
    queries.push({ action: "batch", count: statements.length });
    return statements.map(() => ({ success: true }));
  },
  prepare(sql) {
    return {
      bind(...params) {
        return {
          async run() {
            queries.push({ sql, params, action: "run" });
            return { success: true };
          },
          async all() {
            queries.push({ sql, params, action: "all" });
            if (sql.includes("FROM order_book")) {
              return { results: [{
                ticker: "minecraft:diamond",
                bid: 11000,
                demand: 4,
                orders: 2,
                listed: 7,
                material: "minecraft:diamond",
                depth_json: '[{"price":11000,"qty":4}]'
              }] };
            }
            if (sql.includes("FROM observations") && sql.includes("GROUP BY item_id")) {
              return { results: [{
                ticker: "minecraft:diamond",
                itemId: "minecraft:diamond",
                itemName: "Diamond",
                last: 12500,
                average: 12500,
                low: 12500,
                high: 12500,
                samples: 1,
                lastSeen: 1,
                sales: 1,
                price: 12500,
                t: 1
              }] };
            }
            return { results: [] };
          },
          async first() {
            queries.push({ sql, params, action: "first" });
            return null;
          }
        };
      }
    };
  }
};

const env = { API_KEY: "test-client-key", DB };
const health = await worker.fetch(new Request("https://worker.test/health"), env);
assert.equal(health.status, 200);
assert.equal((await health.json()).ok, true);

const denied = await worker.fetch(
  new Request("https://worker.test/v1/prices?serverId=lifesteal.net"),
  env
);
assert.equal(denied.status, 401);

const saved = await worker.fetch(new Request("https://worker.test/v1/observations", {
  method: "POST",
  headers: {
    Authorization: "Bearer test-client-key",
    "Content-Type": "application/json"
  },
  body: JSON.stringify({
    serverId: "lifesteal.net",
    itemId: "minecraft:diamond",
    itemName: "Diamond",
    price: 12500,
    player: "ExamplePlayer"
  })
}), env);
assert.equal(saved.status, 200);
assert.equal((await saved.json()).ok, true);

const prices = await worker.fetch(
  new Request("https://worker.test/v1/prices?serverId=lifesteal.net", {
    headers: { Authorization: "Bearer test-client-key" }
  }),
  env
);
assert.equal(prices.status, 200);
const priceBody = await prices.json();
assert.equal(priceBody.prices[0].itemId, "minecraft:diamond");

const legacy = await worker.fetch(
  new Request("https://worker.test/api/tickers?serverId=lifesteal.net", {
    headers: { "X-Market-Key": "test-client-key", "X-Server-Id": "lifesteal.net" }
  }),
  env
);
assert.equal(legacy.status, 200);
assert.equal(Array.isArray(await legacy.json()), true);

const savedOrders = await worker.fetch(new Request("https://worker.test/v1/orders", {
  method: "POST",
  headers: {
    Authorization: "Bearer test-client-key",
    "Content-Type": "application/json"
  },
  body: JSON.stringify({
    serverId: "lifesteal.net",
    orders: [{
      ticker: "minecraft:diamond",
      bid: 11000,
      demand: 4,
      orders: 2,
      listed: 7,
      material: "minecraft:diamond",
      depth: [{ price: 11000, qty: 4 }]
    }]
  })
}), env);
assert.equal(savedOrders.status, 200);
assert.equal((await savedOrders.json()).saved, 1);

const orderBook = await worker.fetch(
  new Request("https://worker.test/v1/orders?serverId=lifesteal.net", {
    headers: { Authorization: "Bearer test-client-key" }
  }),
  env
);
assert.equal(orderBook.status, 200);
const orderBody = await orderBook.json();
assert.equal(orderBody.orders[0].ticker, "minecraft:diamond");
assert.equal(orderBody.orders[0].bid, 11000);
assert.equal(orderBody.orders[0].depth[0].qty, 4);

const noUsernameProvision = await worker.fetch(
  new Request("https://worker.test/api/provision?user=ExamplePlayer", {
    headers: { "X-Market-Key": "test-client-key" }
  }),
  env
);
assert.equal(noUsernameProvision.status, 403);
assert.ok(queries.some(q => q.action === "run" && q.sql.includes("INSERT INTO observations")));

console.log("Worker smoke test OK: health, auth, observations/prices, order-book write/read, legacy auth, and safe provisioning.");
