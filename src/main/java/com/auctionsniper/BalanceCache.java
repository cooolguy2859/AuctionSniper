package com.auctionsniper;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BalanceCache {
    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    public void put(String player, long amount) { balances.put(player.toLowerCase(), amount); }
    public int size() { return balances.size(); }
    public void clear() { balances.clear(); }
    public Map<String, Long> snapshot() { return Collections.unmodifiableMap(balances); }
}
