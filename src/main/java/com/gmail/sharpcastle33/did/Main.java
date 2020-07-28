package com.gmail.sharpcastle33.did;

import com.gmail.sharpcastle33.did.config.CaveStyle;
import com.gmail.sharpcastle33.did.config.InvalidConfigException;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.gmail.sharpcastle33.did.listeners.CommandListener;
import com.gmail.sharpcastle33.did.listeners.OreListener;
import com.gmail.sharpcastle33.dungeonmaster.DungeonMaster;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class Main extends JavaPlugin {

	private DungeonMaster dungeonMaster;

	private FileConfiguration config = getConfig();
	private FileConfiguration caveStylesConfig;
	private Map<String, CaveStyle> caveStyles = null;
	private Map<String, Clipboard> schematics = new HashMap<>();

	public static Main plugin;

	@Override
	public void onEnable() {
		plugin = this;

		setupConfig();

		dungeonMaster = new DungeonMaster();
		registerCommand("did", new CommandListener());
		Bukkit.getPluginManager().registerEvents(new OreListener(), plugin);
	}

	private <T extends CommandExecutor & TabCompleter> void registerCommand(String name, T executor) {
		PluginCommand command = getCommand(name);
		if (command == null) {
			throw new IllegalStateException("Cannot find command: \"" + name + "\"");
		}
		command.setExecutor(executor);
		command.setTabCompleter(executor);
	}

	private void setupConfig() {
		//config.addDefault("caveStyles", Lists.newArrayList("default"));
		config.options().copyDefaults(true);
		saveConfig();

		caveStylesConfig = reloadConfig("caveStyles");
		ConfigurationSection defaultConfig = new MemoryConfiguration();
		CaveStyle.DEFAULT.serialize(defaultConfig);
		caveStylesConfig.addDefaults(defaultConfig.getValues(false).entrySet().stream()
				.map(entry -> new AbstractMap.SimpleEntry<>("default." + entry.getKey(), entry.getValue()))
				.collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue, (a, b) -> a, (Supplier<HashMap<String, Object>>)HashMap::new)));
		caveStylesConfig.options().copyDefaults(true);
		saveConfig("caveStyles", caveStylesConfig);
		reload();
	}

	public void reload() {
		schematics.clear();
		reloadConfig();
		caveStylesConfig = reloadConfig("caveStyles");
		caveStyles = null;
		getCaveStyles(); // for error messages TODO: reload this lazily?
	}

	private FileConfiguration reloadConfig(String configName) {
		File configFile = new File(getDataFolder(), configName + ".yml");
		return YamlConfiguration.loadConfiguration(configFile);
	}

	private void saveConfig(String configName, FileConfiguration config) {
		File configFile = new File(getDataFolder(), configName + ".yml");
		try {
			config.save(configFile);
		} catch (IOException e) {
			getLogger().log(Level.SEVERE, "Could not save " + configName + ".yml", e);
		}
	}

	public Map<String, CaveStyle> getCaveStyles() {
		if (caveStyles == null) {
			caveStyles = new HashMap<>();
			for (String styleName : caveStylesConfig.getKeys(false)) {
				try {
					ConfigurationSection value = caveStylesConfig.getConfigurationSection(styleName);
					if (value == null) {
						throw new InvalidConfigException("Cave style \"" + styleName + "\" has invalid type");
					}
					caveStyles.put(styleName, CaveStyle.deserialize(value));
				} catch (InvalidConfigException e) {
					getLogger().log(Level.SEVERE, "Failed to load cave style " + styleName, e);
				}
			}
		}
		return caveStyles;
	}

	public Clipboard getSchematic(String name) {
		Clipboard schematic = schematics.get(name);
		if (schematic != null) {
			return schematic;
		}
		File schemDir = new File(getDataFolder(), "schematics");
		if (!schemDir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			schemDir.mkdirs();
		}

		File schemFile = new File(schemDir, name + ".schem");
		if (!schemFile.exists()) {
			return null;
		}

		ClipboardFormat format = ClipboardFormats.findByFile(schemFile);
		if (format == null) {
			return null;
		}
		try (ClipboardReader reader = format.getReader(new FileInputStream(schemFile))) {
			schematic = reader.read();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

		schematics.put(name, schematic);
		return schematic;
	}

	public DungeonMaster getDungeonMaster() {
		return this.dungeonMaster;
	}

	private static List<Material> ALL_MATERIALS;

	public static List<Material> getAllMaterials() {
		// I can't find a better way of doing this
		if (ALL_MATERIALS == null) {
			ALL_MATERIALS = new ArrayList<>();
			for (Material material : Material.values()) {
				if (!material.name().startsWith("LEGACY_")) {
					ALL_MATERIALS.add(material);
				}
			}
		}
		return ALL_MATERIALS;
	}
}
