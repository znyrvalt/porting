package com.altarsmp.fabric.altar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.faction.FactionManager;
import com.altarsmp.fabric.item.ContentCatalog;
import com.altarsmp.fabric.item.Identity;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.recipe.RecipeRegistry;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TextFx;

/**
 * Every altar the plugin shipped, and the ritual that runs when a player hits one.
 *
 * <p>Upstream this was 38 near-identical {@code *AltarInteract} listener classes plus
 * five Season 2 subclasses of {@code BaseAltarInteract}. Each matched an armour stand by
 * its coloured custom name, cancelled the hit, asked the recipe table what was missing,
 * and then either listed the shortfall or took the ingredients, handed over the item,
 * titled the server and removed the altar. The differences between them - title colour,
 * subtitle, faction gate, sounds, chat lines - are data, so they live in
 * {@code content/altars.json} (extracted from those classes by
 * {@code tools/extract_altars.py}) and this registry turns them into {@link Spec}s.
 *
 * <p>Four altars genuinely do something else and get their own path here, each mirroring
 * its upstream class:
 *
 * <ul>
 *   <li><b>contagionsignal</b> - no recipe at all: the crafter must be holding the Pale
 *       Crossbow, Hyperion and Nightpiercer, and those three are deliberately
 *       <em>not</em> consumed ("Items will NOT be consumed").</li>
 *   <li><b>playertracker</b> - a hard-coded ingredient list with per-ingredient colours,
 *       config-overridable amounts, no title broadcast and no altar removal.</li>
 *   <li><b>copperpickaxeupgrade</b> - also needs a Copper Pickaxe whose
 *       {@code blocks_mined} counter reached {@code copper_pickaxe.blocks_required}; the
 *       old pickaxe is taken along with the ingredients and Copper Pickaxe II handed
 *       back.</li>
 *   <li><b>palecrossbow</b> - gated to the Pale King, and crafting it <em>makes</em> you
 *       the Pale King, with the obfuscated title and zalgo subtitle the plugin built.</li>
 * </ul>
 *
 * <p>{@code CraftingAltarInteract} is ported too: its right-click collectible list was
 * shipped empty ({@code Arrays.asList()}), so {@link #collectibleNames} stays empty and
 * that path only runs if a pack fills it.
 */
public final class AltarRegistry {

	/** A ritual sound: to everyone, or only to the crafter. */
	public record Sound(String sound, float volume, float pitch, boolean toAll) {
	}

	/** One chat line: MiniMessage markup, a legacy colour + text, or a blank line. */
	public record Message(@Nullable String markup, @Nullable String color, @Nullable String text, boolean blank,
			@Nullable String raw, boolean toAll) {

		/** The line as markup, or {@code null} for a concatenated expression. */
		@Nullable
		String rendered() {
			if (this.blank) {
				return "";
			}
			if (this.markup != null) {
				return this.markup;
			}
			if (this.text != null) {
				return "<" + (this.color == null ? "white" : this.color) + ">" + this.text;
			}
			return null;
		}
	}

	/** The faction gate: a config toggle plus a faction check that must pass. */
	public record Gate(String config, boolean defaultValue, boolean negated, String check, @Nullable String message,
			@Nullable Sound sound) {
	}

	/** The title sent to every player when a craft succeeds. */
	public record Title(@Nullable String color, @Nullable String textMarkup, @Nullable String subtitleColor,
			@Nullable String subtitle, int fadeIn, int stay, int fadeOut) {
	}

	/** What the altar hands out. */
	public record Gives(String kind, @Nullable String contentId, @Nullable String sourceClass) {
	}

	/** One altar: its display identity plus everything its ritual needs. */
	public record Spec(String key, String display, @Nullable String color, int cmd, @Nullable String material,
			double yOffset, @Nullable String recipeId, int season, @Nullable String sourceClass, @Nullable Title title,
			@Nullable Gives gives, @Nullable Gate gate, @Nullable String setsKing, List<Sound> sounds,
			List<Message> messages, boolean removesAltar, @Nullable String special,
			@Nullable String missingPrefixColor, @Nullable String zalgo) {

		/** The stand name this altar answers to, without colour. */
		public String plainDisplay() {
			return TextFx.strip(this.display);
		}
	}

