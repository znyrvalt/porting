package com.altarsmp.fabric.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.mojang.serialization.Unit;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.nbt.CompoundTag;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.config.AltarConfig;
import com.altarsmp.fabric.config.ConfigView;
import com.altarsmp.fabric.util.GameRegistry;
import com.altarsmp.fabric.util.TextFx;

/**
 * Builds every AltarSMP item exactly the way {@code a/m.java} built it in the
 * original plugin: base material, custom model data (both the legacy int slot
 * <em>and</em> the float slot the resource pack's {@code range_dispatch} reads),
 * MiniMessage name and lore with {@code <italic:false>}, rarity, unbreakable,
 * the {@code altarsmp:<weapon>/<weapon>} tooltip style, and the enchantment
 * table with levels pulled from {@code config.yml}.
 *
 * <p>The values themselves come from {@link ContentCatalog}, which was extracted
 * mechanically from the decompiled sources - nothing here is guessed.</p>
 */
public final class ItemFactory {

	/** Namespace that owns every tooltip sprite in the resource pack. */
	public static final String TOOLTIP_NAMESPACE = "altarsmp";

	private static final Pattern CFG_PLACEHOLDER = Pattern.compile("\\{cfg:([^|}]+)\\|([^}]*)\\}");
	private static final Pattern OWNER_PLACEHOLDER = Pattern.compile("\\{owner\\|([^}]*)\\}");

	/** id -&gt; tooltip style path, ported from TooltipStyleEnforcer's static table. */
	private static final Map<String, String> TOOLTIP_STYLES = new LinkedHashMap<>();

	private static AltarConfig config;

	private ItemFactory() {
	}

	public static void initialize(AltarConfig cfg) {
		config = cfg;
		ContentCatalog.initialize();
		buildTooltipTable();
	}

	/**
	 * Rebuilds the id -&gt; tooltip-style table from the catalogue and from the
	 * entries {@code com.altarsmp.listeners.TooltipStyleEnforcer} hard-coded
	 * (copper armour/pickaxes all share {@code copper_armor/copper_armor}).
	 */
	private static void buildTooltipTable() {
		TOOLTIP_STYLES.clear();
		for (ContentCatalog.WeaponDef def : ContentCatalog.weapons().values()) {
			if (def.tooltipStyle() != null) {
				TOOLTIP_STYLES.put(def.id(), def.tooltipStyle());
			}
		}
		for (ContentCatalog.ItemDef def : ContentCatalog.items().values()) {
			if (def.tooltipStyle() != null) {
				TOOLTIP_STYLES.put(def.id(), def.tooltipStyle());
			}
		}
		for (ContentCatalog.ArmorDef def : ContentCatalog.armors().values()) {
			if (def.tooltipStyle() != null) {
				TOOLTIP_STYLES.put(def.id(), def.tooltipStyle());
			}
		}
		// Copper tools/armour entries the enforcer knew by their config ids.
		for (String id : new String[]{"copper_helmet", "copper_chestplate", "copper_leggings", "copper_boots",
				"copper_pickaxe", "copper_pickaxe_ii", "copper_pickaxeii", "copperdiamondarmor"}) {
			TOOLTIP_STYLES.putIfAbsent(id, "copper_armor/copper_armor");
		}
	}

	public static boolean tooltipStylesEnabled() {
		return config == null || config.getBoolean("cosmetics.tooltip_styles_enabled", true);
	}

	@Nullable
	public static String tooltipStylePath(String contentId) {
		return TOOLTIP_STYLES.get(Identity.normalise(contentId));
	}

	@Nullable
	public static Identifier tooltipStyleId(String contentId) {
		String path = tooltipStylePath(contentId);
		if (path == null || !tooltipStylesEnabled()) {
			return null;
		}
		return Identifier.fromNamespaceAndPath(TOOLTIP_NAMESPACE, path);
	}

	// ------------------------------------------------------------------ weapons

	public static ItemStack weapon(String id) {
		return weapon(id, null);
	}

	public static ItemStack weapon(String id, @Nullable ServerPlayer owner) {
		ContentCatalog.WeaponDef def = ContentCatalog.weapon(id);
		if (def == null) {
			AltarSMPMod.LOGGER.error("[AltarSMP] cannot create unknown weapon '{}'", id);
			return new ItemStack(Items.BARRIER);
		}
		ItemStack stack = base(def.baseMaterial());
		decorate(stack, def.displayName(), def.lore(), def.conditionalLore(), def.customModelData(),
				def.tooltipStyle(), def.rarity(), def.unbreakable(), def.enchants(), def.id(), owner);
		Identity.putLegacy(stack, def.season() == 2 ? Identity.KEY_S2 : Identity.KEY_WEAPON, def.id());
		if (def.season() == 2) {
			Identity.putLegacy(stack, Identity.KEY_WEAPON, def.id());
		}
		return stack;
	}

