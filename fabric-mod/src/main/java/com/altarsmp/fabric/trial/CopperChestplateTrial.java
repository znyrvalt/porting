package com.altarsmp.fabric.trial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.ability.Motion;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * Copper Chestplate trial - the Chestplate Shard event, a port of
 * {@code com.altarsmp.trials.CopperChestplateTrial}.
 *
 * <p>An admin (or {@code /coppertrial chestplate}) builds the arena at their own
 * position, lifted to Y 200 if they are lower: a five-layer cut-copper island that
 * tapers as it drops, every layer edged with upside-down cut-copper stairs facing
 * outwards, and a lightning rod on top. On the rod sits a glowing Chestplate Shard
 * item, held in place by an invisible marker armour stand wearing the same shard and
 * named in gold.
 *
 * <p>The island defends itself. Every half second, anyone within 30 blocks who has
 * not been struck in the last three seconds is hit by a cosmetic lightning bolt for
 * 20 damage and thrown straight up at 2.5 with a scatter of electric sparks. Elytra
 * flight is stopped and riptide is knocked down within 60 blocks, each with a bass
 * note, so the only way up is to fight the storm.
 *
 * <p>Picking the shard up removes the stand, calms that island and broadcasts the
 * retrieval; anyone carrying a shard glows so the whole server can hunt them.
 *
 * <p>The port drives the glide and riptide rules from the tick instead of events
 * (26.x exposes no toggle-glide or riptide callback), which produces the same
 * outcome one tick later at worst. {@code onPlayerMove} in the original had an empty
 * loop body - a decompiler artefact with no behaviour - so nothing is lost by not
 * porting it.
 */
public final class CopperChestplateTrial {

	/** The plugin's constants: strike radius, no-fly radius, cooldown, launch, floor. */
	private static final double LIGHTNING_RADIUS = 30.0D;
	private static final double FLIGHT_RADIUS = 60.0D;
	private static final long LIGHTNING_COOLDOWN_MILLIS = 3000L;
	private static final double LAUNCH_Y = 2.5D;
	private static final double LIGHTNING_DAMAGE = 20.0D;
	private static final double ISLAND_MIN_Y = 200.0D;
	private static final long STRIKE_PERIOD_TICKS = 10L;
	private static final long GLOW_PERIOD_TICKS = 20L;
	private static final long FLIGHT_SOUND_COOLDOWN_TICKS = 20L;
	private static final String SHARD_ID = "chestplateshard";
	/** {@code buildCopperIsland}'s five layers: platform size and Y offset. */
	private static final int[][] LAYERS = {{5, 0}, {4, -1}, {3, -2}, {2, -3}, {1, -4}};

	private final AltarSMPMod mod;
	private final RandomSource random = RandomSource.create();
	private final List<Island> islands = new ArrayList<>();
	private final Map<UUID, Long> strikeTimes = new HashMap<>();
	private final Map<UUID, Long> flightSounds = new HashMap<>();
	private long tickCount;

	public CopperChestplateTrial(AltarSMPMod mod) {
		this.mod = mod;
	}

