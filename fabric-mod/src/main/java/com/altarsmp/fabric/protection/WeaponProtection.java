package com.altarsmp.fabric.protection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.AABB;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.Messaging;

/**
 * Everything the plugin spread across {@code listeners.WeaponStoragePrevention}
 * (both the season 1 and the season 2 copy), {@code listeners.PvpProtection},
 * {@code commands.PvpToggleCommand}'s static toggle and {@code a.D} - the
 * reflection bridge to WorldGuard - behind one native façade.
 *
 * <p><b>Legendary item protection.</b> {@code weapon-protection.enabled} guards
 * every item the content catalogues mark as protected (season 1 altar weapons,
 * armour and items, season 2 weapons and the mythic-weapon namespace) against the
 * storage routes Bukkit blocked: chests, barrels, ender chests, hoppers, shulker
 * boxes, droppers, dispensers, furnaces, blast furnaces, smokers, brewing stands
 * and crafters (each with its own {@code weapon-protection.containers.*} switch),
 * bundles, and empty item frames. {@code weapon-protection.burn-protection} stops
 * dropped copies from burning in fire, lava, cactus or the void, and
 * {@code weapon-destruction-protection.enabled} (off by default, as upstream) makes
 * them fully indestructible and immortal - no explosion damage and no 5 minute
 * despawn. When destruction protection is off, a dropped legendary that is being
 * destroyed by fire or an explosion is announced to the whole server, which is what
 * {@code scheduleDestroyAnnouncement} meant to do before its broadcast loop was
 * compiled away to an empty body.
 *
 * <p><b>PvP.</b> {@code /pvp [on|off|status]} flips one server-wide switch; while
 * it is off, player-on-player damage - direct or through a projectile - is
 * cancelled and the attacker is told why (throttled to one message every two
 * seconds, the interval {@code PvpProtection} kept but never used for anything).
 * Splash potions and lingering clouds thrown by a player also stop affecting other
 * players when they carry a harmful effect.
 *
 * <p><b>Regions.</b> Bukkit asked WorldGuard about the BUILD and PVP flags through
 * reflection and treated "WorldGuard is not installed" as "allowed". There is no
 * WorldGuard on Fabric, so this class is the region authority instead: the systems
 * that own space - altars, ban zones, trials, faction territory - register a
 * {@link BlockVeto}, {@link AttackVeto} or {@link DamageVeto} and are consulted for
 * every query. Vanilla spawn protection still applies to block changes, and
 * operators bypass it exactly as they do in vanilla.
 */
public final class WeaponProtection {

	/** {@code PvpProtection#b} - the throttle window for "PvP is disabled" messages. */
	private static final long PVP_MESSAGE_THROTTLE_MILLIS = 2000L;
	/** Bukkit's {@code scheduleDestroyAnnouncement} re-checked after 3 ticks. */
	private static final long DESTROY_CHECK_DELAY_TICKS = 3L;
	/** How far around a joining player dropped legendaries are made immortal. */
	private static final double JOIN_SCAN_RADIUS = 32.0D;

	private final AltarSMPMod mod;
	private final AltarConfig config;

	/** {@code PvpToggleCommand#b} - server-wide, {@code true} by default. */
	private volatile boolean pvpEnabled = true;
	/** Per-player throttle for the "PvP is disabled" action bar. */
	private final Map<UUID, Long> lastPvpMessage = new ConcurrentHashMap<>();
	/** Bukkit's {@code Set<UUID> f} - item entities already being announced. */
	private final Set<UUID> announced = new HashSet<>();

	private final List<DamageVeto> damageVetoes = new ArrayList<>();
	private final List<AttackVeto> attackVetoes = new ArrayList<>();
	private final List<BlockVeto> blockVetoes = new ArrayList<>();
	private final List<BlockBreakListener> breakListeners = new ArrayList<>();

	public WeaponProtection(AltarSMPMod mod) {
		this.mod = mod;
		this.config = mod.config();
	}

	// ------------------------------------------------------------------ config

	/** {@code weapon-protection.enabled}. */
	public boolean enabled() {
		return this.config.getBoolean("weapon-protection.enabled", true);
	}

	/** {@code weapon-protection.burn-protection}, only while protection is on. */
	public boolean burnProtected() {
		return enabled() && this.config.getBoolean("weapon-protection.burn-protection", true);
	}

	/** {@code weapon-destruction-protection.enabled} - off by default, as upstream. */
	public boolean destructionProtected() {
		return this.config.getBoolean("weapon-destruction-protection.enabled", false);
	}

	/** {@code weapon-protection.containers.<kind>}. */
	public boolean containerBlocked(String kind) {
		return this.config.getBoolean("weapon-protection.containers." + kind, true);
	}

