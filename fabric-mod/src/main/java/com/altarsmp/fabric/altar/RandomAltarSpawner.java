package com.altarsmp.fabric.altar;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import com.altarsmp.fabric.AltarSMPMod;
import com.altarsmp.fabric.item.Identity;

/**
 * The pillar altars {@code /spawnaltarrandom} raises: {@code SpawnAltarRandomCommand.java}
 * lines 103-845 - the dressed ground, the cleared column, the six pillar shapes, the deck
 * and the furniture.
 *
 * <p>Two deliberate differences from the plugin:
 * <ul>
 * <li>The plugin finished each pillar by spawning an invisible armour stand whose name tag
 * carried the recipe lines ({@code a(Location, String):846}). That stand was not an altar:
 * nothing recorded it, clicking it did nothing, and {@code /destroyaltars} never saw it. This
 * hands the same position to {@link AltarManager#createAltarAt} instead, so a pillar altar is
 * a real altar - craftable from its config recipe, kept in the altar store, swept by
 * {@code /destroyaltars}.</li>
 * <li>The plugin carried three pillar shapes no type ever selected ({@code c():270},
 * {@code d():326}, {@code f():438}). They are not ported, because nothing reaches them.</li>
 * </ul>
 *
 * <p>Operator note: one pillar is a little over six thousand block updates and a single
 * command places five of them, so expect a tick spike. The plugin cost the same.
 */
public final class RandomAltarSpawner {

	/** The types the command accepts and tab-completes ({@code :32}). */
	public static final List<String> TYPES = List.of("hyperionshard", "nightpiercershard", "vulkanhead",
			"illusioncore", "weaponhandle", "paleshard");

	/** Pillars placed per command ({@code :33}). */
	public static final int PILLARS = 5;
	/** Site attempts before the command gives up ({@code :73}). */
	public static final int ATTEMPTS = 100;

	/** Every shape builds to y = 30 ({@code :34}) and decks at y = 31 ({@code l():708}). */
	private static final int PILLAR_HEIGHT = 30;
	private static final int DECK_Y = 31;
	/** The column is cleared before the shape goes up: 11 wide, 36 tall ({@code a():187}). */
	private static final int CLEAR_RADIUS = 5;
	private static final int CLEAR_HEIGHT = 36;
	/** How far out the surrounding ground is dressed ({@code a():114}). */
	private static final int DRESS_RADIUS = 15;

