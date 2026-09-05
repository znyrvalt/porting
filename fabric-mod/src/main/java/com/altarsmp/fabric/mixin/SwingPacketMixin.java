package com.altarsmp.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * {@code PlayerAnimationEvent} - the arm swing that did not necessarily hit anything.
 *
 * <p>Two weapons need the swing itself rather than a landed hit: Dragonrend's Hypersonic
 * Slash fires on every swing while the ability is armed, and the Omen's forbidden circle
 * launches a wind charge per swing (throttled to one per 200ms inside the weapon). Fabric
 * has no swing event, so the packet handler is the hook: {@code handleAnimate} is where
 * the server learns a player swung, and it runs on the server thread already
 * ({@code PacketUtils.ensureRunningOnSameThread}).
 *
 * <p>{@code AbilityBus#onArmSwing} does the throttling and the "is this player holding
 * something that cares" checks, so the packet handler stays a one-line delegate.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SwingPacketMixin {

	@Inject(method = "handleAnimate", at = @At("TAIL"))
	private void altarsmp$onArmSwing(ServerboundSwingPacket packet, CallbackInfo ci) {
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
		if (player != null) {
			mod.abilities().onArmSwing(player);
		}
	}
}
