package com.gmail.sharpcastle33.did.instancing;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import com.gmail.sharpcastle33.did.DescentIntoDarkness;
import com.gmail.sharpcastle33.did.Util;
import com.gmail.sharpcastle33.did.config.CaveStyle;
import com.gmail.sharpcastle33.did.config.CaveStyleGroup;
import com.gmail.sharpcastle33.did.config.ConfigUtil;
import com.gmail.sharpcastle33.did.generator.CaveGenContext;
import com.gmail.sharpcastle33.did.generator.CaveGenerator;
import com.gmail.sharpcastle33.did.listeners.HiddenOre;
import com.onarandombox.MultiverseCore.api.MVDestination;
import com.onarandombox.MultiverseCore.api.MVWorldManager;
import com.onarandombox.MultiverseCore.api.MultiverseWorld;
import com.onarandombox.MultiverseCore.enums.TeleportResult;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.jetbrains.annotations.Nullable;

public class CaveTrackerManager {
	private static final String WORLD_NAME = "did_caves";
	private static final int INSTANCE_WIDTH_CHUNKS = 625;

	private boolean hasInitialized = false;
	private World theWorld;
	private final ArrayList<CaveTracker> caveTrackers = new ArrayList<>();
	private final EnumMap<DyeColor, ArrayList<CaveTracker>> unexploredCavesByGroup = new EnumMap<>(DyeColor.class);
	private ArrayList<Integer> tempClaimedIDs;
	private final Map<UUID, Location> overworldPlayerLocations = new HashMap<>();
	private int nextInstanceId;
	private Objective pollutionObjective;
	private final AtomicBoolean generatingCave = new AtomicBoolean(false);
	private final ThreadLocal<Boolean> isLeavingCave = ThreadLocal.withInitial(() -> false);

	public CaveTrackerManager() {
		for (DyeColor group : DyeColor.values()) {
			unexploredCavesByGroup.put(group, new ArrayList<>());
		}
	}

	public void initialize() {
		theWorld = getOrCreateFlatWorld();
		if (theWorld == null) {
			throw new RuntimeException("Failed to create world");
		}
		load();
		tempClaimedIDs = new ArrayList<>();
	}

	public void update() {
		if (!hasInitialized) {
			initialize();
			hasInitialized = true;
		}

		if (!generatingCave.compareAndSet(false, true)) {
			return;
		}
		for (CaveTracker cave : caveTrackers) {
			if (cave.hasBeenJoined()) {
				long aliveTime = cave.getWorld().getFullTime() - cave.getJoinTime();
				if (aliveTime > cave.getStyle().getLifetime()) {
					deleteCave(cave);
					break;
				}
			}
		}
		if (caveTrackers.size() >= DescentIntoDarkness.instance.getInstanceLimit()) {
			generatingCave.set(false);
			return;
		}
		DyeColor color = getMostAppropriateColor();
		if (color == null) {
			generatingCave.set(false);
			return;
		}
		CaveStyle style = getRandomStyle(color);
		if (style == null) {
			generatingCave.set(false);
			return;
		}
		createCave(color, style).caveFuture.whenComplete((cave, throwable) -> {
			if (throwable != null) {
				Bukkit.getLogger().log(Level.SEVERE, "Failed to create cave", throwable);
			}
			generatingCave.set(false);
			Bukkit.getLogger().log(Level.INFO, "Cave " + cave.getId() + " is ready to join!");
		});
	}

	@Nullable
	private DyeColor getMostAppropriateColor() {
		return DescentIntoDarkness.instance.getCaveStyles().getGroups().entrySet().stream()
				.filter(entry -> !entry.getValue().getCaveWeights().isEmpty())
				.min(Comparator.<Map.Entry<DyeColor, CaveStyleGroup>>comparingDouble(entry -> (double) unexploredCavesByGroup.get(entry.getKey()).size() / entry.getValue().getGroupWeight())
						.thenComparingInt(entry -> -entry.getValue().getGroupWeight()))
				.map(Map.Entry::getKey)
				.orElse(null);
	}

