package com.altarsmp.fabric.weapon.s1;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * Frost Scythe - port of {@code com.altarsmp.weapons.FrostScytheWeapon}.
 *
 * <p><b>Scythe Throw</b> (F): the scythe itself is hurled as a spinning item
 * display (model rotation Z {@code throw_model_rotation_z}, scale
 * {@code throw_model_scale}, spin {@code throw_model_spin_speed}). It deals
 * {@code throw_damage} true damage, freezes the victim for
 * {@code freeze_ticks} (Slowness {@code freeze_slowness_level}) behind a burst of
 * ice, then flies back to the thrower with {@code throw_return_power}. Cooldown
 * {@code throw_cooldown}s, max range {@code throw_max_distance}.</p>
 *
 * <p><b>Ice Spikes</b> (Shift+F): {@code ice_count} packed-ice spikes rise
 * {@code ice_spawn_height} blocks around the target, wait
 * {@code ice_launch_delay} ms and then launch outward at {@code ice_velocity},
 * dealing {@code command_damage} inside {@code ice_hitbox}. Cooldown
 * {@code command_cooldown}s; spikes expire after {@code ice_timeout}s.</p>
 */
public final class FrostScytheWeapon implements WeaponBehavior {

	static final String KEY_THROW = "frostscythe_throw";
	static final String KEY_SPIKES = "frostscythe_spikes";
	private static final int ICE_BLUE = (140 << 16) | (200 << 8) | 255;

	private final AltarSMPMod mod;

