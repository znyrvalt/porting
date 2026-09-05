package com.altarsmp.fabric.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.config.ConfigView;
import com.altarsmp.fabric.item.ContentCatalog;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.util.TextFx;

/**
 * The altar recipe table - a port of {@code a/b.java} + {@code a/a.java} (Season 1)
 * and {@code a/u.java} + the counting half of {@code a/s.java} (Season 2).
 *
 * <p>A recipe is an ordered list of ingredient keys and amounts, read from the
 * {@code recipes.<id>} section of {@code config.yml} (Season 1) or {@code s2.yml}
 * (Season 2), with the bundled {@code content/recipes.json} catalog as the fallback
 * when a section is missing. Keys come in three flavours, and the two seasons count
 * them differently - both behaviours are preserved:
 *
 * <ul>
 *   <li><b>custom_*</b> - an AltarSMP item. Season 1 matched it by display name;
 *       Season 2 by a per-item predicate ({@code WeaponHandle::is} and friends). The
 *       port accepts either, so an item created by this mod always matches and a
 *       hand-renamed legacy item still does.</li>
 *   <li><b>PLAYER_HEAD</b> - Season 1 counted only heads whose name contains
 *       "Head" (the warden/vulkan heads); Season 2 counted every player head.</li>
 *   <li><b>anything else</b> - a vanilla material. Season 1 counted only
 *       <em>unnamed</em> stacks, so a renamed block of gold never satisfied an altar;
 *       Season 2 counted every stack of the item.</li>
 * </ul>
 *
 * <p>Crafting is transactional in the only sense the plugin had: {@link #craft} checks
 * the whole table first and consumes nothing unless every ingredient is present, which
 * is what every {@code *AltarInteract} class did by calling the checker before the
 * remover. {@code DRAGON_EGG} is listed in the {@code crazyslots} and
 * {@code shadowblade} recipes but is never taken - {@code a/a.java} excludes it
 * explicitly, and {@code content/ingredients.json} carries that list.
 */
public final class RecipeRegistry {

	private final AltarSMPMod mod;
	private final Map<String, Map<String, Integer>> cache = new LinkedHashMap<>();

	public RecipeRegistry(AltarSMPMod mod) {
		this.mod = mod;
	}

	/**
	 * Resolves every altar recipe once so a missing or unparseable table is reported
	 * at startup instead of at the altar.
	 */
	public void registerAll() {
		int resolved = 0;
		int empty = 0;
		for (String recipeId : ContentCatalog.recipeIds()) {
			Map<String, Integer> table = ingredients(recipeId);
			if (table.isEmpty()) {
				empty++;
				AltarSMPMod.LOGGER.warn("[AltarSMP] recipe '{}' has no ingredients - its altar can be crafted empty",
						recipeId);
				continue;
			}
			for (String key : table.keySet()) {
				if (ContentCatalog.isCustomIngredient(key)) {
					String catalogId = ContentCatalog.customIngredientId(key);
					if (catalogId == null) {
						AltarSMPMod.LOGGER.warn(
								"[AltarSMP] recipe '{}' wants custom ingredient '{}' but nothing in the catalog produces it"
										+ " - it will be matched by display name only",
								recipeId, key);
					}
					continue;
				}
				if (resolveItem(key) == null) {
					AltarSMPMod.LOGGER.warn("[AltarSMP] recipe '{}' lists unknown material '{}' - it can never be satisfied",
							recipeId, key);
				}
			}
			resolved++;
		}
		AltarSMPMod.LOGGER.info("[AltarSMP] recipes: {} resolved, {} empty, {} custom ingredient keys",
				resolved, empty, ContentCatalog.recipeIds().size());
	}

	/** Drops the cached tables; called by {@code /altarsmp reload}. */
	public void reload() {
		this.cache.clear();
	}

	// ---------------------------------------------------------------- the table

	/** {@code a/b.java#b(plugin, recipe)} / {@code a/u.java#b}. */
	public Map<String, Integer> ingredients(String recipeId) {
		Map<String, Integer> cached = this.cache.get(recipeId);
		if (cached != null) {
			return cached;
		}
		Map<String, Integer> table = new LinkedHashMap<>();
		for (int season : new int[]{1, 2}) {
			ConfigView config = season == 2 ? this.mod.config().s2() : this.mod.config().main();
			for (String key : config.getKeys("recipes." + recipeId)) {
				table.put(key, config.getInt("recipes." + recipeId + "." + key, 0));
			}
			if (!table.isEmpty()) {
				break;
			}
		}
		if (table.isEmpty()) {
			Map<String, Integer> catalog = ContentCatalog.recipe(recipeId);
			if (catalog != null) {
				table.putAll(catalog);
			}
		}
		Map<String, Integer> frozen = Collections.unmodifiableMap(table);
		this.cache.put(recipeId, frozen);
		return frozen;
	}

