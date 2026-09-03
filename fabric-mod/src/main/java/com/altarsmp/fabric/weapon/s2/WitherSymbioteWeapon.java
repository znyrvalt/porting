package com.altarsmp.fabric.weapon.s2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.AbilityContext;
import com.altarsmp.fabric.ability.CooldownBars;
import com.altarsmp.fabric.ability.Effects;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TickScheduler;
import com.altarsmp.fabric.weapon.WeaponBehavior;

/**
 * Wither Symbiote (Season 2) - port of {@code com.altarsmps2.weapons.WitherSymbioteWeapon}
 * and {@code com.altarsmps2.abilities.WitherSymbioteAbilities}.
 *
 * <p><b>Symbiote Infection</b> (F, {@code infection.cooldown}s): the first cast
 * <em>primes</em> the caster - 30 large-smoke puffs over the head, a low wither
 * ambient, {@code infection.prime_glow_amplifier} Glowing for one second and an
 * eight second window in which the next melee hit infects. Every melee hit inside
 * that window consumes the prime, plays a wither shoot at the victim and applies
 * the infection to it <em>plus</em> every untrusted living entity within
 * {@code infection.initial_spread_radius} blocks. A second cast while infections
 * you own are alive remotely <em>detonates</em> them: the first target runs a
 * 30-tick "Wither Detonation" boss bar and explodes into a squid-ink cloud for
 * {@code infection.detonate_damage}, and every other target you infected is hit
 * for the same amount immediately. A 1.5 second lockout follows so the two cannot
 * be chained in one click. An infection that simply runs out
 * ({@code infection.duration}s, re-checked every 5 ticks while it puffs squid ink
 * and spreads to neighbours within {@code infection.spread_radius}) instead gives
 * {@code infection.weakness_duration}s of Weakness and half the detonation damage.
 *
 * <p><b>Symbiote Rampage</b> (Shift+F, {@code rampage.cooldown}s): +{@code rampage.bonus_health}
 * max health (as a transient attribute modifier), an instant
 * {@code rampage.heal_amount} heal, +{@code rampage.speed_bonus} multiplicative
 * movement speed, every cobweb in a 7x7x7 box around the caster cleared, and a
 * world-border override sent to that one client so the screen edges darken for the
 * whole ability. Rampage is <em>sustained</em>: it only lasts
 * {@code rampage.initial_duration}s unless melee hits keep pushing the buffer
 * forward by {@code rampage.sustain_per_hit}s (never past
 * {@code rampage.max_buffer_duration}s from now), and each hit during it steals
 * {@code rampage.lifesteal} health. Every 10 ticks the buffer is drawn as a
 * 50-character action-bar meter (green above 66%, yellow above 33%, red below) and
 * 0.5 saturation is burned; every 4 ticks squid ink and the occasional white smoke
 * trail the caster. When the buffer empties - or the player leaves - the modifiers
 * come off and, for a player who is still connected, the exit costs
 * {@code rampage.exit_health_loss} health plus Weakness and Wither for
 * {@code rampage.exit_weakness_duration}/{@code exit_wither_duration} seconds.
 *
 * <p>Every melee hit also draws the symbiote slash arc: a 17-point WAX_OFF + SMOKE
 * curve of radius 1.4 around the victim, rotated by a random angle in the plane
 * spanned by the attacker's look direction.
 *
 * <p>Three config keys the shipped {@code s2.yml} declares are never read by the
 * original class and are deliberately not read here either, so behaviour matches
 * the plugin instead of the comments: {@code infection.detonate_window} (the class
 * reads {@code infection.duration}, which the YAML does not define, and therefore
 * always used the built-in default of 3), {@code infection.tick_particle_interval}
 * (the spread task is hard-coded to every 5 ticks) and {@code rampage.duration}
 * (the cooldown bar is sized by {@code rampage.cooldown}). The unused
 * {@code matchParticle(String, Particle)} helper in the decompiled class is dead
 * code and has no counterpart here.
 */
public final class WitherSymbioteWeapon implements WeaponBehavior {

	/** Bukkit's {@code WitherSymbioteAbilities#a} - the infection cooldown key. */
	private static final String KEY_INFECT = "ws_infect";
	/** Bukkit's {@code WitherSymbioteAbilities#b} - the rampage cooldown key. */
	private static final String KEY_RAMPAGE = "ws_rampage";

