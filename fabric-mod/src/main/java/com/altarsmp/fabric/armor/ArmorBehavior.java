package com.altarsmp.fabric.armor;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import com.altarsmp.fabric.item.ItemFactory;

/**
 * Behaviour of a piece of AltarSMP armour (the copper trial rewards). The
 * original implemented these as {@code CopperBoots}/{@code CopperChestplate}/...
 * classes holding a Bukkit listener; the port keeps one class per piece.
 */
public interface ArmorBehavior {

	String id();

	EquipmentSlot slot();

	default ItemStack create() {
		return ItemFactory.armor(id());
	}

	/** Passive, called every tick while worn. */
	default void onWorn(ServerPlayer player, ItemStack stack) {
	}

	default void onEquip(ServerPlayer player, ItemStack stack) {
	}

	default void onUnequip(ServerPlayer player, ItemStack stack) {
	}

	default void onAttack(ServerPlayer player, ItemStack stack, LivingEntity target, float damage) {
	}

	/** @return {@code true} to cancel the incoming damage entirely. */
	default boolean onDamaged(ServerPlayer player, ItemStack stack, DamageSource source, float amount, @Nullable LivingEntity attacker) {
		return false;
	}

	default void onKill(ServerPlayer player, ItemStack stack, LivingEntity victim) {
	}
}
