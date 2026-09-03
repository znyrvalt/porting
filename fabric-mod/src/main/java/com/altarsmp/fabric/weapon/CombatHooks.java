package com.altarsmp.fabric.weapon;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Stun;

/**
 * The damage pipeline. Everything the plugin did from Bukkit's
 * {@code EntityDamageEvent} / {@code EntityDamageByEntityEvent} listeners runs
 * here in one ordered pass, called from the {@code LivingEntity#hurtServer}
 * mixin:
 *
 * <ol>
 *   <li>stunned players cannot attack ({@code BoneBladeWeapon#onStunnedAttack});</li>
 *   <li>fall damage is cancelled while stunned ({@code #onFallDamage});</li>
 *   <li>the protection systems get a veto (ban zone, PvP protection, pale-effect
 *       protection, altar protection, weapon protection);</li>
 *   <li>the victim's held weapon and worn armour see {@code onDamaged};</li>
 *   <li>the attacker's held weapon and worn armour see {@code onAttack}.</li>
 * </ol>
 *
 * <p>Returning {@code true} cancels the damage entirely, which is what the
 * Bukkit listeners did with {@code setCancelled(true)}.</p>
 */
public final class CombatHooks {

	private CombatHooks() {
	}

	public static boolean handleDamage(LivingEntity victim, ServerLevel level, DamageSource source, float amount) {
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null) {
			return false;
		}
		LivingEntity attackerEntity = source.getEntity() instanceof LivingEntity living ? living : null;
		ServerPlayer attacker = attackerEntity instanceof ServerPlayer serverAttacker ? serverAttacker : null;
		ServerPlayer victimPlayer = victim instanceof ServerPlayer serverVictim ? serverVictim : null;

		// 1. stunned players cannot attack (BoneBlade cage)
		if (attacker != null && Stun.isStunned(attacker)) {
			tellStunned(attacker);
			return true;
		}

		// 2. no fall damage while stunned
		if (victimPlayer != null && source.is(DamageTypes.FALL) && Stun.isStunned(victimPlayer)) {
			return true;
		}

		// 3. protection systems
		if (victimPlayer != null && mod.protection().blockDamage(victimPlayer, source, amount, attacker)) {
			return true;
		}

		// 4. victim-side abilities (parries, curses, armour passives)
		if (mod.abilities().onDamaged(victim, source, amount)) {
			return true;
		}

		// 5. attacker-side abilities (on-hit weapon effects); a held weapon may
		// still cancel the hit here, which is what the Bow of Deception does to an
		// imposter's swing.
		if (attacker != null && victim != attacker
				&& mod.abilities().onAttack(attacker, victim, amount, isCritical(attacker))) {
			return true;
		}
		return false;
	}

	private static void tellStunned(ServerPlayer player) {
		com.altarsmp.fabric.util.Messaging.actionBar(player, "<red>You are stunned!");
	}

	/**
	 * Vanilla critical-hit conditions (falling, on the ground, not in water, not
	 * riding anything) - the abilities that scale with crits need this to match
	 * what the client shows.
	 */
	public static boolean isCritical(Player attacker) {
		return !attacker.onGround()
				&& attacker.fallDistance > 0.0F
				&& !attacker.isInWater()
				&& !attacker.isPassenger()
				&& !attacker.isSpectator();
	}

	/** Damage the plugin treated as "true" (bypasses armour/shields). */
	@Nullable
	public static ServerPlayer killerOf(DamageSource source) {
		return source.getEntity() instanceof ServerPlayer player ? player : null;
	}
}
