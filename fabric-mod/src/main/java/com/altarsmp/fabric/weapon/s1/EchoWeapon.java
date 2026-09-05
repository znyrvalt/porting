package com.altarsmp.fabric.weapon.s1;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Echo - port of {@code com.altarsmp.weapons.EchoWeapon}.
 *
 * <p><b>Sonic Blast</b> (F): after a short Warden-style charge the weapon itself
 * is thrown forward as a spinning item display travelling at
 * {@code abilities.echo.sonic_blast.speed}. On impact it carves a
 * {@code crater_radius} x {@code crater_depth} crater (only when
 * {@code abilities.terrain_destruction} is on), deals
 * {@code sonic_blast.damage} true damage and hurls everything nearby.
 * Cooldown {@code sonic_blast.cooldown}s.</p>
 *
 * <p><b>Echo Shriek</b> (Shift+F): a shrieker blast that gives every entity in
 * range Slowness II, Darkness II and Glowing for
 * {@code abilities.echo.shriek.effect_duration} ticks. Cooldown
 * {@code shriek.cooldown}s.</p>
 */
public final class EchoWeapon implements WeaponBehavior {

	static final String KEY_BLAST = "echo_blast";
	static final String KEY_SHRIEK = "echo_shriek";
	private static final double SHRIEK_RADIUS = 12.0D;

	private final AltarSMPMod mod;

