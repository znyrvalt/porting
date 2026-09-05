package com.altarsmp.fabric.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.entity.LivingEntity;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.util.Messaging;

/**
 * Death-message replacement.
 *
 * <p>The plugin listened to {@code PlayerDeathEvent#deathMessage(...)}; the Copper
 * Leggings use it to report that the wearer was caught in a mace explosion instead of
 * showing the vanilla line. In 26.x the message is built by
 * {@code CombatTracker#getDeathMessage} and {@code ServerPlayer#die} broadcasts whatever
 * comes back, so replacing it here replaces it everywhere - the kill packet, the chat
 * broadcast and all three team-visibility variants read the same value.
 *
 * <p>The markup comes from {@code AbilityBus#armorDeathMessage}, which asks each worn
 * armour behaviour in turn and takes the first non-null answer.
 */
@Mixin(CombatTracker.class)
public abstract class CombatTrackerMixin {

	@Shadow
	@Final
	private LivingEntity mob;

	@Inject(method = "getDeathMessage", at = @At("RETURN"), cancellable = true)
	private void altarsmp$armorDeathMessage(CallbackInfoReturnable<Component> cir) {
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null || !(this.mob instanceof ServerPlayer victim)) {
			return;
		}
		ServerPlayer killer = victim.getLastHurtByPlayer() instanceof ServerPlayer player ? player : null;
		String markup = mod.abilities().armorDeathMessage(victim, killer);
		if (markup != null) {
			cir.setReturnValue(Messaging.msg(markup));
		}
	}
}
