package com.auctionsniper;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Custom AuctionSniper market dashboard, styled after the supplied dark navy/cyan references. */
public final class MarketScreen extends Screen {
    private static final String[] TABS = {"Today", "Stock", "Market", "Buy", "Sell", "Calc", "History", "Worth", "Board", "Shop", "Style"};
    private static int BG = 0xFF101F29;
    private static int PANEL = 0xFF1B3440;
    private static int PANEL_DARK = 0xFF122630;
    private static int CYAN = 0xFF36D7F2;
    private static int MUTED = 0xFF83AAB8;
    private static int GREEN = 0xFF35E58A;
    private static void applyTheme() {
        switch (AuctionSniperClient.SETTINGS.theme) {
            case 1 -> { BG = 0xFF171923; PANEL = 0xFF292D3B; PANEL_DARK = 0xFF202432; CYAN = 0xFFB49AFF; MUTED = 0xFFA7A9C0; GREEN = 0xFF76D6A2; }
            case 2 -> { BG = 0xFF17251D; PANEL = 0xFF263B2D; PANEL_DARK = 0xFF1D3024; CYAN = 0xFFA7E36A; MUTED = 0xFFA4BBA8; GREEN = 0xFF75E0A0; }
            default -> { BG = 0xFF101F29; PANEL = 0xFF1B3440; PANEL_DARK = 0xFF122630; CYAN = 0xFF36D7F2; MUTED = 0xFF83AAB8; GREEN = 0xFF35E58A; }
        }
    }
    private int selectedTab = 1;
    private int selectedItem;
    private int scroll;
    private final List<Map.Entry<String, List<Long>>> prices = new ArrayList<>();

    public MarketScreen() {
        super(Text.literal("AuctionSniper"));
        refresh();
    }

    private void refresh() {
        prices.clear();
        prices.addAll(PriceTracker.snapshot().entrySet());
        prices.sort(Comparator.comparing(e -> displayName(e.getKey()), String.CASE_INSENSITIVE_ORDER));
        if (selectedItem >= prices.size()) selectedItem = Math.max(0, prices.size() - 1);
    }

    private static String displayName(String key) {
        int split = key.indexOf('|');
        return split >= 0 ? key.substring(split + 1) : key;
    }

    private static long average(List<Long> values) {
        if (values.isEmpty()) return 0;
        double sum = 0;
        for (long value : values) sum += value;
        return (long) (sum / values.size());
    }