	/** {@code weapon-protection.containers.bundle}. */
	public boolean bundleBlocked() {
		return containerBlocked("bundle");
	}

	// ------------------------------------------------------------ pvp toggle

	public boolean isPvpEnabled() {
		return this.pvpEnabled;
	}

	/**
	 * {@code PvpToggleCommand#onCommand} - flips or sets the toggle and broadcasts
	 * the result to every player.
	 *
	 * @param value {@code null} to flip the current state
	 */
	public boolean setPvpEnabled(@Nullable Boolean value) {
		this.pvpEnabled = value != null ? value : !this.pvpEnabled;
		MinecraftServer server = this.mod.server();
		if (server != null) {
			String state = this.pvpEnabled ? "<green><bold>ENABLED" : "<red><bold>DISABLED";
			Messaging.broadcast(server, "<dark_purple>[AltarSMP] <gray>PVP has been " + state + "<gray>!");
		}
		return this.pvpEnabled;
	}

	// ------------------------------------------------------- extension points

	/** A system that can refuse damage to a player (ban zones, altar areas, factions). */
	public interface DamageVeto {
		String id();

		boolean veto(ServerPlayer victim, DamageSource source, float amount, @Nullable ServerPlayer attacker);
	}

	/** A system that can refuse an attack a player starts (altar hitboxes, ban zones). */
	public interface AttackVeto {
		String id();

		boolean veto(ServerPlayer attacker, Entity target);
	}

	/** A system that can refuse a block change at a position (the BUILD flag's job). */
	public interface BlockVeto {
		String id();

		boolean veto(ServerPlayer player, ServerLevel level, BlockPos pos);
	}

	/** A system that reacts after a block was broken (altar cleanup, trial progress). */
	public interface BlockBreakListener {
		void onBlockBroken(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state);
	}

	public void register(DamageVeto veto) {
		this.damageVetoes.add(veto);
	}

	public void register(AttackVeto veto) {
		this.attackVetoes.add(veto);
	}

	public void register(BlockVeto veto) {
		this.blockVetoes.add(veto);
	}

	public void register(BlockBreakListener listener) {
		this.breakListeners.add(listener);
	}

	/** Status line for the admin commands - never a silent configuration. */
	public String status() {
		return "protection=" + (enabled() ? "on" : "off")
				+ ", burn=" + (burnProtected() ? "on" : "off")
				+ ", destruction=" + (destructionProtected() ? "on" : "off")
				+ ", pvp=" + (this.pvpEnabled ? "enabled" : "disabled")
				+ ", vetoes=" + this.damageVetoes.size() + "/" + this.attackVetoes.size() + "/"
				+ this.blockVetoes.size();
	}

	// ------------------------------------------------------------------ combat

