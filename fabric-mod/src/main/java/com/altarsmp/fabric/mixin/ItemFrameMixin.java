package com.altarsmp.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * {@code WeaponStoragePrevention#onItemFrameInteract}.
 *
 * <p>An item frame is a display case, and the plugin treated it as storage: right-clicking
 * an empty frame with a legendary in hand was refused, so no legendary could be hung up
 * where a hopper, a piston or another player could get at it. {@code ItemFrame#interact}
 * is the whole interaction - placing, rotating and taking the item back out - and
 * {@code WeaponProtection#blockItemFrame} only objects when the frame is empty and the
 * held stack is protected, so rotating and removing still work exactly as vanilla does.
 *
 * <p>{@code InteractionResult.FAIL} is the answer that stops the interaction and still
 * swings the arm, which is what a cancelled Bukkit event looked like to the player.
 */
@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {

	@Inject(method = "interact", at = @At("HEAD"), cancellable = true)
	private void altarsmp$blockFrameStorage(Player player, InteractionHand hand,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		if (mod.protection().blockItemFrame(serverPlayer, (ItemFrame) (Object) this, hand)) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
