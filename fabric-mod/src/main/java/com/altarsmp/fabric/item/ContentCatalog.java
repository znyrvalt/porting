package com.altarsmp.fabric.item;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * The authoritative AltarSMP content catalogue.
 *
 * <p>Everything here was extracted mechanically from the original decompiled
 * plugin by {@code tools/extract_content.py} and {@code tools/extract_altars.py}
 * (see {@code docs/AUDIT-*.md}): weapon display names and lore, base materials,
 * custom model data, tooltip style keys, rarity, unbreakable flags, enchantment
 * tables with their original config paths and defaults, the altar table (display
 * name, colour, floating-item model, material) and the full altar recipe table
 * from {@code config.yml} / {@code s2.yml}.</p>
 *
 * <p>Loading content from data rather than hard-coding it in Java is what keeps
 * the port honest: the numbers in the jar are the numbers in the plugin.</p>
 */
public final class ContentCatalog {

	private static final Map<String, WeaponDef> WEAPONS = new LinkedHashMap<>();
	private static final Map<String, ItemDef> ITEMS = new LinkedHashMap<>();
	private static final Map<String, ArmorDef> ARMOR = new LinkedHashMap<>();
	private static final Map<String, AltarDef> ALTARS = new LinkedHashMap<>();
	private static final Map<String, Map<String, Integer>> RECIPES = new LinkedHashMap<>();
	private static final Map<String, Map<String, Integer>> RECIPES_S2 = new LinkedHashMap<>();
	private static final Map<String, String> CUSTOM_INGREDIENT_NAMES = new LinkedHashMap<>();
	private static final Set<String> NEVER_CONSUMED = new LinkedHashSet<>();
	private static final Set<String> PROTECTED_ITEMS = new LinkedHashSet<>();

	private static boolean loaded;

	private ContentCatalog() {
	}

	public static void initialize() {
		if (loaded) {
			return;
		}
		loadWeapons("altarsmp/content/weapons_s1.json");
		loadWeapons("altarsmp/content/weapons_s2.json");
		loadItems("altarsmp/content/items.json");
		loadArmor("altarsmp/content/armor.json");
		loadAltars("altarsmp/content/altars.json");
		loadRecipes("altarsmp/content/recipes.json");
		loadIngredients("altarsmp/content/ingredients.json");
		registerProtectedItems();
		loaded = true;
		AltarSMPMod.LOGGER.info("[AltarSMP] content catalogue: {} weapons, {} items, {} armor, {} altars, {} recipes",
				WEAPONS.size(), ITEMS.size(), ARMOR.size(), ALTARS.size(), RECIPES.size() + RECIPES_S2.size());
	}

	private static void registerProtectedItems() {
		// The original protected every legendary weapon plus the crafted component
		// items (Weapon Handle, Warden's Heart, shards, cores, copper fragments).
		PROTECTED_ITEMS.addAll(WEAPONS.keySet());
		PROTECTED_ITEMS.addAll(ITEMS.keySet());
		PROTECTED_ITEMS.addAll(ARMOR.keySet());
	}

