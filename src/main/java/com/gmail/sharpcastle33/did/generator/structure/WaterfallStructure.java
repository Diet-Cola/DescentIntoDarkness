package com.gmail.sharpcastle33.did.generator.structure;

import com.gmail.sharpcastle33.did.Util;
import com.gmail.sharpcastle33.did.config.ConfigUtil;
import com.gmail.sharpcastle33.did.config.InvalidConfigException;
import com.gmail.sharpcastle33.did.generator.CaveGenContext;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.registry.state.PropertyKey;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class WaterfallStructure extends Structure {
	private static final Map<BlockVector3, Integer> blockLevels = new HashMap<>();

	private final FluidType fluid;
	private final BlockStateHolder<?> block;
	private final int flowDistance;

	protected WaterfallStructure(String name, ConfigurationSection map) {
		super(name, StructureType.WATERFALL, map);
		this.block = ConfigUtil.parseBlock(ConfigUtil.requireString(map, "block"));
		this.fluid = FluidType.byBlockType(block.getBlockType());
		this.flowDistance = fluid == FluidType.BLOCK ? map.getInt("flowDistance", 8) : 8;
		if (flowDistance < 1) {
			throw new InvalidConfigException("flowDistance must be positive");
		}
	}

	@Override
	protected boolean shouldTransformPositionByDefault() {
		return false;
	}

	@Override
	public void place(CaveGenContext ctx, BlockVector3 pos, boolean force) throws WorldEditException {
		if (!force) {
			int wallCount = 0;
			for (Direction dir : Direction.valuesOf(Direction.Flag.CARDINAL | Direction.Flag.UPRIGHT)) {
				if (canPlaceOn(ctx, ctx.getBlock(pos.add(dir.toBlockVector())))) {
					wallCount++;
				}
			}
			if (wallCount != 5) {
				return;
			}
		}

		if (ctx.setBlock(pos, this.block)) {
			simulateFluidTick(ctx, pos);
		}
		blockLevels.clear();
	}

	private void simulateFluidTick(CaveGenContext ctx, BlockVector3 pos) {
		BlockStateHolder<?> state = ctx.getBlock(pos);
		int level = getLevel(pos, state);
		int levelDecrease = fluid == FluidType.LAVA ? 2 : 1;

		if (level > 0) {
			int minLevel = -100;
			int adjacentSourceBlocks = 0;
			for (Direction dir : Direction.valuesOf(Direction.Flag.CARDINAL)) {
				BlockVector3 offsetPos = pos.add(dir.toBlockVector());
				int depth = getLevel(offsetPos, ctx.getBlock(offsetPos));
				if (depth >= 0) {
					if (depth == 0) {
						adjacentSourceBlocks++;
					} else if (depth >= flowDistance) {
						depth = 0;
					}
					minLevel = minLevel >= 0 && depth >= minLevel ? minLevel : depth;
				}
			}

			int newLevel = minLevel + levelDecrease;
			if (newLevel >= flowDistance || minLevel < 0) {
				newLevel = -1;
			}

			BlockVector3 posAbove = pos.add(0, 1, 0);
			int depthAbove = getLevel(posAbove, ctx.getBlock(posAbove));
			if (depthAbove >= 0) {
				if (depthAbove >= flowDistance) {
					newLevel = depthAbove;
				} else {
					newLevel = depthAbove + flowDistance;
				}
			}

			if (adjacentSourceBlocks >= 2 && fluid == FluidType.WATER) {
				BlockStateHolder<?> stateBelow = ctx.getBlock(pos.add(0, -1, 0));
				if (!canReplace(ctx, stateBelow)) {
					newLevel = 0;
				} else if (stateBelow.getBlockType() == this.block.getBlockType() && stateBelow.<Integer>getState(PropertyKey.LEVEL) == 0) {
					newLevel = 0;
				}
			}

			if (newLevel != level) {
				level = newLevel;

				if (newLevel < 0) {
					ctx.setBlock(pos, Util.requireDefaultState(BlockTypes.AIR));
				} else {
					if (setLevel(ctx, pos, newLevel)) {
						simulateFluidTick(ctx, pos);
					}
				}
			}
		}

		BlockVector3 posBelow = pos.add(0, -1, 0);
		BlockStateHolder<?> blockBelow = ctx.getBlock(posBelow);
		if (canFlowInto(ctx, posBelow, blockBelow)) {
			// skipped: trigger mix effects

			if (level >= flowDistance) {
				tryFlowInto(ctx, posBelow, blockBelow, level);
			} else {
				tryFlowInto(ctx, posBelow, blockBelow, level + flowDistance);
			}
		} else if (level >= 0 && (level == 0 || isBlocked(ctx, posBelow, blockBelow))) {
			Set<Direction> flowDirs = getPossibleFlowDirections(ctx, pos, level);
			int newLevel = level + levelDecrease;
			if (level >= flowDistance) {
				newLevel = 1;
			}
			if (newLevel >= flowDistance) {
				return;
			}
			for (Direction flowDir : flowDirs) {
				BlockVector3 offsetPos = pos.add(flowDir.toBlockVector());
				tryFlowInto(ctx, offsetPos, ctx.getBlock(offsetPos), newLevel);
			}
		}
	}

	private int getLevel(BlockVector3 pos, BlockStateHolder<?> state) {
		if (state.getBlockType() != this.block.getBlockType()) {
			return -1;
		}
		if (fluid == FluidType.BLOCK || fluid == FluidType.SNOW_LAYER) {
			return blockLevels.getOrDefault(pos, 0);
		} else {
			return state.<Integer>getState(PropertyKey.LEVEL);
		}
	}

	private boolean setLevel(CaveGenContext ctx, BlockVector3 pos, int level) {
		if (fluid == FluidType.BLOCK) {
			return !Integer.valueOf(level).equals(blockLevels.put(pos, level)) | ctx.setBlock(pos, block);
		} else if (fluid == FluidType.SNOW_LAYER) {
			BlockVector3 posBelow = pos.add(0, -1, 0);
			if (ctx.getBlock(posBelow).getBlockType() == BlockTypes.SNOW) {
				ctx.setBlock(posBelow, Util.requireDefaultState(BlockTypes.SNOW).with(PropertyKey.LAYERS, 8));
			}
			int layers = level == 0 ? 8 : 9 - (int) Math.ceil((double) level / flowDistance * 8);
			if (layers <= 0) layers = 1;
			else if (layers > 8) layers = 8;
			if (ctx.getBlock(pos.add(0, 1, 0)).getBlockType() == BlockTypes.SNOW) {
				layers = 8;
			}
			return !Integer.valueOf(level).equals(blockLevels.put(pos, level)) | ctx.setBlock(pos,
					block.with(PropertyKey.LAYERS, layers));
		} else {
			return ctx.setBlock(pos, block.with(PropertyKey.LEVEL, level));
		}
	}

	private boolean canFlowInto(CaveGenContext ctx, BlockVector3 pos, BlockStateHolder<?> block) {
		BlockType blockType = block.getBlockType();
		return blockType != this.block.getBlockType() && blockType != BlockTypes.LAVA && !isBlocked(ctx, pos, block);
	}

	private void tryFlowInto(CaveGenContext ctx, BlockVector3 pos, BlockStateHolder<?> block, int level) {
		if (!canFlowInto(ctx, pos, block)) {
			return;
		}

		// skipped: trigger mix effects and block dropping
		if (setLevel(ctx, pos, level)) {
			simulateFluidTick(ctx, pos);
		}
	}

	private boolean isBlocked(CaveGenContext ctx, BlockVector3 pos, BlockStateHolder<?> block) {
		return !canReplace(ctx, block) && block.getBlockType() != this.block.getBlockType();
	}

	private Set<Direction> getPossibleFlowDirections(CaveGenContext ctx, BlockVector3 pos, int level) {
		int minDistanceToLower = Integer.MAX_VALUE;
		Set<Direction> flowDirs = EnumSet.noneOf(Direction.class);

		for (Direction dir : Direction.valuesOf(Direction.Flag.CARDINAL)) {
			BlockVector3 offsetPos = pos.add(dir.toBlockVector());
			BlockStateHolder<?> offsetState = ctx.getBlock(offsetPos);

			if (!isBlocked(ctx, offsetPos, offsetState) && (offsetState.getBlockType() != this.block.getBlockType() || getLevel(offsetPos, offsetState) > 0)) {
				int distanceToLower;
				BlockVector3 posBelow = offsetPos.add(0, -1, 0);
				if (isBlocked(ctx, posBelow, ctx.getBlock(posBelow))) {
					distanceToLower = getDistanceToLower(ctx, offsetPos, 1, Util.getOpposite(dir));
				} else {
					distanceToLower = 0;
				}

				if (distanceToLower < minDistanceToLower) {
					flowDirs.clear();
				}

				if (distanceToLower <= minDistanceToLower) {
					flowDirs.add(dir);
					minDistanceToLower = distanceToLower;
				}
			}
		}

		return flowDirs;
	}

	private int getDistanceToLower(CaveGenContext ctx, BlockVector3 pos, int distance, Direction excludingDir) {
		int minDistanceToLower = Integer.MAX_VALUE;

		for (Direction dir : Direction.valuesOf(Direction.Flag.CARDINAL)) {
			if (dir == excludingDir) {
				continue;
			}

			BlockVector3 offsetPos = pos.add(dir.toBlockVector());
			BlockStateHolder<?> offsetState = ctx.getBlock(offsetPos);

			if (!isBlocked(ctx, offsetPos, offsetState) && (offsetState.getBlockType() != this.block.getBlockType() || getLevel(offsetPos, offsetState) > 0)) {
				if (!isBlocked(ctx, offsetPos.add(0, -1, 0), offsetState)) {
					return distance;
				}

				if (distance < getSlopeFindDistance()) {
					int distanceToLower = getDistanceToLower(ctx, offsetPos, distance + 1, Util.getOpposite(dir));
					if (distanceToLower < minDistanceToLower) {
						minDistanceToLower = distanceToLower;
					}
				}
			}
		}

		return minDistanceToLower;
	}

	private int getSlopeFindDistance() {
		return fluid == FluidType.LAVA ? 2 : 4;
	}

	@Override
	protected void serialize0(ConfigurationSection map) {
		map.set("block", ConfigUtil.serializeBlock(this.block));
		if (fluid == FluidType.BLOCK) {
			map.set("flowDistance", flowDistance);
		}
	}

	public enum FluidType {
		WATER(BlockTypes.WATER), LAVA(BlockTypes.LAVA), SNOW_LAYER(BlockTypes.SNOW), BLOCK(null);

		private static Map<BlockType, FluidType> BY_BLOCK_TYPE;

		FluidType(BlockType blockType) {
			if (blockType != null) {
				putByBlockType(blockType, this);
			}
		}

		private static void putByBlockType(BlockType blockType, FluidType fluidType) {
			if (BY_BLOCK_TYPE == null) {
				BY_BLOCK_TYPE = new HashMap<>();
			}
			BY_BLOCK_TYPE.put(blockType, fluidType);
		}

		public static FluidType byBlockType(BlockType blockType) {
			return BY_BLOCK_TYPE.getOrDefault(blockType, BLOCK);
		}
	}
}