    private static long low(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    private static long high(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    private static String money(long value) {
        if (value <= 0) return "—";
        if (value >= 1_000_000_000L) return String.format(java.util.Locale.US, "%.2fB", value / 1_000_000_000.0);
        if (value >= 1_000_000L) return String.format(java.util.Locale.US, "%.2fM", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(java.util.Locale.US, "%.1fK", value / 1_000.0);
        return String.format(java.util.Locale.US, "%,d", value);
    }

    private void text(DrawContext ctx, String value, int x, int y, int color) {
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(value), x, y, color);
    }

    private void panel(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        ctx.fill(x1, y1, x2, y2, color);
        ctx.fill(x1, y1, x2, y1 + 1, 0x5539D7F2);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        applyTheme();
        this.renderBackground(ctx, mouseX, mouseY, delta);
        refresh();
        int margin = Math.max(12, (width - 1000) / 2);
        int left = margin;
        int right = width - margin;
        int top = Math.max(10, (height - 590) / 2);
        int bottom = height - top;

        ctx.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFF07131B);
        ctx.fill(left, top, right, bottom, BG);
        ctx.fill(left, top, right, top + 48, 0xFF263747);
        text(ctx, "AuctionSniper", left + 18, top + 17, CYAN);
        text(ctx, "LIVE LIFESTEAL MARKET", left + 135, top + 19, MUTED);
        text(ctx, "ESC  CLOSE", right - 90, top + 19, MUTED);

        int tabY = top + 55;
        int tabW = (right - left - 24) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int x = left + 12 + i * tabW;
            boolean active = selectedTab == i;
            ctx.fill(x, tabY, x + tabW - 3, tabY + 31, active ? 0xFF164B5A : 0xFF263D49);
            if (active) ctx.fill(x, tabY + 28, x + tabW - 3, tabY + 31, CYAN);
            text(ctx, TABS[i], x + 5, tabY + 10, active ? CYAN : 0xFFE2F0F4);
        }

        int contentTop = tabY + 43;
        int contentBottom = bottom - 16;
        int gap = 12;
        int mainW = (right - left) * 68 / 100;
        int mainX = left + 12;
        int sideX = mainX + mainW + gap;
        int sideW = right - sideX - 12;

        panel(ctx, mainX, contentTop, mainX + mainW, contentBottom, PANEL_DARK);
        panel(ctx, sideX, contentTop, right - 12, contentBottom, PANEL);

        String tab = TABS[selectedTab];
        text(ctx, tab + (selectedTab == 1 ? "  /  AUCTION PRICE TRACKER" : "  /  AUCTIONSNIPER"), mainX + 16, contentTop + 14, CYAN);
        text(ctx, "Saved price observations: " + PriceTracker.pricedListings(), mainX + 16, contentTop + 34, MUTED);

        if (selectedTab == 1 || selectedTab == 6 || selectedTab == 0) {
            drawStock(ctx, mainX, contentTop, mainW, contentBottom);
        } else if (selectedTab == 7 || selectedTab == 8) {
            drawWorth(ctx, mainX, contentTop, mainW);
        } else {
            drawInfo(ctx, mainX, contentTop, mainW, contentBottom, tab);
        }
        drawSide(ctx, sideX, contentTop, sideW, contentBottom, mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawStock(DrawContext ctx, int x, int y, int w, int bottom) {
        int chartTop = y + 65;
        int chartBottom = Math.min(bottom - 105, chartTop + 230);
        int chartRight = x + w - 16;
        ctx.fill(x + 16, chartTop, chartRight, chartBottom, 0xFF0D2029);
        for (int i = 1; i < 5; i++) {
            int gy = chartTop + (chartBottom - chartTop) * i / 5;
            ctx.fill(x + 16, gy, chartRight, gy + 1, 0x333E7180);
        }
        if (selectedItem < prices.size()) {
            List<Long> values = prices.get(selectedItem).getValue();
            long min = low(values), max = high(values);
            if (max <= min) max = min + 1;
            int n = values.size();
            for (int i = 1; i < n; i++) {
                int x1 = x + 24 + (chartRight - x - 36) * (i - 1) / Math.max(1, n - 1);
                int x2 = x + 24 + (chartRight - x - 36) * i / Math.max(1, n - 1);
                int y1 = chartBottom - 12 - (int) ((values.get(i - 1) - min) * (chartBottom - chartTop - 24) / (double) (max - min));
                int y2 = chartBottom - 12 - (int) ((values.get(i) - min) * (chartBottom - chartTop - 24) / (double) (max - min));
                drawLine(ctx, x1, y1, x2, y2, CYAN);
            }
            text(ctx, "LOW " + money(min), x + 22, chartTop + 9, MUTED);
            text(ctx, "HIGH " + money(high(values)), chartRight - 95, chartTop + 9, GREEN);
        } else {
            text(ctx, "No price history yet. Run /ahsniper scan to collect AH listings.", x + 28, chartTop + 90, MUTED);
        }

        int statsY = chartBottom + 14;
        if (selectedItem < prices.size()) {
            var entry = prices.get(selectedItem);
            List<Long> values = entry.getValue();
            text(ctx, displayName(entry.getKey()), x + 18, statsY, 0xFFFFFFFF);
            text(ctx, "AVG  " + money(average(values)), x + 18, statsY + 22, CYAN);
            text(ctx, "LOW  " + money(low(values)), x + 180, statsY + 22, GREEN);
            text(ctx, "HIGH " + money(high(values)), x + 330, statsY + 22, 0xFFFFC45E);
            text(ctx, "SAMPLES  " + values.size(), x + 18, statsY + 43, MUTED);
        }
        text(ctx, "AH price history is collected from listing tooltips.", x + 18, bottom - 27, MUTED);
    }

    private void drawLine(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++) {
            int px = x1 + (x2 - x1) * i / Math.max(1, steps);
            int py = y1 + (y2 - y1) * i / Math.max(1, steps);
            ctx.fill(px, py, px + 2, py + 2, color);
        }
    }

    private int bottomSafe(int y) { return y + 280; }

    private void drawWorth(DrawContext ctx, int x, int y, int w) {
        if (selectedTab == 8) {
            text(ctx, "PLAYER BALANCE LEADERBOARD", x + 18, y + 76, CYAN);
            var leaders = new ArrayList<>(AuctionSniperClient.BALANCES.snapshot().entrySet());
            leaders.sort(Map.Entry.<String, Long>comparingByValue().reversed());
            if (leaders.isEmpty()) text(ctx, "No player balances captured yet. Use /ahsniper balance refresh.", x + 18, y + 108, MUTED);
            int line = 0;
            for (var entry : leaders) {
                if (line >= 14) break;
                text(ctx, (line + 1) + ". " + entry.getKey() + " — " + money(entry.getValue()), x + 18, y + 108 + line * 22, line == 0 ? GREEN : 0xFFFFFFFF);
                line++;
            }
            text(ctx, "Only balances actually seen in chat are shown.", x + 18, bottomSafe(y) , MUTED);
            return;
        }
        text(ctx, "KNOWN NET WORTH ESTIMATE", x + 18, y + 76, CYAN);
        text(ctx, "Your balance: " + (AuctionSniperClient.getOwnBalance() < 0 ? "Not detected" : money(AuctionSniperClient.getOwnBalance()) + " coins"), x + 18, y + 108, 0xFFFFFFFF);
        text(ctx, "Estimated inventory: " + money(MarketData.inventoryValue()), x + 18, y + 132, GREEN);
        text(ctx, "Known total: " + (AuctionSniperClient.getOwnBalance() < 0 ? "Partial — balance unknown" : money(MarketData.knownNetWorth()) + " coins"), x + 18, y + 160, CYAN);
        text(ctx, "Saved item price records: " + PriceTracker.pricedListings(), x + 18, y + 190, MUTED);
        text(ctx, "Captured order entries: " + MarketData.orders().size(), x + 18, y + 212, MUTED);
        text(ctx, "Captured market entries: " + MarketData.market().size(), x + 18, y + 234, MUTED);
        text(ctx, "AH listings, Ender Chest and shulkers are not included.", x + 18, y + 260, MUTED);
    }

    private void drawInfo(DrawContext ctx, int x, int y, int w, int bottom, String tab) {
        text(ctx, tab.toUpperCase(java.util.Locale.ROOT), x + 18, y + 78, CYAN);
        String detail = switch (tab) {
            case "Market" -> "Captured /market prices: " + MarketData.market().size();
            case "Buy" -> "Captured /orders entries: " + MarketData.orders().size();
            case "Sell" -> "Use saved AH price history to value items before listing.";
            case "Calc" -> "Estimated inventory value: " + money(MarketData.inventoryValue());
            case "Shop" -> "Server shop entries captured: " + MarketData.market().size();
            case "Style" -> "Theme " + (AuctionSniperClient.SETTINGS.theme + 1) + " of 3. Click the theme button below to switch.";
            case "Today" -> "Price observations: " + PriceTracker.pricedListings() + " | alerts: " + MarketData.alertCount();
            default -> "Saved item prices and market statistics.";
        };
        text(ctx, detail, x + 18, y + 108, 0xFFFFFFFF);
        text(ctx, "Open /orders or /market to collect menu prices.", x + 18, y + 136, MUTED);
        if (tab.equals("Style")) {
            ctx.fill(x + 18, y + 166, x + 190, y + 198, PANEL);
            text(ctx, "CHANGE THEME", x + 32, y + 178, CYAN);
        }
        List<MarketData.Entry> captured = tab.equals("Market") || tab.equals("Shop")
                ? MarketData.market() : MarketData.orders();
        if (tab.equals("Buy") || tab.equals("Sell") || tab.equals("Market") || tab.equals("Shop")) {
            int lineY = y + 164;
            int shown = 0;
            for (MarketData.Entry entry : captured) {
                if (shown++ >= 8) break;
                text(ctx, entry.item() + "  —  " + money(entry.price()), x + 18, lineY, GREEN);
                lineY += 18;
            }
            if (captured.isEmpty()) text(ctx, "No menu prices captured yet.", x + 18, lineY, MUTED);
        }
    }

    private void drawSide(DrawContext ctx, int x, int y, int w, int bottom, int mouseX, int mouseY) {
        text(ctx, "TRACKED ITEMS", x + 14, y + 14, CYAN);
        text(ctx, "Select an item to view its chart", x + 14, y + 31, MUTED);
        int rowY = y + 54;
        int rowH = 27;
        int visible = Math.max(1, (bottom - rowY - 50) / rowH);
        for (int i = scroll; i < Math.min(prices.size(), scroll + visible); i++) {
            var entry = prices.get(i);
            int yy = rowY + (i - scroll) * rowH;
            boolean active = i == selectedItem;
            if (active) ctx.fill(x + 8, yy - 3, x + w - 8, yy + 21, 0xFF285262);
            String name = displayName(entry.getKey());
            if (name.length() > 20) name = name.substring(0, 17) + "...";
            text(ctx, name, x + 14, yy + 2, active ? 0xFFFFFFFF : 0xFFD3E4EA);
            text(ctx, money(average(entry.getValue())), x + w - 72, yy + 2, GREEN);
        }
        if (prices.isEmpty()) text(ctx, "No saved prices yet", x + 14, rowY + 8, MUTED);
        int buttonY = bottom - 42;
        ctx.fill(x + 10, buttonY, x + w - 10, buttonY + 27, 0xFF145366);
        text(ctx, "OPEN AUCTION HOUSE", x + 20, buttonY + 9, CYAN);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (button != 0) return super.mouseClicked(click, doubled);
        int margin = Math.max(12, (width - 1000) / 2);
        int left = margin, right = width - margin;
        int top = Math.max(10, (height - 590) / 2);
        int tabY = top + 55;
        int tabW = (right - left - 24) / TABS.length;
        if (mouseY >= tabY && mouseY <= tabY + 31) {
            int index = (int) ((mouseX - left - 12) / tabW);
            if (index >= 0 && index < TABS.length) { selectedTab = index; return true; }
        }
        int contentTop = tabY + 43, bottom = height - top;
        int mainW = (right - left) * 68 / 100;
        int sideX = left + 12 + mainW + 12;
        int sideW = right - sideX - 12;
        if (selectedTab == 10 && mouseX >= left + 30 && mouseX <= left + 210 && mouseY >= contentTop + 166 && mouseY <= contentTop + 198) {
            AuctionSniperClient.SETTINGS.theme = (AuctionSniperClient.SETTINGS.theme + 1) % 3;
            AuctionSniperClient.SETTINGS.save();
            applyTheme();
            return true;
        }
        int rowY = contentTop + 54;
        if (mouseX >= sideX && mouseX <= sideX + sideW && mouseY >= rowY - 3 && mouseY < bottom - 50) {
            int index = scroll + (int) ((mouseY - rowY) / 27);
            if (index >= 0 && index < prices.size()) selectedItem = index;
            return true;
        }
        if (mouseX >= sideX + 10 && mouseX <= sideX + sideW - 10 && mouseY >= bottom - 42 && mouseY <= bottom - 15) {
            if (client.getNetworkHandler() != null) client.getNetworkHandler().sendChatCommand("ah");
            close();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldPause() { return false; }
}
