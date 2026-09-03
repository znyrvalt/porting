package com.altarsmp.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.protection.WeaponProtection;

/**
 * Dropped legendaries, and the Chestplate Shard the trial hides on an island.
 *
 * <p>{@code WeaponStoragePrevention#onItemDamage} / {@code #onItemCombust} refused fire,
 * lava, cactus, explosions and the void for any protected item, and
 * {@code #onItemDespawn} kept them from ever ageing out while destruction protection was
 * on. Both are {@code ItemEntity} hooks: damage arrives at {@code hurtServer}, and the
 * despawn check lives in {@code tick}, where pinning the age to vanilla's unlimited
 * marker is exactly what {@code setUnlimitedLifetime} does.
 *
 * <p>When destruction protection is <em>off</em>, a hit is allowed but announced: the
 * plugin's {@code scheduleDestroyAnnouncement} built the broadcast and then dropped it on
 * the floor, so {@code WeaponProtection#announceDestruction} actually sends it, once per
 * item, if the item really is gone a moment later.
 *
 * <p>{@code playerTouch} is the pickup hook {@code CopperChestplateTrial#onItemPickup}
 * needs: when a shard leaves the ground, the island's armour stand goes with it and the
 * server is told who retrieved it. The tail injection plus the removal check means a
 * touch that collected nothing (full inventory, pickup delay) announces nothing.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void altarsmp$protectDroppedLegendary(ServerLevel level, DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> cir) {
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		ItemEntity self = (ItemEntity) (Object) this;
		WeaponProtection protection = mod.protection();
		protection.announceDestruction(self);
		if (protection.protectItemEntity(self, source)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void altarsmp$neverDespawn(CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (!(self.level() instanceof ServerLevel)) {
			return;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		if (mod.protection().preventDespawn(self)) {
			self.setUnlimitedLifetime();
		}
	}

	@Inject(method = "playerTouch", at = @At("TAIL"))
	private void altarsmp$shardPickedUp(Player player, CallbackInfo ci) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		ItemEntity self = (ItemEntity) (Object) this;
		if (!self.isRemoved()) {
			return;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		mod.trials().chestplate().onShardPickedUp(serverPlayer, self);
	}
}