	private final AltarSMPMod mod;
	private final Map<String, Spec> byKey = new LinkedHashMap<>();
	private final Map<String, Spec> byDisplay = new LinkedHashMap<>();
	/** {@code CraftingAltarInteract#b} - shipped empty by the plugin. */
	private final List<String> collectibleNames = new ArrayList<>();

	public AltarRegistry(AltarSMPMod mod) {
		this.mod = mod;
	}

	// ------------------------------------------------------------------- loading

	/** Builds a {@link Spec} for every altar in {@code content/altars.json}. */
	public void registerAll() {
		this.byKey.clear();
		this.byDisplay.clear();
		for (Map.Entry<String, ContentCatalog.AltarDef> entry : ContentCatalog.altars().entrySet()) {
			ContentCatalog.AltarDef def = entry.getValue();
			Spec spec = build(def);
			this.byKey.putIfAbsent(spec.key(), spec);
			this.byDisplay.putIfAbsent(spec.plainDisplay().toLowerCase(Locale.ROOT), spec);
		}
		int withRecipe = 0;
		int withOutput = 0;
		int seasonTwo = 0;
		for (Spec spec : this.byKey.values()) {
			if (spec.recipeId() != null && !spec.recipeId().isEmpty()) {
				withRecipe++;
			}
			if (spec.gives() != null && spec.gives().contentId() != null) {
				withOutput++;
			} else if (!"crafting".equals(spec.special())) {
				AltarSMPMod.LOGGER.error("[AltarSMP] altar '{}' has no resolvable output item - it would craft nothing",
						spec.display());
			}
			if (spec.season() == 2) {
				seasonTwo++;
			}
		}
		AltarSMPMod.LOGGER.info("[AltarSMP] altars registered: {} ({} with recipes, {} with outputs, {} season 2)",
				this.byKey.size(), withRecipe, withOutput, seasonTwo);
	}

	private Spec build(ContentCatalog.AltarDef def) {
		JsonObject ritual = def.ritual();
		Title title = null;
		Gives gives = null;
		Gate gate = null;
		String setsKing = null;
		String special = null;
		String missingPrefix = null;
		String zalgo = null;
		List<Sound> sounds = new ArrayList<>();
		List<Message> messages = new ArrayList<>();
		boolean removesAltar = true;

		if (ritual != null) {
			zalgo = zalgoFor(ritual, def.recipeId());
			title = readTitle(ritual, zalgo);
			gives = readGives(ritual);
			gate = readGate(ritual);
			setsKing = str(ritual, "sets_king");
			special = str(ritual, "special");
			missingPrefix = str(ritual, "missing_prefix_color");
			removesAltar = !ritual.has("removes_altar") || ritual.get("removes_altar").getAsBoolean();
			JsonArray soundArray = array(ritual, "sounds");
			if (soundArray != null) {
				for (JsonElement element : soundArray) {
					JsonObject o = element.getAsJsonObject();
					sounds.add(new Sound(str(o, "sound"), (float) num(o, "volume", 1.0D), (float) num(o, "pitch", 1.0D),
							"all".equals(str(o, "to"))));
				}
			}
			JsonArray messageArray = array(ritual, "messages");
			if (messageArray != null) {
				for (JsonElement element : messageArray) {
					JsonObject o = element.getAsJsonObject();
					messages.add(new Message(str(o, "markup"), str(o, "color"), str(o, "text"),
							o.has("blank") && o.get("blank").getAsBoolean(), str(o, "raw"),
							"all".equals(str(o, "to"))));
				}
			}
		}

		return new Spec(def.key(), def.display(), def.color(),
				def.customModelData() == null ? 0 : def.customModelData(), def.material(), def.yOffset(),
				def.recipeId(), def.season(), def.sourceClass(), title, gives, gate, setsKing,
				Collections.unmodifiableList(sounds), Collections.unmodifiableList(messages), removesAltar, special,
				missingPrefix, zalgo);
	}

