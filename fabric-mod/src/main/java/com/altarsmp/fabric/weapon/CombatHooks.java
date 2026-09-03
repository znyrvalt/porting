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

		// 0. the Contagion Signal's hitbox: every hit on it is cancelled and charged
		// to the ritual's health instead (Bukkit: onSignalDamage).
		if (mod.contagion().onSignalDamage(victim, source, amount)) {
			return true;
		}

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

	/**
	 * The damage <em>amplification</em> pass, the other half of Bukkit's
	 * {@code EntityDamageByEntityEvent#setDamage}. {@link #handleDamage} decides
	 * whether a hit is cancelled at all; this decides how hard it lands, and the
	 * mixin on {@code LivingEntity#hurtServer} must feed its result back into the
	 * damage argument before the hit is processed.
	 *
	 * <p>Today the only amplifier is the faction system (Hyperion bonus against
	 * cursed players, vampire night damage, pale backstab multiplier), exactly as in
	 * {@code VampireManager#onDamage}.
	 *
	 * @param victim the entity being hit
	 * @param source the damage source
	 * @param amount the incoming amount
	 * @return the amount to actually apply
	 */
	public static float modifyDamage(LivingEntity victim, DamageSource source, float amount) {
		AltarSMPMod mod = AltarSMPMod.get();
		if (mod == null || !(victim instanceof ServerPlayer victimPlayer)) {
			return amount;
		}
		if (!(source.getEntity() instanceof ServerPlayer attacker) || attacker == victimPlayer) {
			return amount;
		}
		return mod.factions().modifyPlayerDamage(attacker, victimPlayer, amount);
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