	/** Bukkit's {@code new NamespacedKey("altarsmps2", "ws_rampage_hp")}. */
	private static final Identifier MODIFIER_HEALTH = Identifier.fromNamespaceAndPath("altarsmps2", "ws_rampage_hp");
	/** Bukkit's {@code new NamespacedKey("altarsmps2", "ws_rampage_speed")}. */
	private static final Identifier MODIFIER_SPEED = Identifier.fromNamespaceAndPath("altarsmps2", "ws_rampage_speed");

	/** How long a primed infection waits for a melee hit (Bukkit: {@code now + 8000L}). */
	private static final long PRIME_WINDOW_MILLIS = 8000L;
	/** Lockout after a remote detonation (Bukkit: {@code now + 1500L}). */
	private static final long DETONATE_LOCKOUT_MILLIS = 1500L;
	/** Ticks the "Wither Detonation" boss bar counts down. */
	private static final int DETONATION_TICKS = 30;
	/** Characters in the rampage sustain meter. */
	private static final int METER_WIDTH = 50;
	/** Bukkit's {@code setSize(5.999997E7F)} rampage border. */
	private static final double RAMPAGE_BORDER_SIZE = 5.999997E7D;
	/** Bukkit's {@code setWarningDistance(60000000)}. */
	private static final int RAMPAGE_BORDER_WARNING = 60000000;
	/** Arc sweep of the slash visual: {@code 1.7278759594743864} radians (~99 degrees). */
	private static final double SLASH_SWEEP = 1.7278759594743864D;
	/** Radius of the slash arc. */
	private static final double SLASH_RADIUS = 1.4D;
	/** Points drawn along the slash arc (Bukkit: {@code byte var9 = 16}, inclusive). */
	private static final int SLASH_SEGMENTS = 16;

	private final AltarSMPMod mod;

	/** Bukkit's {@code Map<UUID, Long> d} - prime window per caster. */
	private final Map<UUID, Long> primed = new HashMap<>();
	/** Bukkit's {@code Map<UUID, a> e} - live infections, keyed by the infected entity. */
	private final Map<UUID, Infection> infections = new HashMap<>();
	/** Bukkit's {@code Map<UUID, b> f} - live rampages, keyed by the caster. */
	private final Map<UUID, Rampage> rampages = new HashMap<>();
	/** Bukkit's {@code Map<UUID, Long> g} - post-detonation lockout per caster. */
	private final Map<UUID, Long> detonateLockout = new HashMap<>();
	/** Bukkit's {@code Set<UUID> h} - targets whose detonation is already running. */
	private final Set<UUID> detonating = new HashSet<>();

	/** Infection spread tasks, kept so a finished infection can cancel its own timer. */
	private final Map<UUID, TickScheduler.Task> infectionTasks = new HashMap<>();
	/** Detonation countdown tasks, keyed by the detonating target. */
	private final Map<UUID, TickScheduler.Task> detonationTasks = new HashMap<>();
	/** Rampage sustain-meter tasks, keyed by the caster. */
	private final Map<UUID, TickScheduler.Task> meterTasks = new HashMap<>();
	/** Rampage particle tasks, keyed by the caster. */
	private final Map<UUID, TickScheduler.Task> trailTasks = new HashMap<>();
	/** Detonation boss bars, keyed by the detonating target. */
	private final Map<UUID, ServerBossEvent> detonationBars = new HashMap<>();

	public WitherSymbioteWeapon(AltarSMPMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "wither_symbiote";
	}

	@Override
	public int season() {
		return 2;
	}

	@Override
	public String displayName() {
		return "Wither Symbiote";
	}

	@Override
	public List<String> configFields() {
		return List.of("Infection Cooldown/Duration/Spread Radius/Detonate Damage",
				"Infection Weakness Duration/Amplifier", "Infection Prime Glow Amplifier",
				"Rampage Cooldown/Bonus Health/Heal Amount/Lifesteal/Speed Bonus",
				"Rampage Initial Duration/Max Buffer Duration/Bar Full Seconds/Sustain Per Hit",
				"Rampage Exit Health Loss/Weakness Duration/Weakness Amplifier",
				"Rampage Exit Wither Duration/Amplifier");
	}

	// ------------------------------------------------------------- activation

	@Override
	public void onPrimary(AbilityContext ctx) {
		if (!holding(ctx)) {
			return;
		}
		tryInfection(ctx);
	}

	@Override
	public void onSecondary(AbilityContext ctx) {
		if (!holding(ctx)) {
			return;
		}
		tryRampage(ctx);
	}