	@Nullable
	private static String zalgoFor(JsonObject ritual, @Nullable String recipeId) {
		if (recipeId == null || !ritual.has("zalgo") || !ritual.get("zalgo").isJsonObject()) {
			return null;
		}
		JsonObject zalgo = ritual.getAsJsonObject("zalgo");
		return zalgo.has(recipeId) ? zalgo.get(recipeId).getAsString() : null;
	}

	@Nullable
	private static Title readTitle(JsonObject ritual, @Nullable String zalgo) {
		String subtitle = str(ritual, "title_subtitle");
		if ("zalgo".equals(str(ritual, "title_subtitle_ref"))) {
			subtitle = zalgo;
		}
		// The Pale Crossbow builds its heading from obfuscated "aaaa" either side of a
		// grey question mark; that is what its raw ChatColor expression says, so it is
		// written out as markup here instead of being re-parsed.
		String text = str(ritual, "title_text_raw") != null ? "<dark_gray><obf>aaaa</obf><gray> ? <obf>aaaa</obf>" : null;
		if (text == null && subtitle == null && str(ritual, "title_color") == null) {
			return null;
		}
		return new Title(str(ritual, "title_color"), text, str(ritual, "title_subtitle_color"), subtitle,
				(int) num(ritual, "title_fade_in", 10), (int) num(ritual, "title_stay", 140),
				(int) num(ritual, "title_fade_out", 20));
	}

	@Nullable
	private Gives readGives(JsonObject ritual) {
		JsonObject o = ritual.has("gives") && ritual.get("gives").isJsonObject() ? ritual.getAsJsonObject("gives") : null;
		if (o == null) {
			return null;
		}
		String kind = str(o, "kind");
		String sourceClass = str(o, "class");
		String contentId = ContentCatalog.idByClass(kind, sourceClass);
		if (contentId == null) {
			AltarSMPMod.LOGGER.error("[AltarSMP] altar output class '{}' ({}) is not in the content catalog",
					sourceClass, kind);
		}
		return new Gives(kind == null ? "item" : kind, contentId, sourceClass);
	}

	@Nullable
	private static Gate readGate(JsonObject ritual) {
		JsonObject o = ritual.has("gate") && ritual.get("gate").isJsonObject() ? ritual.getAsJsonObject("gate") : null;
		if (o == null) {
			return null;
		}
		Sound sound = null;
		if (o.has("sound") && o.get("sound").isJsonObject()) {
			JsonObject s = o.getAsJsonObject("sound");
			sound = new Sound(str(s, "sound"), (float) num(s, "volume", 1.0D), (float) num(s, "pitch", 1.0D), false);
		}
		return new Gate(str(o, "config"), o.has("default") && o.get("default").getAsBoolean(),
				o.has("negated") && o.get("negated").getAsBoolean(), str(o, "check"), str(o, "message"), sound);
	}

	// ------------------------------------------------------------------ lookups

	@Nullable
	public Spec byKey(String key) {
		return this.byKey.get(Identity.normalise(key));
	}

	/** The altar an armour stand belongs to, matched on its stripped custom name. */
	@Nullable
	public Spec byDisplay(@Nullable String display) {
		return display == null ? null : this.byDisplay.get(TextFx.strip(display).toLowerCase(Locale.ROOT));
	}

	@Nullable
	public Spec byStand(@Nullable Entity entity) {
		if (!(entity instanceof ArmorStand stand) || stand.getCustomName() == null) {
			return null;
		}
		return byDisplay(stand.getCustomName().getString());
	}

	public Collection<Spec> all() {
		return Collections.unmodifiableCollection(this.byKey.values());
	}

	public List<String> collectibleNames() {
		return Collections.unmodifiableList(this.collectibleNames);
	}

	/** The item an altar hands out, or {@code null} when the catalog cannot produce it. */
	@Nullable
	public ItemStack output(Spec spec) {
		Gives gives = spec.gives();
		if (gives == null || gives.contentId() == null) {
			AltarSMPMod.LOGGER.error("[AltarSMP] altar '{}' cannot produce an output item", spec.display());
			return null;
		}
		switch (gives.kind()) {
			case "weapon":
				return ItemFactory.weapon(gives.contentId());
			case "armor":
				return ItemFactory.armor(gives.contentId());
			default:
				return ItemFactory.item(gives.contentId());
		}
	}

