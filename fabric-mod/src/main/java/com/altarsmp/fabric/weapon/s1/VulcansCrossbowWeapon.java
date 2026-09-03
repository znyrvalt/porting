package com.altarsmp.fabric.weapon.s1;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Vulcan's Crossbow - port of {@code com.altarsmp.weapons.VulcansCrossbowWeapon}.
 *
 * <p><b>Passive</b>: anyone carrying the crossbow keeps Fire Resistance
 * ({@code fire_resistance_level}, 40-tick refresh).</p>
 *
 * <p><b>Triple Shot</b> (passive on every shot): the centre arrow is set alight
 * for 1000 ticks, pierces {@code middle_arrow_pierce} entities and ignores
 * shields; two extra arrows are fired 5 degrees either side with the same stats.
 * On impact the vanilla damage is replaced by {@code middle_arrow_damage} (3.0)
 * for the centre arrow or {@code side_arrow_damage} (2.0) for the sides, and the
 * centre arrow ignites its victim for {@code middle_arrow_ignite_ticks}.</p>
 *
 * <p><b>Vulcan's Wrath</b> (F charges it, the next shot releases it): a
 * 1.35-scale glowing magma block flies at 2.0 blocks/tick with an 0.8 hitbox,
 * sub-stepping its collision check, then detonates for
 * {@code wrath_damage} (34.0) true damage inside {@code wrath_radius} and carves
 * a {@code crater_radius} crater of magma and lava that reverts after
 * {@code crater_revert_time} seconds. The shooter takes
 * {@code wrath_recoil_strength} of backwards recoil. Charging costs
 * {@code wrath_cooldown} seconds.</p>
 */
public final class VulcansCrossbowWeapon implements WeaponBehavior {

	static final String KEY_WRATH = "vulcans_wrath";
	private static final float WRATH_SCALE = 1.35F;
	private static final double WRATH_SPEED = 2.0D;
	private static final double WRATH_HITBOX = 0.8D;
	private static final int WRATH_MAX_TICKS = 500;
	private static final double SIDE_SPREAD_DEGREES = 5.0D;
	private static final long ARROW_TRACKING_TICKS = 200L;

	private final AltarSMPMod mod;
	private final Set<UUID> wrathReady = new HashSet<>();
	private final Map<UUID, Boolean> trackedArrows = new HashMap<>();
	private final Map<UUID, Long> arrowExpiry = new HashMap<>();

