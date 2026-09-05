package com.altarsmp.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.weapon.CombatHooks;

/**
 * The damage pipeline the plugin built out of {@code EntityDamageEvent} and
 * {@code EntityDamageByEntityEvent}. Fabric has no cancellable damage event, so
 * {@code LivingEntity#hurtServer} - the server-side entry point every hit goes through in
 * 26.x - is where {@link CombatHooks} runs.
 *
 * <p>Two things happen, in the order the plugin's priorities produced them: the hit is
 * either cancelled outright (stun, ban zone, PvP protection, weapon protection, parries)
 * or its amount is replaced by the faction system's amplified value (Hyperion's bonus
 * against the cursed, vampire night damage, the pale backstab). Cancelling is a plain
 * {@code CallbackInfoReturnable}; changing the amount is not, because the value is a
 * method argument, so the hit is re-issued with the new number while
 * {@link #altarsmp$amplifying} keeps the nested call from running the hooks twice.
 *
 * <p>{@code LivingEntity#die} is the death hook: kill counters, faction curses spreading
 * on a kill, the ban zone's eliminations and the Copper armour's death messages all hang
 * off {@code WeaponRegistry#onEntityDeath}.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	/** Set while the amplified hit is re-issued, so the hooks run exactly once. */
	@Unique
	private static final ThreadLocal<Boolean> altarsmp$amplifying = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void altarsmp$onHurtServer(ServerLevel level, DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> cir) {
		if (altarsmp$amplifying.get().booleanValue()) {
			return;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		LivingEntity self = (LivingEntity) (Object) this;
		if (CombatHooks.handleDamage(self, level, source, amount)) {
			cir.setReturnValue(false);
			return;
		}
		float amplified = CombatHooks.modifyDamage(self, source, amount);
		if (amplified == amount) {
			return;
		}
		altarsmp$amplifying.set(Boolean.TRUE);
		try {
			cir.setReturnValue(self.hurtServer(level, source, amplified));
		} finally {
			altarsmp$amplifying.set(Boolean.FALSE);
		}
	}

	@Inject(method = "die", at = @At("HEAD"))
	private void altarsmp$onDie(DamageSource source, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self.level() instanceof ServerLevel level)) {
			return;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return;
		}
		mod.weapons().onEntityDeath(self, level, source);
	}
}
