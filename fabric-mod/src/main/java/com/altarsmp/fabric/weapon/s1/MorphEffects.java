package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.AbilityTracker;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TickScheduler;

/**
 * The morph attribute/effect table of {@code WandOfIllusionWeapon#applyMobEffects}
 * plus every passive morph task the plugin started
 * ({@code startEndermanWaterDamage}, {@code startWardenDarknessAura},
 * {@code startSpiderWallClimb}, {@code startWolfTracking},
 * {@code startDrownedWaterSpeed}, {@code startFishWaterSpeed},
 * {@code startMorphFlight}, {@code startZombieConfusion},
 * {@code startVexWallPhase}, {@code startWaterDamage}, {@code startLandDamage},
 * {@code startPhantomDaylightBurn}).
 *
 * <p>Health is set through the max-health base value, size through the scale
 * attribute, and the "always on" potion effects the original applied with
 * {@code Integer.MAX_VALUE} duration are applied as infinite (-1) instances so
 * they never expire mid-morph. Every passive task re-checks
 * {@link WandOfIllusionWeapon#isDisguised(ServerPlayer)} and stops by itself.</p>
 */
public final class MorphEffects {

	/** Infinite duration, replacing Bukkit's {@code Integer.MAX_VALUE}. */
	static final int FOREVER = -1;

	private final AltarSMPMod mod;
	private final WandOfIllusionWeapon wand;

	public MorphEffects(AltarSMPMod mod, WandOfIllusionWeapon wand) {
		this.mod = mod;
		this.wand = wand;
	}

	// ------------------------------------------------------------------ health

	/** {@code WandOfIllusionWeapon#morphHpFor} - config hearts, floored at 2 HP. */
	double morphHpFor(AbilityContext ctx, EntityType<?> type, double defaultHearts) {
		String key = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
		double hearts = ctx.cfgd("abilities.wandofillusion.morphs." + key + ".hearts", defaultHearts / 2.0D);
		return Math.max(2.0D, hearts * 2.0D);
	}

	/** {@code WandOfIllusionWeapon#setMorphHealth} - keeps the current health ratio. */
	void setMorphHealth(ServerPlayer player, double maxHealth) {
		AttributeInstance instance = player.getAttribute(Attributes.MAX_HEALTH);
		if (instance == null) {
			return;
		}
		double original = this.wand.originalMaxHealth(player);
		double ratio = original <= 0.0D ? 1.0D : player.getHealth() / original;
		instance.setBaseValue(maxHealth);
		player.setHealth((float) Math.min(maxHealth, maxHealth * ratio));
	}

	static void setScale(ServerPlayer player, double scale) {
		AttributeInstance instance = player.getAttribute(Attributes.SCALE);
		if (instance != null) {
			instance.setBaseValue(scale);
		}
	}

	// -------------------------------------------------------------------- table

