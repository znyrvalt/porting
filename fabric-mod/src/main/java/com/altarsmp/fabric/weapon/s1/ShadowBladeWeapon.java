package com.altarsmp.fabric.weapon.s1;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.AbilityTracker;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Shadow Blade - port of {@code com.altarsmp.weapons.ShadowBladeWeapon}.
 *
 * <p><b>Passive</b>: Speed {@code abilities.shadowblade.passive_speed_level}
 * while held.</p>
 *
 * <p><b>Shadow Leap</b> (F, {@code leap_cooldown}s): blink up to
 * {@code leap_distance} blocks along the look vector, stopping at the first solid
 * block, and vanish for {@code leap_invis_duration} ticks.</p>
 *
 * <p><b>Shadow Dagger</b> (Shift+F, {@code dagger_cooldown}s): throws a dagger
 * display out to {@code dagger_distance} at {@code dagger_velocity}; it deals
 * {@code dagger_damage} true damage inside {@code dagger_hitbox}, then bursts in a
 * {@code dagger_aoe_radius} cloud applying Slowness
 * {@code dagger_slow_level}/{@code dagger_slow_duration} and Blindness
 * {@code dagger_blind_duration}. The thrower also gets a self-burst.</p>
 *
 * <p><b>Backstab</b>: hitting a target from behind has a
 * {@code backstab_pull_chance} chance to yank it towards the attacker with
 * {@code backstab_pull_strength}.</p>
 */
public final class ShadowBladeWeapon implements WeaponBehavior {

	static final String KEY_LEAP = "shadowblade_leap";
	static final String KEY_DAGGER = "shadowblade_dagger";
	private static final int SHADOW = 0x1A0B2E;

	private final AltarSMPMod mod;

