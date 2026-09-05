package com.altarsmp.fabric.weapon.s2;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mojang.math.Transformation;

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

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Ancient Blade (Season 2) - port of {@code com.altarsmps2.weapons.AncientBladeWeapon}
 * and {@code com.altarsmps2.abilities.AncientBladeAbilities}.
 *
 * <p>Every ability is gated on player kills stored on the blade itself
 * ({@code altarsmps2:ab_kills}, mirrored into the player record):</p>
 * <ul>
 *   <li><b>1 kill - Echolocation</b>: sneak to ping; a warden heartbeat plays and a
 *       sculk tendril ring forms, plus a shrieker sound for each untrusted player
 *       within {@code echolocation.range} ({@code echolocation.cooldown}s).</li>
 *   <li><b>2 kills - Swift</b>: Speed {@code swift.amplifier} refreshed every second.</li>
 *   <li><b>3 kills - Presence</b> (F, {@code presence.cooldown}s): your next player
 *       hit within 10s marks them with Darkness and suppresses their absorption for
 *       {@code presence.mark_duration}s.</li>
 *   <li><b>4 kills - Tightened Grip</b>: Strength {@code tightened_grip.amplifier}.</li>
 *   <li><b>5 kills - Unyielding Darkness</b> (Shift+F, {@code unyielding_darkness.cooldown}s):
 *       leap, hover, then fire a spinning sculk catalyst that detonates into
 *       {@code sonic_count} radial shockwaves dealing
 *       {@code unyielding_darkness.damage} and temporarily sculk-ifying exposed
 *       terrain (reverted after 15s).</li>
 * </ul>
 *
 * <p><b>Deep Connection</b>: while standing on sculk the holder gains
 * {@code deep_connection.speed_bonus} extra movement speed through a real
 * attribute modifier that is removed again when they step off. Melee hits draw the
 * blue sculk slash arc.</p>
 */
public final class AncientBladeWeapon implements WeaponBehavior {

	static final String KEY_PRESENCE = "ab_presence";
	static final String KEY_DARKNESS = "ab_darkness";
	static final String KEY_ECHO = "ab_echo";
	static final String KEY_SPEED = "ab_deep_connection";

	private static final int SCULK_BLUE = 0x50A0FF;
	private static final int FLASH_BLUE = 0x78C8FF;
	private static final int MARK_BLUE = 0x143C8C;
	private static final long PRESENCE_WINDOW_MILLIS = 10000L;
	private static final long SCULK_REVERT_TICKS = 300L;
	private static final double PROJECTILE_MAX_DISTANCE = 32.0D;
	private static final double SHOCKWAVE_DISTANCE = 10.0D;

	private final AltarSMPMod mod;
	private final Map<UUID, Long> presenceWindow = new HashMap<>();

