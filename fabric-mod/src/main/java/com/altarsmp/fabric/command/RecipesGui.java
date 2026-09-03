package com.altarsmp.fabric.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.Block;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * {@code /recipes} — the crafting browser from AltarSMPS2's {@code RecipesCommand}.
 *
 * <p>Season 2's table is the one the merged plugin actually registered; the season 1 class of the
 * same name was never wired to a command. Each entry shows the crafted item and the nine grid slots
 * that make it, so a player can see what an altar craft costs before spending the ingredients.
 * Everything is display only: the window consumes every click and never lets an item leave.
 */
public final class RecipesGui {
	/** One row of the index: the crafted item plus the nine grid slots that make it. */
	private record Entry(String key, ItemStack result, ItemStack[] grid) {}

	private static final Component INDEX_TITLE = Messaging.msg("<dark_gray>Recipe List");
	private static final String DETAIL_TITLE = "<dark_gray>Recipe: ";
	private static final int[] GRID_SLOTS = {0, 1, 2, 9, 10, 11, 18, 19, 20};

	private RecipesGui() {}

	/** Opens the recipe index; every craft the config enables gets a slot. */
	public static void open(ServerPlayer player) {
		List<Entry> shown = new ArrayList<>(enabled().values());
		if (shown.isEmpty()) {
			Messaging.send(player, "<red>Every recipe in the table is disabled in the config.");
			return;
		}
		int size = Math.max(9, Math.min(54, ((shown.size() + 1) / 9 + 1) * 9));

		SimpleContainer board = new SimpleContainer(size);
		board.setItem(0, blockedSlot());
		for (int i = 0; i < shown.size(); i++) {
			board.setItem(1 + i, shown.get(i).result().copy());
		}

		Fx.soundTo(player, SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.2F);
		openMenu(player, board, size, INDEX_TITLE, slot -> {
			int index = slot - 1;
			if (index >= 0 && index < shown.size()) {
				openDetail(player, shown.get(index));
			}
		});
	}

	/** Every recipe the table knows, filtered by {@code recipes-enabled.<key>} in the config. */
	private static Map<String, Entry> enabled() {
		Map<String, Entry> all = definitions();
		Map<String, Entry> shown = new LinkedHashMap<>();
		AltarSMPMod mod = AltarSMPMod.get();
		for (Map.Entry<String, Entry> entry : all.entrySet()) {
			if (mod == null || mod.config().getBoolean("recipes-enabled." + entry.getKey(), true)) {
				shown.put(entry.getKey(), entry.getValue());
			}
		}
		return shown;
	}

	/** {@code RecipesCommand#register}'s table, in its insertion order. */
	private static Map<String, Entry> definitions() {
		Map<String, Entry> all = new LinkedHashMap<>();
		all.put("amethyst_pickaxe", new Entry("amethyst_pickaxe", content("amethystpickaxe"), new ItemStack[]{
				block(Items.AMETHYST_BLOCK), block(Items.AMETHYST_BLOCK), block(Items.DIAMOND_BLOCK),
				item(Items.NETHERITE_INGOT), item(Items.STICK), item(Items.NETHERITE_INGOT),
				null, item(Items.STICK), null}));
		all.put("amethyst_axe", new Entry("amethyst_axe", content("amethystaxe"), new ItemStack[]{
				block(Items.AMETHYST_BLOCK), block(Items.DIAMOND_BLOCK), null,
				block(Items.AMETHYST_BLOCK), item(Items.STICK), null,
				null, item(Items.STICK), null}));
		all.put("soul_in_a_bottle", new Entry("soul_in_a_bottle", content("soulinabottle"), new ItemStack[]{
				block(Items.SCULK_SHRIEKER), block(Items.SCULK_SHRIEKER), block(Items.SCULK_SHRIEKER),
				block(Items.SCULK_SHRIEKER), block(Items.RECOVERY_COMPASS), block(Items.SCULK_SHRIEKER),
				block(Items.SCULK_SHRIEKER), item(Items.GLASS_BOTTLE), block(Items.SCULK_SHRIEKER)}));
		all.put("weapon_handle", new Entry("weapon_handle", content("weaponhandle"), new ItemStack[]{
				item(Items.NETHERITE_INGOT), item(Items.NETHER_STAR), item(Items.NETHERITE_INGOT),
				null, block(Items.REDSTONE_BLOCK), null,
				null, null, null}));
		all.put("cobweb", new Entry("cobweb", item(Items.COBWEB), new ItemStack[]{
				item(Items.STRING), null, item(Items.STRING),
				null, item(Items.STRING), null,
				item(Items.STRING), null, item(Items.STRING)}));
		all.put("golden_apple", new Entry("golden_apple", item(Items.GOLDEN_APPLE), new ItemStack[]{
				null, item(Items.GOLD_INGOT), null,
				item(Items.GOLD_INGOT), item(Items.APPLE), item(Items.GOLD_INGOT),
				null, item(Items.GOLD_INGOT), null}));
		all.put("firework_bundle", new Entry("firework_bundle", firework(), new ItemStack[]{
				item(Items.PAPER), item(Items.GUNPOWDER), null,
				null, null, null, null, null, null}));
		all.put("long_strength_splash", new Entry("long_strength_splash", strengthSplash(), new ItemStack[]{
				strongStrengthPotion(), item(Items.GUNPOWDER), null,
				null, null, null, null, null, null}));
		all.put("black_ghast_saddle", new Entry("black_ghast_saddle", content("blackghastsaddle"), new ItemStack[]{
				item(Items.LEATHER), item(Items.LEATHER), item(Items.LEATHER),
				block(Items.GLASS), item(Items.NETHERITE_SCRAP), block(Items.GLASS),
				null, null, null}));
		return all;
	}