	/**
	 * {@code WandOfIllusionWeapon#applyMobEffects}. Returns the passive tasks that
	 * must be cancelled when the morph ends.
	 */
	public List<TickScheduler.Task> apply(AbilityContext ctx, ServerPlayer player, EntityType<?> type,
			double capturedScale, boolean baby) {
		List<TickScheduler.Task> tasks = new ArrayList<>();
		AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
		AttributeInstance scale = player.getAttribute(Attributes.SCALE);
		this.wand.rememberOriginals(player, health == null ? 20.0D : health.getBaseValue(),
				scale == null ? 1.0D : scale.getBaseValue());
		setScale(player, capturedScale);

		String key = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
		switch (key) {
			case "husk" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 20.0D));
				infinite(player, "SLOWNESS", 1);
			}
			case "cave_spider", "spider" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 16.0D));
				infinite(player, "NIGHT_VISION", 0);
				infinite(player, "SPEED", 1);
				tasks.add(spiderWallClimb(player));
			}
			case "vindicator" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 20.0D));
				infinite(player, "SLOWNESS", 0);
			}
			case "hoglin" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 30.0D));
				infinite(player, "STRENGTH", 0);
			}
			case "zoglin" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 30.0D));
				infinite(player, "STRENGTH", 0);
				infinite(player, "FIRE_RESISTANCE", 0);
			}
			case "shulker" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 30.0D));
				infinite(player, "SLOWNESS", 1);
			}
			case "bee" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 10.0D));
				setScale(player, 0.6D);
				infinite(player, "SPEED", 1);
				enableFlight(player, 0.12F);
				tasks.add(morphFlight(player));
			}
			case "pufferfish" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 5.0D));
				setScale(player, 0.5D);
				infinite(player, "WATER_BREATHING", 0);
				tasks.add(fishWaterSpeed(player));
				tasks.add(landDamage(ctx, player, key));
			}
			case "endermite" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 8.0D));
				setScale(player, 0.4D);
				infinite(player, "SPEED", 1);
			}
			case "skeleton", "stray" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 12.0D));
				infinite(player, "SPEED", 0);
			}
			case "pillager" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 18.0D));
				infinite(player, "SPEED", 0);
			}
			case "enderman" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 25.0D));
				infinite(player, "SPEED", 1);
				tasks.add(endermanWaterDamage(player));
			}
			case "warden" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 30.0D));
				infinite(player, "SPEED", 0);
				tasks.add(wardenDarknessAura(player));
			}
			case "elder_guardian" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 40.0D));
				infinite(player, "WATER_BREATHING", 0);
				infinite(player, "SLOWNESS", 1);
				tasks.add(landDamage(ctx, player, key));
			}
			case "ender_dragon" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 50.0D));
				infinite(player, "STRENGTH", 0);
				infinite(player, "FIRE_RESISTANCE", 0);
				infinite(player, "SLOW_FALLING", 0);
				enableFlight(player, 0.1F);
				tasks.add(morphFlight(player));
			}
			case "blaze" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 14.0D));
				infinite(player, "FIRE_RESISTANCE", 0);
				this.wand.markBlazeMorph(player);
				enableFlight(player, 0.06F);
				tasks.add(morphFlight(player));
				tasks.add(waterDamage(ctx, player, key));
			}
			case "wither" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 45.0D));
				infinite(player, "STRENGTH", 0);
				infinite(player, "FIRE_RESISTANCE", 0);
				enableFlight(player, 0.1F);
				tasks.add(morphFlight(player));
			}
			case "zombie" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 20.0D));
				infinite(player, "SLOWNESS", 1);
				tasks.add(zombieConfusion(player));
			}
			case "drowned" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 14.0D));
				infinite(player, "WATER_BREATHING", 0);
				tasks.add(drownedWaterSpeed(player));
			}
			case "wither_skeleton" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 18.0D));
				infinite(player, "FIRE_RESISTANCE", 0);
				infinite(player, "SPEED", 0);
			}
			case "creeper" -> setMorphHealth(player, morphHpFor(ctx, type, 16.0D));
			case "iron_golem" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 40.0D));
				infinite(player, "SLOWNESS", 1);
			}
			case "wolf" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 16.0D));
				infinite(player, "SPEED", 1);
				tasks.add(wolfTracking(player));
			}
			case "ghast" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 10.0D));
				infinite(player, "FIRE_RESISTANCE", 0);
				enableFlight(player, 0.05F);
				tasks.add(morphFlight(player));
			}
			case "bat" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 6.0D));
				setScale(player, 0.5D);
				infinite(player, "NIGHT_VISION", 0);
				this.wand.markBatMorph(player);
				enableFlight(player, 0.1F);
				tasks.add(morphFlight(player));
			}
			case "witch" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 12.0D));
				infinite(player, "FIRE_RESISTANCE", 0);
			}
			case "piglin_brute" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 24.0D));
				infinite(player, "STRENGTH", 0);
			}
			case "phantom" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 10.0D));
				infinite(player, "NIGHT_VISION", 0);
				enableFlight(player, 0.12F);
				tasks.add(morphFlight(player));
				tasks.add(phantomDaylightBurn(player));
			}
			case "silverfish" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 8.0D));
				setScale(player, 0.4D);
				infinite(player, "SPEED", 2);
			}
			case "slime" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 16.0D));
				infinite(player, "JUMP_BOOST", 2);
				infinite(player, "RESISTANCE", 0);
			}
			case "magma_cube" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 16.0D));
				infinite(player, "JUMP_BOOST", 1);
				infinite(player, "FIRE_RESISTANCE", 0);
			}
			case "vex" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 7.0D));
				setScale(player, 0.5D);
				enableFlight(player, 0.08F);
				tasks.add(morphFlight(player));
				tasks.add(vexWallPhase(player));
			}
			case "ravager" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 25.0D));
				infinite(player, "STRENGTH", 0);
				infinite(player, "SLOWNESS", 0);
			}
			case "evoker" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 18.0D));
				infinite(player, "SPEED", 0);
			}
			case "guardian" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 20.0D));
				infinite(player, "WATER_BREATHING", 0);
				tasks.add(drownedWaterSpeed(player));
				tasks.add(landDamage(ctx, player, key));
			}
			case "piglin" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 16.0D));
				infinite(player, "SPEED", 0);
				infinite(player, "FIRE_RESISTANCE", 0);
			}
			case "strider" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 16.0D));
				infinite(player, "FIRE_RESISTANCE", 0);
			}
			case "zombified_piglin" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 20.0D));
				infinite(player, "FIRE_RESISTANCE", 0);
				infinite(player, "STRENGTH", 0);
			}
			case "horse", "donkey", "mule", "skeleton_horse", "zombie_horse" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 20.0D));
				infinite(player, "SPEED", 2);
				infinite(player, "JUMP_BOOST", 2);
				this.wand.markHorseMorph(player);
			}
			case "cod", "salmon", "tropical_fish" -> {
				setMorphHealth(player, morphHpFor(ctx, type, 3.0D));
				setScale(player, 0.4D);
				infinite(player, "WATER_BREATHING", 0);
				tasks.add(fishWaterSpeed(player));
				tasks.add(landDamage(ctx, player, key));
			}
			default -> AltarSMPMod.LOGGER.debug("[AltarSMP] morph '{}' has no attribute table entry", key);
		}

		if (baby) {
			AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
			if (maxHealth != null) {
				double reduced = maxHealth.getBaseValue() * 0.75D;
				maxHealth.setBaseValue(reduced);
				if (player.getHealth() > reduced) {
					player.setHealth((float) reduced);
				}
			}
		}
		return tasks;
	}

	static void infinite(ServerPlayer player, String bukkitEffect, int amplifier) {
		Effects.apply(player, bukkitEffect, FOREVER, amplifier, false, false, false);
	}

	/** Bukkit's {@code setAllowFlight/setFlying/setFlySpeed} triple. */
	static void enableFlight(ServerPlayer player, float flySpeed) {
		if (player.isCreative() || player.isSpectator()) {
			return;
		}
		player.getAbilities().mayfly = true;
		player.getAbilities().flying = true;
		player.getAbilities().setFlyingSpeed(flySpeed);
		player.onUpdateAbilities();
	}

	/** Restores survival flight state when a morph ends. */
	static void disableFlight(ServerPlayer player) {
		if (player.isCreative() || player.isSpectator()) {
			return;
		}
		player.getAbilities().mayfly = false;
		player.getAbilities().flying = false;
		player.getAbilities().setFlyingSpeed(0.05F);
		player.onUpdateAbilities();
	}

	// -------------------------------------------------------------- passives

	private static boolean morphActive(WandOfIllusionWeapon wand, ServerPlayer player) {
		return wand.isDisguised(player) && !player.isRemoved();
	}

	/** {@code startEndermanWaterDamage} - water hurts, rain hurts less. */
	private TickScheduler.Task endermanWaterDamage(ServerPlayer player) {
		return this.mod.scheduler().timer(() -> {
			if (!morphActive(this.wand, player)) {
				return;
			}
			if (player.isInWater()) {
				player.setHealth(Math.max(0.0F, player.getHealth() - 1.0F));
				Fx.sound(player.serverLevel(), player.position(), "ENTITY_ENDERMAN_HURT", 0.5F, 1.0F);
			} else if (player.level().isRainingAt(BlockPos.containing(player.position()))) {
				player.setHealth(Math.max(0.0F, player.getHealth() - 0.5F));
			}
		}, 0L, 20L);
	}

	/** {@code startWardenDarknessAura} - Darkness to everyone within 15 blocks. */
	private TickScheduler.Task wardenDarknessAura(ServerPlayer player) {
		return this.mod.scheduler().timer(() -> {
			if (!morphActive(this.wand, player)) {
				return;
			}
			ServerLevel level = player.serverLevel();
			for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
				if (other != player && other.level() == level
						&& other.position().distanceTo(player.position()) <= 15.0D) {
					Effects.apply(other, "DARKNESS", 100, 0, false, false, false);
				}
			}
		}, 0L, 120L);
	}

	/** {@code startSpiderWallClimb} - climbing a wall keeps you from falling. */
	private TickScheduler.Task spiderWallClimb(ServerPlayer player) {
		return this.mod.scheduler().timer(() -> {
			if (!morphActive(this.wand, player) || player.onGround()) {
				return;
			}
			BlockPos at = BlockPos.containing(player.position());
			ServerLevel level = player.serverLevel();
			boolean againstWall = !level.getBlockState(at.north()).getCollisionShape(level, at.north()).isEmpty()
					|| !level.getBlockState(at.south()).getCollisionShape(level, at.south()).isEmpty()
					|| !level.getBlockState(at.east()).getCollisionShape(level, at.east()).isEmpty()
					|| !level.getBlockState(at.west()).getCollisionShape(level, at.west()).isEmpty();
			if (againstWall && player.getDeltaMovement().y < 0.2D) {
				Motion.setVelocity(player, new Vec3(player.getDeltaMovement().x, 0.2D, player.getDeltaMovement().z));
				player.resetFallDistance();
			}
		}, 0L, 1L);
	}

	/** {@code startWolfTracking} - senses the closest player within 10 blocks. */
	private TickScheduler.Task wolfTracking(ServerPlayer player) {
		return this.mod.scheduler().timer(() -> {
			if (!morphActive(this.wand, player)) {
				return;
			}
			ServerPlayer closest = null;
			double best = Double.MAX_VALUE;
			for (ServerPlayer other : player.serverLevel().getServer().getPlayerList().getPlayers()) {
				if (other == player || other.level() != player.level()) {
					continue;
				}
				double distance = other.position().distanceTo(player.position());
				if (distance <= 10.0D && distance < best) {
					best = distance;
					closest = other;
				}
			}
			if (closest != null) {
				Messaging.send(player, "<gray><italic>You sense " + closest.getGameProfile().getName() + " nearby...</italic>");
			}
		}, 0L, 20L);
	}

	/** {@code startDrownedWaterSpeed}. */
	private TickScheduler.Task drownedWaterSpeed(ServerPlayer player) {
		return this.mod.scheduler().timer(() -> {
			if (morphActive(this.wand, player) && player.isInWater()) {
				Effects.apply(player, "SPEED", 40, 1, false, false, false);
				Effects.apply(player, "DOLPHINS_GRACE", 40, 0, false, false, false);
			}
		}, 0L, 20L);
	}

	/** {@code startFishWaterSpeed}. */
	private TickScheduler.Task fishWaterSpeed(ServerPlayer player) {
		return this.mod.scheduler().timer(() -> {
			if (morphActive(this.wand, player) && player.isInWater()) {
				Effects.apply(player, "SPEED", 40, 3, false, false, false);
				Effects.apply(player, "DOLPHINS_GRACE", 40, 1, false, false, false);
			}
		}, 0L, 20L);
	}

	/** {@code startMorphFlight} - keeps survival flight on while airborne. */
	private TickScheduler.Task morphFlight(ServerPlayer player) {
		AbilityTracker.setMorphFlight(player, true);
		return this.mod.scheduler().timer(() -> {
			if (!morphActive(this.wand, player)) {
				return;
			}
			if (player.isCreative() || player.isSpectator()) {
				return;
			}
			player.getAbilities().mayfly = true;
			if (!player.getAbilities().flying && !player.onGround()) {
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			}
		}, 1L, 1L);
	}

	/** {@code startZombieConfusion} - 10% chance per second of nausea. */
	private TickScheduler.Task zombieConfusion(ServerPlayer player) {
		return this.mod.scheduler().timer(() -> {
			if (morphActive(this.wand, player) && this.wand.randomChance(10)) {
				Effects.apply(player, "NAUSEA", 60, 0, false, false, false);
			}
		}, 0L, 20L);
	}

	/** {@code startVexWallPhase} - stuck in a block? phase forward up to 3 blocks. */
	private TickScheduler.Task vexWallPhase(ServerPlayer player) {
		return this.mod.scheduler().timer(() -> {
			if (!morphActive(this.wand, player)) {
				return;
			}
			ServerLevel level = player.serverLevel();
			BlockPos at = BlockPos.containing(player.position());
			if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
				return;
			}
			Vec3 direction = player.getLookAngle().normalize();
			for (int step = 1; step <= 3; step++) {
				Vec3 candidate = player.position().add(direction.scale(step));
				BlockPos pos = BlockPos.containing(candidate);
				if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
						&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
					Motion.teleport(player, candidate);
					Fx.simple(level, "PORTAL", candidate, 10, 0.3D, 0.5D, 0.3D, 0.1D);
					return;
				}
			}
		}, 0L, 5L);
	}

	/** {@code startWaterDamage} - blaze-style morphs burn in water. */
	private TickScheduler.Task waterDamage(AbilityContext ctx, ServerPlayer player, String mobKey) {
		String prefix = "abilities.wandofillusion.morphs." + mobKey;
		if (!ctx.cfgb(prefix + ".water_damage_enabled", true)) {
			return null;
		}
		double damage = ctx.cfgd(prefix + ".water_damage", 1.0D);
		return this.mod.scheduler().timer(() -> {
			if (!morphActive(this.wand, player)) {
				return;
			}
			if (player.isInWater() || player.isInWaterOrRain()) {
				player.setHealth(Math.max(0.0F, player.getHealth() - (float) damage));
				Fx.simple(player.serverLevel(), "SMOKE", player.position().add(0.0D, 1.0D, 0.0D),
						6, 0.3D, 0.5D, 0.3D, 0.02D);
			}
		}, 20L, 20L);
	}

	/** {@code startLandDamage} - fish/guardian morphs suffocate on land. */
	private TickScheduler.Task landDamage(AbilityContext ctx, ServerPlayer player, String mobKey) {
		String prefix = "abilities.wandofillusion.morphs." + mobKey;
		if (!ctx.cfgb(prefix + ".land_damage_enabled", true)) {
			return null;
		}
		double damage = ctx.cfgd(prefix + ".land_damage", 1.0D);
		return this.mod.scheduler().timer(() -> {
			if (!morphActive(this.wand, player)) {
				return;
			}
			if (!player.isInWater()) {
				player.setHealth(Math.max(0.0F, player.getHealth() - (float) damage));
				Fx.simple(player.serverLevel(), "SPLASH", player.position().add(0.0D, 1.0D, 0.0D),
						8, 0.3D, 0.3D, 0.3D, 0.0D);
			}
		}, 20L, 20L);
	}

	/** {@code startPhantomDaylightBurn} - daylight with sky access sets you alight. */
	private TickScheduler.Task phantomDaylightBurn(ServerPlayer player) {
		return this.mod.scheduler().timer(() -> {
			if (!morphActive(this.wand, player)) {
				return;
			}
			ServerLevel level = player.serverLevel();
			long time = level.getDayTime() % 24000L;
			boolean day = time < 12300L || time > 23850L;
			BlockPos at = BlockPos.containing(player.position());
			if (day && level.getMaxLocalRawBrightness(at) > 10 && level.canSeeSky(at)) {
				player.setRemainingFireTicks(40);
			}
		}, 0L, 20L);
	}

	// -------------------------------------------------------------- resolution

	/** Resolves a stored Bukkit-style entity name to a vanilla {@link EntityType}. */
	public static EntityType<?> typeOf(String stored) {
		if (stored == null || stored.isBlank() || "PLAYER".equalsIgnoreCase(stored)) {
			return null;
		}
		Identifier id = Identifier.fromNamespaceAndPath("minecraft", stored.toLowerCase(java.util.Locale.ROOT));
		return BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
	}

	/** {@code WandOfIllusionWeapon#formatMobName}. */
	public static String formatMobName(EntityType<?> type) {
		String raw = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath().replace('_', ' ');
		StringBuilder name = new StringBuilder();
		boolean upper = true;
		for (char character : raw.toCharArray()) {
			if (character == ' ') {
				name.append(' ');
				upper = true;
			} else {
				name.append(upper ? Character.toUpperCase(character) : character);
				upper = false;
			}
		}
		return name.toString();
	}

	/** Nearest non-player living entity within 20 blocks, used by the evoker summon. */
	static LivingEntity nearestMobTarget(ServerPlayer player, Entity origin) {
		LivingEntity best = null;
		double distance = Double.MAX_VALUE;
		for (LivingEntity candidate : origin.level().getEntitiesOfClass(LivingEntity.class,
				origin.getBoundingBox().inflate(20.0D), e -> e != player && e.isAlive() && !(e instanceof ServerPlayer))) {
			double here = candidate.position().distanceTo(origin.position());
			if (here < distance) {
				distance = here;
				best = candidate;
			}
		}
		return best;
	}

	/** Creates and spawns an entity of {@code type}, or {@code null} when unavailable. */
	static Entity spawn(ServerLevel level, EntityType<?> type, Vec3 at) {
		Entity entity = type.create(level);
		if (entity == null) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] could not create entity {} for the Wand of Illusion", type);
			return null;
		}
		entity.snapTo(at.x, at.y, at.z);
		level.addFreshEntity(entity);
		return entity;
	}
}