	private CaveStyle getRandomStyle(DyeColor color) {
		CaveStyleGroup group = DescentIntoDarkness.instance.getCaveStyles().getGroups().get(color);
		if (group == null) {
			return null;
		}
		String chosenStyle = group.getCaveWeights().getRandom(new Random());
		if (chosenStyle == null) {
			return null;
		}

		CaveStyle caveStyle = DescentIntoDarkness.instance.getCaveStyles().getCaveStylesByName().get(chosenStyle);
		if (caveStyle == null || caveStyle.isAbstract()) {
			Bukkit.getLogger().log(Level.SEVERE, "Cannot instantiate cave style: " + chosenStyle);
			group.getCaveWeights().remove(chosenStyle);
			return getRandomStyle(color);
		}
		return caveStyle;
	}

	@Nullable
	public CaveTracker findFreeCave(DyeColor color) {
		ArrayList<CaveTracker> caves = unexploredCavesByGroup.get(color);
		return caves.isEmpty() ? null : caves.get(0);
	}

	public CaveCreationHandle createCave(DyeColor color, CaveStyle style) {
		int instanceCapacity = DescentIntoDarkness.instance.getInstanceCapacity();
		int oldInstanceId = nextInstanceId;
		Bukkit.getServer().getLogger().info("NextInstanceID: " + nextInstanceId + " Instance Capacity: " + instanceCapacity);
		while (getCaveById(nextInstanceId) != null || tempClaimedIDs.contains(nextInstanceId)) {
			nextInstanceId = (nextInstanceId + 1) % instanceCapacity;
			if (nextInstanceId == oldInstanceId) {
				return CaveCreationHandle.createExceptionally(new RuntimeException("Could not create cave instances: no free caves left"));
			}
		}
		Bukkit.getServer().getLogger().info("Free ID found: " + nextInstanceId);


		int id = nextInstanceId;
		tempClaimedIDs.add(id);
		
		for(CaveTracker t : DescentIntoDarkness.instance.getCaveTrackerManager().getCaves()) {
			Bukkit.getServer().getLogger().info("CaveTracker found, ID: " + t.getId() + " join time: " + t.getJoinTime());
		}

		Bukkit.getLogger().log(Level.INFO, "Generating cave with ID " + id);

		return new CaveCreationHandle(id, DescentIntoDarkness.instance.supplyAsync(() -> {
			BlockVector2 caveChunkCoords = getInstanceChunkCoords(id);
			BlockVector3 spawnPos = BlockVector3.at(caveChunkCoords.getBlockX() * 16, style.getStartY(), caveChunkCoords.getBlockZ() * 16);
			long seed = new Random().nextLong() ^ System.nanoTime();
			CuboidRegion limit = new CuboidRegion(
					spawnPos.multiply(1, 0, 1).subtract(8 * INSTANCE_WIDTH_CHUNKS - 32, 0, 8 * INSTANCE_WIDTH_CHUNKS - 32),
					spawnPos.multiply(1, 0, 1).add(8 * INSTANCE_WIDTH_CHUNKS - 32, 255, 8 * INSTANCE_WIDTH_CHUNKS - 32)
			);
			Set<BlockVector2> accessedChunks = new HashSet<>();
			try (CaveGenContext ctx = CaveGenContext.create(BukkitAdapter.adapt(theWorld), style, seed).limit(limit).outputAccessedChunksTo(accessedChunks)) {
				CaveGenerator.generateCave(ctx, spawnPos.toVector3());
			} catch (WorldEditException e) {
				throw new RuntimeException("Could not generate cave", e);
			}
			Location spawnPoint = BukkitAdapter.adapt(theWorld, spawnPos);
			while (style.isTransparentBlock(BukkitAdapter.adapt(spawnPoint.getBlock().getBlockData()))) {
				spawnPoint.add(0, -1, 0);
			}
			spawnPoint.add(0, 1, 0);
			return DescentIntoDarkness.instance.supplySyncNow(() -> {
				CaveTracker caveTracker = new CaveTracker(id, theWorld, spawnPoint, style, new ArrayList<>(accessedChunks));
				caveTrackers.add(caveTracker);
				unexploredCavesByGroup.get(color).add(caveTracker);
				Bukkit.getServer().getLogger().info("Returning new CaveTracker of ID: " + id);
				tempClaimedIDs.remove(Integer.valueOf(id));
				save();
				return caveTracker;
			});
		}));
	}

