package com.gmail.sharpcastle33.did.generator.room;

import com.gmail.sharpcastle33.did.config.InvalidConfigException;
import com.gmail.sharpcastle33.did.generator.CaveGenContext;
import com.gmail.sharpcastle33.did.generator.Centroid;
import com.sk89q.worldedit.math.Vector3;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

public class VerticalRoom extends Room {
	private final double minPitch;
	private final double maxPitch;
	private final int minLength;
	private final int maxLength;

	public VerticalRoom(char symbol, List<String> tags, double minPitch, double maxPitch, int minLength,
						int maxLength) {
		super(symbol, RoomType.VERTICAL, tags);
		this.minPitch = minPitch;
		this.maxPitch = maxPitch;
		this.minLength = minLength;
		this.maxLength = maxLength;
	}

	public VerticalRoom(char symbol, ConfigurationSection map) {
		super(symbol, RoomType.VERTICAL, map);
		this.minPitch = map.getDouble("minPitch", 90);
		this.maxPitch = map.getDouble("maxPitch", 90);
		if (maxPitch < minPitch) {
			throw new InvalidConfigException("Invalid pitch range");
		}
		this.minLength = map.getInt("minLength", 3);
		this.maxLength = map.getInt("maxLength", 5);
		if (minLength <= 0 || maxLength < minLength) {
			throw new InvalidConfigException("Invalid length range");
		}
	}

	@Override
	public Object[] createUserData(CaveGenContext ctx, Vector3 location, Vector3 direction, int caveRadius,
								   List<String> tags) {
		double pitch = minPitch + ctx.rand.nextDouble() * (maxPitch - minPitch);
		int length = minLength + ctx.rand.nextInt(maxLength - minLength + 1);
		return new Object[]{pitch, length};
	}

	@Override
	public Vector3 adjustLocation(CaveGenContext ctx, Vector3 location, Vector3 direction, int caveRadius,
								  Object[] userData) {
		double pitch = (Double) userData[0];
		int length = (Integer) userData[1];
		return location
				.add(direction.multiply(length * caveRadius * Math.cos(pitch)))
				.add(0, length * caveRadius * Math.sin(-pitch), 0);
	}

	@Override
	public void addCentroids(CaveGenContext ctx, Vector3 location, Vector3 direction, int caveRadius,
							 List<String> tags, Object[] userData, List<Centroid> centroids,
							 List<Integer> roomStarts) {
		double pitch = (Double) userData[0];
		int length = (Integer) userData[1];

		Vector3 moveVec = direction.multiply(caveRadius * Math.cos(pitch))
				.add(0, caveRadius * Math.sin(-pitch), 0);
		Vector3 pos = location;
		for (int i = 0; i < length; i++) {
			centroids.add(new Centroid(pos, caveRadius, tags));
			pos = pos.add(moveVec);
		}
	}

	@Override
	protected void serialize0(ConfigurationSection map) {
		map.set("minPitch", minPitch);
		map.set("maxPitch", maxPitch);
		map.set("minLength", minLength);
		map.set("maxLength", maxLength);
	}
}
