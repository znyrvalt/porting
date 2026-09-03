package com.altarsmp.fabric.weapon.s1;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.AttributeFx;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Displays;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.data.PlayerRecord;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Bloodlust - port of {@code com.altarsmp.weapons.BloodlustWeapon}.
 *
 * <p>The kill-tier weapon. Kills are counted in the persistent player record
 * (the plugin's {@code PlayerDataManager}), mirrored onto the item's state
 * component, and unlock:</p>
 * <pre>
 *   0 kills  Infection        15% chance to bleed an enemy on hit (1.0 true dmg/s, 5s)
 *   1 kill   Speed            permanent Speed while held (bloodlust.speed_level)
 *   2 kills  Blood Tracker    particle line to nearby players, visible only to you
 *   3 kills  Blood Trail      Shift+F - submerge: invisibility, 0.5 scale, 1.5 step
 *   4 kills  Strength         permanent Strength while held (bloodlust.strength_level)
 *   5 kills  Blood Hook       F - chain projectile, 4.0 true damage, pulls what it hits
 * </pre>
 * <p>Below the required tier, activation prints the kill ladder instead
 * ({@code showKillsInfo}).</p>
 */
public final class BloodlustWeapon implements WeaponBehavior {

	static final String KEY_HOOK = "bloodlust_hook";
	static final String KEY_TRAIL = "bloodlust_trail";
	static final String KEY_BLEED = "bloodlust_bleed";

	private static final int BLOOD_DARK = (139 << 16);
	private static final int BLOOD = (220 << 16);
	private static final int BLOOD_BRIGHT = (255 << 16);

	private final AltarSMPMod mod;
	private final Map<UUID, Boolean> submerged = new HashMap<>();
	private final Map<UUID, Long> trailStartedAt = new HashMap<>();
	private final Map<UUID, Long> bleedUntil = new HashMap<>();
	private final Map<UUID, com.altarsmp.fabric.util.TickScheduler.Task> bleedTasks = new HashMap<>();
	private final Map<UUID, Display.BlockDisplay> hookChains = new HashMap<>();

	public BloodlustWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "bloodlust";
	}

	@Override
	public String displayName() {
		return "Bloodlust";
	}

	@Override
	public List<String> configFields() {
		return List.of("Hook Cooldown (s)", "Blood Trail Cooldown (s)", "Bleed Chance", "Bleed Damage");
	}

	// ------------------------------------------------------------------- kills

	private int kills(ServerPlayer player) {
		return this.mod.store().player(player.getUUID()).bloodlustKills();
	}

	@Override
	public void onKill(AbilityContext ctx, LivingEntity victim) {
		ServerPlayer player = ctx.player();
		PlayerRecord record = ctx.record();
		record.bloodlustKills(record.bloodlustKills() + 1);
		record.addWeaponKill(id(), 1);
		this.mod.store().markDirty();
		Identity.setKills(ctx.weapon(), record.bloodlustKills());
		Fx.sound(ctx.level(), victim.position(), "custom:bloodlust.kill", 1.0F, 1.0F);
		Fx.dust(ctx.level(), victim.position().add(0.0D, 1.0D, 0.0D), BLOOD_BRIGHT, 1.0F, 25, 0.4D, 0.5D, 0.4D);
		Fx.simple(ctx.level(), "DAMAGE_INDICATOR", victim.position().add(0.0D, 1.0D, 0.0D), 5, 0.0D, 0.0D, 0.0D, 0.0D);
		int kills = record.bloodlustKills();
		for (int tier : new int[]{1, 2, 3, 4, 5}) {
			if (kills == tier) {
				Messaging.send(player, "<dark_red><bold>BLOODLUST</bold> <gray>unlocked a new ability at <red>" + tier + " kill(s)</red>!");
				Fx.sound(ctx.level(), player.position(), "ENTITY_PLAYER_LEVELUP", 0.9F, 1.8F);
			}
		}
	}

	// ------------------------------------------------------------------ passive

	@Override
	public void onTick(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		long tick = this.mod.scheduler().currentTick();
		int kills = kills(player);
		if (tick % 20L == 0L) {
			if (kills >= 1) {
				Effects.apply(player, "SPEED", 40, ctx.cfg("bloodlust.speed_level", 1), true, false, false);
			}
			if (kills >= 4) {
				Effects.apply(player, "STRENGTH", 40, ctx.cfg("bloodlust.strength_level", 0), true, false, false);
			}
		}
		if (kills >= 2 && tick % 10L == 0L) {
			trackBlood(ctx);
		}
	}

	/** Blood Tracker: a dust line to every nearby player, rendered only for the holder. */
	private void trackBlood(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		double range = ctx.cfgd("abilities.bloodlust.tracker_range", 30.0D);
		Vec3 from = player.position().add(0.0D, 1.0D, 0.0D);
		for (ServerPlayer other : player.serverLevel().getServer().getPlayerList().getPlayers()) {
			if (other == player || other.level() != player.level()) {
				continue;
			}
			Vec3 to = other.position().add(0.0D, 1.0D, 0.0D);
			if (from.distanceTo(to) > range) {
				continue;
			}
			double distance = from.distanceTo(to);
			int steps = Math.max(4, (int) (distance * 2.0D));
			for (int i = 0; i <= steps; i++) {
				Vec3 point = from.add(to.subtract(from).scale((double) i / steps));
				Fx.dustOnlyTo(player, point, BLOOD, 0.65F, 1, 0.03D, 0.03D, 0.03D);
			}
			if (tick0() % 40L == 0L) {
				Fx.soundTo(player, "BLOCK_GRASS_STEP", 0.45F, 0.5F + (float) Math.random() * 0.3F);
			}
		}
	}

	private long tick0() {
		return this.mod.scheduler().currentTick();
	}

	// ------------------------------------------------------------- infection

	@Override
	public void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
		if (!(target instanceof ServerPlayer victim)) {
			return;
		}
		double chance = ctx.cfgd("abilities.bloodlust.bleed_chance", 0.15D);
		if (ctx.random().nextDouble() > chance) {
			return;
		}
		int bleedCooldown = ctx.cfg("abilities.bloodlust.bleed_cooldown", 10);
		if (this.mod.cooldowns().isOnCooldown(victim, KEY_BLEED)) {
			return;
		}
		this.mod.cooldowns().setCooldownSeconds(victim, KEY_BLEED, bleedCooldown);
		applyBleed(ctx, victim);
	}

	private void applyBleed(AbilityContext ctx, ServerPlayer victim) {
		double bleedDamage = ctx.cfgd("abilities.bloodlust.bleed_damage", 1.0D);
		int durationTicks = ctx.cfg("abilities.bloodlust.bleed_duration_ticks", 100);
		int damageInterval = Math.max(1, ctx.cfg("abilities.bloodlust.bleed_damage_interval", 20));
		int particleInterval = Math.max(1, ctx.cfg("abilities.bloodlust.bleed_particle_interval", 10));
		ServerPlayer attacker = ctx.player();

		CooldownBars.show(victim, KEY_BLEED, "!BLEEDING!", BossEvent.BossBarColor.RED, durationTicks * 50L);
		this.bleedUntil.put(victim.getUUID(), System.currentTimeMillis() + durationTicks * 50L);
		Fx.sound(ctx.level(), victim.position(), "ENTITY_WITHER_SHOOT", 0.55F, 0.55F);

		com.altarsmp.fabric.util.TickScheduler.Task previous = this.bleedTasks.remove(victim.getUUID());
		if (previous != null) {
			previous.cancel();
		}
		final int[] elapsed = {0};
		com.altarsmp.fabric.util.TickScheduler.Task task = this.mod.scheduler().timer(() -> {
			elapsed[0] += damageInterval;
			if (victim.isRemoved() || elapsed[0] >= durationTicks) {
				finishBleed(victim);
				return;
			}
			TrueDamage.apply(victim, bleedDamage, null, true);
			if (attacker != null && !attacker.isRemoved()) {
				spawnBleedReturnOrb(ctx.level(), victim, attacker);
			}
		}, damageInterval, damageInterval);
		this.bleedTasks.put(victim.getUUID(), task);

		com.altarsmp.fabric.util.TickScheduler.Task particles = this.mod.scheduler().timer(() -> {
			if (victim.isRemoved() || !this.bleedUntil.containsKey(victim.getUUID())) {
				return;
			}
			Fx.dust(ctx.level(), victim.position().add(0.0D, 1.0D, 0.0D), BLOOD, 0.5F, 1, 0.03D, 0.03D, 0.03D);
			Fx.sound(ctx.level(), victim.position(), "BLOCK_WET_SPONGE_STEP", 0.55F, 0.85F);
		}, particleInterval, particleInterval);
		particles.cancelAfter(durationTicks);
	}

	private void spawnBleedReturnOrb(ServerLevel level, ServerPlayer victim, ServerPlayer attacker) {
		Vec3 from = victim.position().add(0.0D, 1.0D, 0.0D);
		Vec3 to = attacker.position().add(0.0D, 1.0D, 0.0D);
		int steps = 6;
		for (int i = 0; i <= steps; i++) {
			Vec3 point = from.add(to.subtract(from).scale((double) i / steps));
			Fx.dust(level, point, BLOOD_BRIGHT, 0.6F, 1, 0.02D, 0.02D, 0.02D);
		}
		Fx.sound(level, attacker.position(), "ENTITY_EXPERIENCE_ORB_PICKUP", 0.4F, 1.4F);
	}

	private void finishBleed(ServerPlayer victim) {
		this.bleedUntil.remove(victim.getUUID());
		com.altarsmp.fabric.util.TickScheduler.Task task = this.bleedTasks.remove(victim.getUUID());
		if (task != null) {
			task.cancel();
		}
		CooldownBars.hide(victim, KEY_BLEED);
	}

	// -------------------------------------------------------------- activation

	@Override
	public void onPrimary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int kills = kills(player);
		if (kills >= 5) {
			useBloodHook(ctx);
		} else {
			showKillsInfo(ctx, kills);
		}
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (this.submerged.containsKey(player.getUUID())) {
			Long started = this.trailStartedAt.get(player.getUUID());
			if (started != null && System.currentTimeMillis() - started >= 1000L) {
				endBloodTrail(ctx);
			}
			return;
		}
		int kills = kills(player);
		if (kills >= 3) {
			useBloodTrail(ctx);
		} else {
			showKillsInfo(ctx, kills);
		}
	}

	@Override
	public boolean onUse(AbilityContext ctx, InteractionHand hand) {
		// While submerged, any click surfaces the player again (onInteract).
		if (this.submerged.containsKey(ctx.player().getUUID()) && ctx.player().isShiftKeyDown()) {
			Long started = this.trailStartedAt.get(ctx.player().getUUID());
			if (started != null && System.currentTimeMillis() - started >= 1000L) {
				endBloodTrail(ctx);
				return true;
			}
		}
		return false;
	}

	private void showKillsInfo(AbilityContext ctx, int kills) {
		ServerPlayer player = ctx.player();
		Messaging.send(player, "<dark_red><bold>===== BLOODLUST KILLS =====");
		Messaging.send(player, "<gray>Total Kills: <red>" + kills);
		Messaging.send(player, "<gray>Unlocked Abilities:");
		Messaging.send(player, "<green>\u2713 Infection (0+ kills)");
		Messaging.send(player, kills >= 1 ? "<green>\u2713 Speed II (1+ kill)" : "<gray>\u2717 Speed II (1+ kill)");
		Messaging.send(player, kills >= 2 ? "<green>\u2713 Blood Tracker (2+ kills)" : "<gray>\u2717 Blood Tracker (2+ kills)");
		Messaging.send(player, kills >= 3 ? "<green>\u2713 Blood Trail (3+ kills)" : "<gray>\u2717 Blood Trail (3+ kills)");
		Messaging.send(player, kills >= 4 ? "<green>\u2713 Strength I (4+ kills)" : "<gray>\u2717 Strength I (4+ kills)");
		Messaging.send(player, kills >= 5 ? "<green>\u2713 Blood Hook (5+ kills)" : "<gray>\u2717 Blood Hook (5+ kills)");
		Messaging.send(player, "<dark_red><bold>===========================");
	}

	// ------------------------------------------------------------- blood hook

	private void useBloodHook(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.bloodlust.hook_cooldown", 30);
		if (!ctx.gate(KEY_HOOK, "Blood Hook")) {
			return;
		}
		ctx.startCooldown(KEY_HOOK, cooldown);
		CooldownBars.show(player, KEY_HOOK, "Blood Hook", BossEvent.BossBarColor.RED, cooldown);
		shootBloodChain(ctx);
	}

	private void shootBloodChain(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		double maxDistance = ctx.cfgd("abilities.bloodlust.hook_distance", 20.0D);
		double speed = ctx.cfgd("abilities.bloodlust.hook_velocity", 2.2D);
		double damage = ctx.cfgd("abilities.bloodlust.hook_damage", 4.0D);
		double entityRadius = ctx.cfgd("abilities.bloodlust.hook_entity_radius", 1.2D);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();

		Display.BlockDisplay chain = Displays.block(level, start, Blocks.CHAIN.defaultBlockState());
		Displays.bright(chain);
		Displays.setScale(chain, 0.4F);
		this.hookChains.put(player.getUUID(), chain);
		Fx.sound(level, start, "BLOCK_LAVA_POP", 1.5F, 0.45F);

		final double[] travelled = {0.0D};
		com.altarsmp.fabric.util.TickScheduler.Task task = this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				Displays.remove(chain);
				this.hookChains.remove(player.getUUID());
				return;
			}
			Vec3 previous = start.add(direction.scale(travelled[0]));
			travelled[0] += speed;
			Vec3 head = start.add(direction.scale(travelled[0]));
			chain.snapTo(head.x, head.y, head.z, 0.0F, 0.0F);
			Fx.dust(level, head, BLOOD_BRIGHT, 0.7F, 2, 0.05D, 0.05D, 0.05D);

			// entity along this segment?
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(previous, head).inflate(entityRadius), e -> e != player && e.isAlive())) {
				if (distanceToSegment(entity.position(), previous, head) <= entityRadius) {
					TrueDamage.apply(entity, damage, player, true);
					Fx.sound(level, entity.position(), "ENTITY_PLAYER_SPLASH", 1.2F, 0.75F);
					pullEntityToPlayer(ctx, entity);
					finishHook(player, chain);
					return;
				}
			}
			// block collision
			if (!level.getBlockState(BlockPos.containing(head)).getCollisionShape(level, BlockPos.containing(head)).isEmpty()) {
				clearCobwebsNear(level, head, ctx.cfg("abilities.bloodlust.cobweb_clear_radius", 3));
				pullPlayerToPoint(ctx, head);
				finishHook(player, chain);
			} else if (travelled[0] >= maxDistance) {
				finishHook(player, chain);
			}
		}, 0L, 1L);
		task.cancelAfter((long) (maxDistance / speed) + 4L);
	}

	private void finishHook(ServerPlayer player, Display.BlockDisplay chain) {
		Displays.remove(chain);
		this.hookChains.remove(player.getUUID());
		Fx.sound(player.serverLevel(), player.position(), "BLOCK_LAVA_EXTINGUISH", 1.35F, 0.75F);
	}

	private static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
		Vec3 ab = b.subtract(a);
		double lengthSq = ab.lengthSqr();
		if (lengthSq == 0.0D) {
			return point.distanceTo(a);
		}
		double t = Math.max(0.0D, Math.min(1.0D, point.subtract(a).dot(ab) / lengthSq));
		return point.distanceTo(a.add(ab.scale(t)));
	}

	/** Reels a hooked entity in over {@code hook_pull_duration} ticks. */
	private void pullEntityToPlayer(AbilityContext ctx, LivingEntity entity) {
		double pullSpeed = ctx.cfgd("abilities.bloodlust.hook_pull_speed", 0.65D);
		double arc = ctx.cfgd("abilities.bloodlust.hook_pull_arc", 0.08D);
		double decelFactor = ctx.cfgd("abilities.bloodlust.hook_decel_factor", 0.85D);
		int decelStart = ctx.cfg("abilities.bloodlust.hook_decel_start_tick", 10);
		int duration = ctx.cfg("abilities.bloodlust.hook_pull_duration", 40);
		ServerPlayer player = ctx.player();
		final double[] factor = {pullSpeed};
		final int[] tick = {0};
		this.mod.scheduler().timer(() -> {
			tick[0]++;
			if (entity.isRemoved() || player.isRemoved()) {
				return;
			}
			if (tick[0] > decelStart) {
				factor[0] *= decelFactor;
			}
			Vec3 toPlayer = player.position().add(0.0D, 1.0D, 0.0D).subtract(entity.position()).normalize();
			Vec3 velocity = toPlayer.scale(factor[0]).add(0.0D, arc, 0.0D);
			entity.setDeltaMovement(velocity);
			entity.hurtMarked = true;
			entity.fallDistance = 0.0F;
			Fx.dust(ctx.level(), entity.position().add(0.0D, 0.5D, 0.0D), BLOOD, 0.6F, 2, 0.1D, 0.1D, 0.1D);
		}, 0L, 1L).cancelAfter(duration);
	}

	/** Grapples the holder to the block the chain hit. */
	private void pullPlayerToPoint(AbilityContext ctx, Vec3 target) {
		ServerPlayer player = ctx.player();
		Vec3 to = target.subtract(player.getEyePosition()).normalize().scale(ctx.cfgd("abilities.bloodlust.hook_pull_speed", 0.65D) * 2.4D);
		Motion.setVelocity(player, to.add(0.0D, 0.12D, 0.0D));
	}

	private void clearCobwebsNear(ServerLevel level, Vec3 center, int radius) {
		BlockPos origin = BlockPos.containing(center);
		int cleared = 0;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius), origin.offset(radius, radius, radius))) {
			if (level.getBlockState(pos).is(Blocks.COBWEB)) {
				level.removeBlock(pos, false);
				cleared++;
			}
		}
		if (cleared > 0) {
			Fx.sound(level, center, "BLOCK_WOOL_BREAK", 0.8F, 1.2F);
		}
	}

	// ------------------------------------------------------------ blood trail

	private void useBloodTrail(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.bloodlust.trail_cooldown", 60);
		int durationSeconds = ctx.cfg("abilities.bloodlust.trail_duration", 10);
		if (!ctx.gate(KEY_TRAIL, "Blood Trail")) {
			return;
		}
		ctx.startCooldown(KEY_TRAIL, cooldown);
		CooldownBars.show(player, KEY_TRAIL, "Blood Trail", BossEvent.BossBarColor.RED, durationSeconds);
		this.submerged.put(player.getUUID(), Boolean.TRUE);
		this.trailStartedAt.put(player.getUUID(), System.currentTimeMillis());

		AttributeFx.set(player, AttributeFx.SCALE, ctx.cfgd("abilities.bloodlust.trail_scale", 0.5D));
		AttributeFx.set(player, AttributeFx.STEP_HEIGHT, ctx.cfgd("abilities.bloodlust.trail_step_height", 1.5D));
		int ticks = durationSeconds * 20;
		Effects.apply(player, "INVISIBILITY", ticks, 0, false, false, false);
		Effects.apply(player, "SPEED", ticks, ctx.cfg("abilities.bloodlust.trail_speed_level", 2), false, false, false);
		Effects.apply(player, "RESISTANCE", ticks, ctx.cfg("abilities.bloodlust.trail_resistance_level", 9), false, false, false);
		Effects.apply(player, "MINING_FATIGUE", ticks, ctx.cfg("abilities.bloodlust.trail_mining_fatigue_level", 3), false, false, false);

		playDive(ctx.level(), player.position());
		Fx.dust(ctx.level(), player.position().add(0.0D, 0.2D, 0.0D), BLOOD_DARK, 1.0F, 15, 0.3D, 0.5D, 0.3D);

		this.mod.scheduler().later(() -> {
			if (this.submerged.containsKey(player.getUUID())) {
				endBloodTrail(new AbilityContext(this.mod, player, player.getMainHandItem(), id()));
			}
		}, ticks);
	}

	private void endBloodTrail(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		if (this.submerged.remove(player.getUUID()) == null) {
			return;
		}
		this.trailStartedAt.remove(player.getUUID());
		AttributeFx.restore(player, AttributeFx.SCALE);
		AttributeFx.restore(player, AttributeFx.STEP_HEIGHT);
		Effects.remove(player, "INVISIBILITY");
		Effects.remove(player, "SPEED");
		Effects.remove(player, "RESISTANCE");
		Effects.remove(player, "MINING_FATIGUE");
		CooldownBars.hide(player, KEY_TRAIL);
		spawnResurface(ctx.level(), player);
		boostExit(ctx, player);
	}

	private void boostExit(AbilityContext ctx, ServerPlayer player) {
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 boost = new Vec3(direction.x * ctx.cfgd("abilities.bloodlust.trail_exit_boost", 0.65D),
				ctx.cfgd("abilities.bloodlust.trail_exit_boost_y", 0.18D),
				direction.z * ctx.cfgd("abilities.bloodlust.trail_exit_boost", 0.65D));
		Motion.push(player, boost);
	}

	private void playDive(ServerLevel level, Vec3 pos) {
		Fx.sound(level, pos, "custom:bloodlust.dive", 1.25F, 0.55F);
		Fx.sound(level, pos, "BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE", 1.25F, 0.55F);
	}

	private void spawnResurface(ServerLevel level, ServerPlayer player) {
		Vec3 pos = player.position();
		Fx.sound(level, pos, "custom:bloodlust.resurface", 1.0F, 1.0F);
		Fx.sound(level, pos, "BLOCK_LAVA_EXTINGUISH", 1.0F, 0.8F);
		Fx.dust(level, pos.add(0.0D, 0.5D, 0.0D), BLOOD_BRIGHT, 1.2F, 40, 0.5D, 0.4D, 0.5D);
		Fx.simple(level, "FALLING_LAVA", pos.add(0.0D, 0.5D, 0.0D), 12, 0.2D, 0.2D, 0.2D, 0.01D);
	}

	// -------------------------------------------------------------- housekeeping

	/**
	 * While submerged the Resistance 10 effect already reduces every hit to
	 * nothing, so nothing is cancelled here - matching the plugin, which only
	 * listened for damage to break out of the trail on lethal hits.
	 */
	@Override
	public boolean onDamaged(AbilityContext ctx, net.minecraft.world.damagesource.DamageSource source, float amount,
			net.minecraft.world.entity.LivingEntity attacker) {
		return false;
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		AbilityContext ctx = new AbilityContext(this.mod, player, ItemStack.EMPTY, id());
		endBloodTrail(ctx);
		finishBleed(player);
		Display.BlockDisplay chain = this.hookChains.remove(player.getUUID());
		Displays.remove(chain);
		AttributeFx.restoreAll(player);
	}

	public boolean isSubmerged(ServerPlayer player) {
		return this.submerged.containsKey(player.getUUID());
	}

	public boolean isBleeding(UUID player) {
		return this.bleedUntil.containsKey(player);
	}

}