	public ShadowBladeWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "shadowblade";
	}

	@Override
	public String displayName() {
		return "Shadow Blade";
	}

	@Override
	public List<String> configFields() {
		return List.of("Leap Cooldown (s)", "Dagger Cooldown (s)", "Leap Distance", "Dagger Damage");
	}

	@Override
	public void onTick(AbilityContext ctx) {
		if (this.mod.scheduler().currentTick() % 20L == 0L) {
			Effects.apply(ctx.player(), "SPEED", 40, ctx.cfg("abilities.shadowblade.passive_speed_level", 1), true, false, false);
		}
		if (this.mod.scheduler().currentTick() % 8L == 0L) {
			Fx.dust(ctx.level(), ctx.player().position().add(0.0D, 0.6D, 0.0D), SHADOW, 0.9F, 2, 0.25D, 0.35D, 0.25D);
		}
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		shadowLeap(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		shadowDagger(ctx);
	}

	// ------------------------------------------------------------- shadow leap

	private void shadowLeap(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.shadowblade.leap_cooldown", 30);
		if (!ctx.gate(KEY_LEAP, "Shadow Leap")) {
			return;
		}
		ctx.startCooldown(KEY_LEAP, cooldown);
		CooldownBars.show(player, KEY_LEAP, "Shadow Leap", BossEvent.BossBarColor.PURPLE, cooldown);
		AbilityTracker.recordLeap(player);

		double maxDistance = ctx.cfgd("abilities.shadowblade.leap_distance", 30.0D);
		int invisTicks = ctx.cfg("abilities.shadowblade.leap_invis_duration", 36);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 from = player.getEyePosition();
		Vec3 destination = null;
		double step = 0.5D;
		for (double travelled = step; travelled <= maxDistance; travelled += step) {
			Vec3 candidate = from.add(direction.scale(travelled));
			BlockPos at = BlockPos.containing(candidate);
			if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
				destination = from.add(direction.scale(Math.max(step, travelled - step)));
				break;
			}
			destination = candidate;
		}
		if (destination == null) {
			destination = from.add(direction.scale(maxDistance));
		}
		Vec3 target = new Vec3(destination.x, destination.y - player.getEyeHeight(), destination.z);

		Fx.sound(level, player.position(), "ENTITY_ENDERMAN_TELEPORT", 0.8F, 0.7F);
		Fx.dust(level, player.position().add(0.0D, 1.0D, 0.0D), SHADOW, 1.4F, 25, 0.4D, 0.6D, 0.4D);
		Fx.simple(level, "PORTAL", player.position().add(0.0D, 1.0D, 0.0D), 20, 0.4D, 0.6D, 0.4D, 0.3D);
		Motion.teleport(player, target);
		Fx.sound(level, target, "ENTITY_ENDERMAN_TELEPORT", 0.9F, 1.1F);
		Fx.dust(level, target.add(0.0D, 1.0D, 0.0D), SHADOW, 1.4F, 25, 0.4D, 0.6D, 0.4D);
		Fx.simple(level, "PORTAL", target.add(0.0D, 1.0D, 0.0D), 20, 0.4D, 0.6D, 0.4D, 0.3D);
		Effects.apply(player, "INVISIBILITY", invisTicks, 0, false, false, false);
		Messaging.actionBar(player, "<dark_purple>You slip into the shadows.");
	}

	// ------------------------------------------------------------ shadow dagger

	private void shadowDagger(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.shadowblade.dagger_cooldown", 15);
		if (!ctx.gate(KEY_DAGGER, "Shadow Dagger")) {
			return;
		}
		ctx.startCooldown(KEY_DAGGER, cooldown);
		CooldownBars.show(player, KEY_DAGGER, "Shadow Dagger", BossEvent.BossBarColor.PURPLE, cooldown);

		double damage = ctx.cfgd("abilities.shadowblade.dagger_damage", 3.0D);
		double velocity = ctx.cfgd("abilities.shadowblade.dagger_velocity", 1.5D);
		double hitbox = ctx.cfgd("abilities.shadowblade.dagger_hitbox", 0.8D);
		double distance = ctx.cfgd("abilities.shadowblade.dagger_distance", 100.0D);
		double aoe = ctx.cfgd("abilities.shadowblade.dagger_aoe_radius", 5.0D);
		int slowTicks = ctx.cfg("abilities.shadowblade.dagger_slow_duration", 60);
		int slowLevel = ctx.cfg("abilities.shadowblade.dagger_slow_level", 1);
		int blindTicks = ctx.cfg("abilities.shadowblade.dagger_blind_duration", 60);

		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();
		Display.ItemDisplay dagger = Displays.item(level, start, new ItemStack(Items.NETHERITE_SWORD), 0.45F);
		Displays.bright(dagger);
		Fx.sound(level, start, "ITEM_TRIDENT_THROW", 0.9F, 1.3F);

		// Self-burst around the thrower (dagger_aoe_radius).
		Fx.dust(level, player.position().add(0.0D, 1.0D, 0.0D), SHADOW, 1.3F, 30, aoe * 0.2D, 0.4D, aoe * 0.2D);

		final double[] travelled = {0.0D};
		final float[] spin = {0.0F};
		this.mod.scheduler().timer(() -> {
			travelled[0] += velocity * 2.0D;
			Vec3 head = start.add(direction.scale(travelled[0]));
			dagger.snapTo(head.x, head.y, head.z, 0.0F, 0.0F);
			spin[0] += 30.0F;
			Displays.rotate(dagger, spin[0], 0.45F);
			Fx.dust(level, head, SHADOW, 0.6F, 1, 0.05D, 0.05D, 0.05D);

			BlockPos at = BlockPos.containing(head);
			boolean hitBlock = !level.getBlockState(at).getCollisionShape(level, at).isEmpty();
			List<LivingEntity> struck = level.getEntitiesOfClass(LivingEntity.class,
					new AABB(head, head).inflate(hitbox), e -> e != player && e.isAlive());
			if (hitBlock || !struck.isEmpty() || travelled[0] >= distance) {
				Displays.remove(dagger);
				burst(ctx, head, struck, damage, aoe, slowTicks, slowLevel, blindTicks);
			}
		}, 0L, 1L).cancelAfter((long) (distance / (velocity * 2.0D)) + 20L);
	}

	private void burst(AbilityContext ctx, Vec3 center, List<LivingEntity> struck, double damage, double aoe,
			int slowTicks, int slowLevel, int blindTicks) {
		ServerLevel level = ctx.level();
		ServerPlayer player = ctx.player();
		Fx.sound(level, center, "ENTITY_BLAZE_DEATH", 0.6F, 1.4F);
		Fx.dust(level, center, SHADOW, 1.6F, 40, aoe * 0.3D, aoe * 0.3D, aoe * 0.3D);
		Fx.simple(level, "SMOKE", center, 30, aoe * 0.3D, aoe * 0.3D, aoe * 0.3D, 0.05D);
		Fx.simple(level, "PORTAL", center, 20, aoe * 0.2D, aoe * 0.2D, aoe * 0.2D, 0.4D);

		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center, center).inflate(aoe), e -> e != player && e.isAlive())) {
			boolean direct = struck.contains(entity);
			TrueDamage.apply(entity, direct ? damage : damage * 0.5D, player, false);
			Effects.apply(entity, "SLOWNESS", slowTicks, slowLevel);
			Effects.apply(entity, "BLINDNESS", blindTicks, 0);
			if (entity instanceof ServerPlayer victim) {
				Messaging.actionBar(victim, "<dark_purple>Blinded by shadow!");
			}
		}
	}

	// ----------------------------------------------------------------- backstab

	@Override
	public void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
		double chance = ctx.cfgd("abilities.shadowblade.backstab_pull_chance", 0.3D);
		if (ctx.random().nextDouble() > chance) {
			return;
		}
		Vec3 toTarget = target.position().subtract(ctx.player().position()).normalize();
		Vec3 facing = target.getLookAngle().normalize();
		// Behind the target: our approach vector points the same way it faces.
		if (toTarget.dot(facing) < 0.5D) {
			return;
		}
		double strength = ctx.cfgd("abilities.shadowblade.backstab_pull_strength", 0.35D);
		Vec3 pull = ctx.player().position().subtract(target.position()).normalize().scale(strength);
		target.setDeltaMovement(pull.x, Math.max(pull.y, 0.05D), pull.z);
		target.hurtMarked = true;
		TrueDamage.apply(target, ctx.cfgd("abilities.shadowblade.backstab_bonus_damage", 2.0D), ctx.player(), false);
		Fx.sound(ctx.level(), target.position(), "ENTITY_PLAYER_ATTACK_CRIT", 1.0F, 1.2F);
		Fx.dust(ctx.level(), target.position().add(0.0D, 1.0D, 0.0D), SHADOW, 1.2F, 15, 0.3D, 0.4D, 0.3D);
		Messaging.actionBar(ctx.player(), "<dark_purple><bold>BACKSTAB!");
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		UUID uuid = player.getUUID();
		CooldownBars.hide(player, KEY_LEAP);
		CooldownBars.hide(player, KEY_DAGGER);
		if (uuid == null) {
			return;
		}
	}
}
