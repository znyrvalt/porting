package com.altarsmp.fabric.weapon.s2;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
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
 * Omen (Season 2) - port of {@code com.altarsmps2.weapons.OmenWeapon} and
 * {@code com.altarsmps2.abilities.OmenAbilities}.
 *
 * <p><b>Vault Omen</b> (F, {@code vault_omen.cooldown}s): fires a copper-flame
 * bolt from the eyes ({@code vault_omen.projectile_speed} blocks/second, up to
 * {@code vault_omen.projectile_distance} blocks) that stops on the first block or
 * living entity it meets and detonates into a <em>forbidden circle</em>: a trial
 * spawner detection burst, a rising triangle of
 * {@code vault_omen.land_triangle_size} blocks, raid-horn sounds and a ring drawn
 * every 4 ticks for {@code vault_omen.duration}s, shrinking over its last 1.5s.
 * Every untrusted player inside {@code vault_omen.tether_radius} blocks is
 * tethered - leaving the circle pulls them back at 0.4 blocks/tick - and wind
 * charges cannot be used or launched from inside it.</p>
 *
 * <p><b>Ominous Conjuring</b> (Shift+F, {@code ominous_conjuring.cooldown}s):
 * reads how many untrusted players stand within
 * {@code ominous_conjuring.spell_radius} blocks to pick a spell level (1: nobody,
 * 2: 2-4, 3: 5+), shows a green {@code Ominous Conjuring | Lv.N} boss bar for
 * {@code ominous_conjuring.buff_duration}s, and rings the caster with an expanding
 * soul-fire circle. While conjuring, every arm swing (at most one per 200ms) fires
 * a soul-fire bolt ({@code ominous_projectile.speed}, {@code .distance}) that
 * explodes for {@code ominous_projectile.damage} and applies the level's effect to
 * a player it hits directly: Lv.1 Speed for the caster, Lv.2 a 5s shield cooldown
 * for the victim, Lv.3 a launch of the caster towards the victim.</p>
 */
public final class OmenWeapon implements WeaponBehavior {

	static final String KEY_VAULT = "omen_vault";
	static final String KEY_CONJURING = "omen_conjuring";

	/** Bukkit {@code Color.fromRGB(120, 200, 255)} used by every FLASH burst. */
	private static final int FLASH_BLUE = 0x78C8FF;

	private static final String COPPER_FLAME = "COPPER_FIRE_FLAME";
	private static final String TRIAL_DETECTION = "TRIAL_SPAWNER_DETECTION";
	private static final String TRIAL_DETECTION_OMINOUS = "TRIAL_SPAWNER_DETECTION_OMINOUS";
	private static final String SOUL_FLAME = "SOUL_FIRE_FLAME";

	private static final long SHRINK_WINDOW_MILLIS = 1500L;
	private static final long SHOT_INTERVAL_MILLIS = 200L;
	private static final int TRIANGLE_TICKS = 30;
	private static final int TRIANGLE_POINTS = 28;
	private static final int SHIELD_COOLDOWN_TICKS = 100;
	private static final int CONJURING_RING_STEPS = 41;
	private static final double CIRCLE_STEP_RADIANS = 0.08726646259971647D;
	private static final double TETHER_PULL = 0.4D;
	private static final double PROJECTILE_HIT_BOX = 1.0D;

	private final AltarSMPMod mod;

	/** Bukkit's {@code Map<UUID, a> circles}. */
	private final Map<UUID, ForbiddenCircle> circles = new HashMap<>();
	/** Bukkit's {@code Map<UUID, UUID> tethered} (player -> circle). */
	private final Map<UUID, UUID> tethered = new HashMap<>();
	/** Bukkit's {@code Map<UUID, Long>} conjuring expiry. */
	private final Map<UUID, Long> conjuringUntil = new HashMap<>();
	/** Bukkit's {@code Map<UUID, Integer>} spell level per caster. */
	private final Map<UUID, Integer> spellLevels = new HashMap<>();
	/** Bukkit's {@code Map<UUID, BossBar>} conjuring bars. */
	private final Map<UUID, ServerBossEvent> conjuringBars = new HashMap<>();
	/** Bukkit's {@code Map<UUID, Long>} 200ms shot throttle. */
	private final Map<UUID, Long> lastShot = new HashMap<>();