	private static final int[][] CORNERS_2 = {{-2, -2}, {-2, 2}, {2, -2}, {2, 2}};
	private static final int[][] CORNERS_3 = {{-3, -3}, {-3, 3}, {3, -3}, {3, 3}};
	private static final int[][] CORNERS_4 = {{-4, -4}, {-4, 4}, {4, -4}, {4, 4}};
	private static final int[][] CARDINALS_2 = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
	private static final int[][] CARDINALS_3 = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}};
	/** The eight columns Vulkan Head puts on its edges, between the corners ({@code i():591}). */
	private static final int[][] EDGE_COLUMNS = {{-5, -2}, {-5, 2}, {5, -2}, {5, 2},
			{-2, -5}, {2, -5}, {-2, 5}, {2, 5}};

	/** What gets scattered over dressed ground, picked from the palette's first block ({@code a():168}). */
	private static final Block[] SCULK_SCATTER = {Blocks.SCULK_VEIN, Blocks.SCULK_SENSOR, Blocks.SOUL_LANTERN};
	private static final Block[] NETHER_SCATTER = {Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CRIMSON_FUNGUS};
	private static final Block[] ICE_SCATTER = {Blocks.SNOW, Blocks.POWDER_SNOW};
	private static final Block[] MOSS_SCATTER = {Blocks.MOSS_CARPET, Blocks.FLOWERING_AZALEA, Blocks.AZALEA};

	/** What each pillar is built from ({@code a(String):771}). */
	private static final Block[] ANCIENT_STONE = {Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS,
			Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.DEEPSLATE, Blocks.DEEPSLATE_BRICKS,
			Blocks.DEEPSLATE_TILES, Blocks.COBBLED_DEEPSLATE, Blocks.CRACKED_DEEPSLATE_BRICKS};
	private static final Block[] NETHER = {Blocks.NETHERRACK, Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS,
			Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
			Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.MAGMA_BLOCK, Blocks.BASALT, Blocks.POLISHED_BASALT};
	private static final Block[] COPPER_TUFF = {Blocks.COPPER_BLOCK, Blocks.EXPOSED_COPPER, Blocks.WEATHERED_COPPER,
			Blocks.OXIDIZED_COPPER, Blocks.TUFF, Blocks.TUFF_BRICKS, Blocks.POLISHED_TUFF, Blocks.CHISELED_TUFF,
			Blocks.CHISELED_COPPER};
	private static final Block[] WORKSHOP = {Blocks.YELLOW_CONCRETE, Blocks.ORANGE_CONCRETE,
			Blocks.LIGHT_BLUE_CONCRETE, Blocks.POLISHED_BLACKSTONE, Blocks.IRON_BLOCK, Blocks.COPPER_BLOCK,
			Blocks.EXPOSED_COPPER, Blocks.WEATHERED_COPPER, Blocks.OXIDIZED_COPPER};
	private static final Block[] PALE = {Blocks.SCULK, Blocks.DEEPSLATE, Blocks.DEEPSLATE_BRICKS,
			Blocks.DEEPSLATE_TILES, Blocks.COBBLED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE, Blocks.GRAY_CONCRETE,
			Blocks.LIGHT_GRAY_CONCRETE, Blocks.TUFF};
	private static final Block[] PLAIN = {Blocks.STONE, Blocks.COBBLESTONE};

	private final AltarRegistry registry;
	private final AltarManager altars;

	public RandomAltarSpawner(AltarRegistry registry, AltarManager altars) {
		this.registry = registry;
		this.altars = altars;
	}

	/**
	 * Whether this type has both a pillar shape and an altar to put on top of it. The plugin
	 * only ever checked its own list of six, so a type failing this check means the pillar
	 * shapes and the altar registry have drifted apart.
	 */
	public boolean supports(String type) {
		return TYPES.contains(type) && this.registry.byKey(type) != null;
	}

	/**
	 * The plugin's site test ({@code :82-86}): the top of the column at (x, z), one block
	 * above it as the build base, and the ground under that base has to be solid and neither
	 * water nor lava.
	 *
	 * <p>Probing the heightmap of a column nobody has visited loads that chunk, which is what
	 * Bukkit's {@code World#getHighestBlockYAt} did too.
	 *
	 * @return the base position, or null when nothing can be built on this column
	 */
	@Nullable
	public static BlockPos findSite(ServerLevel level, int x, int z) {
		BlockPos base = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
		BlockState ground = level.getBlockState(base.below());
		if (!ground.blocksMotion() || !ground.getFluidState().isEmpty()) {
			return null;
		}
		return base;
	}

	/**
	 * Raises one pillar at {@code base} and puts its altar on the deck.
	 *
	 * @return whether the altar on top was created
	 */
	public boolean spawnAt(ServerLevel level, BlockPos base, String type, RandomSource rand) {
		Block[] pillar = pillarPalette(type);
		dressSurroundings(level, base, dressingPalette(type), rand);
		clearColumn(level, base);
		switch (type) {
			case "paleshard" -> buildPaleShardPillar(level, base, pillar, rand);
			case "hyperionshard" -> buildHyperionShardPillar(level, base, pillar, rand);
			case "nightpiercershard" -> buildNightpiercerShardPillar(level, base, pillar, rand);
			case "vulkanhead" -> buildVulkanHeadPillar(level, base, pillar, rand);
			case "illusioncore" -> buildIllusionCorePillar(level, base, pillar, rand);
			case "weaponhandle" -> buildWeaponHandlePillar(level, base, pillar, rand);
			default -> buildPlainPillar(level, base, pillar, rand);
		}
		// Each shape in the plugin ended by calling l() itself. The deck is identical for all
		// of them, so it is built once, here.
		buildDeck(level, base, pillar, rand);
		buildFurniture(level, base);
		return placeAltar(level, base, type);
	}

	// ------------------------------------------------------------------ site dressing

	/**
	 * Dresses the ground around the pillar ({@code a():114-166}): a disc of radius 15 that
	 * thins out with distance, a 15% chance of the palette's plant-like block on top of each
	 * repainted block, then thirty scattered 3x3 patches between 4 and 20 blocks out.
	 */
	private void dressSurroundings(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand) {
		for (int dx = -DRESS_RADIUS; dx <= DRESS_RADIUS; dx++) {
			for (int dz = -DRESS_RADIUS; dz <= DRESS_RADIUS; dz++) {
				double distance = Math.sqrt(dx * dx + dz * dz);
				if (distance < 4.0D) {
					continue;
				}
				double density = 1.0D - distance / (double) (DRESS_RADIUS + 5);
				if (rand.nextDouble() > density || distance > DRESS_RADIUS) {
					continue;
				}
				BlockPos top = topBlock(level, base.getX() + dx, base.getZ() + dz);
				if (repaintable(level, top)) {
					set(level, top, pick(palette, rand));
				}
				if (rand.nextDouble() < 0.15D && level.getBlockState(top.above()).isAir()) {
					Block scatter = scatterBlock(palette, rand);
					if (scatter != null) {
						set(level, top.above(), scatter);
					}
				}
			}
		}

		for (int i = 0; i < 30; i++) {
			int dx = rand.nextInt(30) - 15;
			int dz = rand.nextInt(30) - 15;
			double distance = Math.sqrt(dx * dx + dz * dz);
			if (distance < 4.0D || distance > 20.0D) {
				continue;
			}
			BlockPos top = topBlock(level, base.getX() + dx, base.getZ() + dz);
			for (int ox = -1; ox <= 1; ox++) {
				for (int oz = -1; oz <= 1; oz++) {
					if (rand.nextDouble() < 0.6D) {
						BlockPos at = top.offset(ox, 0, oz);
						if (repaintable(level, at)) {
							set(level, at, pick(palette, rand));
						}
					}
				}
			}
		}
	}

	/**
	 * The plant-like block the dressing scatters, chosen from the palette's first entry
	 * ({@code a(Material[], Random):168-183}). Null when the palette has no obvious match,
	 * which is when nothing is scattered.
	 */
	@Nullable
	private static Block scatterBlock(Block[] palette, RandomSource rand) {
		Block first = palette[0];
		if (first == Blocks.SCULK || first == Blocks.DEEPSLATE) {
			return pick(SCULK_SCATTER, rand);
		}
		if (first == Blocks.NETHERRACK || first == Blocks.MAGMA_BLOCK) {
			return pick(NETHER_SCATTER, rand);
		}
		if (first == Blocks.PACKED_ICE || first == Blocks.BLUE_ICE) {
			return pick(ICE_SCATTER, rand);
		}
		if (first == Blocks.MOSS_BLOCK || first == Blocks.COPPER_BLOCK) {
			return pick(MOSS_SCATTER, rand);
		}
		return null;
	}

	// ------------------------------------------------------------------ the column

	/** Empties the 11 x 36 x 11 box the pillar is built inside ({@code a():189-197}). */
	private void clearColumn(ServerLevel level, BlockPos base) {
		for (int dy = 0; dy < CLEAR_HEIGHT; dy++) {
			for (int dx = -CLEAR_RADIUS; dx <= CLEAR_RADIUS; dx++) {
				for (int dz = -CLEAR_RADIUS; dz <= CLEAR_RADIUS; dz++) {
					set(level, base.offset(dx, dy, dz), Blocks.AIR);
				}
			}
		}
	}

	/**
	 * The shape no type asks for by name ({@code b():225-268}) - the plugin's default: a
	 * tapered shaft, four corner columns crowned with soul lanterns, a stepped arch over each
	 * corner at y 10 and y 20, and a 40% chance spiral of blocks winding up the outside.
	 */
	private void buildPlainPillar(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand) {
		for (int y = 0; y <= PILLAR_HEIGHT; y++) {
			// The plugin wrote `y < 5 ? 2 : (y < 15 ? 1 : 1)`, which is 2 then 1.
			int radius = y < 5 ? 2 : 1;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					set(level, base.offset(dx, y, dz), pick(palette, rand));
				}
			}
		}

		for (int[] corner : CORNERS_3) {
			int height = 15 + rand.nextInt(10);
			for (int y = 0; y < height; y++) {
				set(level, base.offset(corner[0], y, corner[1]), pick(palette, rand));
			}
			set(level, base.offset(corner[0], height, corner[1]), Blocks.SOUL_LANTERN);
		}

		cornerArches(level, base, palette, rand, 10);
		cornerArches(level, base, palette, rand, 20);

		for (int y = 0; y <= PILLAR_HEIGHT; y++) {
			double angle = y * 0.3D;
			int dx = (int) Math.round(Math.cos(angle) * 2.0D);
			int dz = (int) Math.round(Math.sin(angle) * 2.0D);
			if (rand.nextDouble() < 0.4D) {
				BlockPos at = base.offset(dx, y, dz);
				if (level.getBlockState(at).isAir()) {
					set(level, at, pick(palette, rand));
				}
			}
		}
	}

	/** Pale Shard ({@code e():380-436}): a wobbling sculk shaft, veins, sensors, deepslate corners. */
	private void buildPaleShardPillar(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand) {
		for (int y = 0; y <= PILLAR_HEIGHT; y++) {
			double angle = y * 0.15D;
			int wobbleX = (int) (Math.sin(angle) * 0.5D);
			int wobbleZ = (int) (Math.cos(angle) * 0.5D);
			int radius = y < 10 ? 2 : 1;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					Block material = rand.nextDouble() < 0.4D ? Blocks.SCULK
							: (rand.nextDouble() < 0.6D ? Blocks.DEEPSLATE_BRICKS : pick(palette, rand));
					set(level, base.offset(dx + wobbleX, y, dz + wobbleZ), material);
				}
			}
		}

		for (int y = 0; y <= PILLAR_HEIGHT; y += 2) {
			for (int[] cardinal : CARDINALS_2) {
				if (rand.nextDouble() < 0.3D) {
					BlockPos at = base.offset(cardinal[0], y, cardinal[1]);
					if (level.getBlockState(at).isAir()) {
						set(level, at, Blocks.SCULK_VEIN);
					}
				}
			}
		}

		for (int i = 0; i < 8; i++) {
			BlockPos at = base.offset(rand.nextInt(9) - 4, rand.nextInt(PILLAR_HEIGHT), rand.nextInt(9) - 4);
			if (level.getBlockState(at).isAir()) {
				set(level, at, Blocks.SCULK_SENSOR);
			}
		}

		for (int[] corner : CORNERS_4) {
			int height = 10 + rand.nextInt(8);
			for (int y = 0; y < height; y++) {
				set(level, base.offset(corner[0], y, corner[1]), Blocks.DEEPSLATE);
			}
			set(level, base.offset(corner[0], height, corner[1]), Blocks.SOUL_LANTERN);
		}
	}

	/** Hyperion Shard ({@code g():474-516}): a glowstone-banded shaft, sea-lantern corners, chain curtains. */
	private void buildHyperionShardPillar(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand) {
		for (int y = 0; y <= PILLAR_HEIGHT; y++) {
			int radius = y < 8 ? 2 : 1;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					Block material = y % 4 == 0 ? Blocks.GLOWSTONE
							: (rand.nextDouble() < 0.4D ? Blocks.YELLOW_CONCRETE : pick(palette, rand));
					set(level, base.offset(dx, y, dz), material);
				}
			}
		}

		for (int[] corner : CORNERS_4) {
			int height = 20 + rand.nextInt(8);
			for (int y = 0; y < height; y++) {
				set(level, base.offset(corner[0], y, corner[1]),
						y % 3 == 0 ? Blocks.GLOWSTONE : Blocks.YELLOW_CONCRETE);
			}
			set(level, base.offset(corner[0], height, corner[1]), Blocks.SEA_LANTERN);
		}

		for (int y = 15; y <= 25; y++) {
			if (y % 2 != 0) {
				continue;
			}
			for (int offset = -3; offset <= 3; offset++) {
				if (rand.nextDouble() < 0.5D) {
					// Bukkit's Material.IRON_CHAIN is the chain block.
					set(level, base.offset(offset, y, -4), Blocks.CHAIN);
					set(level, base.offset(offset, y, 4), Blocks.CHAIN);
					set(level, base.offset(-4, y, offset), Blocks.CHAIN);
					set(level, base.offset(4, y, offset), Blocks.CHAIN);
				}
			}
		}
	}

	/** Nightpiercer Shard ({@code h():518-563}): crying obsidian, purpur bands, end rods, end-stone corners. */
	private void buildNightpiercerShardPillar(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand) {
		for (int y = 0; y <= PILLAR_HEIGHT; y++) {
			int radius = y < 10 ? 2 : 1;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					Block material = rand.nextDouble() < 0.3D ? Blocks.CRYING_OBSIDIAN
							: (rand.nextDouble() < 0.5D ? Blocks.OBSIDIAN : pick(palette, rand));
					set(level, base.offset(dx, y, dz), material);
				}
			}
		}

		for (int y = 5; y <= PILLAR_HEIGHT; y += 6) {
			for (int[] cardinal : CARDINALS_2) {
				set(level, base.offset(cardinal[0], y, cardinal[1]), Blocks.PURPUR_BLOCK);
			}
		}

		for (int y = 10; y <= PILLAR_HEIGHT; y += 5) {
			for (int[] cardinal : CARDINALS_3) {
				set(level, base.offset(cardinal[0], y, cardinal[1]), Blocks.END_ROD);
			}
		}

		for (int[] corner : CORNERS_4) {
			int height = 15 + rand.nextInt(8);
			for (int y = 0; y < height; y++) {
				set(level, base.offset(corner[0], y, corner[1]),
						y % 4 == 0 ? Blocks.PURPUR_PILLAR : Blocks.END_STONE_BRICKS);
			}
		}
	}

	/** Vulkan Head ({@code i():565-602}): a wide nether shaft, soul-fire braziers, magma and basalt columns. */
	private void buildVulkanHeadPillar(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand) {
		for (int y = 0; y <= PILLAR_HEIGHT; y++) {
			int radius = y < 8 ? 3 : (y < 20 ? 2 : 1);
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					Block material = rand.nextDouble() < 0.3D ? Blocks.RED_NETHER_BRICKS
							: (rand.nextDouble() < 0.5D ? Blocks.NETHER_BRICKS : pick(palette, rand));
					set(level, base.offset(dx, y, dz), material);
				}
			}
		}

		for (int y = 8; y <= PILLAR_HEIGHT; y += 6) {
			for (int[] cardinal : CARDINALS_3) {
				set(level, base.offset(cardinal[0], y, cardinal[1]), Blocks.NETHER_BRICK_FENCE);
				set(level, base.offset(cardinal[0], y + 1, cardinal[1]), Blocks.SOUL_FIRE);
			}
		}

		for (int[] column : EDGE_COLUMNS) {
			int height = 10 + rand.nextInt(10);
			for (int y = 0; y < height; y++) {
				set(level, base.offset(column[0], y, column[1]),
						y % 3 == 0 ? Blocks.MAGMA_BLOCK : Blocks.BASALT);
			}
		}
	}

	/** Illusion Core ({@code j():605-653}): copper that oxidises with height, a tuff-brick skirt, lightning rods. */
	private void buildIllusionCorePillar(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand) {
		for (int y = 0; y <= PILLAR_HEIGHT; y++) {
			int radius = y < 10 ? 2 : 1;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					Block material;
					if (y < 8) {
						material = Blocks.COPPER_BLOCK;
					} else if (y < 16) {
						material = Blocks.EXPOSED_COPPER;
					} else if (y < 24) {
						material = Blocks.WEATHERED_COPPER;
					} else {
						material = Blocks.OXIDIZED_COPPER;
					}
					if (rand.nextDouble() < 0.3D) {
						material = pick(palette, rand);
					}
					set(level, base.offset(dx, y, dz), material);
				}
			}
		}

		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				if (Math.abs(dx) == 4 || Math.abs(dz) == 4) {
					for (int y = 0; y < 5; y++) {
						set(level, base.offset(dx, y, dz), Blocks.TUFF_BRICKS);
					}
				}
			}
		}

		for (int y = 10; y <= PILLAR_HEIGHT; y += 8) {
			for (int[] cardinal : CARDINALS_2) {
				set(level, base.offset(cardinal[0], y, cardinal[1]), Blocks.LIGHTNING_ROD);
			}
		}
	}

	/** Weapon Handle ({@code k():655-692}): banded concrete, blackstone accents, lantern-topped corners. */
	private void buildWeaponHandlePillar(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand) {
		for (int y = 0; y <= PILLAR_HEIGHT; y++) {
			int radius = y < 10 ? 2 : 1;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					Block material = y % 5 == 0 ? Blocks.LIGHT_BLUE_CONCRETE
							: (rand.nextDouble() < 0.4D ? Blocks.YELLOW_CONCRETE : pick(palette, rand));
					set(level, base.offset(dx, y, dz), material);
				}
			}
		}

		for (int y = 10; y <= PILLAR_HEIGHT; y += 10) {
			// The plugin listed the same four offsets in a different order.
			for (int[] cardinal : CARDINALS_2) {
				set(level, base.offset(cardinal[0], y, cardinal[1]), Blocks.POLISHED_BLACKSTONE);
			}
		}

		for (int[] corner : CORNERS_4) {
			for (int y = 0; y < 8; y++) {
				Block material = y < 3 ? Blocks.YELLOW_CONCRETE
						: (y < 6 ? Blocks.LIGHT_BLUE_CONCRETE : Blocks.LIME_CONCRETE);
				set(level, base.offset(corner[0], y, corner[1]), material);
			}
			set(level, base.offset(corner[0], 8, corner[1]), Blocks.LANTERN);
		}
	}

	/**
	 * The stepped arch over each corner ({@code a():694-706}): four blocks walking in from a
	 * corner towards the shaft, higher in the middle than at either end.
	 */
	private void cornerArches(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand, int y) {
		for (int[] corner : CORNERS_3) {
			for (int step = 0; step <= 3; step++) {
				int dx = corner[0] - (corner[0] > 0 ? step : -step);
				int dz = corner[1] - (corner[1] > 0 ? step : -step);
				int height = y + (3 - Math.abs(step - 1));
				set(level, base.offset(dx, height, dz), pick(palette, rand));
			}
		}
	}

	// ------------------------------------------------------------------ deck and furniture

	/**
	 * The deck every pillar ends with ({@code l():708-757}): a 5x5 platform at y 31, the 3x3x3
	 * room cleared above it for the altar, a 70%-complete 7x7 ring, four corner posts with a
	 * lantern each, and iron bars railings with a gap in the middle of every side.
	 */
	private void buildDeck(ServerLevel level, BlockPos base, Block[] palette, RandomSource rand) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				set(level, base.offset(dx, DECK_Y, dz), pick(palette, rand));
			}
		}

		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = 1; dy <= 3; dy++) {
					set(level, base.offset(dx, DECK_Y + dy, dz), Blocks.AIR);
				}
			}
		}

		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				if ((Math.abs(dx) == 3 || Math.abs(dz) == 3) && (Math.abs(dx) > 2 || Math.abs(dz) > 2)
						&& rand.nextDouble() < 0.7D) {
					set(level, base.offset(dx, DECK_Y, dz), pick(palette, rand));
				}
			}
		}

		for (int[] corner : CORNERS_2) {
			set(level, base.offset(corner[0], DECK_Y + 1, corner[1]), palette[0]);
			set(level, base.offset(corner[0], DECK_Y + 2, corner[1]), Blocks.LANTERN);
		}

		for (int i = -2; i <= 2; i++) {
			if (i != 0) {
				set(level, base.offset(i, DECK_Y + 1, -2), Blocks.IRON_BARS);
				set(level, base.offset(i, DECK_Y + 1, 2), Blocks.IRON_BARS);
				set(level, base.offset(-2, DECK_Y + 1, i), Blocks.IRON_BARS);
				set(level, base.offset(2, DECK_Y + 1, i), Blocks.IRON_BARS);
			}
		}
	}

	/**
	 * The deck furniture ({@code m():759-769}): an enchanting table on the west edge with a
	 * bookshelf either side of it, and an ender chest on the east edge.
	 */
	private void buildFurniture(ServerLevel level, BlockPos base) {
		set(level, base.offset(-2, DECK_Y + 1, 0), Blocks.ENCHANTING_TABLE);
		set(level, base.offset(2, DECK_Y + 1, 0), Blocks.ENDER_CHEST);
		set(level, base.offset(-2, DECK_Y + 1, -1), Blocks.BOOKSHELF);
		set(level, base.offset(-2, DECK_Y + 1, 1), Blocks.BOOKSHELF);
	}

	/**
	 * The altar on the deck. The plugin spawned a name-tagged armour stand here carrying the
	 * recipe lines ({@code a():846-882}); this registers a real altar at the same spot - the
	 * plugin's stand feet were at base + 32.5, which is where the altar's name stand lands.
	 */
	private boolean placeAltar(ServerLevel level, BlockPos base, String type) {
		AltarRegistry.Spec spec = this.registry.byKey(Identity.normalise(type));
		if (spec == null) {
			AltarSMPMod.LOGGER.error("[AltarSMP] built a {} pillar but no altar is registered under that key, so "
					+ "there is nothing on its deck", type);
			return false;
		}
		return this.altars.createAltarAt(level, base.offset(0, DECK_Y + 1, 0), spec, null);
	}

	// ------------------------------------------------------------------ palettes and helpers

	/** {@code a(String):771-832}. */
	private static Block[] pillarPalette(String type) {
		return switch (type) {
			case "hyperionshard", "nightpiercershard" -> ANCIENT_STONE;
			case "vulkanhead" -> NETHER;
			case "illusioncore" -> COPPER_TUFF;
			case "weaponhandle" -> WORKSHOP;
			case "paleshard" -> PALE;
			default -> PLAIN;
		};
	}

	/** {@code b(String):834-845} - what the ground around the pillar is dressed in. */
	private static Block[] dressingPalette(String type) {
		return switch (type) {
			case "hyperionshard" -> new Block[]{Blocks.YELLOW_CONCRETE, Blocks.ORANGE_CONCRETE,
					Blocks.YELLOW_TERRACOTTA, Blocks.GLOWSTONE};
			case "nightpiercershard" -> new Block[]{Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
					Blocks.PURPLE_CONCRETE, Blocks.END_STONE};
			case "vulkanhead" -> new Block[]{Blocks.NETHERRACK, Blocks.MAGMA_BLOCK, Blocks.RED_CONCRETE,
					Blocks.CRIMSON_NYLIUM};
			case "illusioncore" -> new Block[]{Blocks.MOSS_BLOCK, Blocks.COPPER_BLOCK, Blocks.OXIDIZED_COPPER,
					Blocks.TUFF};
			case "weaponhandle" -> new Block[]{Blocks.YELLOW_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE,
					Blocks.ORANGE_CONCRETE, Blocks.YELLOW_TERRACOTTA};
			case "paleshard" -> new Block[]{Blocks.SCULK, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE,
					Blocks.BONE_BLOCK};
			default -> new Block[]{Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE};
		};
	}

	private static Block pick(Block[] palette, RandomSource rand) {
		return palette[rand.nextInt(palette.length)];
	}

	/** Bukkit's {@code getHighestBlockYAt}: the topmost block of the column, not the air above it. */
	private static BlockPos topBlock(ServerLevel level, int x, int z) {
		return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
	}

	/** The plugin's {@code getType().isSolid() && !isLiquid()} test on a block it is about to repaint. */
	private static boolean repaintable(ServerLevel level, BlockPos at) {
		BlockState state = level.getBlockState(at);
		return state.blocksMotion() && state.getFluidState().isEmpty();
	}

	/** Places a block, skipping the update when nothing would change. */
	private static void set(ServerLevel level, BlockPos at, Block block) {
		BlockState state = block.defaultBlockState();
		if (!level.getBlockState(at).equals(state)) {
			level.setBlock(at, state, 3);
		}
	}
}
