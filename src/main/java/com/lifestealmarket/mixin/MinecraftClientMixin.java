package com.lifestealmarket.mixin;

import com.lifestealmarket.NetworthTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void lifestealmarket$silentAhHistory(Screen screen, CallbackInfo ci) {
		if (screen != null && NetworthTracker.INSTANCE.interceptScreen(screen)) ci.cancel();
	}
}

