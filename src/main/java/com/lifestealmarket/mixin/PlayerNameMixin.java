package com.lifestealmarket.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.lifestealmarket.Nameplate;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerEntity.class)
public abstract class PlayerNameMixin {

	@ModifyReturnValue(method = "getDisplayName", at = @At("RETURN"))
	private Text lsm$decorateName(Text original) {

		try { return Nameplate.decorate(original, (PlayerEntity) (Object) this); }
		catch (Exception e) { return original; }
	}
}