	public void deleteCave(CaveTracker caveTracker) {
		Bukkit.getLogger().log(Level.INFO, "Deleting cave " + caveTracker.getId());

		HiddenOre.onDeleteCave(caveTracker.getId());

		List<UUID> members = caveTracker.getPlayers();
		for (int i = members.size() - 1; i >= 0; i--) {
			UUID player = members.get(i);

			OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(player);
			//noinspection ConstantConditions - dumb bukkit
			if (offlinePlayer == null || !offlinePlayer.hasPlayedBefore()) {
				continue;
			}

			Location spawnLocation = overworldPlayerLocations.remove(player);
			if (spawnLocation == null) {
				spawnLocation = offlinePlayer.getBedSpawnLocation();
				if (spawnLocation == null) {
					spawnLocation = DescentIntoDarkness.multiverseCore.getMVWorldManager().getSpawnWorld().getSpawnLocation();
				}
			}
			if (spawnLocation != null) {
				Util.teleportOfflinePlayer(offlinePlayer, spawnLocation);
			}

			caveTracker.removePlayer(player);
		}

		// remove all entities in the cave
		for (BlockVector2 chunkCoords : caveTracker.getChunkPositions()) {
			Chunk chunk = caveTracker.getWorld().getChunkAt(chunkCoords.getBlockX(), chunkCoords.getBlockZ());
			if (!chunk.isLoaded()) {
				chunk.load();
			}
			for (Entity entity : chunk.getEntities()) {
				if (!(entity instanceof Player)) {
					entity.remove();
				}
			}
		}

		caveTracker.getTeam().unregister();
		caveTrackers.remove(caveTracker);
		save();
	}

	public boolean isInCave(Player p) {
		return getCaveForPlayer(p) != null;
	}

	@Nullable
	public Location respawnPlayer(Player p) {
		CaveTracker existingCave = getCaveForPlayer(p);
		if (existingCave == null) {
			return null;
		}

		existingCave.removePlayer(p.getUniqueId());
//		if (existingCave.getPlayers().isEmpty()) {
//			deleteCave(existingCave);
//		}

		Location newLocation = overworldPlayerLocations.remove(p.getUniqueId());
		if (newLocation == null) {
			newLocation = p.getBedSpawnLocation();
			if (newLocation == null) {
				newLocation = DescentIntoDarkness.multiverseCore.getMVWorldManager().getSpawnWorld().getSpawnLocation();
			}
		}
		return newLocation;
	}

	public boolean teleportPlayerTo(Player p, @Nullable CaveTracker newCave) {
		CaveTracker existingCave = getCaveForPlayer(p);
		if (existingCave == newCave) {
			return true;
		}

		if (newCave == null) {
			Location newLocation = respawnPlayer(p);
			if (newLocation != null) {
				isLeavingCave.set(true);
				try {
					p.teleport(newLocation);
				} finally {
					isLeavingCave.set(false);
				}
				return true;
			} else {
				return false;
			}
		}

		if (existingCave == null) {
			overworldPlayerLocations.put(p.getUniqueId(), p.getLocation());
		} else {
			existingCave.removePlayer(p.getUniqueId());
//			if (existingCave.getPlayers().isEmpty()) {
//				deleteCave(existingCave);
//			}
		}

		Location start = newCave.getStart();
		String destStr = String.format("e:%s:%f,%f,%f", WORLD_NAME, start.getX(), start.getY(), start.getZ());
		MVDestination dest = DescentIntoDarkness.multiverseCore.getDestFactory().getDestination(destStr);
		newCave.addPlayer(p.getUniqueId());
		if (DescentIntoDarkness.multiverseCore.getSafeTTeleporter().teleport(Bukkit.getConsoleSender(), p, dest) != TeleportResult.SUCCESS) {
			newCave.removePlayer(p.getUniqueId());
			return false;
		}

		return true;
	}

