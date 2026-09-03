package com.altarsmp.fabric.item;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The "is this block part of the vein" test shared by both copper pickaxes,
 * ported verbatim from {@code com.altarsmp.items.CopperPickaxe#isMineable} and
 * {@code CopperPickaxeII#isMineable} (the two lists are identical).
 *
 * <p>The original matched Bukkit {@code Material} names by substring; the port
 * matches the vanilla block id path with the same substrings so behaviour is
 * unchanged and new blocks that fit the pattern (for example future ore types)
 * keep working.</p>
 */
public final class MineableBlocks {

	private static final String[] SUBSTRINGS = {
			"stone", "ore", "deepslate", "granite", "diorite", "andesite", "terracotta",
			"sandstone", "netherrack", "basalt", "blackstone", "cobble", "brick",
			"obsidian", "end_stone", "prismarine", "quartz", "calcite", "tuff",
			"dripstone", "amethyst", "copper"
	};

	private static final Set<Identifier> EXACT = Set.of(
			Identifier.fromNamespaceAndPath("minecraft", "dirt"),
			Identifier.fromNamespaceAndPath("minecraft", "grass_block"),
			Identifier.fromNamespaceAndPath("minecraft", "gravel"),
			Identifier.fromNamespaceAndPath("minecraft", "sand"),
			Identifier.fromNamespaceAndPath("minecraft", "clay"),
			Identifier.fromNamespaceAndPath("minecraft", "soul_sand"),
			Identifier.fromNamespaceAndPath("minecraft", "soul_soil"),
			Identifier.fromNamespaceAndPath("minecraft", "mycelium"),
			Identifier.fromNamespaceAndPath("minecraft", "podzol"),
			Identifier.fromNamespaceAndPath("minecraft", "glowstone"),
			Identifier.fromNamespaceAndPath("minecraft", "ice"),
			Identifier.fromNamespaceAndPath("minecraft", "packed_ice"),
			Identifier.fromNamespaceAndPath("minecraft", "blue_ice"),
			Identifier.fromNamespaceAndPath("minecraft", "snow_block")
	);

	private MineableBlocks() {
	}

	/** {@code true} when {@code state} is a block the copper pickaxes will mine in bulk. */
	public static boolean isMineable(Level level, BlockPos pos, BlockState state) {
		if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
			return false;
		}
		// A negative destroy speed means unbreakable in survival (bedrock-like).
		if (state.getDestroySpeed(level, pos) < 0.0F) {
			return false;
		}
		Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (EXACT.contains(id)) {
			return true;
		}
		String path = id.getPath();
		for (String token : SUBSTRINGS) {
			if (path.contains(token)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Breaks {@code pos} through the player's own game mode so drops, XP, stats
	 * and {@code PlayerBlockBreakEvents} all fire exactly as a normal swing would.
	 *
	 * @return {@code true} when the block was actually removed
	 */
	public static boolean breakFor(net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
		return player.gameMode.destroyBlock(pos);
	}
}
