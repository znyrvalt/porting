package com.altarsmp.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * The bundle routes {@code WeaponStoragePrevention} closed off.
 *
 * <p>Bundles do not go through {@code Slot#mayPlace}: stacking onto or into a bundle is
 * handled by {@code BundleItem}'s two {@code ClickAction} overrides, which is why the
 * plugin needed its own bundle handler on top of the inventory-click one. Refusing the
 * override - returning {@code false}, "not handled" - leaves the item on the cursor and
 * nothing is stuffed into the bundle, which is the cancellation Bukkit produced.
 *
 * <p>{@code WeaponProtection#blockBundle} covers both directions the config cares about:
 * a legendary going into a bundle, and a bundle going into a legendary's slot.
 *
 * <p>Only the server side vetoes. The client runs the same code for prediction, and a
 * client that disagrees with its server would make the click feel broken; when the server
 * refuses, it resends the menu and the cursor keeps the item.
 */
@Mixin(BundleItem.class)
public abstract class BundleItemMixin {

	@Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
	private void altarsmp$blockStuffingBundle(ItemStack bundle, Slot slot, ClickAction action, Player player,
			CallbackInfoReturnable<Boolean> cir) {
		if (altarsmp$blocked(player, bundle, slot.getItem())) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
	private void altarsmp$blockStackingOntoBundle(ItemStack bundle, ItemStack other, Slot slot, ClickAction action,
			Player player, SlotAccess access, CallbackInfoReturnable<Boolean> cir) {
		if (altarsmp$blocked(player, bundle, other)) {
			cir.setReturnValue(false);
		}
	}

	private static boolean altarsmp$blocked(Player player, ItemStack bundle, ItemStack other) {
		if (!(player instanceof ServerPlayer)) {
			return false;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		return mod != null && mod.protection().blockBundle(bundle, other);
	}
}
