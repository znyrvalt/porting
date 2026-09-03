package com.altarsmp.fabric.altar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.data.AltarRecord;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.recipe.RecipeRegistry;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TextFx;

/**
 * Altar placement, holograms, rotation and removal - a port of
 * {@code com.altarsmp.altars.AltarManager}, {@code a/s.java} (its Season 2 twin),
 * {@code AltarBreakCleanup} and both {@code AltarBreakListener}s.
 *
 * <p>An altar is four things anchored on a structure block: the block itself, an
 * invisible armour stand carrying the coloured altar name (that is what the ritual
 * matches on, and what a player punches), a full-bright {@code ItemDisplay} two and a
 * half blocks above it wearing the resource pack's custom-model item, and a stack of
 * {@code TextDisplay} holograms five blocks up - the name at 1.2x scale, then the
 * recipe's ingredient lines at 0.8x, a quarter block apart. Season 2 builds the same
 * shape four blocks up, two blocks for the item, and tags its parts
 * {@code altarsmps2_*} instead of {@code altar_*}.
 *
 * <p>The item display spins: every tick the rotation angle grows by
 * {@code altar.rotation_speed} degrees (2.0 by default) and every registered display
 * gets a new {@link Transformation} around the Y axis, which is the plugin's rotation
 * task verbatim. Displays are found by their scoreboard tags, so an altar that was
 * placed before a restart - or whose chunk has just streamed back in - keeps spinning
 * once {@link #rebuildDisplays()} sees it again.
 *
 * <p>Breaking the structure block takes the whole altar down ({@code AltarBreakCleanup}:
 * displays and holograms by tag within 6x10x6, plus any invisible, gravity-free, named
 * armour stand). Crafting an altar removes it too, by the plugin's
 * {@code removeAltarNear} rules. Explosions are meant to do the same; that path arrives
 * from the explosion mixin, since 26.x has no block-explode event to subscribe to.
 */
public final class AltarManager {

	// Season 1 scoreboard tags (AltarManager / AltarBreakCleanup / AltarBreakListener)
	public static final String TAG_DISPLAY = "altar_display";
	public static final String TAG_HOLOGRAM = "altar_hologram";
	public static final String TAG_STAND = "altar_stand";
	// Season 2 scoreboard tags (a/s.java, a/s.java#AltarBreakListener)
	public static final String TAG_S2_DISPLAY = "altarsmps2_display";
	public static final String TAG_S2_HOLOGRAM = "altarsmps2_hologram";
	public static final String TAG_S2_STAND = "altarsmps2_stand";

	/** {@code player.getTargetBlockExact(10)}. */
	private static final double TARGET_RANGE = 10.0D;
	/** {@code AltarBreakCleanup}: 6 wide, 10 tall. */
	private static final double CLEANUP_RADIUS = 6.0D;
	private static final double CLEANUP_HEIGHT = 10.0D;
	/** {@code AltarBreakListener}: 3.5 wide, 6.5 tall. */
	private static final double BREAK_RADIUS = 3.5D;
	private static final double BREAK_HEIGHT = 6.5D;
	/** {@code removeAltarNear}: 3 wide, 3+4 tall. */
	private static final double NEAR_RADIUS = 3.0D;
	private static final double NEAR_HEIGHT = 7.0D;
	/** How often the tagged displays are re-collected, so reloaded chunks keep spinning. */
	private static final long RESCAN_PERIOD_TICKS = 600L;
	/** Name fragments {@code removeAltarNear} treats as altar parts. */
	private static final String[] NEAR_NAME_HINTS = {"Block", "Star", "Head", "Heart", "Handle", "Shard", "Core"};
	/**
	 * {@code DestroyAltarsCommand}'s much wider name net: the sweep is meant to clean up
	 * altars left behind by older builds, so it matches on the words their stands carried.
	 */
	private static final String[] DESTROY_NAME_HINTS = {"Altar", "Block", "Star", "Head", "Heart", "Handle",
			"Shard", "Core", "Bloodlust", "Bone Blade", "Hyperion", "Nightpiercer", "Vulcan", "Vulkan",
			"Wand of Illusion", "Frost Scythe", "Crafting", "Pure Blade", "Earth Gauntlet", "Paladin", "Cutlass",
			"Crazy Slots", "Ice Shard", "Fire Shard", "Pale Shard", "Weapon Handle", "Warden Head",
			"Illusion Core", "Left-click", "Right-click", "x "};
	/** The tag MythicWeapons' altars carried; the sweep removes them too. */
	private static final String TAG_MYTHIC = "mythic_altar";

