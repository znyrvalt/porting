package com.altarsmp.fabric.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.core.component.DataComponents;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.item.ItemFactory;
import com.altarsmp.fabric.util.Fx;
import com.altarsmp.fabric.util.Messaging;

/**
 * {@code /legendaries} — the item browser from AltarSMP's {@code LegendariesGUI}, with the season 2
 * legendaries on a second page ({@code Legendaries2Command}).
 *
 * <p>Upstream shipped two windows: season 1's, and a season 2 one that refused to open whenever
 * season 1 was loaded, telling the player to look in {@code /legendaries} instead — while season 1's
 * own page 2 sat unreachable in the code, its navigation arrow and click handler included. Both
 * pages are wired up here at the slot numbers the plugin used, so every legendary in the catalogue
 * can be browsed and, for operators, received with a click.
 */
public final class LegendariesGui {
	private static final Component PAGE_ONE_TITLE = Messaging.msg("<dark_purple><bold>LEGENDARIES");
	private static final Component PAGE_TWO_TITLE = Messaging.msg("<aqua><bold>SEASON 2 LEGENDARIES");

	private LegendariesGui() {}

	/** Opens the browser on season 1's page. */
	public static void open(ServerPlayer player) {
		open(player, 1);
	}

	/** Opens the browser on the given page: 1 = season 1 legendaries, 2 = season 2. */
	public static void open(ServerPlayer player, int page) {
		Map<Integer, String> entries = page == 2 ? pageTwoEntries() : pageOneEntries();
		boolean operator = player.hasPermissions(2);

		Fx.soundTo(player, SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.2F);
		DisplayMenu.open(player, 54, page == 2 ? PAGE_TWO_TITLE : PAGE_ONE_TITLE, board -> {
			net.minecraft.world.item.Item paneItem =
					page == 2 ? Items.CYAN_STAINED_GLASS_PANE : Items.PURPLE_STAINED_GLASS_PANE;
			ItemStack pane = DisplayMenu.pane(paneItem);
			for (int slot = 0; slot < 9; slot++) {
				board.setItem(slot, pane.copy());
			}
			if (page == 2) {
				for (int slot = 45; slot < 54; slot++) {
					board.setItem(slot, pane.copy());
				}
			}

			for (Map.Entry<Integer, String> entry : entries.entrySet()) {
				Optional<ItemStack> built = ItemFactory.content(entry.getValue(), player);
				if (built.isEmpty()) {
					AltarSMPMod.LOGGER.warn("[legendaries] page {} slot {}: the catalogue cannot build '{}'",
							page, entry.getKey(), entry.getValue());
					continue;
				}
				ItemStack stack = built.get();
				List<Component> lore = new ArrayList<>();
				if (page == 2) {
					lore.add(Component.empty());
				}
				lore.add(Messaging.msg("<dark_gray>/" + entry.getValue()));
				if (operator) {
					lore.add(Messaging.msg("<yellow>Click to receive"));
				}
				stack.set(DataComponents.LORE, new ItemLore(lore));
				board.setItem(entry.getKey(), stack);
			}

			if (page == 1) {
				board.setItem(53, DisplayMenu.button(Items.ARROW, "<gold>→ Next Page",
						List.of("<gray>Season 2 Legendaries")));
			} else {
				board.setItem(45, DisplayMenu.button(Items.ARROW, "<gold>← Previous Page",
						List.of("<gray>AltarSMP Items")));
			}
		}, slot -> {
			if (page == 1 && slot == 53) {
				open(player, 2);
				return;
			}
			if (page == 2 && slot == 45) {
				open(player, 1);
				return;
			}
			String contentId = entries.get(slot);
			if (contentId == null || !operator) {
				return;
			}
			ItemFactory.content(contentId, player).ifPresent(stack -> receive(player, stack));
		});
	}

	/** Season 1's page, at the slot numbers {@code LegendariesGUI#buildPage1Entries} used. */
	private static Map<Integer, String> pageOneEntries() {
		Map<Integer, String> entries = new LinkedHashMap<>();
		entries.put(11, "copperhelmet");
		entries.put(12, "copperchestplate");
		entries.put(13, "copperleggings");
		entries.put(14, "copperboots");
		entries.put(15, "copperpickaxe");
		entries.put(18, "pureblade");
		entries.put(19, "hyperion");
		entries.put(20, "bloodlust");
		entries.put(21, "vulcanscrossbow");
		entries.put(22, "wandofillusion");
		entries.put(23, "frostscythe");
		entries.put(24, "nightpiercer");
		entries.put(25, "boneblade");
		entries.put(26, "palecrossbow");
		entries.put(27, "windweaver");
		entries.put(28, "witherbone");
		entries.put(29, "shadowblade");
		entries.put(30, "earthgauntlet");
		entries.put(31, "paladinbattleaxe");
		entries.put(32, "cutlass");
		entries.put(33, "crazyslots");
		entries.put(34, "contagionsignal");
		entries.put(36, "eclipsesword");
		entries.put(37, "knightfall");
		entries.put(38, "striker");
		entries.put(39, "nukelauncher");
		entries.put(40, "echo");
		entries.put(43, "copperfragment");
		entries.put(44, "chestplateshard");
		entries.put(46, "hyperionshard");
		entries.put(47, "nightpiercershard");
		entries.put(48, "illusioncore");
		entries.put(49, "vulkanhead");
		entries.put(50, "paleshard");
		entries.put(51, "copperpickaxeupgrade");
		return entries;
	}

	/**
	 * Season 2's page: weapons along row two, materials along row three, at
	 * {@code Legendaries2Command}'s slots. The sixth weapon takes slot 24 — upstream ran out of
	 * weapon slots and silently dropped the Bow of Deception and Lies from the browser.
	 */
	private static Map<Integer, String> pageTwoEntries() {
		Map<Integer, String> entries = new LinkedHashMap<>();
		entries.put(19, "omen");
		entries.put(20, "ancientblade");
		entries.put(21, "withersymbiote");
		entries.put(22, "tidebreaker");
		entries.put(23, "dragonrend");
		entries.put(24, "bowofdeceptionandlies");
		entries.put(28, "soulinabottle");
		entries.put(29, "fragmentofthesea");
		entries.put(30, "dragonheart");
		entries.put(31, "amethystpickaxe");
		entries.put(32, "amethystaxe");
		entries.put(33, "blackghastsaddle");
		return entries;
	}

	/** Hands an item to an operator, dropping it at their feet when their inventory is full. */
	private static void receive(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack.copy())) {
			player.drop(stack.copy(), false);
		}
		Fx.soundTo(player, SoundEvents.PLAYER_LEVELUP, 0.5F, 1.5F);
	}
}
