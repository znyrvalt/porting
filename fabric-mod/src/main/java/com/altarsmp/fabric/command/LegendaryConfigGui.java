package com.altarsmp.fabric.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.config.ConfigFields;
import com.altarsmp.fabric.config.ConfigFields.Field;
import com.altarsmp.fabric.config.YamlLite;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;
import com.altarsmp.fabric.util.TextFx;

/**
 * {@code /legendaryconfig} and {@code /legendaryconfig2} — the live stat editor.
 *
 * <p>The plugin's editor was a Paper {@code Dialog}: a list of "Label: current value" buttons, and
 * clicking one asked the operator to type the new value into chat, which an
 * {@code AsyncChatEvent} listener caught, range-checked, wrote with
 * {@code getConfig().set(...)} plus {@code saveConfig()}, and answered with
 * "{@code <path> set to <value>. Config reloaded.}". 26.2 has dialogs but no serverbound dialog
 * input — they are display only — and cancelling a signed chat packet before the server applies its
 * seen-messages offset risks desyncing that player's chat chain, so the port keeps the same three
 * steps (browse, choose, set) and moves them into containers: a list of items, a list of that
 * item's stats with their current values, and a stepper per stat. Typing an exact value is still
 * available, as {@code /legendaryconfig set <entry> <path> <value>}, with the same range checks and
 * the same wording the plugin used.
 *
 * <p>Every edit goes through {@link com.altarsmp.fabric.config.AltarConfig#write}, which stores the
 * value in the file that owns it — comments intact — reloads both documents and rewrites the altar
 * holograms that quote them. That is the plugin's {@code saveConfig()} plus
 * {@code reloadConfigAndServices()}.
 */
public final class LegendaryConfigGui {
	/** Both seasons called their editor window "Legendary Config". */
	private static final Component LIST_TITLE = Messaging.msg("<gold><bold>Legendary Config");
	private static final Component GLOBAL_ICON_NAME = Messaging.msg("<red>Global Settings");

	private LegendaryConfigGui() {}

	/** {@code LegendaryConfigCommand#onCommand}: reload first, then show the list. */
	public static void openList(ServerPlayer player, boolean seasonTwo) {
		AltarSMPMod mod = AltarSMPMod.get();
		mod.config().reload();
		mod.altars().refreshAllHolograms(mod.server());

		List<String> ids = seasonTwo ? ConfigFields.seasonTwoIds() : ConfigFields.seasonOneIds();
		Map<Integer, String> slots = new LinkedHashMap<>();
		int limit = seasonTwo ? 17 : 44;
		int slot = 10;
		for (String id : ids) {
			// The plugin walked the middle rows and jumped the border column.
			if ((slot + 1) % 9 == 0) {
				slot += 2;
			}
			if (slot >= limit) {
				AltarSMPMod.LOGGER.warn("[legendaryconfig] no room left in the list for '{}'", id);
				break;
			}
			slots.put(slot, id);
			slot++;
		}
		if (!seasonTwo) {
			// The global block closed the plugin's list, after every item.
			int globalSlot = slot;
			while (globalSlot >= 10 && slots.containsKey(globalSlot)) {
				globalSlot--;
			}
			if (globalSlot >= 10) {
				slots.put(globalSlot, ConfigFields.GLOBAL);
			}
		}

		int size = seasonTwo ? 27 : 54;
		Fx.soundTo(player, SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.2F);
		DisplayMenu.open(player, size, LIST_TITLE, board -> {
			ItemStack pane = DisplayMenu.pane(Items.GRAY_STAINED_GLASS_PANE);
			for (int i = 0; i < 9; i++) {
				board.setItem(i, pane.copy());
			}
			for (int i = size - 9; i < size; i++) {
				board.setItem(i, pane.copy());
			}
			for (Map.Entry<Integer, String> entry : slots.entrySet()) {
				ItemStack icon = icon(entry.getValue());
				if (icon.isEmpty()) {
					continue;
				}
				List<String> lore = new ArrayList<>();
				lore.add("");
				lore.add(seasonTwo ? "<yellow>Click to edit this weapon's stats"
						: "<yellow>Click <gray>to edit this item's stats");
				lore.add("<gray>" + ConfigFields.forContent(entry.getValue()).size() + " editable value(s)");
				icon.set(net.minecraft.core.component.DataComponents.LORE,
						new net.minecraft.world.item.component.ItemLore(lore.stream().map(Messaging::msg).toList()));
				board.setItem(entry.getKey(), icon);
			}
		}, clicked -> {
			String id = slots.get(clicked);
			if (id != null) {
				openFields(player, id, seasonTwo);
			}
		});
	}

