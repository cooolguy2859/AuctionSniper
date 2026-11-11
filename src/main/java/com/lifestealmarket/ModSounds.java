package com.lifestealmarket;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ModSounds {
	public static SoundEvent SOFT_CLICK;
	public static SoundEvent UI_CLICK;
	public static SoundEvent DRIP;

	public static SoundEvent THOCK;
	public static SoundEvent COIN;
	public static SoundEvent MARIMBA;
	public static SoundEvent TICK;
	public static SoundEvent POP;
	public static SoundEvent CHORD;

	private ModSounds() {}

	public static void register() {
		SOFT_CLICK = reg("soft_click");
		UI_CLICK = reg("ui_click");
		DRIP = reg("drip");
		THOCK = reg("thock");
		COIN = reg("coin");
		MARIMBA = reg("marimba");
		TICK = reg("tick");
		POP = reg("pop");
		CHORD = reg("chord");
	}

	private static SoundEvent reg(String name) {
		Identifier id = Identifier.of("lifestealmarket", name);
		return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
	}
}

