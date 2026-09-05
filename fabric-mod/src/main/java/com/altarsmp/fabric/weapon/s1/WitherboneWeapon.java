package com.altarsmp.fabric.weapon.s1;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Witherbone - port of {@code com.altarsmp.weapons.WitherboneWeapon}.
 *
 * <p>The charge weapon. Every hit adds charge, stored on the item's state
 * component so it survives a relog and a server restart, with thresholds at
 * {@code half_charge}, {@code mid_charge} and {@code full_charge}.</p>
 *
 * <p><b>Wither Leap</b> (F, {@code leap_cooldown}s): dashes forward at
 * {@code leap_velocity}; while airborne a ram aura inside
 * {@code leap_rammer_radius} deals {@code leap_damage} true damage, throws victims
 * with {@code leap_knockback} and applies Wither {@code wither_level} for
 * {@code wither_duration}s. The leaper gets Speed {@code leap_speed_level} for
 * {@code leap_speed_duration_ticks}.</p>
 *
 * <p><b>Purge Strike</b> (Shift+F, {@code strike_cooldown}s): a
 * {@code strike_range} x {@code strike_height} box in front of the player takes
 * {@code strike_damage} true damage plus Wither; at full charge the strike
 * additionally sludges every target for {@code purge_wither_duration}s and the
 * charge resets.</p>
 */
public final class WitherboneWeapon implements WeaponBehavior {

	static final String KEY_LEAP = "witherbone_leap";
	static final String KEY_STRIKE = "witherbone_strike";
	static final String STATE_CHARGE = "witherbone_charge";

	private static final int WITHER_PURPLE = (60 << 16) | (20 << 8) | 80;

	private final AltarSMPMod mod;