	/** The stat list for one item: a slot per editable value, each showing what it is set to. */
	private static void openFields(ServerPlayer player, String contentId, boolean seasonTwo) {
		List<Field> fields = ConfigFields.forContent(contentId);
		String name = displayName(contentId);
		if (fields.isEmpty()) {
			Messaging.send(player, "<yellow>" + name + " has no editable stats.");
			return;
		}
		int size = Math.max(9, (fields.size() + 3) / 9 * 9);
		Fx.soundTo(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.0F);
		DisplayMenu.open(player, size, Messaging.msg("<gold>" + name + " Config"), board -> {
			for (int i = 0; i < fields.size(); i++) {
				board.setItem(i, fieldIcon(fields.get(i)));
			}
			board.setItem(size - 2, DisplayMenu.button(Items.BARRIER, "<red>Close", List.of()));
			board.setItem(size - 1, DisplayMenu.button(Items.ARROW, "<gold>← Back",
					List.of("<gray>Legendary Config")));
		}, clicked -> {
			if (clicked == size - 1) {
				openList(player, seasonTwo);
				return;
			}
			if (clicked == size - 2) {
				player.closeContainer();
				return;
			}
			if (clicked < fields.size()) {
				openEditor(player, contentId, fields.get(clicked), seasonTwo);
			}
		});
	}

	/** The stepper for one value: presets, steps either way, and the value it holds now. */
	private static void openEditor(ServerPlayer player, String contentId, Field field, boolean seasonTwo) {
		int size = 27;
		Fx.soundTo(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.0F);
		DisplayMenu.open(player, size, Messaging.msg("<gold>" + field.label()), board -> {
			board.setItem(4, fieldIcon(field));
			if (field.kind() == ConfigFields.Kind.TOGGLE) {
				board.setItem(11, DisplayMenu.button(Items.LIME_CONCRETE, "<green>Set to true",
						List.of("<gray>" + field.path())));
				board.setItem(15, DisplayMenu.button(Items.RED_CONCRETE, "<red>Set to false",
						List.of("<gray>" + field.path())));
			} else {
				double step = field.kind() == ConfigFields.Kind.INTEGER ? 1.0D : field.step();
				double[] steps = {-step * 10.0D, -step * 4.0D, -step, step, step * 4.0D, step * 10.0D};
				int[] slots = {10, 11, 12, 14, 15, 16};
				for (int i = 0; i < steps.length; i++) {
					double delta = steps[i];
					board.setItem(slots[i], DisplayMenu.button(delta < 0.0D ? Items.RED_STAINED_GLASS_PANE
							: Items.LIME_STAINED_GLASS_PANE,
							(delta < 0.0D ? "<red>" : "<green>") + signed(delta),
							List.of("<gray>" + field.path())));
				}
				board.setItem(13, DisplayMenu.button(Items.CLOCK, "<yellow>Now: " + current(field),
						List.of("<gray>" + field.path())));
				board.setItem(2, DisplayMenu.button(Items.BLUE_CONCRETE, "<aqua>Set to minimum",
						List.of("<yellow>" + trim(field.min()))));
				board.setItem(6, DisplayMenu.button(Items.BLUE_CONCRETE, "<aqua>Set to maximum",
						List.of("<yellow>" + trim(field.max()))));
			}
			board.setItem(20, DisplayMenu.button(Items.ARROW, "<gold>← Back",
					List.of("<gray>" + displayName(contentId) + " Config")));
			board.setItem(24, DisplayMenu.button(Items.BARRIER, "<red>Close", List.of()));
		}, clicked -> {
			if (clicked == 20) {
				openFields(player, contentId, seasonTwo);
				return;
			}
			if (clicked == 24) {
				player.closeContainer();
				return;
			}
			Double target = editorTarget(field, clicked);
			if (target == null) {
				return;
			}
			apply(player, field, target);
			openEditor(player, contentId, field, seasonTwo);
		});
	}