	/**
	 * Step 3 of {@code CombatHooks}: the victim-side veto every protection system
	 * gets. Bukkit ran these as separate HIGHEST-priority listeners that each called
	 * {@code setCancelled(true)}.
	 */
	public boolean blockDamage(ServerPlayer victim, DamageSource source, float amount, @Nullable ServerPlayer attacker) {
		ServerPlayer responsible = attacker != null ? attacker : sourceAttacker(source);
		if (responsible != null && !responsible.equals(victim) && !this.pvpEnabled) {
			// PvpProtection#onPlayerDamage
			tellPvpDisabled(responsible);
			return true;
		}
		for (DamageVeto veto : this.damageVetoes) {
			if (veto.veto(victim, source, amount, responsible)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Left-clicking an entity, from {@code AttackEntityCallback}. Cancelling here is
	 * Bukkit's {@code EntityDamageByEntityEvent#setCancelled(true)} one step earlier:
	 * the swing never lands, so no knockback, no sound and no weapon ability fires.
	 */
	public boolean blockAttack(ServerPlayer attacker, Entity target, ItemStack held) {
		if (target instanceof ServerPlayer victim && !victim.equals(attacker) && !this.pvpEnabled) {
			tellPvpDisabled(attacker);
			return true;
		}
		for (AttackVeto veto : this.attackVetoes) {
			if (veto.veto(attacker, target)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code PvpProtection#onPotionSplash} / {@code #onAreaEffectCloud}: a harmful
	 * cloud or splash thrown by a player must not touch other players while PvP is
	 * off. Returns the entities that have to be removed from the affected list.
	 */
	public boolean removePvpVictims(@Nullable ServerPlayer thrower, List<ServerPlayer> affected) {
		if (this.pvpEnabled || thrower == null) {
			return false;
		}
		return affected.removeIf(other -> !other.equals(thrower));
	}

	/** {@code PvpProtection#isNegativeEffect}, keyed on the effect's registry id. */
	public static boolean isHarmfulEffect(String effectId) {
		String id = effectId.toLowerCase(Locale.ROOT);
		return id.contains("poison") || id.contains("harm") || id.contains("instant_damage")
				|| id.contains("slowness") || id.contains("weakness") || id.contains("wither")
				|| id.contains("blindness") || id.contains("nausea") || id.contains("hunger")
				|| id.contains("mining_fatigue") || id.contains("levitation") || id.contains("bad_omen")
				|| id.contains("darkness");
	}

	private void tellPvpDisabled(ServerPlayer attacker) {
		long now = System.currentTimeMillis();
		Long last = this.lastPvpMessage.get(attacker.getUUID());
		if (last != null && now - last < PVP_MESSAGE_THROTTLE_MILLIS) {
			return;
		}
		this.lastPvpMessage.put(attacker.getUUID(), now);
		Messaging.actionBar(attacker, "<red>PvP is disabled.");
	}

	@Nullable
	private static ServerPlayer sourceAttacker(DamageSource source) {
		Entity direct = source.getDirectEntity();
		if (direct instanceof ServerPlayer player) {
			return player;
		}
		return source.getEntity() instanceof ServerPlayer owner ? owner : null;
	}

	// ------------------------------------------------------------------- world

	/**
	 * The port's answer to {@code a.D#b(player, location)} - WorldGuard's BUILD flag.
	 * Tidebreaker's water placement and every other ability that changes blocks asks
	 * this first.
	 */
	public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
		return allowBlockChange(player, player.serverLevel(), pos);
	}

	/** {@code PlayerBlockBreakEvents.BEFORE} - return {@code false} to keep the block. */
	public boolean allowBlockBreak(ServerPlayer player, BlockPos pos, BlockState state) {
		return allowBlockChange(player, player.serverLevel(), pos);
	}

	private boolean allowBlockChange(ServerPlayer player, ServerLevel level, BlockPos pos) {
		// Vanilla's own check already exempts operators and empty op lists, so the
		// port does not need a second rule for them.
		MinecraftServer server = level.getServer();
		if (server != null && server.isUnderSpawnProtection(level, pos, player)) {
			return false;
		}
		for (BlockVeto veto : this.blockVetoes) {
			if (veto.veto(player, level, pos)) {
				return false;
			}
		}
		return true;
	}

	/** After a block really broke: altar cleanup and anything else listening. */
	public void onBlockBroken(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		for (BlockBreakListener listener : this.breakListeners) {
			listener.onBlockBroken(player, level, pos, state);
		}
	}

	/**
	 * Join hook. Per-player throttles are dropped so a relog is never silently
	 * muted, and - when destruction protection is on - legendary items already lying
	 * on the ground nearby are made immortal, because a dropped weapon that survives
	 * a restart must not despawn or burn before its owner gets back to it.
	 */
	public void onJoin(ServerPlayer player) {
		this.lastPvpMessage.remove(player.getUUID());
		if (destructionProtected()) {
			ServerLevel level = player.serverLevel();
			AABB around = player.getBoundingBox().inflate(JOIN_SCAN_RADIUS);
			for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, around)) {
				if (isProtected(item.getItem())) {
					item.setExtendedLifetime();
				}
			}
		}
	}

	// ----------------------------------------------------------------- storage

	/**
	 * Bukkit's {@code isWeapon(ItemStack)}: any item carrying one of the altar
	 * identity keys ({@code altarsmp:altar_weapon}, {@code altar_armor},
	 * {@code altar_item}), the season 2 identity or {@code mythicweapons:mythic_weapon}.
	 */
	public boolean isProtected(@Nullable ItemStack stack) {
		return stack != null && !stack.isEmpty() && Identity.isProtectedContentItem(stack);
	}

	/** Bukkit's {@code isBundle(ItemStack)} - the plain bundle and every dyed one. */
	public static boolean isBundle(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if (stack.is(Items.BUNDLE)) {
			return true;
		}
		String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
		return id.endsWith("_bundle");
	}

	/**
	 * Bukkit's {@code isStorage(InventoryType)} mapped onto the containers that
	 * actually exist in a menu slot. Returns the config key that decided, or
	 * {@code null} when the container is not a storage container at all (a player
	 * inventory, a crafting grid, a villager's trades, ...).
	 */
	@Nullable
	public static String storageKind(@Nullable Container container) {
		if (container == null) {
			return null;
		}
		if (container instanceof ChestBlockEntity) {
			return "chest";
		}
		if (container instanceof BarrelBlockEntity) {
			return "barrel";
		}
		if (container instanceof PlayerEnderChestContainer) {
			return "ender_chest";
		}
		if (container instanceof HopperBlockEntity) {
			return "hopper";
		}
		if (container instanceof ShulkerBoxBlockEntity) {
			return "shulker";
		}
		if (container instanceof DropperBlockEntity || container instanceof DispenserBlockEntity
				|| container instanceof AbstractFurnaceBlockEntity || container instanceof BrewingStandBlockEntity
				|| container instanceof CrafterBlockEntity) {
			return "other";
		}
		return null;
	}

	/**
	 * {@code WeaponStoragePrevention#onInventoryClick} / {@code #onInventoryDrag} -
	 * the gate behind {@code Slot#mayPlace}, which vanilla consults for plain clicks,
	 * shift-clicks, drags and number-key swaps alike.
	 */
	public boolean blockSlotPlacement(Slot slot, ItemStack stack) {
		if (!enabled() || !isProtected(stack)) {
			return false;
		}
		String kind = storageKind(slot.container);
		return kind != null && containerBlocked(kind);
	}

	/**
	 * The hopper/dropper/dispenser route ({@code InventoryMoveItemEvent}): automated
	 * movement of a legendary into or out of a storage container is refused.
	 */
	public boolean blockAutomatedMove(@Nullable Container target, @Nullable Container source, ItemStack stack) {
		if (!enabled() || !isProtected(stack)) {
			return false;
		}
		String into = storageKind(target);
		String outOf = storageKind(source);
		return (into != null && containerBlocked(into)) || (outOf != null && containerBlocked(outOf));
	}

	/**
	 * The bundle routes ({@code BundleItem#overrideStackedOnOther} and
	 * {@code #overrideOtherStackedOnMe}): a legendary cannot be stuffed into a
	 * bundle, and a bundle cannot be stuffed into a legendary.
	 */
	public boolean blockBundle(ItemStack bundle, ItemStack other) {
		if (!enabled() || !bundleBlocked()) {
			return false;
		}
		return (isBundle(bundle) && isProtected(other)) || (isBundle(other) && isProtected(bundle));
	}

	/**
	 * {@code WeaponStoragePrevention#onItemFrameInteract} - an empty frame must not
	 * accept a legendary.
	 */
	public boolean blockItemFrame(ServerPlayer player, Entity frame, InteractionHand hand) {
		if (!enabled()) {
			return false;
		}
		if (!(frame instanceof net.minecraft.world.entity.decoration.ItemFrame itemFrame)
				|| !itemFrame.getItem().isEmpty()) {
			return false;
		}
		return isProtected(player.getItemInHand(hand));
	}

	// --------------------------------------------------- dropped item entities

	/**
	 * {@code #onItemCombust} / {@code #onItemDamage} / {@code #onItemDamageAll},
	 * called from the {@code ItemEntity#hurtServer} mixin.
	 *
	 * @return {@code true} when the damage must not land
	 */
	public boolean protectItemEntity(ItemEntity item, DamageSource source) {
		ItemStack stack = item.getItem();
		if (!isProtected(stack)) {
			return false;
		}
		if (destructionProtected()) {
			return true;
		}
		if (!burnProtected()) {
			return false;
		}
		return source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.ON_FIRE) || source.is(DamageTypes.LAVA)
				|| source.is(DamageTypes.CACTUS) || source.is(DamageTypes.FELL_OUT_OF_WORLD);
	}

	/** {@code #onItemDespawn} - a legendary never despawns while destruction protection is on. */
	public boolean preventDespawn(ItemEntity item) {
		return destructionProtected() && isProtected(item.getItem());
	}

	/**
	 * {@code WeaponStoragePrevention#scheduleDestroyAnnouncement}. Upstream built the
	 * message, looped over every online player and then did nothing at all with it;
	 * the broadcast the text obviously asked for is what runs here, once per item.
	 */
	public void announceDestruction(ItemEntity item) {
		if (destructionProtected() || !isProtected(item.getItem())) {
			return;
		}
		UUID id = item.getUUID();
		if (!this.announced.add(id)) {
			return;
		}
		String name = item.getItem().getHoverName().getString();
		this.mod.scheduler().later(() -> {
			this.announced.remove(id);
			if (!item.isRemoved()) {
				return;
			}
			MinecraftServer server = this.mod.server();
			if (server == null) {
				return;
			}
			Messaging.broadcast(server,
					name.isEmpty() ? "<red>A legendary weapon has been destroyed!"
							: "<red>" + name + " <red>has been destroyed!");
		}, DESTROY_CHECK_DELAY_TICKS);
	}

	/** {@code Inventory#getStorageContents()} walk the bundle click handler did. */
	public boolean bundleWithSpaceExists(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (isBundle(inventory.getItem(slot))) {
				return true;
			}
		}
		return false;
	}
}
