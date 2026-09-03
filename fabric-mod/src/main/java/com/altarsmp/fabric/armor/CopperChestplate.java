package com.altarsmp.fabric.armor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * Copper Chestplate - port of {@code com.altarsmp.armor.CopperChestplate}.
 *
 * <p><b>Infinite Resistance</b> (every 20 ticks while worn): Resistance
 * {@code copper-armor.chestplate.resistance_level} for 60 ticks, so the effect
 * never lapses.
 *
 * <p><b>Static Shock</b>: every melee hit that lands with at least 95% of the
 * attack-recharge meter filled is counted, and every
 * {@code copper-armor.chestplate.hits_required} (7) hits the counter resets and a
 * thorns ring fires:
 *
 * <ul>
 *   <li>every creeper inside {@code thorns_radius} (15) becomes charged and throws
 *       15 electric sparks;</li>
 *   <li>every other living entity inside that radius - unless it is a player who is
 *       blocking with a shield - loses {@code thorns_damage} (5) health directly,
 *       plays the hurt animation and the player hurt sound;</li>
 *   <li>{@code lightning_ring_strikes} (8) purely visual lightning bolts are struck
 *       evenly around a {@code lightning_ring_radius} (5) circle at the wearer's
 *       height, followed by one lightning impact sound.</li>
 * </ul>
 *
 * <p>Health is taken with {@code setHealth}, as upstream: no armour, no totem, no
 * damage event and therefore no death-message attribution. The lore also advertises
 * "Lightning Immunity", which no code in the original implements - the port keeps
 * the line in the item text and adds nothing behind it, rather than inventing a
 * mechanic the plugin never had.
 */
public final class CopperChestplate implements ArmorBehavior {

	/** {@code startResistanceTask}'s {@code runTaskTimer(plugin, 20L, 20L)}. */
	private static final int PASSIVE_INTERVAL = 20;
	/** Bukkit's {@code !(getAttackCooldown() < 0.95)} - a fully charged swing. */
	private static final float REQUIRED_ATTACK_CHARGE = 0.95F;
	/** {@code playHurtAnimation(0.0F)} runs the hurt animation for 10 ticks. */
	private static final int HURT_ANIMATION_TICKS = 10;

	private final AltarSMPMod mod;

	/** Bukkit's {@code Map<UUID, Integer> j} - Static Shock hit counter. */
	private final Map<UUID, Integer> hits = new HashMap<>();

	public CopperChestplate(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "copper_chestplate";
	}

	@Override
	public EquipmentSlot slot() {
		return EquipmentSlot.CHEST;
	}

	@Override
	public Set<String> aliasIds() {
		return Set.of("copper_diamond_chestplate");
	}

	/** {@code CopperChestplate#onCommand} - the body of {@code /copperchestplate}. */
	public boolean give(ServerPlayer player) {
		ItemStack chestplate = create();
		if (!player.getInventory().add(chestplate)) {
			player.drop(chestplate, false);
		}
		Messaging.send(player, "<green>You received Copper Chestplate!");
		Fx.soundTo(player, "ENTITY_LIGHTNING_BOLT_THUNDER", 1.0F, 1.5F);
		return true;
	}

	// ------------------------------------------------------------------ worn

	@Override
	public void onWorn(ServerPlayer player, ItemStack stack) {
		if (player.tickCount % PASSIVE_INTERVAL == 0) {
			int resistanceLevel = this.mod.config().getInt("copper-armor.chestplate.resistance_level", 0);
			Effects.apply(player, "RESISTANCE", 60, resistanceLevel, false, false, true);
		}
	}

	/** {@code CopperChestplate#onWearerAttacks} + {@code #incrementAndCheckThorns}. */
	@Override
	public void onAttack(ServerPlayer player, ItemStack stack, LivingEntity target, float damage) {
		if (player.getAttackStrengthScale(0.0F) < REQUIRED_ATTACK_CHARGE) {
			return;
		}
		UUID uuid = player.getUUID();
		int count = this.hits.merge(uuid, 1, Integer::sum);
		int required = this.mod.config().getInt("copper-armor.chestplate.hits_required", 7);
		if (count >= required) {
			this.hits.put(uuid, 0);
			activateThorns(player);
		}
	}

	/** {@code CopperChestplate#activateThorns}. */
	private void activateThorns(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 at = player.position();
		AltarConfig config = this.mod.config();
		double thornsRadius = config.getDouble("copper-armor.chestplate.thorns_radius", 15.0D);
		double thornsDamage = config.getDouble("copper-armor.chestplate.thorns_damage", 5.0D);
		double ringRadius = config.getDouble("copper-armor.chestplate.lightning_ring_radius", 5.0D);
		int strikes = config.getInt("copper-armor.chestplate.lightning_ring_strikes", 8);

		AABB box = new AABB(at.x - thornsRadius, at.y - thornsRadius, at.z - thornsRadius,
				at.x + thornsRadius, at.y + thornsRadius, at.z + thornsRadius);
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
			if (entity.equals(player)) {
				continue;
			}
			if (entity instanceof Creeper creeper) {
				power(creeper);
				Fx.simple(level, "ELECTRIC_SPARK", creeper.position().add(0.0D, 1.0D, 0.0D),
						15, 0.3D, 0.5D, 0.3D, 0.1D);
				continue;
			}
			if (entity instanceof ServerPlayer blocking && blocking.isBlocking()) {
				continue;
			}
			entity.setHealth((float) Math.max(0.0D, entity.getHealth() - thornsDamage));
			entity.hurtDuration = HURT_ANIMATION_TICKS;
			entity.hurtTime = entity.hurtDuration;
			Fx.sound(level, entity.position(), "ENTITY_PLAYER_HURT", 1.0F, 1.0F);
		}

		// Bukkit's strikeLightningEffect: the bolt is visual only, it neither sets
		// fire to anything nor damages it.
		for (int i = 0; i < strikes; i++) {
			double angle = Math.PI * 2.0D * i / strikes;
			Vec3 bolt = new Vec3(at.x + ringRadius * Math.cos(angle), at.y, at.z + ringRadius * Math.sin(angle));
			Fx.lightning(level, bolt, true);
		}
		Fx.sound(level, at, "ENTITY_LIGHTNING_BOLT_IMPACT", 1.0F, 0.8F);
	}

	/**
	 * Bukkit's {@code Creeper#setPowered(true)}. Vanilla 26.x only powers a creeper
	 * through {@code thunderHit}, which also sets it on fire, so the synched data
	 * field the getter reads is widened instead and set directly.
	 */
	private static void power(Creeper creeper) {
		creeper.getEntityData().set(Creeper.DATA_IS_POWERED, true);
	}
}
