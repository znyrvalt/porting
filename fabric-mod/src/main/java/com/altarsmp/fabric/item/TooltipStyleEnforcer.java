package com.altarsmp.fabric.item;

import java.util.Map;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import com.altarsmp.fabric.AltarSMPMod;

/**
 * Port of {@code com.altarsmp.listeners.TooltipStyleEnforcer}.
 *
 * <p>Legendary items only get their framed tooltip while the resource pack is
 * active, and a stack that has been through an anvil, a grindstone or an older
 * version of the plugin can lose the {@code tooltip_style} component. The
 * original re-applied it one tick after join, inventory click and held-item
 * change; the port re-applies it on the same triggers plus a slow safety sweep,
 * and honours {@code cosmetics.tooltip_debug} for the diagnostic output.</p>
 */
public final class TooltipStyleEnforcer {

	private static final int SWEEP_INTERVAL_TICKS = 20;
	private static boolean registered;

	private TooltipStyleEnforcer() {
	}

	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				scan(player);
			}
		});
	}

	public static void scan(ServerPlayer player) {
		if (player == null || player.isRemoved()) {
			return;
		}
		boolean debug = AltarSMPMod.get().config().getBoolean("cosmetics.tooltip_debug", false);
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			String id = Identity.idOf(stack);
			if (id == null) {
				continue;
			}
			String path = ItemFactory.tooltipStylePath(id);
			if (path == null) {
				if (debug) {
					AltarSMPMod.LOGGER.info("[TooltipDebug] no mapping for id='{}' on slot {}", id, slot);
				}
				continue;
			}
			Identifier expected = Identifier.fromNamespaceAndPath(ItemFactory.TOOLTIP_NAMESPACE, path);
			Identifier current = stack.get(DataComponents.TOOLTIP_STYLE);
			if (expected.equals(current)) {
				continue;
			}
			stack.set(DataComponents.TOOLTIP_STYLE, expected);
			if (debug) {
				AltarSMPMod.LOGGER.info("[TooltipDebug] applied {} to {} in slot {}", expected, id, slot);
			}
		}
	}

	public static void clear(ServerPlayer player) {
		// Nothing is held per player; the sweep is stateless.
	}

	/** Diagnostics for {@code /altarsmp debug}. */
	public static Map<String, String> mapping() {
		return Map.of();
	}
}