	/**
	 * {@code WitherSymbioteAbilities#onMelee} (HIGH) plus {@code #onRampageHit}
	 * (MONITOR) - both fired by the same Bukkit event, so both run from the one
	 * attack hook here.
	 */
	@Override
	public void onAttack(AbilityContext ctx, LivingEntity target, float damage, boolean critical) {
		if (!holding(ctx) || target == null) {
			return;
		}
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		UUID uuid = player.getUUID();

		spawnSlashArc(level, player, target);

		Rampage rampage = this.rampages.get(uuid);
		if (rampage != null) {
			// #onMelee: a hit slides the sustain buffer forward, capped at
			// max_buffer_duration measured from *now*.
			double sustainPerHit = ctx.cfgd("abilities.withersymbiote.rampage.sustain_per_hit", 4.0D);
			double maxBufferSeconds = ctx.cfgd("abilities.withersymbiote.rampage.max_buffer_duration", 12.0D);
			long now = System.currentTimeMillis();
			long cap = now + (long) (maxBufferSeconds * 1000.0D);
			rampage.bufferUntilMillis = Math.min(cap, rampage.bufferUntilMillis + (long) (sustainPerHit * 1000.0D));

			// #onRampageHit: lifesteal, clamped to the (buffed) maximum health.
			double lifesteal = ctx.cfgd("abilities.withersymbiote.rampage.lifesteal", 1.0D);
			AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
			if (maxHealth != null) {
				player.setHealth((float) Math.min(player.getHealth() + lifesteal, maxHealth.getValue()));
			}
		}

		Long primeEnd = this.primed.get(uuid);
		if (primeEnd == null || System.currentTimeMillis() > primeEnd) {
			return;
		}
		this.primed.remove(uuid);
		Fx.sound(level, target.position(), "ENTITY_WITHER_SHOOT", 0.7F, 0.6F);

		int durationSeconds = ctx.cfg("abilities.withersymbiote.infection.duration", 3);
		applyInfection(level, player, target, durationSeconds);

		// The first hit also seeds the infection into everything untrusted nearby.
		int initialRadius = ctx.cfg("abilities.withersymbiote.infection.initial_spread_radius", 3);
		for (LivingEntity neighbour : nearbyLiving(level, target.position(), initialRadius)) {
			if (neighbour.equals(target) || neighbour.equals(player)) {
				continue;
			}
			if (this.infections.containsKey(neighbour.getUUID())) {
				continue;
			}
			if (neighbour instanceof ServerPlayer victim && !trusted(player, victim)) {
				continue;
			}
			applyInfection(level, player, neighbour, durationSeconds);
		}
	}

	/**
	 * Bukkit's rampage task called {@code endRampage} once it saw the player was no
	 * longer online; the detached player meant the exit effects, the health loss and
	 * the border reset were all dropped. The port does the same bookkeeping at
	 * disconnect - state, tasks and the transient attribute modifiers - and skips
	 * the penalties that could never have landed anyway.
	 */
	@Override
	public void onPlayerQuit(ServerPlayer player) {
		UUID uuid = player.getUUID();
		if (this.rampages.remove(uuid) != null) {
			cancel(this.meterTasks, uuid);
			cancel(this.trailTasks, uuid);
			removeRampageModifiers(player);
		}
	}

	private boolean holding(AbilityContext ctx) {
		return Identity.is(ctx.weapon(), id()) || Identity.is(ctx.player().getMainHandItem(), id());
	}

	// ------------------------------------------------------- symbiote infection

