package com.altarsmp.fabric.mixin;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.weapon.WeaponBehavior;
import com.altarsmp.fabric.weapon.s1.BoneBladeWeapon;

/**
 * {@code ProjectileHitEvent}, which Fabric does not expose.
 *
 * <p>Every projectile in the game - arrows, spectral arrows, tridents, fireballs and the
 * thrown items - resolves its impact through {@code Projectile#onHit}, which then
 * dispatches to {@code onHitEntity} or {@code onHitBlock}. Hooking the parent catches both
 * cases with one injection, which is what the plugin got from a single event: Vulcan's
 * Crossbow explodes on impact, the Frost Scythe's shard shatters, the Striker's arrow
 * chain-lightnings, and the Nuke Launcher's warhead detonates.
 *
 * <p>The Bone Blade's cage is the one impact that is not a weapon projectile in the
 * ordinary sense: it is a named {@code Snowball}, so it is recognised by name and handed
 * straight to {@code BoneBladeWeapon#onCageImpact}, which stuns everyone inside the
 * configured hitbox.
 *
 * <p>Only the server side runs this. {@code onHit} fires on the client too, where the mod
 * has no world state to consult.
 */
@Mixin(net.minecraft.world.entity.projectile.Projectile.class)
public abstract class ProjectileMixin {

	@Inject(method = "onHit", at = @At("HEAD"))
	private void altarsmp$onProjectileHit(HitResult result, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (!(self.level() instanceof ServerLevel)) {
			return;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		@Nullable
		Entity hit = result instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
		mod.weapons().onProjectileHit(self, hit);
		if (BoneBladeWeapon.isCageProjectile(self) && self.getOwner() instanceof ServerPlayer shooter) {
			WeaponBehavior behavior = mod.weapons().get("boneblade");
			if (behavior instanceof BoneBladeWeapon boneBlade) {
				boneBlade.onCageImpact(shooter, result.getLocation());
			}
		}
	}
}
