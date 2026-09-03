package com.altarsmp.fabric.armor;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * Copper Boots - port of {@code com.altarsmp.armor.CopperBoots}.
 *
 * <p><b>Blazing Trail</b> (passive, every 2 ticks): a moving wearer leaves three
 * flame and two soul-fire-flame particles at their feet.
 *
 * <p><b>Static Shock</b> (passive, every 20 ticks): Speed {@code speed_level} for
 * 40 ticks, Fire Resistance while {@code fire_resistance} is on, and - when the
 * block under the wearer is any copper block - Speed
 * {@code copper_block_speed_level} instead, which is the "run faster on copper"
 * half of the lore.
 *
 * <p>The original ran both as server-wide {@code BukkitRunnable}s that looped over
 * every online player; the port drives them from the per-tick armour dispatch and
 * keeps the same 20/2 tick cadence, so the buffs and the trail land on the same
 * rhythm without a second scheduler. Both the netherite {@code copper_boots} and
 * the diamond trial variant {@code copper_diamond_boots} count as worn, exactly as
 * {@code isWearingArmor} accepted either PDC value.
 */
public final class CopperBoots implements ArmorBehavior {

	/** {@code startPassiveEffects}'s {@code runTaskTimer(plugin, 20L, 20L)}. */
	private static final int PASSIVE_INTERVAL = 20;
	/** {@code startFireTrail}'s {@code runTaskTimer(plugin, 0L, 2L)}. */
	private static final int TRAIL_INTERVAL = 2;
	/** Bukkit's {@code getVelocity().lengthSquared() > 0.01}. */
	private static final double TRAIL_MIN_SPEED_SQUARED = 0.01D;

	private final AltarSMPMod mod;

	public CopperBoots(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "copper_boots";
	}

	@Override
	public EquipmentSlot slot() {
		return EquipmentSlot.FEET;
	}

	@Override
	public Set<String> aliasIds() {
		return Set.of("copper_diamond_boots");
	}

	@Override
	public void onWorn(ServerPlayer player, ItemStack stack) {
		int tick = player.tickCount;
		if (tick % PASSIVE_INTERVAL == 0) {
			applyPassives(player);
		}
		if (tick % TRAIL_INTERVAL == 0) {
			blazingTrail(player);
		}
	}

	/** {@code CopperBoots#startPassiveEffects}. */
	private void applyPassives(ServerPlayer player) {
		AltarConfig config = this.mod.config();
		int speedLevel = config.getInt("copper-armor.boots.speed_level", 2);
		Effects.apply(player, "SPEED", 40, speedLevel, false, false, true);

		if (config.getBoolean("copper-armor.boots.fire_resistance", true)) {
			Effects.apply(player, "FIRE_RESISTANCE", 40, 0, false, false, true);
		}
		if (standingOnCopper(player)) {
			int copperSpeedLevel = config.getInt("copper-armor.boots.copper_block_speed_level", 3);
			Effects.apply(player, "SPEED", 40, copperSpeedLevel, false, false, true);
		}
	}

	/** {@code CopperBoots#isStandingOnCopper} - Bukkit matched {@code Material#name()}. */
	private boolean standingOnCopper(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		BlockPos below = player.blockPosition().below();
		BlockState state = level.getBlockState(below);
		String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
		return id.toUpperCase(java.util.Locale.ROOT).contains("COPPER");
	}

	/** {@code CopperBoots#startFireTrail}. */
	private void blazingTrail(ServerPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		if (velocity.lengthSqr() <= TRAIL_MIN_SPEED_SQUARED) {
			return;
		}
		ServerLevel level = player.serverLevel();
		Vec3 at = player.position().add(0.0D, 0.1D, 0.0D);
		Fx.simple(level, "FLAME", at, 3, 0.1D, 0.05D, 0.1D, 0.02D);
		Fx.simple(level, "SOUL_FIRE_FLAME", at, 2, 0.08D, 0.05D, 0.08D, 0.01D);
	}

	/** {@code CopperBoots#onCommand} - the body of {@code /copperboots}. */
	public boolean give(ServerPlayer player) {
		ItemStack boots = create();
		if (!player.getInventory().add(boots)) {
			player.drop(boots, false);
		}
		Messaging.send(player, "<green>You received Copper Boots!");
		Fx.soundTo(player, "ENTITY_BLAZE_AMBIENT", 1.0F, 1.5F);
		return true;
	}

	@Override
	public ItemStack create() {
		return ItemFactory.armor(id());
	}

	@Override
	public void onAttack(ServerPlayer player, ItemStack stack, LivingEntity target, float damage) {
		// The boots have no on-hit behaviour; the "Static Shock" line in their lore
		// describes the copper-block speed passive above, as it did in the original.
	}
}