	// ------------------------------------------------------------------ ritual

	/**
	 * {@code PlayerArmorStandManipulateEvent} in every {@code *AltarInteract}: nobody may
	 * put an item into (or take one out of) an altar stand's hand.
	 *
	 * @return {@code true} when the interaction must be cancelled
	 */
	public boolean blockManipulate(ServerPlayer player, Entity target) {
		return byStand(target) != null;
	}

	/**
	 * The left click on an altar stand - {@code EntityDamageByEntityEvent} at HIGHEST
	 * priority in every {@code *AltarInteract}, and {@code BaseAltarInteract} for Season
	 * 2. The damage is always cancelled; the ritual then either lists what is missing or
	 * crafts.
	 *
	 * @return {@code true} when the entity was an altar and the hit was consumed
	 */
	public boolean onLeftClick(ServerPlayer player, Entity target) {
		Spec spec = byStand(target);
		if (spec == null) {
			return false;
		}
		craft(player, spec, target);
		return true;
	}

	/** Runs one altar's ritual. */
	public void craft(ServerPlayer player, Spec spec, @Nullable Entity stand) {
		if (!gateAllows(player, spec)) {
			return;
		}
		switch (spec.special() == null ? "" : spec.special()) {
			case "contagionsignal":
				craftContagionSignal(player, spec, stand);
				return;
			case "playertracker":
				craftPlayerTracker(player, spec);
				return;
			case "copperpickaxeupgrade":
				craftPickaxeUpgrade(player, spec, stand);
				return;
			case "palecrossbow":
				craftPaleCrossbow(player, spec, stand);
				return;
			case "crafting":
				Messaging.send(player, "<red>This altar holds nothing to collect.");
				return;
			default:
				craftFromRecipe(player, spec, stand);
		}
	}

	/**
	 * The faction gates. Each reads {@code curses.gate_faction_crafts} plus one faction
	 * check: Hyperion refuses anyone carrying a curse, Nightpiercer refuses anyone who is
	 * not the Vampire King, the Pale Crossbow anyone who is not the Pale King.
	 * {@code negated} records the {@code !} in the original condition.
	 */
	private boolean gateAllows(ServerPlayer player, Spec spec) {
		Gate gate = spec.gate();
		if (gate == null || !this.mod.config().getBoolean(gate.config(), gate.defaultValue())) {
			return true;
		}
		boolean check = switch (gate.check() == null ? "" : gate.check()) {
			case "hasCurse" -> FactionManager.hasCurse(player);
			case "isPaleKing" -> FactionManager.isPaleKing(player);
			case "isVampireKing" -> FactionManager.isVampireKing(player);
			default -> {
				AltarSMPMod.LOGGER.error("[AltarSMP] altar '{}' gates on unknown faction check '{}'", spec.display(),
						gate.check());
				yield true;
			}
		};
		boolean refuse = gate.negated() ? !check : check;
		if (!refuse) {
			return true;
		}
		if (gate.message() != null) {
			Messaging.send(player, "<red>" + gate.message());
		}
		if (gate.sound() != null) {
			Fx.soundTo(player, gate.sound().sound(), gate.sound().volume(), gate.sound().pitch());
		}
		return false;
	}

	/** The uniform altars: recipe check, then take, give, title, remove. */
	private void craftFromRecipe(ServerPlayer player, Spec spec, @Nullable Entity stand) {
		String recipeId = spec.recipeId();
		if (recipeId == null || recipeId.isEmpty()) {
			AltarSMPMod.LOGGER.error("[AltarSMP] altar '{}' has no recipe id - nothing to check", spec.display());
			return;
		}
		RecipeRegistry recipes = this.mod.recipes();
		List<String> missing = recipes.missing(player, recipeId, spec.season());
		if (!missing.isEmpty()) {
			reportMissing(player, spec, "Missing items to craft " + spec.display() + ":", missing);
			return;
		}
		ItemStack output = output(spec);
		if (output == null) {
			// Checked before anything is taken: a broken catalog must never cost the
			// player their ingredients.
			AltarSMPMod.LOGGER.error("[AltarSMP] altar '{}' has no output item - ingredients were not taken",
					spec.display());
			return;
		}
		// Transactional: the whole table was verified above, so taking cannot fail
		// half-way and nobody is ever charged for a craft that did not happen.
		recipes.consume(player, recipeId, spec.season());
		give(player, output);
		finish(player, spec, stand);
	}

