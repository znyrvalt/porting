package com.altarsmp.fabric.weapon.s1;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.AbilityTracker;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Windweaver - port of {@code com.altarsmp.weapons.WindweaverWeapon}.
 *
 * <p><b>Gust</b> (F): a cone of wind hurls everything in front of the holder away
 * with {@code abilities.windweaver.gust_knockback_base} plus
 * {@code gust_y_knockback_bonus} upward. The cooldown is rolled between
 * {@code gust_cooldown_min} and {@code gust_cooldown_max} every cast, which is why
 * the config exposes two numbers instead of one.</p>
 *
 * <p><b>Wind Leap</b> (Shift+F): a long dash along the look vector
 * ({@code leap_velocity}) with a short burst of Slow Falling so the landing is
 * survivable. Cooldown {@code leap_cooldown}s.</p>
 */
public final class WindweaverWeapon implements WeaponBehavior {

	static final String KEY_GUST = "windweaver_gust";
	static final String KEY_LEAP = "windweaver_leap";
	private static final int WIND_WHITE = 0xE8F4FF;

	private final AltarSMPMod mod;

	public WindweaverWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "windweaver";
	}

	@Override
	public String displayName() {
		return "Windweaver";
	}

	@Override
	public List<String> configFields() {
		return List.of("Gust Cooldown Min (s)", "Gust Cooldown Max (s)", "Leap Cooldown (s)", "Gust Knockback");
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		useGust(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		useWindLeap(ctx);
	}

	private void useGust(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int min = ctx.cfg("abilities.windweaver.gust_cooldown_min", 15);
		int max = ctx.cfg("abilities.windweaver.gust_cooldown_max", 30);
		if (!ctx.gate(KEY_GUST, "Gust")) {
			return;
		}
		int cooldown = max <= min ? min : min + ctx.random().nextInt(max - min + 1);
		ctx.startCooldown(KEY_GUST, cooldown);
		CooldownBars.show(player, KEY_GUST, "Gust (" + cooldown + "s)", BossEvent.BossBarColor.WHITE, cooldown);

		double radius = ctx.cfgd("abilities.windweaver.gust_radius", 8.0D);
		double knockback = ctx.cfgd("abilities.windweaver.gust_knockback_base", 0.7D);
		double lift = ctx.cfgd("abilities.windweaver.gust_y_knockback_bonus", 0.8D);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 center = player.position().add(0.0D, 1.0D, 0.0D);

		Fx.sound(level, center, "ENTITY_BREEZE_SHOOT", 1.2F, 0.9F);
		Fx.sound(level, center, "ENTITY_WIND_CHARGE_BURST", 1.0F, 1.1F);
		Fx.simple(level, "GUST", center, 40, radius * 0.2D, 0.4D, radius * 0.2D, 0.25D);
		Fx.simple(level, "GUST_EMITTER_SMALL", center, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		Fx.dust(level, center, WIND_WHITE, 1.4F, 30, radius * 0.2D, 0.4D, radius * 0.2D);

		int pushed = 0;
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center, center).inflate(radius), e -> e != player && e.isAlive())) {
			Vec3 toEntity = entity.position().subtract(player.position());
			if (toEntity.length() > radius) {
				continue;
			}
			Vec3 push = toEntity.normalize().scale(knockback);
			double alignment = push.dot(direction);
			if (alignment < 0.1D) {
				continue;
			}
			entity.setDeltaMovement(push.x * (1.0D + alignment), lift * (0.6D + alignment * 0.4D), push.z * (1.0D + alignment));
			entity.hurtMarked = true;
			entity.fallDistance = 0.0F;
			Fx.simple(level, "GUST", entity.position().add(0.0D, 1.0D, 0.0D), 12, 0.3D, 0.3D, 0.3D, 0.2D);
			pushed++;
		}
		Messaging.actionBar(player, "<white>Gust pushed <yellow>" + pushed + "</yellow> target(s) - cooldown " + cooldown + "s");
	}

	private void useWindLeap(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.windweaver.leap_cooldown", 15);
		if (!ctx.gate(KEY_LEAP, "Wind Leap")) {
			return;
		}
		ctx.startCooldown(KEY_LEAP, cooldown);
		CooldownBars.show(player, KEY_LEAP, "Wind Leap", BossEvent.BossBarColor.WHITE, cooldown);
		AbilityTracker.recordLeap(player);

		Motion.launch(player, ctx.cfgd("abilities.windweaver.leap_velocity", 1.8D), 0.55D);
		Effects.apply(player, "SLOW_FALLING", ctx.cfg("abilities.windweaver.leap_slow_fall_ticks", 60), 0, false, false, false);
		Effects.apply(player, "JUMP", ctx.cfg("abilities.windweaver.leap_jump_ticks", 60), 1, false, false, false);
		Fx.sound(level, player.position(), "ENTITY_BREEZE_JUMP", 1.0F, 1.0F);
		Fx.simple(level, "GUST", player.position(), 25, 0.4D, 0.4D, 0.4D, 0.2D);
		Fx.dust(level, player.position().add(0.0D, 0.6D, 0.0D), WIND_WHITE, 1.2F, 18, 0.4D, 0.3D, 0.4D);

		final int[] trail = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			trail[0]++;
			Fx.dust(level, player.position(), WIND_WHITE, 0.8F, 3, 0.15D, 0.15D, 0.15D);
			Fx.simple(level, "CLOUD", player.position(), 2, 0.2D, 0.1D, 0.2D, 0.02D);
		}, 0L, 2L).cancelAfter(20L);
	}

	@Override
	public void onTick(AbilityContext ctx) {
		// Passive: a faint wind trail so the weapon reads as airborne.
		if (this.mod.scheduler().currentTick() % 6L == 0L) {
			Fx.simple(ctx.level(), "GUST", ctx.player().position().add(0.0D, 0.8D, 0.0D), 1, 0.15D, 0.15D, 0.15D, 0.05D);
		}
	}
}
