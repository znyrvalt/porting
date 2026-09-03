package com.altarsmp.fabric.weapon.s1;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Pure Blade - port of {@code com.altarsmp.weapons.PureBladeWeapon}.
 *
 * <p><b>Shade Soul</b> (F, {@code abilities.pureblade.shade_soul_cooldown}s):
 * fires a soul projectile at {@code shade_soul_velocity} for up to
 * {@code shade_soul_max_ticks}; on impact everything inside
 * {@code shade_soul_hit_radius} takes {@code shade_soul_damage} true damage and
 * Slowness {@code shade_soul_slowness_level} for {@code soul_slow_duration}
 * ticks.</p>
 *
 * <p><b>Cyclone</b> (Shift+F, {@code cyclone_cooldown}s): the holder spins for
 * {@code cyclone_duration} ticks with Speed {@code cyclone_speed_level}, hitting
 * everything inside {@code cyclone_radius} for {@code cyclone_damage} every
 * {@code cyclone_hit_interval} ticks and shredding cobwebs in
 * {@code cyclone_cobweb_radius}.</p>
 */
public final class PureBladeWeapon implements WeaponBehavior {

	static final String KEY_SOUL = "pureblade_shade_soul";
	static final String KEY_CYCLONE = "pureblade_cyclone";
	private static final int SOUL_TEAL = (0x2E << 16) | (0xE0 << 8) | 0xC8;
	private static final int PURE_WHITE = 0xFFFFFF;

	private final AltarSMPMod mod;