	/** {@code WitherSymbioteAbilities#tryInfection}. */
	private void tryInfection(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		UUID uuid = player.getUUID();
		long now = System.currentTimeMillis();

		Long lockout = this.detonateLockout.get(uuid);
		if (lockout != null && now < lockout) {
			return;
		}

		List<Infection> mine = new ArrayList<>();
		for (Infection infection : this.infections.values()) {
			if (infection.owner().equals(uuid)) {
				mine.add(infection);
			}
		}

		if (!mine.isEmpty()) {
			// Second cast: blow up everything this caster infected. The first target
			// gets the full 30-tick detonation show, the rest are paid out at once.
			Infection first = mine.get(0);
			detonate(level, first);
			double detonateDamage = ctx.cfgd("abilities.withersymbiote.infection.detonate_damage", 8.0D);
			for (Infection infection : mine) {
				if (infection == first) {
					continue;
				}
				LivingEntity target = living(level, infection.target());
				if (target != null && !target.isRemoved() && allowed(player, target)) {
					TrueDamage.apply(target, detonateDamage, player);
				}
				stopInfection(infection.target());
			}
			stopInfection(first.target());
			this.detonateLockout.put(uuid, now + DETONATE_LOCKOUT_MILLIS);
			return;
		}

		Long primeEnd = this.primed.get(uuid);
		if (primeEnd != null && now < primeEnd) {
			// Already primed and nothing to detonate: the click does nothing at all.
			return;
		}

		int cooldown = ctx.cfg("abilities.withersymbiote.infection.cooldown", 40);
		if (!ctx.gate(KEY_INFECT, "Symbiote Infection", cooldown)) {
			return;
		}
		CooldownBars.show(player, KEY_INFECT, "Symbiote Infection", BossEvent.BossBarColor.GREEN, cooldown);
		this.primed.put(uuid, now + PRIME_WINDOW_MILLIS);

		Vec3 at = player.position().add(0.0D, 2.5D, 0.0D);
		Fx.simple(level, "LARGE_SMOKE", at, 30, 0.5D, 0.5D, 0.5D, 0.02D);
		Fx.sound(level, at, "ENTITY_WITHER_AMBIENT", 0.6F, 0.6F);

		int glowAmplifier = ctx.cfg("abilities.withersymbiote.infection.prime_glow_amplifier", 0);
		Effects.apply(player, "GLOWING", 20, glowAmplifier, true, false, false);
		Messaging.actionBar(player, "<dark_green>Symbiote Infection primed - next hit infects");
	}

	/**
	 * {@code WitherSymbioteAbilities#applyInfection} - registers the infection and
	 * starts its 5-tick task, which puffs squid ink, re-spreads to untrusted
	 * neighbours and expires the infection when its window closes.
	 */
	private void applyInfection(ServerLevel level, ServerPlayer owner, LivingEntity target, int durationSeconds) {
		UUID targetId = target.getUUID();
		if (this.infections.containsKey(targetId)) {
			return;
		}
		Infection infection = new Infection(owner.getUUID(), targetId,
				System.currentTimeMillis() + durationSeconds * 1000L);
		this.infections.put(targetId, infection);

		int spreadRadius = cfgInt("abilities.withersymbiote.infection.spread_radius", 3);
		TickScheduler.Task task = this.mod.scheduler().timer(() -> {
			Infection current = this.infections.get(targetId);
			LivingEntity infected = living(level, targetId);
			if (current != infection || infected == null || infected.isRemoved()) {
				stopInfection(targetId);
				return;
			}
			if (System.currentTimeMillis() > infection.expiresAtMillis()) {
				expireInfection(level, infection, infected);
				stopInfection(targetId);
				return;
			}

			ServerLevel infectedLevel = (ServerLevel) infected.level();
			Vec3 at = infected.position().add(0.0D, 1.0D, 0.0D);
			Fx.simple(infectedLevel, "SQUID_INK", at, 4, 0.2D, 0.2D, 0.2D, 0.0D);

			ServerPlayer source = player(infectedLevel, infection.owner());
			if (source == null) {
				return;
			}
			for (LivingEntity neighbour : nearbyLiving(infectedLevel, at, spreadRadius)) {
				if (neighbour.equals(infected) || this.infections.containsKey(neighbour.getUUID())) {
					continue;
				}
				if (neighbour instanceof ServerPlayer victim && !trusted(source, victim)) {
					continue;
				}
				applyInfection(infectedLevel, source, neighbour, durationSeconds);
			}
		}, 0L, 5L);
		this.infectionTasks.put(targetId, task);
	}

	/** {@code WitherSymbioteAbilities#expireInfection}. */
	private void expireInfection(ServerLevel level, Infection infection, LivingEntity target) {
		int weaknessSeconds = cfgInt("abilities.withersymbiote.infection.weakness_duration", 8);
		int weaknessAmplifier = cfgInt("abilities.withersymbiote.infection.weakness_amplifier", 0);
		Effects.apply(target, "WEAKNESS", weaknessSeconds * 20, weaknessAmplifier, true, true, true);

		ServerPlayer owner = player(level, infection.owner());
		double damage = cfgDouble("abilities.withersymbiote.infection.detonate_damage", 8.0D) * 0.5D;
		if (allowed(owner, target)) {
			TrueDamage.apply(target, damage, owner);
		}
	}

