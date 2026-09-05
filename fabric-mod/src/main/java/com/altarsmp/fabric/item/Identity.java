package com.altarsmp.fabric.item;

import java.util.Locale;
import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * The AltarSMP item identity layer.
 *
 * <p>Identity is <b>never</b> derived from the display name.  It is read from, in
 * order of preference:</p>
 * <ol>
 *   <li>{@code altarsmp:identity} - the component registered by this mod;</li>
 *   <li>{@code minecraft:custom_data} entries written by the Bukkit plugin:
 *       {@code altarsmp:altar_weapon}, {@code altarsmp:altar_item},
 *       {@code altarsmp:altar_armor}, {@code altarsmps2:s2_id},
 *       {@code mythicweapons:mythic_weapon}.</li>
 * </ol>
 *
 * <p>Aliases used by the original sources are normalised here (for example
 * {@code paladin_axe}/{@code paladinbattleaxe}, {@code vulcans_crossbow}/
 * {@code vulcan}, {@code eclipse}/{@code eclipse_sword}/{@code eclipsesword},
 * {@code ancientblade}/{@code ancient_blade}).  They come from the plugin's own
 * command names, tooltip keys and config keys - nothing is invented to paper over
 * a lookup failure.</p>
 */
public final class Identity {

	// ---- legacy PersistentDataContainer keys (Bukkit namespace:path strings) ----
	public static final String KEY_WEAPON = "altarsmp:altar_weapon";
	public static final String KEY_ITEM = "altarsmp:altar_item";
	public static final String KEY_ARMOR = "altarsmp:altar_armor";
	public static final String KEY_S2 = "altarsmps2:s2_id";
	public static final String KEY_MYTHIC = "mythicweapons:mythic_weapon";

	// ---- legacy per-item state keys -------------------------------------------
	public static final String KEY_KNIGHTFALL_KILLS = "altarsmp:knightfall_kills";
	public static final String KEY_BLOCKS_MINED = "altarsmp:blocks_mined";
	public static final String KEY_CRAZY_SLOTS_TRANSFORM = "altarsmp:crazy_slots_transform_id";
	public static final String KEY_MINOR_CRAZY_ROLL = "altarsmp:minor_crazy_slots_roll";
	public static final String KEY_HOT_POTATO = "altarsmp:hot_potato";
	public static final String KEY_BINGO_BOOK = "altarsmp:bingo_book";
	public static final String KEY_PLAYER_TRACKER = "altarsmp:player_tracker";
	public static final String KEY_AB_KILLS = "altarsmps2:ab_kills";

	private Identity() {
	}

	/** Canonical id of the stack, or {@code null} when it is not AltarSMP content. */
	@Nullable
	public static String idOf(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		String direct = stack.get(ModComponents.IDENTITY);
		if (direct != null && !direct.isBlank()) {
			return normalise(direct);
		}
		String legacy = legacyTag(stack);
		return legacy == null ? null : normalise(legacy);
	}

