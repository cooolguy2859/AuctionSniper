package com.auctionsniper;

import java.util.Locale;

/**
 * Shared decision engine for Deal Advisor and Auto Buy.
 * Uses observed listing prices as fair-value evidence and order data as a
 * liquidity check. It deliberately fails closed when evidence is weak/stale.
 */
public final class DealAdvisor {
    public record Assessment(boolean eligible, long fairValue, long estimatedProfit,
                             int confidence, String reason) {}

    private static final long MAX_REMOTE_AGE_MS = 24L * 60L * 60L * 1000L;

    private DealAdvisor() {}

    public static Assessment assess(String itemKey, String displayName, long askingPrice) {
        if (askingPrice <= 0) return reject("Invalid asking price");
        long now = System.currentTimeMillis();

        Long localMedian = PriceTracker.median(itemKey);
        int localSamples = PriceTracker.sampleCount(itemKey);
        MarketApi.RemotePrice remote = MarketApi.price(itemKey);
        long remoteAge = remote == null || remote.lastSeen() <= 0 ? Long.MAX_VALUE : Math.max(0, now - remote.lastSeen());
        boolean remoteFresh = remote != null && remoteAge <= MAX_REMOTE_AGE_MS && remote.samples() >= 3;
        boolean localReliable = localMedian != null && localSamples >= 8;

        if (!localReliable && !remoteFresh) return reject("Not enough recent price history");

        long fair = 0;
        int confidence = 0;
        if (localReliable && remoteFresh) {
            // Prefer the lower credible estimate to avoid buying against an inflated average.
            fair = Math.min(localMedian, remote.average());
            confidence = Math.min(100, 45 + Math.min(30, localSamples) + Math.min(25, remote.samples()));
        } else if (localReliable) {
            fair = localMedian;
            confidence = Math.min(75, 35 + localSamples * 4);
        } else {
            fair = remote.average();
            confidence = Math.min(70, 25 + remote.samples() * 3);
        }

        // A very wide or suspiciously low/high remote range lowers confidence.
        if (remoteFresh && remote.low() > 0 && remote.high() > remote.low()) {
            long spread = remote.high() - remote.low();
            if (spread > fair * 2L) confidence -= 15;
        }

        long bestBid = findMarketBid(displayName);
        if (bestBid > 0) {
            // A buy order is a possible resale/liquidity signal, not proof of a sale.
            if (bestBid > fair) fair = Math.min(fair, bestBid);
            if (bestBid < askingPrice) confidence -= 10;
            else confidence += 5;
        }

        if (confidence < 35) return new Assessment(false, fair, fair - askingPrice,
                Math.max(0, confidence), "Market evidence is too uncertain");
        long profit = fair - askingPrice;
        if (profit <= 0) return new Assessment(false, fair, profit, confidence, "Price is not below fair value");
        return new Assessment(true, fair, profit, Math.min(100, confidence), "Price history and market signals support this deal");
    }

    private static long findMarketBid(String displayName) {
        String target = normalize(displayName);
        long best = 0;
        for (MarketData.Entry entry : MarketData.orders()) {
            if (normalize(entry.item()).equals(target)) best = Math.max(best, entry.price());
        }
        return best;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Assessment reject(String reason) {
        return new Assessment(false, 0, 0, 0, reason);
    }
}
