package com.altarsmp.fabric.weapon.s1;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Striker - port of {@code com.altarsmp.weapons.StrikerWeapon}.
 *
 * <p><b>TNT Arrows</b> (passive): every arrow this bow launches detonates on
 * impact for {@code abilities.striker.tnt_arrow.damage} true damage inside
 * {@code tnt_arrow.radius}.</p>
 *
 * <p><b>Strike Shot</b> (F, {@code strike_shot.cooldown}s): calls down a red
 * column {@code strike_shot.column_height} blocks tall on the aimed point.
 * Everything in the {@code radius_xz} x {@code radius_y} box takes
 * {@code strike_shot.damage} true damage. Blocks are only carved when
 * {@code abilities.striker.carve_terrain} is true.</p>
 *
 * <p><b>Nuke Shot</b> (Shift+F): enabled by
 * {@code eclipse-revamp.striker-nuke-shot}; hands the target over to the same
 * nuclear strike system the Nuke Launcher uses, so the countdown, flash and
 * crater behave identically.</p>
 */
public final class StrikerWeapon implements WeaponBehavior {

	static final String KEY_STRIKE = "striker_strike_shot";
	private static final int STRIKE_RED = 0xFF2222;

	private final AltarSMPMod mod;

	public StrikerWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "striker";
	}

	@Override
	public String displayName() {
		return "Striker";
	}

	@Override
	public List<String> configFields() {
		return List.of("Strike Shot Cooldown (s)", "Strike Shot Damage", "Column Height", "TNT Arrow Damage");
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		useStrikeShot(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		if (!ctx.cfgb("eclipse-revamp.striker-nuke-shot", true)) {
			Messaging.send(ctx.player(), "<red>Nuke Shot is disabled by <yellow>eclipse-revamp.striker-nuke-shot</yellow>.");
			return;
		}
		Vec3 target = aimTarget(ctx, ctx.cfgd("abilities.nukelauncher.target_range", 200.0D));
		if (target == null) {
			Messaging.send(ctx.player(), "<red>No valid target in range for Nuke Shot.");
			return;
		}
		this.mod.nukeZone().requestStrike(ctx.player(), target, "striker");
	}

	@Override
	public void onProjectileHit(AbilityContext ctx, Entity projectile, Entity hit) {
		tntArrowDetonate(ctx, projectile.position());
	}

	private void tntArrowDetonate(AbilityContext ctx, Vec3 impact) {
		ServerLevel level = ctx.level();
		ServerPlayer player = ctx.player();
		double damage = ctx.cfgd("abilities.striker.tnt_arrow.damage", 3.0D);
		double radius = ctx.cfgd("abilities.striker.tnt_arrow.radius", 3.0D);
		Fx.sound(level, impact, "ENTITY_GENERIC_EXPLODE", 1.0F, 1.2F);
		Fx.simple(level, "EXPLOSION", impact, 3, radius * 0.3D, radius * 0.3D, radius * 0.3D, 0.0D);
		Fx.simple(level, "FLAME", impact, 20, radius * 0.3D, radius * 0.3D, radius * 0.3D, 0.08D);
		Fx.dust(level, impact, STRIKE_RED, 1.3F, 15, radius * 0.3D, radius * 0.3D, radius * 0.3D);
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(impact, impact).inflate(radius), e -> e != player && e.isAlive())) {
			TrueDamage.apply(entity, damage, player, true);
			Vec3 away = entity.position().subtract(impact).normalize().scale(0.6D);
			entity.setDeltaMovement(away.x, 0.35D, away.z);
			entity.hurtMarked = true;
		}
		if (ctx.canDestroyTerrain() && ctx.cfgb("abilities.striker.carve_terrain", false)) {
			BlockPos origin = BlockPos.containing(impact);
			for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-2, -2, -2), origin.offset(2, 2, 2))) {
				if (pos.distSqr(origin) <= 4 && level.getBlockState(pos).getDestroySpeed(level, pos) >= 0.0F) {
					level.removeBlock(pos, true);
				}
			}
		}
	}

	private void useStrikeShot(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.striker.strike_shot.cooldown", 60);
		if (!ctx.gate(KEY_STRIKE, "Strike Shot")) {
			return;
		}
		Vec3 target = aimTarget(ctx, 120.0D);
		if (target == null) {
			Messaging.send(player, "<red>Strike Shot needs a target - aim at the ground.");
			return;
		}
		ctx.startCooldown(KEY_STRIKE, cooldown);
		CooldownBars.show(player, KEY_STRIKE, "Strike Shot", BossEvent.BossBarColor.RED, cooldown);

		double damage = ctx.cfgd("abilities.striker.strike_shot.damage", 8.0D);
		double radiusXz = ctx.cfg("abilities.striker.strike_shot.radius_xz", 5);
		double radiusY = ctx.cfg("abilities.striker.strike_shot.radius_y", 150);
		double columnHeight = ctx.cfg("abilities.striker.strike_shot.column_height", 150);
		BlockPos column = BlockPos.containing(target);

		Messaging.actionBar(player, "<red><bold>STRIKE SHOT INBOUND!");
		Fx.sound(level, target, "custom/nuke_incoming", 1.0F, 1.0F);
		Fx.sound(level, target, "ENTITY_WITHER_SPAWN", 0.6F, 1.6F);

		// Warning column, then the strike.
		final int[] warned = {0};
		this.mod.scheduler().timer(() -> {
			warned[0]++;
			for (double y = 0.0D; y < columnHeight; y += 3.0D) {
				Vec3 point = new Vec3(column.getX() + 0.5D, column.getY() + y, column.getZ() + 0.5D);
				Fx.dust(level, point, STRIKE_RED, 1.4F, 2, 0.05D, 0.05D, 0.05D);
			}
			Fx.sound(level, target, "BLOCK_BEACON_AMBIENT", 0.8F, 1.8F - warned[0] * 0.1F);
		}, 0L, 4L).cancelAfter(40L);

		this.mod.scheduler().later(() -> detonateColumn(ctx, column, damage, radiusXz, radiusY), 40L);
	}

	private void detonateColumn(AbilityContext ctx, BlockPos column, double damage, double radiusXz, double radiusY) {
		ServerLevel level = ctx.level();
		ServerPlayer player = ctx.player();
		Vec3 impact = Vec3.atBottomCenterOf(column);
		Fx.sound(level, impact, "ENTITY_GENERIC_EXPLODE", 3.0F, 0.6F);
		Fx.sound(level, impact, "custom/nuke_explosion", 0.7F, 1.4F);
		Fx.lightning(level, impact, true);
		for (double y = 0.0D; y < radiusY; y += 2.0D) {
			Vec3 point = new Vec3(column.getX() + 0.5D, column.getY() + y, column.getZ() + 0.5D);
			Fx.dust(level, point, STRIKE_RED, 1.8F, 4, radiusXz * 0.1D, 0.1D, radiusXz * 0.1D);
			Fx.simple(level, "FLAME", point, 6, radiusXz * 0.2D, 0.2D, radiusXz * 0.2D, 0.05D);
			Fx.simple(level, "LAVA", point, 3, radiusXz * 0.2D, 0.2D, radiusXz * 0.2D, 0.0D);
		}
		Fx.simple(level, "EXPLOSION_EMITTER", impact, 4, radiusXz * 0.4D, 1.0D, radiusXz * 0.4D, 0.0D);

		AABB box = new AABB(column).inflate(radiusXz, radiusY, radiusXz);
		int hits = 0;
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != player && e.isAlive())) {
			TrueDamage.apply(entity, damage, player, true);
			entity.setRemainingFireTicks(100);
			Vec3 away = entity.position().subtract(impact);
			entity.setDeltaMovement(away.x * 0.2D, 0.6D, away.z * 0.2D);
			entity.hurtMarked = true;
			hits++;
		}
		if (ctx.canDestroyTerrain() && ctx.cfgb("abilities.striker.carve_terrain", false)) {
			BlockPos from = column.offset((int) -radiusXz, -3, (int) -radiusXz);
			BlockPos to = column.offset((int) radiusXz, 3, (int) radiusXz);
			for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
				if (level.getBlockState(pos).getDestroySpeed(level, pos) >= 0.0F) {
					level.removeBlock(pos, true);
				}
			}
		}
		Messaging.send(player, "<red>Strike Shot hit <white>" + hits + "</white> target(s).");
	}

	/** Raycast from the player's eyes, returning the block they are aiming at. */
	static Vec3 aimTarget(AbilityContext ctx, double maxDistance) {
		ServerLevel level = ctx.level();
		Vec3 start = ctx.player().getEyePosition();
		Vec3 direction = ctx.player().getLookAngle().normalize();
		Vec3 end = start.add(direction.scale(maxDistance));
		BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(start, end,
				net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE,
				ctx.player()));
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
			return null;
		}
		return hit.getLocation();
	}
}
