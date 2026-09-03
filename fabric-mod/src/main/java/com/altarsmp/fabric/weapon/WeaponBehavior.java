package com.altarsmp.fabric.weapon;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.altarsmp.fabric.ability.AbilityContext;

/**
 * One legendary weapon's behaviour.
 *
 * <p>The original had one {@code BaseWeapon} subclass per weapon that registered
 * itself as a Bukkit {@code Listener} and a {@code CommandExecutor}; the port
 * keeps the one-class-per-weapon structure but routes events through
 * {@link com.altarsmp.fabric.ability.AbilityBus} instead of the Bukkit event bus,
 * and exposes the admin commands through Brigadier.</p>
 *
 * <p>Activation model, unchanged from the plugin:</p>
 * <ul>
 *   <li>{@link #onPrimary} - press <b>F</b> (swap hands) while holding the weapon;</li>
 *   <li>{@link #onSecondary} - press <b>Shift + F</b>;</li>
 *   <li>{@link #onSneakToggle} - weapons that react to sneak transitions;</li>
 *   <li>everything else is passive ({@link #onAttack}, {@link #onKill},
 *       {@link #onDamaged}, {@link #onTick}, ...).</li>
 * </ul>
 */
public interface WeaponBehavior {

	/** Canonical content id, e.g. {@code paladinbattleaxe}. */
	String id();

	/** 1 for Season 1 content, 2 for the AltarSMPS2 module. */
	default int season() {
		return 1;
	}

	/** Display name used by commands and holograms. */
	String displayName();

	default ItemStack create(@Nullable ServerPlayer owner) {
		return com.altarsmp.fabric.item.ItemFactory.weapon(id(), owner);
	}

	// ------------------------------------------------------------- activation

	default void onPrimary(AbilityContext ctx) {
	}

	default void onSecondary(AbilityContext ctx) {
	}

	default void onSneakToggle(AbilityContext ctx, boolean sneaking) {
	}

	/** Right-click while holding the weapon. Return {@code true} to consume the use. */
	default boolean onUse(AbilityContext ctx, InteractionHand hand) {
		return false;
	}

	/**
	 * Right-click while pointing at a block. Return {@code true} to consume the
	 * interaction (used by the silverfish morph, altar-like tools, ...).
	 */
	default boolean onUseBlock(AbilityContext ctx, BlockPos pos, net.minecraft.core.Direction direction) {
		return false;
	}

	/** Left-click on a block. Return {@code true} to cancel the swing. */
	default boolean onAttackBlock(AbilityContext ctx, BlockPos pos) {
		return false;
	}

	// ---------------------------------------------------------------- passive

	default void onEquip(AbilityContext ctx) {
	}

	default void onUnequip(AbilityContext ctx) {
	}

	default void onTick(AbilityContext ctx) {
	}

	// ----------------------------------------------------------------- combat

	/**
	 * Attacker-side hook that runs <em>before</em> {@link #onAttack} and may cancel
	 * the hit entirely - Bukkit's {@code EntityDamageByEntityEvent#setCancelled(true)}
	 * from a HIGH-priority listener. Only the Bow of Deception needs it: an imposter
	 * swinging a bow that is not bound to them has the damage cancelled and is
	 * punished instead.
	 *
	 * @return {@code true} to cancel the damage
	 */
	default boolean interceptAttack(AbilityContext ctx, LivingEntity target, float damage) {
		return false;
	}

	default void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
	}

	/**
	 * Arm swing that did not necessarily hit anything (Bukkit's
	 * {@code PlayerAnimationEvent}). Dragonrend's Hypersonic Slash triggers on the
	 * swing itself, so this is fed from the swing packet hook in
	 * {@code mixin.SwingPacketMixin}.
	 */
	default void onArmSwing(AbilityContext ctx) {
	}

	default void onKill(AbilityContext ctx, LivingEntity victim) {
	}

	/** @return {@code true} to cancel the incoming damage entirely (a parry). */
	default boolean onDamaged(AbilityContext ctx, DamageSource source, float amount, @Nullable LivingEntity attacker) {
		return false;
	}

	/** Bow/crossbow/trident projectiles owned by this player. */
	default void onProjectileHit(AbilityContext ctx, Entity projectile, @Nullable Entity hit) {
	}

	default void onProjectileLaunch(AbilityContext ctx, Entity projectile) {
	}

	default void onBlockBreak(AbilityContext ctx, BlockPos pos, BlockState state) {
	}

	// ------------------------------------------------- global interception hooks
	//
	// Almost every hook above is dispatched to the weapon the player is holding.
	// A few original behaviours reacted to something else entirely - Omen's Vault
	// forbidden circles suppress <em>wind charges</em>, an item the player is not
	// holding an Omen for - so these two are offered to every behaviour.

	/**
	 * Offered for every item use, whatever is held.
	 *
	 * @return {@code true} to cancel the use (Bukkit {@code PlayerInteractEvent#setCancelled})
	 */
	default boolean interceptItemUse(net.minecraft.server.level.ServerLevel level, ServerPlayer player,
			InteractionHand hand, ItemStack used) {
		return false;
	}

	/**
	 * Offered for every projectile a player launches, whatever fired it.
	 *
	 * @return {@code true} to suppress the launch (Bukkit
	 *         {@code ProjectileLaunchEvent#setCancelled}); the caller discards the
	 *         projectile so nothing is left flying.
	 */
	default boolean interceptProjectileLaunch(net.minecraft.server.level.ServerLevel level, ServerPlayer shooter,
			Entity projectile) {
		return false;
	}

	// ------------------------------------------------------------ housekeeping

	default void onPlayerQuit(ServerPlayer player) {
	}

	/**
	 * Config paths this weapon exposes in {@code /legendaryconfig} (the plugin
	 * returned {@code List<c>} of editable fields).
	 */
	default List<String> configFields() {
		return List.of();
	}
}