	private final AltarSMPMod mod;
	private final Set<UUID> rotatingDisplays = new HashSet<>();
	private float angle;
	private long tickCount;
	/** {@code LockAltarsCommand#isLocked} - the admin kill-switch for altar interaction. */
	private volatile boolean locked;

	public AltarManager(AltarSMPMod mod) {
		this.mod = mod;
	}

	// ----------------------------------------------------------------- building

	/**
	 * {@code AltarManager#createAltar} / {@code a/s.java#a}: builds the altar on the
	 * block the player is looking at, up to ten blocks away.
	 *
	 * @return {@code true} when the altar was built
	 */
	public boolean createAltar(ServerPlayer player, AltarRegistry.Spec spec) {
		ServerLevel level = player.serverLevel();
		BlockHitResult hit = lookAt(player, TARGET_RANGE);
		if (hit == null) {
			Messaging.send(player, "<red>Look at a block within 10 blocks to place the altar.");
			return false;
		}
		if (!createAltarAt(level, hit.getBlockPos().above(), spec, player)) {
			Messaging.send(player, "<red>Error creating altar. Look at a block within 3 blocks.");
			return false;
		}
		Messaging.send(player, "<green>Altar for " + spec.display() + " created!");
		return true;
	}

	/**
	 * Builds an altar at an exact position: the structure-block anchor, the rotating item
	 * display, the invisible name stand and the hologram lines, and then records it.
	 *
	 * <p>{@code /altar} reaches this through the raycast above. {@code /spawnaltarrandom}
	 * picks its own positions and calls it directly, so a pillar altar is a real, recorded,
	 * craftable altar that {@code /destroyaltars} can sweep - not a decoration that says
	 * "left-click to craft" and does nothing.
	 *
	 * @param owner who to credit in the record, or null when the server placed it
	 */
	public boolean createAltarAt(ServerLevel level, BlockPos anchor, AltarRegistry.Spec spec,
			@Nullable ServerPlayer owner) {
		try {
			level.setBlock(anchor, Blocks.STRUCTURE_BLOCK.defaultBlockState(), 3);
			Vec3 anchorCenter = Vec3.atLowerCornerOf(anchor);
			boolean seasonTwo = spec.season() == 2;

			// The plugin's Wither Symbiote altar shows a piece of paper, not a sword.
			String materialKey = spec.material() == null ? "NETHERITE_SWORD" : spec.material();
			int cmd = spec.cmd();
			if ("Wither Symbiote".equals(spec.display())) {
				materialKey = "PAPER";
				cmd = 4;
			}
			net.minecraft.world.item.Item material = RecipeRegistry.resolveItem(materialKey);
			if (material == null) {
				AltarSMPMod.LOGGER.error("[AltarSMP] altar '{}' wants unknown display material '{}' - using a sword",
						spec.display(), materialKey);
				material = Items.NETHERITE_SWORD;
			}
			ItemStack displayed = new ItemStack(material);
			if (!seasonTwo || cmd > 0) {
				ItemFactory.applyModelData(displayed, cmd);
			}

			double itemHeight = seasonTwo ? 2.0D : 2.5D;
			Display.ItemDisplay display = EntityType.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
			if (display == null) {
				AltarSMPMod.LOGGER.error("[AltarSMP] could not create the item display for altar '{}'", spec.display());
				return false;
			}
			display.moveTo(anchorCenter.x + 0.5D, anchorCenter.y + itemHeight, anchorCenter.z + 0.5D, 0.0F, 0.0F);
			display.setItemStack(displayed);
			display.setBrightnessOverride(Brightness.FULL_BRIGHT);
			display.setTransformation(new Transformation(new Vector3f(0.0F, 0.0F, 0.0F),
					new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F), new Vector3f(1.0F, 1.0F, 1.0F),
					new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F)));
			display.addTag(seasonTwo ? TAG_S2_DISPLAY : TAG_DISPLAY);
			display.addTag("altar_" + spec.display().replace(' ', '_'));
			level.addFreshEntity(display);
			this.rotatingDisplays.add(display.getUUID());