	public OmenWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "omen";
	}

	@Override
	public int season() {
		return 2;
	}

	@Override
	public String displayName() {
		return "Omen";
	}

	@Override
	public List<String> configFields() {
		return List.of("Vault Omen Cooldown/Duration/Tether Radius/Land Triangle Size",
				"Vault Omen Projectile Speed/Distance",
				"Ominous Conjuring Cooldown/Buff Duration/Spell Radius",
				"Ominous Conjuring Lv1 Speed Duration/Amplifier",
				"Ominous Projectile Speed/Distance/Damage");
	}

	// ------------------------------------------------------------- activation

	@Override
	public void onPrimary(AbilityContext ctx) {
		if (!holding(ctx)) {
			return;
		}
		tryVaultOmen(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		if (!holding(ctx)) {
			return;
		}
		tryOminousConjuring(ctx);
	}

	private boolean holding(AbilityContext ctx) {
		return Identity.is(ctx.weapon(), id()) || Identity.is(ctx.player().getMainHandItem(), id());
	}

	// ------------------------------------------------------------ vault omen

	/** {@code OmenAbilities#tryVaultOmen}. */
	private void tryVaultOmen(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int cooldown = ctx.cfg("abilities.omen.vault_omen.cooldown", 35);
		if (!ctx.gate(KEY_VAULT, "Vault Omen", cooldown)) {
			return;
		}
		CooldownBars.show(player, KEY_VAULT, "Vault Omen", BossEvent.BossBarColor.WHITE, cooldown);

		int durationSeconds = ctx.cfg("abilities.omen.vault_omen.duration", 7);
		int tetherRadius = ctx.cfg("abilities.omen.vault_omen.tether_radius", 3);
		int triangleSize = ctx.cfg("abilities.omen.vault_omen.land_triangle_size", 3);
		double step = ctx.cfgd("abilities.omen.vault_omen.projectile_speed", 18.0D) / 20.0D;
		double maxDistance = ctx.cfgd("abilities.omen.vault_omen.projectile_distance", 30.0D);

		ServerLevel level = ctx.level();
		player.swing(InteractionHand.MAIN_HAND);
		Fx.sound(level, player.position(), "ENTITY_DRAGON_FIREBALL_EXPLODE", 1.4F, 0.8F);
		Fx.sound(level, player.position(), "ITEM_TRIDENT_THUNDER", 1.0F, 0.7F);

		Vec3 direction = player.getViewVector(1.0F);
		Vec3 origin = player.getEyePosition().add(direction.scale(1.5D));
		UUID caster = player.getUUID();
		final double[] travelled = {0.0D};
		final int[] ticks = {0};
		final boolean[] done = {false};
		this.mod.scheduler().timer(() -> {
			if (done[0]) {
				return;
			}
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(caster);
			if (owner == null) {
				done[0] = true;
				return;
			}
			if (travelled[0] >= maxDistance) {
				done[0] = true;
				triggerVault(level, owner, origin.add(direction.scale(travelled[0])),
						durationSeconds, tetherRadius, triangleSize);
				return;
			}
			Vec3 at = origin.add(direction.scale(travelled[0]));
			Fx.simple(level, COPPER_FLAME, at, 6, 0.15D, 0.15D, 0.15D, 0.01D);
			Fx.simple(level, COPPER_FLAME, at, 3, 0.03D, 0.03D, 0.03D, 0.0D);
			if (!passable(level, at)) {
				done[0] = true;
				triggerVault(level, owner, at, durationSeconds, tetherRadius, triangleSize);
				return;
			}
			if (ticks[0] >= 2) {
				for (Entity entity : level.getEntities(null, box(at, PROJECTILE_HIT_BOX))) {
					if (entity instanceof LivingEntity living && living != owner) {
						done[0] = true;
						triggerVault(level, owner, at, durationSeconds, tetherRadius, triangleSize);
						return;
					}
				}
			}
			travelled[0] += step;
			ticks[0]++;
		}, 0L, 1L).cancelAfter((long) Math.ceil(maxDistance / step) + 10L);
	}

	/** {@code OmenAbilities#triggerVault} - the forbidden circle itself. */
	private void triggerVault(ServerLevel level, ServerPlayer caster, Vec3 at,
			int durationSeconds, int tetherRadius, int triangleSize) {
		UUID circleId = UUID.randomUUID();
		ForbiddenCircle circle = new ForbiddenCircle(level, at,
				System.currentTimeMillis() + durationSeconds * 1000L, tetherRadius, caster.getUUID());
		this.circles.put(circleId, circle);

		Fx.simple(level, COPPER_FLAME, at, 60, 1.5D, 0.6D, 1.5D, 0.05D);
		Fx.simple(level, TRIAL_DETECTION, at, 80, 1.5D, 0.5D, 1.5D, 0.0D);
		Fx.sound(level, at, "BLOCK_TRIAL_SPAWNER_DETECT_PLAYER", 1.5F, 1.0F);
		Fx.sound(level, at, "BLOCK_VAULT_INSERT_ITEM", 1.2F, 0.9F);
		Fx.sound(level, at, "BLOCK_VAULT_CLOSE_SHUTTER", 1.4F, 0.8F);
		Fx.sound(level, at, "EVENT_RAID_HORN", 1.0F, 1.0F);
		for (int index = 0; index < 14; index++) {
			Vec3 flash = at.add((level.random.nextDouble() - 0.5D) * 3.0D, level.random.nextDouble() * 3.0D,
					(level.random.nextDouble() - 0.5D) * 3.0D);
			Fx.colored(level, "FLASH", flash, FLASH_BLUE, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		risingTriangle(level, at, triangleSize);

		// Every 4 ticks: draw the circle (or the shrinking ring in the last 1.5s)
		// and tether everybody untrusted standing inside it.
		this.mod.scheduler().timer(() -> {
			if (!this.circles.containsKey(circleId)) {
				return;
			}
			long remaining = circle.expiresAtMillis() - System.currentTimeMillis();
			if (remaining <= SHRINK_WINDOW_MILLIS) {
				double factor = remaining / (double) SHRINK_WINDOW_MILLIS;
				if (factor <= 0.0D) {
					removeCircle(circleId);
				} else {
					drawShrinkingRing(level, circle, factor);
				}
				return;
			}
			drawForbiddenCircle(level, circle);
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(circle.owner());
			for (ServerPlayer other : level.players()) {
				if (other.getUUID().equals(circle.owner()) || !allowed(owner, other)) {
					continue;
				}
				if (other.position().distanceToSqr(circle.pos()) <= (double) tetherRadius * tetherRadius) {
					this.tethered.put(other.getUUID(), circleId);
				}
			}
		}, 0L, 4L).cancelAfter(durationSeconds * 20L + 60L);

		// Bukkit enforced the tether from PlayerMoveEvent; a per-tick check is the
		// equivalent on Fabric and cannot be dodged by standing still.
		this.mod.scheduler().timer(() -> {
			if (!this.circles.containsKey(circleId)) {
				return;
			}
			double radiusSquared = (double) tetherRadius * tetherRadius;
			for (Iterator<Map.Entry<UUID, UUID>> it = this.tethered.entrySet().iterator(); it.hasNext();) {
				Map.Entry<UUID, UUID> entry = it.next();
				if (!entry.getValue().equals(circleId)) {
					continue;
				}
				ServerPlayer victim = level.getServer().getPlayerList().getPlayer(entry.getKey());
				if (victim == null) {
					it.remove();
					continue;
				}
				if (victim.position().distanceToSqr(circle.pos()) > radiusSquared) {
					Vec3 pull = circle.pos().subtract(victim.position()).normalize().scale(TETHER_PULL);
					Motion.setVelocity(victim, pull);
				}
			}
		}, 0L, 1L).cancelAfter(durationSeconds * 20L + 60L);
	}

	private void removeCircle(UUID circleId) {
		this.circles.remove(circleId);
		this.tethered.values().removeIf(circleId::equals);
	}

	/** {@code OmenAbilities#drawForbiddenCircle}. */
	private void drawForbiddenCircle(ServerLevel level, ForbiddenCircle circle) {
		Vec3 center = circle.pos();
		double radius = circle.tetherRadius();
		for (double angle = 0.0D; angle < Math.PI * 2.0D; angle += CIRCLE_STEP_RADIANS) {
			Vec3 at = center.add(Math.cos(angle) * radius, 0.1D, Math.sin(angle) * radius);
			Fx.simple(level, TRIAL_DETECTION, at, 3, 0.05D, 0.05D, 0.05D, 0.0D);
		}
		for (double height = 0.0D; height <= 3.0D; height += 0.25D) {
			Fx.simple(level, COPPER_FLAME, center.add(0.0D, height, 0.0D), 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	/** {@code OmenAbilities#drawShrinkingRing}. */
	private void drawShrinkingRing(ServerLevel level, ForbiddenCircle circle, double factor) {
		Vec3 center = circle.pos();
		double radius = circle.tetherRadius() * factor;
		for (double angle = 0.0D; angle < Math.PI * 2.0D; angle += CIRCLE_STEP_RADIANS) {
			Vec3 at = center.add(Math.cos(angle) * radius, 0.1D, Math.sin(angle) * radius);
			Fx.simple(level, COPPER_FLAME, at, 2, 0.05D, 0.05D, 0.05D, 0.0D);
		}
		for (double height = 0.0D; height <= 3.0D * factor; height += 0.25D) {
			Fx.simple(level, COPPER_FLAME, center.add(0.0D, height, 0.0D), 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	/** {@code OmenAbilities#spawnRisingTriangle}. */
	private void risingTriangle(ServerLevel level, Vec3 at, int size) {
		Vec3[] corners = {
				at.add(0.0D, 0.1D, size),
				at.add(-size * 0.866D, 0.1D, -size * 0.5D),
				at.add(size * 0.866D, 0.1D, -size * 0.5D),
		};
		final int[] elapsed = {0};
		this.mod.scheduler().timer(() -> {
			if (elapsed[0] >= TRIANGLE_TICKS) {
				return;
			}
			for (int corner = 0; corner < 3; corner++) {
				Vec3 from = corners[corner];
				Vec3 to = corners[(corner + 1) % 3];
				Vec3 delta = to.subtract(from);
				for (int point = 0; point <= TRIANGLE_POINTS; point++) {
					Vec3 on = from.add(delta.scale(point / (double) TRIANGLE_POINTS));
					Fx.simple(level, COPPER_FLAME, on, 1, 0.0D, 0.0D, 0.0D, 0.0D);
				}
			}
			elapsed[0] += 2;
		}, 0L, 2L).cancelAfter(TRIANGLE_TICKS + 4L);
	}

	// ------------------------------------------------------- ominous conjuring

	/** {@code OmenAbilities#tryOminousConjuring}. */
	private void tryOminousConjuring(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		UUID uuid = player.getUUID();
		int cooldown = ctx.cfg("abilities.omen.ominous_conjuring.cooldown", 50);
		int buffSeconds = ctx.cfg("abilities.omen.ominous_conjuring.buff_duration", 10);
		if (!ctx.gate(KEY_CONJURING, "Ominous Conjuring", cooldown)) {
			return;
		}
		CooldownBars.show(player, KEY_CONJURING, "Ominous Conjuring", BossEvent.BossBarColor.WHITE, cooldown);

		int spellLevel = computeSpellLevel(ctx);
		this.spellLevels.put(uuid, spellLevel);
		long buffMillis = buffSeconds * 1000L;
		long until = System.currentTimeMillis() + buffMillis;
		this.conjuringUntil.put(uuid, until);

		ServerBossEvent previous = this.conjuringBars.remove(uuid);
		if (previous != null) {
			previous.removeAllPlayers();
		}
		ServerBossEvent bar = Messaging.bossBar("<green>Ominous Conjuring <dark_gray>| <aqua>Lv." + spellLevel,
				BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
		bar.addPlayer(player);
		bar.setProgress(1.0F);
		this.conjuringBars.put(uuid, bar);

		ServerLevel level = ctx.level();
		Vec3 at = player.position();
		Fx.sound(level, at, "EVENT_RAID_HORN", 1.0F, 1.0F);
		Fx.sound(level, at, "BLOCK_VAULT_ACTIVATE", 1.0F, 1.4F);
		Fx.sound(level, at, "BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE", 1.5F, 1.0F);

		// Boss bar countdown, exactly like the plugin's 2-tick progress task.
		this.mod.scheduler().timer(() -> {
			ServerBossEvent current = this.conjuringBars.get(uuid);
			if (current != bar) {
				return;
			}
			long remaining = until - System.currentTimeMillis();
			if (remaining > 0L && level.getServer().getPlayerList().getPlayer(uuid) != null) {
				bar.setProgress((float) Math.max(0.0D, Math.min(1.0D, remaining / (double) buffMillis)));
			} else {
				endConjuring(uuid);
			}
		}, 0L, 2L).cancelAfter(buffSeconds * 20L + 20L);

		// Expanding soul-fire ring: radius 0.5 -> 20 in 0.5 steps.
		final double[] radius = {0.5D};
		final int[] steps = {0};
		this.mod.scheduler().timer(() -> {
			if (steps[0]++ >= CONJURING_RING_STEPS || radius[0] > 20.0D) {
				return;
			}
			ServerPlayer caster = level.getServer().getPlayerList().getPlayer(uuid);
			if (caster == null) {
				return;
			}
			Vec3 center = caster.position();
			int points = 24 + (int) (radius[0] * 2.0D);
			for (int point = 0; point < points; point++) {
				double angle = Math.PI * 2.0D * point / points;
				Vec3 ring = center.add(Math.cos(angle) * radius[0], 0.4D, Math.sin(angle) * radius[0]);
				Fx.simple(level, SOUL_FLAME, ring, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
			radius[0] += 0.5D;
		}, 0L, 1L).cancelAfter(CONJURING_RING_STEPS + 5L);
	}

	/** {@code OmenAbilities#computeSpellLevel}. */
	private int computeSpellLevel(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		int radius = ctx.cfg("abilities.omen.ominous_conjuring.spell_radius", 20);
		double radiusSquared = (double) radius * radius;
		Vec3 at = player.position();
		int nearby = 0;
		for (ServerPlayer other : ctx.level().players()) {
			if (other == player || !ctx.abilityAllowedOn(other)) {
				continue;
			}
			if (other.position().distanceToSqr(at) <= radiusSquared) {
				nearby++;
			}
		}
		if (nearby <= 1) {
			return 1;
		}
		return nearby <= 4 ? 2 : 3;
	}

	/** {@code OmenAbilities#onArmSwing} while conjuring. */
	@Override
	public void onArmSwing(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		UUID uuid = player.getUUID();
		if (!Identity.is(player.getMainHandItem(), id()) || !isConjuring(uuid)) {
			return;
		}
		long now = System.currentTimeMillis();
		Long last = this.lastShot.get(uuid);
		if (last != null && now - last < SHOT_INTERVAL_MILLIS) {
			return;
		}
		this.lastShot.put(uuid, now);
		fireConjuringProjectile(ctx, this.spellLevels.getOrDefault(uuid, computeSpellLevel(ctx)));
	}

	/** {@code OmenAbilities#fireConjuringProjectile}. */
	private void fireConjuringProjectile(AbilityContext ctx, int spellLevel) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		double step = ctx.cfgd("abilities.omen.ominous_projectile.speed", 30.0D) / 20.0D;
		double maxDistance = ctx.cfgd("abilities.omen.ominous_projectile.distance", 25.0D);
		double damage = ctx.cfgd("abilities.omen.ominous_projectile.damage", 4.0D);
		int speedDuration = ctx.cfg("abilities.omen.ominous_conjuring.lvl1_speed_duration", 20);
		int speedAmplifier = ctx.cfg("abilities.omen.ominous_conjuring.lvl1_speed_amplifier", 1);

		player.swing(InteractionHand.MAIN_HAND);
		Fx.sound(level, player.position(), "ITEM_TRIDENT_THROW", 0.7F, 1.4F);
		Fx.sound(level, player.position(), "BLOCK_RESPAWN_ANCHOR_CHARGE", 0.4F, 1.6F);

		Vec3 direction = player.getViewVector(1.0F);
		Vec3 origin = player.getEyePosition().add(direction.scale(1.5D));
		UUID caster = player.getUUID();
		final double[] travelled = {0.0D};
		final int[] ticks = {0};
		final boolean[] done = {false};
		this.mod.scheduler().timer(() -> {
			if (done[0]) {
				return;
			}
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(caster);
			if (owner == null) {
				done[0] = true;
				return;
			}
			if (travelled[0] >= maxDistance) {
				done[0] = true;
				detonate(level, owner, origin.add(direction.scale(travelled[0])), null,
						damage, spellLevel, speedDuration, speedAmplifier);
				return;
			}
			Vec3 at = origin.add(direction.scale(travelled[0]));
			Fx.simple(level, SOUL_FLAME, at, 12, 0.25D, 0.25D, 0.25D, 0.02D);
			Fx.simple(level, SOUL_FLAME, at, 6, 0.05D, 0.05D, 0.05D, 0.0D);
			if (!passable(level, at)) {
				done[0] = true;
				detonate(level, owner, at, null, damage, spellLevel, speedDuration, speedAmplifier);
				return;
			}
			if (ticks[0] >= 2) {
				for (Entity entity : level.getEntities(null, box(at, PROJECTILE_HIT_BOX))) {
					if (!(entity instanceof LivingEntity living) || living == owner) {
						continue;
					}
					if (living instanceof ServerPlayer other && !allowed(owner, other)) {
						continue;
					}
					done[0] = true;
					detonate(level, owner, at, living, damage, spellLevel, speedDuration, speedAmplifier);
					return;
				}
			}
			travelled[0] += step;
			ticks[0]++;
		}, 0L, 1L).cancelAfter((long) Math.ceil(maxDistance / step) + 10L);
	}

	/** The conjuring projectile's impact (the plugin's inner {@code a(Location, LivingEntity)}). */
	private void detonate(ServerLevel level, ServerPlayer caster, Vec3 at, @javax.annotation.Nullable LivingEntity directHit,
			double damage, int spellLevel, int speedDuration, int speedAmplifier) {
		Fx.simple(level, SOUL_FLAME, at, 50, PROJECTILE_HIT_BOX, 0.5D, PROJECTILE_HIT_BOX, 0.06D);
		Fx.simple(level, TRIAL_DETECTION_OMINOUS, at, 60, PROJECTILE_HIT_BOX, 0.4D, PROJECTILE_HIT_BOX, 0.0D);
		Fx.sound(level, at, "BLOCK_VAULT_ACTIVATE", 1.2F, 1.4F);
		Fx.sound(level, at, "ENTITY_GENERIC_EXPLODE", 0.7F, 1.3F);
		for (int index = 0; index < 10; index++) {
			Vec3 flash = at.add((level.random.nextDouble() - 0.5D) * 2.0D, level.random.nextDouble() * 2.0D,
					(level.random.nextDouble() - 0.5D) * 2.0D);
			Fx.colored(level, "FLASH", flash, FLASH_BLUE, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		for (Entity entity : level.getEntities(null, box(at, PROJECTILE_HIT_BOX))) {
			if (!(entity instanceof LivingEntity living) || living == caster) {
				continue;
			}
			if (living instanceof ServerPlayer other && !allowed(caster, other)) {
				continue;
			}
			TrueDamage.apply(living, damage, caster, false);
		}
		if (directHit instanceof ServerPlayer victim) {
			applySpellLevelEffects(level, caster, victim, spellLevel, speedDuration, speedAmplifier);
		}
	}

	/** {@code OmenAbilities#applySpellLevelEffects}. */
	private void applySpellLevelEffects(ServerLevel level, ServerPlayer caster, ServerPlayer victim,
			int spellLevel, int speedDuration, int speedAmplifier) {
		switch (spellLevel) {
			case 1 -> Effects.apply(caster, "SPEED", speedDuration, speedAmplifier, true, false, false);
			case 2 -> {
				victim.getCooldowns().addCooldown(new ItemStack(Items.SHIELD), SHIELD_COOLDOWN_TICKS);
				Fx.sound(level, victim.position(), "ITEM_SHIELD_BREAK", 0.6F, 1.0F);
			}
			case 3 -> {
				if (!caster.isShiftKeyDown()) {
					Vec3 launch = victim.position().subtract(caster.position()).normalize();
					Motion.setVelocity(caster, new Vec3(launch.x, Math.max(0.2D, launch.y), launch.z));
				}
			}
			default -> {
			}
		}
	}

	/** {@code OmenAbilities#isConjuring}. */
	private boolean isConjuring(UUID uuid) {
		Long until = this.conjuringUntil.get(uuid);
		if (until == null) {
			return false;
		}
		if (System.currentTimeMillis() > until) {
			endConjuring(uuid);
			return false;
		}
		return true;
	}

	/** {@code OmenAbilities#endConjuring}. */
	private void endConjuring(UUID uuid) {
		this.conjuringUntil.remove(uuid);
		this.spellLevels.remove(uuid);
		this.lastShot.remove(uuid);
		ServerBossEvent bar = this.conjuringBars.remove(uuid);
		if (bar != null) {
			bar.removeAllPlayers();
		}
	}

	// --------------------------------------------------- forbidden circle rules

	/**
	 * {@code OmenAbilities#onRightClick} - a wind charge cannot be thrown from
	 * inside a forbidden circle. Offered globally because the player is holding a
	 * wind charge, not an Omen.
	 */
	@Override
	public boolean interceptItemUse(ServerLevel level, ServerPlayer player, InteractionHand hand, ItemStack used) {
		if (!used.is(Items.WIND_CHARGE) || !isInsideAnyForbiddenCircle(level, player.position())) {
			return false;
		}
		Messaging.actionBar(player, "<red>Vault Omen suppresses your wind charge");
		return true;
	}

	/** {@code OmenAbilities#onProjectileLaunch} - the same rule for the projectile. */
	@Override
	public boolean interceptProjectileLaunch(ServerLevel level, ServerPlayer shooter, Entity projectile) {
		if (!(projectile instanceof WindCharge) || !isInsideAnyForbiddenCircle(level, shooter.position())) {
			return false;
		}
		Messaging.actionBar(shooter, "<red>Vault Omen suppresses your wind charge");
		return true;
	}

	/** {@code OmenAbilities#isInsideAnyForbiddenCircle}. */
	public boolean isInsideAnyForbiddenCircle(ServerLevel level, Vec3 pos) {
		for (ForbiddenCircle circle : this.circles.values()) {
			if (circle.level() == level
					&& pos.distanceToSqr(circle.pos()) <= (double) circle.tetherRadius() * circle.tetherRadius()) {
				return true;
			}
		}
		return false;
	}

	/** How many forbidden circles are currently on the ground (admin diagnostics). */
	public int activeCircles() {
		return this.circles.size();
	}

	// ------------------------------------------------------------- housekeeping

	@Override
	public void onPlayerQuit(ServerPlayer player) {
		UUID uuid = player.getUUID();
		endConjuring(uuid);
		this.tethered.remove(uuid);
	}

	// ------------------------------------------------------------------ helpers

	/** Bukkit's {@code block.isPassable()} - no collision shape at all. */
	private static boolean passable(ServerLevel level, Vec3 at) {
		BlockPos pos = BlockPos.containing(at);
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static AABB box(Vec3 center, double radius) {
		return new AABB(center.x - radius, center.y - radius, center.z - radius,
				center.x + radius, center.y + radius, center.z + radius);
	}

	/**
	 * Bukkit's {@code factions.isTrusted(a, b)} plus the ability-immunity rule; the
	 * port keeps both behind {@code AbilityImmunity} so a friendly target is never
	 * hit and a recently-ability-hit player is never chain-hit.
	 */
	private boolean allowed(@javax.annotation.Nullable ServerPlayer attacker, ServerPlayer victim) {
		return this.mod.immunity().allowAbilityHit(attacker, victim);
	}

	/** Bukkit's {@code record a(UUID id, Location pos, long expiresAt, int radius)}. */
	private record ForbiddenCircle(ServerLevel level, Vec3 pos, long expiresAtMillis, int tetherRadius, UUID owner) {
	}
}
