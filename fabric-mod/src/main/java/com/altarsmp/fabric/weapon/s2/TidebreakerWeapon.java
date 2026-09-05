package com.altarsmp.fabric.weapon.s2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mojang.math.Transformation;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Tidebreaker (Season 2) - port of {@code com.altarsmps2.weapons.TidebreakerWeapon}
 * and {@code com.altarsmps2.abilities.TidebreakerAbilities}.
 *
 * <p><b>Passive</b>: while the trident is held, the owner breathes water
 * ({@code passive.water_breathing_amplifier}) and every dolphin, squid, glow squid
 * or nautilus within {@code passive.nautilus_buff_radius} blocks gets Resistance
 * {@code passive.nautilus_resistance_amplifier} and Speed
 * {@code passive.nautilus_speed_amplifier}, refreshed every two seconds.</p>
 *
 * <p><b>Stormcall</b> (F, {@code stormcall.cooldown}s): hurls a glowing trident
 * model 1.2 blocks per tick for up to 20 blocks; where it lands a storm cloud
 * forms {@code stormcall.cloud_height} blocks up and, for
 * {@code stormcall.duration}s, crackles with a flickering electric ring. Every
 * {@code stormcall.hit_interval} ticks the {@code stormcall.max_targets_per_tick}
 * healthiest untrusted creatures within two blocks are struck by a curvy lightning
 * bolt for {@code stormcall.damage}; with nothing to strike the bolts fork into the
 * ground at random.</p>
 *
 * <p><b>Rising Tide</b> (Shift+F, {@code rising_tide.cooldown}s) reads the pitch:
 * looking up ({@literal <} -50&deg;) launches the wielder
 * {@code rising_tide.jet_height}/6 blocks into the air on a column of temporary
 * water (cobwebs around them are cleared) and, on landing, releases an expanding
 * water shockwave out to {@code rising_tide.shockwave_radius} blocks that damages
 * for {@code rising_tide.water_damage} and tosses everything hit upwards, then
 * drains itself. Looking level or down charges for a second - dragging nearby
 * creatures in - before sending a four-step staircase wave
 * {@code rising_tide.wave_distance} blocks ahead that damages once per creature and
 * pulls them back to the wielder ("muddied") at
 * {@code rising_tide.pull_velocity}.</p>
 *
 * <p>All temporary water honours the {@code abilities.terrain_destruction} master
 * switch and the weapon-protection build rules, exactly like the plugin's
 * {@code D.b(player, location)} check.</p>
 */
public final class TidebreakerWeapon implements WeaponBehavior {

	static final String KEY_STORM = "tide_storm";
	static final String KEY_RISE = "tide_rise";

	private static final double STRIKE_RADIUS = 2.0D;
	private static final double FLICKER_RING_RADIUS = 3.0D;
	private static final int FLICKER_POINTS = 24;
	private static final int FLICKER_LIT = 8;
	private static final int BOLT_SEGMENTS = 14;
	private static final int BOLT_SUBDIVISIONS = 5;
	private static final double TRIDENT_SPEED = 1.2D;
	private static final double TRIDENT_MAX_DISTANCE = 20.0D;
	private static final int WAVE_HEIGHT = 4;
	private static final int LANDING_STEPS = 8;
	private static final double LANDING_START_RADIUS = 1.5D;
	private static final double LANDING_KNOCKBACK_HORIZONTAL = 0.6D;
	private static final double LANDING_KNOCKBACK_VERTICAL = 1.1D;
	private static final double CHARGE_PULL = 0.22D;
	private static final int CHARGE_TICKS = 20;
	private static final int MUDDIED_TICKS = 20;
	private static final int MUDDIED_STOP_DISTANCE = 2;
	private static final int UP_JET_WATER_BLOCKS = 3;
	private static final int[] UP_JET_WATER_REVERT_TICKS = {6, 12, 24, 40};
	private static final float UP_JET_PITCH_THRESHOLD = -50.0F;

	private final AltarSMPMod mod;