	public EchoWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "echo";
	}

	@Override
	public String displayName() {
		return "Echo";
	}

	@Override
	public List<String> configFields() {
		return List.of("Sonic Blast Cooldown (s)", "Echo Shriek Cooldown (s)", "Crater Radius", "Blast Damage");
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		useSonicBlast(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		useEchoShriek(ctx);
	}

	// ------------------------------------------------------------- sonic blast

	private void useSonicBlast(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.echo.sonic_blast.cooldown", 45);
		if (!ctx.gate(KEY_BLAST, "Sonic Blast")) {
			return;
		}
		ctx.startCooldown(KEY_BLAST, cooldown);
		CooldownBars.show(player, KEY_BLAST, "Sonic Blast", BossEvent.BossBarColor.BLUE, cooldown);

		Vec3 chargePos = player.getEyePosition();
		Fx.sound(level, chargePos, "ENTITY_WARDEN_SONIC_CHARGE", 2.0F, 1.0F);
		Fx.simple(level, "SOUL_FIRE_FLAME", chargePos, 8, 0.0D, 0.0D, 0.0D, 0.2D);
		Fx.simple(level, "SCULK_SOUL", chargePos, 6, 0.2D, 0.2D, 0.2D, 0.01D);

		this.mod.scheduler().later(() -> {
			if (player.isRemoved()) {
				return;
			}
			fireSonicBlast(ctx);
		}, 30L);
	}

	private void fireSonicBlast(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		double speed = ctx.cfgd("abilities.echo.sonic_blast.speed", 2.0D);
		double damage = ctx.cfgd("abilities.echo.sonic_blast.damage", 10.0D);
		int craterRadius = ctx.cfg("abilities.echo.sonic_blast.crater_radius", 6);
		int craterDepth = ctx.cfg("abilities.echo.sonic_blast.crater_depth", 6);
		double maxDistance = ctx.cfgd("abilities.echo.sonic_blast.max_distance", 60.0D);

		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();
		Display.ItemDisplay projectile = Displays.item(level, start, create(player), 1.0F);
		Displays.bright(projectile);
		Fx.sound(level, start, "ENTITY_WARDEN_SONIC_BOOM", 1.0F, 1.0F);

		final double[] travelled = {0.0D};
		final float[] spin = {0.0F};
		this.mod.scheduler().timer(() -> {
			travelled[0] += speed;
			Vec3 head = start.add(direction.scale(travelled[0]));
			projectile.snapTo(head.x, head.y, head.z, 0.0F, 0.0F);
			spin[0] += 25.0F;
			Displays.rotate(projectile, spin[0], 1.0F);
			Fx.simple(level, "SONIC_BOOM", head, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			Fx.simple(level, "SCULK_CHARGE_POP", head, 2, 0.1D, 0.1D, 0.1D, 0.0D);

			boolean hitBlock = !level.getBlockState(BlockPos.containing(head)).getCollisionShape(level, BlockPos.containing(head)).isEmpty();
			List<LivingEntity> struck = level.getEntitiesOfClass(LivingEntity.class,
					new AABB(head, head).inflate(1.6D), entity -> entity != player && entity.isAlive());
			if (hitBlock || !struck.isEmpty() || travelled[0] >= maxDistance) {
				Displays.remove(projectile);
				sonicCrater(ctx, head, damage, craterRadius, craterDepth);
				for (LivingEntity entity : struck) {
					TrueDamage.apply(entity, damage, player, false);
				}
			}
		}, 0L, 1L).cancelAfter((long) (maxDistance / speed) + 4L);
	}

	private void sonicCrater(AbilityContext ctx, Vec3 center, double damage, int radius, int depth) {
		ServerLevel level = ctx.level();
		ServerPlayer player = ctx.player();
		Fx.sound(level, center, "ENTITY_WARDEN_SONIC_BOOM", 2.0F, 0.5F);
		Fx.sound(level, center, "ENTITY_GENERIC_EXPLODE", 2.0F, 0.6F);
		Fx.simple(level, "EXPLOSION_EMITTER", center, 3, 1.0D, 0.5D, 1.0D, 0.0D);
		Fx.simple(level, "SONIC_BOOM", center, 2, 2.0D, 0.3D, 2.0D, 0.0D);
		Fx.simple(level, "SOUL_FIRE_FLAME", center, 30, radius * 0.4D, 0.3D, radius * 0.4D, 0.08D);

		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center, center).inflate(radius + 2.0D), e -> e != player && e.isAlive())) {
			TrueDamage.apply(entity, damage, player, false);
			Vec3 away = entity.position().subtract(center).normalize();
			entity.setDeltaMovement(away.x * 1.4D, 0.7D, away.z * 1.4D);
			entity.hurtMarked = true;
		}

		if (!ctx.canDestroyTerrain()) {
			return;
		}
		BlockPos origin = BlockPos.containing(center);
		int removed = 0;
		for (int y = 0; y >= -depth; y--) {
			double layerRadius = radius * (1.0D - Math.abs(y) / (double) (depth + 1));
			for (int x = (int) -Math.ceil(layerRadius); x <= Math.ceil(layerRadius); x++) {
				for (int z = (int) -Math.ceil(layerRadius); z <= Math.ceil(layerRadius); z++) {
					if (x * x + z * z > layerRadius * layerRadius) {
						continue;
					}
					BlockPos pos = origin.offset(x, y, z);
					if (level.getBlockState(pos).is(Blocks.BEDROCK) || level.getBlockState(pos).isAir()) {
						continue;
					}
					if (level.getBlockState(pos).getDestroySpeed(level, pos) < 0.0F) {
						continue;
					}
					level.removeBlock(pos, false);
					removed++;
				}
			}
		}
		if (removed > 0) {
			Messaging.send(player, "<aqua>Sonic Blast carved out <white>" + removed + "</white> blocks.");
		}
	}

	// ------------------------------------------------------------- echo shriek

	private void useEchoShriek(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.echo.shriek.cooldown", 60);
		int duration = ctx.cfg("abilities.echo.shriek.effect_duration", 200);
		double radius = ctx.cfgd("abilities.echo.shriek.radius", SHRIEK_RADIUS);
		if (!ctx.gate(KEY_SHRIEK, "Echo Shriek")) {
			return;
		}
		ctx.startCooldown(KEY_SHRIEK, cooldown);
		CooldownBars.show(player, KEY_SHRIEK, "Echo Shriek", BossEvent.BossBarColor.PURPLE, cooldown);

		Vec3 center = player.position();
		Fx.sound(level, center, "BLOCK_SCULK_SHRIEKER_SHRIEK", 2.0F, 1.0F);
		Fx.sound(level, center, "ENTITY_WARDEN_AMBIENT", 0.8F, 0.5F);
		Fx.simple(level, "SCULK_SOUL", center.add(0.0D, 1.0D, 0.0D), 40, radius * 0.4D, 0.4D, radius * 0.4D, 0.05D);
		Fx.simple(level, "SONIC_BOOM", center.add(0.0D, 1.0D, 0.0D), 3, radius * 0.2D, 0.2D, radius * 0.2D, 0.0D);

		int affected = 0;
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center, center).inflate(radius), e -> e != player && e.isAlive())) {
			Effects.apply(entity, "SLOWNESS", duration, 1, false, false, false);
			Effects.apply(entity, "DARKNESS", duration, 1, false, false, false);
			Effects.apply(entity, "GLOWING", duration, 0, false, false, false);
			Vec3 away = entity.position().subtract(center).normalize();
			entity.setDeltaMovement(away.x * -0.5D, 0.1D, away.z * -0.5D);
			entity.hurtMarked = true;
			Fx.simple(level, "SCULK_CHARGE_POP", entity.position().add(0.0D, 1.0D, 0.0D), 6, 0.2D, 0.2D, 0.2D, 0.0D);
			affected++;
		}
		Messaging.actionBar(player, "<aqua>Echo Shriek hit <white>" + affected + "</white> target(s)");
	}
}