	public WitherboneWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "witherbone";
	}

	@Override
	public String displayName() {
		return "Witherbone";
	}

	@Override
	public List<String> configFields() {
		return List.of("Leap Cooldown (s)", "Strike Cooldown (s)", "Leap Damage", "Strike Damage");
	}

	// ------------------------------------------------------------------- charge

	private int charge(ItemStack weapon) {
		return Identity.stateInt(weapon, STATE_CHARGE, 0);
	}

	private void setCharge(AbilityContext ctx, int value) {
		int clamped = Math.max(0, Math.min(value, ctx.cfg("abilities.witherbone.full_charge", 50)));
		Identity.setStateInt(ctx.weapon(), STATE_CHARGE, clamped);
		updateLoreCharge(ctx, clamped);
	}

	private void updateLoreCharge(AbilityContext ctx, int value) {
		int half = ctx.cfg("abilities.witherbone.half_charge", 15);
		int mid = ctx.cfg("abilities.witherbone.mid_charge", 35);
		int full = ctx.cfg("abilities.witherbone.full_charge", 50);
		String stage = value >= full ? "<dark_purple><bold>FULL CHARGE" : value >= mid ? "<dark_purple>Mid Charge"
				: value >= half ? "<dark_purple>Half Charge" : "<gray>Uncharged";
		Messaging.actionBar(ctx.player(), stage + " <gray>(" + value + "/" + full + ")");
	}

	@Override
	public void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
		int gained = charge(ctx.weapon()) + 1;
		setCharge(ctx, gained);
		Effects.apply(target, "WITHER", ctx.cfg("abilities.witherbone.strike_wither_duration_ticks", 40),
				ctx.cfg("abilities.witherbone.strike_wither_level", 1));
		Fx.dust(ctx.level(), target.position().add(0.0D, 1.0D, 0.0D), WITHER_PURPLE, 1.0F, 6, 0.3D, 0.3D, 0.3D);
		Fx.sound(ctx.level(), target.position(), "ENTITY_WITHER_HURT", 0.5F, 1.3F);
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		witherLeap(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		purgeStrike(ctx);
	}

	// -------------------------------------------------------------- wither leap

	private void witherLeap(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.witherbone.leap_cooldown", 10);
		if (!ctx.gate(KEY_LEAP, "Wither Leap")) {
			return;
		}
		ctx.startCooldown(KEY_LEAP, cooldown);
		CooldownBars.show(player, KEY_LEAP, "Wither Leap", BossEvent.BossBarColor.PURPLE, cooldown);
		Motion.recordLeap(player);

		double velocity = ctx.cfgd("abilities.witherbone.leap_velocity", 1.4D);
		double leapDamage = ctx.cfgd("abilities.witherbone.leap_damage", 6.0D);
		double knockback = ctx.cfgd("abilities.witherbone.leap_knockback", 0.9D);
		double ramRadius = ctx.cfgd("abilities.witherbone.leap_rammer_radius", 1.5D);
		int witherSeconds = ctx.cfg("abilities.witherbone.wither_duration", 3);
		int witherLevel = ctx.cfg("abilities.witherbone.wither_level", 2);
		int speedTicks = ctx.cfg("abilities.witherbone.leap_speed_duration_ticks", 60);
		int speedLevel = ctx.cfg("abilities.witherbone.leap_speed_level", 2);

		Motion.launch(player, velocity, 0.45D);
		Effects.apply(player, "SPEED", speedTicks, speedLevel, true, false, true);
		Fx.sound(level, player.position(), "ENTITY_WITHER_SHOOT", 0.8F, 0.7F);
		Fx.dust(level, player.position().add(0.0D, 1.0D, 0.0D), WITHER_PURPLE, 1.6F, 30, 0.5D, 0.5D, 0.5D);
		Fx.simple(level, "SMOKE", player.position(), 20, 0.4D, 0.4D, 0.4D, 0.05D);

		final java.util.Set<java.util.UUID> rammed = new java.util.HashSet<>();
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			Fx.dust(level, player.position().add(0.0D, 0.8D, 0.0D), WITHER_PURPLE, 1.0F, 4, 0.3D, 0.2D, 0.3D);
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
					player.getBoundingBox().inflate(ramRadius), e -> e != player && e.isAlive())) {
				if (!rammed.add(entity.getUUID())) {
					continue;
				}
				TrueDamage.apply(entity, leapDamage, player, false);
				Effects.apply(entity, "WITHER", witherSeconds * 20, witherLevel);
				Vec3 away = entity.position().subtract(player.position()).normalize().scale(knockback);
				entity.setDeltaMovement(away.x, 0.4D, away.z);
				entity.hurtMarked = true;
				Fx.sound(level, entity.position(), "ENTITY_WITHER_HURT", 0.7F, 1.1F);
			}
		}, 0L, 1L).cancelAfter(20L);
	}

	// ------------------------------------------------------------- purge strike

	private void purgeStrike(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.witherbone.strike_cooldown", 8);
		if (!ctx.gate(KEY_STRIKE, "Purge Strike")) {
			return;
		}
		ctx.startCooldown(KEY_STRIKE, cooldown);

		int range = ctx.cfg("abilities.witherbone.strike_range", 9);
		int height = ctx.cfg("abilities.witherbone.strike_height", 3);
		double damage = ctx.cfgd("abilities.witherbone.strike_damage", 4.0D);
		int witherTicks = ctx.cfg("abilities.witherbone.strike_wither_duration_ticks", 40);
		int witherLevel = ctx.cfg("abilities.witherbone.strike_wither_level", 1);
		int purgeSeconds = ctx.cfg("abilities.witherbone.purge_wither_duration", 4);
		boolean fullCharge = charge(ctx.weapon()) >= ctx.cfg("abilities.witherbone.full_charge", 50);

		Vec3 direction = new Vec3(player.getLookAngle().x, 0.0D, player.getLookAngle().z).normalize();
		Vec3 center = player.position().add(direction.scale(range * 0.5D)).add(0.0D, 1.0D, 0.0D);
		AABB box = new AABB(center, center).inflate(range * 0.5D, height, range * 0.5D);

		Fx.sound(level, center, "ENTITY_WITHER_BREAK_BLOCK", 1.0F, 0.8F);
		Fx.dust(level, center, WITHER_PURPLE, 2.0F, 60, range * 0.4D, height * 0.4D, range * 0.4D);
		Fx.simple(level, "SMOKE", center, 40, range * 0.3D, height * 0.3D, range * 0.3D, 0.05D);
		Messaging.actionBar(player, fullCharge ? "<dark_purple><bold>PURGE STRIKE!" : "<dark_purple>Strike!");

		int hits = 0;
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != player && e.isAlive())) {
			TrueDamage.apply(entity, damage, player, false);
			Effects.apply(entity, "WITHER", fullCharge ? purgeSeconds * 20 : witherTicks, fullCharge ? 2 : witherLevel);
			Fx.dust(level, entity.position().add(0.0D, 1.0D, 0.0D), WITHER_PURPLE, 1.2F, 12, 0.3D, 0.4D, 0.3D);
			hits++;
		}
		if (fullCharge) {
			setCharge(ctx, 0);
			Fx.sound(level, player.position(), "ENTITY_WITHER_DEATH", 0.6F, 1.4F);
			Messaging.send(player, "<dark_purple>Full charge released - <white>" + hits + "</white> target(s) purged.");
		}
		CooldownBars.show(player, KEY_STRIKE, "Purge Strike", BossEvent.BossBarColor.PURPLE, cooldown);
	}

	@Override
	public boolean onDamaged(AbilityContext ctx, DamageSource source, float amount, LivingEntity attacker) {
		// Being hit bleeds a little charge, matching the plugin's decay on damage.
		int charge = charge(ctx.weapon());
		if (charge > 0) {
			setCharge(ctx, charge - 1);
		}
		return false;
	}

	@Override
	public void onTick(AbilityContext ctx) {
		if (this.mod.scheduler().currentTick() % 20L == 0L && charge(ctx.weapon()) > 0) {
			updateLoreCharge(ctx, charge(ctx.weapon()));
		}
	}
}