	public boolean isLeavingCave() {
		return isLeavingCave.get();
	}

	@Nullable
	public CaveTracker getCaveById(int id) {
		for (CaveTracker cave : caveTrackers) {
			if (cave.getId() == id) {
				Bukkit.getServer().getLogger().info("Checking ID " + id + "... taken.");
				return cave;
			}
		}
		Bukkit.getServer().getLogger().info("Checking ID " + id + "... available!");
		return null;
	}

	@Nullable
	public CaveTracker getCaveForPlayer(Player p) {
		for (CaveTracker cave : caveTrackers) {
			List<UUID> members = cave.getPlayers();
			if(members.contains(p.getUniqueId())) {
				return cave;
			}
		}
		return null;
	}

	public static boolean isCaveWorld(World world) {
		MultiverseWorld mvWorld = DescentIntoDarkness.multiverseCore.getMVWorldManager().getMVWorld(world);
		return mvWorld != null && mvWorld.getName().equals(WORLD_NAME);
	}

	public List<CaveTracker> getCaves() {
		return Collections.unmodifiableList(caveTrackers);
	}

	public Objective getPollutionObjective() {
		if (pollutionObjective == null) {
			pollutionObjective = DescentIntoDarkness.instance.getScoreboard().getObjective("pollution");
			if (pollutionObjective == null) {
				pollutionObjective = DescentIntoDarkness.instance.getScoreboard().registerNewObjective("pollution", "dummy", "Pollution");
			}
			if (DescentIntoDarkness.instance.showDebugPollution()) {
				pollutionObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
			}
		}
		return pollutionObjective;
	}

	@Nullable
	private World getOrCreateFlatWorld() {
		MVWorldManager worldManager = DescentIntoDarkness.multiverseCore.getMVWorldManager();
		// Sometimes worlds can linger after a server crash
		if (worldManager.isMVWorld(WORLD_NAME)) {
			return worldManager.getMVWorld(WORLD_NAME).getCBWorld();
		}

		String generator = "DescentIntoDarkness:full_" + ConfigUtil.serializeBlock(Util.requireDefaultState(BlockTypes.STONE));
		if (!worldManager.addWorld(WORLD_NAME, World.Environment.THE_END, "0", WorldType.FLAT, Boolean.FALSE, generator, false)) {
			return null;
		}
		MultiverseWorld mvWorld = worldManager.getMVWorld(WORLD_NAME);
		mvWorld.setRespawnToWorld(worldManager.getSpawnWorld().getName());
		World world = mvWorld.getCBWorld();
		world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
		world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);

