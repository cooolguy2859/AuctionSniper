package com.auctionsniper;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class MarketScreen extends Screen {
    private final List<Map.Entry<String, Integer>> entries = new ArrayList<>();

    public MarketScreen() {
        super(Text.literal("AuctionSniper Market"));
        refresh();
    }

    private void refresh() {
        entries.clear();
        entries.addAll(MarketDatabase.snapshot().entrySet());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        int left = Math.max(12, (this.width - 420) / 2);
        int top = Math.max(12, (this.height - 300) / 2);
        int right = Math.min(this.width - 12, left + 420);
        int bottom = Math.min(this.height - 12, top + 300);

        context.fill(left, top, right, bottom, 0xEE10131B);
        context.fill(left, top, right, top + 28, 0xFF252D3B);
        context.drawTextWithShadow(this.textRenderer, "AuctionSniper | Market Database",
                left + 10, top + 9, 0xFFFFFF);

        context.drawTextWithShadow(this.textRenderer,
                "Unique items: " + MarketDatabase.uniqueItems()
                        + "    Observations: " + MarketDatabase.totalObservations(),
                left + 10, top + 38, 0xA8D8FF);
        context.drawTextWithShadow(this.textRenderer,
                "ITEM NAME", left + 10, top + 58, 0xFFFF55);
        context.drawTextWithShadow(this.textRenderer,
                "SEEN", right - 48, top + 58, 0xFFFF55);
        context.drawTextWithShadow(this.textRenderer,
                "LOW / AVG / HIGH PRICE", left + 10, top + 72, 0xAAAAAA);

        int y = top + 90;
        int shown = 0;
        for (Map.Entry<String, Integer> entry : entries) {
            if (y > bottom - 34) break;
            if ((shown++ & 1) == 0) context.fill(left + 6, y - 2, right - 6, y + 12, 0x553A4658);
            String name = entry.getKey();
            if (name.length() > 43) name = name.substring(0, 40) + "...";
            context.drawTextWithShadow(this.textRenderer, name, left + 10, y, 0xFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Integer.toString(entry.getValue()),
                    right - 48, y, 0x55FF55);
            y += 15;
        }

        if (entries.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    "No items recorded yet. Run /ahsniper scan.", left + 10, y, 0xCCCCCC);
        }
        context.drawTextWithShadow(this.textRenderer,
                "Prices: waiting for reliable Auction House price detection.",
                left + 10, bottom - 25, 0xFFCC66);
        context.drawTextWithShadow(this.textRenderer,
                "Press ESC to close | /ahsniper gui to reopen",
                left + 10, bottom - 13, 0xAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
