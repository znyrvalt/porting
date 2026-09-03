package com.altarsmp.fabric.armor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * Copper Leggings - port of {@code com.altarsmp.armor.CopperLeggings}.
 *
 * <p><b>Shockwave</b>: the leggings remember how far the wearer has fallen and, on
 * landing, cancel the fall damage entirely and fire a mace-style strike instead.
 * Damage is {@code shockwave_base_damage} (2.0 = one heart) plus
 * {@code shockwave_scaling_damage} (1.0) for every
 * {@code shockwave_scaling_interval} (5) blocks fallen beyond
 * {@code min_fall_distance} (5), so 5 blocks is one heart, 10 blocks 1.5, 15 blocks
 * two - exactly the ladder the lore describes. Anything within
 * {@code shockwave_radius} (5) of the landing point takes it, absorption first and
 * then health, with crit and orange dust on every victim, unless it is a player
 * blocking with a shield. A player who would die from it and is holding a Totem of
 * Undying in either hand has the totem consumed and gets the full totem pop
 * instead. The landing itself throws an explosion emitter, explosions, two dust
 * colours, crits and smoke plus an explosion, a heavy mace smash and an anvil land.
 *
 * <p>Landing in water or lava wipes the recorded fall, and a 5-tick lockout stops
 * one fall from firing twice (both the move check and the fall-damage event could
 * otherwise trigger it).
 *
 * <p>Dying while wearing the leggings replays a smaller explosion over the corpse
 * and replaces the death message with "{@code <victim> was caught in <killer>'s
 * explosion}" (or the killer-less variant), which the death pipeline picks up
 * through {@link #deathMessageMarkup}.
 */
public final class CopperLeggings implements ArmorBehavior {

	/** Ticks the strike is locked out for after firing (Bukkit's {@code Set<UUID> e}). */
	private static final long STRIKE_LOCKOUT_TICKS = 5L;
	/** {@code applyTotemPop}'s values, which the plugin hard-coded here. */
	private static final int TOTEM_FOOD_LEVEL = 20;
	private static final float TOTEM_SATURATION = 20.0F;
	private static final int CRIT_COLOUR = 0xFF8C00;
	private static final int ORANGE_DUST = 0xFFA500;
	private static final int COPPER_DUST = 0xB87333;

	private final AltarSMPMod mod;

	/** Bukkit's {@code Map<UUID, Double> d} - the highest fall distance seen so far. */
	private final Map<UUID, Double> fallDistances = new HashMap<>();
	/** Bukkit's {@code Set<UUID> e} - players inside the 5-tick strike lockout. */
	private final Set<UUID> strikeLockout = new HashSet<>();

	public CopperLeggings(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "copper_leggings";
	}

	@Override
	public EquipmentSlot slot() {
		return EquipmentSlot.LEGS;
	}

	@Override
	public Set<String> aliasIds() {
		return Set.of("copper_diamond_leggings");
	}

	/** {@code CopperLeggings#onCommand} - the body of {@code /copperleggings}. */
	public boolean give(ServerPlayer player) {
		ItemStack leggings = create();
		if (!player.getInventory().add(leggings)) {
			player.drop(leggings, false);
		}
		Messaging.send(player, "<green>You received Copper Leggings!");
		Fx.soundTo(player, "BLOCK_COPPER_STEP", 1.0F, 1.2F);
		return true;
	}

	// ---------------------------------------------------------------- falling

	/**
	 * {@code CopperLeggings#onPlayerMove} (MONITOR). Bukkit fired this on every
	 * movement; the armour dispatch runs every tick, which catches the same moments.
	 */
	@Override
	public void onWorn(ServerPlayer player, ItemStack stack) {
		UUID uuid = player.getUUID();
		double fall = player.fallDistance;
		if (fall > 0.0D) {
			this.fallDistances.put(uuid, fall);
		}

		ServerLevel level = player.serverLevel();
		net.minecraft.world.level.block.state.BlockState at = level.getBlockState(player.blockPosition());
		if (at.is(Blocks.WATER) || at.is(Blocks.LAVA)) {
			this.fallDistances.remove(uuid);
			return;
		}
		if (!player.onGround() || this.strikeLockout.contains(uuid)) {
			return;
		}
		Double recorded = this.fallDistances.get(uuid);
		int minFallDistance = this.mod.config().getInt("copper-armor.leggings.min_fall_distance", 5);
		if (recorded != null && recorded >= minFallDistance && fall == 0.0D) {
			fire(level, player, recorded, minFallDistance);
		}
	}

	/**
	 * {@code CopperLeggings#onFallDamage} (HIGH): the leggings eat the fall damage
	 * and turn it into the shockwave.
	 *
	 * @return {@code true} - the fall damage is always cancelled while worn
	 */
	@Override
	public boolean onDamaged(ServerPlayer player, ItemStack stack, DamageSource source, float amount,
			@Nullable LivingEntity attacker) {
		if (!source.is(DamageTypes.FALL)) {
			return false;
		}
		UUID uuid = player.getUUID();
		if (!this.strikeLockout.contains(uuid)) {
			Double recorded = this.fallDistances.remove(uuid);
			int minFallDistance = this.mod.config().getInt("copper-armor.leggings.min_fall_distance", 5);
			if (recorded != null && recorded >= minFallDistance) {
				fire(player.serverLevel(), player, recorded, minFallDistance);
			}
		}
		return true;
	}

	private void fire(ServerLevel level, ServerPlayer player, double fallDistance, int minFallDistance) {
		this.strikeLockout.add(player.getUUID());
		this.fallDistances.remove(player.getUUID());
		this.mod.scheduler().later(() -> this.strikeLockout.remove(player.getUUID()), STRIKE_LOCKOUT_TICKS);
		triggerMaceStrike(level, player, fallDistance, minFallDistance);
	}

	/** {@code CopperLeggings#triggerMaceStrike}. */
	private void triggerMaceStrike(ServerLevel level, ServerPlayer player, double fallDistance, int minFallDistance) {
		AltarConfig config = this.mod.config();
		double baseDamage = config.getDouble("copper-armor.leggings.shockwave_base_damage", 2.0D);
		int interval = Math.max(1, config.getInt("copper-armor.leggings.shockwave_scaling_interval", 5));
		double scalingDamage = config.getDouble("copper-armor.leggings.shockwave_scaling_damage", 1.0D);
		double radius = config.getInt("copper-armor.leggings.shockwave_radius", 5);
		double damage = baseDamage + Math.floor((fallDistance - minFallDistance) / interval) * scalingDamage;

		Vec3 at = player.position();
		AABB box = new AABB(at.x - radius, at.y - radius, at.z - radius,
				at.x + radius, at.y + radius, at.z + radius);
		int struck = 0;
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
			if (entity.equals(player) || entity.distanceTo(player) > radius) {
				continue;
			}
			if (entity instanceof ServerPlayer blocking && blocking.isBlocking()) {
				continue;
			}
			if (entity instanceof ServerPlayer victim && consumeTotemIfLethal(victim, damage)) {
				struck++;
				continue;
			}

			float absorption = entity.getAbsorptionAmount();
			if (absorption >= damage) {
				entity.setAbsorptionAmount((float) (absorption - damage));
			} else {
				entity.setAbsorptionAmount(0.0F);
				entity.setHealth((float) Math.max(0.0D, entity.getHealth() - (damage - absorption)));
			}
			struck++;
			Vec3 body = entity.position().add(0.0D, 1.0D, 0.0D);
			Fx.simple(level, "CRIT", body, 25, 0.4D, 0.5D, 0.4D, 0.3D);
			Fx.dust(level, body, CRIT_COLOUR, 1.5F, 15, 0.3D, 0.4D, 0.3D);
		}

		Fx.simple(level, "EXPLOSION_EMITTER", at, 2, 0.5D, 0.3D, 0.5D, 0.0D);
		Fx.simple(level, "EXPLOSION", at, 8, 2.0D, 0.5D, 2.0D, 0.0D);
		Fx.dust(level, at, ORANGE_DUST, 2.5F, 80, 3.0D, 1.5D, 3.0D);
		Fx.dust(level, at, COPPER_DUST, 2.0F, 50, 2.5D, 1.0D, 2.5D);
		Fx.simple(level, "CRIT", at, 40, 2.5D, 0.8D, 2.5D, 0.2D);
		Fx.simple(level, "SMOKE", at, 30, 2.0D, 0.5D, 2.0D, 0.05D);
		Fx.sound(level, at, "ENTITY_GENERIC_EXPLODE", 1.2F, 0.8F);
		Fx.sound(level, at, "ITEM_MACE_SMASH_GROUND_HEAVY", 1.5F, 0.7F);
		Fx.sound(level, at, "BLOCK_ANVIL_LAND", 0.8F, 0.5F);
		// Upstream kept `if (struck > 0) { }` empty; nothing was announced.
	}

	/** The leggings' own totem rule: only a lethal hit consumes the totem. */
	private boolean consumeTotemIfLethal(ServerPlayer victim, double damage) {
		if (victim.getAbsorptionAmount() + victim.getHealth() > damage) {
			return false;
		}
		ItemStack offhand = victim.getOffhandItem();
		ItemStack mainhand = victim.getMainHandItem();
		if (offhand.is(Items.TOTEM_OF_UNDYING)) {
			offhand.shrink(1);
		} else if (mainhand.is(Items.TOTEM_OF_UNDYING)) {
			mainhand.shrink(1);
		} else {
			return false;
		}
		applyTotemPop(victim);
		return true;
	}

	/** {@code CopperLeggings#applyTotemPop}. */
	private void applyTotemPop(ServerPlayer player) {
		TrueDamage.applyTotemEffects(player);
		player.getFoodData().setFoodLevel(TOTEM_FOOD_LEVEL);
		// saturationLevel is private with no setter, hence the access widener entry.
		FoodData food = player.getFoodData();
		food.saturationLevel = TOTEM_SATURATION;
		player.removeAllEffects();
		Effects.apply(player, "REGENERATION", 900, 1);
		Effects.apply(player, "ABSORPTION", 100, 1);
		Effects.apply(player, "FIRE_RESISTANCE", 800, 0);
		Fx.sound(player.serverLevel(), player.position(), "ITEM_TOTEM_USE", 1.0F, 1.0F);
	}

	// ------------------------------------------------------------------ death

	/** {@code CopperLeggings#onPlayerDeath} - the explosion over the corpse. */
	@Override
	public void onDeath(ServerPlayer player, DamageSource source, @Nullable ServerPlayer killer) {
		ServerLevel level = player.serverLevel();
		Vec3 at = player.position();
		Fx.simple(level, "EXPLOSION_EMITTER", at, 3, 0.5D, 0.3D, 0.5D, 0.0D);
		Fx.simple(level, "EXPLOSION", at, 10, 2.0D, 1.0D, 2.0D, 0.0D);
		Fx.simple(level, "SMOKE", at, 30, 2.0D, 1.0D, 2.0D, 0.05D);
		Fx.dust(level, at, CRIT_COLOUR, 2.0F, 50, 2.0D, 1.0D, 2.0D);
		Fx.sound(level, at, "ENTITY_GENERIC_EXPLODE", 1.5F, 0.7F);
	}

	/** {@code PlayerDeathEvent#deathMessage} from {@code CopperLeggings#onPlayerDeath}. */
	@Override
	@Nullable
	public String deathMessageMarkup(ServerPlayer player, @Nullable ServerPlayer killer) {
		String victim = player.getGameProfile().getName();
		if (killer != null) {
			return "<white>" + victim + " <gray>was caught in <white>" + killer.getGameProfile().getName()
					+ "'s <gray>explosion";
		}
		return "<white>" + victim + " <gray>was caught in an explosion";
	}
}
