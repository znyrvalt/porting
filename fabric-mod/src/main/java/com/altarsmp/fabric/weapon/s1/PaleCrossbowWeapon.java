package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Matrix4f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.GameRegistry;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Pale Crossbow - port of {@code com.altarsmp.weapons.PaleCrossbowWeapon}.
 *
 * <p><b>Pale Shot</b> (right-click while the crossbow is charged): the charge is
 * consumed, the shooter recoils 0.5 backwards, and a glowing resin block display
 * flies at {@code pale_shot.speed} with {@code pale_shot.gravity} applied each
 * tick. On impact it explodes for {@code arrow_bonus_damage} true damage inside a
 * {@code pale_arrow_hitbox} radius, leaves a grey lingering Unluck cloud (radius
 * 4, 200 ticks, shrinking) and marks hit players with the pale effect
 * (Luck II for an hour plus the {@code pale_effect} tag). Shots are rate limited
 * by {@code shoot_cooldown} and a 400-tick crossbow item cooldown.</p>
 *
 * <p><b>Pale Roots</b> (F, {@code pale_roots.cooldown}s): {@code arch_count} root
 * models erupt along the ground in the direction you face, out to
 * {@code pale_roots.range}. Each model is five pale-oak block displays driven by
 * the original's exact piece table and 0/113.68/165.12/180 degree animation
 * curve, hit-scans for {@code pale_roots.duration_ticks} dealing
 * {@code pale_roots.damage} true damage, Wither II, Slowness II, a 0.8 upward
 * launch and a {@code dot_damage} x {@code dot_duration} seconds bleed, then
 * crumbles away with creaking sounds.</p>
 */
public final class PaleCrossbowWeapon implements WeaponBehavior {

	static final String KEY_SHOOT = "palecrossbow_shoot";
	static final String KEY_ROOTS = "palecrossbow_pale_roots";
	static final String TAG_PALE_EFFECT = "pale_effect";
	static final String TAG_KILLED_BY = "killed_by_pale_crossbow";

	private static final float PALE_ROOT_UNIT = 0.0625F;
	private static final float PALE_ROOT_ORIGIN_Y = -0.5625F;
	private static final double PALE_ROOT_EXTRA_CHAIN_SPACING = 0.75D;
	private static final double PALE_ROOT_VISUAL_Y_OFFSET = 0.4D;
	private static final float[] PALE_ROOT_ANIMATION_TICKS = {0.0F, 3.0F, 6.0F, 10.0F};
	private static final float[] PALE_ROOT_ANIMATION_DEGREES = {0.0F, 113.68F, 165.12F, 180.0F};
	/** {@code PALE_ROOT_PIECES}: from/to corners, pivot and rotation of each root beam. */
	private static final RootPiece[] PALE_ROOT_PIECES = {
			new RootPiece(-8.0F, -9.0F, 8.0F, 8.0F, 7.0F, 24.0F, 0.0F, -9.0F, 16.0F, 0.0F, 0.0F, 0.0F),
			new RootPiece(-8.0F, 7.0F, -8.0F, 8.0F, 23.0F, 8.0F, 0.0F, 7.0F, 0.0F, 0.0F, 0.0F, 0.0F),
			new RootPiece(-8.0F, 7.0F, -30.5F, 8.0F, 23.0F, -8.0F, 0.0F, 23.0F, -8.0F, -45.0F, 0.0F, 0.0F),
			new RootPiece(-8.0F, -9.0F, -24.0F, 8.0F, 7.0F, -8.0F, 0.0F, -9.0F, -16.0F, 0.0F, 0.0F, 0.0F),
			new RootPiece(-8.0F, 7.0F, 8.0F, 8.0F, 23.0F, 30.5F, 0.0F, 23.0F, 8.0F, 45.0F, 0.0F, 0.0F)
	};
	private static final int PALE_SHOT_MAX_TICKS = 500;
	private static final double PALE_SHOT_RECOIL = 0.5D;
	private static final int PALE_SHOT_ITEM_COOLDOWN_TICKS = 400;
	private static final long PALE_SHOT_COOLDOWN_MILLIS = 20000L;

