package com.gmail.sharpcastle33.did.generator;

import com.gmail.sharpcastle33.did.Util;
import com.gmail.sharpcastle33.did.generator.painter.PainterStep;
import com.gmail.sharpcastle33.did.generator.structure.Structure;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class PostProcessor {

	private static final int STRUCTURE_CHANCE_ADJUST = 6 * 6;

	public static void postProcess(CaveGenContext ctx, List<Centroid> centroids, List<Integer> roomStarts) throws WorldEditException {
		Bukkit.getLogger().log(Level.WARNING, "Beginning smoothing pass... " + centroids.size() + " centroids.");

		for (int i = 0; i < roomStarts.size(); i++) {
			int roomStart = roomStarts.get(i);
			int roomEnd = i == roomStarts.size() - 1 ? centroids.size() : roomStarts.get(i + 1);
			List<Centroid> roomCentroids = centroids.subList(roomStart, roomEnd);
			int minRoomY = roomCentroids.stream().mapToInt(centroid -> centroid.pos.getBlockY() - centroid.size).min().orElse(0);
			int maxRoomY = roomCentroids.stream().mapToInt(centroid -> centroid.pos.getBlockY() + centroid.size).max().orElse(255);
			for (Centroid centroid : roomCentroids) {
				smooth(ctx, centroid, minRoomY, maxRoomY);
			}
		}

		Bukkit.getLogger().log(Level.WARNING, "Beginning painter pass...");

		Set<BlockVector3> paintedBlocks = new HashSet<>();
		List<BlockVector3> paintedBlocksThisCentroid = new ArrayList<>();
		for(Centroid centroid : centroids) {
			for (PainterStep painterStep : ctx.style.getPainterSteps()) {
				if (painterStep.areTagsInverted()
						? painterStep.getTags().stream().noneMatch(centroid.tags::contains)
						: painterStep.getTags().stream().anyMatch(centroid.tags::contains)) {
					painterStep.apply(ctx, centroid.pos.toBlockPoint(), centroid.size + 4, pos -> {
						if (paintedBlocks.contains(pos)) {
							return false;
						}
						paintedBlocksThisCentroid.add(pos);
						return true;
					});
				}
			}
			paintedBlocks.addAll(paintedBlocksThisCentroid);
			paintedBlocksThisCentroid.clear();
		}

		Bukkit.getLogger().log(Level.WARNING, "Beginning structure pass...");

		for (Structure structure : ctx.style.getStructures()) {
			generateStructure(ctx, centroids, structure);
		}

		if (!centroids.isEmpty()) {
			generatePortal(ctx, centroids.get(0).pos.toBlockPoint(), 100);
		}

		if (ctx.isDebug()) {
			for (Centroid centroid : centroids) {
				ctx.setBlock(centroid.pos.toBlockPoint(), Util.requireDefaultState(BlockTypes.EMERALD_BLOCK));
			}
		}
	}

	public static void smooth(CaveGenContext ctx, Centroid centroid, int minRoomY, int maxRoomY) throws MaxChangedBlocksException {
		int x = centroid.pos.getBlockX();
		int y = centroid.pos.getBlockY();
		int z = centroid.pos.getBlockZ();
		int r = centroid.size + 2;

		for(int tx = -r; tx <= r; tx++){
			for(int ty = -r; ty <= r; ty++){
				for(int tz = -r; tz <= r; tz++){
					if(tx * tx  +  ty * ty  +  tz * tz <= r * r){
						BlockVector3 pos = BlockVector3.at(tx+x, ty+y, tz+z);

						if(ctx.style.getBaseBlock().equalsFuzzy(ctx.getBlock(pos))) {
							int amt = countTransparent(ctx, pos);
							if(amt >= 13) {
								//Bukkit.getServer().getLogger().log(Level.WARNING,"count: " + amt);
								if(ctx.rand.nextInt(100) < 95) {
									ctx.setBlock(pos, ctx.style.getAirBlock(pos.getBlockY(), centroid, minRoomY, maxRoomY));
								}
							}
						}
					}
				}
			}
		}
	}

	public static int countTransparent(CaveGenContext ctx, BlockVector3 loc) {
		final int r = 1;
		int count = 0;
		for (int tx = -r; tx <= r; tx++) {
			for (int ty = -r; ty <= r; ty++) {
				for (int tz = -r; tz <= r; tz++) {
					BlockVector3 pos = loc.add(tx, ty, tz);
					if (ctx.style.isTransparentBlock(ctx.getBlock(pos))) {
						count++;
					}
				}
			}
		}
		return count;
	}



	public static void generateStructure(CaveGenContext ctx, List<Centroid> centroids, Structure structure) throws WorldEditException {
		List<Direction> validDirections = structure.getValidDirections();
		if (validDirections.isEmpty()) {
			return;
		}
		for (Centroid centroid : centroids) {
			if (centroid.size <= 0) {
				continue;
			}

			double averageStructures = structure.getCount() * (centroid.size * centroid.size) / STRUCTURE_CHANCE_ADJUST;
			// compute the number of structures in this centroid using the Poisson distribution
			// https://stackoverflow.com/questions/9832919/generate-poisson-arrival-in-java
			double L = Math.exp(-averageStructures);
			int numStructures = -1;
			double p = 1;
			do {
				p *= ctx.rand.nextDouble();
				numStructures++;
			} while (p > L);

			for (int j = 0; j < numStructures; j++) {
				if (structure.areTagsInverted()
						? structure.getTags().stream().noneMatch(centroid.tags::contains)
						: structure.getTags().stream().anyMatch(centroid.tags::contains)) {
					// pick a random point on the unit sphere until it's a valid direction
					Vector3 vector;
					Direction dir;
					do {
						vector = Vector3.at(ctx.rand.nextGaussian(), ctx.rand.nextGaussian(), ctx.rand.nextGaussian()).normalize().multiply(centroid.size);
						dir = Direction.findClosest(vector, Direction.Flag.CARDINAL | Direction.Flag.UPRIGHT);
					} while (!validDirections.contains(dir));
					assert dir != null; // stupid worldedit
					double distanceToWall = dir.toVector().dot(vector);
					Vector3 orthogonal = vector.subtract(dir.toVector().multiply(distanceToWall));
					BlockVector3 origin = centroid.pos.add(orthogonal).toBlockPoint();

					BlockVector3 pos;
					if (dir == Direction.DOWN) {
						pos = PostProcessor.getFloor(ctx, origin, (int) Math.ceil(distanceToWall) + 2);
					} else if (dir == Direction.UP) {
						pos = PostProcessor.getCeiling(ctx, origin, (int) Math.ceil(distanceToWall) + 2);
					} else {
						pos = PostProcessor.getWall(ctx, origin, (int) Math.ceil(distanceToWall) + 2, dir.toBlockVector());
					}

					if (structure.canPlaceOn(ctx, ctx.getBlock(pos))) {
						structure.place(ctx, pos, dir, false);
					}
				}
			}
		}
	}

	private static void generatePortal(CaveGenContext ctx, BlockVector3 firstCentroid, int caveRadius) {
		if (ctx.style.getPortals().isEmpty()) {
			return;
		}
		Structure portal = ctx.style.getPortals().get(ctx.rand.nextInt(ctx.style.getPortals().size()));
		double zeroChance = Math.exp(-portal.getCount());
		if (ctx.rand.nextDouble() < zeroChance) {
			return;
		}
		BlockVector3 pos = PostProcessor.getFloor(ctx, firstCentroid, caveRadius);
		portal.place(ctx, pos, Direction.DOWN, true);
	}

	public static void generateWaterfalls(CaveGenContext ctx, List<Centroid> centroids, Vector3 loc, int caveRadius, int amount, int rarity, int placeRadius) {
		for(Centroid centroid : centroids) {
			if(ctx.rand.nextInt(rarity) == 0) {
				placeWaterfalls(loc,caveRadius,amount,placeRadius);
			}
		}
	}

	public static int placeWaterfalls(Vector3 loc, int caveRadius, int amount, int placeRadius) {
		for(int i = 0; i < amount; i++) {
			//TODO
		}

		return 0;

	}

	public static boolean isFloor(CaveGenContext ctx, BlockVector3 pos) {
		return isSolid(ctx, pos) && isSolid(ctx, pos.add(0, -1, 0)) && !isSolid(ctx, pos.add(0, 1, 0));
	}

	public static boolean isRoof(CaveGenContext ctx, BlockVector3 pos) {
		return isSolid(ctx, pos) && !isSolid(ctx, pos.add(0, -1, 0)) && isSolid(ctx, pos.add(0, 1, 0));
	}

	public static boolean isSolid(CaveGenContext ctx, BlockVector3 pos) {
		return !ctx.style.isTransparentBlock(ctx.getBlock(pos));
	}

	public static BlockVector3 getWall(CaveGenContext ctx, BlockVector3 loc, int r, BlockVector3 direction) {
		r= (int) (r *1.8);
		BlockVector3 ret = loc;
		for(int i = 0; i < r; i++) {
			ret = ret.add(direction);
			if (!ctx.style.isTransparentBlock(ctx.getBlock(ret))) {
				return ret;
			}
		}
		return ret;
	}

	public static BlockVector3 getCeiling(CaveGenContext ctx, BlockVector3 loc, int r) {
		BlockVector3 ret = loc;
		for(int i = 0; i < r+2; i++) {
			ret = ret.add(0,1,0);
			if (!ctx.style.isTransparentBlock(ctx.getBlock(ret))) {
				return ret;
			}
		}
		return ret;
	}

	public static BlockVector3 getFloor(CaveGenContext ctx, BlockVector3 loc, int r) {
		BlockVector3 ret = loc;
		for(int i = 0; i < r+2; i++) {
			ret = ret.add(0, -1, 0);
			if (!ctx.style.isTransparentBlock(ctx.getBlock(ret))) {
				return ret;
			}
		}
		return ret;
	}

	public static void genStalagmites(CaveGenContext ctx, BlockVector3 loc, int r, int amount) throws MaxChangedBlocksException {
		for(int i = 0; i < amount; i++) {
			int hozMod = Math.min(3, r);
			int tx = ctx.rand.nextInt(hozMod)+1;
			int tz = ctx.rand.nextInt(hozMod)+1;

			BlockVector3 start = loc.add(tx,0,tz);
			BlockVector3 end = getCeiling(ctx,start,r);
			end = end.add(0,-1,0);
			ctx.setBlock(end, Util.requireDefaultState(BlockTypes.COBBLESTONE_WALL));
		}

	}

	public boolean isBottomSlabPos(CaveGenContext ctx, BlockVector3 loc) {
		BlockVector3 tx = loc.add(1,0,0);
		BlockVector3 tz = loc.add(0,0,1);
		BlockVector3 tx1 = loc.add(-1,0,0);
		BlockVector3 tz1 = loc.add(0,0,-1);

		return isSlabConditionBottom(ctx, tx)
				|| isSlabConditionBottom(ctx, tz)
				|| isSlabConditionBottom(ctx, tx1)
				|| isSlabConditionBottom(ctx, tz1);
	}

	public boolean isTopSlabPos(CaveGenContext ctx, BlockVector3 loc) {
		BlockVector3 tx = loc.add(1,0,0);
		BlockVector3 tz = loc.add(0,0,1);
		BlockVector3 tx1 = loc.add(-1,0,0);
		BlockVector3 tz1 = loc.add(0,0,-1);

		return isSlabConditionTop(ctx, tx)
				|| isSlabConditionTop(ctx, tz)
				|| isSlabConditionTop(ctx, tx1)
				|| isSlabConditionTop(ctx, tz1);
	}


	public boolean isSlabConditionBottom(CaveGenContext ctx, BlockVector3 loc) {
		if(!ctx.style.isTransparentBlock(ctx.getBlock(loc))) {
			return ctx.style.isTransparentBlock(ctx.getBlock(loc.add(0, 1, 0)));
		}
		return false;
	}

	public boolean isSlabConditionTop(CaveGenContext ctx, BlockVector3 loc) {
		if(!ctx.style.isTransparentBlock(ctx.getBlock(loc))) {
			return ctx.style.isTransparentBlock(ctx.getBlock(loc.add(0, -1, 0)));
		}
		return false;
	}






}