	/** {@code a/b.java#a(plugin, recipe, key, fallback)} - one configurable amount. */
	public int amount(String recipeId, String key, int fallback) {
		Map<String, Integer> table = ingredients(recipeId);
		Integer value = table.get(key);
		return value == null ? fallback : value;
	}

	/** Which season's rules apply to a recipe: 2 for the {@code s2.yml} tables, else 1. */
	public int seasonOf(String recipeId) {
		Map<String, Integer> s2 = ContentCatalog.s2Recipe(recipeId);
		return s2 != null && !s2.isEmpty() ? 2 : 1;
	}

	/**
	 * {@code a/b.java#a(plugin, recipe)} - the hologram lines under an altar's name:
	 * {@code <dark_gray>64x<white> Gold Block}, with custom ingredients in red.
	 */
	public List<String> hologramLines(String recipeId) {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : ingredients(recipeId).entrySet()) {
			int amount = entry.getValue();
			if (amount <= 0) {
				continue;
			}
			String key = entry.getKey();
			if (ContentCatalog.isCustomIngredient(key)) {
				lines.add("<dark_gray>" + amount + "x<red> " + ContentCatalog.customIngredientName(key));
			} else if (key.equals("PLAYER_HEAD")) {
				lines.add("<dark_gray>" + amount + "x<white> Player Head");
			} else {
				lines.add("<dark_gray>" + amount + "x<white> " + ContentCatalog.prettyMaterial(key));
			}
		}
		return lines;
	}

	// ------------------------------------------------------------ check + take

	/**
	 * {@code a/a.java#a(plugin, player, recipe)} - the "Missing items to craft X"
	 * lines, empty when the player can craft. Colours follow the Season 1 rules
	 * (custom ingredients in red) or the Season 2 rules (everything in white).
	 */
	public List<String> missing(ServerPlayer player, String recipeId, int season) {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : ingredients(recipeId).entrySet()) {
			String key = entry.getKey();
			int need = entry.getValue();
			if (season == 1 && need <= 0) {
				continue;
			}
			int have = count(player, key, season);
			if (have >= need) {
				continue;
			}
			if (season == 2) {
				lines.add("<gray>- <white>" + nameOf(key) + ": <red>" + have + "/" + need);
				continue;
			}
			if (ContentCatalog.isCustomIngredient(key)) {
				lines.add("<gray>- <red>" + ContentCatalog.customIngredientName(key) + ": <red>" + have + "/" + need);
			} else if (key.equals("PLAYER_HEAD")) {
				lines.add("<gray>- <white>Player Head: <red>" + have + "/" + need);
			} else {
				lines.add("<gray>- <white>" + ContentCatalog.prettyMaterial(key) + ": <red>" + have + "/" + need);
			}
		}
		return lines;
	}

	/** {@code a/b.java#b} display naming: custom name, "Player Head", or Title Case. */
	private static String nameOf(String key) {
		if (ContentCatalog.isCustomIngredient(key)) {
			return ContentCatalog.customIngredientName(key);
		}
		return key.equals("PLAYER_HEAD") ? "Player Head" : ContentCatalog.prettyMaterial(key);
	}

	/** True when nothing is missing. */
	public boolean canCraft(ServerPlayer player, String recipeId, int season) {
		return missing(player, recipeId, season).isEmpty();
	}

	/**
	 * Checks the whole table and only then takes the ingredients - the transactional
	 * rule every altar relied on.
	 *
	 * @return {@code true} when the ingredients were taken
	 */
	public boolean craft(ServerPlayer player, String recipeId, int season) {
		if (!missing(player, recipeId, season).isEmpty()) {
			return false;
		}
		consume(player, recipeId, season);
		return true;
	}

	/** {@code a/a.java#b(plugin, player, recipe)} / {@code a/u.java}'s consume loop. */
	public void consume(ServerPlayer player, String recipeId, int season) {
		for (Map.Entry<String, Integer> entry : ingredients(recipeId).entrySet()) {
			String key = entry.getKey();
			int amount = entry.getValue();
			if (season == 1 && (amount <= 0 || ContentCatalog.isNeverConsumed(key))) {
				continue;
			}
			remove(player, key, amount, season);
		}
	}

	// ----------------------------------------------------------------- counting

	/** How many of one ingredient key the player carries, by that season's rules. */
	public int count(ServerPlayer player, String key, int season) {
		if (ContentCatalog.isCustomIngredient(key)) {
			return countMatching(player, customMatcher(key));
		}
		if (key.equals("PLAYER_HEAD") && season == 1) {
			return countMatching(player, RecipeRegistry::isNamedHead);
		}
		Item item = resolveItem(key);
		if (item == null) {
			return 0;
		}
		boolean unnamedOnly = season == 1;
		return countMatching(player, stack -> stack.is(item) && (!unnamedOnly || !hasDisplayName(stack)));
	}

	/** Takes {@code amount} of one ingredient key, by that season's rules. */
	public void remove(ServerPlayer player, String key, int amount, int season) {
		if (amount <= 0) {
			return;
		}
		if (ContentCatalog.isCustomIngredient(key)) {
			removeMatching(player, customMatcher(key), amount);
			return;
		}
		if (key.equals("PLAYER_HEAD") && season == 1) {
			removeMatching(player, RecipeRegistry::isNamedHead, amount);
			return;
		}
		Item item = resolveItem(key);
		if (item == null) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] cannot take unknown material '{}' from {}", key,
					player.getGameProfile().getName());
			return;
		}
		boolean unnamedOnly = season == 1;
		removeMatching(player, stack -> stack.is(item) && (!unnamedOnly || !hasDisplayName(stack)), amount);
	}

	/**
	 * The Season 2 predicates ({@code WeaponHandle::is}, {@code DragonHeart::is}, ...)
	 * plus the Season 1 display-name rule, so either form of the item is accepted.
	 */
	public static Predicate<ItemStack> customMatcher(String key) {
		@Nullable String catalogId = ContentCatalog.customIngredientId(key);
		String name = ContentCatalog.customIngredientName(key);
		return stack -> {
			if (stack.isEmpty()) {
				return false;
			}
			if (catalogId != null && Identity.is(stack, catalogId)) {
				return true;
			}
			String stripped = displayName(stack);
			if (stripped == null) {
				return false;
			}
			// a/u.java accepts both spellings of the Warden's Heart.
			return stripped.equals(name) || stripped.equals("Warden Heart") && name.equals("Warden's Heart");
		};
	}

	/** {@code PLAYER_HEAD} with a name containing "Head" - the Season 1 head rule. */
	public static boolean isNamedHead(ItemStack stack) {
		String name = displayName(stack);
		return stack.is(Items.PLAYER_HEAD) && name != null && name.contains("Head");
	}

	public static int countMatching(ServerPlayer player, Predicate<ItemStack> matcher) {
		Inventory inventory = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && matcher.test(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * {@code a/s.java#a(player, predicate, amount)} and {@code a/a.java}'s removers:
	 * shrink stacks in place, clear them when emptied, stop as soon as the debt is paid.
	 */
	public static void removeMatching(ServerPlayer player, Predicate<ItemStack> matcher, int amount) {
		Inventory inventory = player.getInventory();
		int remaining = amount;
		for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || !matcher.test(stack)) {
				continue;
			}
			int have = stack.getCount();
			if (have > remaining) {
				stack.shrink(remaining);
				remaining = 0;
			} else {
				remaining -= have;
				inventory.setItem(slot, ItemStack.EMPTY);
			}
		}
		inventory.setChanged();
	}

	// --------------------------------------------------- helpers used by altars

	/**
	 * {@code AltarManager#removeItems} - takes {@code amount} of a material counting
	 * every stack, named or not. The Player Tracker altar uses this instead of the
	 * recipe engine, exactly as upstream.
	 */
	public static void removeMaterial(ServerPlayer player, String materialKey, int amount) {
		Item item = resolveItem(materialKey);
		if (item == null) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] cannot remove unknown material '{}'", materialKey);
			return;
		}
		removeMatching(player, stack -> stack.is(item), amount);
	}

	/** {@code PlayerTrackerAltarInteract#countItems} / {@code Inventory#containsAtLeast}. */
	public static int countMaterial(ServerPlayer player, String materialKey) {
		Item item = resolveItem(materialKey);
		return item == null ? 0 : countMatching(player, stack -> stack.is(item));
	}

	/** {@code AltarManager#hasCustomItem} - a named item of a given material. */
	public static boolean hasCustomItem(ServerPlayer player, String displayName, @Nullable String materialKey) {
		Item item = materialKey == null ? null : resolveItem(materialKey);
		String wanted = TextFx.strip(displayName);
		return countMatching(player, stack -> (item == null || stack.is(item))
				&& wanted.equals(displayName(stack))) > 0;
	}

	/** {@code AltarManager#removeCustomItem}. */
	public static void removeCustomItem(ServerPlayer player, String displayName, int amount) {
		String wanted = TextFx.strip(displayName);
		removeMatching(player, stack -> wanted.equals(displayName(stack)), amount);
	}

	/**
	 * Bukkit's {@code Material.valueOf}/{@code matchMaterial} for the keys used in the
	 * recipe tables: the upper-case material name is the item's registry name.
	 */
	@Nullable
	public static Item resolveItem(String materialKey) {
		if (materialKey == null || materialKey.isEmpty()) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.get(Identifier.parse(materialKey.toLowerCase(Locale.ROOT)));
		return item == null || item == Items.AIR ? null : item;
	}

	/** The stripped display name, or {@code null} for an unnamed stack. */
	@Nullable
	public static String displayName(ItemStack stack) {
		net.minecraft.network.chat.Component name = stack.get(DataComponents.CUSTOM_NAME);
		return name == null ? null : TextFx.strip(name.getString());
	}

	private static boolean hasDisplayName(ItemStack stack) {
		return stack.has(DataComponents.CUSTOM_NAME);
	}

}
