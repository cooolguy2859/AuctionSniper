package com.lifestealmarket.mixin;

import com.lifestealmarket.ServerCommands;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Mixin(value = ClientPlayNetworkHandler.class, priority = 500)
public abstract class ServerCommandsMixin {
	@Shadow private CommandDispatcher<CommandSource> commandDispatcher;

	@Inject(method = "onCommandTree", at = @At("RETURN"), require = 0)
	private void lsm$snapshotServerCommands(CommandTreeS2CPacket packet, CallbackInfo ci) {
		try {
			Set<String> names = new HashSet<>();
			for (CommandNode<CommandSource> n : commandDispatcher.getRoot().getChildren())
				if (n.getName() != null) names.add(n.getName().toLowerCase(Locale.ROOT));
			ServerCommands.set(names);
		} catch (Exception ignored) {  }
	}
}