	public FrostScytheWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "frost_scythe";
	}

	@Override
	public String displayName() {
		return "Frost Scythe";
	}

	@Override
	public List<String> configFields() {
		return List.of("Throw Cooldown (s)", "Ice Spike Cooldown (s)", "Throw Damage", "Freeze Ticks");
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		throwScythe(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		iceSpikes(ctx);
	}

	// ------------------------------------------------------------ scythe throw

	private void throwScythe(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.frostscythe.throw_cooldown", 30);
		if (!ctx.gate(KEY_THROW, "Scythe Throw")) {
			return;
		}
		ctx.startCooldown(KEY_THROW, cooldown);
		CooldownBars.show(player, KEY_THROW, "Scythe Throw", BossEvent.BossBarColor.BLUE, cooldown);

		double damage = ctx.cfgd("abilities.frostscythe.throw_damage", 6.0D);
		double maxDistance = ctx.cfg("abilities.frostscythe.throw_max_distance", 100);
		double speed = ctx.cfgd("abilities.frostscythe.throw_speed", 2.0D);
		float scale = (float) ctx.cfgd("abilities.frostscythe.throw_model_scale", 1.5D);
		float spinSpeed = (float) ctx.cfgd("abilities.frostscythe.throw_model_spin_speed", 0.5D);
		int freezeTicks = ctx.cfg("abilities.frostscythe.freeze_ticks", 100);
		int freezeSlowness = ctx.cfg("abilities.frostscythe.freeze_slowness_level", 1);
		double returnPower = ctx.cfgd("abilities.frostscythe.throw_return_power", 2.0D);

		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();
		Display.ItemDisplay scythe = Displays.item(level, start, create(player), scale);
		Displays.bright(scythe);
		Fx.sound(level, start, "ITEM_TRIDENT_THROW", 1.0F, 0.9F);
		Fx.dust(level, start, ICE_BLUE, 1.0F, 12, 0.3D, 0.3D, 0.3D);

		final double[] travelled = {0.0D};
		final float[] spin = {0.0F};
		final boolean[] returning = {false};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				Displays.remove(scythe);
				return;
			}
			spin[0] += spinSpeed * 24.0F;
			if (returning[0]) {
				Vec3 toPlayer = player.getEyePosition().subtract(scythe.position()).normalize().scale(returnPower);
				Vec3 next = scythe.position().add(toPlayer);
				scythe.snapTo(next.x, next.y, next.z, 0.0F, 0.0F);
				Displays.rotate(scythe, spin[0], scale);
				Fx.dust(level, next, ICE_BLUE, 0.8F, 2, 0.05D, 0.05D, 0.05D);
				if (next.distanceTo(player.getEyePosition()) < 1.2D) {
					Displays.remove(scythe);
					Fx.sound(level, player.position(), "ITEM_TRIDENT_RETURN", 1.0F, 1.0F);
					Messaging.actionBar(player, "<aqua>Scythe returned.");
				}
				return;
			}
			travelled[0] += speed;
			Vec3 head = start.add(direction.scale(travelled[0]));
			scythe.snapTo(head.x, head.y, head.z, 0.0F, 0.0F);
			Displays.rotate(scythe, spin[0], scale);
			Fx.dust(level, head, ICE_BLUE, 0.7F, 2, 0.05D, 0.05D, 0.05D);
			Fx.simple(level, "SNOWFLAKE", head, 3, 0.1D, 0.1D, 0.1D, 0.0D);

			BlockPos at = BlockPos.containing(head);
			boolean hitBlock = !level.getBlockState(at).getCollisionShape(level, at).isEmpty();
			List<LivingEntity> struck = level.getEntitiesOfClass(LivingEntity.class,
					new AABB(head, head).inflate(1.3D), e -> e != player && e.isAlive());
			if (hitBlock || !struck.isEmpty() || travelled[0] >= maxDistance) {
				returning[0] = true;
				if (!struck.isEmpty()) {
					for (LivingEntity entity : struck) {
						TrueDamage.apply(entity, damage, player, false);
						freeze(ctx, entity, freezeTicks, freezeSlowness);
					}
					Fx.sound(level, head, "BLOCK_GLASS_BREAK", 1.0F, 1.2F);
				} else {
					Fx.sound(level, head, "BLOCK_STONE_BREAK", 0.8F, 1.4F);
				}
				Fx.dust(level, head, ICE_BLUE, 1.6F, 25, 0.5D, 0.5D, 0.5D);
			}
		}, 0L, 1L).cancelAfter((long) (maxDistance / speed) + 200L);
	}

	private void freeze(AbilityContext ctx, LivingEntity entity, int freezeTicks, int slownessLevel) {
		ServerLevel level = ctx.level();
		// Bukkit: var2.setFreezeTicks(getFreezeTicks()) - real vanilla frost.
		entity.setTicksFrozen(Math.max(entity.getTicksFrozen(), freezeTicks));
		Effects.apply(entity, "SLOWNESS", freezeTicks, slownessLevel, false, false, true);
		Fx.simple(level, "SNOWFLAKE", entity.position().add(0.0D, 1.0D, 0.0D), 30, 0.3D, 0.5D, 0.3D, 0.1D);
		Fx.simple(level, "ENCHANTED_HIT", entity.position().add(0.0D, 1.0D, 0.0D), 100, 0.5D, 0.5D, 0.5D, 0.2D);
		Fx.dust(level, entity.position().add(0.0D, 1.0D, 0.0D), ICE_BLUE, 1.2F, 20, 0.4D, 0.6D, 0.4D);
		Fx.sound(level, entity.position(), "BLOCK_GLASS_PLACE", 0.9F, 1.3F);
		if (entity instanceof ServerPlayer victim) {
			Messaging.actionBar(victim, "<aqua><bold>FROZEN!</bold>");
			CooldownBars.show(victim, "frostscythe_frozen", "Frozen", BossEvent.BossBarColor.BLUE, freezeTicks * 50L);
			this.mod.scheduler().later(() -> CooldownBars.hide(victim, "frostscythe_frozen"), freezeTicks);
		}
		// Ice shell visuals around the frozen target.
		List<Display.BlockDisplay> shell = new ArrayList<>();
		Vec3 center = entity.position();
		for (int i = 0; i < 6; i++) {
			double angle = Math.toRadians(60.0D * i);
			Vec3 pos = center.add(Math.cos(angle) * 0.9D, 0.6D + (i % 3) * 0.5D, Math.sin(angle) * 0.9D);
			Display.BlockDisplay ice = Displays.block(level, pos, Blocks.PACKED_ICE.defaultBlockState());
			Displays.setScale(ice, 0.45F);
			shell.add(ice);
		}
		this.mod.scheduler().later(() -> shell.forEach(Displays::remove), freezeTicks);
	}

	// -------------------------------------------------------------- ice spikes

	private void iceSpikes(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.frostscythe.command_cooldown", 45);
		if (!ctx.gate(KEY_SPIKES, "Ice Spikes")) {
			return;
		}
		ctx.startCooldown(KEY_SPIKES, cooldown);
		CooldownBars.show(player, KEY_SPIKES, "Ice Spikes", BossEvent.BossBarColor.BLUE, cooldown);

		int count = ctx.cfg("abilities.frostscythe.ice_count", 3);
		double velocity = ctx.cfgd("abilities.frostscythe.ice_velocity", 1.5D);
		double hitbox = ctx.cfgd("abilities.frostscythe.ice_hitbox", 4.0D);
		int spawnHeight = ctx.cfg("abilities.frostscythe.ice_spawn_height", 4);
		int timeoutSeconds = ctx.cfg("abilities.frostscythe.ice_timeout", 15);
		long launchDelayTicks = Math.max(1L, ctx.cfg("abilities.frostscythe.ice_launch_delay", 500) / 50L);
		double damage = ctx.cfgd("abilities.frostscythe.command_damage", 3.0D);

		Vec3 center = player.position();
		Fx.sound(level, center, "BLOCK_GLASS_PLACE", 1.0F, 0.7F);
		Messaging.actionBar(player, "<aqua>Ice Spikes forming...");

		List<Display.BlockDisplay> spikes = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			double angle = Math.toRadians(360.0D / count * i);
			Vec3 pos = center.add(Math.cos(angle) * 1.6D, 0.0D, Math.sin(angle) * 1.6D);
			for (int y = 0; y < spawnHeight; y++) {
				Display.BlockDisplay spike = Displays.block(level, pos.add(0.0D, y, 0.0D), Blocks.PACKED_ICE.defaultBlockState());
				Displays.setScale(spike, 1.0F);
				Displays.bright(spike);
				spikes.add(spike);
			}
			Fx.dust(level, pos.add(0.0D, 1.0D, 0.0D), ICE_BLUE, 1.4F, 12, 0.3D, 0.8D, 0.3D);
			Fx.simple(level, "SNOWFLAKE", pos.add(0.0D, 1.0D, 0.0D), 10, 0.4D, 0.8D, 0.4D, 0.02D);
		}
		Fx.sound(level, center, "ENTITY_PLAYER_ATTACK_CRIT", 1.0F, 0.8F);

		this.mod.scheduler().later(() -> {
			if (player.isRemoved()) {
				spikes.forEach(Displays::remove);
				return;
			}
			Fx.sound(level, center, "BLOCK_GLASS_BREAK", 1.2F, 0.9F);
			spikes.forEach(Displays::remove);
			int hits = 0;
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(center, center).inflate(hitbox), e -> e != player && e.isAlive())) {
				TrueDamage.apply(entity, damage, player, false);
				Vec3 away = entity.position().subtract(center).normalize().scale(velocity);
				entity.setDeltaMovement(away.x, Math.max(away.y, 0.45D), away.z);
				entity.hurtMarked = true;
				Effects.apply(entity, "SLOWNESS", ctx.cfg("abilities.frostscythe.freeze_ticks", 100),
						ctx.cfg("abilities.frostscythe.freeze_slowness_level", 1));
				Fx.dust(level, entity.position().add(0.0D, 1.0D, 0.0D), ICE_BLUE, 1.2F, 15, 0.4D, 0.4D, 0.4D);
				hits++;
			}
			Messaging.actionBar(player, "<aqua>Ice Spikes hit <white>" + hits + "</white> target(s)");
		}, launchDelayTicks);

		// Safety net: if the launch task never ran (player quit mid-cast) the
		// displays still expire.
		this.mod.scheduler().later(() -> spikes.forEach(Displays::remove), timeoutSeconds * 20L);
	}

	@Override
	public void onTick(AbilityContext ctx) {
		// Passive frost aura: a light dust trail so the scythe reads as cold.
		if (this.mod.scheduler().currentTick() % 10L == 0L) {
			Fx.dust(ctx.level(), ctx.player().position().add(0.0D, 1.0D, 0.0D), ICE_BLUE, 0.6F, 1, 0.2D, 0.2D, 0.2D);
		}
	}

	/** Item used by the ice shell visuals. */
	static ItemStack iceShard() {
		return new ItemStack(Items.ICE);
	}
}
