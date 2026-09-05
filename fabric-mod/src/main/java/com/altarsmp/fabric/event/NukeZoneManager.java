package com.altarsmp.fabric.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.TrueDamage;
import com.altarsmp.fabric.data.WorldRecord;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * Nuke Zone - port of {@code a.k} (the world event) and the strike half of
 * {@code com.altarsmp.weapons.NukeLauncherWeapon}.
 *
 * <p><b>The zone event</b> ({@code /nukezone start|stop|force|status}): once
 * activated, every 30 minutes a nuke is dropped 500-1000 blocks away from a random
 * online player at the highest motion-blocking block there. Everyone is told the
 * impact coordinates 60 seconds out, again at 30 and at 10 seconds with their own
 * sounds, and at zero a power-0 explosion plays and a Gaussian-noise dome crater
 * 120 blocks wide and 80 deep is carved out a batch at a time so the server does
 * not stall.
 *
 * <p><b>The weapon strike</b> (Nuke Launcher's Nuclear Strike and the Striker's Nuke
 * Shot): the painted target gets a per-second countdown with two boss bars - one for
 * the launcher, one "GET OUT" bar shown only to other players inside 200 blocks - a
 * red dust ring drawn on the kill radius every second, {@code custom/nuke_incoming}
 * at three seconds, escalating elder-guardian curses, broadcasts at 60/30/15/10/5/4/3/2/1
 * seconds, and then the detonation: {@code custom/nuke_explosion} at volume 10, a
 * sphere crater of {@code crater_radius} carved {@code layers_per_tick} layers per
 * tick (only while {@code abilities.terrain_destruction} allows it), five expanding
 * explosion shockwave rings, everything inside {@code kill_radius} killed outright,
 * everything inside {@code damage_radius} taking {@code outer_damage} true damage,
 * and everyone within {@code flash_range} getting a carved pumpkin pushed over their
 * view for {@code flash_duration} ticks before their real helmet comes back.
 *
 * <p>{@code abort} has no upstream counterpart - it is the port's Shift+F cancel for
 * a strike that is still counting down, and it says so in the weapon class.
 *
 * <p>The zone flag and its world are persisted; an in-flight countdown is not, which
 * is what the plugin did too (a 30-second countdown cannot survive a restart in any
 * meaningful way).
 */
public final class NukeZoneManager {

	/** {@code a.k}'s 30-minute interval, crater radius and depth. */
	private static final long ZONE_INTERVAL_TICKS = 36000L;
	private static final int ZONE_CRATER_RADIUS = 120;
	private static final int ZONE_CRATER_DEPTH = 80;
	/** The zone nuke's warning schedule, in ticks before impact. */
	private static final int ZONE_COUNTDOWN_TICKS = 1200;
	private static final int ZONE_WARN_30 = 600;
	private static final int ZONE_WARN_10 = 200;
	private static final int ZONE_IMPACT = 0;
	/** {@code NukeLauncherWeapon#WARNING_TIMES}. */
	private static final int[] WARNING_TIMES = {60, 30, 15, 10, 5, 4, 3, 2, 1};
	/** Squared distance the "GET OUT" bar is shown within. */
	private static final double GET_OUT_DIST_SQ = 40000.0D;
	/** {@code playCustomSound}'s 300-block listener radius, squared. */
	private static final double CUSTOM_SOUND_DIST_SQ = 90000.0D;
	private static final long SECONDS_PER_TICK = 20L;
	/** {@code spawnShockwaveRings}'s five rings, four ticks apart, 40 frames each. */
	private static final int RING_COUNT = 5;
	private static final long RING_STAGGER_TICKS = 4L;
	private static final int RING_FRAMES = 40;
	private static final double RING_START_RADIUS = 3.0D;
	private static final double RING_GROWTH = 6.0D;

	private final AltarSMPMod mod;
	private final RandomSource random = RandomSource.create();
	private final Map<UUID, Strike> strikes = new LinkedHashMap<>();
	private final List<ZoneNuke> zoneNukes = new ArrayList<>();
	private final List<Carve> carves = new ArrayList<>();
	private final List<Flash> flashes = new ArrayList<>();
	private boolean zoneActive;
	@Nullable
	private ResourceKey<Level> zoneWorld;
	private long zoneTicks;

	public NukeZoneManager(AltarSMPMod mod) {
		this.mod = mod;
	}

