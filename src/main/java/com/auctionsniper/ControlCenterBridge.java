package com.auctionsniper;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Optional Mod Control Center bridge. AuctionSniper remains standalone without the panel. */
public final class ControlCenterBridge {
    public void registerFeatures(Object registry) {
        register(registry, "AuctionSniper", "Auto-buy armed",
                "Arms or disarms automatic purchases during a scan.",
                AuctionScanner::isAutoBuyEnabled, AuctionScanner::setAutoBuyEnabled);
        register(registry, "AuctionSniper", "Player balance overlay",
                "Turns the nearby-player balance overlay and its refresh loop on or off.",
                BalanceOverlay::isEnabled, BalanceOverlay::setEnabled);
    }

    private static void register(Object registry, String owner, String name, String description,
                                 BooleanSupplier getter, Consumer<Boolean> setter) {
        try {
            Method method = Class.forName("dev.mcgee.controlcenter.ModControlCenterClient")
                    .getMethod("register", Object.class, String.class, String.class, String.class,
                            BooleanSupplier.class, Consumer.class);
            method.invoke(null, registry, owner, name, description, getter, setter);
        } catch (ClassNotFoundException ignored) {
            // Control Center is optional.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not register AuctionSniper control: " + name, e);
        }
    }
}