	public TidebreakerWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "tidebreaker";
	}

	@Override
	public int season() {
		return 2;
	}

	@Override
	public String displayName() {
		return "Tidebreaker";
	}

	@Override
	public List<String> configFields() {
		return List.of("Passive Water Breathing Amplifier",
				"Passive Nautilus Buff Radius/Resistance Amplifier/Speed Amplifier",
				"Stormcall Cooldown/Duration/Damage/Hit Interval/Max Targets Per Tick/Cloud Height",
				"Rising Tide Cooldown/Jet Height/Shockwave Radius/Wave Distance/Water Damage/Pull Velocity");
	}

	// ------------------------------------------------------------------ passive

	/** {@code TidebreakerAbilities#tickPassives} (the plugin ran it every 40 ticks). */
	@Override
	public void onTick(AbilityContext ctx) {
		if (this.mod.scheduler().currentTick() % 40L != 0L) {
			return;
		}
		ServerPlayer player = ctx.player();
		if (!Identity.is(player.getMainHandItem(), id()) && !Identity.is(player.getOffhandItem(), id())) {
			return;
		}
		int waterAmplifier = ctx.cfg("abilities.tidebreaker.passive.water_breathing_amplifier", 0);
		int radius = ctx.cfg("abilities.tidebreaker.passive.nautilus_buff_radius", 8);
		int resistanceAmplifier = ctx.cfg("abilities.tidebreaker.passive.nautilus_resistance_amplifier", 2);
		int speedAmplifier = ctx.cfg("abilities.tidebreaker.passive.nautilus_speed_amplifier", 4);
		Effects.apply(player, "WATER_BREATHING", 80, waterAmplifier, true, false, false);
		for (Entity entity : ctx.level().getEntities(null, box(player.position(), radius))) {
			if (entity instanceof LivingEntity living && isNautilusType(living)) {
				Effects.apply(living, "RESISTANCE", 80, resistanceAmplifier, true, false, false);
				Effects.apply(living, "SPEED", 80, speedAmplifier, true, false, false);
			}
		}
	}

	/** {@code TidebreakerAbilities#isNautilusType}. */
	private static boolean isNautilusType(LivingEntity entity) {
		EntityType<?> type = entity.getType();
		if (type == EntityType.DOLPHIN || type == EntityType.SQUID || type == EntityType.GLOW_SQUID) {
			return true;
		}
		// The server also had a "NAUTILUS" creature type from another plugin; match
		// by registry id so a datapack/mod nautilus keeps its buff.
		return BuiltInIds.entityId(type).endsWith("nautilus");
	}

	// ------------------------------------------------------------- activation

	@Override
	public void onPrimary(AbilityContext ctx) {
		tryStormcall(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		tryRisingTide(ctx);
	}

	// --------------------------------------------------------------- stormcall

	/** {@code TidebreakerAbilities#tryStormcall}. */
	private void tryStormcall(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.tidebreaker.stormcall.cooldown", 40);
		if (!ctx.gate(KEY_STORM, "Stormcall", cooldown)) {
			return;
		}
		CooldownBars.show(player, KEY_STORM, "Stormcall", BossEvent.BossBarColor.BLUE, cooldown);

		int durationSeconds = ctx.cfg("abilities.tidebreaker.stormcall.duration", 10);
		double damage = ctx.cfgd("abilities.tidebreaker.stormcall.damage", 2.0D);
		int hitInterval = ctx.cfg("abilities.tidebreaker.stormcall.hit_interval", 15);
		int maxTargets = ctx.cfg("abilities.tidebreaker.stormcall.max_targets_per_tick", 2);
		int cloudHeight = ctx.cfg("abilities.tidebreaker.stormcall.cloud_height", 9);
		// abilities.tidebreaker.stormcall.radius is read by the plugin and then never
		// used - the strike search stays at the hard-coded 2 blocks it always was.

		ServerLevel level = ctx.level();
		Vec3 direction = player.getViewVector(1.0F);
		if (direction.lengthSqr() < 0.001D) {
			direction = new Vec3(0.0D, 0.0D, 1.0D);
		}
		direction = direction.normalize();
		Fx.sound(level, player.position(), "ITEM_TRIDENT_THROW", 1.4F, 1.0F);
		player.swing(InteractionHand.MAIN_HAND);

		Vec3 start = player.getEyePosition().add(direction.scale(0.6D));
		ItemStack trident = new ItemStack(Items.TRIDENT);
		ItemFactory.applyModelData(trident, 1);

		// The plugin oriented the thrown model with two chained quaternions.
		float yaw = (float) Math.atan2(-direction.x, direction.z);
		float pitch = (float) Math.asin(direction.y);
		Quaternionf aim = new Quaternionf(new AxisAngle4f(pitch, (float) Math.cos(yaw), 0.0F, (float) -Math.sin(yaw)));
		Quaternionf tilt = new Quaternionf(new AxisAngle4f((float) Math.toRadians(90.0D), 1.0F, 0.0F, 0.0F));
		Quaternionf leftRotation = aim.mul(tilt);
		Quaternionf rightRotation = new Quaternionf(new AxisAngle4f(yaw, 0.0F, 1.0F, 0.0F));

		Display.ItemDisplay display = Displays.model(level, start, trident);
		Displays.bright(display);
		display.setTransformation(new Transformation(new Vector3f(0.0F, 0.0F, 0.0F), leftRotation,
				new Vector3f(1.4F, 1.4F, 1.4F), rightRotation));
		Displays.teleportInterpolation(display, 2);
		Displays.interpolate(display, 0, 2);

		Vec3 velocity = direction.scale(TRIDENT_SPEED);
		UUID caster = player.getUUID();
		final double[] travelled = {0.0D};
		final boolean[] done = {false};
		this.mod.scheduler().timer(() -> {
			if (done[0]) {
				return;
			}
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(caster);
			if (owner == null || display.isRemoved()) {
				done[0] = true;
				Displays.remove(display);
				return;
			}
			Vec3 next = display.position().add(velocity);
			display.snapTo(next.x, next.y, next.z);
			Fx.simple(level, "SPLASH", next, 5, 0.15D, 0.15D, 0.15D, 0.02D);
			Fx.simple(level, "END_ROD", next, 1, 0.05D, 0.05D, 0.05D, 0.0D);
			if (travelled[0] % 4.0D < 1.0D) {
				Fx.sound(level, next, "ITEM_TRIDENT_RIPTIDE_1", 0.4F, 1.6F);
			}
			travelled[0] += TRIDENT_SPEED;
			BlockPos at = BlockPos.containing(next);
			BlockState state = level.getBlockState(at);
			boolean blocked = state.isSolid() && !state.is(Blocks.WATER);
			if (blocked || travelled[0] >= TRIDENT_MAX_DISTANCE) {
				done[0] = true;
				Displays.remove(display);
				activateStormcall(owner, level, next, durationSeconds, damage, hitInterval, maxTargets, cloudHeight);
			}
		}, 0L, 1L).cancelAfter((long) Math.ceil(TRIDENT_MAX_DISTANCE / TRIDENT_SPEED) + 10L);
	}

	/** {@code TidebreakerAbilities#activateStormcall}. */
	private void activateStormcall(ServerPlayer caster, ServerLevel level, Vec3 at, int durationSeconds,
			double damage, int hitInterval, int maxTargets, int cloudHeight) {
		Fx.sound(level, at, "ITEM_TRIDENT_HIT_GROUND", 1.4F, 0.8F);
		UUID casterId = caster.getUUID();
		final int[] elapsed = {0};
		final int[] sinceStrike = {0};
		final double[] phase = {0.0D};
		this.mod.scheduler().timer(() -> {
			if (elapsed[0] >= durationSeconds * 20) {
				return;
			}
			Vec3 cloud = at.add(0.0D, cloudHeight, 0.0D);
			Fx.simple(level, "LARGE_SMOKE", cloud, 35, 1.2D, 0.4D, 1.2D, 0.0D);
			Fx.simple(level, "WHITE_SMOKE", cloud, 25, 1.0D, 0.3D, 1.0D, 0.0D);
			Fx.simple(level, "LARGE_SMOKE", cloud.add(0.0D, 0.3D, 0.0D), 20, 0.8D, 0.2D, 0.8D, 0.0D);
			Fx.simple(level, "WHITE_SMOKE", cloud.add(0.0D, -0.2D, 0.0D), 15, 0.9D, 0.2D, 0.9D, 0.0D);
			drawFlickerRing(level, at, FLICKER_RING_RADIUS, phase[0]);
			phase[0] += Math.PI / 12.0D;

			if (sinceStrike[0] >= hitInterval) {
				sinceStrike[0] = 0;
				ServerPlayer owner = level.getServer().getPlayerList().getPlayer(casterId);
				List<LivingEntity> targets = new ArrayList<>();
				for (Entity entity : level.getEntities(null, box(at, STRIKE_RADIUS))) {
					if (!(entity instanceof LivingEntity living) || living == caster) {
						continue;
					}
					if (living instanceof ServerPlayer other && !allowed(owner, other)) {
						continue;
					}
					targets.add(living);
				}
				targets.sort(Comparator.comparingDouble(LivingEntity::getHealth).reversed());
				if (targets.isEmpty()) {
					double angle = level.random.nextDouble() * Math.PI * 2.0D;
					double distance = level.random.nextDouble() * STRIKE_RADIUS;
					Vec3 ground = at.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
					Vec3 fork = cloud.add((level.random.nextDouble() - 0.5D) * 2.5D, 0.0D,
							(level.random.nextDouble() - 0.5D) * 2.5D);
					drawCurvyBolt(level, fork, ground);
					Fx.sound(level, ground, "ENTITY_LIGHTNING_BOLT_THUNDER", 0.7F, 1.4F);
				} else {
					int struck = 0;
					for (LivingEntity target : targets) {
						if (struck >= maxTargets) {
							break;
						}
						TrueDamage.apply(target, damage, owner, false);
						Fx.simple(level, "END_ROD", target.position().add(0.0D, 1.0D, 0.0D), 12,
								0.3D, 0.5D, 0.3D, 0.05D);
						Fx.sound(level, target.position(), "ENTITY_LIGHTNING_BOLT_THUNDER", 0.7F, 1.4F);
						Vec3 fork = cloud.add((level.random.nextDouble() - 0.5D) * 2.5D, 0.0D,
								(level.random.nextDouble() - 0.5D) * 2.5D);
						drawCurvyBolt(level, fork, target.position());
						struck++;
					}
				}
			}
			elapsed[0] += 2;
			sinceStrike[0] += 2;
		}, 0L, 2L).cancelAfter(durationSeconds * 20L + 10L);
	}

	/** {@code TidebreakerAbilities#drawCurvyBolt}. */
	private void drawCurvyBolt(ServerLevel level, Vec3 from, Vec3 to) {
		Vec3 delta = to.subtract(from);
		Vec3 previous = from;
		for (int segment = 1; segment <= BOLT_SEGMENTS; segment++) {
			double progress = segment / (double) BOLT_SEGMENTS;
			double jitterScale = (1.0D - progress) * 1.8D;
			Vec3 point = from.add(delta.scale(progress));
			point = point.add((level.random.nextDouble() - 0.5D) * jitterScale,
					(level.random.nextDouble() - 0.5D) * jitterScale * 0.5D,
					(level.random.nextDouble() - 0.5D) * jitterScale);
			for (int subdivision = 0; subdivision < BOLT_SUBDIVISIONS; subdivision++) {
				Vec3 between = previous.add(point.subtract(previous).scale(subdivision / (double) BOLT_SUBDIVISIONS));
				Fx.simple(level, "ELECTRIC_SPARK", between, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
			previous = point;
		}
	}

	/** {@code TidebreakerAbilities#drawFlickerRing}. */
	private void drawFlickerRing(ServerLevel level, Vec3 center, double radius, double phase) {
		List<Integer> points = new ArrayList<>(FLICKER_POINTS);
		for (int index = 0; index < FLICKER_POINTS; index++) {
			points.add(index);
		}
		java.util.Collections.shuffle(points);
		for (int index = 0; index < FLICKER_LIT; index++) {
			double angle = phase + Math.PI * 2.0D * points.get(index) / FLICKER_POINTS;
			Vec3 at = center.add(Math.cos(angle) * radius, 0.1D, Math.sin(angle) * radius);
			Fx.simple(level, "ELECTRIC_SPARK", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	// ------------------------------------------------------------- rising tide

	/** {@code TidebreakerAbilities#tryRisingTide}. */
	private void tryRisingTide(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.tidebreaker.rising_tide.cooldown", 50);
		if (!ctx.gate(KEY_RISE, "Rising Tide", cooldown)) {
			return;
		}
		// The plugin showed no boss bar for Rising Tide, only for Stormcall.
		if (player.getXRot() < UP_JET_PITCH_THRESHOLD) {
			risingTideUp(ctx);
		} else {
			risingTideDown(ctx);
		}
	}

	/** {@code TidebreakerAbilities#risingTideUp}. */
	private void risingTideUp(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int jetHeight = ctx.cfg("abilities.tidebreaker.rising_tide.jet_height", 14);
		double shockwaveRadius = ctx.cfgd("abilities.tidebreaker.rising_tide.shockwave_radius", 8.0D);
		boolean terrain = ctx.canDestroyTerrain();
		Vec3 at = player.position();

		for (double angle = 0.0D; angle < Math.PI * 2.0D; angle += Math.PI / 16.0D) {
			Vec3 ring = at.add(Math.cos(angle), 0.0D, Math.sin(angle));
			Fx.simple(level, "SPLASH", ring.add(0.0D, 0.3D, 0.0D), 12, 0.2D, 0.3D, 0.2D, 0.1D);
			Fx.simple(level, "CLOUD", ring.add(0.0D, 0.2D, 0.0D), 4, 0.1D, 0.1D, 0.1D, 0.02D);
		}
		Fx.sound(level, at, "BLOCK_WATER_AMBIENT", 1.0F, 1.4F);
		breakWebs(level, player, at, 2, terrain);

		BlockPos base = BlockPos.containing(at);
		List<BlockPos> jet = new ArrayList<>(UP_JET_WATER_BLOCKS);
		for (int height = 0; height < UP_JET_WATER_BLOCKS; height++) {
			BlockPos pos = base.above(height);
			jet.add(pos);
			if (terrain && canReplace(level.getBlockState(pos)) && mayPlaceWater(player, pos)) {
				level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
			}
		}
		for (int delay : UP_JET_WATER_REVERT_TICKS) {
			this.mod.scheduler().later(() -> {
				for (BlockPos pos : jet) {
					if (level.getBlockState(pos).is(Blocks.WATER)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
					}
				}
			}, delay);
		}

		Motion.setVelocity(player, new Vec3(0.0D, jetHeight / 6.0D, 0.0D));
		Messaging.actionBar(player, "<aqua>Rising Tide - launching up");

		UUID caster = player.getUUID();
		final int[] tick = {0};
		final boolean[] armed = {false};
		final boolean[] done = {false};
		this.mod.scheduler().timer(() -> {
			if (done[0]) {
				return;
			}
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(caster);
			if (owner == null) {
				done[0] = true;
				return;
			}
			tick[0] += 2;
			if (!armed[0] && tick[0] > 6) {
				armed[0] = true;
			}
			if (armed[0] && (owner.onGround() || tick[0] > 200)) {
				done[0] = true;
				onLanding(owner, level, owner.position(), shockwaveRadius,
						ctx.cfgd("abilities.tidebreaker.rising_tide.water_damage", 2.5D), terrain);
			}
		}, 0L, 2L).cancelAfter(210L);
	}

	/** {@code TidebreakerAbilities#onLanding} - the expanding water shockwave. */
	private void onLanding(ServerPlayer caster, ServerLevel level, Vec3 at, double radius, double waterDamage,
			boolean terrain) {
		caster.fallDistance = 0.0F;
		Fx.sound(level, at, "ENTITY_GENERIC_EXPLODE", 1.5F, 1.0F);
		Fx.sound(level, at, "BLOCK_WATER_AMBIENT", 2.0F, 0.6F);
		Fx.simple(level, "SPLASH", at, 30, 0.3D, 0.3D, 0.3D, 0.1D);

		int ceiling = (int) Math.ceil(radius);
		BlockPos base = BlockPos.containing(at);
		Set<UUID> struck = new HashSet<>();
		final int[] step = {0};
		this.mod.scheduler().timer(() -> {
			if (step[0] > LANDING_STEPS) {
				return;
			}
			double inner;
			double outer;
			if (step[0] == 0) {
				inner = 0.0D;
				outer = LANDING_START_RADIUS;
			} else {
				double before = (step[0] - 1.0D) / 7.0D;
				double after = step[0] / 7.0D;
				inner = LANDING_START_RADIUS + before * (radius - LANDING_START_RADIUS);
				outer = LANDING_START_RADIUS + after * (radius - LANDING_START_RADIUS);
			}
			if (terrain) {
				for (int dx = -ceiling; dx <= ceiling; dx++) {
					for (int dz = -ceiling; dz <= ceiling; dz++) {
						double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
						if (distance <= inner || distance > outer || distance > radius) {
							continue;
						}
						int height = (int) Math.round(1.0D + (WAVE_HEIGHT - 1) * (distance / radius));
						if (height <= 0) {
							continue;
						}
						int skip = distance < LANDING_START_RADIUS ? 1 : 0;
						for (int dy = 0; dy < Math.max(0, height - skip); dy++) {
							BlockPos pos = base.offset(dx, dy, dz);
							if (canReplace(level.getBlockState(pos)) && mayPlaceWater(caster, pos)) {
								level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
							}
						}
					}
				}
			}
			double mid = (inner + outer) / 2.0D;
			for (int point = 0; point < 36; point++) {
				double angle = Math.PI * 2.0D * point / 36.0D;
				Vec3 foam = at.add(Math.cos(angle) * mid, 0.4D, Math.sin(angle) * mid);
				Fx.simple(level, "SPLASH", foam, 4, 0.1D, 0.1D, 0.1D, 0.04D);
				Vec3 crest = at.add(Math.cos(angle) * (outer + 0.2D), 0.6D, Math.sin(angle) * (outer + 0.2D));
				Fx.simple(level, "WHITE_SMOKE", crest, 1, 0.05D, 0.05D, 0.05D, 0.0D);
				if (point % 2 == 0) {
					Fx.simple(level, "CLOUD", crest, 1, 0.05D, 0.05D, 0.05D, 0.0D);
				}
			}
			AABB search = new AABB(at.x - radius - 1.0D, at.y - (WAVE_HEIGHT + 1.0D), at.z - radius - 1.0D,
					at.x + radius + 1.0D, at.y + WAVE_HEIGHT + 1.0D, at.z + radius + 1.0D);
			for (Entity entity : level.getEntities(null, search)) {
				if (!(entity instanceof LivingEntity living) || living == caster) {
					continue;
				}
				if (living instanceof ServerPlayer other && !allowed(caster, other)) {
					continue;
				}
				if (!struck.add(living.getUUID())) {
					continue;
				}
				if (living.position().distanceTo(at) > radius + 0.5D) {
					continue;
				}
				TrueDamage.apply(living, waterDamage, caster, false);
				Vec3 away = living.position().subtract(at);
				away = new Vec3(away.x, 0.0D, away.z);
				if (away.lengthSqr() < 0.001D) {
					away = new Vec3(0.0D, 0.0D, 1.0D);
				}
				away = away.normalize().scale(LANDING_KNOCKBACK_HORIZONTAL);
				Motion.setVelocity(living, new Vec3(away.x, LANDING_KNOCKBACK_VERTICAL, away.z));
			}
			step[0]++;
		}, 0L, 1L).cancelAfter(LANDING_STEPS + 4L);

		drain(level, at, radius + 2.5D, 12L);
	}

	/**
	 * The plugin's second {@code onLanding} task: from tick 12, every 2 ticks for at
	 * most 15 passes, every water block and waterlogged state inside the wave is
	 * removed until two passes find nothing left.
	 */
	private void drain(ServerLevel level, Vec3 at, double radius, long firstDelay) {
		int ceiling = (int) Math.ceil(radius);
		BlockPos base = BlockPos.containing(at);
		final int[] passes = {0};
		final int[] emptyPasses = {0};
		this.mod.scheduler().timer(() -> {
			if (emptyPasses[0] >= 2 || passes[0] >= 15) {
				return;
			}
			passes[0]++;
			int removed = 0;
			for (int dx = -ceiling; dx <= ceiling; dx++) {
				for (int dy = -2; dy <= WAVE_HEIGHT + 3; dy++) {
					for (int dz = -ceiling; dz <= ceiling; dz++) {
						if ((double) dx * dx + (double) dz * dz > radius * radius) {
							continue;
						}
						BlockPos pos = base.offset(dx, dy, dz);
						BlockState state = level.getBlockState(pos);
						if (state.is(Blocks.WATER)) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
							removed++;
						} else if (state.hasProperty(BlockStateProperties.WATERLOGGED)
								&& state.getValue(BlockStateProperties.WATERLOGGED)) {
							level.setBlock(pos, state.setValue(BlockStateProperties.WATERLOGGED, false), 2);
							removed++;
						}
					}
				}
			}
			if (removed == 0) {
				emptyPasses[0]++;
			} else {
				emptyPasses[0] = 0;
			}
		}, firstDelay, 2L).cancelAfter(firstDelay + 32L);
	}

	/** {@code TidebreakerAbilities#risingTideDown} - the charge before the wave. */
	private void risingTideDown(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		Vec3 direction = player.getViewVector(1.0F);
		direction = new Vec3(direction.x, 0.0D, direction.z);
		if (direction.lengthSqr() < 0.001D) {
			direction = new Vec3(0.0D, 0.0D, 1.0D);
		}
		direction = direction.normalize();
		Vec3 side = new Vec3(-direction.z, 0.0D, direction.x).normalize();

		Vec3 ahead = player.position().add(direction);
		Vec3 anchor = new Vec3(Math.floor(ahead.x) + 0.5D, Math.floor(player.position().y),
				Math.floor(ahead.z) + 0.5D);
		Messaging.actionBar(player, "<aqua>Rising Tide - charging...");

		UUID caster = player.getUUID();
		final int[] tick = {0};
		final Vec3 chargeDirection = direction;
		final Vec3 chargeSide = side;
		this.mod.scheduler().timer(() -> {
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(caster);
			if (owner == null) {
				return;
			}
			tick[0]++;
			AABB pull = new AABB(owner.position().x - 8.0D, owner.position().y - 4.0D, owner.position().z - 8.0D,
					owner.position().x + 8.0D, owner.position().y + 4.0D, owner.position().z + 8.0D);
			for (Entity entity : level.getEntities(null, pull)) {
				if (!(entity instanceof LivingEntity living) || living == owner) {
					continue;
				}
				if (living instanceof ServerPlayer other && !allowed(owner, other)) {
					continue;
				}
				Vec3 towards = owner.position().subtract(living.position());
				towards = new Vec3(towards.x, 0.0D, towards.z);
				if (towards.lengthSqr() < 0.001D) {
					continue;
				}
				towards = towards.normalize().scale(CHARGE_PULL);
				Motion.setVelocity(living, living.getDeltaMovement().scale(0.5D).add(towards));
			}
			if (tick[0] % 4 == 0) {
				Fx.sound(level, anchor, "BLOCK_BUBBLE_COLUMN_BUBBLE_POP", 1.4F, 0.7F);
			}
			if (tick[0] % 2 == 0) {
				Fx.sound(level, anchor, "BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT", 1.2F, 0.8F);
			}
			Vec3 bubbles = anchor.add(0.0D, 0.5D, 0.0D);
			Fx.simple(level, "SPLASH", bubbles, 6, 0.5D, 0.3D, 0.5D, 0.0D);
			Fx.simple(level, "BUBBLE_POP", bubbles, 3, 0.4D, 0.3D, 0.4D, 0.0D);
			if (tick[0] >= CHARGE_TICKS) {
				launchStaircaseWave(owner, level, anchor, chargeDirection, chargeSide,
						ctx.cfg("abilities.tidebreaker.rising_tide.wave_distance", 25),
						ctx.cfgd("abilities.tidebreaker.rising_tide.water_damage", 2.5D),
						ctx.cfgd("abilities.tidebreaker.rising_tide.pull_velocity", 1.1D),
						ctx.canDestroyTerrain());
			}
		}, 0L, 1L).cancelAfter(CHARGE_TICKS + 2L);
	}

	/** {@code TidebreakerAbilities#launchStaircaseWave}. */
	private void launchStaircaseWave(ServerPlayer caster, ServerLevel level, Vec3 anchor, Vec3 direction, Vec3 side,
			int waveDistance, double waterDamage, double pullVelocity, boolean terrain) {
		Fx.sound(level, anchor, "ITEM_BUCKET_FILL", 1.4F, 0.6F);
		Set<UUID> struck = new HashSet<>();
		Set<BlockPos> previousWater = new HashSet<>();
		final int[] step = {0};
		this.mod.scheduler().timer(() -> {
			for (BlockPos pos : previousWater) {
				if (level.getBlockState(pos).is(Blocks.WATER)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
				}
			}
			previousWater.clear();
			if (step[0] >= waveDistance) {
				return;
			}
			int columns = Math.min(4, step[0] + 1);
			int startOffset = Math.max(0, step[0] - 4 + 1);
			Vec3 front = null;
			for (int column = 0; column < columns; column++) {
				int distance = startOffset + column;
				int height = column + 1;
				Vec3 at = anchor.add(direction.scale(distance));
				if (column == columns - 1) {
					front = at;
				}
				int extra = 0;
				BlockPos columnPos = BlockPos.containing(at);
				for (int check = 1; check <= 2; check++) {
					if (level.getBlockState(columnPos.above(check)).isSolid()) {
						extra = check;
					}
				}
				int total = height + extra;
				for (int offset = -1; offset <= 1; offset++) {
					Vec3 shifted = at.add(side.scale(offset));
					BlockPos shiftedPos = BlockPos.containing(shifted);
					for (int dy = 0; dy < total; dy++) {
						BlockPos pos = shiftedPos.above(dy);
						if (terrain && canReplace(level.getBlockState(pos)) && mayPlaceWater(caster, pos)) {
							level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
							previousWater.add(pos);
						}
					}
				}
			}
			if (front != null) {
				Fx.sound(level, front, "ITEM_BUCKET_EMPTY", 0.7F, 1.4F);
				Fx.sound(level, front, "BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT", 0.6F, 0.8F);
				for (int spray = 0; spray < 10; spray++) {
					Vec3 drop = front.add((level.random.nextDouble() - 0.5D) * 2.5D,
							level.random.nextDouble() * 0.4D + 0.2D, (level.random.nextDouble() - 0.5D) * 2.5D);
					Fx.simple(level, "SPLASH", drop, 1, 0.05D, 0.05D, 0.05D, 0.02D);
				}
				for (int bubble = 0; bubble < 6; bubble++) {
					Vec3 pop = front.add((level.random.nextDouble() - 0.5D) * 3.0D, 0.1D,
							(level.random.nextDouble() - 0.5D) * 3.0D);
					Fx.simple(level, "BUBBLE_POP", pop, 1, 0.0D, 0.0D, 0.0D, 0.0D);
				}
			}
			Vec3 back = anchor.add(direction.scale(startOffset));
			Vec3 edge = front != null ? front : back;
			for (double along = 0.0D; along <= 1.001D; along += 0.15D) {
				Vec3 point = back.add(edge.subtract(back).scale(along));
				double leftWidth = 1.6D + 0.2D + level.random.nextDouble() * 0.4D;
				double rightWidth = 1.6D + 0.2D + level.random.nextDouble() * 0.4D;
				Vec3 left = point.add(side.scale(-leftWidth));
				Vec3 right = point.add(side.scale(rightWidth));
				Fx.simple(level, "CLOUD", left.add(0.0D, 0.4D, 0.0D), 1, 0.08D, 0.1D, 0.08D, 0.0D);
				Fx.simple(level, "CLOUD", right.add(0.0D, 0.4D, 0.0D), 1, 0.08D, 0.1D, 0.08D, 0.0D);
				Fx.simple(level, "WHITE_SMOKE", left.add(0.0D, 0.6D, 0.0D), 1, 0.1D, 0.15D, 0.1D, 0.0D);
				Fx.simple(level, "WHITE_SMOKE", right.add(0.0D, 0.6D, 0.0D), 1, 0.1D, 0.15D, 0.1D, 0.0D);
			}
			for (int column = 0; column < columns; column++) {
				Vec3 at = anchor.add(direction.scale(startOffset + column));
				AABB search = new AABB(at.x - 1.6D, at.y - 3.0D, at.z - 1.6D, at.x + 1.6D, at.y + 3.0D, at.z + 1.6D);
				for (Entity entity : level.getEntities(null, search)) {
					if (!(entity instanceof LivingEntity living) || living == caster) {
						continue;
					}
					if (living instanceof ServerPlayer other && !allowed(caster, other)) {
						continue;
					}
					if (!struck.add(living.getUUID())) {
						continue;
					}
					TrueDamage.apply(living, waterDamage, caster, false);
					startMuddiedPull(caster, level, living, pullVelocity);
				}
			}
			step[0]++;
		}, 0L, 2L).cancelAfter(waveDistance * 2L + 6L);
	}

	/** {@code TidebreakerAbilities#startMuddiedPull}. */
	private void startMuddiedPull(ServerPlayer caster, ServerLevel level, LivingEntity target, double pullVelocity) {
		UUID casterId = caster.getUUID();
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(casterId);
			if (owner == null || target.isRemoved() || !target.isAlive() || tick[0] >= MUDDIED_TICKS) {
				return;
			}
			Vec3 to = owner.position().add(0.0D, 1.0D, 0.0D);
			Vec3 from = target.position().add(0.0D, 1.0D, 0.0D);
			if (to.distanceTo(from) - MUDDIED_STOP_DISTANCE <= 0.0D) {
				Motion.setVelocity(target, Vec3.ZERO);
				tick[0] = MUDDIED_TICKS;
				return;
			}
			Vec3 pull = to.subtract(target.position()).normalize().scale(pullVelocity);
			Motion.setVelocity(target, pull);
			tick[0]++;
		}, 0L, 1L).cancelAfter(MUDDIED_TICKS + 2L);
	}

	// ------------------------------------------------------------------ helpers

	/** {@code TidebreakerAbilities#breakWebs}. */
	private void breakWebs(ServerLevel level, ServerPlayer player, Vec3 at, int radius, boolean terrain) {
		if (!terrain) {
			return;
		}
		BlockPos base = BlockPos.containing(at);
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos pos = base.offset(dx, dy, dz);
					if (level.getBlockState(pos).is(Blocks.COBWEB) && mayPlaceWater(player, pos)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
	}

	/** {@code TidebreakerAbilities#canReplace}. */
	private static boolean canReplace(BlockState state) {
		if (state.isAir()) {
			return true;
		}
		if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)) {
			return true;
		}
		if (state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)) {
			return true;
		}
		if (state.is(Blocks.DEAD_BUSH) || state.is(Blocks.SHORT_DRY_GRASS) || state.is(Blocks.TALL_DRY_GRASS)) {
			return true;
		}
		return state.canBeReplaced() && !state.isSolid();
	}

	/** The plugin's {@code D.b(player, location)} build-permission check. */
	private boolean mayPlaceWater(ServerPlayer player, BlockPos pos) {
		return this.mod.protection().allowBlockPlace(player, pos);
	}

	private static AABB box(Vec3 center, double radius) {
		return new AABB(center.x - radius, center.y - radius, center.z - radius,
				center.x + radius, center.y + radius, center.z + radius);
	}

	/** Bukkit's {@code factions.isTrusted} + ability immunity, as elsewhere in the port. */
	private boolean allowed(@javax.annotation.Nullable ServerPlayer attacker, ServerPlayer victim) {
		return this.mod.immunity().allowAbilityHit(attacker, victim);
	}

	/** Registry-id lookup helper kept tiny so the nautilus check stays readable. */
	private static final class BuiltInIds {
		static String entityId(EntityType<?> type) {
			net.minecraft.resources.Identifier key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
					.getKey(type);
			return key == null ? "" : key.getPath();
		}
	}
}