	public PureBladeWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "pureblade";
	}

	@Override
	public String displayName() {
		return "Pure Blade";
	}

	@Override
	public List<String> configFields() {
		return List.of("Shade Soul Cooldown (s)", "Cyclone Cooldown (s)", "Shade Soul Damage", "Cyclone Damage");
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		shadeSoul(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		cyclone(ctx);
	}

	// --------------------------------------------------------------- shade soul

	private void shadeSoul(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.pureblade.shade_soul_cooldown", 30);
		if (!ctx.gate(KEY_SOUL, "Shade Soul")) {
			return;
		}
		ctx.startCooldown(KEY_SOUL, cooldown);
		CooldownBars.show(player, KEY_SOUL, "Shade Soul", BossEvent.BossBarColor.BLUE, cooldown);

		double velocity = ctx.cfgd("abilities.pureblade.shade_soul_velocity", 2.5D);
		double damage = ctx.cfgd("abilities.pureblade.shade_soul_damage", 6.0D);
		int maxTicks = ctx.cfg("abilities.pureblade.shade_soul_max_ticks", 60);
		double hitRadius = ctx.cfgd("abilities.pureblade.shade_soul_hit_radius", 3.0D);
		int slowLevel = ctx.cfg("abilities.pureblade.shade_soul_slowness_level", 1);
		int slowDuration = ctx.cfg("abilities.pureblade.soul_slow_duration", 100);

		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();
		Display.ItemDisplay soul = Displays.item(level, start, create(player), 0.5F);
		Displays.bright(soul);
		Fx.sound(level, start, "ENTITY_ALLAY_ITEM_TAKEN", 1.0F, 0.7F);
		Fx.sound(level, start, "BLOCK_SCULK_SPREAD", 0.8F, 1.2F);

		final double[] travelled = {0.0D};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				Displays.remove(soul);
				return;
			}
			travelled[0] += velocity;
			Vec3 head = start.add(direction.scale(travelled[0]));
			soul.snapTo(head.x, head.y, head.z, 0.0F, 0.0F);
			Fx.dust(level, head, SOUL_TEAL, 1.0F, 4, 0.1D, 0.1D, 0.1D);
			Fx.simple(level, "SCULK_SOUL", head, 2, 0.1D, 0.1D, 0.1D, 0.01D);

			BlockPos at = BlockPos.containing(head);
			boolean hitBlock = !level.getBlockState(at).getCollisionShape(level, at).isEmpty();
			List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class,
					new AABB(head, head).inflate(hitRadius), e -> e != player && e.isAlive());
			if (hitBlock || !near.isEmpty() || travelled[0] >= velocity * maxTicks) {
				Displays.remove(soul);
				Fx.sound(level, head, "ENTITY_WARDEN_HEARTBEAT", 0.9F, 1.3F);
				Fx.dust(level, head, SOUL_TEAL, 1.8F, 40, hitRadius * 0.3D, hitRadius * 0.3D, hitRadius * 0.3D);
				Fx.simple(level, "SOUL", head, 20, hitRadius * 0.3D, hitRadius * 0.3D, hitRadius * 0.3D, 0.05D);
				for (LivingEntity entity : near) {
					TrueDamage.apply(entity, damage, player, false);
					Effects.apply(entity, "SLOWNESS", slowDuration, slowLevel);
					Vec3 away = entity.position().subtract(head).normalize().scale(0.5D);
					entity.setDeltaMovement(away.x, 0.25D, away.z);
					entity.hurtMarked = true;
				}
			}
		}, 0L, 1L).cancelAfter(maxTicks + 2L);
	}

	// ------------------------------------------------------------------ cyclone

	private void cyclone(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.pureblade.cyclone_cooldown", 60);
		if (!ctx.gate(KEY_CYCLONE, "Cyclone")) {
			return;
		}
		ctx.startCooldown(KEY_CYCLONE, cooldown);
		CooldownBars.show(player, KEY_CYCLONE, "Cyclone", BossEvent.BossBarColor.WHITE, cooldown);

		int duration = ctx.cfg("abilities.pureblade.cyclone_duration", 30);
		double radius = ctx.cfg("abilities.pureblade.cyclone_radius", 3);
		double damage = ctx.cfgd("abilities.pureblade.cyclone_damage", 1.8D);
		int hitInterval = Math.max(1, ctx.cfg("abilities.pureblade.cyclone_hit_interval", 6));
		int cobwebRadius = ctx.cfg("abilities.pureblade.cyclone_cobweb_radius", 3);

		Effects.apply(player, "SPEED", duration, ctx.cfg("abilities.pureblade.cyclone_speed_level", 9), false, false, false);
		Motion.recordDash(player);
		Fx.sound(level, player.position(), "ENTITY_BREEZE_WIND_BURST", 1.0F, 1.2F);
		Messaging.actionBar(player, "<white><bold>CYCLONE!");

		final int[] hits = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			Vec3 center = player.position().add(0.0D, 1.0D, 0.0D);
			for (int i = 0; i < 12; i++) {
				double angle = Math.toRadians(30.0D * i + this.mod.scheduler().currentTick() * 24.0D);
				Vec3 point = center.add(Math.cos(angle) * radius * 0.7D, Math.sin(angle * 0.5D) * 0.4D, Math.sin(angle) * radius * 0.7D);
				Fx.dust(level, point, PURE_WHITE, 1.0F, 1, 0.05D, 0.05D, 0.05D);
				Fx.simple(level, "GUST", point, 1, 0.1D, 0.1D, 0.1D, 0.2D);
			}
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
					player.getBoundingBox().inflate(radius), e -> e != player && e.isAlive())) {
				TrueDamage.apply(entity, damage, player, true);
				Vec3 away = entity.position().subtract(player.position()).normalize().scale(0.45D);
				entity.setDeltaMovement(away.x, 0.3D, away.z);
				entity.hurtMarked = true;
				hits[0]++;
			}
			Fx.sound(level, player.position(), "ENTITY_PLAYER_ATTACK_SWEEP", 0.8F, 1.4F);
		}, 0L, hitInterval).cancelAfter(duration);

		// Cobwebs in the spin radius are shredded (the plugin cleared them too).
		if (ctx.canDestroyTerrain()) {
			BlockPos origin = BlockPos.containing(player.position());
			for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-cobwebRadius, -cobwebRadius, -cobwebRadius),
					origin.offset(cobwebRadius, cobwebRadius, cobwebRadius))) {
				if (level.getBlockState(pos).is(Blocks.COBWEB)) {
					level.removeBlock(pos, false);
					Fx.simple(level, "ITEM_SLIME", Vec3.atCenterOf(pos), 4, 0.2D, 0.2D, 0.2D, 0.05D);
				}
			}
		}
		this.mod.scheduler().later(() -> Messaging.actionBar(player, "<white>Cyclone ended - <yellow>" + hits[0] + "</yellow> hits"), duration);
	}
}