		return world;
	}

	private BlockVector2 getInstanceChunkCoords(int instanceId) {
		int ring = (int) (0.5 * (Math.sqrt(instanceId + 1) - 1) + 0.00000001); // hopefully this addition compensates for rounding errors
		int radius = ring + 1;
		int instanceInRing = instanceId - 4 * (ring * ring + ring);
		int d = instanceInRing % (radius + radius);
		switch (instanceInRing / (radius + radius)) {
			case 0: return BlockVector2.at(d - radius, -radius).multiply(INSTANCE_WIDTH_CHUNKS);
			case 1: return BlockVector2.at(radius, d - radius).multiply(INSTANCE_WIDTH_CHUNKS);
			case 2: return BlockVector2.at(radius - d, radius).multiply(INSTANCE_WIDTH_CHUNKS);
			case 3: return BlockVector2.at(-radius, radius - d).multiply(INSTANCE_WIDTH_CHUNKS);
			default: throw new ArithmeticException("Earth is bad at math!");
		}
	}

	public void load() {
		this.overworldPlayerLocations.clear();
		this.caveTrackers.clear();
		this.unexploredCavesByGroup.clear();
		for (DyeColor color : DyeColor.values()) {
			unexploredCavesByGroup.put(color, new ArrayList<>());
		}

		File runtimeFolder = new File(DescentIntoDarkness.instance.getDataFolder(), "runtime");
		File caveTrackerFile = new File(runtimeFolder, "cave_trackers.yml");
		if (!caveTrackerFile.exists()) {
			return;
		}
		FileConfiguration config = YamlConfiguration.loadConfiguration(caveTrackerFile);

		ConfigurationSection overworldPlayerLocations = config.getConfigurationSection("overworldPlayerLocations");
		if (overworldPlayerLocations != null) {
			for (String uuidStr : overworldPlayerLocations.getKeys(false)) {
				UUID uuid;
				try {
					uuid = UUID.fromString(uuidStr);
				} catch (IllegalArgumentException e) {
					continue;
				}
				ConfigurationSection value = overworldPlayerLocations.getConfigurationSection(uuidStr);
				if (value == null) {
					continue;
				}
				Location location = new Location(null, value.getDouble("x", 0), value.getDouble("y", 0), value.getDouble("z", 0));
				String world = value.getString("world");
				if (world != null) {
					location.setWorld(Bukkit.getWorld(world));
				}
				this.overworldPlayerLocations.put(uuid, location);
			}
		}

		List<Map<?, ?>> caveTrackers = config.getMapList("caveTrackers");
		for (Map<?, ?> caveTracker : caveTrackers) {
			this.caveTrackers.add(new CaveTracker(theWorld, ConfigUtil.asConfigurationSection(caveTracker)));
		}

		ConfigurationSection unexploredCavesByGroup = config.getConfigurationSection("unexploredCavesByGroup");
		if (unexploredCavesByGroup != null) {
			for (String group : unexploredCavesByGroup.getKeys(false)) {
				DyeColor color = ConfigUtil.tryParseEnum(DyeColor.class, group);
				if (color == null) {
					continue;
				}
				List<?> value = unexploredCavesByGroup.getList(group);
				if (value == null) {
					continue;
				}
				for (Object obj : value) {
					Integer caveId = Util.tryCastInt(obj);
					if (caveId == null) {
						continue;
					}
					this.unexploredCavesByGroup.get(color).add(getCaveById(caveId));
				}
			}
		}
	}

	public void save() {
		File runtimeFolder = new File(DescentIntoDarkness.instance.getDataFolder(), "runtime");
		if (!runtimeFolder.exists() && !runtimeFolder.mkdirs()) {
			return;
		}
		FileConfiguration config = new YamlConfiguration();

		overworldPlayerLocations.forEach((uuid, location) -> {
			ConfigurationSection section = config.createSection("overworldPlayerLocations." + uuid.toString());
			World world = location.getWorld();
			if (world != null) {
				section.set("world", world.getName());
			}
			section.set("x", location.getX());
			section.set("y", location.getY());
			section.set("z", location.getZ());
		});

		List<ConfigurationSection> caveTrackerConfig = new ArrayList<>();
		for (CaveTracker caveTracker : caveTrackers) {
			ConfigurationSection section = new MemoryConfiguration();
			caveTracker.serialize(section);
			caveTrackerConfig.add(section);
		}
		config.set("caveTrackers", caveTrackerConfig);

		unexploredCavesByGroup.forEach((group, caves) -> {
			List<Integer> caveIds = new ArrayList<>();
			for (CaveTracker cave : caves) {
				if (cave != null) {
					caveIds.add(cave.getId());
				}
			}
			config.set("unexploredCavesByGroup." + group.name().toLowerCase(Locale.ROOT), caveIds);
		});

		Util.saveSafely(new File(runtimeFolder, "cave_trackers.yml"), config::save);
	}

	public static class CaveCreationHandle {
		public final int caveId;
		public final CompletableFuture<CaveTracker> caveFuture;

		public CaveCreationHandle(int caveId, CompletableFuture<CaveTracker> caveFuture) {
			this.caveId = caveId;
			this.caveFuture = caveFuture;
		}

		public static CaveCreationHandle createExceptionally(Throwable t) {
			return new CaveCreationHandle(-1, Util.completeExceptionally(t));
		}

		public boolean isError() {
			return caveFuture.isCompletedExceptionally();
		}
	}

}