	/** Nothing to subscribe to: everything here runs off {@link #tick} and the commands. */
	public void registerEventHooks() {
		// Intentionally empty - see #tick.
	}

	public void loadFromStore() {
		WorldRecord.EventState record = this.mod.store().world().nukeZone();
		this.zoneActive = record.active();
		String world = record.data().get("world");
		this.zoneWorld = world == null || world.isBlank() ? null
				: ResourceKey.create(Registries.DIMENSION, Identifier.parse(world));
		if (this.zoneActive) {
			AltarSMPMod.LOGGER.info("[AltarSMP] resuming nuke zone in {} ({} strike(s) recorded)", this.zoneWorld,
					record.numbers().getOrDefault("strikes", 0L));
		}
	}

	/** Called on shutdown: in-flight carves stop, the zone flag stays for the next boot. */
	public void stopForShutdown() {
		this.carves.clear();
		this.zoneNukes.clear();
		for (Strike strike : this.strikes.values()) {
			hideBars(strike);
		}
		this.strikes.clear();
		persist();
	}

	public void tick(MinecraftServer server) {
		this.zoneTicks++;
		if (this.zoneActive && this.zoneTicks % ZONE_INTERVAL_TICKS == 0L) {
			scheduleZoneNuke(server);
		}
		tickZoneNukes(server);
		if (this.zoneTicks % SECONDS_PER_TICK == 0L) {
			tickStrikes(server);
		}
		tickCarves();
		tickFlashes();
	}

	// ------------------------------------------------------------ zone event