	/** Opens one recipe: its grid on the left, an arrow, then the crafted item. */
	private static void openDetail(ServerPlayer player, Entry entry) {
		SimpleContainer board = new SimpleContainer(27);
		for (int i = 0; i < GRID_SLOTS.length; i++) {
			ItemStack ingredient = entry.grid()[i];
			board.setItem(GRID_SLOTS[i], ingredient == null ? ItemStack.EMPTY : ingredient.copy());
		}
		board.setItem(14, arrowSlot());
		board.setItem(16, entry.result().copy());
		Fx.soundTo(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.0F);
		openMenu(player, board, 27, Messaging.msg(DETAIL_TITLE + nameOf(entry.result())), slot -> {});
	}

	/**
	 * Shows a read-only chest window. Clicks are answered by {@code clicked} without ever reaching
	 * the container, so nothing can be taken out of a recipe.
	 */
	private static void openMenu(ServerPlayer player, SimpleContainer board, int slots, Component title,
			IntConsumer onClick) {
		int rows = slots / 9;
		player.openMenu(new MenuProvider() {
			@Override
			public Component getDisplayName() {
				return title;
			}

			@Override
			public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player ignored) {
				return new ReadOnlyMenu(containerId, player, board, rows, onClick);
			}
		});
	}

	/** The catalogue entry for a plugin item, or an empty stack when the id cannot be built. */
	private static ItemStack content(String contentId) {
		Optional<ItemStack> built = ItemFactory.content(contentId);
		if (built.isEmpty()) {
			AltarSMPMod.LOGGER.warn("[recipes] the catalogue cannot build '{}' for the recipe table", contentId);
			return ItemStack.EMPTY;
		}
		return built.get();
	}

	private static String nameOf(ItemStack stack) {
		return stack.isEmpty() ? "unknown" : stack.getHoverName().getString();
	}

	private static ItemStack item(Item item) {
		return new ItemStack(item);
	}

	private static ItemStack block(Block block) {
		return new ItemStack(block);
	}

	/** The power-3 rocket of the firework bundle recipe. */
	private static ItemStack firework() {
		ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
		stack.set(DataComponents.FIREWORKS, new Fireworks(3, List.of()));
		return stack;
	}

	/** Splash potion of Strength II for eight minutes, tinted the way the plugin brewed it. */
	private static ItemStack strengthSplash() {
		ItemStack stack = new ItemStack(Items.SPLASH_POTION);
		stack.set(DataComponents.POTION_CONTENTS, new PotionContents(
				Optional.empty(),
				Optional.of((149 << 16) | (38 << 8) | 38),
				List.of(new MobEffectInstance(MobEffects.STRENGTH, 9600, 1, false, true, true)),
				Optional.empty()));
		stack.set(DataComponents.CUSTOM_NAME, Messaging.msg("<white>Splash Potion of Strength II (8:00)"));
		return stack;
	}

	private static ItemStack strongStrengthPotion() {
		ItemStack stack = new ItemStack(Items.POTION);
		stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_STRENGTH));
		return stack;
	}

	private static ItemStack blockedSlot() {
		ItemStack stack = new ItemStack(Items.BARRIER);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(" ").withStyle(ChatFormatting.RED));
		return stack;
	}

	private static ItemStack arrowSlot() {
		ItemStack stack = new ItemStack(Items.ARROW);
		stack.set(DataComponents.CUSTOM_NAME, Messaging.msg("<gray>Crafts"));
		return stack;
	}

	/** Chest window that never lets an item leave it. */
	private static final class ReadOnlyMenu extends ChestMenu {
		private final int contentSize;
		private final IntConsumer onClick;

		private ReadOnlyMenu(int containerId, Player player, SimpleContainer board, int rows, IntConsumer onClick) {
			super(menuType(rows), containerId, player.getInventory(), board, rows);
			this.contentSize = rows * 9;
			this.onClick = onClick;
		}

		private static MenuType<ChestMenu> menuType(int rows) {
			return switch (rows) {
				case 1 -> MenuType.GENERIC_9x1;
				case 2 -> MenuType.GENERIC_9x2;
				case 3 -> MenuType.GENERIC_9x3;
				case 4 -> MenuType.GENERIC_9x4;
				case 5 -> MenuType.GENERIC_9x5;
				default -> MenuType.GENERIC_9x6;
			};
		}

		@Override
		public void clicked(int slotId, int button, ClickType clickType, Player player) {
			// Deliberately never calls super: the window is a display, and any move would desync.
			if (slotId >= 0 && slotId < this.contentSize) {
				this.onClick.accept(slotId);
			}
		}
	}
}
