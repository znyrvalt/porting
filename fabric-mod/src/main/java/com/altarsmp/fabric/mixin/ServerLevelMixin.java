package com.altarsmp.fabric.mixin;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * Two server-level hooks that have no Fabric event.
 *
 * <p><b>Explosions.</b> {@code AltarBreakListener} tore an altar down when its structure
 * block appeared in {@code EntityExplodeEvent}'s block list. Every {@code Level#explode}
 * overload ends up in {@code ServerLevel}'s implementation, so its tail is the one place
 * that sees every explosion - creepers, TNT, beds, end crystals, the Nuke Launcher's
 * warhead and Vulcan's arrows alike - and {@code AltarManager#onExplosion} does the rest by
 * checking the recorded altars in blast range.
 *
 * <p><b>Projectile launch.</b> {@code ProjectileLaunchEvent} fed the weapons that arm
 * themselves when their projectile enters the world (Vulcan's Crossbow tags its arrows,
 * the Omen refuses to let its wind charges leave a forbidden circle). New entities are
 * added through {@code ServerLevel#addFreshEntity}, and filtering that for projectiles is
 * the same moment Bukkit's event fired.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

	@Inject(method = "explode", at = @At("TAIL"))
	private void altarsmp$onExplosion(@Nullable Entity exploded, @Nullable DamageSource damageSource,
			@Nullable ExplosionDamageCalculator damageCalculator, double x, double y, double z, float radius,
			boolean fire, Level.ExplosionInteraction explosionInteraction, ParticleOptions smallParticle,
			ParticleOptions largeParticle, WeightedList<ExplosionParticleInfo> particleInfo,
			Holder<SoundEvent> sound, CallbackInfo ci) {
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		mod.altars().onExplosion((ServerLevel) (Object) this, new Vec3(x, y, z), radius);
	}

	@Inject(method = "addFreshEntity", at = @At("TAIL"))
	private void altarsmp$onProjectileLaunch(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() || !(entity instanceof net.minecraft.world.entity.projectile.Projectile)) {
			return;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		mod.weapons().onProjectileLaunch(entity);
	}
}
