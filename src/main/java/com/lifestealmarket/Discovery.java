package com.lifestealmarket;

/**
 * Backend discovery is intentionally disabled. All market traffic is pinned to
 * the Cloudflare Worker configured in MarketConfig so a discovery response
 * cannot redirect the client to an unrelated or legacy backend.
 */
public final class Discovery {
    private Discovery() {}

    public static void noteOk() {}
    public static void noteFail() {}
    public static void resolve(boolean startup) {}
    public static void flushNotice() {}
}
