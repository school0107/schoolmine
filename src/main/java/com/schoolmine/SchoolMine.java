package com.schoolmine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class SchoolMine extends JavaPlugin {
    public final Map<UUID, Boolean> autoPickupUsers = new HashMap<>();
    public final Map<UUID, Boolean> autoSmeltUsers = new HashMap<>();
    public final Map<UUID, Boolean> mining3x3Users = new HashMap<>();
    public final Map<UUID, Boolean> autoMineUsers = new HashMap<>();

    public final Map<Location, Long> blockRemoveQueue = new HashMap<>();
    private File queueFile;
    private FileConfiguration queueStorage;

    private FileConfiguration boosterConfig;
    private PlayerPointsAPI ppAPI;
    
    public final Map<String, Long> serverBoosters = new HashMap<>();
    public final Map<UUID, Map<String, Long>> playerBoosters = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("booster.yml", false);
        loadBoosterConfig();

        if (getServer().getPluginManager().getPlugin("PlayerPoints") != null) {
            this.ppAPI = PlayerPoints.getInstance().getAPI();
        }

        queueFile = new File(getDataFolder(), "blockremove_queue.yml");
        if (!queueFile.exists()) {
            try { queueFile.createNewFile(); } catch (IOException ignored) {}
        }
        queueStorage = YamlConfiguration.loadConfiguration(queueFile);
        loadBlockQueue();

        // Đăng ký Events & Commands
        getServer().getPluginManager().registerEvents(new MineListener(this), this);
        
        MineCommands commandExecutor = new MineCommands(this);
        String[] commands = {"autopickup", "autosmelt", "3x3", "automine", "booster", "schoolmine"};
        for (String cmd : commands) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(commandExecutor);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerData(p.getUniqueId());
        }

        int intervalTicks = getConfig().getInt("block-remove.check-interval-ticks", 40);
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<Location, Long>> it = blockRemoveQueue.entrySet().iterator();
                List<String> blacklist = getConfig().getStringList("block-remove.blacklist");
                
                while (it.hasNext()) {
                    Map.Entry<Location, Long> entry = it.next();
                    if (now >= entry.getValue()) {
                        Location loc = entry.getKey();
                        Block b = loc.getBlock();
                        if (!blacklist.contains(b.getType().name())) {
                            b.setType(Material.AIR);
                        }
                        it.remove();
                    }
                }
            }
        }.runTaskTimer(this, intervalTicks, intervalTicks);

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    updatePlayerBossBar(p, now);
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (getConfig().getBoolean("block-remove.persist-on-restart", true)) {
            saveBlockQueue();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            savePlayerData(p.getUniqueId());
        }
        for (BossBar bar : playerBossBars.values()) {
            bar.removeAll();
        }
    }

    public void loadBoosterConfig() {
        File f = new File(getDataFolder(), "booster.yml");
        this.boosterConfig = YamlConfiguration.loadConfiguration(f);
    }

    public FileConfiguration getBoosterConfig() { return this.boosterConfig; }
    public PlayerPointsAPI getPlayerPointsAPI() { return this.ppAPI; }

    public String getMsg(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&6SchoolMine&8] &r");
        String msg = getConfig().getString("messages." + path, "");
        return (prefix + msg).replace("&", "§");
    }

    public void updatePlayerBossBar(Player p, long now) {
        UUID uuid = p.getUniqueId();
        long sTime = serverBoosters.getOrDefault("all", 0L) - now;
        long pTime = playerBoosters.containsKey(uuid) ? playerBoosters.get(uuid).getOrDefault("all", 0L) - now : 0L;
        long highest = Math.max(sTime, pTime);

        if (highest > 0) {
            String title = "§6§l⚡ BOOSTER ĐANG HOẠT ĐỘNG: §e" + (highest / 1000L) + " giây còn lại";
            if (!playerBossBars.containsKey(uuid)) {
                BossBar bar = Bukkit.createBossBar(title, BarColor.GOLD, BarStyle.SOLID);
                bar.addPlayer(p);
                playerBossBars.put(uuid, bar);
            } else {
                playerBossBars.get(uuid).setTitle(title);
            }
        } else if (playerBossBars.containsKey(uuid)) {
            playerBossBars.get(uuid).removeAll();
            playerBossBars.remove(uuid);
        }
    }

    public void clearBossBar(UUID uuid) {
        if (playerBossBars.containsKey(uuid)) {
            playerBossBars.get(uuid).removeAll();
            playerBossBars.remove(uuid);
        }
    }

    public void loadPlayerData(UUID uuid) {
        File f = new File(getDataFolder(), "playerdata/" + uuid + ".yml");
        if (!f.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        autoPickupUsers.put(uuid, cfg.getBoolean("autopickup", true));
        autoSmeltUsers.put(uuid, cfg.getBoolean("autosmelt", true));
        mining3x3Users.put(uuid, cfg.getBoolean("mining3x3", false));
        autoMineUsers.put(uuid, cfg.getBoolean("automine", false));
    }

    public void savePlayerData(UUID uuid) {
        File f = new File(getDataFolder(), "playerdata/" + uuid + ".yml");
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("autopickup", autoPickupUsers.getOrDefault(uuid, true));
        cfg.set("autosmelt", autoSmeltUsers.getOrDefault(uuid, true));
        cfg.set("mining3x3", mining3x3Users.getOrDefault(uuid, false));
        cfg.set("automine", autoMineUsers.getOrDefault(uuid, false));
        try { cfg.save(f); } catch (IOException ignored) {}
    }

    private void saveBlockQueue() {
        queueStorage.set("queue", null);
        int i = 0;
        for (Map.Entry<Location, Long> entry : blockRemoveQueue.entrySet()) {
            String base = "queue." + i;
            queueStorage.set(base + ".w", entry.getKey().getWorld().getName());
            queueStorage.set(base + ".x", entry.getKey().getBlockX());
            queueStorage.set(base + ".y", entry.getKey().getBlockY());
            queueStorage.set(base + ".z", entry.getKey().getBlockZ());
            queueStorage.set(base + ".t", entry.getValue());
            i++;
        }
        try { queueStorage.save(queueFile); } catch (IOException ignored) {}
    }

    private void loadBlockQueue() {
        if (!queueStorage.contains("queue") || queueStorage.getConfigurationSection("queue") == null) return;
        for (String key : queueStorage.getConfigurationSection("queue").getKeys(false)) {
            String base = "queue." + key;
            World w = Bukkit.getWorld(queueStorage.getString(base + ".w", ""));
            if (w == null) continue;
            int x = queueStorage.getInt(base + ".x");
            int y = queueStorage.getInt(base + ".y");
            int z = queueStorage.getInt(base + ".z");
            long t = queueStorage.getLong(base + ".t");
            blockRemoveQueue.put(new Location(w, x, y, z), t);
        }
    }
}