	public AncientBladeWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "ancientblade";
	}

	@Override
	public int season() {
		return 2;
	}

	@Override
	public String displayName() {
		return "Ancient Blade";
	}

	@Override
	public List<String> configFields() {
		return List.of("Echolocation Range/Cooldown/Kills", "Swift Kills/Amplifier",
				"Presence Cooldown/Kills/Mark Duration/Mark Amplifier", "Tightened Grip Kills/Amplifier",
				"Unyielding Darkness Cooldown/Kills/Damage/Sonic Count", "Deep Connection Speed Bonus");
	}

	// -------------------------------------------------------------------- kills

	public static int kills(ItemStack blade) {
		return Identity.kills(blade, "ancientblade");
	}

	@Override
	public void onKill(AbilityContext ctx, LivingEntity victim) {
		ItemStack blade = ctx.weapon();
		if (!Identity.is(blade, id()) || !(victim instanceof ServerPlayer)) {
			return;
		}
		int total = kills(blade) + 1;
		Identity.setKills(blade, total);
		ctx.record().ancientBladeKills(total);
		ctx.record().addWeaponKill(id(), 1);
		Messaging.actionBar(ctx.player(), "<aqua>Ancient Blade kills: <white>" + total);
	}

	// ------------------------------------------------------------------ passives

	@Override
	public void onTick(AbilityContext ctx) {
		if (this.mod.scheduler().currentTick() % 20L != 0L) {
			return;
		}
		ServerPlayer player = ctx.player();
		ItemStack blade = player.getMainHandItem();
		if (!Identity.is(blade, id())) {
			removeSpeedBonus(player);
			return;
		}
		int total = kills(blade);

		if (total >= ctx.cfg("abilities.ancientblade.swift.kills_required", 2)) {
			Effects.apply(player, "SPEED", 60, ctx.cfg("abilities.ancientblade.swift.amplifier", 0),
					true, false, false);
		}
		if (total >= ctx.cfg("abilities.ancientblade.tightened_grip.kills_required", 4)) {
			Effects.apply(player, "STRENGTH", 60, ctx.cfg("abilities.ancientblade.tightened_grip.amplifier", 1),
					true, false, false);
		}
		applyDeepConnection(ctx, player);

		if (player.isShiftKeyDown()
				&& total >= ctx.cfg("abilities.ancientblade.echolocation.kills_required", 1)
				&& !ctx.mod().cooldowns().isOnCooldown(player, KEY_ECHO)) {
			echolocate(ctx, player);
		}
	}

	/** {@code AncientBladeAbilities#isOnSculk} + the movement-speed modifier. */
	private void applyDeepConnection(AbilityContext ctx, ServerPlayer player) {
		var instance = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
		if (instance == null) {
			return;
		}
		Identifier modifierId = Identifier.fromNamespaceAndPath(AltarSMPMod.MOD_ID, "ancient_blade_deep_connection");
		boolean onSculk = isOnSculk(player);
		boolean hasModifier = instance.getModifier(modifierId) != null;
		if (onSculk && !hasModifier) {
			double bonus = ctx.cfgd("abilities.ancientblade.deep_connection.speed_bonus", 0.25D);
			instance.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
					modifierId, bonus,
					net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} else if (!onSculk && hasModifier) {
			instance.removeModifier(modifierId);
		}
	}

	private void removeSpeedBonus(ServerPlayer player) {
		var instance = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
		if (instance != null) {
			instance.removeModifier(Identifier.fromNamespaceAndPath(AltarSMPMod.MOD_ID, "ancient_blade_deep_connection"));
		}
	}

	boolean isOnSculk(ServerPlayer player) {
		BlockPos below = BlockPos.containing(player.position().subtract(0.0D, 0.1D, 0.0D));
		Block block = player.serverLevel().getBlockState(below).getBlock();
		return block == Blocks.SCULK || block == Blocks.SCULK_CATALYST || block == Blocks.SCULK_SHRIEKER
				|| block == Blocks.SCULK_SENSOR || block == Blocks.SCULK_VEIN;
	}

	/** {@code AncientBladeAbilities} echolocation: heartbeat, tendrils, remote shrieks. */
	private void echolocate(AbilityContext ctx, ServerPlayer player) {
		ServerLevel level = ctx.level();
		int range = ctx.cfg("abilities.ancientblade.echolocation.range", 24);
		int cooldown = ctx.cfg("abilities.ancientblade.echolocation.cooldown", 3);
		ctx.mod().cooldowns().setCooldownSeconds(player, KEY_ECHO, cooldown);
		Fx.soundTo(player, "ENTITY_WARDEN_HEARTBEAT", 1.0F, 1.0F);
		spawnTendrilRing(level, player.position().add(0.0D, 1.6D, 0.0D));
		for (ServerPlayer other : level.players()) {
			if (other == player || !ctx.abilityAllowedOn(other)
					|| other.position().distanceToSqr(player.position()) > (double) range * range) {
				continue;
			}
			Fx.soundAt(player, other.position(), "BLOCK_SCULK_SHRIEKER_SHRIEK", 1.0F, 1.0F);
		}
	}

	/** {@code AncientBladeAbilities#spawnTendrilRing} - eight spiralling tendrils. */
	private void spawnTendrilRing(ServerLevel level, Vec3 center) {
		for (int index = 0; index < 8; index++) {
			double base = Math.PI * 2.0D * index / 8.0D;
			final int[] step = {0};
			this.mod.scheduler().timer(() -> {
				if (step[0] >= 6) {
					return;
				}
				double progress = step[0] / 6.0D;
				double angle = base + progress * 0.8D;
				double radius = Math.sin(progress * Math.PI);
				Vec3 point = center.add(Math.cos(angle) * radius, -0.05D + radius * 0.3D, Math.sin(angle) * radius);
				Fx.simple(level, "SCULK_SOUL", point, 1, 0.0D, 0.0D, 0.0D, 0.0D);
				Fx.simple(level, "SCULK_CHARGE_POP", point, 1, 0.0D, 0.0D, 0.0D, 0.0D);
				step[0]++;
			}, 0L, 1L).cancelAfter(8L);
		}
	}

	// ----------------------------------------------------------------- presence

	@Override
	public void onPrimary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int required = ctx.cfg("abilities.ancientblade.presence.kills_required", 3);
		if (kills(ctx.weapon()) < required) {
			Messaging.actionBar(player, "<red>Need " + required + " kills");
			return;
		}
		if (!ctx.gate(KEY_PRESENCE, "Presence")) {
			return;
		}
		int cooldown = ctx.cfg("abilities.ancientblade.presence.cooldown", 45);
		ctx.startCooldown(KEY_PRESENCE, cooldown);
		CooldownBars.show(player, KEY_PRESENCE, "Presence", BossEvent.BossBarColor.BLUE, cooldown);
		this.presenceWindow.put(player.getUUID(), System.currentTimeMillis() + PRESENCE_WINDOW_MILLIS);
		Messaging.actionBar(player, "<aqua>Presence - your next hit marks them");
		ServerLevel level = ctx.level();
		Vec3 at = player.position();
		Fx.sound(level, at, "BLOCK_SCULK_SHRIEKER_SHRIEK", 2.0F, 0.9F);
		Fx.sound(level, at, "BLOCK_SCULK_SHRIEKER_SHRIEK", 2.0F, 1.1F);
		for (int index = 0; index < 32; index++) {
			double angle = Math.PI * 2.0D * index / 32.0D;
			double x = Math.cos(angle);
			double z = Math.sin(angle);
			Fx.simple(level, "SCULK_SOUL", at.add(x * 0.3D, 0.1D, z * 0.3D), 0, x * 0.6D, 0.05D, z * 0.6D, 1.0D);
		}
		for (int index = 0; index < 32; index++) {
			double angle = Math.PI * 2.0D * index / 32.0D;
			Fx.simple(level, "SCULK_SOUL", at.add(Math.cos(angle) * 1.5D, 0.1D, Math.sin(angle) * 1.5D),
					1, 0.05D, 0.05D, 0.05D, 0.0D);
		}
		Fx.sound(level, at, "BLOCK_SCULK_CATALYST_BLOOM", 1.4F, 1.0F);
	}

	// ------------------------------------------------------- unyielding darkness

	@Override
	public void onSecondary(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int required = ctx.cfg("abilities.ancientblade.unyielding_darkness.kills_required", 5);
		if (kills(ctx.weapon()) < required) {
			Messaging.actionBar(player, "<red>Need " + required + " kills");
			return;
		}
		if (!ctx.gate(KEY_DARKNESS, "Unyielding Darkness")) {
			return;
		}
		int cooldown = ctx.cfg("abilities.ancientblade.unyielding_darkness.cooldown", 60);
		ctx.startCooldown(KEY_DARKNESS, cooldown);
		CooldownBars.show(player, KEY_DARKNESS, "Unyielding Darkness", BossEvent.BossBarColor.PURPLE, cooldown);
		double damage = ctx.cfgd("abilities.ancientblade.unyielding_darkness.damage", 8.0D);
		int sonicCount = ctx.cfg("abilities.ancientblade.unyielding_darkness.sonic_count", 5);

		Motion.setVelocity(player, new Vec3(0.0D, 1.4D, 0.0D));
		Fx.sound(ctx.level(), player.position(), "BLOCK_SCULK_CATALYST_BLOOM", 1.0F, 0.7F);

		final int[] puff = {0};
		this.mod.scheduler().timer(() -> {
			if (puff[0]++ > 25 || player.isRemoved()) {
				return;
			}
			Fx.blockParticles(ctx.level(), "SCULK", player.position().add(0.0D, 0.5D, 0.0D),
					8, 0.4D, 0.4D, 0.4D, 0.05D);
		}, 0L, 1L).cancelAfter(30L);

		final boolean[] descending = {false};
		final int[] hover = {0};
		this.mod.scheduler().timer(() -> {
			if (player.isRemoved()) {
				return;
			}
			if (!descending[0] && player.getDeltaMovement().y <= 0.05D) {
				descending[0] = true;
			}
			if (!descending[0]) {
				return;
			}
			player.setDeltaMovement(Vec3.ZERO);
			player.hurtMarked = true;
			player.resetFallDistance();
			if (hover[0]++ >= 8) {
				fireCatalystProjectile(ctx, damage, sonicCount);
				Vec3 recoil = player.getLookAngle().normalize().scale(-0.15D);
				Motion.setVelocity(player, new Vec3(recoil.x, 0.1D, recoil.z));
			}
		}, 0L, 1L).cancelAfter(200L);
	}

	/** {@code AncientBladeAbilities#fireCatalystProjectile} - the spinning catalyst. */
	private void fireCatalystProjectile(AbilityContext ctx, double damage, int sonicCount) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 start = player.getEyePosition();
		Display.BlockDisplay catalyst = Displays.block(level, start, Blocks.SCULK_CATALYST.defaultBlockState());
		Displays.interpolate(catalyst, 0, 2);
		Displays.teleportInterpolation(catalyst, 2);
		Displays.tag(catalyst, "altarsmp_ab_catalyst");

		final double[] travelled = {0.0D};
		final float[] spin = {0.0F};
		final Vec3[] previous = {start};
		this.mod.scheduler().timer(() -> {
			if (catalyst.isRemoved() || travelled[0] >= PROJECTILE_MAX_DISTANCE) {
				if (travelled[0] >= PROJECTILE_MAX_DISTANCE) {
					detonate(ctx, start.add(direction.scale(travelled[0])), damage, sonicCount);
				}
				Displays.remove(catalyst);
				return;
			}
			Vec3 point = start.add(direction.scale(travelled[0]));
			catalyst.setPos(point);
			spin[0] += 0.6F;
			float cos = (float) Math.cos(spin[0]);
			float sin = (float) Math.sin(spin[0]);
			Displays.interpolate(catalyst, 0, 2);
			catalyst.setTransformation(new Transformation(new Vector3f(-1.0F, -1.0F * (cos - sin),
					-1.0F * (sin + cos)), new AxisAngle4f(spin[0], 1.0F, 0.0F, 0.0F),
					new Vector3f(2.0F, 2.0F, 2.0F), new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)));
			Fx.blockParticles(level, "SCULK", point, 6, 0.3D, 0.3D, 0.3D, 0.0D);

			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(BlockPos.containing(point)).inflate(1.5D), e -> e != player && e.isAlive())) {
				if (victim instanceof ServerPlayer target && !ctx.abilityAllowedOn(target)) {
					continue;
				}
				detonate(ctx, point, damage, sonicCount);
				Displays.remove(catalyst);
				return;
			}
			Vec3 delta = point.subtract(previous[0]);
			if (delta.length() > 0.0D && !level.noCollision(new AABB(previous[0], point).inflate(0.05D))) {
				Vec3 hit = previous[0].add(delta.normalize().scale(delta.length() * 0.5D));
				detonate(ctx, hit.subtract(delta.normalize()), damage, sonicCount);
				Displays.remove(catalyst);
				return;
			}
			previous[0] = point;
			travelled[0] += 1.0D;
		}, 0L, 2L).cancelAfter((long) (PROJECTILE_MAX_DISTANCE * 2.0D) + 10L);
	}

	/** {@code AncientBladeAbilities} impact: boom visuals, sculk spread, radial shockwaves. */
	private void detonate(AbilityContext ctx, Vec3 at, double damage, int sonicCount) {
		ServerLevel level = ctx.level();
		ServerPlayer player = ctx.player();
		Fx.simple(level, "SONIC_BOOM", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		for (int index = 0; index < 4; index++) {
			Vec3 point = at.add((ctx.random().nextDouble() - 0.5D) * 0.8D,
					ctx.random().nextDouble() * 0.8D - 0.2D, (ctx.random().nextDouble() - 0.5D) * 0.8D);
			Fx.colored(level, "FLASH", point, FLASH_BLUE, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		for (int index = 0; index < 5; index++) {
			Vec3 point = at.add((ctx.random().nextDouble() - 0.5D) * 6.0D,
					ctx.random().nextDouble() * 3.0D - 0.5D, (ctx.random().nextDouble() - 0.5D) * 6.0D);
			Fx.colored(level, "FLASH", point, FLASH_BLUE, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		replaceWithSculkTemporarily(ctx, at);
		Fx.sound(level, at, "ENTITY_WARDEN_SONIC_BOOM", 2.0F, 1.0F);
		Fx.sound(level, at, "ENTITY_GENERIC_EXPLODE", 1.2F, 0.7F + ctx.random().nextFloat() * 0.6F);
		Fx.sound(level, at, "BLOCK_SCULK_CATALYST_BLOOM", 1.5F, 0.5F + ctx.random().nextFloat() * 0.5F);
		Fx.sound(level, at, "BLOCK_SCULK_SHRIEKER_SHRIEK", 1.0F, 0.6F + ctx.random().nextFloat() * 0.6F);
		Fx.sound(level, at, "ENTITY_WITHER_BREAK_BLOCK", 0.8F, 0.5F + ctx.random().nextFloat() * 0.5F);

		final int[] burst = {0};
		this.mod.scheduler().timer(() -> {
			if (burst[0]++ >= 3) {
				return;
			}
			for (int index = 0; index < 32; index++) {
				double angle = ctx.random().nextDouble() * Math.PI * 2.0D;
				double lift = (ctx.random().nextDouble() - 0.5D) * 0.8D;
				double distance = 0.5D + ctx.random().nextDouble() * 9.5D;
				Vec3 point = at.add(Math.cos(angle) * distance, lift * distance, Math.sin(angle) * distance);
				int count = 6 + ctx.random().nextInt(6);
				Fx.blockParticles(level, "SCULK", point, count, 0.4D, 0.4D, 0.4D, 0.2D);
				Fx.simple(level, "SCULK_SOUL", point, count / 2, 0.3D, 0.3D, 0.3D, 0.05D);
			}
		}, 0L, 3L).cancelAfter(12L);

		for (int index = 0; index < sonicCount; index++) {
			double angle = Math.PI * 2.0D * index / sonicCount;
			Vec3 direction = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
			final double[] travelled = {0.0D};
			this.mod.scheduler().timer(() -> {
				if (travelled[0] >= SHOCKWAVE_DISTANCE || player.isRemoved()) {
					return;
				}
				Vec3 point = at.add(direction.scale(travelled[0]));
				Fx.simple(level, "SCULK_SOUL", point, 3, 0.2D, 0.2D, 0.2D, 0.02D);
				for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
						new AABB(BlockPos.containing(point)).inflate(1.5D), e -> e != player && e.isAlive())) {
					if (victim instanceof ServerPlayer target && !ctx.abilityAllowedOn(target)) {
						continue;
					}
					TrueDamage.apply(victim, damage, player, false);
				}
				travelled[0] += 1.0D;
			}, 0L, 1L).cancelAfter((long) SHOCKWAVE_DISTANCE + 5L);
		}
	}

	/** {@code AncientBladeAbilities#replaceBlocksWithSculkTemporarily}. */
	private void replaceWithSculkTemporarily(AbilityContext ctx, Vec3 at) {
		if (!ctx.canDestroyTerrain()) {
			return;
		}
		ServerLevel level = ctx.level();
		BlockPos origin = BlockPos.containing(at);
		Map<BlockPos, BlockState> restore = new HashMap<>();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					BlockState state = level.getBlockState(pos);
					if (state.isAir() || isProtectedFromSculk(level, pos, state) || !isExposedToAirOrPlants(level, pos)) {
						continue;
					}
					restore.put(pos, state);
					level.setBlock(pos, Blocks.SCULK.defaultBlockState(), 3);
				}
			}
		}
		this.mod.scheduler().later(() -> {
			for (Map.Entry<BlockPos, BlockState> entry : restore.entrySet()) {
				if (level.getBlockState(entry.getKey()).is(Blocks.SCULK)) {
					level.setBlock(entry.getKey(), entry.getValue(), 3);
				}
			}
		}, SCULK_REVERT_TICKS);
	}

	/** {@code AncientBladeAbilities#isProtectedFromSculk} - includes containers. */
	static boolean isProtectedFromSculk(ServerLevel level, BlockPos pos, BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.BEDROCK || block == Blocks.BARRIER || block == Blocks.COMMAND_BLOCK
				|| block == Blocks.CHAIN_COMMAND_BLOCK || block == Blocks.REPEATING_COMMAND_BLOCK
				|| block == Blocks.STRUCTURE_BLOCK || block == Blocks.STRUCTURE_VOID
				|| block == Blocks.JIGSAW) {
			return true;
		}
		BlockEntity entity = level.getBlockEntity(pos);
		return entity instanceof net.minecraft.world.WorldlyContainer
				|| (entity != null && entity instanceof net.minecraft.world.Container);
	}

	/** {@code AncientBladeAbilities#isExposedToAirOrPlants}. */
	static boolean isExposedToAirOrPlants(ServerLevel level, BlockPos pos) {
		for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
			BlockPos neighbour = pos.relative(face);
			BlockState state = level.getBlockState(neighbour);
			if (state.isAir()) {
				return true;
			}
			Block block = state.getBlock();
			if (block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS || block == Blocks.FERN
					|| block == Blocks.LARGE_FERN || state.is(net.minecraft.tags.BlockTags.FLOWERS)) {
				return true;
			}
		}
		return false;
	}

	// -------------------------------------------------------------------- melee

	@Override
	public void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		spawnBlueSlash(level, player, target);
		if (!(target instanceof ServerPlayer victim)) {
			return;
		}
		Long until = this.presenceWindow.get(player.getUUID());
		if (until == null || System.currentTimeMillis() > until) {
			return;
		}
		this.presenceWindow.remove(player.getUUID());
		int duration = ctx.cfg("abilities.ancientblade.presence.mark_duration", 10) * 20;
		int amplifier = ctx.cfg("abilities.ancientblade.presence.mark_amplifier", 0);
		Effects.apply(victim, "DARKNESS", duration, amplifier, true, true, true);
		victim.setAbsorptionAmount(0.0F);
		Messaging.actionBar(victim, "<dark_aqua>Marked by Presence");
		Messaging.send(victim, "<red>Your Absorption was suppressed by <red><bold>MARKED</bold><red>.");
		Fx.sound(level, victim.position(), "ENTITY_WARDEN_SONIC_BOOM", 1.4F, 1.0F);
		Fx.sound(level, victim.position(), "ENTITY_PLAYER_ATTACK_SWEEP", 1.6F, 0.6F);
		final int[] elapsed = {0};
		this.mod.scheduler().timer(() -> {
			if (elapsed[0] >= duration || victim.isRemoved() || !victim.isAlive()) {
				return;
			}
			victim.setAbsorptionAmount(0.0F);
			elapsed[0] += 5;
		}, 5L, 5L).cancelAfter(duration + 10L);

		Vec3 center = victim.position().add(0.0D, 1.0D, 0.0D);
		Fx.simple(level, "SONIC_BOOM", center, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		Fx.colored(level, "FLASH", center, MARK_BLUE, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		for (int x = 0; x <= 1; x++) {
			for (int z = 0; z <= 1; z++) {
				Vec3 corner = center.add(x - 0.5D, 0.0D, z - 0.5D);
				Fx.blockParticles(level, "SCULK", corner, 12, 0.4D, 0.4D, 0.4D, 0.1D);
				Fx.simple(level, "SCULK_SOUL", corner, 6, 0.3D, 0.3D, 0.3D, 0.05D);
			}
		}
	}

	/** {@code AncientBladeAbilities#spawnBlueSlash} - the rotating wax-off arc. */
	private void spawnBlueSlash(ServerLevel level, ServerPlayer player, LivingEntity target) {
		Vec3 center = target.position().add(0.0D, 1.0D, 0.0D);
		Vec3 forward = player.getLookAngle().normalize();
		Vec3 side = forward.cross(new Vec3(0.0D, 1.0D, 0.0D));
		if (side.lengthSqr() < 1.0E-4D) {
			side = new Vec3(1.0D, 0.0D, 0.0D);
		}
		side = side.normalize();
		Vec3 vertical = side.cross(forward).normalize();
		double roll = level.random.nextDouble() * Math.PI * 2.0D - Math.PI;
		Vec3 first = side.scale(Math.cos(roll)).add(vertical.scale(Math.sin(roll)));
		Vec3 second = side.scale(-Math.sin(roll)).add(vertical.scale(Math.cos(roll)));
		for (int index = 0; index <= 16; index++) {
			double t = index / 16.0D;
			double angle = (t - 0.5D) * 1.7278759594743864D;
			Vec3 offset = first.scale(Math.sin(angle) * 1.4D).add(second.scale(Math.cos(angle) * 1.4D * 0.25D));
			Vec3 point = center.add(offset);
			Fx.simple(level, "WAX_OFF", point, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			Fx.dust(level, point, SCULK_BLUE, 1.0F, 1, 0.0D, 0.0D, 0.0D);
		}
	}

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		this.presenceWindow.remove(player.getUUID());
		removeSpeedBonus(player);
		CooldownBars.hide(player, KEY_PRESENCE);
		CooldownBars.hide(player, KEY_DARKNESS);
		Displays.removeTagged(player.serverLevel(), "altarsmp_ab_catalyst");
	}
}
