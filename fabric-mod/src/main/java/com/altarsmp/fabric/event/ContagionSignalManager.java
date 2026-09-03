package com.altarsmp.fabric.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.data.WorldRecord;
import com.altarsmp.fabric.faction.FactionManager;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TickScheduler;
import com.altarsmp.fabric.weapon.WeaponRegistry;

/**
 * Contagion Signal ritual - port of
 * {@code com.altarsmp.managers.ContagionSignalManager} and the placement half of
 * {@code com.altarsmp.weapons.ContagionSignalWeapon}.
 *
 * <p>Docking the signal on an altar (a structure block) starts a 600-tick ritual:
 * a {@code STRIPPED_WARPED_HYPHAE} block display scaled 1.5 and lit to full
 * brightness spins three degrees a tick, a 150-block END_ROD beam with white dust
 * every fourth block, a pulsing 20-point ring and a rising firework spiral are
 * drawn every two ticks with a conduit ambience every fifth, a boot sequence of
 * spyglass, golem-repair, trapdoor, conduit, beacon, sonic-charge and a wither
 * arrival plays on its own schedule, and a green segmented boss bar counts the
 * ritual down for the whole server.
 *
 * <p>The 2.5x2.5 interaction hitbox below it takes hits: each one is cancelled,
 * throttled to 150 ms per attacker, and removes 20% of the attacker's attack
 * damage (or the projectile's damage) from the signal's 500 health, with damage
 * indicators and an iron golem hurt sound. Warnings go out at 75%, 50% and 25%,
 * and at zero the signal drops itself as an item and is destroyed.
 *
 * <p>When the ritual finishes everyone online is converted to the tuner's role,
 * offline players are queued for conversion on their next join (the plugin rewrote
 * its own playerdata YML files for that; the port keeps the queue in its own
 * store), the completion title, sounds and particles fire, and everything is
 * cleaned up.
 */
public final class ContagionSignalManager {

	/** The plugin's hard-coded ritual constants. */
	private static final double MAX_HEALTH = 500.0D;
	private static final double DAMAGE_FACTOR = 0.19999999999999996D;
	private static final int RITUAL_TICKS = 600;
	private static final long HIT_THROTTLE_MILLIS = 150L;
	private static final float SPIN_DEGREES_PER_TICK = 3.0F;
	private static final String DISPLAY_TAG = "contagion_signal_display";
	private static final String HITBOX_TAG = "contagion_signal_hitbox";

	private final AltarSMPMod mod;
	private final RandomSource random = RandomSource.create();
	private final Map<UUID, Long> lastHit = new HashMap<>();

	private boolean active;
	@Nullable
	private ResourceKey<Level> dimension;
	private Vec3 signal = Vec3.ZERO;
	@Nullable
	private UUID owner;
	private String role = "human";
	private double health = MAX_HEALTH;
	private int remaining = RITUAL_TICKS;
	private boolean warned75;
	private boolean warned50;
	private boolean warned25;
	@Nullable
	private Display.BlockDisplay display;
	@Nullable
	private Interaction hitbox;
	@Nullable
	private ServerBossEvent bar;
	private float spin;
	private int particleTick;

	public ContagionSignalManager(AltarSMPMod mod) {
		this.mod = mod;
	}

	public boolean isSignalActive() {
		return this.active;
	}

	@Nullable
	public Vec3 getSignalLocation() {
		return this.active ? this.signal : null;
	}

	/**
	 * The plugin's placement came from right-clicking an altar block with the weapon.
	 * The port keeps that rule: docking anywhere else is refused with the same
	 * message, and the item is only consumed when the ritual really starts.
	 *
	 * @param player the player docking the signal
	 * @return {@code true} when the ritual started
	 */
	public boolean broadcast(ServerPlayer player) {
		if (this.active) {
			Messaging.send(player, "<red>A Contagion Signal ritual is already running.");
			return false;
		}
		ServerLevel level = player.serverLevel();
		BlockPos below = player.blockPosition().below();
		if (!level.getBlockState(below).is(Blocks.STRUCTURE_BLOCK)) {
			Messaging.send(player, "<red>You must place this on an altar block.");
			return false;
		}
		ItemStack held = player.getMainHandItem();
		placeSignal(player, Vec3.atLowerCornerOf(below).add(0.5D, 1.0D, 0.5D), roleOf(player));
		held.shrink(1);
		return true;
	}

