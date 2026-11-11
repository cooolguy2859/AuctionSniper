package com.lifestealmarket.mixin;

import com.lifestealmarket.NetworthTracker;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundManager.class)
public class SoundManagerMixin {
	@Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
			at = @At("HEAD"), cancellable = true)
	private void lifestealmarket$muteHeadlessGuiSound(SoundInstance sound, CallbackInfoReturnable<SoundSystem.PlayResult> cir) {
		if (sound == null || !NetworthTracker.INSTANCE.isHeadlessSoundSuppressed()) return;

		if (net.minecraft.client.MinecraftClient.getInstance().currentScreen != null) return;

		Identifier id = sound.getId();
		if (id != null && isHeadlessMenuSound(id.getPath())) {
			cir.setReturnValue(SoundSystem.PlayResult.STARTED_SILENTLY);
		}
	}

	private static boolean isHeadlessMenuSound(String path) {
		if (path == null) return false;
		if (path.contains("amethyst")) return true;
		if (path.contains("chest") && path.endsWith(".open")) return true;

		if (path.contains("click") || path.contains("button")) return true;
		if (path.contains("page")) return true;
		if (path.contains("note_block") || path.contains("pling")) return true;
		return false;
	}
}