	/**
	 * {@code ContagionSignalAltarInteract}: the three legendary weapons must be in the
	 * inventory, and they are deliberately not taken.
	 */
	private void craftContagionSignal(ServerPlayer player, Spec spec, @Nullable Entity stand) {
		List<String> missing = new ArrayList<>();
		if (!holdsContent(player, "palecrossbow")) {
			missing.add("<gray>- <gray>Pale Crossbow: <red>0/1");
		}
		if (!holdsContent(player, "hyperion")) {
			missing.add("<gray>- <gold>Hyperion: <red>0/1");
		}
		if (!holdsContent(player, "nightpiercer")) {
			missing.add("<gray>- <dark_red>Nightpiercer: <red>0/1");
		}
		if (!missing.isEmpty()) {
			reportMissing(player, spec, "Missing items to craft Contagion Signal:", missing);
			Messaging.send(player, "<gray>(Items will NOT be consumed)");
			return;
		}
		ItemStack output = output(spec);
		if (output == null) {
			return;
		}
		give(player, output);
		MinecraftServer server = this.mod.server();
		if (server != null) {
			for (ServerPlayer online : server.getPlayerList().getPlayers()) {
				Messaging.send(online, "<gold>" + player.getGameProfile().getName()
						+ " <yellow>has crafted the <green>Contagion Signal<yellow>!");
			}
		}
		finish(player, spec, stand);
	}

	private static boolean holdsContent(ServerPlayer player, String contentId) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (Identity.is(player.getInventory().getItem(slot), contentId)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code PlayerTrackerAltarInteract}: four materials with configurable amounts, their
	 * own colours, no title broadcast and no altar removal.
	 */
	private void craftPlayerTracker(ServerPlayer player, Spec spec) {
		RecipeRegistry recipes = this.mod.recipes();
		int netherite = recipes.amount("playertracker", "NETHERITE_INGOT", 2);
		int heavyCore = recipes.amount("playertracker", "HEAVY_CORE", 1);
		int enderEye = recipes.amount("playertracker", "ENDER_EYE", 8);
		int spiderEye = recipes.amount("playertracker", "FERMENTED_SPIDER_EYE", 4);

		List<String> missing = new ArrayList<>();
		addTrackerLine(missing, player, "NETHERITE_INGOT", "Netherite Ingot", "aqua", netherite);
		addTrackerLine(missing, player, "HEAVY_CORE", "Heavy Core", "dark_purple", heavyCore);
		addTrackerLine(missing, player, "ENDER_EYE", "Eye of Ender", "green", enderEye);
		addTrackerLine(missing, player, "FERMENTED_SPIDER_EYE", "Fermented Spider Eye", "red", spiderEye);
		if (!missing.isEmpty()) {
			reportMissing(player, spec, "Missing items to craft Player Tracker:", missing);
			return;
		}
		ItemStack output = output(spec);
		if (output == null) {
			return;
		}
		RecipeRegistry.removeMaterial(player, "NETHERITE_INGOT", netherite);
		RecipeRegistry.removeMaterial(player, "HEAVY_CORE", heavyCore);
		RecipeRegistry.removeMaterial(player, "ENDER_EYE", enderEye);
		RecipeRegistry.removeMaterial(player, "FERMENTED_SPIDER_EYE", spiderEye);
		give(player, output);
		Messaging.send(player, "<gold>[Altar] <green>You have crafted a Player Tracker!");
		playSounds(player, spec, false);
	}

