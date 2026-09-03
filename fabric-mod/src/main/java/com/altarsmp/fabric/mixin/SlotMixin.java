package com.altarsmp.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * {@code WeaponStoragePrevention#onInventoryClick} / {@code #onInventoryDrag}.
 *
 * <p>The plugin refused to let a legendary be clicked into a chest, shulker, barrel or
 * any other container its config listed. Vanilla asks {@code Slot#mayPlace} for every one
 * of those interactions - plain clicks, shift-clicks, drags and number-key swaps alike -
 * so a single veto there covers the whole surface, including the ones Bukkit needed
 * separate drag handlers for.
 *
 * <p>{@code Slot#container} is public in 26.x, which is what lets
 * {@code WeaponProtection#storageKind} name the container the way the config does
 * ({@code chest}, {@code shulker}, {@code barrel}, ...).
 *
 * <p>This runs on both logical sides, as vanilla's own container code does. The server's
 * answer is the one that counts: when a client predicts differently, the container menu
 * is resent and the item stays where the server put it.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void altarsmp$blockLegendaryStorage(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		if (mod.protection().blockSlotPlacement((Slot) (Object) this, stack)) {
			cir.setReturnValue(false);
		}
	}
}
