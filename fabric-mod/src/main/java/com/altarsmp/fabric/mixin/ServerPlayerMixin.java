package com.altarsmp.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * {@code PlayerDropItemEvent} for the trial items.
 *
 * <p>Both the Bingo Book and the Copper Core were undroppable: the plugin cancelled the
 * drop event, which in Bukkit leaves the item in the player's hand. Vanilla has no such
 * event, but every player-initiated drop - the Q key, the whole-stack variant and the
 * "drop everything" key - ends up in {@code ServerPlayer#drop(ItemStack, boolean,
 * boolean)} with the stack already taken out of the inventory, so refusing there and
 * putting the stack back is the same behaviour.
 *
 * <p>Death is deliberately left alone: Bukkit's drop event does not fire when a player
 * dies, and neither does this veto ({@code isAlive} guard), so a dead player still drops
 * whatever the game rules say they drop.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)"
			+ "Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"), cancellable = true)
	private void altarsmp$blockTrialDrop(ItemStack stack, boolean dropAroundPlayer, boolean traceItem,
			CallbackInfoReturnable<ItemEntity> cir) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (!self.isAlive() || stack.isEmpty()) {
			return;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null || !mod.trials().blockDrop(self, stack)) {
			return;
		}
		if (!self.getInventory().add(stack)) {
			// No room anywhere: it stays on the ground rather than vanishing.
			return;
		}
		self.getInventory().setChanged();
		cir.setReturnValue(null);
	}
}