	private static void addTrackerLine(List<String> missing, ServerPlayer player, String material, String label,
			String color, int need) {
		int have = RecipeRegistry.countMaterial(player, material);
		if (have < need) {
			missing.add("<gray>- <" + color + ">" + label + ": <red>" + have + "/" + need);
		}
	}

	/**
	 * {@code CopperPickaxeUpgradeAltarInteract}: the pickaxe's own mined-block counter has
	 * to have reached {@code copper_pickaxe.blocks_required} before the recipe is even
	 * looked at, and the old pickaxe goes with the ingredients.
	 */
	private void craftPickaxeUpgrade(ServerPlayer player, Spec spec, @Nullable Entity stand) {
		int required = this.mod.config().getInt("copper_pickaxe.blocks_required", 25000);
		int mined = highestMined(player);
		RecipeRegistry recipes = this.mod.recipes();
		String recipeId = spec.recipeId() == null ? "copperpickaxeupgrade" : spec.recipeId();

		List<String> missing = new ArrayList<>();
		if (mined < required) {
			missing.add("<gray>- <gold>Copper Pickaxe (" + shortRequirement(required) + " mined): <red>"
					+ formatNumber(mined) + "/" + formatNumber(required));
		}
		missing.addAll(recipes.missing(player, recipeId, spec.season()));
		if (!missing.isEmpty()) {
			reportMissing(player, spec, "Missing items to upgrade Copper Pickaxe:", missing);
			return;
		}
		ItemStack output = output(spec);
		if (output == null) {
			return;
		}
		recipes.consume(player, recipeId, spec.season());
		removePickaxes(player);
		give(player, output);
		finish(player, spec, stand);
	}

