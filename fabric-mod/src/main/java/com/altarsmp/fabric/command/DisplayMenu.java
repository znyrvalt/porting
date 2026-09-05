package com.altarsmp.fabric.command;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import com.altarsmp.fabric.util.Messaging;

/**
 * The read-only window every admin GUI in the port is built on.
 *
 * <p>The plugin built its browsers with {@code Bukkit.createInventory(holder, size, title)} and
 * cancelled every {@code InventoryClickEvent}; the equivalent here is a chest menu whose
 * {@link #clicked} answers the click itself and never calls {@code super}, so no slot can ever be
 * picked up, shifted, split or dropped. The container is a {@link SimpleContainer} the caller fills
 * once, which makes the window a display rather than an inventory.
 *
 * <p>Clicks are answered on the server only. In singleplayer the same class also runs on the client
 * half of the integrated server pair, where {@code clicked} fires a second time for prediction; the
 * {@code ServerPlayer} test keeps a navigation click from opening two windows and an editor click
 * from applying its step twice.
 */
public final class DisplayMenu extends ChestMenu {

	private final int contentSize;
	private final IntConsumer onClick;

	private DisplayMenu(int containerId, Player player, SimpleContainer board, int rows, IntConsumer onClick) {
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

	/**
	 * Opens a display window.
	 *
	 * @param player  who sees it
	 * @param slots   window size, a multiple of nine between 9 and 54
	 * @param title   the window title
	 * @param fill    lays the items out into the container before the window opens
	 * @param onClick answers a click on one of the window's own slots, server side
	 */
	public static void open(ServerPlayer player, int slots, Component title, Consumer<SimpleContainer> fill,
			IntConsumer onClick) {
		int size = Math.max(9, Math.min(54, (slots + 8) / 9 * 9));
		SimpleContainer board = new SimpleContainer(size);
		fill.accept(board);
		int rows = size / 9;
		player.openMenu(new MenuProvider() {
			@Override
			public Component getDisplayName() {
				return title;
			}

			@Override
			public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player ignored) {
				return new DisplayMenu(containerId, player, board, rows, onClick);
			}
		});
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player player) {
		// Deliberately never calls super: the window is a display, and any move would desync it.
		if (!(player instanceof ServerPlayer) || slotId < 0 || slotId >= this.contentSize) {
			return;
		}
		this.onClick.accept(slotId);
	}

	/** A border pane: the plugin filled its window edges with named glass. */
	public static ItemStack pane(Item item) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Messaging.msg("<white> "));
		return stack;
	}

	/** A labelled button with lore lines, already parsed from the port's markup. */
	public static ItemStack button(Item item, String name, List<String> lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Messaging.msg(name));
		if (!lore.isEmpty()) {
			List<Component> lines = lore.stream().map(Messaging::msg).toList();
			stack.set(DataComponents.LORE, new ItemLore(lines));
		}
		return stack;
	}
}