	@Nullable
	private static JsonObject readObject(String resource) {
		try (InputStream in = ContentCatalog.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				AltarSMPMod.LOGGER.error("[AltarSMP] missing bundled content table '{}'", resource);
				return null;
			}
			JsonElement element = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			return element.isJsonObject() ? element.getAsJsonObject() : null;
		} catch (RuntimeException | java.io.IOException e) {
			AltarSMPMod.LOGGER.error("[AltarSMP] could not read content table '{}'", resource, e);
			return null;
		}
	}

	@Nullable
	private static JsonArray readArray(String resource) {
		try (InputStream in = ContentCatalog.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				AltarSMPMod.LOGGER.error("[AltarSMP] missing bundled content table '{}'", resource);
				return null;
			}
			JsonElement element = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			return element.isJsonArray() ? element.getAsJsonArray() : null;
		} catch (RuntimeException | java.io.IOException e) {
			AltarSMPMod.LOGGER.error("[AltarSMP] could not read content table '{}'", resource, e);
			return null;
		}
	}

	private static void loadWeapons(String resource) {
		JsonArray array = readArray(resource);
		if (array == null) {
			return;
		}
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject o = element.getAsJsonObject();
			String id = str(o, "id");
			if (id == null) {
				continue;
			}
			List<EnchantSpec> enchants = new ArrayList<>();
			JsonArray ea = o.has("enchants") && o.get("enchants").isJsonArray() ? o.getAsJsonArray("enchants") : null;
			if (ea != null) {
				for (JsonElement ee : ea) {
					JsonObject eo = ee.getAsJsonObject();
					enchants.add(new EnchantSpec(str(eo, "enchant"), str(eo, "config"), number(eo, "default")));
				}
			}
			List<String> lore = strings(o, "lore");
			List<ConditionalLore> conditional = new ArrayList<>();
			JsonArray ca = o.has("conditional_lore") && o.get("conditional_lore").isJsonArray() ? o.getAsJsonArray("conditional_lore") : null;
			if (ca != null) {
				for (JsonElement ce : ca) {
					JsonObject co = ce.getAsJsonObject();
					List<Condition> conditions = new ArrayList<>();
					JsonArray wa = co.has("when_all") ? co.getAsJsonArray("when_all") : null;
					if (wa != null) {
						for (JsonElement we : wa) {
							JsonObject wo = we.getAsJsonObject();
							conditions.add(new Condition(str(wo, "path"), bool(wo, "equals", false)));
						}
					}
					conditional.add(new ConditionalLore(conditions, strings(co, "lines")));
				}
			}
			WeaponDef def = new WeaponDef(
					id,
					o.has("season") ? o.get("season").getAsInt() : 1,
					str(o, "display_name"),
					str(o, "base_material"),
					o.has("custom_model_data") && !o.get("custom_model_data").isJsonNull() ? o.get("custom_model_data").getAsInt() : null,
					str(o, "tooltip_style"),
					str(o, "rarity"),
					bool(o, "unbreakable", true),
					Collections.unmodifiableList(enchants),
					Collections.unmodifiableList(lore),
					Collections.unmodifiableList(conditional),
					str(o, "class"));
			WEAPONS.put(Identity.normalise(id), def);
		}
	}

	private static void loadItems(String resource) {
		JsonArray array = readArray(resource);
		if (array == null) {
			return;
		}
		for (JsonElement element : array) {
			JsonObject o = element.getAsJsonObject();
			String cls = str(o, "class");
			if (cls == null) {
				continue;
			}
			String id = classToId(cls, str(o, "package"));
			ITEMS.put(id, new ItemDef(id, cls, str(o, "base_material"),
					o.has("custom_model_data") && !o.get("custom_model_data").isJsonNull() ? o.get("custom_model_data").getAsInt() : null,
					str(o, "display_name"), str(o, "tooltip_style"), str(o, "rarity"), strings(o, "lore")));
		}
	}

	private static void loadArmor(String resource) {
		JsonArray array = readArray(resource);
		if (array == null) {
			return;
		}
		for (JsonElement element : array) {
			JsonObject o = element.getAsJsonObject();
			String cls = str(o, "class");
			if (cls == null) {
				continue;
			}
			String id = classToId(cls, "altarsmp");
			ARMOR.put(id, new ArmorDef(id, cls, str(o, "base_material"),
					o.has("custom_model_data") && !o.get("custom_model_data").isJsonNull() ? o.get("custom_model_data").getAsInt() : null,
					str(o, "display_name"), str(o, "tooltip_style"), str(o, "rarity"), strings(o, "lore")));
		}
	}

	private static String classToId(String cls, @Nullable String pkg) {
		String base = cls;
		if (base.endsWith("Weapon")) {
			base = base.substring(0, base.length() - 6);
		}
		StringBuilder sb = new StringBuilder();
		for (char c : base.toCharArray()) {
			if (Character.isUpperCase(c) && sb.length() > 0) {
				sb.append('_');
			}
			sb.append(Character.toLowerCase(c));
		}
		String id = sb.toString();
		return switch (id) {
			case "copper_fragment" -> "copperfragment";
			case "copper_chestplate_fragment" -> "chestplateshard";
			case "hyperion_shard" -> "hyperionshard";
			case "nightpiercer_shard" -> "nightpiercershard";
			case "pale_shard" -> "paleshard";
			case "illusion_core" -> "illusioncore";
			case "vulkan_head" -> "vulkanhead";
			case "warden_head" -> "wardenhead";
			case "warden_heart" -> "wardenheart";
			case "weapon_handle" -> "weaponhandle";
			case "player_tracker_item" -> "playertracker";
			case "copper_pickaxe_i_i" -> "copperpickaxeii";
			case "copper_pickaxe" -> "copperpickaxe";
			case "copper_diamond_armor" -> "copperdiamondarmor";
			case "soul_in_a_bottle" -> "soulinabottle";
			case "fragment_of_the_sea" -> "fragmentofthesea";
			case "dragon_heart" -> "dragonheart";
			case "amethyst_pickaxe" -> "amethystpickaxe";
			case "amethyst_axe" -> "amethystaxe";
			case "black_ghast_saddle" -> "blackghastsaddle";
			default -> id;
		};
	}

	private static void loadAltars(String resource) {
		JsonObject o = readObject(resource);
		if (o == null) {
			return;
		}
		for (String key : new String[]{"season1", "season2"}) {
			if (!o.has(key) || !o.get(key).isJsonArray()) {
				continue;
			}
			for (JsonElement element : o.getAsJsonArray(key)) {
				JsonObject a = element.getAsJsonObject();
				String display = str(a, "display");
				String recipe = str(a, "recipe");
				if (display == null) {
					continue;
				}
				Map<String, Integer> ingredients = new LinkedHashMap<>();
				if (a.has("ingredients") && a.get("ingredients").isJsonObject()) {
					for (Map.Entry<String, JsonElement> e : a.getAsJsonObject("ingredients").entrySet()) {
						ingredients.put(e.getKey(), e.getValue().getAsInt());
					}
				}
				String altarKey = a.has("key") ? str(a, "key") : (recipe != null ? recipe : display.toLowerCase().replace(' ', '_').replace("'", ""));
				AltarDef def = new AltarDef(altarKey, display, str(a, "color"),
						a.has("cmd") && !a.get("cmd").isJsonNull() ? a.get("cmd").getAsInt() : null,
						a.has("material") ? str(a, "material") : "NETHERITE_SWORD",
						a.has("y_offset") ? a.get("y_offset").getAsDouble() : 0.0D,
						recipe, Collections.unmodifiableMap(ingredients),
						str(a, "interact_class"), key.equals("season2") ? 2 : 1);
				ALTARS.put(altarKey, def);
				if (recipe != null && !recipe.equals(altarKey)) {
					ALTARS.putIfAbsent(recipe, def);
				}
			}
		}
	}

	private static void loadRecipes(String resource) {
		JsonObject o = readObject(resource);
		if (o == null) {
			return;
		}
		loadRecipeSection(o, "main", RECIPES);
		loadRecipeSection(o, "s2", RECIPES_S2);
		// Any S2 recipe also has to be visible to the merged Season-1 lookups because
		// the merged plugin registered every altar recipe in one table.
		for (Map.Entry<String, Map<String, Integer>> entry : RECIPES_S2.entrySet()) {
			RECIPES.putIfAbsent(entry.getKey(), entry.getValue());
		}
	}

	private static void loadRecipeSection(JsonObject root, String key, Map<String, Map<String, Integer>> target) {
		if (!root.has(key) || !root.get(key).isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(key).entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			Map<String, Integer> table = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> ingredient : entry.getValue().getAsJsonObject().entrySet()) {
				try {
					table.put(ingredient.getKey(), ingredient.getValue().getAsInt());
				} catch (RuntimeException e) {
					AltarSMPMod.LOGGER.warn("[AltarSMP] recipe {}.{} has a non-numeric amount", key + "." + entry.getKey(), ingredient.getKey());
				}
			}
			target.put(entry.getKey(), Collections.unmodifiableMap(table));
		}
	}

	private static void loadIngredients(String resource) {
		JsonObject o = readObject(resource);
		if (o == null) {
			return;
		}
		for (String key : new String[]{"custom_s1", "custom_s2"}) {
			if (o.has(key) && o.get(key).isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
					CUSTOM_INGREDIENT_NAMES.putIfAbsent(e.getKey(), e.getValue().getAsString());
				}
			}
		}
		if (o.has("never_consumed") && o.get("never_consumed").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("never_consumed")) {
				NEVER_CONSUMED.add(e.getAsString());
			}
		}
	}

	// ---------------------------------------------------------------- accessors

	@Nullable
	public static WeaponDef weapon(String id) {
		return WEAPONS.get(Identity.normalise(id));
	}

	public static Map<String, WeaponDef> weapons() {
		return Collections.unmodifiableMap(WEAPONS);
	}

	public static boolean isWeaponId(String id) {
		return WEAPONS.containsKey(Identity.normalise(id));
	}

	@Nullable
	public static ItemDef item(String id) {
		return ITEMS.get(Identity.normalise(id));
	}

	public static Map<String, ItemDef> items() {
		return Collections.unmodifiableMap(ITEMS);
	}

	@Nullable
	public static ArmorDef armor(String id) {
		return ARMOR.get(Identity.normalise(id));
	}

	public static Map<String, ArmorDef> armors() {
		return Collections.unmodifiableMap(ARMOR);
	}

	@Nullable
	public static AltarDef altar(String key) {
		return ALTARS.get(Identity.normalise(key));
	}

	@Nullable
	public static AltarDef altarByDisplay(String display) {
		for (AltarDef def : ALTARS.values()) {
			if (def.display().equalsIgnoreCase(display)) {
				return def;
			}
		}
		return null;
	}

	public static Map<String, AltarDef> altars() {
		return Collections.unmodifiableMap(ALTARS);
	}

	/** Ingredient table for an altar recipe id (merged S1 + S2 view). */
	public static Map<String, Integer> recipe(String recipeId) {
		Map<String, Integer> table = RECIPES.get(recipeId);
		if (table == null) {
			table = RECIPES_S2.get(recipeId);
		}
		return table == null ? Collections.emptyMap() : table;
	}

	/** Season-2 view of a recipe (s2.yml wins, matching the S2 module's lookups). */
	public static Map<String, Integer> s2Recipe(String recipeId) {
		Map<String, Integer> table = RECIPES_S2.get(recipeId);
		return table != null ? table : recipe(recipeId);
	}

	public static Set<String> recipeIds() {
		Set<String> ids = new LinkedHashSet<>(RECIPES.keySet());
		ids.addAll(RECIPES_S2.keySet());
		return ids;
	}

	public static boolean isCustomIngredient(String key) {
		return CUSTOM_INGREDIENT_NAMES.containsKey(key);
	}

	public static String customIngredientName(String key) {
		String name = CUSTOM_INGREDIENT_NAMES.get(key);
		if (name != null) {
			return name;
		}
		String stripped = key.startsWith("custom_") ? key.substring(7) : key;
		return prettyMaterial(stripped);
	}

	public static boolean isNeverConsumed(String materialKey) {
		return NEVER_CONSUMED.contains(materialKey.toUpperCase(java.util.Locale.ROOT));
	}

	public static boolean isProtectedItemId(String id) {
		return PROTECTED_ITEMS.contains(Identity.normalise(id));
	}

	/** {@code BONE_BLOCK} -&gt; {@code Bone Block} (a/b.java#a). */
	public static String prettyMaterial(String key) {
		String[] parts = key.toLowerCase(java.util.Locale.ROOT).split("_");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (parts[i].isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ helpers

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static boolean bool(JsonObject o, String key, boolean def) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : def;
	}

	@Nullable
	private static Object number(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull()) {
			return null;
		}
		JsonElement e = o.get(key);
		if (e.getAsJsonPrimitive().isNumber()) {
			return e.getAsNumber();
		}
		return e.getAsString();
	}

	private static List<String> strings(JsonObject o, String key) {
		List<String> out = new ArrayList<>();
		if (o.has(key) && o.get(key).isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray(key)) {
				out.add(e.getAsString());
			}
		}
		return out;
	}

	// -------------------------------------------------------------------- types

	public record EnchantSpec(String enchant, @Nullable String configPath, @Nullable Object defaultValue) {
		public int level(int fallback) {
			if (this.defaultValue instanceof Number n) {
				return n.intValue();
			}
			return fallback;
		}
	}

	public record Condition(@Nullable String path, boolean expected) {
	}

	public record ConditionalLore(List<Condition> whenAll, List<String> lines) {
	}

	public record WeaponDef(String id, int season, @Nullable String displayName, @Nullable String baseMaterial,
			@Nullable Integer customModelData, @Nullable String tooltipStyle, @Nullable String rarity,
			boolean unbreakable, List<EnchantSpec> enchants, List<String> lore, List<ConditionalLore> conditionalLore,
			@Nullable String sourceClass) {
	}

	public record ItemDef(String id, String sourceClass, @Nullable String baseMaterial, @Nullable Integer customModelData,
			@Nullable String displayName, @Nullable String tooltipStyle, @Nullable String rarity, List<String> lore) {
	}

	public record ArmorDef(String id, String sourceClass, @Nullable String baseMaterial, @Nullable Integer customModelData,
			@Nullable String displayName, @Nullable String tooltipStyle, @Nullable String rarity, List<String> lore) {
	}

	public record AltarDef(String key, String display, @Nullable String color, @Nullable Integer customModelData,
			@Nullable String material, double yOffset, @Nullable String recipeId, Map<String, Integer> ingredients,
			@Nullable String sourceClass, int season) {
	}
}