	/** {@code ContagionSignalWeapon#getPlayerRole}'s lower-case ritual role. */
	public static String roleOf(ServerPlayer player) {
		if (FactionManager.isPale(player) || FactionManager.isPaleKing(player)) {
			return "pale";
		}
		if (FactionManager.isVampire(player) || FactionManager.isVampireKing(player)) {
			return "vampire";
		}
		return "human";
	}

	/** Nothing to register: the ritual is driven by {@link #tick} and the death pipeline. */
	public void registerEventHooks() {
		// Intentionally empty - see #tick.
	}

	/** Restores the persisted ritual and re-queues offline conversions. */
	public void loadFromStore() {
		WorldRecord.EventState record = this.mod.store().world().contagion();
		this.active = record.active();
		String world = record.data().get("world");
		this.dimension = world == null || world.isBlank() ? null
				: ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(world));
		this.role = record.data().getOrDefault("role", "human");
		this.owner = record.data().containsKey("owner") && !record.data().get("owner").isBlank()
				? UUID.fromString(record.data().get("owner")) : null;
		this.health = record.numbers().getOrDefault("health", (long) MAX_HEALTH);
		this.remaining = record.numbers().getOrDefault("remaining", (long) RITUAL_TICKS).intValue();
		double x = record.numbers().getOrDefault("x", 0L);
		double y = record.numbers().getOrDefault("y", 0L);
		double z = record.numbers().getOrDefault("z", 0L);
		this.signal = new Vec3(x, y, z);
		this.warned75 = record.numbers().getOrDefault("warned75", 0L) != 0L;
		this.warned50 = record.numbers().getOrDefault("warned50", 0L) != 0L;
		this.warned25 = record.numbers().getOrDefault("warned25", 0L) != 0L;
		if (this.active) {
			AltarSMPMod.LOGGER.info("[AltarSMP] resuming contagion ritual tuned to {} with {}s left", this.role,
					this.remaining);
		}
	}

	/** Called on shutdown; the ritual state stays stored so it resumes next boot. */
	public void stopForShutdown() {
		despawnEntities();
		persist();
	}

	/** {@code /contagion stop} - the plugin's {@code forceStop}. */
	public void forceStop() {
		if (!this.active) {
			return;
		}
		cleanup();
		MinecraftServer server = this.mod.server();
		if (server != null) {
			Messaging.broadcast(server, "<red>Contagion Signal has been forcefully stopped by an admin.");
		}
	}

	// ---------------------------------------------------------------- placing

	/** {@code ContagionSignalManager#placeSignal}. */
	public synchronized void placeSignal(ServerPlayer player, Vec3 at, String role) {
		if (this.active) {
			return;
		}
		MinecraftServer server = this.mod.server();
		ServerLevel level = player.serverLevel();
		this.active = true;
		this.dimension = level.dimension();
		this.signal = at;
		this.owner = player.getUUID();
		this.role = role;
		this.health = MAX_HEALTH;
		this.remaining = RITUAL_TICKS;
		this.warned75 = false;
		this.warned50 = false;
		this.warned25 = false;
		this.spin = 0.0F;
		this.particleTick = 0;
		this.lastHit.clear();

		spawnEntities(level, at);

		String displayName = roleDisplayName(role);
		this.bar = Messaging.bossBar("<green><bold>" + displayName.toUpperCase() + " CONTAGION RITUAL",
				BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
		this.bar.setProgress(1.0F);
		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			Messaging.showBossBar(this.bar, online);
		}

		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			Messaging.send(online, "<yellow>>> <green># SUCCESSFULLY DOCKED TO ALTAR. BEGIN BOOTING SEQUENCE.");
			Messaging.send(online, "");
			Messaging.send(online, "<gray>" + role + " [x3]");
			Messaging.send(online, "");
			Messaging.send(online, "<yellow>>> <green># CONTAGION RITUAL TUNED TO <yellow><bold><underlined>"
					+ displayName.toUpperCase() + "</underlined></bold><green>.");
		}

		playSignalBootSequence(level, at);
		persist();
	}

	/** The block display and the interaction hitbox the plugin spawned. */
	private void spawnEntities(ServerLevel level, Vec3 at) {
		Display.BlockDisplay blockDisplay = EntityType.BLOCK_DISPLAY.create(level,
				net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
		if (blockDisplay != null) {
			blockDisplay.snapTo(at.add(0.0D, 0.5D, 0.0D));
			blockDisplay.setBlockState(Blocks.STRIPPED_WARPED_HYPHAE.defaultBlockState());
			blockDisplay.setBrightnessOverride(new Brightness(15, 15));
			blockDisplay.setTransformation(spinTransformation(0.0F));
			blockDisplay.addTag(DISPLAY_TAG);
			level.addFreshEntity(blockDisplay);
			this.display = blockDisplay;
		}
		Interaction interaction = EntityType.INTERACTION.create(level,
				net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
		if (interaction != null) {
			interaction.snapTo(at.add(0.0D, -0.75D, 0.0D));
			// Bukkit's setInteractionWidth/Height/Responsive - the synched data keys are
			// private in 26.x, so they are set through the widened accessors.
			interaction.getEntityData().set(Interaction.DATA_WIDTH_ID, 2.5F);
			interaction.getEntityData().set(Interaction.DATA_HEIGHT_ID, 2.5F);
			interaction.getEntityData().set(Interaction.DATA_RESPONSE_ID, true);
			interaction.addTag(HITBOX_TAG);
			level.addFreshEntity(interaction);
			this.hitbox = interaction;
		}
	}

	private static Transformation spinTransformation(float degrees) {
		return new Transformation(new Vector3f(-0.75F, -0.75F, -0.75F),
				new AxisAngle4f((float) Math.toRadians(degrees), 0.0F, 1.0F, 0.0F),
				new Vector3f(1.5F, 1.5F, 1.5F), new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F));
	}

	private void despawnEntities() {
		if (this.display != null && !this.display.isRemoved()) {
			this.display.discard();
		}
		if (this.hitbox != null && !this.hitbox.isRemoved()) {
			this.hitbox.discard();
		}
		this.display = null;
		this.hitbox = null;
	}

	/** {@code ContagionSignalManager#playSignalBootSequence}. */
	private void playSignalBootSequence(ServerLevel level, Vec3 at) {
		TickScheduler scheduler = this.mod.scheduler();
		scheduler.later(() -> Fx.sound(level, at, "ITEM_SPYGLASS_USE", 1.0F, 0.8F), 0L);
		scheduler.later(() -> Fx.sound(level, at, "ITEM_SPYGLASS_USE", 1.0F, 0.9F), 1L);
		scheduler.later(() -> Fx.sound(level, at, "ITEM_SPYGLASS_USE", 1.0F, 1.0F), 2L);
		scheduler.later(() -> Fx.sound(level, at, "ENTITY_IRON_GOLEM_REPAIR", 1.0F, 1.0F), 14L);
		scheduler.later(() -> Fx.sound(level, at, "BLOCK_IRON_TRAPDOOR_CLOSE", 1.0F, 1.5F), 24L);
		scheduler.later(() -> Fx.sound(level, at, "BLOCK_IRON_TRAPDOOR_CLOSE", 1.0F, 1.2F), 29L);
		scheduler.later(() -> {
			Fx.sound(level, at, "BLOCK_CONDUIT_ACTIVATE", 2.0F, 1.0F);
			Fx.sound(level, at, "ENTITY_EVOKER_PREPARE_SUMMON", 2.0F, 1.0F);
		}, 200L);
		scheduler.later(() -> {
			Fx.sound(level, at, "BLOCK_BEACON_ACTIVATE", 2.0F, 1.0F);
			Fx.sound(level, at, "ENTITY_WARDEN_SONIC_CHARGE", 8.0F, 1.0F);
		}, 230L);
		scheduler.later(() -> {
			Vec3 sky = at.add(0.0D, 15.0D, 0.0D);
			Fx.simple(level, "EXPLOSION", sky, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			Fx.simple(level, "EXPLOSION_EMITTER", sky, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			Fx.simple(level, "SOUL", sky, 100, 1.0D, 1.0D, 1.0D, 1.0D);
			Fx.simple(level, "SOUL_FIRE_FLAME", sky, 100, 1.0D, 1.0D, 1.0D, 1.0D);
			Fx.sound(level, at, "ENTITY_WITHER_DEATH", 8.0F, 0.5F);
			Fx.sound(level, at, "ENTITY_WITHER_SPAWN", 8.0F, 0.8F);
			Fx.sound(level, at, "ENTITY_GENERIC_EXPLODE", 8.0F, 0.5F);
		}, 260L);
	}

	// ------------------------------------------------------------------- tick

	public void tick(MinecraftServer server) {
		if (!this.active) {
			applyQueuedConversions(server);
			return;
		}
		ServerLevel level = signalLevel(server);
		if (level == null) {
			return;
		}
		// The plugin's rotation task: three degrees a tick.
		this.spin += SPIN_DEGREES_PER_TICK;
		if (this.spin >= 360.0F) {
			this.spin = 0.0F;
		}
		if (this.display != null && !this.display.isRemoved()) {
			this.display.setTransformation(spinTransformation(this.spin));
		} else {
			spawnEntities(level, this.signal);
		}
		if (this.particleTick % 2 == 0) {
			spawnParticles(level);
		}
		this.particleTick++;
		if (this.particleTick % 20 != 0) {
			return;
		}
		// The plugin's timer task: one ritual second per 20 ticks.
		this.remaining = Math.max(0, this.remaining - 1);
		updateBossBar(server);
		if (this.remaining <= 0) {
			onRitualComplete(server, level);
		}
	}

	/** {@code startParticleEffect}'s body - beam, pulsing ring, spiral, flash, ambience. */
	private void spawnParticles(ServerLevel level) {
		Vec3 base = this.signal;
		int frame = this.particleTick / 2;
		for (int y = 0; y < 150; y += 2) {
			Vec3 at = base.add(0.0D, y, 0.0D);
			Fx.simple(level, "END_ROD", at, 1, 0.1D, 0.0D, 0.1D, 0.0D);
			if (y % 4 == 0) {
				Fx.dust(level, at, 0xFFFFFF, 1.2F, 2, 0.15D, 0.0D, 0.15D);
			}
		}
		if (frame % 10 < 5) {
			double ringRadius = 1.5D + Math.sin(frame * 0.2D) * 0.5D;
			for (int i = 0; i < 20; i++) {
				double angle = Math.PI * 2.0D * i / 20.0D;
				Vec3 at = base.add(ringRadius * Math.cos(angle), 0.5D, ringRadius * Math.sin(angle));
				Fx.simple(level, "END_ROD", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}
		double spiral = frame * 0.15D;
		for (int y = 0; y < 50; y += 5) {
			Vec3 at = base.add(0.8D * Math.cos(spiral + y * 0.1D), y, 0.8D * Math.sin(spiral + y * 0.1D));
			Fx.simple(level, "FIREWORK", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		if (frame % 40 == 0) {
			Fx.colored(level, "FLASH", base.add(0.0D, 2.0D, 0.0D), 0xFFFFFFFF, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		if (frame % 5 == 0) {
			Fx.sound(level, base, "BLOCK_CONDUIT_AMBIENT_SHORT", 1.5F,
					0.9F + (float) this.random.nextDouble() * 0.2F);
		}
	}

	/** {@code ContagionSignalManager#updateBossBar}. */
	private void updateBossBar(MinecraftServer server) {
		if (this.bar == null) {
			return;
		}
		String displayName = roleDisplayName(this.role);
		int seconds = Math.max(0, this.remaining);
		String clock = String.format("%d:%02d", seconds / 60, seconds % 60);
		double fraction = this.health / MAX_HEALTH;
		this.bar.setProgress((float) Math.max(0.0D, Math.min(1.0D, fraction)));
		this.bar.setColor(fraction > 0.5D ? BossEvent.BossBarColor.GREEN
				: fraction > 0.25D ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.RED);
		this.bar.setName(Messaging.msg("<green><bold>" + displayName.toUpperCase()
				+ " CONTAGION RITUAL <gray>" + clock));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.showBossBar(this.bar, player);
		}
	}

	// ----------------------------------------------------------------- damage

	/**
	 * {@code ContagionSignalManager#onSignalDamage}. Called from the damage pipeline
	 * ({@code weapon.CombatHooks}) whenever the hit entity is the ritual's hitbox.
	 *
	 * @return {@code true} - signal damage is always cancelled and charged to the ritual
	 */
	public boolean onSignalDamage(Entity damaged, DamageSource source, float amount) {
		if (!this.active || this.hitbox == null || !this.hitbox.equals(damaged)) {
			return false;
		}
		ServerPlayer attacker = source.getEntity() instanceof ServerPlayer player ? player
				: source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile
						&& projectile.getOwner() instanceof ServerPlayer shooter ? shooter : null;
		if (attacker == null) {
			return true;
		}
		long now = System.currentTimeMillis();
		Long previous = this.lastHit.get(attacker.getUUID());
		if (previous != null && now - previous < HIT_THROTTLE_MILLIS) {
			return true;
		}
		this.lastHit.put(attacker.getUUID(), now);

		double damage = amount;
		if (!(source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile)) {
			AttributeInstance attack = attacker.getAttribute(Attributes.ATTACK_DAMAGE);
			damage = attack == null ? 1.0D : attack.getValue();
		}
		this.health -= damage * DAMAGE_FACTOR;

		MinecraftServer server = this.mod.server();
		ServerLevel level = server == null ? null : signalLevel(server);
		if (level != null) {
			Vec3 at = this.signal.add(0.0D, 1.0D, 0.0D);
			Fx.simple(level, "DAMAGE_INDICATOR", at, 5, 0.3D, 0.3D, 0.3D, 0.0D);
			Fx.sound(level, at, "ENTITY_IRON_GOLEM_HURT", 0.5F, 1.5F);
		}
		double percent = this.health / MAX_HEALTH * 100.0D;
		if (percent <= 75.0D && !this.warned75) {
			this.warned75 = true;
			broadcastWarning(server, 75);
		}
		if (percent <= 50.0D && !this.warned50) {
			this.warned50 = true;
			broadcastWarning(server, 50);
		}
		if (percent <= 25.0D && !this.warned25) {
			this.warned25 = true;
			broadcastWarning(server, 25);
		}
		if (server != null) {
			updateBossBar(server);
		}
		persist();
		if (this.health <= 0.0D) {
			onSignalDestroyed(server, level);
		}
		return true;
	}

	private void broadcastWarning(@Nullable MinecraftServer server, int percent) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.send(player, "<yellow>>> <red># WARNING!! <green>Contagion Signal is at <green><underlined>"
					+ percent + "%</underlined><green>!");
			Fx.soundTo(player, "ENTITY_ELDER_GUARDIAN_CURSE", 0.8F, 1.2F);
		}
	}

	/** {@code ContagionSignalManager#onSignalDestroyed}. */
	private void onSignalDestroyed(@Nullable MinecraftServer server, @Nullable ServerLevel level) {
		if (level != null) {
			WeaponRegistry registry = this.mod.weapons();
			ItemStack signal = registry.create("contagionsignal", null).orElse(ItemStack.EMPTY);
			if (!signal.isEmpty()) {
				level.spawnAtLocation(net.minecraft.core.BlockPos.containing(this.signal), signal);
			}
		}
		if (server != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Messaging.send(player, "<yellow>>> <red># CONTAGION SIGNAL DESTROYED!");
				Fx.soundTo(player, "ENTITY_WITHER_DEATH", 0.8F, 0.5F);
			}
		}
		cleanup();
	}

	// --------------------------------------------------------------- complete

	/** {@code ContagionSignalManager#onRitualComplete}. */
	private void onRitualComplete(MinecraftServer server, ServerLevel level) {
		Vec3 at = this.signal;
		Fx.sound(level, at, "UI_TOAST_CHALLENGE_COMPLETE", 2.0F, 0.8F);
		Fx.sound(level, at, "ENTITY_ENDER_DRAGON_DEATH", 1.5F, 0.5F);
		Fx.sound(level, at, "ENTITY_WARDEN_SONIC_BOOM", 2.0F, 0.3F);
		Fx.simple(level, "END_ROD", at.add(0.0D, 2.0D, 0.0D), 500, 3.0D, 3.0D, 3.0D, 0.5D);
		Fx.colored(level, "FLASH", at.add(0.0D, 2.0D, 0.0D), 0xFFFFFFFF, 3, 0.0D, 0.0D, 0.0D, 0.0D);
		Fx.simple(level, "EXPLOSION_EMITTER", at, 5, 1.0D, 1.0D, 1.0D, 0.0D);

		convertAllPlayers(server, this.role);
		String displayName = roleDisplayName(this.role);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Messaging.title(player, "<dark_red><bold>RITUAL COMPLETE", "<gray>All players are now " + displayName,
					20, 100, 30);
			Messaging.send(player, "<yellow>>> <green># CONTAGION RITUAL COMPLETE!");
			Messaging.send(player, "<yellow>>> <gray>All players have been converted to <yellow><bold>"
					+ displayName.toUpperCase() + "</bold><gray>.");
		}
		cleanup();
	}

	/**
	 * {@code convertAllPlayers}: everyone online is converted now, and the plugin's
	 * pass over its playerdata folder - which wrote {@code role} into offline players'
	 * YML files - becomes a queue in this mod's own store, applied when they join.
	 */
	private void convertAllPlayers(MinecraftServer server, String role) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			convertPlayer(player, role);
		}
		WorldRecord record = this.mod.store().world();
		record.flags().put("pending_conversion_role", role);
		for (com.altarsmp.fabric.data.PlayerRecord stored : this.mod.store().players()) {
			UUID uuid = stored.uuid();
			if (server.getPlayerList().getPlayer(uuid) == null) {
				record.flags().put("pending_conversion_" + uuid, role);
			}
		}
		this.mod.store().markDirty();
	}

	private void applyQueuedConversions(MinecraftServer server) {
		WorldRecord record = this.mod.store().world();
		if (record.flags().isEmpty()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			String key = "pending_conversion_" + player.getUUID();
			String role = record.flags().remove(key);
			if (role != null) {
				convertPlayer(player, role);
				this.mod.store().markDirty();
			}
		}
	}

	/** {@code convertPlayer} - strip every curse tag, then apply the tuned role. */
	private void convertPlayer(ServerPlayer player, String role) {
		FactionManager.makeHuman(player);
		player.removeTag(FactionManager.TAG_PERMA_PALE);
		player.removeTag(FactionManager.TAG_PERMA_HUMAN);
		switch (role) {
			case "pale" -> FactionManager.makePale(player);
			case "vampire" -> FactionManager.makeVampire(player);
			default -> player.addTag(FactionManager.TAG_HUMAN);
		}
	}

	/** {@code ContagionSignalManager#cleanup}. */
	private void cleanup() {
		this.active = false;
		despawnEntities();
		if (this.bar != null) {
			MinecraftServer server = this.mod.server();
			if (server != null) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					Messaging.hideBossBar(this.bar, player);
				}
			}
			this.bar = null;
		}
		this.dimension = null;
		this.owner = null;
		this.role = "human";
		this.health = MAX_HEALTH;
		this.remaining = RITUAL_TICKS;
		this.lastHit.clear();
		persist();
	}

	private void persist() {
		WorldRecord.EventState record = this.mod.store().world().contagion();
		record.active(this.active);
		record.data().put("world", this.dimension == null ? "" : this.dimension.location().toString());
		record.data().put("role", this.role);
		record.data().put("owner", this.owner == null ? "" : this.owner.toString());
		record.numbers().put("health", (long) this.health);
		record.numbers().put("remaining", (long) this.remaining);
		record.numbers().put("x", (long) this.signal.x);
		record.numbers().put("y", (long) this.signal.y);
		record.numbers().put("z", (long) this.signal.z);
		record.numbers().put("warned75", this.warned75 ? 1L : 0L);
		record.numbers().put("warned50", this.warned50 ? 1L : 0L);
		record.numbers().put("warned25", this.warned25 ? 1L : 0L);
		this.mod.store().markDirty();
	}

	@Nullable
	private ServerLevel signalLevel(MinecraftServer server) {
		return this.dimension == null ? null : server.getLevel(this.dimension);
	}

	/** {@code getRoleDisplayName}. */
	public static String roleDisplayName(String role) {
		return switch (role) {
			case "pale" -> "Pale Rot";
			case "vampire" -> "Vampire";
			default -> "Human";
		};
	}

	/** {@code getRoleColoredName}. */
	public static String roleColoredName(String role) {
		return switch (role) {
			case "pale" -> "<gray>Pale Rot";
			case "vampire" -> "<dark_red>Vampire";
			default -> "<green>Human";
		};
	}

	/** Diagnostics for {@code /contagion status}. */
	public String describe() {
		if (!this.active) {
			return "no ritual running";
		}
		return roleDisplayName(this.role) + " ritual, " + this.remaining + "s left, signal at "
				+ (int) this.health + "/" + (int) MAX_HEALTH;
	}
}
