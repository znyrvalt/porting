package com.altarsmp.fabric.ability;

import javax.annotation.Nullable;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.config.AltarConfig;

/**
 * Port of {@code BaseWeapon#applyTrueDamage} / {@code #applyTotemEffects}.
 *
 * <p>Ability damage in AltarSMP bypasses armour, shields and resistance because
 * it writes health directly. The original took great care to keep vanilla death
 * semantics working while doing that, and every branch is reproduced here:</p>
 * <ol>
 *   <li>skip while the target is still inside half of its invulnerability window;</li>
 *   <li>skip creative players;</li>
 *   <li>unless bypassed, run the target through {@link AbilityImmunity};</li>
 *   <li>if the hit would be lethal and the player holds a totem (off hand first,
 *       then main hand), consume it and apply the configured totem effects;</li>
 *   <li>otherwise drain absorption first, then health, down to a 0.5 HP floor
 *       (the same floor the plugin used, which is what makes a "true damage"
 *       hit feel like true damage without deleting a player outright);</li>
 *   <li>credit the killer, play the hurt animation/sound and reset the
 *       invulnerability timer to 10 ticks.</li>
 * </ol>
 */
public final class TrueDamage {

	private TrueDamage() {
	}

	public static void apply(LivingEntity target, double amount) {
		apply(target, amount, null, false);
	}

	public static void apply(LivingEntity target, double amount, @Nullable ServerPlayer attacker) {
		apply(target, amount, attacker, false);
	}

	public static void apply(LivingEntity target, double amount, @Nullable ServerPlayer attacker, boolean bypassImmunity) {
		if (target.isRemoved() || target.isDeadOrDying()) {
			return;
		}
		if (target.invulnerableTime > target.invulnerableDuration / 2) {
			return;
		}
		if (target instanceof Player player && player.isCreative()) {
			return;
		}
		AltarSMPMod mod = AltarSMPMod.get();
		if (!bypassImmunity && target instanceof ServerPlayer victim) {
			if (mod == null || !mod.immunity().allowEnvironmentalHit(victim)) {
				return;
			}
		}
		if (target instanceof ServerPlayer player && consumeTotemIfLethal(player, amount)) {
			return;
		}

		double remaining = amount;
		float absorption = target.getAbsorptionAmount();
		if (absorption > 0.0F) {
			if (absorption >= remaining) {
				target.setAbsorptionAmount((float) (absorption - remaining));
				hurtFeedback(target);
				return;
			}
			target.setAbsorptionAmount(0.0F);
			remaining -= absorption;
		}

		double newHealth = Math.max(0.5D, target.getHealth() - remaining);
		if (target instanceof ServerPlayer victim && attacker != null) {
			victim.setLastHurtByPlayer(attacker);
		}
		target.lastDamageSource = target.level().damageSources().magic();
		target.setHealth((float) newHealth);
		hurtFeedback(target);
	}

	private static void hurtFeedback(LivingEntity target) {
		target.invulnerableTime = 10;
		target.hurtMarked = true;
		if (target.level() instanceof net.minecraft.server.level.ServerLevel level) {
			level.broadcastEntityEvent(target, (byte) 2);
			level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_HURT,
					SoundSource.PLAYERS, 1.0F, 1.0F);
		}
	}

	/**
	 * @return {@code true} when a totem was consumed and the damage must not be applied.
	 */
	private static boolean consumeTotemIfLethal(ServerPlayer player, double amount) {
		if (player.getAbsorptionAmount() + player.getHealth() > amount) {
			return false;
		}
		ItemStack offhand = player.getOffhandItem();
		ItemStack mainhand = player.getMainHandItem();
		if (offhand.is(Items.TOTEM_OF_UNDYING)) {
			offhand.shrink(1);
			applyTotemEffects(player);
			return true;
		}
		if (mainhand.is(Items.TOTEM_OF_UNDYING)) {
			mainhand.shrink(1);
			applyTotemEffects(player);
			return true;
		}
		return false;
	}

	/** Port of {@code BaseWeapon#applyTotemEffects} with its {@code totem.*} config. */
	public static void applyTotemEffects(ServerPlayer player) {
		AltarConfig config = AltarSMPMod.get().config();
		player.setHealth(1.0F);
		if (player.level() instanceof net.minecraft.server.level.ServerLevel level) {
			level.broadcastEntityEvent(player, (byte) 35);
		}
		int regenDuration = config.getInt("totem.regen_duration", 900);
		int regenLevel = config.getInt("totem.regen_level", 1);
		int absorptionDuration = config.getInt("totem.absorption_duration", 100);
		int absorptionLevel = config.getInt("totem.absorption_level", 1);
		int fireResistDuration = config.getInt("totem.fire_resist_duration", 800);
		int noDamageTicks = config.getInt("totem.no_damage_ticks", 20);
		Effects.apply(player, "REGENERATION", regenDuration, regenLevel);
		Effects.apply(player, "ABSORPTION", absorptionDuration, absorptionLevel);
		Effects.apply(player, "FIRE_RESISTANCE", fireResistDuration, 0);
		player.invulnerableTime = noDamageTicks;
	}
}