			ArmorStand stand = new ArmorStand(level, anchorCenter.x + 0.5D, anchorCenter.y + 0.5D,
					anchorCenter.z + 0.5D);
			stand.setInvisible(true);
			stand.setCustomName(nameOf(spec));
			stand.setCustomNameVisible(false);
			stand.setInvulnerable(false);
			stand.setNoGravity(true);
			stand.setMarker(false);
			stand.setPersistenceRequired();
			if (seasonTwo) {
				stand.addTag(TAG_S2_STAND);
			}
			level.addFreshEntity(stand);

			double hologramBase = seasonTwo ? 4.0D : 5.0D + spec.yOffset();
			Vec3 base = stand.position().add(0.0D, hologramBase, 0.0D);
			createHologramText(level, base.add(0.0D, 0.5D, 0.0D), nameOf(spec), true, seasonTwo);
			double offset = 0.25D;
			for (String line : this.mod.recipes().hologramLines(spec.recipeId() == null ? "" : spec.recipeId())) {
				createHologramText(level, base.add(0.0D, offset, 0.0D), Messaging.msg(line), false, seasonTwo);
				offset -= 0.25D;
			}

			recordAltar(spec, level, anchor, stand, owner);
			return true;
		} catch (RuntimeException e) {
			AltarSMPMod.LOGGER.error("[AltarSMP] failed to create the altar for '{}'", spec.display(), e);
			return false;
		}
	}

	/** The coloured altar name both the stand and the hologram title carry. */
	public static Component nameOf(AltarRegistry.Spec spec) {
		String color = spec.color() == null ? "gold" : spec.color();
		return Messaging.msg("<" + color + ">" + spec.display());
	}

	/** {@code AltarManager#createHologramText} / {@code a/s.java#a(world, loc, text, title)}. */
	private void createHologramText(ServerLevel level, Vec3 at, Component text, boolean title, boolean seasonTwo) {
		Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		if (display == null) {
			AltarSMPMod.LOGGER.error("[AltarSMP] could not create an altar hologram line");
			return;
		}
		display.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
		display.setText(text);
		display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		// shadowed = true, see-through = false, default background = false, centred.
		display.setFlags(Display.TextDisplay.FLAG_SHADOW);
		float scale = title ? 1.2F : 0.8F;
		display.setTransformation(new Transformation(new Vector3f(0.0F, 0.0F, 0.0F),
				new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F), new Vector3f(scale, scale, scale),
				new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F)));
		display.addTag(seasonTwo ? TAG_S2_HOLOGRAM : TAG_HOLOGRAM);
		level.addFreshEntity(display);
	}

	private void recordAltar(AltarRegistry.Spec spec, ServerLevel level, BlockPos anchor, ArmorStand stand,
			@Nullable ServerPlayer player) {
		AltarRecord record = new AltarRecord(stand.getStringUUID(), spec.key(),
				level.dimension().identifier().toString(), anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
		record.recipeId(spec.recipeId() == null ? "" : spec.recipeId());
		record.spawnedBy(player == null ? "server" : player.getGameProfile().getName());
		this.mod.store().putAltar(record);
		this.mod.store().markDirty();
	}

	/** {@code player.getTargetBlockExact(range)} - the block the player is aiming at. */
	@Nullable
	public static BlockHitResult lookAt(ServerPlayer player, double range) {
		Vec3 start = player.getEyePosition();
		Vec3 end = start.add(player.getLookAngle().normalize().scale(range));
		BlockHitResult hit = player.serverLevel().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		return hit;
	}

	// ------------------------------------------------------------------ rotation

	/** The plugin's rotation task: {@code altar.rotation_speed} degrees per tick. */
	public void tick(MinecraftServer server) {
		this.tickCount++;
		if (this.tickCount % RESCAN_PERIOD_TICKS == 0L) {
			rebuildDisplays();
		}
		if (this.rotatingDisplays.isEmpty()) {
			return;
		}
		float speed = (float) this.mod.config().getDouble("altar.rotation_speed", 2.0D);
		this.angle += speed;
		if (this.angle >= 360.0F) {
			this.angle = 0.0F;
		}
		AxisAngle4f spin = new AxisAngle4f((float) Math.toRadians(this.angle), 0.0F, 1.0F, 0.0F);
		for (Level dimension : server.getAllLevels()) {
			if (!(dimension instanceof ServerLevel level)) {
				continue;
			}
			for (Entity entity : level.getAllEntities()) {
				if (!(entity instanceof Display.ItemDisplay display)
						|| !this.rotatingDisplays.contains(display.getUUID())) {
					continue;
				}
				Transformation current = display.getTransformation();
				float scale = current.getScale().x();
				display.setTransformation(new Transformation(current.getTranslation(), spin,
						new Vector3f(scale, scale, scale), new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F)));
			}
		}
	}

	/** {@code AltarManager#unregisterRotatingDisplay}. */
	public void unregisterRotatingDisplay(UUID displayId) {
		this.rotatingDisplays.remove(displayId);
	}

	/**
	 * {@code AltarManager#cleanupOrphanedDisplays}: forget what we held and re-collect
	 * every tagged item display in the loaded worlds. Run at startup and periodically, so
	 * altars placed before a restart (or in a chunk that just streamed in) spin again.
	 */
	public void rebuildDisplays() {
		MinecraftServer server = this.mod.server();
		if (server == null) {
			return;
		}
		this.rotatingDisplays.clear();
		int found = 0;
		for (Level dimension : server.getAllLevels()) {
			if (!(dimension instanceof ServerLevel level)) {
				continue;
			}
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof Display.ItemDisplay display
						&& (display.getTags().contains(TAG_DISPLAY) || display.getTags().contains(TAG_S2_DISPLAY))) {
					this.rotatingDisplays.add(display.getUUID());
					found++;
				}
			}
		}
		if (found > 0) {
			AltarSMPMod.LOGGER.info("[AltarSMP] {} altar display(s) rotating", found);
		}
	}

	/**
	 * Shutdown hook. The altar entities are persistent world entities - deleting them
	 * here would erase every altar on the server - so this only drops the references this
	 * class holds; the displays themselves stay where they were built.
	 */
	public void removeDisplays() {
		this.rotatingDisplays.clear();
	}

	// ------------------------------------------------------------------ removal

	/**
	 * {@code AltarBreakCleanup#cleanupNear}: the structure block went away, so the altar
	 * goes with it - tagged displays and holograms within 6x10x6, plus any invisible,
	 * gravity-free, named armour stand (which is how the plugin recognised its own).
	 *
	 * @return how many entities were removed
	 */
	public int removeAt(ServerLevel level, BlockPos pos) {
		Vec3 center = Vec3.atLowerCornerOf(pos).add(0.5D, 0.5D, 0.5D);
		int removed = 0;
		for (Entity entity : nearby(level, center, CLEANUP_RADIUS, CLEANUP_HEIGHT)) {
			if (isAltarDisplay(entity) || isAltarHologram(entity) || isAltarStand(entity)) {
				forget(entity);
				entity.discard();
				removed++;
			}
		}
		if (removed > 0) {
			String altarId = altarIdAt(level, center);
			if (altarId != null) {
				this.mod.store().removeAltar(altarId);
				this.mod.store().markDirty();
			}
		}
		return removed;
	}

	/**
	 * {@code AltarBreakListener#onBreak} (both seasons): a creative player breaking a
	 * block removes tagged altar parts within 3.5x6.5x3.5 and reports the count.
	 *
	 * @return how many parts were removed, or 0 when nothing was tagged there
	 */
	public int removeTaggedParts(ServerLevel level, BlockPos pos) {
		Vec3 center = Vec3.atLowerCornerOf(pos).add(0.5D, 1.0D, 0.5D);
		int removed = 0;
		for (Entity entity : nearby(level, center, BREAK_RADIUS, BREAK_HEIGHT)) {
			if (!(entity instanceof Display) && !(entity instanceof ArmorStand)) {
				continue;
			}
			if (!isTagged(entity, TAG_DISPLAY, TAG_HOLOGRAM, TAG_STAND, TAG_S2_DISPLAY, TAG_S2_HOLOGRAM, TAG_S2_STAND)) {
				continue;
			}
			forget(entity);
			entity.discard();
			removed++;
		}
		return removed;
	}

	/**
	 * {@code AltarManager#removeAltarNear(location, display)} - what a successful craft
	 * calls. Season 1 recognised its parts by tag <em>or</em> by name (any armour stand
	 * whose name matches the altar, or merely contains "Block", "Star", "Head", "Heart",
	 * "Handle", "Shard" or "Core", any item entity named {@code *_DISPLAY}, any tagged
	 * display or hologram); Season 2 matched strictly on its own tags.
	 */
	public void removeAltarNear(Entity stand) {
		if (!(stand.level() instanceof ServerLevel level)) {
			return;
		}
		String display = stand.getCustomName() == null ? "" : TextFx.strip(stand.getCustomName().getString());
		boolean seasonTwo = isTagged(stand, TAG_S2_DISPLAY, TAG_S2_HOLOGRAM, TAG_S2_STAND);
		Vec3 center = stand.position();
		for (Entity entity : nearby(level, center, NEAR_RADIUS, NEAR_HEIGHT)) {
			boolean remove;
			if (seasonTwo) {
				boolean namedStand = entity instanceof ArmorStand && isTagged(entity, TAG_S2_STAND)
						&& entity.getCustomName() != null
						&& display.equalsIgnoreCase(TextFx.strip(entity.getCustomName().getString()));
				remove = namedStand || (isTagged(entity, TAG_S2_DISPLAY) && taggedWithName(entity, display))
						|| isTagged(entity, TAG_S2_HOLOGRAM);
			} else if (entity instanceof ArmorStand other && other.getCustomName() != null) {
				String name = TextFx.strip(other.getCustomName().getString());
				remove = name.equalsIgnoreCase(display) || containsHint(name);
			} else if (entity instanceof ItemEntity item && item.getCustomName() != null) {
				remove = item.getCustomName().getString().contains("_DISPLAY");
			} else {
				remove = isAltarDisplay(entity) || isAltarHologram(entity);
			}
			if (remove) {
				forget(entity);
				entity.discard();
			}
		}
		this.mod.store().removeAltar(stand.getStringUUID());
		this.mod.store().markDirty();
	}

	private static boolean containsHint(String name) {
		for (String hint : NEAR_NAME_HINTS) {
			if (name.contains(hint)) {
				return true;
			}
		}
		return false;
	}

	private static boolean taggedWithName(Entity entity, String display) {
		return entity.getTags().contains("altar_" + display.replace(' ', '_'));
	}

	private static boolean isAltarDisplay(Entity entity) {
		return entity instanceof Display.ItemDisplay && isTagged(entity, TAG_DISPLAY, TAG_S2_DISPLAY);
	}

	private static boolean isAltarHologram(Entity entity) {
		return entity instanceof Display.TextDisplay && isTagged(entity, TAG_HOLOGRAM, TAG_S2_HOLOGRAM);
	}

	/** {@code AltarBreakCleanup}'s stand rule: invisible, no gravity, named. */
	private static boolean isAltarStand(Entity entity) {
		return entity instanceof ArmorStand stand && !stand.isVisible() && stand.isNoGravity()
				&& stand.getCustomName() != null;
	}

	private static boolean isTagged(Entity entity, String... tags) {
		Set<String> owned = entity.getTags();
		for (String tag : tags) {
			if (owned.contains(tag)) {
				return true;
			}
		}
		return false;
	}

	private void forget(Entity entity) {
		this.rotatingDisplays.remove(entity.getUUID());
	}

	/** {@code World#getNearbyEntities(location, x, y, z)} - a box of those half-extents. */
	private static List<Entity> nearby(ServerLevel level, Vec3 center, double radius, double height) {
		AABB box = new AABB(center.x - radius, center.y - height, center.z - radius, center.x + radius,
				center.y + height, center.z + radius);
		return level.getEntities(null, box, candidate -> true);
	}

	@Nullable
	private String altarIdAt(ServerLevel level, Vec3 center) {
		for (Map.Entry<String, AltarRecord> entry : this.mod.store().altars().entrySet()) {
			AltarRecord record = entry.getValue();
			if (record.dimension().equals(level.dimension().identifier().toString())
					&& Math.abs(record.x() - center.x) < 1.5D && Math.abs(record.y() - center.y) < 2.5D
					&& Math.abs(record.z() - center.z) < 1.5D) {
				return entry.getKey();
			}
		}
		return null;
	}

	// ------------------------------------------------------------- interactions

	/**
	 * {@code WeaponRegistry}'s armour-stand hit path: the altar registry decides whether
	 * the entity is an altar and runs its ritual.
	 *
	 * @return {@code true} when the hit was an altar and must not deal damage
	 */
	public boolean handleLeftClick(ServerPlayer player, Entity target) {
		return this.mod.altarRegistry().onLeftClick(player, target);
	}

	/** {@code PlayerArmorStandManipulateEvent}: nobody may use an altar stand's hand. */
	public boolean blockManipulate(ServerPlayer player, Entity target) {
		return this.mod.altarRegistry().blockManipulate(player, target);
	}

	/** {@code CraftingAltarInteract}'s right-click collect path. */
	public boolean handleRightClick(ServerPlayer player, Entity target) {
		return this.mod.altarRegistry().onRightClick(player, target);
	}

	/**
	 * {@code AltarManager#refreshHologram}: rebuild the text above an altar, which is how
	 * a recipe change in {@code config.yml} reaches the world.
	 */
	public void refreshHologram(ServerLevel level, Entity stand, AltarRegistry.Spec spec) {
		Vec3 center = stand.position();
		for (Entity entity : nearby(level, center, NEAR_RADIUS, 12.0D)) {
			if (isAltarHologram(entity)) {
				entity.discard();
			}
		}
		boolean seasonTwo = spec.season() == 2;
		double hologramBase = seasonTwo ? 4.0D : 5.0D + spec.yOffset();
		Vec3 base = center.add(0.0D, hologramBase, 0.0D);
		createHologramText(level, base.add(0.0D, 0.5D, 0.0D), nameOf(spec), true, seasonTwo);
		double offset = 0.25D;
		for (String line : this.mod.recipes().hologramLines(spec.recipeId() == null ? "" : spec.recipeId())) {
			createHologramText(level, base.add(0.0D, offset, 0.0D), Messaging.msg(line), false, seasonTwo);
			offset -= 0.25D;
		}
	}

	// ------------------------------------------------------------------ store

	/** Reads the recorded altars; the entities themselves live in the world. */
	public void loadFromStore() {
		Map<String, AltarRecord> altars = this.mod.store().altars();
		int unknown = 0;
		for (AltarRecord record : altars.values()) {
			if (this.mod.altarRegistry().byKey(record.altarType()) == null) {
				unknown++;
				AltarSMPMod.LOGGER.warn("[AltarSMP] recorded altar {} at {} {} {} refers to unknown content '{}'",
						record.altarId(), record.x(), record.y(), record.z(), record.altarType());
			}
		}
		AltarSMPMod.LOGGER.info("[AltarSMP] {} recorded altar(s){}", altars.size(),
				unknown == 0 ? "" : ", " + unknown + " referring to unknown content");
	}

	/** Every recorded altar, for {@code /altars list} and the admin GUIs. */
	public List<AltarRecord> recorded() {
		return new ArrayList<>(this.mod.store().altars().values());
	}

	/**
	 * Rewrites every altar hologram in every loaded level.
	 *
	 * <p>The holograms quote recipe amounts and names straight out of the config, so
	 * {@code /altarsmp reload} and the {@code /legendaryconfig} editor both call this
	 * after a change; what a player reads over an altar always matches the file on
	 * disk. This is {@code AltarSMP#reloadConfigAndServices}' hologram half.
	 *
	 * @return how many holograms were rewritten
	 */
	public int refreshAllHolograms(MinecraftServer server) {
		int refreshed = 0;
		for (AltarRecord record : recorded()) {
			AltarRegistry.Spec spec = this.mod.altarRegistry().byKey(record.altarType());
			ServerLevel level = levelById(server, record.dimension());
			if (spec == null || level == null) {
				continue;
			}
			net.minecraft.world.entity.Entity stand;
			try {
				stand = level.getEntity(java.util.UUID.fromString(record.altarId()));
			} catch (IllegalArgumentException e) {
				AltarSMPMod.LOGGER.warn("[AltarSMP] altar record {} is not a usable entity id", record.altarId());
				continue;
			}
			if (stand == null || specOf(stand) == null) {
				continue;
			}
			refreshHologram(level, stand, spec);
			refreshed++;
		}
		return refreshed;
	}

	/** The level whose dimension id matches a stored record, or null when it is not loaded. */
	@Nullable
	private static ServerLevel levelById(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	/**
	 * {@code DestroyAltarsCommand}'s sweep: removes every altar part in a level.
	 *
	 * @return how many entities were removed
	 */
	public int destroyAll(ServerLevel level) {
		int removed = 0;
		for (Entity entity : level.getAllEntities()) {
			if (isAltarDisplay(entity) || isAltarHologram(entity) || isAltarStand(entity)) {
				forget(entity);
				entity.discard();
				removed++;
			}
		}
		return removed;
	}

	/** The altar a stand belongs to, for commands that report on what was clicked. */
	@Nullable
	public AltarRegistry.Spec specOf(@Nullable Entity entity) {
		return this.mod.altarRegistry().byStand(entity);
	}

	/** Lower-case display names of every registered altar, for command suggestions. */
	public List<String> altarNames() {
		List<String> names = new ArrayList<>();
		for (AltarRegistry.Spec spec : this.mod.altarRegistry().all()) {
			names.add(spec.plainDisplay().toLowerCase(Locale.ROOT));
		}
		return names;
	}

	// ------------------------------------------------------------------- lock

	/** {@code LockAltarsCommand#isLocked}: while locked, altar stands ignore right clicks. */
	public boolean isLocked() {
		return this.locked;
	}

	/** {@code /lockaltars} flips the switch and reports which way it went. */
	public boolean toggleLock() {
		this.locked = !this.locked;
		return this.locked;
	}

	// ---------------------------------------------------------- destroy sweep

	/** {@code DestroyAltarsCommand}'s five tallies. */
	public record Sweep(int stands, int legacyItems, int displays, int holograms, int blocks) {
		public int total() {
			return this.stands + this.legacyItems + this.displays + this.holograms + this.blocks;
		}
	}

	/**
	 * {@code DestroyAltarsCommand}: removes every altar part, and every structure block,
	 * either within {@code radius} of {@code center} or across the whole level when
	 * {@code center} is null. The stand rule is the plugin's wide one - a name from
	 * {@link #DESTROY_NAME_HINTS}, an {@code altarsmps2_stand}/{@code mythic_altar} tag,
	 * or plain invisibility - because the sweep exists to clean up after older builds.
	 *
	 * @param center the player's block, or null for a whole-level sweep
	 * @param radius the cube half-extent, ignored when {@code center} is null
	 */
	public Sweep sweep(ServerLevel level, @Nullable BlockPos center, int radius) {
		int stands = 0;
		int legacyItems = 0;
		int displays = 0;
		int holograms = 0;
		Iterable<Entity> found = center == null ? level.getAllEntities()
				: nearby(level, Vec3.atCenterOf(center), radius, radius);
		for (Entity entity : found) {
			if (entity instanceof ArmorStand stand && looksLikeAltarStand(stand)) {
				forget(entity);
				entity.discard();
				stands++;
			} else if (entity instanceof ItemEntity item && isLegacyDisplayItem(item)) {
				entity.discard();
				legacyItems++;
			} else if (isAltarDisplay(entity)) {
				forget(entity);
				entity.discard();
				displays++;
			} else if (isAltarHologram(entity)) {
				entity.discard();
				holograms++;
			}
		}
		int blocks;
		if (center == null) {
			blocks = 0;
			Stream<ChunkHolder> holders = level.getChunkSource().chunkMap.allChunksWithAtLeastStatus(ChunkStatus.FULL);
			for (ChunkHolder holder : holders.toList()) {
				LevelChunk chunk = holder.getTickingChunk();
				if (chunk != null) {
					blocks += clearStructureBlocks(level, chunk);
				}
			}
		} else {
			blocks = 0;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dy = -radius; dy <= radius; dy++) {
					for (int dz = -radius; dz <= radius; dz++) {
						BlockPos pos = center.offset(dx, dy, dz);
						if (level.getBlockState(pos).is(Blocks.STRUCTURE_BLOCK)) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
							blocks++;
						}
					}
				}
			}
		}
		return new Sweep(stands, legacyItems, displays, holograms, blocks);
	}

	/** {@code DestroyAltarsCommand#a(ArmorStand)}: hinted name, altar tag, or invisible. */
	private static boolean looksLikeAltarStand(ArmorStand stand) {
		if (isTagged(stand, TAG_S2_STAND, TAG_MYTHIC) || stand.isInvisible()) {
			return true;
		}
		Component name = stand.getCustomName();
		if (name == null) {
			return false;
		}
		String plain = TextFx.strip(name.getString());
		for (String hint : DESTROY_NAME_HINTS) {
			if (plain.contains(hint)) {
				return true;
			}
		}
		return false;
	}

	/** Floating item entities named {@code *_DISPLAY} / {@code *_ALTAR_ITEM} by older builds. */
	private static boolean isLegacyDisplayItem(ItemEntity item) {
		Component name = item.getCustomName();
		if (name == null) {
			return false;
		}
		String plain = TextFx.strip(name.getString());
		return plain.contains("_DISPLAY") || plain.contains("_ALTAR_ITEM");
	}

	/**
	 * Explosions. {@code AltarBreakListener} walked {@code EntityExplodeEvent}'s block list
	 * and tore down every altar whose structure block appeared in it. 26.x has no
	 * block-explode event to subscribe to, so the explosion mixin reports the centre and the
	 * power instead: every recorded altar within blast range is checked, and any whose anchor
	 * is no longer a structure block comes down exactly the way a mined anchor does.
	 *
	 * @param center the explosion's position
	 * @param power the explosion's power, which bounds how far blocks could have been removed
	 */
	public void onExplosion(ServerLevel level, Vec3 center, float power) {
		double radius = Math.max(4.0D, power * 2.0D);
		String dimension = level.dimension().identifier().toString();
		for (AltarRecord record : recorded()) {
			if (!record.dimension().equals(dimension)) {
				continue;
			}
			Vec3 anchor = new Vec3(record.x(), record.y(), record.z());
			if (anchor.distanceTo(center) > radius) {
				continue;
			}
			BlockPos pos = BlockPos.containing(anchor);
			if (!level.getBlockState(pos).is(Blocks.STRUCTURE_BLOCK)) {
				int removed = removeAt(level, pos);
				AltarSMPMod.LOGGER.info("[AltarSMP] explosion at {} {} {} removed altar {} ({} parts)", center.x,
						center.y, center.z, record.altarId(), removed);
			}
		}
	}

	/** Airs every structure block in one loaded chunk, skipping sections that are all air. */
	private static int clearStructureBlocks(ServerLevel level, LevelChunk chunk) {
		int cleared = 0;
		LevelChunkSection[] sections = chunk.getSections();
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			if (section == null || section.hasOnlyAir()) {
				continue;
			}
			int baseY = level.getSectionYFromSectionIndex(index) << 4;
			int minX = chunk.getPos().getMinBlockX();
			int minZ = chunk.getPos().getMinBlockZ();
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						BlockPos pos = new BlockPos(minX + x, baseY + y, minZ + z);
						if (level.getBlockState(pos).is(Blocks.STRUCTURE_BLOCK)) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
							cleared++;
						}
					}
				}
			}
		}
		return cleared;
	}
}
