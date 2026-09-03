package com.altarsmp.fabric.weapon.s1;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
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
 * Earth Gauntlet - port of {@code com.altarsmp.weapons.EarthGauntletWeapon}.
 *
 * <p><b>Meteor Strike</b> (F): the holder is launched skyward (vertical velocity
 * capped by {@code abilities.earthgauntlet.meteor_vertical_velocity_cap}) and the
 * armed window opens for {@code meteor_active_ticks}. Slamming back down - or
 * letting the window lapse - detonates the meteor: {@code meteor_damage} true
 * damage plus {@code meteor_knockback} to everything nearby, flame/gust bursts,
 * and Nausea for {@code meteor_nausea_duration}s. Cooldown
 * {@code meteor_cooldown}s.</p>
 *
 * <p><b>Mudslide</b> (Shift+F): lobs a mud ball out to
 * {@code mudslide_range} with real ballistic drop ({@code mudslide_gravity}).
 * Whatever it hits is turned to mud, takes {@code mud_damage} and becomes
 * <em>muddied</em> (Slowness {@code muddied_slowness_level} for
 * {@code muddied_duration}s). While a target is muddied the holder can yank it in
 * at {@code pull_velocity}. Mud reverts after {@code mud_revert_time} minutes.</p>
 */
public final class EarthGauntletWeapon implements WeaponBehavior {

	static final String KEY_METEOR = "earthgauntlet_meteor";
	static final String KEY_MUDSLIDE = "earthgauntlet_mudslide";

	private static final int MUD_BROWN = (139 << 16) | (90 << 8) | 43;
	private static final int EARTH_BROWN = (101 << 16) | (67 << 8) | 33;

	private final AltarSMPMod mod;
	private final Map<UUID, Long> meteorWindowUntil = new HashMap<>();
	private final Map<UUID, UUID> muddiedTargets = new HashMap<>();
	private final Map<UUID, Long> pullReadyUntil = new HashMap<>();