	/** What a click in the editor window means, or null when it was not a value button. */
	private static Double editorTarget(Field field, int slot) {
		if (field.kind() == ConfigFields.Kind.TOGGLE) {
			return switch (slot) {
				case 11 -> 1.0D;
				case 15 -> 0.0D;
				default -> null;
			};
		}
		double step = field.kind() == ConfigFields.Kind.INTEGER ? 1.0D : field.step();
		return switch (slot) {
			case 10 -> clamp(field, current(field) - step * 10.0D);
			case 11 -> clamp(field, current(field) - step * 4.0D);
			case 12 -> clamp(field, current(field) - step);
			case 14 -> clamp(field, current(field) + step);
			case 15 -> clamp(field, current(field) + step * 4.0D);
			case 16 -> clamp(field, current(field) + step * 10.0D);
			case 2 -> field.min();
			case 6 -> field.max();
			default -> null;
		};
	}

	/**
	 * {@code a.d#b}: writes the value, reloads the config and every system that reads it, and says
	 * what happened in the plugin's own words.
	 */
	private static void apply(ServerPlayer player, Field field, double value) {
		double bounded = clamp(field, value);
		Object toWrite = switch (field.kind()) {
			case TOGGLE -> bounded != 0.0D;
			case INTEGER -> (int) Math.round(bounded);
			case DECIMAL -> roundToStep(bounded, field.step());
		};
		AltarSMPMod mod = AltarSMPMod.get();
		if (!mod.config().write(field.path(), toWrite)) {
			Messaging.send(player, "<red>Could not write " + field.path() + " - see the server log.");
			return;
		}
		mod.altars().refreshAllHolograms(mod.server());
		Fx.soundTo(player, SoundEvents.PLAYER_LEVELUP, 0.5F, 1.5F);
		Messaging.send(player, "<green>" + field.path() + " set to " + YamlLite.scalarText(toWrite)
				+ ". Config reloaded.");
	}

	/**
	 * {@code /legendaryconfig set <entry> <path> <value>}: the typed form of the same edit, with the
	 * plugin's parsing rules and its refusal messages.
	 *
	 * @return true when the value was written
	 */
	public static boolean set(ServerPlayer player, String contentId, String path, String text) {
		Field field = ConfigFields.forContent(contentId).stream()
				.filter(candidate -> candidate.path().equalsIgnoreCase(path))
				.findFirst()
				.orElse(null);
		if (field == null) {
			Messaging.send(player, "<red>No such value for " + displayName(contentId) + ": " + path);
			return false;
		}
		double value;
		try {
			value = parse(field, text);
		} catch (IllegalArgumentException e) {
			Messaging.send(player, "<red>" + e.getMessage());
			Messaging.send(player, "<gray>" + allowed(field));
			return false;
		}
		apply(player, field, value);
		return true;
	}

	/** {@code a.d#a(String, c)}: the plugin's per-kind parsing, messages included. */
	private static double parse(Field field, String text) {
		String trimmed = text == null ? "" : text.trim();
		switch (field.kind()) {
			case INTEGER -> {
				int value;
				try {
					value = Integer.parseInt(trimmed);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Enter a valid whole number.");
				}
				if (value < field.min() || value > field.max()) {
					throw new IllegalArgumentException("Enter a whole number from " + (int) field.min() + " to "
							+ (int) field.max() + ".");
				}
				return value;
			}
			case DECIMAL -> {
				double value;
				try {
					value = Double.parseDouble(trimmed);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Enter a valid number.");
				}
				if (!Double.isFinite(value) || value < field.min() || value > field.max()) {
					throw new IllegalArgumentException("Enter a number from " + trim(field.min()) + " to "
							+ trim(field.max()) + ".");
				}
				return value;
			}
			default -> {
				return switch (trimmed.toLowerCase(Locale.ROOT)) {
					case "true", "yes", "on", "1" -> 1.0D;
					case "false", "no", "off", "0" -> 0.0D;
					default -> throw new IllegalArgumentException("Enter true or false.");
				};
			}
		}
	}

