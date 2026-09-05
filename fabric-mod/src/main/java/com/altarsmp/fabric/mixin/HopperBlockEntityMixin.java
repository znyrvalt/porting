package com.altarsmp.fabric.mixin;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * {@code WeaponStoragePrevention#onHopperMove} - Bukkit's {@code InventoryMoveItemEvent}.
 *
 * <p>Every automated move a hopper, hopper minecart or dropper makes funnels through
 * {@code HopperBlockEntity#addItem(Container, Container, ItemStack, Direction)}: it is
 * what pushes an item out of one container into the next and what pulls an item entity
 * into a container. Refusing there is refusing the move - returning the stack untouched
 * is the same answer the vanilla caller gets when nothing fit, so the item stays where it
 * was and the hopper simply tries again on its next cooldown.
 *
 * <p>{@code WeaponProtection#blockAutomatedMove} checks both ends against the configured
 * container kinds, so a legendary can neither be sucked out of a protected chest nor
 * pushed into one.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

	@Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;"
			+ "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)"
			+ "Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
	private static void altarsmp$blockAutomatedMove(@Nullable Container source, Container target, ItemStack stack,
			@Nullable Direction direction, CallbackInfoReturnable<ItemStack> cir) {
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		if (mod.protection().blockAutomatedMove(target, source, stack)) {
			cir.setReturnValue(stack);
		}
	}
}