	/** {@code WitherSymbioteAbilities#detonate} - the 30-tick boss-bar explosion. */
	private void detonate(ServerLevel level, Infection infection) {
		LivingEntity target = living(level, infection.target());
		if (target == null || target.isRemoved()) {
			return;
		}
		UUID targetId = infection.target();
		if (!this.detonating.add(targetId)) {
			return;
		}

		ServerPlayer owner = player(level, infection.owner());
		ServerBossEvent bar = Messaging.bossBar("<green>Wither Detonation",
				BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
		if (target instanceof ServerPlayer victim) {
			bar.addPlayer(victim);
		}
		if (owner != null) {
			bar.addPlayer(owner);
		}
		bar.setProgress(1.0F);
		this.detonationBars.put(targetId, bar);

		final int[] tick = {0};
		final boolean[] exploded = {false};
		TickScheduler.Task task = this.mod.scheduler().timer(() -> {
			LivingEntity current = living(level, targetId);
			if (current == null || current.isRemoved()) {
				finishDetonation(targetId);
				return;
			}
			if (tick[0] >= DETONATION_TICKS) {
				if (exploded[0]) {
					return;
				}
				exploded[0] = true;
				ServerLevel currentLevel = (ServerLevel) current.level();
				spawnDetonationCloud(currentLevel, current.position());
				Fx.sound(currentLevel, current.position(), "ENTITY_WITHER_DEATH", 0.6F, 1.4F);
				double damage = cfgDouble("abilities.withersymbiote.infection.detonate_damage", 8.0D);
				if (allowed(owner, current)) {
					TrueDamage.apply(current, damage, owner);
				}
				// Bukkit cancelled this task and kept the bar up one more second.
				this.mod.scheduler().later(() -> finishDetonation(targetId), 20L);
				cancel(this.detonationTasks, targetId);
				return;
			}
			ServerBossEvent shown = this.detonationBars.get(targetId);
			if (shown != null) {
				shown.setProgress((float) Math.max(0.0D, 1.0D - tick[0] / (double) DETONATION_TICKS));
			}
			Fx.simple((ServerLevel) current.level(), "SQUID_INK", current.position().add(0.0D, 1.0D, 0.0D),
					2, 0.25D, 0.15D, 0.25D, 0.0D);
			tick[0]++;
		}, 0L, 1L);
		this.detonationTasks.put(targetId, task);
	}

	private void finishDetonation(UUID targetId) {
		cancel(this.detonationTasks, targetId);
		ServerBossEvent bar = this.detonationBars.remove(targetId);
		if (bar != null) {
			bar.removeAllPlayers();
		}
		this.detonating.remove(targetId);
	}

	private void stopInfection(UUID targetId) {
		this.infections.remove(targetId);
		cancel(this.infectionTasks, targetId);
	}

	/** {@code WitherSymbioteAbilities#spawnDetonationCloud}. */
	private void spawnDetonationCloud(ServerLevel level, Vec3 at) {
		Vec3 center = at.add(0.0D, 1.0D, 0.0D);
		// 40 squid-ink motes scattered through a unit sphere, each pushed out by a
		// random radius between 0.1 and 1.0.
		RandomSource random = level.getRandom();
		for (int i = 0; i < 40; i++) {
			double yaw = random.nextDouble() * Math.PI * 2.0D;
			double vertical = range(random, -0.8D, 0.8D);
			double radius = range(random, 0.1D, 1.0D);
			double horizontal = Math.sqrt(Math.max(0.0D, 1.0D - vertical * vertical));
			Vec3 offset = new Vec3(Math.cos(yaw) * horizontal * radius, vertical * radius,
					Math.sin(yaw) * horizontal * radius);
			Fx.simple(level, "SQUID_INK", center.add(offset), 1, 0.05D, 0.05D, 0.05D, 0.0D);
		}

		// Three rising puffs at 1.0/1.3/0.8 blocks, each fired as a velocity
		// (Bukkit count 0 + extra 1.0), spun a third of a turn apart.
		double[] heights = {1.0D, 1.3D, 0.8D};
		for (int i = 0; i < heights.length; i++) {
			Vec3 puff = at.add(0.0D, heights[i], 0.0D);
			double angle = i * (Math.PI * 2.0D / 3.0D);
			double x = Math.cos(angle) * 0.12D + range(random, -0.02D, 0.02D);
			double y = 0.06D + range(random, -0.01D, 0.02D);
			double z = Math.sin(angle) * 0.12D + range(random, -0.02D, 0.02D);
			for (int j = 0; j < 3; j++) {
				Fx.simple(level, "SQUID_INK", puff, 0, x, y, z, 1.0D);
			}
		}
	}

	// ---------------------------------------------------------- symbiote rampage

	/** {@code WitherSymbioteAbilities#tryRampage}. */
	private void tryRampage(AbilityContext ctx) {
		ServerPlayer player = ctx.player();
		ServerLevel level = ctx.level();
		UUID uuid = player.getUUID();
		if (this.rampages.containsKey(uuid)) {
			return;
		}

		int cooldown = ctx.cfg("abilities.withersymbiote.rampage.cooldown", 120);
		if (!ctx.gate(KEY_RAMPAGE, "Symbiote Rampage", cooldown)) {
			return;
		}
		CooldownBars.show(player, KEY_RAMPAGE, "Symbiote Rampage", BossEvent.BossBarColor.RED, cooldown);

		// Read for parity with the plugin, which never uses it: the bar above is
		// sized by the cooldown, and how long rampage actually lasts comes from
		// initial_duration plus whatever the sustain buffer buys.
		ctx.cfg("abilities.withersymbiote.rampage.duration", 30);

		double bonusHealth = ctx.cfgd("abilities.withersymbiote.rampage.bonus_health", 6.0D);
		double healAmount = ctx.cfgd("abilities.withersymbiote.rampage.heal_amount", 6.0D);
		double speedBonus = ctx.cfgd("abilities.withersymbiote.rampage.speed_bonus", 0.5D);

		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null) {
			maxHealth.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER_HEALTH, bonusHealth,
					AttributeModifier.Operation.ADD_VALUE));
		}
		double healthCap = maxHealth != null ? maxHealth.getValue() : 20.0D;
		player.setHealth((float) Math.min(player.getHealth() + healAmount, healthCap));

		AttributeInstance movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (movementSpeed != null) {
			// Bukkit's MULTIPLY_SCALAR_1 is +50% of the total, i.e. ADD_MULTIPLIED_TOTAL.
			movementSpeed.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER_SPEED, speedBonus,
					AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}

		clearCobwebs(level, player.blockPosition());

		long now = System.currentTimeMillis();
		double initialDuration = ctx.cfgd("abilities.withersymbiote.rampage.initial_duration", 6.0D);
		double barFullSeconds = ctx.cfgd("abilities.withersymbiote.rampage.bar_full_seconds", 5.0D);
		Rampage rampage = new Rampage(uuid, now + (long) (initialDuration * 1000.0D), barFullSeconds * 1000.0D);
		this.rampages.put(uuid, rampage);
		Messaging.actionBar(player, "<red>RAMPAGE - sustain by dealing damage, avoid taking damage");

		// Bukkit handed this player a private WorldBorder with an absurd warning
		// distance, which is what paints the darkened screen edges for the ability.
		sendRampageBorder(player);

		this.meterTasks.put(uuid, this.mod.scheduler().timer(() -> tickRampageMeter(level, uuid), 10L, 10L));
		this.trailTasks.put(uuid, this.mod.scheduler().timer(() -> tickRampageTrail(level, uuid), 0L, 4L));
	}

	/** The 7x7x7 cobweb clear rampage starts with. */
	private void clearCobwebs(ServerLevel level, BlockPos origin) {
		for (int x = -3; x <= 3; x++) {
			for (int y = -3; y <= 3; y++) {
				for (int z = -3; z <= 3; z++) {
					BlockPos pos = origin.offset(x, y, z);
					if (level.getBlockState(pos).is(Blocks.COBWEB)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
	}

	/** The 10-tick sustain meter task; also the thing that ends rampage. */
	private void tickRampageMeter(ServerLevel level, UUID uuid) {
		Rampage rampage = this.rampages.get(uuid);
		if (rampage == null) {
			cancel(this.meterTasks, uuid);
			return;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
		if (player == null) {
			// Bukkit ended rampage here too; see #onPlayerQuit for what that meant.
			this.rampages.remove(uuid);
			cancel(this.meterTasks, uuid);
			cancel(this.trailTasks, uuid);
			return;
		}
		long now = System.currentTimeMillis();
		if (now > rampage.bufferUntilMillis) {
			endRampage(player);
			return;
		}

		double fraction = Math.max(0.0D, Math.min(1.0D, (rampage.bufferUntilMillis - now) / rampage.barFullMillis));

		// Rampage burns saturation as it is sustained (Bukkit setSaturation - 0.5).
		// FoodData keeps its saturation private with no setter, so the field is
		// widened in altarsmp.accesswidener rather than approximated with exhaustion.
		FoodData food = player.getFoodData();
		food.saturationLevel = Math.max(0.0F, food.saturationLevel - 0.5F);

		int filled = (int) Math.round(fraction * METER_WIDTH);
		String color = fraction > 0.66D ? "<green>" : (fraction > 0.33D ? "<yellow>" : "<red>");
		int midpoint = METER_WIDTH / 2;
		StringBuilder meter = new StringBuilder();
		for (int i = 0; i < METER_WIDTH; i++) {
			if (i == midpoint) {
				meter.append("<white>|");
			} else if (i < filled) {
				meter.append(color).append('|');
			} else {
				meter.append("<dark_gray>|");
			}
		}
		Messaging.actionBar(player, meter.toString());
	}

	/** The 4-tick squid-ink trail task. */
	private void tickRampageTrail(ServerLevel level, UUID uuid) {
		if (!this.rampages.containsKey(uuid)) {
			cancel(this.trailTasks, uuid);
			return;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
		if (player == null) {
			cancel(this.trailTasks, uuid);
			return;
		}
		Vec3 base = player.position();
		Vec3 head = base.add(0.0D, 1.0D, 0.0D);
		Vec3 feet = base.add(0.0D, 0.4D, 0.0D);
		Fx.simple(level, "SQUID_INK", head, 1, 0.25D, 0.25D, 0.25D, 0.0D);
		Fx.simple(level, "SQUID_INK", feet, 1, 0.25D, 0.2D, 0.25D, 0.0D);
		if (level.getRandom().nextDouble() < 0.4D) {
			Fx.simple(level, "WHITE_SMOKE", head, 1, 0.2D, 0.3D, 0.2D, 0.0D);
		}
	}

	/** {@code WitherSymbioteAbilities#endRampage}. */
	private void endRampage(ServerPlayer player) {
		UUID uuid = player.getUUID();
		if (this.rampages.remove(uuid) == null) {
			return;
		}
		cancel(this.meterTasks, uuid);
		cancel(this.trailTasks, uuid);

		ServerLevel level = (ServerLevel) player.level();
		restoreBorder(player, level);
		removeRampageModifiers(player);

		int weaknessSeconds = cfgInt("abilities.withersymbiote.rampage.exit_weakness_duration", 10);
		int weaknessAmplifier = cfgInt("abilities.withersymbiote.rampage.exit_weakness_amplifier", 0);
		int witherSeconds = cfgInt("abilities.withersymbiote.rampage.exit_wither_duration", 10);
		int witherAmplifier = cfgInt("abilities.withersymbiote.rampage.exit_wither_amplifier", 0);
		Effects.apply(player, "WEAKNESS", weaknessSeconds * 20, weaknessAmplifier, true, true, true);
		Effects.apply(player, "WITHER", witherSeconds * 20, witherAmplifier, true, true, true);

		double healthLoss = cfgDouble("abilities.withersymbiote.rampage.exit_health_loss", 6.0D);
		TrueDamage.apply(player, healthLoss, null);
		Messaging.actionBar(player, "<red>Rampage ended");
	}

	private void removeRampageModifiers(ServerPlayer player) {
		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null) {
			maxHealth.removeModifier(MODIFIER_HEALTH);
		}
		AttributeInstance movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (movementSpeed != null) {
			movementSpeed.removeModifier(MODIFIER_SPEED);
		}
	}

	// --------------------------------------------------------------- slash arc

	/** {@code WitherSymbioteAbilities#spawnSlashArc}. */
	private void spawnSlashArc(ServerLevel level, ServerPlayer player, LivingEntity target) {
		Vec3 center = target.position().add(0.0D, 1.0D, 0.0D);
		Vec3 direction = player.getLookAngle();
		Vec3 right = direction.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
		Vec3 up = right.cross(direction).normalize();
		double theta = range(level.getRandom(), -Math.PI, Math.PI);
		drawSlash(level, center, right, up, theta);
	}

	/** {@code WitherSymbioteAbilities#drawSlash}. */
	private void drawSlash(ServerLevel level, Vec3 center, Vec3 right, Vec3 up, double theta) {
		Vec3 planeX = right.scale(Math.cos(theta)).add(up.scale(Math.sin(theta)));
		Vec3 planeY = right.scale(-Math.sin(theta)).add(up.scale(Math.cos(theta)));
		for (int i = 0; i <= SLASH_SEGMENTS; i++) {
			double t = i / (double) SLASH_SEGMENTS;
			double angle = (t - 0.5D) * SLASH_SWEEP;
			Vec3 offset = planeX.scale(Math.sin(angle) * SLASH_RADIUS)
					.add(planeY.scale(Math.cos(angle) * SLASH_RADIUS * 0.25D));
			Vec3 at = center.add(offset);
			Fx.simple(level, "WAX_OFF", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			Fx.simple(level, "SMOKE", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	// ----------------------------------------------------------- world border

	private void sendRampageBorder(ServerPlayer player) {
		WorldBorder border = new WorldBorder();
		border.setCenter(player.getX(), player.getZ());
		border.setSize(RAMPAGE_BORDER_SIZE);
		border.setWarningBlocks(RAMPAGE_BORDER_WARNING);
		border.setWarningTime(0);
		player.connection.send(new ClientboundInitializeBorderPacket(border));
	}

	/** Bukkit's {@code setWorldBorder(null)} - hand the real border back. */
	private void restoreBorder(ServerPlayer player, ServerLevel level) {
		player.connection.send(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
	}

	// ------------------------------------------------------------------ helpers

	private List<LivingEntity> nearbyLiving(ServerLevel level, Vec3 center, double radius) {
		AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
				center.x + radius, center.y + radius, center.z + radius);
		return level.getEntitiesOfClass(LivingEntity.class, box);
	}

	/**
	 * Bukkit's {@code Bukkit.getEntity(uuid)} filtered to living entities: looked up
	 * in the level the ability started in first, then in every other dimension.
	 */
	private LivingEntity living(ServerLevel level, UUID uuid) {
		Entity found = level.getEntityInAnyDimension(uuid);
		return found instanceof LivingEntity living && !living.isRemoved() ? living : null;
	}

	private ServerPlayer player(ServerLevel level, UUID uuid) {
		return level.getServer().getPlayerList().getPlayer(uuid);
	}

	/**
	 * Bukkit's {@code immunity.a(attacker, victimOrNull)} combined with
	 * {@code factions.isTrusted} - both live behind {@code AbilityImmunity} here.
	 */
	private boolean allowed(ServerPlayer attacker, LivingEntity target) {
		return !(target instanceof ServerPlayer victim) || this.mod.immunity().allowAbilityHit(attacker, victim);
	}

	/**
	 * Bukkit's {@code TrustManager#isTrusted(a, b)} - the trust set the plugin
	 * persisted per player, so a friend is never infected by the spread. Kept apart
	 * from {@link #allowed} on purpose: the immunity gate has side effects (it arms
	 * both cooldowns), a trust lookup must not.
	 */
	private boolean trusted(ServerPlayer attacker, ServerPlayer victim) {
		return this.mod.store().player(attacker.getUUID()).trusted().contains(String.valueOf(victim.getUUID()));
	}

	/** Bukkit's {@code ThreadLocalRandom#nextDouble(min, max)} - RandomSource has none. */
	private static double range(RandomSource random, double min, double max) {
		return min + (max - min) * random.nextDouble();
	}

	private void cancel(Map<UUID, TickScheduler.Task> tasks, UUID key) {
		TickScheduler.Task task = tasks.remove(key);
		if (task != null) {
			task.cancel();
		}
	}

	// Config reads for work that happens inside a scheduled task, where there is no
	// AbilityContext to ask. Season 2 read s2.yml (r#n()), and AltarConfig#s2()
	// falls back to the merged config.yml, so this resolves exactly like ctx.cfg*.

	private int cfgInt(String path, int def) {
		return this.mod.config().s2().getInt(path, def);
	}

	private double cfgDouble(String path, double def) {
		return this.mod.config().s2().getDouble(path, def);
	}

	/** Bukkit's {@code WitherSymbioteAbilities.a(UUID owner, UUID target, long expiry)}. */
	private record Infection(UUID owner, UUID target, long expiresAtMillis) {
	}

	/** Bukkit's {@code WitherSymbioteAbilities.b} - the sustain buffer is mutable. */
	private static final class Rampage {
		private final UUID owner;
		private final double barFullMillis;
		private long bufferUntilMillis;

		private Rampage(UUID owner, long bufferUntilMillis, double barFullMillis) {
			this.owner = owner;
			this.bufferUntilMillis = bufferUntilMillis;
			this.barFullMillis = barFullMillis;
		}
	}
}