	/** {@code a.d#a(c)}: the range line the plugin printed under its prompt. */
	public static String allowed(Field field) {
		return switch (field.kind()) {
			case INTEGER -> "Allowed: whole numbers " + (int) field.min() + " to " + (int) field.max() + ".";
			case DECIMAL -> "Allowed: " + trim(field.min()) + " to " + trim(field.max()) + ".";
			case TOGGLE -> "Allowed: true or false.";
		};
	}

	/** The value a field holds right now, read the way the plugin read it. */
	private static double current(Field field) {
		AltarSMPMod mod = AltarSMPMod.get();
		return switch (field.kind()) {
			case INTEGER -> mod.config().getInt(field.path(), (int) field.min());
			case DECIMAL -> mod.config().getDouble(field.path(), field.min());
			case TOGGLE -> mod.config().getBoolean(field.path(), false) ? 1.0D : 0.0D;
		};
	}

	private static double clamp(Field field, double value) {
		if (field.kind() == ConfigFields.Kind.TOGGLE) {
			return value != 0.0D ? 1.0D : 0.0D;
		}
		return Math.max(field.min(), Math.min(field.max(), value));
	}

	/** Keeps a stepped decimal on the field's own grid instead of accumulating binary error. */
	private static double roundToStep(double value, double step) {
		if (step <= 0.0D) {
			return value;
		}
		return Math.round(value / step) * step;
	}

	private static String trim(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1.0E15D) {
			return String.valueOf((long) value);
		}
		return String.valueOf(value);
	}

	private static String signed(double delta) {
		return (delta > 0.0D ? "+" : "") + trim(delta);
	}

	/** The list icon: the item itself, or the plugin's redstone block for the global entry. */
	private static ItemStack icon(String contentId) {
		if (ConfigFields.GLOBAL.equals(contentId)) {
			ItemStack stack = new ItemStack(Items.REDSTONE_BLOCK);
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, GLOBAL_ICON_NAME);
			return stack;
		}
		Optional<ItemStack> built = ItemFactory.content(contentId);
		if (built.isEmpty()) {
			AltarSMPMod.LOGGER.warn("[legendaryconfig] the catalogue cannot build '{}'", contentId);
			return ItemStack.EMPTY;
		}
		return built.get();
	}

	/** One editable value as a button: its label, what it is now, and what it accepts. */
	private static ItemStack fieldIcon(Field field) {
		List<String> lore = new ArrayList<>();
		lore.add("<gray>" + field.path());
		lore.add("<yellow>Current: " + describe(field));
		lore.add("<gray>" + allowed(field));
		lore.add("");
		lore.add("<green>Click to change");
		return DisplayMenu.button(Items.NAME_TAG, "<yellow>" + field.label(), lore);
	}

	private static String describe(Field field) {
		if (field.kind() == ConfigFields.Kind.TOGGLE) {
			return current(field) != 0.0D ? "true" : "false";
		}
		return trim(current(field));
	}

	/** The catalogue's display name for an entry, without markup, for window titles. */
	private static String displayName(String contentId) {
		if (ConfigFields.GLOBAL.equals(contentId)) {
			return "Global Settings";
		}
		Optional<ItemStack> built = ItemFactory.content(contentId);
		if (built.isEmpty()) {
			return contentId;
		}
		return TextFx.strip(built.get().getHoverName().getString());
	}

	/** Every entry the editor knows, for the {@code set} subcommand's suggestions. */
	public static List<String> entryIds() {
		return List.copyOf(ConfigFields.contentIds());
	}

	/** The config paths one entry exposes, for suggestions. */
	public static List<String> fieldPaths(String contentId) {
		return ConfigFields.forContent(contentId).stream().map(Field::path).toList();
	}
}
