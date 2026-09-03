package com.altarsmp.fabric.trial;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.altarsmp.fabric.util.Messaging;

/**
 * The Bingo task board - the port of {@code BingoEvent#openTasksGUI}'s 54-slot
 * inventory.
 *
 * <p>Bukkit created a plain chest inventory titled <b>Bingo Tasks</b>, filled it
 * with glass borders and one item per task, cancelled every click and drag in it,
 * and turned a click into {@code attemptCompleteTask}. Vanilla menus have no
 * "cancel the click" hook a mod can subscribe to from outside, so the board is a
 * real {@link ChestMenu} whose {@link #clicked} override simply never calls
 * {@code super}: nothing can ever be taken out of or put into it, and a click on a
 * task slot goes straight to the trial, exactly as the cancelled event did.
 *
 * <p>The layout is the plugin's own: yellow panes across the top and bottom rows,
 * orange panes down both border columns, 28 tasks in the 7x4 grid, seven more along
 * the bottom row, and the last five in the border slots 45, 53, 9, 18 and 27.
 */
public final class BingoTasksMenu extends ChestMenu {

	static final Component TITLE = Messaging.msg("<gold><bold>Bingo Tasks");
	private static final int ROWS = 6;
	private static final int SIZE = ROWS * 9;
	private static final int[] BORDER_SLOTS = {9, 17, 18, 26, 27, 35, 36, 44};

	private final BingoEvent event;
	private final ServerPlayer viewer;

	private BingoTasksMenu(int containerId, ServerPlayer viewer, SimpleContainer board, BingoEvent event) {
		super(MenuType.GENERIC_9x6, containerId, viewer.getInventory(), board, ROWS);
		this.viewer = viewer;
		this.event = event;
	}

	/** Builds the board for this player's progress and opens it. */
	static void open(ServerPlayer player, BingoEvent event) {
		SimpleContainer board = new SimpleContainer(SIZE);
		fill(board, player, event);
		player.openMenu(new MenuProvider() {
			@Override
			public Component getDisplayName() {
				return TITLE;
			}

			@Override
			public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player opening) {
				return new BingoTasksMenu(containerId, player, board, event);
			}
		});
	}

	private static void fill(SimpleContainer board, ServerPlayer player, BingoEvent event) {
		ItemStack header = pane(Items.YELLOW_STAINED_GLASS_PANE, "<gold><bold>BINGO EVENT");
		ItemStack border = pane(Items.ORANGE_STAINED_GLASS_PANE, " ");
		for (int slot = 0; slot < 9; slot++) {
			board.setItem(slot, header.copy());
		}
		for (int slot = 45; slot < SIZE; slot++) {
			board.setItem(slot, header.copy());
		}
		for (int slot : BORDER_SLOTS) {
			board.setItem(slot, border.copy());
		}

		java.util.Set<Integer> done = event.completedBy(player.getUUID());
		int task = 0;
		for (int slot : BingoEvent.GRID_SLOTS) {
			if (task >= 28) {
				break;
			}
			board.setItem(slot, BingoEvent.createTaskItem(BingoEvent.TASKS[task], done.contains(task), task + 1));
			task++;
		}
		for (int slot : BingoEvent.BOTTOM_SLOTS) {
			if (task >= 35) {
				break;
			}
			board.setItem(slot, BingoEvent.createTaskItem(BingoEvent.TASKS[task], done.contains(task), task + 1));
			task++;
		}
		for (int i = 0; i < BingoEvent.CORNER_SLOTS.length; i++) {
			int index = BingoEvent.CORNER_TASKS[i];
			board.setItem(BingoEvent.CORNER_SLOTS[i],
					BingoEvent.createTaskItem(BingoEvent.TASKS[index], done.contains(index), index + 1));
		}
	}

	private static ItemStack pane(net.minecraft.world.level.ItemLike item, String markup) {
		ItemStack stack = new ItemStack(item);
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Messaging.msg(markup).copy()
				.withStyle(style -> style.withItalic(false)));
		return stack;
	}

	/**
	 * The board is display-only. Not calling {@code super} is what replaces Bukkit's
	 * cancelled {@code InventoryClickEvent} and {@code InventoryDragEvent}: no click
	 * type - pick up, shift-click, swap, clone, throw or drag - can move an item,
	 * and a task slot click runs the task instead.
	 */
	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player player) {
		int index = BingoEvent.taskIndexFromSlot(slotId);
		if (index >= 0 && player instanceof ServerPlayer serverPlayer) {
			this.event.attemptCompleteTask(serverPlayer, index);
		}
	}

	/** The board must not close when the trial ends under it - {@code stop()} does that. */
	@Override
	public boolean stillValid(Player player) {
		return this.event.isRunning() && !this.viewer.isRemoved();
	}
}
