package com.lifestealmarket.gui;

import com.lifestealmarket.Flair;
import com.lifestealmarket.MarketConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CustomizeScreen extends Screen {
	private static final int PW = 480, RCL = 12, RCR = PW - 12;

	private final Screen parent;
	private int C_ACCENT = 0xFF39D0FF, C_ACC_DIM = 0x5539D0FF, C_PANEL = 0xFF070C18;
	private int px, py, pw, ph;
	private double uiScale = 1.0;
	private TextFieldWidget guiHex, nameHex, gradHex;

	private String curColor = "", curTagId = "", curRoleId = "";
	private boolean flairSeeded = false;

	private record Dec(String kind, String text, String right, int x, int y, int h, int col) {}
	private final List<Dec> decor = new ArrayList<>();
	private final List<ClickableWidget> pending = new ArrayList<>();
	private int pvY;
	private int previewTop;

	private final List<int[]> paletteCells = new ArrayList<>();
	private static final int PAL_CW = 16, PAL_CH = 10;
	private final long openedAt = System.currentTimeMillis();

	@Override
	public void close() { Ui.escBack(this.client, parent); }

	private static String TAB = "gui";

	private static final class Flow {
		int x, y; final int startX;
		Flow(int x, int y) { this.x = x; this.y = y; this.startX = x; }
		int[] place(int w, int maxX) {
			if (x > startX && x + w > maxX) { x = startX; y += 20; }
			int[] p = { x, y }; x += w + 4; return p;
		}
		int end() { return y + 20; }
	}

	public CustomizeScreen(Screen parent) {
		super(Text.literal("Style"));
		this.parent = parent;
		Flair.forcePoll();
	}

	private void seedFromFeed(boolean reinit) {
		if (flairSeeded || !Flair.loaded()) return;
		flairSeeded = true;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			String[] f = Flair.of(mc.player.getName().getString());
			if (f != null) {
				curColor = f[0];
				curTagId = f.length > 2 ? f[2] : "";
				curRoleId = f.length > 4 ? f[4] : "";
			}
		}
		if (reinit) this.clearAndInit();
	}

	private int lastLabelEnd = 0;

	private void addLabel(String text, int y) {
		decor.add(new Dec("label", text, null, RCL + 6, y, 0, 0));
		lastLabelEnd = RCL + 6 + this.textRenderer.getWidth(Ui.strip(text)) + 10;
	}

	@Override
	protected void init() {
		int a = MarketConfig.themeColor(MarketConfig.get().theme);
		C_ACCENT = a; C_ACC_DIM = (a & 0x00FFFFFF) | 0x55000000;
		C_PANEL = StyledButton.lerpColor(0xFF080D18, 0xFF000000 | (a & 0xFFFFFF), 0.14f);
		pw = PW;
		decor.clear(); pending.clear(); paletteCells.clear();
		guiHex = nameHex = gradHex = null;
		seedFromFeed(false);

		int cur = 36;

		String[][] tabs = { { "gui", "GUI Colour" }, { "name", "Name" }, { "gradient", "Gradient" },
				{ "tag", "Tag" }, { "role", "Title" } };
		Flow tb = new Flow(RCL, cur);
		for (String[] td : tabs) {
			boolean active = td[0].equals(TAB);
			String lbl = (active ? "§l" : "§7") + td[1];
			int wd = this.textRenderer.getWidth(Ui.strip(lbl)) + 14 + (active ? 4 : 0);
			int[] p = tb.place(wd, RCR);
			pending.add(new StyledButton(p[0], p[1], wd, 16, Text.literal(lbl), active,
					() -> { TAB = td[0]; this.clearAndInit(); }).accent(C_ACCENT));
		}
		cur = tb.end() + 6;

		int sec0 = cur;
		switch (TAB) {
			case "gui" -> {
				decor.add(new Dec("label", "§7GUI Colour §8— only you see it", null, RCL + 6, cur, 0, 0)); cur += 12;
				Flow f = new Flow(RCL + 6, cur);
				for (String t : MarketConfig.selectableThemes()) {
					boolean active = t.equalsIgnoreCase(MarketConfig.get().theme);

					boolean art = MarketConfig.isArtTheme(t);
					String lbl = (active ? "§l" : "") + (art ? "✦ " : "")
							+ Ui.trim(Ui.strip(MarketConfig.themeLabel(t)), 16) + (active ? " §a✓" : "");
					int wd = this.textRenderer.getWidth(Ui.strip(lbl)) + 24 + (active ? 4 : 0);
					int[] p = f.place(wd, RCR - 6);
					int col = MarketConfig.themeColor(t);
					StyledButton b = new StyledButton(p[0], p[1], wd, 16, Text.literal("§f" + lbl), active, () -> {
						MarketConfig.get().theme = t; MarketConfig.save(); this.clearAndInit();
					}).accent(col);
					if ("custom".equalsIgnoreCase(t) && MarketConfig.get().customAccent.isEmpty())
						b.chipGradient(0xFFFF5555, 0xFF55FFFF);
					else if (art) {
						b.chipGradient(col, StyledButton.lerpColor(col, 0xFFFFFFFF, 0.55f))
						 .tooltip("§6✦ §lCustom theme");
					} else b.chip(col);
					pending.add(b);
				}
				cur = f.end();
				if (MarketConfig.ownsType("anygui")) cur = addPalette(cur, 1);
				decor.add(new Dec("card", null, null, 0, sec0 - 4, cur - sec0 + 6, C_ACCENT)); cur += 10;
			}
			case "name" -> {
				List<MarketConfig.OwnedItem> names = MarketConfig.ownedItems("nametag");
				addLabel("§7Name Colour §8— everyone sees it", cur);
				if (names.isEmpty() && !MarketConfig.ownsType("anytag"))
					decor.add(new Dec("hint", "§8nothing owned yet — grab one in the Shop", null, lastLabelEnd, cur, 0, 0));
				cur += 12;
				Flow f = new Flow(RCL + 6, cur);
				pending.add(noneButton(f, curColor.isEmpty(), () -> applyFlair("", curTagId)));
				for (MarketConfig.OwnedItem nc : names) {
					boolean active = nc.payload().equalsIgnoreCase(curColor);
					int col = Ui.argb(nc.payload(), 0xFFFFFFFF);
					String lbl = (active ? "§l" : "") + Ui.trim(Ui.shortName(nc.name(), "nametag"), 14) + (active ? " §a✓" : "");
					int wd = this.textRenderer.getWidth(Ui.strip(lbl)) + 24 + (active ? 4 : 0);
					int[] p = f.place(wd, RCR - 6);
					pending.add(new StyledButton(p[0], p[1], wd, 16, Text.literal("§f" + lbl), active,
							() -> applyFlair(nc.payload(), curTagId)).accent(col).chip(col));
				}
				cur = f.end();
				if (MarketConfig.ownsType("anytag")) cur = addPalette(cur, 0);
				decor.add(new Dec("card", null, null, 0, sec0 - 4, cur - sec0 + 6, 0xFFFF6FB5)); cur += 10;
			}
			case "gradient" -> {
				List<MarketConfig.OwnedItem> grads = MarketConfig.ownedItems("gradientname");
				addLabel("§7Gradient Name §8— everyone sees it", cur);
				if (grads.isEmpty() && !MarketConfig.ownsType("anygradient"))
					decor.add(new Dec("hint", "§8nothing owned yet — grab one in the Shop", null, lastLabelEnd, cur, 0, 0));
				cur += 12;
				Flow f = new Flow(RCL + 6, cur);
				pending.add(noneButton(f, curColor.isEmpty(), () -> applyFlair("", curTagId)));
				for (MarketConfig.OwnedItem gr : grads) {
					String spec = gr.payload().replace("#", "").toUpperCase(Locale.ROOT);
					String[] pc = spec.split("-", 2);
					int c1 = Ui.argb(pc[0], 0xFFFF8FBF), c2 = Ui.argb(pc.length > 1 ? pc[1] : pc[0], 0xFFFFD34A);
					boolean active = spec.equalsIgnoreCase(curColor);
					String lbl = (active ? "§l" : "") + Ui.trim(Ui.shortName(gr.name(), "gradientname"), 14) + (active ? " §a✓" : "");
					int wd = this.textRenderer.getWidth(Ui.strip(lbl)) + 24 + (active ? 4 : 0);
					int[] p = f.place(wd, RCR - 6);
					pending.add(new StyledButton(p[0], p[1], wd, 16, Text.literal("§f" + lbl), active,
							() -> applyFlair(spec, curTagId)).accent(c1).chipGradient(c1, c2));
				}
				if (MarketConfig.ownsType("anygradient")) {
					int[] p = f.place(104, RCR - 6);
					gradHex = new TextFieldWidget(this.textRenderer, p[0], p[1], 74, 16, Text.literal("hex"));
					gradHex.setPlaceholder(Text.literal("§8HEX-HEX"));
					gradHex.setMaxLength(13);
					gradHex.setText(curColor.contains("-") ? curColor : "");
					pending.add(gradHex);
					pending.add(new StyledButton(p[0] + 77, p[1], 27, 16, Text.literal("§7Set"), () -> {
						String h = gradHex.getText().trim().toUpperCase(Locale.ROOT);
						if (h.matches("[0-9A-F]{6}-[0-9A-F]{6}")) applyFlair(h, curTagId);
						else msg("§cUse two hex colours like FF8FBF-FFD34A.");
					}).accent(C_ACCENT));
				}
				cur = f.end();
				decor.add(new Dec("card", null, null, 0, sec0 - 4, cur - sec0 + 6, 0xFFFF5E62)); cur += 10;
			}
			case "role" -> {

				List<MarketConfig.OwnedItem> roles = new ArrayList<>();
				for (MarketConfig.OwnedItem t : MarketConfig.ownedItems("tag")) if (isRole(t)) roles.add(t);
				addLabel("§7Title §8— everyone sees it", cur);
				if (roles.isEmpty())
					decor.add(new Dec("hint", "§8titles are earned, not bought", null, lastLabelEnd, cur, 0, 0));
				cur += 12;
				Flow f = new Flow(RCL + 6, cur);
				pending.add(noneButton(f, curRoleId.isEmpty() || curRoleId.equals("none"),
						() -> applyRole("none")));
				for (MarketConfig.OwnedItem rl : roles) {
					boolean active = rl.id().equalsIgnoreCase(curRoleId);

					String shown = rl.payload().trim();
					int wd = this.textRenderer.getWidth(Ui.strip(shown)) + 18 + (active ? 8 : 0);
					int[] p = f.place(wd, RCR - 6);
					pending.add(new StyledButton(p[0], p[1], wd, 16,
							Text.literal(shown + (active ? " §a✓" : "")), active,
							() -> { if (!active) applyRole(rl.id()); }).accent(Ui.GOLD));
				}
				cur = f.end();
				decor.add(new Dec("card", null, null, 0, sec0 - 4, cur - sec0 + 6, Ui.GOLD)); cur += 10;
			}
			default -> {

				List<MarketConfig.OwnedItem> tags2 = new ArrayList<>();
				for (MarketConfig.OwnedItem t : MarketConfig.ownedItems("tag")) if (!isRole(t)) tags2.add(t);
				addLabel("§7Tag §8— everyone sees it", cur);
				if (tags2.isEmpty()) decor.add(new Dec("hint", "§8nothing owned yet — grab one in the Shop", null, lastLabelEnd, cur, 0, 0));
				cur += 12;
				Flow f = new Flow(RCL + 6, cur);

				pending.add(noneButton(f, curTagId.isEmpty() || curTagId.equals("none"),
						() -> applyFlair(curColor, "none")));
				for (MarketConfig.OwnedItem tg : tags2) {
					boolean active = tg.id().equalsIgnoreCase(curTagId);
					String nm = Ui.trim(Ui.shortName(tg.name(), "tag"), 14);
					String lbl = (active ? "§l" : "") + nm + (active ? " §a✓" : "");
					String glyph = tg.payload().trim();
					int wd = this.textRenderer.getWidth(Ui.strip(lbl)) + this.textRenderer.getWidth(glyph) + 16 + (active ? 4 : 0);
					int[] p = f.place(wd, RCR - 6);

					pending.add(new StyledButton(p[0], p[1], wd, 16,
							Text.literal((active ? "§l" : "§f") + lbl.replace("§l", "")), active,
							() -> { if (!active) applyFlair(curColor, tg.id()); })
							.accent(isRole(tg) ? Ui.GOLD : C_ACCENT).chipTag(glyph));
				}
				cur = f.end();
				decor.add(new Dec("card", null, null, 0, sec0 - 4, cur - sec0 + 6, Ui.GOLD)); cur += 10;
			}
		}

		ph = 320;
		pvY = Math.max(cur + 2, ph - 84);
		previewTop = cur + 6;

		double frac = MarketConfig.guiFrac();
		uiScale = Math.min(this.width * frac / pw, this.height * frac / ph);
		px = (this.width - pw) / 2; py = (this.height - ph) / 2;
		for (ClickableWidget w : pending) { w.setX(w.getX() + px); w.setY(w.getY() + py); addDrawableChild(w); }

		addDrawableChild(new StyledButton(px + pw - 12 - 56, py + 7, 56, 17, Text.literal("§fBack"),
				() -> { if (this.client != null) this.client.setScreen(parent); }).accent(C_ACCENT));
		addDrawableChild(new StyledButton(px + pw - 12 - 120, py + 7, 60, 17, Text.literal("§b✦ Shop"),
				() -> { if (this.client != null) this.client.setScreen(new ShopScreen(this)); }).accent(C_ACCENT));
	}

	private int addPalette(int cur, int target) {
		decor.add(new Dec("label", "§8custom:", null, RCL + 6, cur + 4, 0, 0));
		int gx0 = RCL + 6 + this.textRenderer.getWidth("custom:") + 8;
		for (int c = 0; c <= 16; c++) {
			int argb = c == 16 ? 0xFFFFFFFF : MarketConfig.hsv(c / 16f, 0.85f, 1f);
			paletteCells.add(new int[]{ gx0 + c * (PAL_CW + 1), cur + 3, argb, target });
		}
		int hx = gx0 + 17 * (PAL_CW + 1) + 6;
		TextFieldWidget fld = new TextFieldWidget(this.textRenderer, hx, cur, 46, 16, Text.literal("hex"));
		fld.setPlaceholder(Text.literal("§8RRGGBB"));
		fld.setMaxLength(6);
		pending.add(fld);
		if (target == 1) {
			guiHex = fld;
			guiHex.setText(MarketConfig.get().customAccent);
			pending.add(new StyledButton(hx + 49, cur, 27, 16, Text.literal("§7Set"), () -> {
				String h = guiHex.getText().trim();
				if (h.matches("[0-9a-fA-F]{6}")) {
					MarketConfig.get().customAccent = h.toUpperCase(Locale.ROOT);
					MarketConfig.get().theme = "custom"; MarketConfig.save(); this.clearAndInit();
				} else msg("§cThat isn't a RRGGBB hex colour.");
			}).accent(C_ACCENT));
		} else {
			nameHex = fld;
			nameHex.setText(curColor.contains("-") ? "" : curColor);
			pending.add(new StyledButton(hx + 49, cur, 27, 16, Text.literal("§7Set"), () -> {
				String h = nameHex.getText().trim();
				if (h.matches("[0-9a-fA-F]{6}")) applyFlair(h.toUpperCase(Locale.ROOT), curTagId);
				else msg("§cThat isn't a RRGGBB hex colour.");
			}).accent(C_ACCENT));
		}
		return cur + 20;
	}

	private void drawHowItLooks(DrawContext ctx, int x, int y, int w, int h) {
		if (h < 24) return;
		StyledButton.roundFill(ctx, x, y, w, h, 0x30000000);
		ctx.fill(x, y + 1, x + 1, y + h - 1, 0xAA000000 | (C_ACCENT & 0xFFFFFF));
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8HOW IT LOOKS"), x + 8, y + 5, 0xFFFFFFFF);

		int inner = y + 16, ih = h - 22;

		MinecraftClient mc = MinecraftClient.getInstance();
		String me = mc.player != null ? mc.player.getName().getString() : "You";
		var name = Ui.colouredName(me, curColor.isEmpty() ? null : curColor);
		var tags = Text.empty();
		for (MarketConfig.OwnedItem tg : MarketConfig.ownedItems("tag")) {
			if (tg.id().equalsIgnoreCase(curRoleId)) tags.append(Text.literal(tg.payload()));
		}
		for (MarketConfig.OwnedItem tg : MarketConfig.ownedItems("tag")) {
			if (tg.id().equalsIgnoreCase(curTagId)) tags.append(Text.literal(tg.payload()));
		}

		int cwx = x + 8, cwy = inner, cww = w - 16, cwh = ih;
		StyledButton.roundFill(ctx, cwx, cwy, cww, cwh, 0xCC05070C);
		StyledButton.roundBorder(ctx, cwx, cwy, cww, cwh, 0x22FFFFFF);

		int maxW = cww - 14, ly = cwy + 5;
		var others1 = Text.literal("§7<§fSteve§7> anyone selling spawners");
		var mine = Text.empty().append(Text.literal("§7<")).append(tags).append(name)
				.append(Text.literal("§7> §fi've got 12, 300k each"));
		ctx.drawTextWithShadow(this.textRenderer, Ui.fitText(this.textRenderer, others1, maxW), cwx + 7, ly, 0xFFFFFFFF);
		ctx.drawTextWithShadow(this.textRenderer, Ui.fitText(this.textRenderer, mine, maxW), cwx + 7, ly + 11, 0xFFFFFFFF);
	}

	private static boolean isRole(MarketConfig.OwnedItem i) {
		return i.id().toLowerCase(Locale.ROOT).startsWith("role_");
	}

	private StyledButton noneButton(Flow f, boolean active, Runnable onPress) {
		String lbl = active ? "§lNone §a✓" : "None";
		int wd = this.textRenderer.getWidth(Ui.strip(lbl)) + 14 + (active ? 4 : 0);
		int[] p = f.place(wd, RCR - 6);
		return new StyledButton(p[0], p[1], wd, 16, Text.literal("§f" + lbl), active, onPress).accent(C_ACCENT);
	}

	@Override
	public void tick() {
		seedFromFeed(true);
		super.tick();
	}

	private void applyRole(String roleId) {
		if (!Flair.loaded()) { msg("§7Loading your current flair… try again in a second."); return; }
		Flair.set(curColor, null, roleId, ok -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.execute(() -> {
				if (ok) {
					curRoleId = roleId;
					msg("§a✔ Applied — everyone running the mod sees it.");
					this.clearAndInit();
				} else msg("§cCouldn't apply — you may not own that (or the market is down).");
			});
		});
	}

	private void applyFlair(String color, String tagId) {
		if (!Flair.loaded()) { msg("§7Loading your current flair… try again in a second."); return; }
		Flair.set(color, tagId, ok -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.execute(() -> {
				if (ok) {
					curColor = color; curTagId = tagId;
					msg("§a✔ Applied — everyone running the mod sees it.");
					this.clearAndInit();
				} else msg("§cCouldn't apply — you may not own that (or the market is down).");
			});
		});
	}

	private void msg(String s) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.sendMessage(Text.literal("§d[Style] §7" + s), false);
	}

	private double toGuiX(double x) { return (x - this.width / 2.0) / uiScale + this.width / 2.0; }
	private double toGuiY(double y) { return (y - this.height / 2.0) / uiScale + this.height / 2.0; }

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click rc, boolean doubled) {
		if (NavBar.click(rc.x(), rc.y(), this)) return true;
		double gx = toGuiX(rc.x()), gy = toGuiY(rc.y());
		for (int[] cell : paletteCells) {
			int x = px + cell[0], y = py + cell[1];
			if (gx >= x && gx < x + PAL_CW && gy >= y && gy < y + PAL_CH) {
				StyledButton.playClick();
				String hex = String.format(Locale.ROOT, "%06X", cell[2] & 0xFFFFFF);
				if (cell[3] == 0) applyFlair(hex, curTagId);
				else {
					MarketConfig.get().customAccent = hex;
					MarketConfig.get().theme = "custom";
					MarketConfig.save();
					this.clearAndInit();
				}
				return true;
			}
		}
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
		NavBar.draw(ctx, this.textRenderer, "style", this.width, this.height, px, py, pw, uiScale);
	}

	private void renderInner(DrawContext ctx, int mx, int my, float delta) {
		Ui.panel(ctx, this.textRenderer, px, py, pw, ph, C_PANEL, C_ACCENT, "✿ Style", "§7your wardrobe");

		for (Dec d : decor) {
			if (!d.kind().equals("card")) continue;
			StyledButton.roundFill(ctx, px + RCL - 2, py + d.y(), RCR - RCL + 4, d.h(), 0x30000000);
			ctx.fill(px + RCL - 2, py + d.y() + 1, px + RCL - 1, py + d.y() + d.h() - 1, 0xAA000000 | (d.col() & 0xFFFFFF));
		}
		for (Dec d : decor) {
			switch (d.kind()) {
				case "card" -> {}
				case "head" -> Ui.sectionHead(ctx, this.textRenderer, px + d.x(), py + d.y(), px + RCR, d.text(), d.right(), C_ACCENT);
				default -> ctx.drawTextWithShadow(this.textRenderer, Text.literal(d.text()), px + d.x(), py + d.y(), 0xFFFFFFFF);
			}
		}

		String hovHex = null; int hovMx = 0, hovMy = 0;
		for (int[] cell : paletteCells) {
			int x = px + cell[0], y = py + cell[1];
			ctx.fill(x, y, x + PAL_CW, y + PAL_CH, cell[2]);
			ctx.fill(x + PAL_CW - 1, y, x + PAL_CW, y + PAL_CH, 0x33000000);
			ctx.fill(x, y + PAL_CH - 1, x + PAL_CW, y + PAL_CH, 0x33000000);
			String hex = String.format(Locale.ROOT, "%06X", cell[2] & 0xFFFFFF);
			boolean active = cell[3] == 0 ? hex.equalsIgnoreCase(curColor)
					: "custom".equalsIgnoreCase(MarketConfig.get().theme) && hex.equalsIgnoreCase(MarketConfig.get().customAccent);
			boolean hov = mx >= x && mx < x + PAL_CW && my >= y && my < y + PAL_CH;
			if (active) {
				StyledButton.roundBorder(ctx, x - 1, y - 1, PAL_CW + 2, PAL_CH + 2, 0xFFFFFFFF);
				ctx.drawText(this.textRenderer, Text.literal("✓"), x + 5, y + 1, 0xFF000000, false);
			} else if (hov) StyledButton.roundBorder(ctx, x - 1, y - 1, PAL_CW + 2, PAL_CH + 2, 0xCCFFFFFF);
			if (hov) { hovHex = hex; hovMx = mx; hovMy = my; }
		}
		if (hovHex != null) {
			String lbl = "#" + hovHex;
			int wt = this.textRenderer.getWidth(lbl);
			ctx.fill(hovMx + 6, hovMy - 9, hovMx + 14 + wt, hovMy + 3, 0xE0000000);
			StyledButton.roundBorder(ctx, hovMx + 6, hovMy - 9, wt + 8, 12, 0x44FFFFFF);
			ctx.drawTextWithShadow(this.textRenderer, Text.literal("§f" + lbl), hovMx + 10, hovMy - 7, 0xFFFFFFFF);
		}

		int prevH = Math.min(54, py + pvY - 8 - (py + previewTop));
		if (prevH >= 24) drawHowItLooks(ctx, px + RCL, py + pvY - 8 - prevH, RCR - RCL, prevH);

		int apvY = py + pvY, stageW = RCR - RCL, stageH = 58;
		StyledButton.roundFill(ctx, px + RCL, apvY, stageW, stageH, 0xCC04070E);
		int mid = px + pw / 2, gw = stageW / 2 - 6, acc = C_ACCENT & 0xFFFFFF;
		int ga = 0x24;
		Ui.hGrad(ctx, mid - gw, apvY + 1, mid, apvY + stageH - 1, acc, (ga << 24) | acc, 6);
		Ui.hGrad(ctx, mid, apvY + 1, mid + gw, apvY + stageH - 1, (ga << 24) | acc, acc, 6);
		ctx.fillGradient(px + RCL + 1, apvY + 1, px + RCR - 1, apvY + stageH - 1, 0x0AFFFFFF, 0x18000000);
		StyledButton.roundBorder(ctx, px + RCL, apvY, stageW, stageH, C_ACC_DIM);
		ctx.drawTextWithShadow(this.textRenderer, Text.literal("§8YOUR NAMETAG"), px + RCL + 6, apvY + 4, 0xFFFFFFFF);
		MinecraftClient mc = MinecraftClient.getInstance();
		String me = mc.player != null ? mc.player.getName().getString() : "You";
		var t = Text.empty();

		for (MarketConfig.OwnedItem tg : MarketConfig.ownedItems("tag"))
			if (tg.id().equalsIgnoreCase(curRoleId)) t.append(Text.literal(tg.payload()));
		for (MarketConfig.OwnedItem tg : MarketConfig.ownedItems("tag"))
			if (tg.id().equalsIgnoreCase(curTagId)) t.append(Text.literal(tg.payload()));
		t.append(Ui.colouredName(me, curColor.isEmpty() ? null : curColor));

		if (MarketConfig.get().showNetworthTags && !MarketConfig.get().netPrivate) {
			long nw = com.lifestealmarket.NetworthTracker.INSTANCE.netWorth();
			if (nw > 0) t.append(Text.literal(" §a✦ " + com.lifestealmarket.Coins.fmt(nw)));
		}
		var mm = ctx.getMatrices();
		mm.pushMatrix(); mm.translate(px + pw / 2f, apvY + 30f); mm.scale(1.6f, 1.6f);
		ctx.drawCenteredTextWithShadow(this.textRenderer, t, 0, -4, 0xFFFFFFFF);
		mm.popMatrix();
		ctx.fill(mid - 78, apvY + 40, mid + 78, apvY + 41, 0x2EFFFFFF);
		mm.pushMatrix(); mm.translate(px + pw / 2f, apvY + 49f); mm.scale(1.6f, -1.6f);
		ctx.drawCenteredTextWithShadow(this.textRenderer, t, 0, -4, 0x26FFFFFF);
		mm.popMatrix();

		ctx.fill(px + 1, py + ph - 20, px + pw - 1, py + ph - 1, 0x50000000);
		ctx.fill(px + 1, py + ph - 20, px + pw - 1, py + ph - 19, 0x22FFFFFF);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("§8GUI + background: only you  ·  name + tag: §7everyone"),
				px + pw / 2, py + ph - 15, 0xFFFFFFFF);

		super.render(ctx, mx, my, delta);
	}
}