	// -------------------------------------------------------------------- items

	public static ItemStack item(String id) {
		ContentCatalog.ItemDef def = ContentCatalog.item(id);
		if (def == null) {
			AltarSMPMod.LOGGER.error("[AltarSMP] cannot create unknown item '{}'", id);
			return new ItemStack(Items.BARRIER);
		}
		ItemStack stack = base(def.baseMaterial());
		decorate(stack, def.displayName(), def.lore(), List.of(), def.customModelData(), def.tooltipStyle(),
				def.rarity(), true, List.of(), def.id(), null);
		Identity.putLegacy(stack, Identity.KEY_ITEM, def.id());
		return stack;
	}

	public static ItemStack armor(String id) {
		ContentCatalog.ArmorDef def = ContentCatalog.armor(id);
		if (def == null) {
			AltarSMPMod.LOGGER.error("[AltarSMP] cannot create unknown armour '{}'", id);
			return new ItemStack(Items.BARRIER);
		}
		ItemStack stack = base(def.baseMaterial());
		decorate(stack, def.displayName(), def.lore(), List.of(), def.customModelData(), def.tooltipStyle(),
				def.rarity(), true, List.of(), def.id(), null);
		Identity.putLegacy(stack, Identity.KEY_ARMOR, def.id());
		return stack;
	}

	/** Any content id - weapon, item or armour - in one lookup. */
	public static Optional<ItemStack> content(String id) {
		return content(id, null);
	}

	public static Optional<ItemStack> content(String id, @Nullable ServerPlayer owner) {
		String normalised = Identity.normalise(id);
		if (ContentCatalog.weapon(normalised) != null) {
			return Optional.of(weapon(normalised, owner));
		}
		if (ContentCatalog.item(normalised) != null) {
			return Optional.of(item(normalised));
		}
		if (ContentCatalog.armor(normalised) != null) {
			return Optional.of(armor(normalised));
		}
		// Recipe/ingredient keys use config spellings such as {@code custom_warden_heart}.
		String stripped = normalised.startsWith("custom_") ? normalised.substring(7) : normalised;
		if (ContentCatalog.item(stripped) != null) {
			return Optional.of(item(stripped));
		}
		if (ContentCatalog.weapon(stripped) != null) {
			return Optional.of(weapon(stripped, owner));
		}
		return Optional.empty();
	}

	public static boolean exists(String id) {
		return content(id).isPresent();
	}

	// ------------------------------------------------------------------- shared

	private static ItemStack base(@Nullable String bukkitMaterial) {
		Item item = GameRegistry.item(bukkitMaterial == null ? "STONE" : bukkitMaterial);
		if (item == null) {
			AltarSMPMod.LOGGER.error("[AltarSMP] unknown base material '{}' - falling back to STONE", bukkitMaterial);
			item = Items.STONE;
		}
		return new ItemStack(item);
	}

