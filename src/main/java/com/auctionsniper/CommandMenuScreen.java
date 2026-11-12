package com.auctionsniper;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.client.MinecraftClient;

/** Clickable command hub for the AuctionSniper client mod. */
public final class CommandMenuScreen extends Screen {
    public CommandMenuScreen() { super(Text.literal("AuctionSniper Control Center")); }

    @Override
    protected void init() {
        int buttonWidth = 220;
        int left = width / 2 - buttonWidth - 8;
        int right = width / 2 + 8;
        int y = Math.max(42, height / 2 - 112);
        int gap = 28;

        addDrawableChild(ButtonWidget.builder(Text.literal("Start Auction Scan"), b -> startScan(false))
                .dimensions(left, y, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("AUTO BUY (3 max / scan)"), b -> startScan(true))
                .dimensions(right, y, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Open Auction House"), b -> serverCommand("ah"))
                .dimensions(left, y + gap, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Open Custom Orders"), b -> serverCommand("orders"))
                .dimensions(right, y + gap, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Open Market Dashboard"), b ->
                client.setScreen(new MarketScreen()))
                .dimensions(left, y + gap * 2, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh My Balance"), b -> {
                if (client.getNetworkHandler() != null) client.getNetworkHandler().sendChatCommand("bal");
                AuctionSniperClient.msg("Requested your balance.");
            }).dimensions(right, y + gap * 2, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Toggle Player Balance Labels"), b -> {
                boolean enabled = !BalanceOverlay.isEnabled();
                BalanceOverlay.setEnabled(enabled);
                AuctionSniperClient.msg("Player balance labels " + (enabled ? "enabled" : "disabled") + ".");
                clearAndInit();
            }).dimensions(left, y + gap * 3, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel Running Scan"), b -> AuctionScanner.cancel())
                .dimensions(right, y + gap * 3, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Min Profit: " + AuctionSniperClient.SETTINGS.minimumProfit), b -> {
                AuctionSniperClient.SETTINGS.minimumProfit = AuctionSniperClient.SETTINGS.minimumProfit == 200_000 ? 100_000 : 200_000;
                AuctionSniperClient.SETTINGS.save();
                clearAndInit();
            }).dimensions(left, y + gap * 4, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Budget: " + AuctionSniperClient.SETTINGS.maxBudget), b -> {
                long[] choices = {200_000, 500_000, 1_000_000, 5_000_000};
                long next = choices[0];
                for (int i = 0; i < choices.length; i++) if (choices[i] == AuctionSniperClient.SETTINGS.maxBudget) next = choices[(i + 1) % choices.length];
                AuctionSniperClient.SETTINGS.maxBudget = next;
                AuctionSniperClient.SETTINGS.save();
                clearAndInit();
            }).dimensions(right, y + gap * 4, buttonWidth, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(width / 2 - 70, y + gap * 5 + 4, 140, 22).build());
    }

    private void startScan(boolean autoBuy) {
        if (client.player == null || client.getNetworkHandler() == null) {
            AuctionSniperClient.msg("Join Lifesteal Network first.");
            return;
        }
        if (AuctionScanner.isScanning()) {
            AuctionSniperClient.msg("A scan is already running. Cancel it first.");
            return;
        }
        AuctionScanner.setAutoBuyEnabled(autoBuy);
        AuctionScanner.startScan();
        close();
    }

    private void serverCommand(String command) {
        if (client.getNetworkHandler() == null) {
            AuctionSniperClient.msg("Join Lifesteal Network first.");
            return;
        }
        client.getNetworkHandler().sendChatCommand(command);
        close();
    }

    @Override
    public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0x55E5FF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Choose an action — no command typing needed"), width / 2, 31, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override public boolean shouldPause() { return false; }
}