	@Nullable
	private static String legacyTag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag tag = data.copyTag();
		for (String key : new String[]{KEY_WEAPON, KEY_ITEM, KEY_ARMOR, KEY_S2, KEY_MYTHIC}) {
			if (tag.contains(key, 8)) {
				String value = tag.getString(key);
				if (value != null && !value.isBlank()) {
					return value;
				}
			}
		}
		return null;
	}

	public static boolean is(ItemStack stack, String contentId) {
		String id = idOf(stack);
		return id != null && contentId != null && id.equals(normalise(contentId));
	}

	/** True for any AltarSMP/AltarSMPS2 legendary weapon. */
	public static boolean isWeapon(ItemStack stack) {
		String id = idOf(stack);
		return id != null && ContentCatalog.isWeaponId(id);
	}

	/** True for any content the protection system must guard. */
	public static boolean isProtectedContent(ItemStack stack) {
		String id = idOf(stack);
		if (id == null) {
			return false;
		}
		return ContentCatalog.isWeaponId(id) || ContentCatalog.isProtectedItemId(id);
	}

	public static boolean hasLegacyTag(ItemStack stack, String key) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(key);
	}

	public static String legacyString(ItemStack stack, String key, String def) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return def;
		}
		CompoundTag tag = data.copyTag();
		return tag.contains(key, 8) ? tag.getString(key) : def;
	}

	public static int legacyInt(ItemStack stack, String key, int def) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return def;
		}
		CompoundTag tag = data.copyTag();
		return tag.contains(key, 99) ? tag.getInt(key) : def;
	}

	/** Writes a legacy PDC key into {@code minecraft:custom_data} (in place). */
	public static void putLegacy(ItemStack stack, String key, String value) {
		CompoundTag tag = customTag(stack);
		tag.putString(key, value);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	public static void putLegacy(ItemStack stack, String key, int value) {
		CompoundTag tag = customTag(stack);
		tag.putInt(key, value);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	public static void removeLegacy(ItemStack stack, String key) {
		CompoundTag tag = customTag(stack);
		if (tag.remove(key) != null) {
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		}
	}

	public static CompoundTag customTag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? new CompoundTag() : data.copyTag();
	}

	// ---- structured state ------------------------------------------------------

	public static CompoundTag state(ItemStack stack) {
		CompoundTag tag = stack.get(ModComponents.STATE);
		return tag == null ? new CompoundTag() : tag.copy();
	}

	public static void state(ItemStack stack, CompoundTag tag) {
		stack.set(ModComponents.STATE, tag);
	}

	public static int stateInt(ItemStack stack, String key, int def) {
		CompoundTag tag = stack.get(ModComponents.STATE);
		return tag == null || !tag.contains(key, 99) ? def : tag.getInt(key);
	}

	public static void setStateInt(ItemStack stack, String key, int value) {
		CompoundTag tag = stack.get(ModComponents.STATE) == null ? new CompoundTag() : stack.get(ModComponents.STATE).copy();
		tag.putInt(key, value);
		stack.set(ModComponents.STATE, tag);
	}

	public static long stateLong(ItemStack stack, String key, long def) {
		CompoundTag tag = stack.get(ModComponents.STATE);
		return tag == null || !tag.contains(key, 99) ? def : tag.getLong(key);
	}

	public static void setStateLong(ItemStack stack, String key, long value) {
		CompoundTag tag = stack.get(ModComponents.STATE) == null ? new CompoundTag() : stack.get(ModComponents.STATE).copy();
		tag.putLong(key, value);
		stack.set(ModComponents.STATE, tag);
	}

	public static boolean stateBool(ItemStack stack, String key, boolean def) {
		CompoundTag tag = stack.get(ModComponents.STATE);
		return tag == null || !tag.contains(key, 99) ? def : tag.getBoolean(key);
	}

	public static void setStateBool(ItemStack stack, String key, boolean value) {
		CompoundTag tag = stack.get(ModComponents.STATE) == null ? new CompoundTag() : stack.get(ModComponents.STATE).copy();
		tag.putBoolean(key, value);
		stack.set(ModComponents.STATE, tag);
	}

	public static String stateString(ItemStack stack, String key, String def) {
		CompoundTag tag = stack.get(ModComponents.STATE);
		return tag == null || !tag.contains(key, 8) ? def : tag.getString(key);
	}

	public static void setStateString(ItemStack stack, String key, String value) {
		CompoundTag tag = stack.get(ModComponents.STATE) == null ? new CompoundTag() : stack.get(ModComponents.STATE).copy();
		tag.putString(key, value);
		stack.set(ModComponents.STATE, tag);
	}

	/** Drops one structured-state entry, leaving the rest of the component intact. */
	public static void removeState(ItemStack stack, String key) {
		CompoundTag tag = stack.get(ModComponents.STATE);
		if (tag == null || !tag.contains(key)) {
			return;
		}
		CompoundTag copy = tag.copy();
		copy.remove(key);
		stack.set(ModComponents.STATE, copy);
	}

	/**
	 * Kill counter for a weapon, honouring both the new structured component and
	 * the legacy PDC keys the original plugin wrote.
	 */
	public static int kills(ItemStack stack, String contentId) {
		CompoundTag tag = stack.get(ModComponents.STATE);
		if (tag != null && tag.contains("kills", 99)) {
			return tag.getInt("kills");
		}
		String legacyKey = switch (normalise(contentId)) {
			case "knightfall" -> KEY_KNIGHTFALL_KILLS;
			case "ancientblade" -> KEY_AB_KILLS;
			case "bloodlust" -> "altarsmp:bloodlust_kills";
			default -> null;
		};
		if (legacyKey != null) {
			return legacyInt(stack, legacyKey, 0);
		}
		return 0;
	}

	public static void setKills(ItemStack stack, int value) {
		CompoundTag tag = stack.get(ModComponents.STATE) == null ? new CompoundTag() : stack.get(ModComponents.STATE).copy();
		tag.putInt("kills", value);
		stack.set(ModComponents.STATE, tag);
	}

	// ---- alias normalisation ---------------------------------------------------

	/**
	 * Maps every spelling the original sources used onto one canonical id.
	 * All of these appear verbatim in the plugin (command names, tooltip keys,
	 * config keys, PDC values).
	 */
	public static String normalise(String raw) {
		if (raw == null) {
			return "";
		}
		String id = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		return switch (id) {
			case "paladin_axe", "paladinaxe", "paladin_battle_axe", "paladins_battle_axe", "paladinbattle_axe" -> "paladinbattleaxe";
			case "vulcan", "vulcans_crossbow", "vulcan_crossbow", "vulcanscrossbow" -> "vulcanscrossbow";
			case "eclipse", "eclipse_sword" -> "eclipsesword";
			case "ancientblade", "ancient_blade" -> "ancientblade";
			case "frost_scythe", "frostscythe" -> "frostscythe";
			case "nuke_launcher", "nukelauncher" -> "nukelauncher";
			case "fire_slash", "fireslash" -> "fireslash";
			case "minor_crazy_slots", "minorcrazyslots" -> "minorcrazyslots";
			case "crazy_slots", "crazyslots" -> "crazyslots";
			case "bone_blade", "boneblade" -> "boneblade";
			case "shadow_blade", "shadowblade" -> "shadowblade";
			case "pure_blade", "pureblade" -> "pureblade";
			case "earth_gauntlet", "earthgauntlet" -> "earthgauntlet";
			case "pale_crossbow", "palecrossbow" -> "palecrossbow";
			case "wand_of_illusion", "wandofillusion", "wand" -> "wandofillusion";
			case "contagion_signal", "contagionsignal" -> "contagionsignal";
			case "wither_bone", "witherbone", "witherboneblade", "wither_bone_blade" -> "witherbone";
			case "bow_of_deception", "bow_of_deception_and_lies", "bowofdeceptionandlies", "bowofdeception" -> "bowofdeception";
			case "wither_symbiote", "withersymbiote" -> "withersymbiote";
			case "dragon_rend", "dragonrend" -> "dragonrend";
			case "copper_helmet", "copperhelmet" -> "copperhelmet";
			case "copper_chestplate", "copperchestplate" -> "copperchestplate";
			case "copper_leggings", "copperleggings" -> "copperleggings";
			case "copper_boots", "copperboots" -> "copperboots";
			case "copper_pickaxe_ii", "copperpickaxeii", "copper_pickaxe_upgrade", "copperpickaxeupgrade" -> "copperpickaxeii";
			case "copper_pickaxe", "copperpickaxe" -> "copperpickaxe";
			case "weapon_handle", "weaponhandle" -> "weaponhandle";
			case "warden_heart", "wardenheart" -> "wardenheart";
			case "illusion_core", "illusioncore" -> "illusioncore";
			case "hyperion_shard", "hyperionshard" -> "hyperionshard";
			case "nightpiercer_shard", "nightpiercershard" -> "nightpiercershard";
			case "pale_shard", "paleshard" -> "paleshard";
			case "vulkan_head", "vulkanhead" -> "vulkanhead";
			case "warden_head", "wardenhead" -> "wardenhead";
			case "copper_fragment", "copperfragment" -> "copperfragment";
			case "chestplate_shard", "chestplateshard", "copper_chestplate_fragment", "copperchestplatefragment" -> "chestplateshard";
			case "player_tracker", "playertracker" -> "playertracker";
			case "soul_in_a_bottle", "soulinabottle" -> "soulinabottle";
			case "fragment_of_the_sea", "fragmentofthesea" -> "fragmentofthesea";
			case "dragon_heart", "dragonheart" -> "dragonheart";
			case "amethyst_pickaxe", "amethystpickaxe" -> "amethystpickaxe";
			case "amethyst_axe", "amethystaxe" -> "amethystaxe";
			case "black_ghast_saddle", "blackghastsaddle" -> "blackghastsaddle";
			default -> id;
		};
	}

	public static Optional<String> optionalId(ItemStack stack) {
		return Optional.ofNullable(idOf(stack));
	}

	public static void debug(ItemStack stack) {
		AltarSMPMod.LOGGER.info("[identity] {} -> id={} legacyTag={}", stack.getItem(), idOf(stack), legacyTag(stack));
	}
}