	private static int highestMined(ServerPlayer player) {
		int best = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && Identity.is(stack, "copperpickaxe")) {
				best = Math.max(best, Identity.stateInt(stack, Identity.KEY_BLOCKS_MINED, 0));
			}
		}
		return best;
	}

	private static void removePickaxes(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && Identity.is(stack, "copperpickaxe")) {
				player.getInventory().setItem(slot, ItemStack.EMPTY);
			}
		}
		player.getInventory().setChanged();
	}

	/** {@code 25000} -&gt; {@code 25k}, the shorthand the plugin's message used. */
	private static String shortRequirement(int required) {
		return required % 1000 == 0 ? (required / 1000) + "k" : Integer.toString(required);
	}

	/** {@code CopperPickaxeUpgradeAltarInteract#formatNumber}. */
	static String formatNumber(int value) {
		return String.format(Locale.ROOT, "%,d", value);
	}

	/**
	 * {@code PaleCrossbowAltarInteract}: crafting it crowns the Pale King, and the whole
	 * server gets the obfuscated title with the zalgo subtitle.
	 */
	private void craftPaleCrossbow(ServerPlayer player, Spec spec, @Nullable Entity stand) {
		String recipeId = spec.recipeId() == null ? "palecrossbow" : spec.recipeId();
		RecipeRegistry recipes = this.mod.recipes();
		List<String> missing = recipes.missing(player, recipeId, spec.season());
		if (!missing.isEmpty()) {
			reportMissing(player, spec, "Missing items to craft Pale Crossbow:", missing);
			return;
		}
		ItemStack output = output(spec);
		recipes.consume(player, recipeId, spec.season());
		if (output != null) {
			give(player, output);
		}
		this.mod.factions().setPaleKing(player);
		finish(player, spec, stand);
	}

	// ------------------------------------------------------------ ritual pieces

	private static void reportMissing(ServerPlayer player, Spec spec, String header, List<String> lines) {
		String color = spec.missingPrefixColor() == null ? "red" : spec.missingPrefixColor();
		Messaging.send(player, "<" + color + ">" + header);
		for (String line : lines) {
			Messaging.send(player, line);
		}
	}

	/** {@code Inventory#addItem}, with a drop at the player's feet when it is full. */
	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
			Messaging.send(player, "<yellow>Your inventory was full - the item was dropped at your feet.");
		}
	}

	/**
	 * The shared success path. The order reproduces the plugin's: sounds and the title go
	 * to everyone first, then the crafter's own lines, then the crafter's own sounds, and
	 * finally the altar comes down.
	 */
	private void finish(ServerPlayer player, Spec spec, @Nullable Entity stand) {
		MinecraftServer server = this.mod.server();
		playSounds(player, spec, true);

		Title title = spec.title();
		if (server != null && title != null) {
			String heading = title.textMarkup() != null ? title.textMarkup()
					: "<" + (title.color() == null ? "gray" : title.color()) + ">" + player.getGameProfile().getName();
			String subtitle = title.subtitle() == null ? ""
					: "<" + (title.subtitleColor() == null ? "gray" : title.subtitleColor()) + ">" + title.subtitle();
			for (ServerPlayer online : server.getPlayerList().getPlayers()) {
				Messaging.title(online, heading, subtitle, title.fadeIn(), title.stay(), title.fadeOut());
			}
		}

		for (Message message : spec.messages()) {
			if (isEngineLine(message)) {
				continue;
			}
			String markup = message.rendered();
			if (markup == null) {
				// A concatenated expression: either the missing-item echo (the engine
				// sends those) or the Contagion Signal broadcast (its own path sent it).
				continue;
			}
			if (message.toAll()) {
				if (server != null) {
					Messaging.broadcast(server, markup);
				}
			} else {
				Messaging.send(player, markup);
			}
		}

		playSounds(player, spec, false);

		if (spec.setsKing() != null && !"pale".equals(spec.setsKing())) {
			AltarSMPMod.LOGGER.warn("[AltarSMP] altar '{}' sets king role '{}', which no altar upstream sets here",
					spec.display(), spec.setsKing());
		}
		if (spec.removesAltar() && stand != null) {
			this.mod.altars().removeAltarNear(stand);
		}
	}

	private static void playSounds(ServerPlayer player, Spec spec, boolean toAll) {
		MinecraftServer server = AltarSMPMod.get();
		for (Sound sound : spec.sounds()) {
			if (sound.toAll() != toAll) {
				continue;
			}
			if (toAll) {
				if (server == null) {
					continue;
				}
				for (ServerPlayer online : server.getPlayerList().getPlayers()) {
					Fx.soundTo(online, sound.sound(), sound.volume(), sound.pitch());
				}
			} else {
				Fx.soundTo(player, sound.sound(), sound.volume(), sound.pitch());
			}
		}
	}

	/** Lines the engine emits itself: the missing list and the gate refusals. */
	private static boolean isEngineLine(Message message) {
		String markup = message.rendered();
		if (markup == null) {
			return true;
		}
		return markup.contains("Missing items to craft") || markup.contains("Missing items to upgrade")
				|| markup.contains("Only humans can forge") || markup.contains("You cannot forge")
				|| markup.contains("Only the Plague Doctor can forge") || markup.contains("(Items will NOT be consumed)");
	}

	/**
	 * {@code CraftingAltarInteract#onRightClickArmorStand}: right-clicking a stand whose
	 * name is in the collectible list hands that item over and removes the altar. The
	 * plugin shipped the list empty, so this never fires unless a pack fills it.
	 *
	 * @return {@code true} when the right click was consumed
	 */
	public boolean onRightClick(ServerPlayer player, Entity target) {
		Spec spec = byStand(target);
		if (spec == null) {
			return false;
		}
		if (this.mod.altars().isLocked()) {
			// LockAltarsCommand cancelled the interact event outright, without a word.
			return true;
		}
		if (!this.collectibleNames.contains(spec.plainDisplay())) {
			return false;
		}
		ItemStack output = output(spec);
		if (output == null) {
			return false;
		}
		give(player, output);
		Messaging.send(player, "<green>You collected a <gold>" + spec.plainDisplay() + "<green>!");
		this.mod.altars().removeAltarNear(target);
		return true;
	}

	/** Resolves a material key, for the altars that count raw materials themselves. */
	@Nullable
	public static Item material(String key) {
		return RecipeRegistry.resolveItem(key);
	}

	// ------------------------------------------------------------------- json

	@Nullable
	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static double num(JsonObject o, String key, double fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : fallback;
	}

	@Nullable
	private static JsonArray array(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
	}
}