	public VulcansCrossbowWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "vulcanscrossbow";
	}

	@Override
	public String displayName() {
		return "Vulcan's Crossbow";
	}

	@Override
	public List<String> configFields() {
		return List.of("Wrath Cooldown (s)", "Wrath Damage", "Wrath Radius (blocks)", "Crater Radius (blocks)",
				"Crater Revert Time (s)", "Middle Arrow Damage", "Side Arrow Damage", "Middle Arrow Pierce",
				"Fire Resistance Level");
	}

	int wrathCooldown(AbilityContext ctx) {
		return ctx.cfg("abilities.vulcanscrossbow.wrath_cooldown", 45);
	}

	double wrathDamage(AbilityContext ctx) {
		return ctx.cfgd("abilities.vulcanscrossbow.wrath_damage", 34.0D);
	}

	int craterRevertTicks(AbilityContext ctx) {
		return ctx.cfg("abilities.vulcanscrossbow.crater_revert_time", 60) * 20;
	}

	// ------------------------------------------------------------------ passive

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		long tick = this.mod.scheduler().currentTick();
		if (tick % 20L == 0L) {
			purgeExpiredArrows(tick);
			if (carriesWeapon(player)) {
				Effects.apply(player, "FIRE_RESISTANCE", 40,
						ctx.cfg("abilities.vulcanscrossbow.fire_resistance_level", 0), false, false, false);
			}
		}
		if (tick % 4L == 0L && this.wrathReady.contains(player.getUUID())) {
			Fx.simple(ctx.level(), "FLAME", player.position().add(0.0D, 1.0D, 0.0D), 4, 0.25D, 0.35D, 0.25D, 0.02D);
		}
	}

	static boolean carriesWeapon(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (Identity.is(inventory.getItem(slot), "vulcanscrossbow")) {
				return true;
			}
		}
		return Identity.is(inventory.offhand.get(0), "vulcanscrossbow");
	}

	private void purgeExpiredArrows(long tick) {
		this.arrowExpiry.entrySet().removeIf(entry -> {
			if (entry.getValue() <= tick) {
				this.trackedArrows.remove(entry.getKey());
				return true;
			}
			return false;
		});
	}

	// ------------------------------------------------------------------- wrath

	@Override
	public void onPrimary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (this.wrathReady.contains(player.getUUID())) {
			Messaging.actionBar(player, "<gold>Vulcan's Wrath is charged - fire to release.");
			return;
		}
		if (!ctx.gate(KEY_WRATH, "Vulcan's Wrath")) {
			return;
		}
		ctx.startCooldown(KEY_WRATH, wrathCooldown(ctx));
		this.wrathReady.add(player.getUUID());
		CooldownBars.show(player, KEY_WRATH, "Vulcan's Wrath", BossEvent.BossBarColor.RED, wrathCooldown(ctx));
		Fx.sound(ctx.level(), player.position(), "ITEM_CROSSBOW_SHOOT", 1.0F, 0.5F);
		Fx.sound(ctx.level(), player.position(), "ENTITY_WARDEN_SONIC_BOOM", 1.3F, 1.0F);
		Fx.sound(ctx.level(), player.position(), "ENTITY_BLAZE_SHOOT", 2.0F, 0.5F);
		Messaging.actionBar(player, "<gold><bold>VULCAN'S WRATH CHARGED - loose a shot!");
	}

	@Override
	public void onProjectileLaunch(AbilityContext ctx, net.minecraft.world.entity.Entity projectile) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		if (!(projectile instanceof AbstractArrow arrow)) {
			return;
		}
		if (this.wrathReady.remove(player.getUUID())) {
			unchargeCrossbow(player);
			double recoil = ctx.cfgd("abilities.vulcanscrossbow.wrath_recoil_strength", 1.0D);
			Vec3 look = player.getLookAngle().normalize();
			Motion.setVelocity(player, new Vec3(-look.x * recoil, 0.4D, -look.z * recoil));
			fireWrathProjectile(ctx);
			Fx.sound(level, player.position(), "ITEM_CROSSBOW_SHOOT", 1.0F, 0.5F);
			Fx.sound(level, player.position(), "ENTITY_WARDEN_SONIC_BOOM", 1.3F, 1.0F);
			Fx.sound(level, player.position(), "ENTITY_BLAZE_SHOOT", 1.4F, 0.8F);
			arrow.discard();
			return;
		}
		prepareTripleShot(ctx, arrow);
	}

	/** Clears the charged projectiles so the shot does not also fire normally. */
	private void unchargeCrossbow(ServerPlayer player) {
		ItemStack crossbow = player.getMainHandItem();
		if (crossbow.is(net.minecraft.world.item.Items.CROSSBOW)) {
			crossbow.remove(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES);
		}
	}

	/** {@code VulcansCrossbowWeapon#onBowShoot} - the non-wrath branch. */
	private void prepareTripleShot(AbilityContext ctx, AbstractArrow middle) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		middle.setRemainingFireTicks(1000);
		middle.pickup = AbstractArrow.Pickup.DISALLOWED;
		middle.setPierceLevel((byte) ctx.cfg("abilities.vulcanscrossbow.middle_arrow_pierce", 3));
		track(middle.getUUID(), true);
		startArrowTrail(level, middle, true);

		Vec3 direction = middle.getDeltaMovement().normalize();
		double speed = middle.getDeltaMovement().length();
		shootSideArrow(ctx, rotateAroundY(direction, Math.toRadians(-SIDE_SPREAD_DEGREES)), speed, middle);
		shootSideArrow(ctx, rotateAroundY(direction, Math.toRadians(SIDE_SPREAD_DEGREES)), speed, middle);

		Fx.sound(level, player.position(), "ENTITY_BLAZE_SHOOT", 1.0F, 0.8F);
		Fx.sound(level, player.position(), "ENTITY_WARDEN_SONIC_BOOM", 0.7F, 1.5F);
		Fx.sound(level, player.position(), "ENTITY_GENERIC_EXTINGUISH_FIRE", 0.8F, 1.0F);
		Fx.sound(level, player.position(), "ITEM_CROSSBOW_SHOOT", 1.0F, 0.5F);
		if (ctx.cfgb("abilities.vulcanscrossbow.fire_cooldown_enabled", false)) {
			player.getCooldowns().addCooldown(net.minecraft.world.item.Items.CROSSBOW,
					ctx.cfg("abilities.vulcanscrossbow.fire_cooldown_ticks", 100));
		}
	}

	/** {@code VulcansCrossbowWeapon#shootArrowWithStats}. */
	private void shootSideArrow(AbilityContext ctx, Vec3 direction, double speed, AbstractArrow template) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		Arrow side = net.minecraft.world.entity.EntityType.ARROW.create(level);
		if (side == null) {
			return;
		}
		Vec3 origin = player.getEyePosition().add(direction.scale(0.5D));
		side.snapTo(origin.x, origin.y, origin.z, player.getYRot(), player.getXRot());
		side.setOwner(player);
		side.setBaseDamage(template.getBaseDamage());
		side.setCritArrow(template.isCritArrow());
		side.setRemainingFireTicks(template.getRemainingFireTicks());
		side.setPierceLevel(template.getPierceLevel());
		side.pickup = AbstractArrow.Pickup.DISALLOWED;
		side.setDeltaMovement(direction.scale(speed));
		level.addFreshEntity(side);
		track(side.getUUID(), false);
		startArrowTrail(level, side, false);
	}

	private void track(UUID arrow, boolean isMiddle) {
		this.trackedArrows.put(arrow, isMiddle);
		this.arrowExpiry.put(arrow, this.mod.scheduler().currentTick() + ARROW_TRACKING_TICKS);
	}

	/** {@code VulcansCrossbowWeapon#startVulcanArrowTrail}. */
	private void startArrowTrail(ServerLevel level, AbstractArrow arrow, boolean middle) {
		this.mod.scheduler().timer(() -> {
			if (arrow.isRemoved() || arrow.getDeltaMovement().lengthSqr() < 1.0E-6D) {
				return;
			}
			Vec3 pos = arrow.position();
			if (middle) {
				Fx.simple(level, "FLAME", pos, 10, 0.1D, 0.1D, 0.1D, 0.08D);
				Fx.simple(level, "CRIT", pos, 20, 0.15D, 0.15D, 0.15D, 0.5D);
			} else {
				Fx.simple(level, "SOUL_FIRE_FLAME", pos, 2, 0.05D, 0.05D, 0.05D, 0.01D);
			}
		}, 0L, 1L).cancelAfter(ARROW_TRACKING_TICKS);
	}

	static Vec3 rotateAroundY(Vec3 vector, double radians) {
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		return new Vec3(vector.x * cos - vector.z * sin, vector.y, vector.x * sin + vector.z * cos);
	}

	// ------------------------------------------------------------ wrath projectile

	/** {@code VulcansCrossbowWeapon#fireWrathProjectile}. */
	private void fireWrathProjectile(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition().add(direction.scale(0.75D));
		Display.BlockDisplay magma = Displays.block(level, start, Blocks.MAGMA_BLOCK.defaultBlockState());
		Displays.bright(magma);
		Displays.setScale(magma, WRATH_SCALE);
		Displays.teleportInterpolation(magma, 1);
		Displays.tag(magma, "altarsmp_vulcans_wrath");

		Vec3 velocity = direction.scale(WRATH_SPEED);
		final int[] age = {0};
		this.mod.scheduler().timer(() -> {
			if (magma.isRemoved() || age[0]++ >= WRATH_MAX_TICKS) {
				Displays.remove(magma);
				return;
			}
			Vec3 from = magma.position();
			int steps = Math.max(1, (int) Math.ceil(velocity.length() / 0.25D));
			for (int step = 1; step <= steps; step++) {
				Vec3 probe = from.add(velocity.scale((double) step / steps));
				for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
						new AABB(probe, probe).inflate(WRATH_HITBOX), e -> e != player && e.isAlive())) {
					if (!ctx.abilityAllowedOn(victim instanceof ServerPlayer target ? target : null)) {
						continue;
					}
					impact(ctx, probe);
					return;
				}
				if (!level.getBlockState(BlockPos.containing(probe)).isAir()) {
					impact(ctx, probe);
					return;
				}
			}
			Vec3 next = from.add(velocity);
			magma.setPos(next);
			Fx.simple(level, "FLAME", next, 2, 0.08D, 0.08D, 0.08D, 0.05D);
		}, 0L, 1L).cancelAfter(WRATH_MAX_TICKS + 5L);
	}

	/** {@code VulcansCrossbowWeapon#handleWrathImpact}. */
	void impact(AbilityContext ctx, Vec3 point) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		Displays.removeTagged(level, "altarsmp_vulcans_wrath");
		Fx.simple(level, "EXPLOSION_EMITTER", point, 3, 0.5D, 0.5D, 0.5D, 0.0D);
		Fx.simple(level, "FLAME", point, 80, 2.0D, 2.0D, 2.0D, 0.15D);
		Fx.simple(level, "SOUL_FIRE_FLAME", point, 50, 1.5D, 1.5D, 1.5D, 0.1D);
		Fx.simple(level, "SMOKE", point, 70, 2.0D, 2.0D, 2.0D, 0.1D);
		Fx.dust(level, point, 0xFF6400, 2.0F, 40, 2.0D, 2.0D, 2.0D);
		Fx.sound(level, point, "ENTITY_GENERIC_EXPLODE", 2.0F, 0.7F);

		double radius = ctx.cfgd("abilities.vulcanscrossbow.wrath_radius", 5.0D);
		double damage = wrathDamage(ctx);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(BlockPos.containing(point)).inflate(radius), e -> e != player && e.isAlive())) {
			if (!ctx.abilityAllowedOn(victim instanceof ServerPlayer target ? target : null)) {
				continue;
			}
			TrueDamage.apply(victim, damage, player, true);
			Fx.simple(level, "FLAME", victim.position().add(0.0D, 1.0D, 0.0D), 20, 0.4D, 0.6D, 0.4D, 0.1D);
		}
		makeCrater(ctx, point, ctx.cfg("abilities.vulcanscrossbow.crater_radius", 5));
	}

	/** {@code VulcansCrossbowWeapon#makeCrater} - magma/lava pit that reverts later. */
	private void makeCrater(AbilityContext ctx, Vec3 center, int radius) {
		ServerLevel level = ctx.level();
		BlockPos origin = BlockPos.containing(center);
		Map<BlockPos, BlockState> original = new HashMap<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -3; dy <= 2; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
					double limit = radius - ctx.random().nextDouble() * 1.5D;
					if (distance > limit) {
						continue;
					}
					BlockPos pos = origin.offset(dx, dy, dz);
					BlockState state = level.getBlockState(pos);
					if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
						continue;
					}
					original.put(pos, state);
					if (dy < 0) {
						level.setBlock(pos, ctx.random().nextDouble() < 0.75D
								? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.LAVA.defaultBlockState(), 3);
					} else if (dy == 0 && distance < limit - 1.0D) {
						level.setBlock(pos, ctx.random().nextDouble() < 0.3D
								? Blocks.LAVA.defaultBlockState() : Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
					} else {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
		Fx.sound(level, center, "ENTITY_GENERIC_EXPLODE", 3.0F, 0.8F);
		Fx.sound(level, center, "BLOCK_LAVA_POP", 2.0F, 0.5F);
		Fx.sound(level, center, "BLOCK_LAVA_AMBIENT", 1.0F, 1.0F);
		Fx.simple(level, "SMOKE", center, 50, 2.0D, 2.0D, 2.0D, 0.05D);
		Fx.simple(level, "FLAME", center, 30, 1.5D, 1.0D, 1.5D, 0.05D);
		Fx.simple(level, "LAVA", center, 40, 2.0D, 2.0D, 2.0D, 0.02D);

		int revert = craterRevertTicks(ctx);
		this.mod.scheduler().later(() -> {
			for (Map.Entry<BlockPos, BlockState> entry : original.entrySet()) {
				level.setBlock(entry.getKey(), entry.getValue(), 3);
			}
			Fx.sound(level, center, "BLOCK_FIRE_EXTINGUISH", 1.2F, 0.8F);
		}, revert);
	}

	// ------------------------------------------------------------------ impacts

	@Override
	public void onProjectileHit(AbilityContext ctx, net.minecraft.world.entity.Entity projectile,
			@javax.annotation.Nullable net.minecraft.world.entity.Entity hit) {
		if (!(projectile instanceof AbstractArrow arrow) || !(hit instanceof LivingEntity victim)) {
			return;
		}
		Boolean isMiddle = this.trackedArrows.remove(arrow.getUUID());
		this.arrowExpiry.remove(arrow.getUUID());
		if (isMiddle == null) {
			return;
		}
		if (!ctx.abilityAllowedOn(victim instanceof ServerPlayer target ? target : null)) {
			return;
		}
		double damage = isMiddle
				? ctx.cfgd("abilities.vulcanscrossbow.middle_arrow_damage", 3.0D)
				: ctx.cfgd("abilities.vulcanscrossbow.side_arrow_damage", 2.0D);
		TrueDamage.apply(victim, damage, ctx.player(), true);
		if (isMiddle) {
			victim.setRemainingFireTicks(ctx.cfg("abilities.vulcanscrossbow.middle_arrow_ignite_ticks", 100));
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		this.wrathReady.remove(player.getUUID());
		CooldownBars.hide(player, KEY_WRATH);
		Displays.removeTagged(player.serverLevel(), "altarsmp_vulcans_wrath");
	}
}