	/** {@code CopperChestplateTrial#onCommand} - builds the island where the admin stands. */
	public void start(ServerPlayer player) {
		Vec3 at = player.position();
		Vec3 center = new Vec3(at.x, Math.max(at.y, ISLAND_MIN_Y), at.z);
		ServerLevel level = player.serverLevel();
		spawnTrialIsland(level, center);

		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		BlockPos block = BlockPos.containing(center);
		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			Messaging.send(online, "<gold><bold>The Chestplate Shard Event has begun!");
			Messaging.send(online, "<yellow>Retrieve the <gold>Chestplate Shards<yellow> from the top of the world!");
			Messaging.send(online, "<yellow>Location: <red>X: " + block.getX() + " Y: " + block.getY() + " Z: "
					+ block.getZ());
			Messaging.send(online, "<gray>Holding a shard will make you glow and be visible to all players!");
			Fx.soundTo(online, "ENTITY_LIGHTNING_BOLT_THUNDER", 1.0F, 0.8F);
		}
	}

	/** {@code CopperChestplateTrial#spawnTrialIsland}. */
	public void spawnTrialIsland(ServerLevel level, Vec3 center) {
		buildCopperIsland(level, center);
		Vec3 pedestal = center.add(0.5D, 1.5D, 0.5D);

		ArmorStand stand = new ArmorStand(level, pedestal.x, pedestal.y, pedestal.z);
		stand.setInvisible(true);
		stand.setNoGravity(true);
		stand.setInvulnerable(true);
		stand.setSmall(true);
		stand.setMarker(true);
		stand.setItemSlot(EquipmentSlot.HEAD, createShard());
		stand.setCustomName(Messaging.msg("<gold>Chestplate Shard"));
		stand.setCustomNameVisible(true);
		level.addFreshEntity(stand);

		ItemEntity shard = new ItemEntity(level, pedestal.x, pedestal.y, pedestal.z, createShard());
		shard.setGlowingTag(true);
		shard.setCustomName(Messaging.msg("<gold>Chestplate Shard"));
		shard.setCustomNameVisible(true);
		shard.setPickUpDelay(0);
		shard.setExtendedLifetime();
		level.addFreshEntity(shard);

		this.islands.add(new Island(level.dimension(), center, stand, shard));
	}

	/** {@code CopperChestplateTrial#buildCopperIsland}. */
	private void buildCopperIsland(ServerLevel level, Vec3 center) {
		BlockPos origin = BlockPos.containing(center);
		for (int[] layer : LAYERS) {
			int size = layer[0];
			int offsetY = layer[1];
			int half = size / 2;
			for (int dx = -half; dx <= half; dx++) {
				for (int dz = -half; dz <= half; dz++) {
					BlockPos pos = origin.offset(dx, offsetY, dz);
					if (Math.abs(dx) != half && Math.abs(dz) != half) {
						level.setBlock(pos, Blocks.CUT_COPPER.defaultBlockState(), 3);
						continue;
					}
					BlockState stairs = Blocks.CUT_COPPER_STAIRS.defaultBlockState()
							.setValue(StairBlock.HALF, Half.TOP);
					if (dx == half) {
						stairs = stairs.setValue(StairBlock.FACING, net.minecraft.core.Direction.EAST);
					} else if (dx == -half) {
						stairs = stairs.setValue(StairBlock.FACING, net.minecraft.core.Direction.WEST);
					} else if (dz == half) {
						stairs = stairs.setValue(StairBlock.FACING, net.minecraft.core.Direction.SOUTH);
					} else {
						stairs = stairs.setValue(StairBlock.FACING, net.minecraft.core.Direction.NORTH);
					}
					level.setBlock(pos, stairs, 3);
				}
			}
		}
		level.setBlock(origin.above(), Blocks.LIGHTNING_ROD.defaultBlockState(), 3);
	}

	/** {@code CopperChestplateFragment#createShard}. */
	public static ItemStack createShard() {
		ItemStack stack = new ItemStack(Items.COPPER_BLOCK);
		stack.set(DataComponents.CUSTOM_NAME, Messaging.msg("<gold>Chestplate Shard").copy()
				.withStyle(style -> style.withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Messaging.msg("<gray>A powerful shard imbued"),
				Messaging.msg("<gray>with the essence of thunder."),
				Messaging.msg(""),
				Messaging.msg("<yellow>Obtained from the Chestplate Shard Event"),
				Messaging.msg("<red>Holding this makes you glow!"))));
		Identity.putLegacy(stack, Identity.KEY_ITEM, SHARD_ID);
		return stack;
	}

	/** {@code CopperChestplateFragment#isShard}. */
	public static boolean isShard(@Nullable ItemStack stack) {
		return stack != null && stack.is(Items.COPPER_BLOCK)
				&& SHARD_ID.equals(Identity.legacyString(stack, Identity.KEY_ITEM, ""));
	}

	/** The plugin's trial task and glow task, on their original periods. */
	public void tick(MinecraftServer server) {
		this.tickCount++;
		if (this.tickCount % STRIKE_PERIOD_TICKS == 0L) {
			strikePass(server);
		}
		enforceNoFlight(server);
		if (this.tickCount % GLOW_PERIOD_TICKS == 0L) {
			glowPass(server);
		}
	}

	/** {@code startTrialTask}'s body: lightning near live shards, cleanup of dead ones. */
	private void strikePass(MinecraftServer server) {
		Iterator<Island> iterator = this.islands.iterator();
		while (iterator.hasNext()) {
			Island island = iterator.next();
			ServerLevel level = server.getLevel(island.dimension);
			if (level == null) {
				continue;
			}
			if (island.shard.isRemoved() || !island.shard.isAlive()) {
				island.stand.discard();
				iterator.remove();
				continue;
			}
			long now = System.currentTimeMillis();
			for (ServerPlayer player : level.players()) {
				if (player.position().distanceTo(island.center) > LIGHTNING_RADIUS) {
					continue;
				}
				Long last = this.strikeTimes.get(player.getUUID());
				if (last != null && now - last < LIGHTNING_COOLDOWN_MILLIS) {
					continue;
				}
				this.strikeTimes.put(player.getUUID(), now);
				strikeLightning(player);
			}
		}
	}

	/** {@code CopperChestplateTrial#strikeLightning}. */
	private void strikeLightning(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 at = player.position();
		Fx.lightning(level, at, true);
		// Bukkit's player.damage(20.0): generic damage, so armour and totems still apply.
		player.hurtServer(level, level.damageSources().generic(), (float) LIGHTNING_DAMAGE);
		Motion.setVelocity(player, new Vec3((this.random.nextDouble() - 0.5D) * 0.3D, LAUNCH_Y,
				(this.random.nextDouble() - 0.5D) * 0.3D));
		Fx.simple(level, "ELECTRIC_SPARK", at.add(0.0D, 1.0D, 0.0D), 30, 0.5D, 0.5D, 0.5D, 0.1D);
	}

	/** The plugin's glide-cancel and riptide-cancel listeners, driven per tick. */
	private void enforceNoFlight(MinecraftServer server) {
		if (this.islands.isEmpty()) {
			return;
		}
		long tick = this.mod.scheduler().currentTick();
		for (Island island : this.islands) {
			ServerLevel level = server.getLevel(island.dimension);
			if (level == null) {
				continue;
			}
			for (ServerPlayer player : level.players()) {
				if (player.position().distanceTo(island.center) > FLIGHT_RADIUS) {
					continue;
				}
				boolean blocked = false;
				if (player.isFallFlying()) {
					player.stopFallFlying();
					blocked = true;
				}
				if (player.isAutoSpinAttack()) {
					Motion.setVelocity(player, new Vec3(0.0D, -0.5D, 0.0D));
					blocked = true;
				}
				if (blocked) {
					Long last = this.flightSounds.get(player.getUUID());
					if (last == null || tick - last >= FLIGHT_SOUND_COOLDOWN_TICKS) {
						this.flightSounds.put(player.getUUID(), tick);
						Fx.soundTo(player, "BLOCK_NOTE_BLOCK_BASS", 1.0F, 0.5F);
					}
				}
			}
		}
	}

	/** {@code startGlowTask}'s body: shard carriers glow. */
	private void glowPass(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean carrying = isShard(player.getMainHandItem()) || isShard(player.getOffhandItem());
			if (!carrying) {
				for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
					if (isShard(player.getInventory().getItem(slot))) {
						carrying = true;
						break;
					}
				}
			}
			// Bukkit's Player#setGlowing writes the same flag vanilla keeps in
			// hasGlowingTag; 26.x names the setter setGlowingTag.
			if (carrying != player.isCurrentlyGlowing()) {
				player.setGlowingTag(carrying);
			}
		}
	}

	/**
	 * {@code CopperChestplateTrial#onItemPickup} for a shard. The item-entity mixin
	 * calls this the moment a player collects one.
	 */
	public void onShardPickedUp(ServerPlayer player, ItemEntity item) {
		Iterator<Island> iterator = this.islands.iterator();
		while (iterator.hasNext()) {
			Island island = iterator.next();
			if (!island.shard.equals(item)) {
				continue;
			}
			island.stand.discard();
			iterator.remove();
			MinecraftServer server = this.mod.server();
			if (server == null) {
				return;
			}
			for (ServerPlayer online : server.getPlayerList().getPlayers()) {
				Messaging.send(online, "<red><bold>ALERT! <gold>" + player.getGameProfile().getName()
						+ " <yellow>has retrieved a Chestplate Shard!");
				Messaging.send(online, "<gray>The weather has calmed down near that shard's location.");
				Fx.soundTo(online, "ENTITY_LIGHTNING_BOLT_THUNDER", 0.7F, 1.2F);
			}
			return;
		}
	}

	/** {@code CopperChestplateTrial#isNearActiveTrial}. */
	public boolean isNearActiveTrial(@Nullable ServerLevel level, Vec3 pos, double radius) {
		if (level == null) {
			return false;
		}
		ResourceKey<Level> dimension = level.dimension();
		for (Island island : this.islands) {
			if (island.dimension.equals(dimension) && island.center.distanceTo(pos) <= radius) {
				return true;
			}
		}
		return false;
	}

	public boolean isRunning() {
		return !this.islands.isEmpty();
	}

	/** Shutdown: the islands' entities go away with the world; nothing to announce. */
	public void stopForShutdown() {
		for (Island island : this.islands) {
			if (!island.stand.isRemoved()) {
				island.stand.discard();
			}
		}
		this.islands.clear();
	}

	/** Restores islands recorded before a restart, without their entities. */
	public void loadFromStore(List<String> recordedCenters) {
		this.islands.clear();
		for (String record : recordedCenters) {
			String[] parts = record.split(",");
			if (parts.length != 4) {
				AltarSMPMod.LOGGER.warn("[AltarSMP] skipping unreadable chestplate trial record '{}'", record);
				continue;
			}
			MinecraftServer server = this.mod.server();
			if (server == null) {
				return;
			}
			ResourceKey<Level> dimension = Level.OVERWORLD;
			for (ResourceKey<Level> key : server.levelKeys()) {
				if (key.identifier().toString().equals(parts[0])) {
					dimension = key;
					break;
				}
			}
			Vec3 center = new Vec3(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
					Double.parseDouble(parts[3]));
			ServerLevel level = server.getLevel(dimension);
			if (level != null) {
				// The shard and its stand are world entities: rebuild them where they were.
				spawnTrialIsland(level, center);
			}
		}
	}

	/** Records every live island so a restart can rebuild them. */
	public List<String> describeIslands() {
		List<String> records = new ArrayList<>();
		for (Island island : this.islands) {
			records.add(island.dimension.identifier() + "," + island.center.x + "," + island.center.y + ","
					+ island.center.z);
		}
		return records;
	}

	/** {@code CopperChestplateTrial.a} - one built island. */
	private static final class Island {
		final ResourceKey<Level> dimension;
		final Vec3 center;
		final ArmorStand stand;
		final ItemEntity shard;

		Island(ResourceKey<Level> dimension, Vec3 center, ArmorStand stand, ItemEntity shard) {
			this.dimension = dimension;
			this.center = center;
			this.stand = stand;
			this.shard = shard;
		}
	}
}