	public EarthGauntletWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "earthgauntlet";
	}

	@Override
	public String displayName() {
		return "Earth Gauntlet";
	}

	@Override
	public List<String> configFields() {
		return List.of("Meteor Cooldown (s)", "Mudslide Cooldown (s)", "Meteor Damage", "Mud Damage");
	}

	@Override
	public void onPrimary(AbilityContext ctx) {
		if (this.pullReadyUntil.getOrDefault(ctx.player().getUUID(), 0L) > System.currentTimeMillis()) {
			pullMuddiedTarget(ctx);
			return;
		}
		useMeteorStrike(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		useMudslide(ctx);
	}

	// ------------------------------------------------------------ meteor strike

	private void useMeteorStrike(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.earthgauntlet.meteor_cooldown", 30);
		int activeTicks = ctx.cfg("abilities.earthgauntlet.meteor_active_ticks", 120);
		double cap = ctx.cfgd("abilities.earthgauntlet.meteor_vertical_velocity_cap", 0.55D);
		if (!ctx.gate(KEY_METEOR, "Meteor Strike")) {
			return;
		}
		ctx.startCooldown(KEY_METEOR, cooldown);
		CooldownBars.show(player, KEY_METEOR, "Meteor Strike", BossEvent.BossBarColor.YELLOW, cooldown);
		this.meteorWindowUntil.put(player.getUUID(), System.currentTimeMillis() + activeTicks * 50L);

		Fx.sound(level, player.position(), "ENTITY_GENERIC_EXPLODE", 0.7F, 1.6F);
		Fx.dust(level, player.position().add(0.0D, 1.0D, 0.0D), MUD_BROWN, 1.5F, 25, 0.8D, 0.8D, 0.8D);
		Messaging.actionBar(player, "<gold><bold>METEOR STRIKE</bold> <gray>- land to detonate");

		// Launch: capped upward velocity for the first few ticks of the flight.
		final int[] boost = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			boost[0]++;
			Vec3 motion = player.getDeltaMovement();
			player.setDeltaMovement(motion.x, Math.min(cap, motion.y + cap * 0.5D), motion.z);
			player.hurtMarked = true;
			Fx.simple(level, "FLAME", player.position(), 6, 0.3D, 0.3D, 0.3D, 0.05D);
		}, 0L, 1L).cancelAfter(8L);

		// Detonate when the player lands or the armed window lapses.
		this.mod.scheduler().timer(() -> {
			Long until = this.meteorWindowUntil.get(player.getUUID());
			if (until == null || player.isRemoved()) {
				return;
			}
			boolean landed = player.onGround() && boost[0] >= 8;
			if (!landed && until > System.currentTimeMillis()) {
				Fx.simple(level, "FLAME", player.position(), 4, 0.4D, 0.4D, 0.4D, 0.1D);
				return;
			}
			this.meteorWindowUntil.remove(player.getUUID());
			meteorImpact(ctx, player.position());
		}, 10L, 2L).cancelAfter(activeTicks + 10L);
	}

	private void meteorImpact(AbilityContext ctx, Vec3 impact) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		double damage = ctx.cfgd("abilities.earthgauntlet.meteor_damage", 7.0D);
		double knockback = ctx.cfgd("abilities.earthgauntlet.meteor_knockback", 8.0D);
		double radius = ctx.cfgd("abilities.earthgauntlet.meteor_radius", 6.0D);
		int nausea = ctx.cfg("abilities.earthgauntlet.meteor_nausea_duration", 5);

		Fx.sound(level, impact, "ENTITY_GENERIC_EXPLODE", 2.0F, 0.6F);
		Fx.sound(level, impact, "BLOCK_ANVIL_LAND", 1.0F, 0.7F);
		Fx.simple(level, "EXPLOSION_EMITTER", impact, 2, 0.5D, 0.3D, 0.5D, 0.0D);
		Fx.simple(level, "FLAME", impact, 60, 0.8D, 0.6D, 0.8D, 0.15D);
		Fx.simple(level, "GUST", impact, 40, 0.8D, 0.8D, 0.8D, 0.15D);
		Fx.dust(level, impact, EARTH_BROWN, 2.0F, 60, 0.9D, 0.6D, 0.9D);
		Fx.blockParticles(level, "MUD", impact, 300, 1.0D, 1.0D, 1.0D, 0.0D);
		Fx.lightning(level, impact, true);

		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(impact, impact).inflate(radius), e -> e != player && e.isAlive())) {
			TrueDamage.apply(entity, damage, player, false);
			Vec3 away = entity.position().subtract(impact);
			double distance = Math.max(0.5D, away.length());
			Vec3 push = away.normalize().scale(Math.min(knockback, knockback / distance));
			entity.setDeltaMovement(push.x, Math.min(0.9D, push.y + 0.45D), push.z);
			entity.hurtMarked = true;
			if (entity instanceof ServerPlayer victim) {
				Effects.apply(victim, "NAUSEA", nausea * 20, 0);
			}
		}
		if (ctx.canDestroyTerrain() && ctx.cfgb("abilities.earthgauntlet.meteor_carve", false)) {
			BlockPos origin = BlockPos.containing(impact);
			for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-2, -2, -2), origin.offset(2, 0, 2))) {
				if (!level.getBlockState(pos).is(Blocks.BEDROCK) && level.getBlockState(pos).getDestroySpeed(level, pos) >= 0.0F) {
					level.removeBlock(pos, false);
				}
			}
		}
	}

	// ----------------------------------------------------------------- mudslide

	private void useMudslide(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int cooldown = ctx.cfg("abilities.earthgauntlet.mudslide_cooldown", 18);
		if (!ctx.gate(KEY_MUDSLIDE, "Mudslide")) {
			return;
		}
		ctx.startCooldown(KEY_MUDSLIDE, cooldown);
		CooldownBars.show(player, KEY_MUDSLIDE, "Mudslide", BossEvent.BossBarColor.GREEN, cooldown);

		double range = ctx.cfgd("abilities.earthgauntlet.mudslide_range", 100.0D);
		double gravity = ctx.cfgd("abilities.earthgauntlet.mudslide_gravity", 0.0025D);
		double mudDamage = ctx.cfgd("abilities.earthgauntlet.mud_damage", 2.0D);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 position = player.getEyePosition();
		net.minecraft.world.entity.Display.ItemDisplay mudball =
				Displays.item(level, position, new ItemStack(Items.MUD), 0.7F);
		Displays.bright(mudball);
		Fx.sound(level, position, "BLOCK_MUD_PLACE", 1.0F, 0.8F);

		final Vec3[] velocity = {direction.scale(1.6D)};
		final Vec3[] head = {position};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				Displays.remove(mudball);
				return;
			}
			velocity[0] = velocity[0].subtract(0.0D, gravity, 0.0D);
			head[0] = head[0].add(velocity[0]);
			mudball.snapTo(head[0].x, head[0].y, head[0].z, 0.0F, 0.0F);
			Fx.dust(level, head[0], EARTH_BROWN, 1.2F, 8, 0.2D, 0.2D, 0.2D);
			Fx.blockParticles(level, "MUD", head[0], 5, 0.1D, 0.1D, 0.1D, 0.0D);

			BlockPos at = BlockPos.containing(head[0]);
			boolean hitBlock = !level.getBlockState(at).getCollisionShape(level, at).isEmpty();
			List<LivingEntity> struck = level.getEntitiesOfClass(LivingEntity.class,
					new AABB(head[0], head[0]).inflate(1.2D), e -> e != player && e.isAlive());
			if (hitBlock || !struck.isEmpty() || head[0].distanceTo(position) > range) {
				Displays.remove(mudball);
				Fx.sound(level, head[0], "BLOCK_MUD_BREAK", 1.0F, 0.9F);
				Fx.blockParticles(level, "MUD", head[0], 40, 0.6D, 0.4D, 0.6D, 0.05D);
				applyMuddied(ctx, head[0], struck, mudDamage);
			}
		}, 0L, 1L).cancelAfter((long) (range / 1.6D) + 20L);
	}

	private void applyMuddied(AbilityContext ctx, Vec3 impact, List<LivingEntity> struck, double mudDamage) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		int duration = ctx.cfg("abilities.earthgauntlet.muddied_duration", 4);
		int slowness = ctx.cfg("abilities.earthgauntlet.muddied_slowness_level", 2);
		int revertMinutes = ctx.cfg("abilities.earthgauntlet.mud_revert_time", 1);

		BlockPos origin = BlockPos.containing(impact).below();
		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> replaced = new HashMap<>();
		if (ctx.canDestroyTerrain()) {
			for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(1, 1, 1))) {
				net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
				if (state.isAir() || state.is(Blocks.MUD) || state.getDestroySpeed(level, pos) < 0.0F) {
					continue;
				}
				replaced.put(pos.immutable(), state);
				level.setBlock(pos, Blocks.MUD.defaultBlockState(), 3);
			}
			if (!replaced.isEmpty()) {
				this.mod.scheduler().later(() -> replaced.forEach((pos, state) -> {
					if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.MUD)) {
						level.setBlock(pos, state, 3);
					}
				}), revertMinutes * 1200L);
			}
		}

		for (LivingEntity entity : struck) {
			TrueDamage.apply(entity, mudDamage, player, false);
			Effects.apply(entity, "SLOWNESS", duration * 20, slowness);
			Fx.dust(level, entity.position().add(0.0D, 1.0D, 0.0D), EARTH_BROWN, 1.2F, 12, 0.3D, 0.3D, 0.3D);
			if (entity instanceof ServerPlayer victim) {
				Messaging.actionBar(victim, "<dark_green>You are <bold>MUDDIED</bold>!");
				this.muddiedTargets.put(player.getUUID(), victim.getUUID());
				this.pullReadyUntil.put(player.getUUID(), System.currentTimeMillis() + duration * 1000L);
				CooldownBars.show(player, "earthgauntlet_pull", "Pull Ready", BossEvent.BossBarColor.GREEN, duration);
			}
		}
	}

	private void pullMuddiedTarget(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		UUID targetId = this.muddiedTargets.remove(player.getUUID());
		this.pullReadyUntil.remove(player.getUUID());
		CooldownBars.hide(player, "earthgauntlet_pull");
		if (targetId == null) {
			return;
		}
		ServerPlayer target = player.serverLevel().getServer().getPlayerList().getPlayer(targetId);
		if (target == null || target.isRemoved() || target.level() != player.level()) {
			return;
		}
		double pull = ctx.cfgd("abilities.earthgauntlet.pull_velocity", 1.5D);
		Vec3 toPlayer = player.position().add(0.0D, 1.0D, 0.0D).subtract(target.position()).normalize().scale(pull);
		target.setDeltaMovement(toPlayer.x, toPlayer.y + 0.15D, toPlayer.z);
		target.hurtMarked = true;
		target.fallDistance = 0.0F;
		Fx.sound(ctx.level(), target.position(), "ENTITY_FISHING_BOBBER_RETRIEVE", 1.0F, 0.7F);
		Fx.dust(ctx.level(), target.position(), EARTH_BROWN, 1.4F, 20, 0.4D, 0.4D, 0.4D);
		Messaging.send(player, "<dark_green>You yanked <white>" + target.getGameProfile().getName() + "</white> in!");
	}

	@Override
	public boolean onDamaged(AbilityContext ctx, DamageSource source, float amount, LivingEntity attacker) {
		Fx.blockParticles(ctx.level(), "MUD", ctx.player().position().add(0.0D, 1.0D, 0.0D), 8, 0.2D, 0.2D, 0.2D, 0.0D);
		Fx.dust(ctx.level(), ctx.player().position().add(0.0D, 1.0D, 0.0D), EARTH_BROWN, 1.2F, 8, 0.2D, 0.2D, 0.2D);
		return false;
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		this.meteorWindowUntil.remove(player.getUUID());
		this.muddiedTargets.remove(player.getUUID());
		this.pullReadyUntil.remove(player.getUUID());
		CooldownBars.hide(player, KEY_METEOR);
		CooldownBars.hide(player, KEY_MUDSLIDE);
		CooldownBars.hide(player, "earthgauntlet_pull");
	}

	public boolean hasMeteorWindow(ServerPlayer player) {
		Long until = this.meteorWindowUntil.get(player.getUUID());
		return until != null && until > System.currentTimeMillis();
	}

	public int activeMeteors() {
		return this.meteorWindowUntil.size();
	}
}