	/** {@code a.k#a(World)} - {@code /nukezone start}. */
	public String start(ServerPlayer initiator) {
		if (this.zoneActive) {
			return "<red>Nuke zone is already active!";
		}
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return "<red>The server is not ready.";
		}
		this.zoneActive = true;
		this.zoneWorld = initiator.serverLevel().dimension();
		this.zoneTicks = 0L;
		Messaging.broadcast(server, "<red><bold>===============================");
		Messaging.broadcast(server, "<dark_red><bold>NUKE ZONE ACTIVATED!");
		Messaging.broadcast(server, "<gray>Random nukes will strike every 30 minutes!");
		Messaging.broadcast(server, "<gray>You will get a 60-second warning.");
		Messaging.broadcast(server, "<red><bold>===============================");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Fx.soundTo(player, "ENTITY_WITHER_SPAWN", 1.0F, 0.5F);
		}
		persist();
		return "<green>Nuke zone started!";
	}

	/** {@code a.k#a()} - {@code /nukezone stop}. */
	public String stop() {
		if (!this.zoneActive) {
			return "<red>Nuke zone is not active.";
		}
		this.zoneActive = false;
		this.zoneNukes.clear();
		MinecraftServer server = this.mod.server();
		if (server != null) {
			Messaging.broadcast(server, "<red><bold>===============================");
			Messaging.broadcast(server, "<green><bold>NUKE ZONE DEACTIVATED!");
			Messaging.broadcast(server, "<red><bold>===============================");
		}
		persist();
		return "<red>Nuke zone stopped!";
	}

	/** {@code a.k#b(World)} - {@code /nukezone force}. */
	public String force(ServerPlayer initiator) {
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return "<red>The server is not ready.";
		}
		scheduleZoneNuke(server, initiator.serverLevel());
		return "<yellow>Forced a nuke drop!";
	}

	/** {@code /nukezone status}. */
	public String status() {
		return this.zoneActive
				? "<yellow>Nuke zone is active! <gray>Next random strike in "
						+ (ZONE_INTERVAL_TICKS - this.zoneTicks % ZONE_INTERVAL_TICKS) / SECONDS_PER_TICK + "s"
				: "<gray>Nuke zone is not active.";
	}

	/** {@code a.k#b()} - whether the world event is running. */
	public boolean isActive() {
		return this.zoneActive;
	}

	/** {@code a.k#c(World)} - pick a spot near a random player and start the countdown. */
	private void scheduleZoneNuke(MinecraftServer server) {
		ServerLevel level = zoneLevel(server);
		if (level == null) {
			return;
		}
		scheduleZoneNuke(server, level);
	}

	private void scheduleZoneNuke(MinecraftServer server, ServerLevel level) {
		List<ServerPlayer> online = server.getPlayerList().getPlayers();
		if (online.isEmpty()) {
			return;
		}
		ServerPlayer anchor = online.get(this.random.nextInt(online.size()));
		Vec3 at = anchor.position();
		double distance = 500.0D + this.random.nextDouble() * 500.0D;
		double angle = this.random.nextDouble() * Math.PI * 2.0D;
		double x = at.x + distance * Math.cos(angle);
		double z = at.z + distance * Math.sin(angle);
		BlockPos column = BlockPos.containing(x, at.y, z);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
		Vec3 target = new Vec3(x, y, z);

		Messaging.broadcast(server, "<red><bold>NUKE INCOMING! <gray>Impact in 60 seconds at <yellow>"
				+ BlockPos.containing(target).getX() + ", " + BlockPos.containing(target).getZ() + "<gray>!");
		for (ServerPlayer player : online) {
			Fx.soundTo(player, "ENTITY_WITHER_SPAWN", 1.0F, 1.5F);
		}
		this.zoneNukes.add(new ZoneNuke(level.dimension(), target, ZONE_COUNTDOWN_TICKS));
	}

	private void tickZoneNukes(MinecraftServer server) {
		if (this.zoneNukes.isEmpty()) {
			return;
		}
		Iterator<ZoneNuke> iterator = this.zoneNukes.iterator();
		while (iterator.hasNext()) {
			ZoneNuke nuke = iterator.next();
			ServerLevel level = server.getLevel(nuke.dimension);
			if (level == null) {
				iterator.remove();
				continue;
			}
			nuke.remaining--;
			BlockPos at = BlockPos.containing(nuke.target);
			if (nuke.remaining == ZONE_WARN_30) {
				Messaging.broadcast(server, "<red><bold>NUKE! <gray>30 seconds until impact at <yellow>" + at.getX()
						+ ", " + at.getZ() + "<gray>!");
				playToNearby(level, nuke.target, "BLOCK_NOTE_BLOCK_PLING", 1.0F, 0.5F);
			} else if (nuke.remaining == ZONE_WARN_10) {
				Messaging.broadcast(server, "<dark_red><bold>NUKE! <gray>10 seconds! <red>GET AWAY FROM <yellow>"
						+ at.getX() + ", " + at.getZ() + "<gray>!");
				playToNearby(level, nuke.target, "ENTITY_ELDER_GUARDIAN_CURSE", 1.0F, 1.0F);
			} else if (nuke.remaining <= ZONE_IMPACT) {
				Messaging.broadcast(server, "<dark_red><bold>DETONATION!");
				// Power 0, no fire, no block damage: the crater below is the damage.
				level.explode(null, nuke.target.x, nuke.target.y, nuke.target.z, 0.0F,
						Level.ExplosionInteraction.NONE);
				playToNearby(level, nuke.target, "ENTITY_GENERIC_EXPLODE", 1.0F, 0.3F);
				this.carves.add(new BatchCarve(level, nuke.target, ZONE_CRATER_RADIUS, ZONE_CRATER_DEPTH, this.random));
				iterator.remove();
			}
		}
	}

	@Nullable
	private ServerLevel zoneLevel(MinecraftServer server) {
		return this.zoneWorld == null ? null : server.getLevel(this.zoneWorld);
	}

	// --------------------------------------------------------- weapon strikes

	/**
	 * {@code NukeLauncherWeapon#useNuclearStrike}'s countdown, shared by the Nuke
	 * Launcher and the Striker's Nuke Shot.
	 *
	 * @param owner    the player who painted the target
	 * @param target   the impact point
	 * @param weaponId {@code nukelauncher} or {@code striker}
	 * @return {@code false} when the owner already has a strike in the air or another
	 *         strike is already landing inside its own crater radius of this point
	 */
	public boolean requestStrike(ServerPlayer owner, Vec3 target, String weaponId) {
		if (this.strikes.containsKey(owner.getUUID())) {
			return false;
		}
		int craterRadius = this.mod.config().getInt("abilities.nukelauncher.crater_radius", 100);
		for (Strike existing : this.strikes.values()) {
			if (existing.dimension.equals(owner.serverLevel().dimension())
					&& existing.target.distanceToSqr(target) < (double) craterRadius * craterRadius) {
				return false;
			}
		}
		ServerLevel level = owner.serverLevel();
		int countdown = this.mod.config().getInt("abilities.nukelauncher.countdown", 30);
		Strike strike = new Strike(owner.getUUID(), owner.getGameProfile().getName(), weaponId, level.dimension(),
				target, countdown);
		strike.ownBar = Messaging.bossBar("<dark_red>☢ NUKE IMPACT</dark_red> <gray>in</gray> <red>" + countdown
				+ "s</red>", BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
		strike.warningBar = Messaging.bossBar("<dark_red>☢ NUKE INCOMING <gray>- <red>GET OUT",
				BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
		Messaging.showBossBar(strike.ownBar, owner);
		this.strikes.put(owner.getUUID(), strike);

		MinecraftServer server = this.mod.server();
		BlockPos at = BlockPos.containing(target);
		if (server != null) {
			Messaging.broadcast(server, "<dark_red><bold>☢ NUCLEAR STRIKE LAUNCHED</bold></dark_red> <gray>by</gray> <red>"
					+ strike.ownerName + "</red> <gray>→ targeting</gray> <yellow>" + at.getX() + ", " + at.getZ());
		}
		WorldRecord.EventState record = this.mod.store().world().nukeZone();
		record.numbers().merge("strikes", 1L, Long::sum);
		record.owner(strike.ownerName);
		record.data().put("last_weapon", weaponId);
		this.mod.store().markDirty();
		return true;
	}

	/** The port's Shift+F cancel: only a strike that is still counting down. */
	public boolean abort(ServerPlayer owner) {
		Strike strike = this.strikes.remove(owner.getUUID());
		if (strike == null) {
			return false;
		}
		hideBars(strike);
		MinecraftServer server = this.mod.server();
		if (server != null) {
			BlockPos at = BlockPos.containing(strike.target);
			Messaging.broadcast(server, "<yellow>☢ <gray>Nuclear strike by <red>" + strike.ownerName
					+ "</red> <gray>was aborted @ <yellow>" + at.getX() + ", " + at.getZ());
		}
		return true;
	}

	/** A launcher who logs out leaves nobody to abort the strike - the port cancels it. */
	public void onOwnerQuit(ServerPlayer owner) {
		Strike strike = this.strikes.remove(owner.getUUID());
		if (strike != null) {
			hideBars(strike);
		}
	}

	private void tickStrikes(MinecraftServer server) {
		if (this.strikes.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Strike>> iterator = this.strikes.entrySet().iterator();
		while (iterator.hasNext()) {
			Strike strike = iterator.next().getValue();
			ServerLevel level = server.getLevel(strike.dimension);
			ServerPlayer owner = server.getPlayerList().getPlayer(strike.owner);
			if (level == null) {
				iterator.remove();
				continue;
			}
			if (strike.remaining <= 0) {
				iterator.remove();
				hideBars(strike);
				detonate(server, level, owner, strike);
				continue;
			}

			int total = this.mod.config().getInt("abilities.nukelauncher.countdown", 30);
			float progress = (float) strike.remaining / (float) Math.max(1, total);
			if (strike.ownBar != null) {
				strike.ownBar.setProgress(Math.max(0.0F, Math.min(1.0F, progress)));
				strike.ownBar.setName(Messaging.msg("<dark_red>☢ NUKE IMPACT</dark_red> <gray>in</gray> <red>"
						+ strike.remaining + "s</red>"));
			}
			if (strike.warningBar != null) {
				strike.warningBar.setProgress(Math.max(0.0F, Math.min(1.0F, progress)));
				strike.warningBar.setName(Messaging.msg("<dark_red>☢ NUKE INCOMING <gray>- <red>GET OUT <gray>(<red>"
						+ strike.remaining + "s</red>)"));
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					if (player.equals(owner)) {
						continue;
					}
					boolean near = player.serverLevel().dimension().equals(strike.dimension)
							&& player.position().distanceToSqr(strike.target) <= GET_OUT_DIST_SQ;
					if (near) {
						Messaging.showBossBar(strike.warningBar, player);
					} else {
						Messaging.hideBossBar(strike.warningBar, player);
					}
				}
			}

			int killRadius = this.mod.config().getInt("abilities.nukelauncher.kill_radius", 50);
			drawKillRing(level, strike.target, killRadius);

			if (strike.remaining == 3) {
				playToNearby(level, strike.target, "custom/nuke_incoming", 3.0F, 1.0F);
			}
			if (strike.remaining <= 45 && strike.remaining % 5 == 0) {
				float pitch = 1.0F - 0.5F * (1.0F - strike.remaining / 45.0F);
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					Fx.soundTo(player, "ENTITY_ELDER_GUARDIAN_CURSE", 1.0F, Math.max(0.5F, pitch));
				}
			}
			if (strike.remaining <= 10 && strike.remaining % 2 == 0) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					Fx.soundTo(player, "ENTITY_ELDER_GUARDIAN_CURSE", 1.5F, 0.5F);
				}
			}
			for (int warning : WARNING_TIMES) {
				if (strike.remaining == warning) {
					String colour = strike.remaining <= 5 ? "<red>"
							: strike.remaining <= 15 ? "<gold>" : "<yellow>";
					BlockPos at = BlockPos.containing(strike.target);
					Messaging.broadcast(server, "<dark_red><bold>☢ NUKE</bold></dark_red> <gray>impact in</gray> "
							+ colour + "<bold>" + strike.remaining + "s</bold> <dark_gray>@ <yellow>" + at.getX()
							+ ", " + at.getZ());
					break;
				}
			}
			strike.remaining--;
		}
	}

	/** The red dust circle on the kill radius, redrawn every second of the countdown. */
	private void drawKillRing(ServerLevel level, Vec3 target, int killRadius) {
		int points = Math.max(60, killRadius * 4);
		for (int i = 0; i < points; i++) {
			double angle = Math.PI * 2.0D / points * i;
			Vec3 at = new Vec3(target.x + killRadius * Math.cos(angle), target.y + 1.0D,
					target.z + killRadius * Math.sin(angle));
			Fx.dust(level, at, 0xFF0000, 2.0F, 1, 0.0D, 0.0D, 0.0D);
		}
	}

	/** {@code NukeLauncherWeapon#detonate} - the blast one second after the countdown. */
	private void detonate(MinecraftServer server, ServerLevel level, @Nullable ServerPlayer owner, Strike strike) {
		int flashRange = this.mod.config().getInt("abilities.nukelauncher.flash_range", 200);
		flashNearbyPlayers(level, strike.target, flashRange);
		this.mod.scheduler().later(() -> {
			playToNearby(level, strike.target, "custom/nuke_explosion", 10.0F, 1.0F);
			carveSphereCrater(level, strike.target);
			spawnShockwaveRings(level, strike.target);
			damageEntities(level, owner, strike.target);
			BlockPos at = BlockPos.containing(strike.target);
			Messaging.broadcast(server, "<dark_red><bold>☢ NUCLEAR IMPACT</bold></dark_red> <gray>at</gray> <yellow>"
					+ at.getX() + ", " + at.getZ());
		}, SECONDS_PER_TICK);
	}

	/** {@code NukeLauncherWeapon#damageEntities}. */
	private void damageEntities(ServerLevel level, @Nullable ServerPlayer owner, Vec3 target) {
		int killRadius = this.mod.config().getInt("abilities.nukelauncher.kill_radius", 50);
		int damageRadius = this.mod.config().getInt("abilities.nukelauncher.damage_radius", 100);
		double outerDamage = this.mod.config().getDouble("abilities.nukelauncher.outer_damage", 10.0D);
		double killSq = (double) killRadius * killRadius;
		double damageSq = (double) damageRadius * damageRadius;
		AABB box = new AABB(target.x - damageRadius, target.y - damageRadius, target.z - damageRadius,
				target.x + damageRadius, target.y + damageRadius, target.z + damageRadius);
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
			double distanceSq = entity.position().distanceToSqr(target);
			if (distanceSq <= killSq) {
				// Upstream called setHealth(0), which bypasses armour, shields and totems.
				entity.setHealth(0.0F);
			} else if (distanceSq <= damageSq) {
				TrueDamage.apply(entity, outerDamage, owner, true);
			}
		}
	}

	/** {@code NukeLauncherWeapon#carveSphereCrater}. */
	private void carveSphereCrater(ServerLevel level, Vec3 target) {
		if (!this.mod.config().getBoolean("abilities.terrain_destruction", true)) {
			return;
		}
		int radius = this.mod.config().getInt("abilities.nukelauncher.crater_radius", 100);
		int layersPerTick = this.mod.config().getInt("abilities.nukelauncher.layers_per_tick", 15);
		this.carves.add(new SphereCarve(level, BlockPos.containing(target), radius, layersPerTick));
	}

	/** {@code NukeLauncherWeapon#spawnShockwaveRings} - five rings, four ticks apart. */
	private void spawnShockwaveRings(ServerLevel level, Vec3 target) {
		for (int ring = 0; ring < RING_COUNT; ring++) {
			long start = ring * RING_STAGGER_TICKS;
			for (int frame = 0; frame < RING_FRAMES; frame++) {
				final int step = frame;
				this.mod.scheduler().later(() -> {
					double radius = RING_START_RADIUS + RING_GROWTH * (step + 1);
					spawnRing(level, target.add(0.0D, 1.5D + step * 0.3D, 0.0D), radius);
				}, start + step);
			}
		}
	}

	/** {@code NukeLauncherWeapon#spawnRing}. */
	private void spawnRing(ServerLevel level, Vec3 center, double radius) {
		int points = Math.max(20, (int) (radius * 2.0D));
		for (int i = 0; i < points; i++) {
			double angle = Math.PI * 2.0D / points * i;
			Vec3 at = new Vec3(center.x + radius * Math.cos(angle), center.y, center.z + radius * Math.sin(angle));
			Fx.simple(level, "EXPLOSION", at, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	/**
	 * {@code NukeLauncherWeapon#flashNearbyPlayers} - the carved-pumpkin screen
	 * overlay, restored after {@code flash_duration} ticks.
	 */
	private void flashNearbyPlayers(ServerLevel level, Vec3 target, int flashRange) {
		double rangeSq = (double) flashRange * flashRange;
		int duration = this.mod.config().getInt("abilities.nukelauncher.flash_duration", 30);
		for (ServerPlayer player : level.players()) {
			if (player.position().distanceToSqr(target) > rangeSq) {
				continue;
			}
			sendHead(player, new ItemStack(Items.CARVED_PUMPKIN));
			ItemStack real = player.getItemBySlot(EquipmentSlot.HEAD).copy();
			this.flashes.add(new Flash(player.getUUID(), duration, real));
		}
	}

	private void tickFlashes() {
		if (this.flashes.isEmpty()) {
			return;
		}
		MinecraftServer server = this.mod.server();
		Iterator<Flash> iterator = this.flashes.iterator();
		while (iterator.hasNext()) {
			Flash flash = iterator.next();
			if (--flash.remaining > 0) {
				continue;
			}
			iterator.remove();
			ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(flash.player);
			if (player != null) {
				sendHead(player, flash.helmet);
			}
		}
	}

	private static void sendHead(ServerPlayer player, ItemStack helmet) {
		player.connection.send(new ClientboundSetEquipmentPacket(player.getId(),
				List.of(Pair.of(EquipmentSlot.HEAD, helmet))));
	}

	/** {@code NukeLauncherWeapon#playCustomSound} - only listeners inside 300 blocks. */
	private void playToNearby(ServerLevel level, Vec3 target, String sound, float volume, float pitch) {
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		Identifier id = com.altarsmp.fabric.util.GameRegistry.soundId(sound);
		if (id == null) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] nuke sound '{}' is not in this Minecraft version", sound);
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.serverLevel().dimension().equals(level.dimension())
					|| player.position().distanceToSqr(target) > CUSTOM_SOUND_DIST_SQ) {
				continue;
			}
			Fx.sound(level, target, id, volume, pitch);
		}
	}

	private void hideBars(Strike strike) {
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (strike.ownBar != null) {
				Messaging.hideBossBar(strike.ownBar, player);
			}
			if (strike.warningBar != null) {
				Messaging.hideBossBar(strike.warningBar, player);
			}
		}
	}

	private void persist() {
		WorldRecord.EventState record = this.mod.store().world().nukeZone();
		record.active(this.zoneActive);
		record.data().put("world", this.zoneWorld == null ? "" : this.zoneWorld.location().toString());
		this.mod.store().markDirty();
	}

	// ---------------------------------------------------------------- carving

	/** One carve job; {@code tick()} returns {@code true} when it has finished. */
	private interface Carve {
		boolean tick();
	}

	/** {@code carveSphereCrater}'s layer-by-layer sphere, top down. */
	private static final class SphereCarve implements Carve {
		private final ServerLevel level;
		private final BlockPos center;
		private final int radius;
		private final List<Integer> layers = new ArrayList<>();
		private final int layersPerTick;
		private int index;

		SphereCarve(ServerLevel level, BlockPos center, int radius, int layersPerTick) {
			this.level = level;
			this.center = center;
			this.radius = radius;
			this.layersPerTick = Math.max(1, layersPerTick);
			int top = Math.min(level.getMaxY(), center.getY() + radius);
			int bottom = Math.max(level.getMinY(), center.getY() - radius);
			for (int y = top; y >= bottom; y--) {
				this.layers.add(y);
			}
		}

		@Override
		public boolean tick() {
			int end = Math.min(this.index + this.layersPerTick, this.layers.size());
			for (int i = this.index; i < end; i++) {
				int y = this.layers.get(i);
				int dy = y - this.center.getY();
				int radiusSq = this.radius * this.radius - dy * dy;
				if (radiusSq < 0) {
					continue;
				}
				int extent = (int) Math.ceil(Math.sqrt(radiusSq));
				for (int dx = -extent; dx <= extent; dx++) {
					for (int dz = -extent; dz <= extent; dz++) {
						if (dx * dx + dz * dz <= radiusSq) {
							clear(this.level, this.center.offset(dx, dy, dz));
						}
					}
				}
			}
			this.index = end;
			return this.index >= this.layers.size();
		}
	}

	/** {@code a.k#a(Location, World, 120, 80)} - the Gaussian-noise dome crater. */
	private static final class BatchCarve implements Carve {
		private final ServerLevel level;
		private final List<BlockPos> blocks;
		private final int batch;
		private int index;

		BatchCarve(ServerLevel level, Vec3 center, int radius, int depth, RandomSource random) {
			this.level = level;
			this.blocks = new ArrayList<>();
			int originX = BlockPos.containing(center).getX();
			int originY = BlockPos.containing(center).getY();
			int originZ = BlockPos.containing(center).getZ();
			int size = radius * 2 + 1;
			int[][] noise = new int[size][size];
			for (int x = 0; x < size; x++) {
				for (int z = 0; z < size; z++) {
					noise[x][z] = (int) (random.nextGaussian() * 3.0D);
				}
			}
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
					double noisyRadius = radius + noise[dx + radius][dz + radius];
					if (distance > noisyRadius) {
						continue;
					}
					double ratio = distance / noisyRadius;
					int columnDepth = (int) (depth * Math.sqrt(Math.max(0.0D, 1.0D - ratio * ratio)));
					for (int dy = 0; dy >= -columnDepth; dy--) {
						BlockPos pos = new BlockPos(originX + dx, originY + dy, originZ + dz);
						BlockState state = level.getBlockState(pos);
						if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
							this.blocks.add(pos);
						}
					}
				}
			}
			this.batch = Math.max(1, this.blocks.size() / 30);
		}

		@Override
		public boolean tick() {
			int end = Math.min(this.index + this.batch, this.blocks.size());
			for (int i = this.index; i < end; i++) {
				clear(this.level, this.blocks.get(i));
			}
			this.index = end;
			return this.index >= this.blocks.size();
		}
	}

	private static void clear(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.is(Blocks.BEDROCK)) {
			return;
		}
		// Bukkit's setType(AIR, false): clients see it, neighbours are not updated, so
		// no physics cascade and no drops while a crater is being dug.
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
	}

	private void tickCarves() {
		if (this.carves.isEmpty()) {
			return;
		}
		this.carves.removeIf(Carve::tick);
	}

	// ------------------------------------------------------------------ state

	private static final class Strike {
		final UUID owner;
		final String ownerName;
		final String weaponId;
		final ResourceKey<Level> dimension;
		final Vec3 target;
		int remaining;
		@Nullable
		ServerBossEvent ownBar;
		@Nullable
		ServerBossEvent warningBar;

		Strike(UUID owner, String ownerName, String weaponId, ResourceKey<Level> dimension, Vec3 target,
				int remaining) {
			this.owner = owner;
			this.ownerName = ownerName;
			this.weaponId = weaponId;
			this.dimension = dimension;
			this.target = target;
			this.remaining = remaining;
		}
	}

	private static final class ZoneNuke {
		final ResourceKey<Level> dimension;
		final Vec3 target;
		int remaining;

		ZoneNuke(ResourceKey<Level> dimension, Vec3 target, int remaining) {
			this.dimension = dimension;
			this.target = target;
			this.remaining = remaining;
		}
	}

	private static final class Flash {
		final UUID player;
		int remaining;
		final ItemStack helmet;

		Flash(UUID player, int remaining, ItemStack helmet) {
			this.player = player;
			this.remaining = remaining;
			this.helmet = helmet;
		}
	}
}
