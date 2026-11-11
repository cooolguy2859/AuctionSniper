package com.lifestealmarket.gui;

import com.lifestealmarket.NetworthTracker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemIcons {
	private ItemIcons() {}

	private static volatile Map<String, Item> byName = null;
	private static volatile Map<String, ItemStack> byVariant = null;
	private static final Map<String, ItemStack> cache = new ConcurrentHashMap<>();

	private static final Map<String, ItemStack> seen = new ConcurrentHashMap<>();

	public static void remember(String ticker, ItemStack real) {
		if (ticker == null || ticker.isEmpty() || real == null || real.isEmpty()) return;
		String k = NetworthTracker.norm(ticker);
		if (seen.containsKey(k) || seen.size() >= 4000) return;
		try { ItemStack c = real.copy(); c.setCount(1); seen.put(k, c); } catch (Exception ignored) {}
	}

	private static Map<String, ItemStack> variants() {
		Map<String, ItemStack> m = byVariant;
		if (m == null) {
			final Map<String, ItemStack> built = new HashMap<>();
			try {
				Item[] bases = { Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW };
				Registries.POTION.streamEntries().forEach(p -> {
					for (Item b : bases) {
						ItemStack st = net.minecraft.component.type.PotionContentsComponent.createStack(b, p);
						built.putIfAbsent(st.getName().getString().toLowerCase(Locale.ROOT), st);
					}
				});
			} catch (Exception ignored) {  }
			byVariant = m = built;
		}
		return m;
	}

	private static Map<String, Item> names() {
		Map<String, Item> m = byName;
		if (m == null) {
			m = new HashMap<>();
			for (Item it : Registries.ITEM) m.putIfAbsent(it.getName().getString().toLowerCase(Locale.ROOT), it);
			byName = m;
		}
		return m;
	}

	public static ItemStack stackFor(String ticker) {
		if (ticker == null || ticker.isEmpty()) return ItemStack.EMPTY;
		String k = NetworthTracker.norm(ticker);

		ItemStack real = seen.get(k);
		if (real != null) return real;
		return cache.computeIfAbsent(k, kk -> resolve(ticker));
	}

	private static ItemStack resolve(String ticker) {
		String base = Venues.plain(ticker).toLowerCase(Locale.ROOT);

		ItemStack variant = variants().get(base);
		if (variant != null) return variant.copy();
		Item it = names().get(base);
		if (it == null) {
			String mat = Venues.materialFor(ticker);
			if (mat != null && !mat.isEmpty()) {
				try {
					Identifier id = Identifier.of(mat);
					if (Registries.ITEM.containsId(id)) it = Registries.ITEM.get(id);
				} catch (Exception ignored) {}
			}
		}
		if (it == null || it == Items.AIR) {

			boolean ench = ticker.indexOf('·') >= 0 || base.split(" ").length <= 2;
			it = ench ? Items.ENCHANTED_BOOK : Items.PAPER;
		}
		return new ItemStack(it);
	}

	public static boolean draw(DrawContext ctx, String ticker, int x, int y, float scale) {
		ItemStack st = stackFor(ticker);
		if (st.isEmpty()) return false;
		var m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(x, y);
		m.scale(scale, scale);
		ctx.drawItem(st, 0, 0);
		m.popMatrix();
		return true;
	}
}