	private static void decorate(ItemStack stack, @Nullable String displayName, List<String> lore,
			List<ContentCatalog.ConditionalLore> conditional, @Nullable Integer cmd, @Nullable String tooltipStyle,
			@Nullable String rarity, boolean unbreakable, List<ContentCatalog.EnchantSpec> enchants, String contentId,
			@Nullable ServerPlayer owner) {

		if (displayName != null && !displayName.isEmpty()) {
			Component name = TextFx.parse(resolve(displayName, owner));
			stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(style -> style.withItalic(false)));
		}
		if (cmd != null) {
			applyModelData(stack, cmd);
		}
		if (rarity != null) {
			Rarity value = rarity(rarity);
			if (value != null) {
				stack.set(DataComponents.RARITY, value);
			}
		}
		if (unbreakable) {
			stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		}
		if (tooltipStyle != null && tooltipStylesEnabled()) {
			stack.set(DataComponents.TOOLTIP_STYLE, Identifier.fromNamespaceAndPath(TOOLTIP_NAMESPACE, tooltipStyle));
		}
		applyEnchants(stack, enchants);
		List<Component> lines = new ArrayList<>();
		for (String line : lore) {
			lines.add(TextFx.parse(resolve(line, owner)).copy().withStyle(style -> style.withItalic(false)));
		}
		for (ContentCatalog.ConditionalLore block : conditional) {
			if (conditionsMet(block)) {
				for (String line : block.lines()) {
					lines.add(TextFx.parse(resolve(line, owner)).copy().withStyle(style -> style.withItalic(false)));
				}
			}
		}
		if (!lines.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(lines));
		}
		if (owner != null) {
			stack.set(ModComponents.PROVENANCE, owner.getGameProfile().getName() + "|" + owner.getUUID());
		}
		stack.set(ModComponents.IDENTITY, contentId);
	}

	/**
	 * Mirrors {@code BaseWeapon#setItemCmd}: the plugin wrote the model number to
	 * the legacy int slot <em>and</em> to {@code CustomModelDataComponent.floats}.
	 * Modern item model dispatch reads the float slot, so both are set.
	 */
	public static void applyModelData(ItemStack stack, int cmd) {
		stack.set(DataComponents.CUSTOM_MODEL_DATA,
				new CustomModelData(List.of((float) cmd), List.of(cmd), List.of(), List.of()));
	}

	public static void applyEnchants(ItemStack stack, List<ContentCatalog.EnchantSpec> specs) {
		if (specs == null || specs.isEmpty()) {
			return;
		}
		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(
				stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
		for (ContentCatalog.EnchantSpec spec : specs) {
			Optional<Holder.Reference<Enchantment>> holder = GameRegistry.enchantment(spec.enchant());
			if (holder.isEmpty()) {
				AltarSMPMod.LOGGER.warn("[AltarSMP] enchantment '{}' on item could not be resolved", spec.enchant());
				continue;
			}
			int level = specLevel(spec);
			if (level <= 0) {
				continue;
			}
			mutable.set(holder.get(), level);
		}
		stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
	}

	/** Adds (or raises) a single enchantment - used by Knightfall's kill tiers. */
	public static void applyEnchant(ItemStack stack, String enchantId, int level) {
		Optional<Holder.Reference<Enchantment>> holder = GameRegistry.enchantment(enchantId);
		if (holder.isEmpty()) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] enchantment '{}' could not be resolved", enchantId);
			return;
		}
		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(
				stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
		mutable.set(holder.get(), level);
		stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
	}

	private static int specLevel(ContentCatalog.EnchantSpec spec) {
		if (spec.configPath() != null && config != null) {
			int configured = config.getInt(spec.configPath(), -1);
			if (configured >= 0) {
				return configured;
			}
		}
		return spec.level(1);
	}

	@Nullable
	private static Rarity rarity(String name) {
		try {
			return Rarity.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] unknown rarity '{}'", name);
			return null;
		}
	}

	private static boolean conditionsMet(ContentCatalog.ConditionalLore block) {
		for (ContentCatalog.Condition condition : block.whenAll()) {
			if (condition.path() == null) {
				continue;
			}
			boolean actual = config != null && config.getBoolean(condition.path(), false);
			if (actual != condition.expected()) {
				return false;
			}
		}
		return true;
	}

	// -------------------------------------------------------- lore placeholders

	/**
	 * Resolves the two placeholder forms the original item builders emitted:
	 * {@code {cfg:<config path>|<default>}} and {@code {owner|<default name>}}.
	 */
	public static String resolve(String template, @Nullable ServerPlayer owner) {
		if (template == null || template.indexOf('{') < 0) {
			return template;
		}
		String out = template;
		Matcher cfg = CFG_PLACEHOLDER.matcher(out);
		StringBuilder sb = new StringBuilder();
		while (cfg.find()) {
			cfg.appendReplacement(sb, Matcher.quoteReplacement(configValue(cfg.group(1), cfg.group(2))));
		}
		cfg.appendTail(sb);
		out = sb.toString();

		Matcher own = OWNER_PLACEHOLDER.matcher(out);
		StringBuilder sb2 = new StringBuilder();
		while (own.find()) {
			String value = owner != null ? owner.getGameProfile().getName() : own.group(1);
			own.appendReplacement(sb2, Matcher.quoteReplacement(value));
		}
		own.appendTail(sb2);
		return sb2.toString();
	}

	private static String configValue(String path, String fallback) {
		if (config == null) {
			return fallback;
		}
		ConfigView view = config.main().contains(path) ? config.main() : config.s2();
		try {
			if (isInteger(fallback)) {
				return Integer.toString(view.getInt(path, Integer.parseInt(fallback)));
			}
			if (isDouble(fallback)) {
				return formatDouble(view.getDouble(path, Double.parseDouble(fallback)));
			}
			if ("true".equalsIgnoreCase(fallback) || "false".equalsIgnoreCase(fallback)) {
				return Boolean.toString(view.getBoolean(path, Boolean.parseBoolean(fallback)));
			}
			return view.getString(path, fallback);
		} catch (RuntimeException e) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] could not resolve lore placeholder cfg:{} - using '{}'", path, fallback, e);
			return fallback;
		}
	}

	private static boolean isInteger(String s) {
		try {
			Integer.parseInt(s);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static boolean isDouble(String s) {
		try {
			Double.parseDouble(s);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static String formatDouble(double value) {
		if (value == Math.rint(value) && !Double.isInfinite(value)) {
			return Long.toString((long) value);
		}
		return Double.toString(value);
	}

	// --------------------------------------------------------------- legacy tag

	/** Writes an arbitrary legacy PDC key/value onto a stack's custom-data tag. */
	public static void putTag(ItemStack stack, String key, String value) {
		CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		tag.putString(key, value);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	public static String tag(ItemStack stack, String key) {
		CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		return tag.contains(key, 8) ? tag.getString(key) : null;
	}
}