	private final AltarSMPMod mod;
	private final Map<UUID, List<Display.BlockDisplay>> rootModels = new HashMap<>();

	public PaleCrossbowWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "palecrossbow";
	}

	@Override
	public String displayName() {
		return "Pale Crossbow";
	}

	@Override
	public List<String> configFields() {
		return List.of("Pale Roots Cooldown (s)", "Shoot Cooldown (s)", "Pale Roots Damage", "Pale Roots DoT Damage",
				"Pale Roots DoT Duration (s)", "Pale Roots Arch Count", "Pale Roots Range (blocks)",
				"Pale Roots Duration (ticks)", "Pale Shot Speed", "Pale Shot Gravity", "Pale Arrow Hitbox",
				"Arrow Bonus Damage");
	}

	int paleRootsRange(AbilityContext ctx) {
		return ctx.cfg("abilities.palecrossbow.pale_roots.range", 15);
	}

	int paleRootsDurationTicks(AbilityContext ctx) {
		return ctx.cfg("abilities.palecrossbow.pale_roots.duration_ticks", 100);
	}

	// ------------------------------------------------------------------ passive

	@Override
	public void onTick(AbilityContext ctx) {
		if (this.mod.scheduler().currentTick() % 5L != 0L) {
			return;
		}
		ServerPlayer player = ctx.player();
		if (!Identity.is(player.getMainHandItem(), id()) && !Identity.is(player.getOffhandItem(), id())) {
			return;
		}
		Vec3 pos = player.position().add(0.0D, 1.0D, 0.0D).add(
				ctx.random().nextDouble() - 0.5D, ctx.random().nextDouble() - 0.5D, ctx.random().nextDouble() - 0.5D);
		Fx.dust(ctx.level(), pos, 0xB4B4B4, 0.8F, 1, 0.0D, 0.0D, 0.0D);
	}

	// ---------------------------------------------------------------- pale shot

	@Override
	public boolean onUse(AbilityContext ctx, net.minecraft.world.InteractionHand hand) {
		ServerPlayer player = ctx.player();
		if (hand != net.minecraft.world.InteractionHand.MAIN_HAND || !Identity.is(ctx.weapon(), id())) {
			return false;
		}
		ItemStack crossbow = player.getMainHandItem();
		net.minecraft.world.item.component.ChargedProjectiles charged =
				crossbow.get(DataComponents.CHARGED_PROJECTILES);
		if (charged == null || charged.isEmpty()) {
			return false;
		}
		if (ctx.mod().cooldowns().isOnCooldown(player, KEY_SHOOT)) {
			Messaging.actionBar(player, "<gray>Pale Crossbow recharging: <yellow>" + ctx.remaining(KEY_SHOOT));
			return true;
		}
		crossbow.remove(DataComponents.CHARGED_PROJECTILES);
		Vec3 look = player.getLookAngle().normalize();
		Motion.setVelocity(player, look.scale(-PALE_SHOT_RECOIL));
		ctx.mod().cooldowns().setCooldown(player, KEY_SHOOT, PALE_SHOT_COOLDOWN_MILLIS);
		CooldownBars.show(player, KEY_SHOOT, "Pale Shot", BossEvent.BossBarColor.YELLOW, PALE_SHOT_COOLDOWN_MILLIS);
		player.getCooldowns().addCooldown(Items.CROSSBOW, PALE_SHOT_ITEM_COOLDOWN_TICKS);
		firePaleShot(ctx);
		return true;
	}

	/** {@code PaleCrossbowWeapon#firePaleShot}. */
	private void firePaleShot(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		Vec3 origin = player.getEyePosition();
		Vec3 direction = player.getLookAngle().normalize();
		Fx.sound(level, origin, "ENTITY_GENERIC_EXPLODE", 1.0F, 2.0F);
		Fx.sound(level, origin, "ENTITY_CREAKING_DEATH", 1.0F, 2.0F);

		for (int index = 0; index < 12; index++) {
			double angle = Math.PI * 2.0D * index / 12.0D;
			Fx.dust(level, origin.add(Math.cos(angle) * 0.6D, 0.0D, Math.sin(angle) * 0.6D), 0xB4B4B4, 1.0F,
					2, 0.05D, 0.05D, 0.05D);
		}
		for (double distance = 0.5D; distance <= 4.0D; distance += 0.5D) {
			Fx.simple(level, "ASH", origin.add(direction.scale(distance)), 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		Fx.simple(level, "CLOUD", origin, 6, 0.2D, 0.2D, 0.2D, 0.02D);

		Display.ItemDisplay resin = Displays.item(level, origin, new ItemStack(Items.RESIN_BLOCK), 0.75F);
		Displays.bright(resin);
		Displays.teleportInterpolation(resin, 3);
		Displays.tag(resin, "altarsmp_pale_shot");

		Vec3 velocity = direction.scale(ctx.cfgd("abilities.palecrossbow.pale_shot.speed", 2.0D));
		double gravity = ctx.cfgd("abilities.palecrossbow.pale_shot.gravity", 0.0025D);
		final double[] vertical = {velocity.y};
		final int[] age = {0};
		this.mod.scheduler().timer(() -> {
			if (resin.isRemoved() || age[0]++ >= PALE_SHOT_MAX_TICKS) {
				if (age[0] >= PALE_SHOT_MAX_TICKS) {
					Fx.sound(level, player.position(), "ENTITY_GENERIC_EXPLODE", 2.0F, 1.5F);
				}
				Displays.remove(resin);
				return;
			}
			vertical[0] -= gravity;
			Vec3 next = resin.position().add(velocity.x, vertical[0], velocity.z);
			resin.setPos(next);
			crumble(level, next, "RESIN_BLOCK", 3, 0.1D, 0.1D, 0.1D, 0.01D);

			double hitbox = ctx.cfgd("abilities.palecrossbow.pale_arrow_hitbox", 3.0D);
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(BlockPos.containing(next)).inflate(hitbox), e -> e != player && e.isAlive())) {
				if (!ctx.abilityAllowedOn(victim instanceof ServerPlayer target ? target : null)) {
					continue;
				}
				paleShotImpact(ctx, next, victim);
				Displays.remove(resin);
				return;
			}
			if (!level.getBlockState(BlockPos.containing(next)).isAir()) {
				paleShotImpact(ctx, next, null);
				Displays.remove(resin);
			}
		}, 0L, 1L).cancelAfter(PALE_SHOT_MAX_TICKS + 5L);
	}

	/** {@code PaleCrossbowWeapon.c#a(Location, LivingEntity)}. */
	private void paleShotImpact(AbilityContext ctx, Vec3 point, javax.annotation.Nullable LivingEntity victim) {
		ServerLevel level = ctx.level();
		ServerPlayer player = ctx.player();
		Fx.sound(level, point, "ENTITY_GENERIC_EXPLODE", 1.5F, 0.5F);
		Fx.sound(level, point, "ENTITY_CREAKING_DEATH", 1.5F, 1.0F);
		Vec3 center = point.add(0.0D, 1.0D, 0.0D);
		Fx.blockParticles(level, "RESIN_BLOCK", center, 25, 0.4D, 0.4D, 0.4D, 0.05D);
		Fx.itemParticles(level, "RESIN_CLUMP", center, 40, 0.5D, 0.5D, 0.5D, 0.1D);
		Fx.itemParticles(level, "PALE_MOSS_BLOCK", center, 40, 0.5D, 0.5D, 0.5D, 0.1D);
		if (victim == null) {
			return;
		}
		TrueDamage.apply(victim, ctx.cfg("abilities.palecrossbow.arrow_bonus_damage", 15), player, true);
		AreaEffectCloud cloud = EntityType.AREA_EFFECT_CLOUD.create(level);
		if (cloud != null) {
			cloud.snapTo(point.x, point.y, point.z);
			cloud.setRadius(4.0F);
			cloud.setDuration(200);
			cloud.setRadiusPerTick(-0.02F);
			cloud.setFixedColor(0x636363);
			cloud.addEffect(new MobEffectInstance(
					net.minecraft.world.effect.MobEffects.UNLUCK, 12000, 0, false, true, true));
			level.addFreshEntity(cloud);
		}
		if (victim instanceof ServerPlayer target) {
			applyPaleEffect(target);
		}
	}

	// --------------------------------------------------------------- pale roots

	@Override
	public void onPrimary(AbilityContext ctx) {
		usePaleRoots(ctx);
	}

	/** {@code PaleCrossbowWeapon#usePaleRoots}. */
	private void usePaleRoots(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.palecrossbow.pale_roots.cooldown", 45);
		if (!ctx.gate(KEY_ROOTS, "Pale Roots")) {
			return;
		}
		ctx.startCooldown(KEY_ROOTS, cooldown);
		CooldownBars.show(player, KEY_ROOTS, "Pale Roots", BossEvent.BossBarColor.YELLOW, cooldown);

		Vec3 anchor = player.position().add(0.0D, 1.0D, 0.0D);
		Set<UUID> alreadyHit = new HashSet<>();
		int range = paleRootsRange(ctx);
		int arches = Math.max(1, ctx.cfg("abilities.palecrossbow.pale_roots.arch_count", 5));
		double step = (double) range / Math.max(1, 40);
		double chainSpacing = step * 2.0D + PALE_ROOT_EXTRA_CHAIN_SPACING;

		final int[] tick = {0};
		final double[] nextArchDistance = {step};
		final int[] spawned = {0};
		this.mod.scheduler().timer(() -> {
			if (tick[0] >= 40 || player.isRemoved() || spawned[0] >= arches) {
				return;
			}
			Vec3 direction = currentRootDirection(player);
			double distance = (tick[0] + 1) * step;
			Vec3 probe = findRootLocation(level, anchor, direction, distance);
			Fx.itemParticles(level, probe, "PALE_OAK_WOOD", 5, 0.2D, 0.2D, 0.2D, 0.2D);
			Fx.sound(level, probe, "ENTITY_CREAKING_STEP", 0.8F, 1.0F);
			if (nextArchDistance[0] <= range && distance + 0.001D >= nextArchDistance[0]) {
				Vec3 rootAt = findRootLocation(level, anchor, direction, nextArchDistance[0]);
				float yaw = (float) Math.atan2(direction.x, direction.z);
				List<Display.BlockDisplay> model = spawnRootModel(level, rootAt, yaw);
				this.rootModels.put(UUID.randomUUID(), model);
				animateRootModel(model, yaw);
				Fx.sound(level, rootAt, "ENTITY_CREAKING_ATTACK", 1.0F, 1.0F);
				startRootHitScan(ctx, rootAt, model, alreadyHit);
				scheduleRootRemoval(ctx, rootAt, model);
				spawned[0]++;
				nextArchDistance[0] += chainSpacing;
			}
			tick[0]++;
		}, 0L, 1L).cancelAfter(45L);
	}

	/** {@code PaleCrossbowWeapon#getCurrentPaleRootDirection}. */
	Vec3 currentRootDirection(ServerPlayer player) {
		Vec3 look = player.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0.0D, look.z);
		if (flat.lengthSqr() < 0.001D) {
			flat = new Vec3(1.0D, 0.0D, 0.0D);
		}
		return flat.normalize();
	}

	/** {@code PaleCrossbowWeapon#findPaleRootLocation} - walks down to the first standable surface. */
	Vec3 findRootLocation(ServerLevel level, Vec3 anchor, Vec3 direction, double distance) {
		double x = anchor.x + direction.x * distance;
		double z = anchor.z + direction.z * distance;
		int top = Math.min(level.getMaxY() - 2, (int) Math.floor(anchor.y) + 3);
		int bottom = Math.max(level.getMinY() + 1, (int) Math.floor(anchor.y) - 5);
		for (int y = top; y >= bottom; y--) {
			BlockPos pos = BlockPos.containing(x, y, z);
			if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
					&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
					&& !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) {
				return new Vec3(x, y, z);
			}
		}
		return new Vec3(x, Math.max(level.getMinY() + 1,
				Math.min(level.getMaxY() - 2, (int) Math.floor(anchor.y))), z);
	}

	/** {@code PaleCrossbowWeapon#spawnPaleRootModel}. */
	private List<Display.BlockDisplay> spawnRootModel(ServerLevel level, Vec3 at, float yaw) {
		List<Display.BlockDisplay> pieces = new ArrayList<>(PALE_ROOT_PIECES.length);
		Vec3 base = at.add(0.0D, PALE_ROOT_VISUAL_Y_OFFSET, 0.0D);
		for (RootPiece piece : PALE_ROOT_PIECES) {
			Display.BlockDisplay display = Displays.block(level, base, Blocks.PALE_OAK_WOOD.defaultBlockState());
			Displays.bright(display);
			Displays.interpolate(display, 0, 1);
			applyPieceTransform(display, piece, yaw, rootPitch(0.0F));
			pieces.add(display);
		}
		return pieces;
	}

	/** {@code PaleCrossbowWeapon#animatePaleRootModel}. */
	private void animateRootModel(List<Display.BlockDisplay> model, float yaw) {
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			if (!modelAlive(model) || tick[0] > 10) {
				return;
			}
			for (int index = 0; index < model.size(); index++) {
				Display.BlockDisplay display = model.get(index);
				if (!display.isRemoved()) {
					Displays.interpolate(display, 0, 1);
					applyPieceTransform(display, PALE_ROOT_PIECES[index], yaw, rootPitch(tick[0]));
				}
			}
			tick[0]++;
		}, 0L, 1L).cancelAfter(14L);
	}

	/** {@code PaleCrossbowWeapon#setPaleRootPartTransform} - the original matrix chain. */
	private void applyPieceTransform(Display.BlockDisplay display, RootPiece piece, float yaw, float pitch) {
		Matrix4f matrix = new Matrix4f()
				.rotateY(yaw)
				.translate(0.0F, PALE_ROOT_ORIGIN_Y, 0.0F)
				.rotateX(pitch)
				.translate(0.0F, -PALE_ROOT_ORIGIN_Y, 0.0F)
				.translate(piece.pivotX, piece.pivotY, piece.pivotZ)
				.rotateXYZ(piece.rotationX, piece.rotationY, piece.rotationZ)
				.translate(piece.fromX - piece.pivotX, piece.fromY - piece.pivotY, piece.fromZ - piece.pivotZ)
				.scale(piece.sizeX, piece.sizeY, piece.sizeZ);
		display.setTransformation(new Transformation(matrix));
	}

	/** {@code PaleCrossbowWeapon#getPaleRootPitch} - piecewise-linear animation curve. */
	float rootPitch(float tick) {
		float degrees = PALE_ROOT_ANIMATION_DEGREES[PALE_ROOT_ANIMATION_DEGREES.length - 1];
		for (int index = 1; index < PALE_ROOT_ANIMATION_TICKS.length; index++) {
			if (tick <= PALE_ROOT_ANIMATION_TICKS[index]) {
				float fromTick = PALE_ROOT_ANIMATION_TICKS[index - 1];
				float toTick = PALE_ROOT_ANIMATION_TICKS[index];
				float progress = (tick - fromTick) / Math.max(0.001F, toTick - fromTick);
				float fromDegrees = PALE_ROOT_ANIMATION_DEGREES[index - 1];
				float toDegrees = PALE_ROOT_ANIMATION_DEGREES[index];
				degrees = fromDegrees + (toDegrees - fromDegrees) * progress;
				break;
			}
		}
		return (float) Math.toRadians(degrees - 180.0F);
	}

	private static boolean modelAlive(List<Display.BlockDisplay> model) {
		for (Display.BlockDisplay display : model) {
			if (!display.isRemoved()) {
				return true;
			}
		}
		return false;
	}

	/** {@code PaleCrossbowWeapon#startPaleRootHitScan}. */
	private void startRootHitScan(AbilityContext ctx, Vec3 at, List<Display.BlockDisplay> model, Set<UUID> alreadyHit) {
		final int[] tick = {0};
		int duration = paleRootsDurationTicks(ctx);
		this.mod.scheduler().timer(() -> {
			if (tick[0] >= duration || !modelAlive(model)) {
				return;
			}
			hitRootTargets(ctx, at, alreadyHit);
			tick[0]++;
		}, 0L, 1L).cancelAfter(duration + 5L);
	}

	/** {@code PaleCrossbowWeapon#hitPaleRootTargets}. */
	private void hitRootTargets(AbilityContext ctx, Vec3 at, Set<UUID> alreadyHit) {
		ServerLevel level = ctx.level();
		ServerPlayer player = ctx.player();
		Vec3 center = at.add(0.0D, 0.9D, 0.0D);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(BlockPos.containing(center)).inflate(1.5D), e -> e != player && e.isAlive())) {
			if (!alreadyHit.add(victim.getUUID())) {
				continue;
			}
			if (!ctx.abilityAllowedOn(victim instanceof ServerPlayer target ? target : null)) {
				continue;
			}
			TrueDamage.apply(victim, ctx.cfgd("abilities.palecrossbow.pale_roots.damage", 6.0D), player, true);
			if (victim instanceof ServerPlayer target) {
				applyPaleEffect(target);
			}
			Effects.apply(victim, "WITHER", 100, 1, false, true, true);
			Effects.apply(victim, "SLOWNESS", 100, 1, false, true, true);
			Motion.addVelocity(victim, new Vec3(0.0D, 0.8D, 0.0D));
			Vec3 victimCenter = victim.position().add(0.0D, 1.0D, 0.0D);
			Fx.itemParticles(level, victimCenter, "PALE_OAK_WOOD", 35, 0.5D, 0.8D, 0.5D, 0.2D);
			Fx.simple(level, "DAMAGE_INDICATOR", victimCenter, 12, 0.3D, 0.5D, 0.3D, 0.0D);
			Fx.sound(level, victimCenter, "ENCHANT_THORNS_HIT", 1.0F, 1.0F);
			startRootDot(ctx, victim);
		}
	}

	/** {@code PaleCrossbowWeapon#startPaleRootDot}. */
	private void startRootDot(AbilityContext ctx, LivingEntity victim) {
		int seconds = ctx.cfg("abilities.palecrossbow.pale_roots.dot_duration", 2);
		double damage = ctx.cfgd("abilities.palecrossbow.pale_roots.dot_damage", 1.0D);
		final int[] ticks = {0};
		this.mod.scheduler().timer(() -> {
			if (ticks[0] >= seconds || !victim.isAlive()) {
				return;
			}
			ticks[0]++;
			TrueDamage.apply(victim, damage, ctx.player(), true);
			Fx.itemParticles((ServerLevel) victim.level(), victim.position().add(0.0D, 1.0D, 0.0D),
					"PALE_OAK_WOOD", 8, 0.3D, 0.5D, 0.3D, 0.1D);
		}, 20L, 20L).cancelAfter(seconds * 20L + 5L);
	}

	/** {@code PaleCrossbowWeapon#schedulePaleRootRemoval}. */
	private void scheduleRootRemoval(AbilityContext ctx, Vec3 at, List<Display.BlockDisplay> model) {
		ServerLevel level = ctx.level();
		this.mod.scheduler().later(() -> {
			Fx.itemParticles(level, at.add(0.0D, 0.5D, 0.0D), "PALE_OAK_WOOD", 100, 0.5D, 0.5D, 0.5D, 0.3D);
			Fx.sound(level, at, "ENTITY_CREAKING_DEATH", 1.0F,
					0.9F + (float) ctx.random().nextDouble() * 0.2F);
			for (Display.BlockDisplay display : model) {
				Displays.remove(display);
			}
		}, paleRootsDurationTicks(ctx));
	}

	// ------------------------------------------------------------------ helpers

	/** {@code Particle.BLOCK_CRUMBLE} when this Minecraft build has it, else {@code BLOCK}. */
	@SuppressWarnings("unchecked")
	private void crumble(ServerLevel level, Vec3 pos, String material, int count,
			double dx, double dy, double dz, double speed) {
		net.minecraft.world.level.block.state.BlockState state = Fx.blockState(material);
		if (state == null) {
			return;
		}
		ParticleType<?> resolved = GameRegistry.particle("BLOCK_CRUMBLE");
		if (resolved != null) {
			Fx.send(level, new BlockParticleOption((ParticleType<BlockParticleOption>) resolved, state),
					pos, count, dx, dy, dz, speed);
			return;
		}
		Fx.blockParticles(level, material, pos, count, dx, dy, dz, speed);
	}

	public static void applyPaleEffect(ServerPlayer player) {
		Effects.apply(player, "LUCK", 72000, 1, false, false, true);
		player.addTag(TAG_PALE_EFFECT);
	}

	public static boolean hasPaleEffect(ServerPlayer player) {
		return player.getTags().contains(TAG_PALE_EFFECT);
	}

	public static void removePaleEffect(ServerPlayer player) {
		Effects.remove(player, "LUCK");
		player.removeTag(TAG_PALE_EFFECT);
	}

	public static void markKilledByCrossbow(ServerPlayer player) {
		player.addTag(TAG_KILLED_BY);
	}

	public static boolean wasKilledByCrossbow(ServerPlayer player) {
		return player.getTags().contains(TAG_KILLED_BY);
	}

	public static void clearKilledByCrossbow(ServerPlayer player) {
		player.removeTag(TAG_KILLED_BY);
	}

	@Override
	public void onKill(AbilityContext ctx, LivingEntity victim) {
		if (victim instanceof ServerPlayer killed) {
			markKilledByCrossbow(killed);
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		CooldownBars.hide(player, KEY_SHOOT);
		CooldownBars.hide(player, KEY_ROOTS);
		Displays.removeTagged(player.serverLevel(), "altarsmp_pale_shot");
		this.rootModels.values().forEach(model -> model.forEach(Displays::remove));
		this.rootModels.clear();
	}

	/** {@code PaleCrossbowWeapon.a} - one beam of the root model. */
	private static final class RootPiece {
		final float fromX;
		final float fromY;
		final float fromZ;
		final float sizeX;
		final float sizeY;
		final float sizeZ;
		final float pivotX;
		final float pivotY;
		final float pivotZ;
		final float rotationX;
		final float rotationY;
		final float rotationZ;

		RootPiece(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
				float pivotX, float pivotY, float pivotZ, float rotationX, float rotationY, float rotationZ) {
			this.fromX = fromX * PALE_ROOT_UNIT;
			this.fromY = fromY * PALE_ROOT_UNIT;
			this.fromZ = fromZ * PALE_ROOT_UNIT;
			this.sizeX = (toX - fromX) * PALE_ROOT_UNIT;
			this.sizeY = (toY - fromY) * PALE_ROOT_UNIT;
			this.sizeZ = (toZ - fromZ) * PALE_ROOT_UNIT;
			this.pivotX = pivotX * PALE_ROOT_UNIT;
			this.pivotY = pivotY * PALE_ROOT_UNIT;
			this.pivotZ = pivotZ * PALE_ROOT_UNIT;
			this.rotationX = (float) Math.toRadians(rotationX);
			this.rotationY = (float) Math.toRadians(rotationY);
			this.rotationZ = (float) Math.toRadians(rotationZ);
		}
	}
}
