package com.lifestealmarket.gui;

import com.lifestealmarket.Coins;
import com.lifestealmarket.EnderShare;
import com.lifestealmarket.MarketConfig;
import com.lifestealmarket.NetworthTracker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public class EnderChestScreen extends Screen {
	private static final int COLS = 9, CELL = 18;

	private final Screen parent;
	private final String owner;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF;
	private int px, py, pw, ph, gridX, gridY, rows = 3;
	private double uiScale = 1.0;
	private final long openedAt = System.currentTimeMillis();

	private EnderShare.Chest chest;
	private String status = "Loading…";
	private final List<Object[]> cells = new ArrayList<>();
	private List<Text> hoverTip;

	public EnderChestScreen(Screen parent, String owner) {
		super(Text.literal(owner + "'s Ender Chest"));
		this.parent = parent;
		this.owner = owner;
	}

	@Override
	protected void init() {
		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a; C_ACC_DIM = (a & 0x00FFFFFF) | 0x55000000;
		rows = 3;
		if (chest != null) for (EnderShare.Slot s : chest.items()) rows = Math.max(rows, s.slot() / COLS + 1);

		pw = COLS * CELL + 16; ph = 16 + rows * CELL + 18;

		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(Math.min(this.width * frac / pw, this.height * frac / ph),
				2.1 * MarketConfig.get().guiSize);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		gridX = px + 8; gridY = py + 14;
		if (chest == null && status.startsWith("Loading")) load();
	}

	private void load() {
		EnderShare.fetch(owner, c -> {
			if (c == null) { status = "market offline"; return; }
			chest = c;
			status = c.shared() ? "" : "not shared";
			this.clearAndInit();
		});
	}

	private static ItemStack decodeStack(EnderShare.Slot s) {
		String raw = s.nbt();
		if (raw == null || raw.isEmpty()) return ItemStack.EMPTY;
		try {
			net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
			if (mc == null || mc.world == null) return ItemStack.EMPTY;
			net.minecraft.nbt.NbtCompound tag = net.minecraft.nbt.StringNbtReader.readCompound(raw);
			var ops = mc.world.getRegistryManager().getOps(net.minecraft.nbt.NbtOps.INSTANCE);
			return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
		} catch (Throwable t) { return ItemStack.EMPTY; }
	}

	private static ItemStack stackOf(EnderShare.Slot s) {
		ItemStack exact = decodeStack(s);
		if (!exact.isEmpty()) { exact.setCount(Math.max(1, s.count())); return exact; }
		ItemStack st = ItemStack.EMPTY;
		try {
			Identifier id = Identifier.tryParse(s.id());
			if (id != null && Registries.ITEM.containsId(id)) st = new ItemStack(Registries.ITEM.get(id));
		} catch (Exception ignored) {}
		if (st.isEmpty()) st = new ItemStack(Items.PAPER);
		st.setCount(Math.max(1, s.count()));
		return st;
	}

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override public void close() { Ui.escBack(this.client, parent); }

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());
		return super.mouseClicked(new net.minecraft.client.gui.Click(gx, gy, rc.buttonInfo()), doubled);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ThemeBg.draw(ctx, this.width, this.height);
		int mx = (int) Math.round(toGuiX(mouseX)), my = (int) Math.round(toGuiY(mouseY));
		var m = ctx.getMatrices();
		float cxp = this.width / 2f, cyp = this.height / 2f;
		double eff = uiScale * Ui.openPop(openedAt);
		m.pushMatrix(); m.translate(cxp, cyp); m.scale((float) eff, (float) eff); m.translate(-cxp, -cyp);
		try { renderInner(ctx, mx, my, delta); } finally { m.popMatrix(); }

		if (hoverTip != null) ctx.drawTooltip(this.textRenderer, hoverTip, mouseX, mouseY);
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {

		StyledButton.roundFill(ctx, px, py, pw, ph, 0xFF0A0F18);
		StyledButton.roundBorder(ctx, px, py, pw, ph, C_ACC_DIM);

		if (chest == null || !chest.shared()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§8" + Ui.fit(this.textRenderer, status, pw - 12)),
					px + pw / 2, py + ph / 2 - 4, 0xFFFFFFFF);
			super.render(ctx, mx, my, delta);
			return;
		}

		ctx.drawTextWithShadow(this.textRenderer,
				Text.literal("§f" + Ui.fit(this.textRenderer, owner, pw - 70)), px + 8, py + 4, 0xFFFFFFFF);
		long live = 0;
		for (EnderShare.Slot s : chest.items()) live += NetworthTracker.INSTANCE.stackValue(s.ticker(), s.count());
		if (live <= 0) live = chest.value();
		String worth = "§e" + Coins.fmt(live);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal(worth),
				px + pw - 8 - this.textRenderer.getWidth(Ui.strip(worth)), py + 4, 0xFFFFFFFF);

		cells.clear();
		for (int r = 0; r < rows; r++) for (int c = 0; c < COLS; c++) {
			int x = gridX + c * CELL, y = gridY + r * CELL;
			ctx.fill(x, y, x + CELL - 1, y + CELL - 1, 0x33000000);
			ctx.fill(x, y, x + CELL - 1, y + 1, 0x18FFFFFF);
		}
		for (EnderShare.Slot s : chest.items()) {
			int r = s.slot() / COLS, c = s.slot() % COLS;
			if (r >= rows) continue;
			int x = gridX + c * CELL + 1, y = gridY + r * CELL + 1;
			ItemStack st = stackOf(s);
			ctx.drawItem(st, x, y);
			ctx.drawStackOverlay(this.textRenderer, st, x, y);
			cells.add(new Object[]{ x, y, st, s });
		}

		String age = chest.updated() > 0 ? ago(System.currentTimeMillis() - chest.updated()) : "—";
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8" + age), px + 8, py + ph - 11, 0xFFFFFFFF);

		if (chest.items().isEmpty())
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§8not opened yet"),
					px + pw / 2, gridY + rows * CELL / 2 - 4, 0xFFFFFFFF);

		super.render(ctx, mx, my, delta);

		hoverTip = null;
		for (Object[] cell : cells) {
			int x = (Integer) cell[0], y = (Integer) cell[1];
			if (mx < x || mx >= x + 16 || my < y || my >= y + 16) continue;
			EnderShare.Slot s = (EnderShare.Slot) cell[3];
			ItemStack hovered = (ItemStack) cell[2];
			List<Text> tip = new ArrayList<>();

			try {
				net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
				if (mc != null && mc.world != null && !hovered.isEmpty())
					tip.addAll(hovered.getTooltip(net.minecraft.item.Item.TooltipContext.create(mc.world),
							mc.player, net.minecraft.item.tooltip.TooltipType.BASIC));
			} catch (Throwable tooltipFail) {  }
			if (tip.isEmpty()) {
				String nm = s.name() == null || s.name().isBlank() ? hovered.getName().getString() : s.name();
				tip.add(Text.literal("§f" + nm));
			}
			if (s.count() > 1) tip.add(Text.literal("§7x" + s.count()));
			long v = NetworthTracker.INSTANCE.stackValue(s.ticker(), s.count());
			tip.add(Text.literal(v > 0 ? "§8worth §e" + Coins.fmt(v) : "§8no price yet"));
			hoverTip = tip;
			break;
		}
	}

	private static String ago(long ms) {
		long s = Math.max(0, ms / 1000);
		if (s < 90) return s + "s ago";
		if (s < 5400) return (s / 60) + "m ago";
		if (s < 172800) return (s / 3600) + "h ago";
		return (s / 86400) + "d ago";
	}
}

