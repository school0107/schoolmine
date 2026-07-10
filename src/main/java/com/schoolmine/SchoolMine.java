package com.schoolmine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class SchoolMine extends JavaPlugin implements Listener {
    public final HashMap<UUID, Boolean> autoPickupUsers = new HashMap<>();
    public final HashMap<UUID, Boolean> autoSmeltUsers = new HashMap<>();
    public final HashMap<UUID, Boolean> mining3x3Users = new HashMap<>();
    public final HashMap<UUID, Boolean> autoMineUsers = new HashMap<>();

    public final Map<Location, Long> blockRemoveQueue = new HashMap<>();
    private FileConfiguration boosterConfig;
    private FileConfiguration queueStorage;
    private File queueFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("booster.yml", false);
        loadBoosterConfig();
        
        queueFile = new File(getDataFolder(), "blockremove_queue.yml");
        if (!queueFile.exists()) {
            try { queueFile.createNewFile(); } catch (IOException ignored) {}
        }
        queueStorage = YamlConfiguration.loadConfiguration(queueFile);
        loadBlockQueue();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new MiningListener(this), this);

        MineCommands commands = new MineCommands(this);
        String[] cmdList = {"autopickup", "autosmelt", "3x3", "automine", "booster", "schoolmine"};
        for (String cmd : cmdList) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(commands);
        }

        int ticks = getConfig().getInt("block-remove.check-interval-ticks", 40);
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<Location, Long>> iterator = blockRemoveQueue.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Location, Long> entry = iterator.next();
                    if (now >= entry.getValue()) {
                        Location loc = entry.getKey();
                        Block block = loc.getBlock();
                        List<String> blacklist = getConfig().getStringList("block-remove.blacklist");
                        if (!blacklist.contains(block.getType().name())) {
                            block.setType(Material.AIR);
                        }
                        iterator.remove();
                    }
                }
            }
        }.runTaskTimer(this, ticks, ticks);
    }

    @Override
    public void onDisable() {
        if (getConfig().getBoolean("block-remove.persist-on-restart", true)) {
            saveBlockQueue();
        }
    }

    public void loadBoosterConfig() {
        File boosterFile = new File(getDataFolder(), "booster.yml");
        this.boosterConfig = YamlConfiguration.loadConfiguration(boosterFile);
    }

    public FileConfiguration getBoosterConfig() {
        return this.boosterConfig;
    }

    public String getMsg(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&6SchoolMine&8] &r");
        String message = getConfig().getString("messages." + path, "");
        return (prefix + message).replace("&", "§");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!getConfig().getBoolean("block-remove.enabled", true)) return;
        Player player = event.getPlayer();
        
        if (player.hasPermission("schoolmine.breakblock") && !getConfig().getBoolean("schoolmine.breakblock", true)) {
            return;
        }

        World world = event.getBlock().getWorld();
        List<String> worldWhitelist = getConfig().getStringList("block-remove.world-whitelist");
        if (!worldWhitelist.isEmpty() && !worldWhitelist.contains(world.getName())) return;

        int maxQueue = getConfig().getInt("block-remove.max-queue-size", 100000);
        if (blockRemoveQueue.size() >= maxQueue) return;

        long delayMs = getConfig().getLong("block-remove.delay-seconds", 120) * 1000L;
        blockRemoveQueue.put(event.getBlock().getLocation(), System.currentTimeMillis() + delayMs);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!autoMineUsers.getOrDefault(player.getUniqueId(), false)) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.getType().name().contains("PICKAXE")) return;

        int radius = getConfig().getInt("auto-mine.default-radius", 2);
        for (int i = 6; i >= 1; i--) {
            if (player.hasPermission("automine." + i)) {
                radius = i;
                break;
            }
        }

        Location loc = player.getLocation();
        int ticksInterval = getBoosterConfig().getInt("nuker.check-interval-ticks", 15);
        if (player.getTicksLived() % ticksInterval != 0) return;

        List<String> whitelist = getConfig().getStringList("3x3-mining.whitelist");
        int maxBlocks = getBoosterConfig().getInt("nuker.max-blocks-per-check", 25);
        int broken = 0;

        double speedFactor = 1.0;
        String toolName = tool.getType().name();
        if (toolName.contains("WOODEN")) speedFactor = 0.5;
        else if (toolName.contains("STONE")) speedFactor = 1.0;
        else if (toolName.contains("IRON")) speedFactor = 1.5;
        else if (toolName.contains("GOLDEN")) speedFactor = 2.0;
        else if (toolName.contains("DIAMOND")) speedFactor = 2.5;
        else if (toolName.contains("NETHERITE")) speedFactor = 3.0;

        if (tool.hasItemMeta()) {
            ItemMeta meta = tool.getItemMeta();
            if (meta != null && meta.hasEnchant(Enchantment.EFFICIENCY)) {
                speedFactor += (meta.getEnchantLevel(Enchantment.EFFICIENCY) * 0.4);
            }
        }

        for (int x = -radius; x <= radius && broken < maxBlocks * speedFactor; x++) {
            for (int y = 0; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = loc.clone().add(x, y, z).getBlock();
                    if (whitelist.contains(b.getType().name())) {
                        player.breakBlock(b);
                        broken++;
                    }
                }
            }
        }
    }

    private void saveBlockQueue() {
        queueStorage.set("queue", null);
        int i = 0;
        for (Map.Entry<Location, Long> entry : blockRemoveQueue.entrySet()) {
            String path = "queue." + i;
            queueStorage.set(path + ".world", entry.getKey().getWorld().getName());
            queueStorage.set(path + ".x", entry.getKey().getBlockX());
            queueStorage.set(path + ".y", entry.getKey().getBlockY());
            queueStorage.set(path + ".z", entry.getKey().getBlockZ());
            queueStorage.set(path + ".time", entry.getValue());
            i++;
        }
        try { queueStorage.save(queueFile); } catch (IOException ignored) {}
    }

    private void loadBlockQueue() {
        if (!queueStorage.contains("queue")) return;
        if (queueStorage.getConfigurationSection("queue") == null) return;
        for (String key : queueStorage.getConfigurationSection("queue").getKeys(false)) {
            String path = "queue." + key;
            String worldName = queueStorage.getString(path + ".world");
            if (worldName == null) continue;
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;
            int x = queueStorage.getInt(path + ".x");
            int y = queueStorage.getInt(path + ".y");
            int z = queueStorage.getInt(path + ".z");
            long time = queueStorage.getLong(path + ".time");
            blockRemoveQueue.put(new Location(world, x, y, z), time);
        }
    }
}

class MiningListener implements Listener {
    private final SchoolMine plugin;

    public MiningListener(SchoolMine plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (event.isCancelled()) return;

        UUID uuid = player.getUniqueId();
        boolean usePickup = plugin.autoPickupUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-pickup.default-enabled", true));
        boolean useSmelt = plugin.autoSmeltUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-smelt.default-enabled", true));
        double multiplier = getDropMultiplier(player);

        List<String> pickupBlacklist = plugin.getConfig().getStringList("auto-pickup.blacklist");

        if (usePickup || useSmelt || multiplier > 1.0) {
            if (pickupBlacklist.contains(block.getType().name())) return;

            event.setDropItems(false);
            Collection<ItemStack> drops = block.getDrops(player.getInventory().getItemInMainHand());
            for (ItemStack drop : drops) {
                ItemStack finalDrop = drop.clone();
                if (useSmelt) {
                    finalDrop = processAutoSmelt(finalDrop);
                }
                finalDrop.setAmount((int) (finalDrop.getAmount() * multiplier));

                if (usePickup) {
                    if (!player.getInventory().addItem(finalDrop).isEmpty()) {
                        block.getWorld().dropItemNaturally(block.getLocation(), finalDrop);
                    }
                } else {
                    block.getWorld().dropItemNaturally(block.getLocation(), finalDrop);
                }
            }
            player.giveExp(event.getExpToDrop());
            event.setExpToDrop(0);
        }
    }

    private double getDropMultiplier(Player player) {
        for (double i = 5.0; i >= 1.2; i -= 0.2) {
            String perm = String.format(Locale.US, "mine.x%.1f", i).replace(".0", "");
            if (player.hasPermission(perm)) return i;
        }
        return 1.0;
    }

    private ItemStack processAutoSmelt(ItemStack item) {
        String original = item.getType().name();
        String path = "auto-smelt.smelt-map." + original;
        if (plugin.getConfig().contains(path)) {
            String target = plugin.getConfig().getString(path);
            if (target != null) {
                Material mat = Material.getMaterial(target);
                if (mat != null) item.setType(mat);
            }
        }
        return item;
    }
}

class MineCommands implements CommandExecutor {
    private final SchoolMine plugin;

    public MineCommands(SchoolMine plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("schoolmine")) {
            if (!sender.hasPermission("schoolmine.admin")) {
                sender.sendMessage(plugin.getMsg("no-permission"));
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                plugin.reloadConfig();
                plugin.loadBoosterConfig();
                sender.sendMessage(plugin.getMsg("reload-success"));
                return true;
            } else if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("§6=== SchoolMine Status ===");
                sender.sendMessage("§eBlock Remove Queue Size: §f" + plugin.blockRemoveQueue.size());
                sender.sendMessage("§eActive AutoPickup Players: §f" + plugin.autoPickupUsers.size());
                return true;
            }
            sender.sendMessage("§eSử dụng: /schoolmine [reload|status|help]");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cChỉ người chơi mới gõ được lệnh này!");
            return true;
        }

        UUID uuid = player.getUniqueId();

        if (cmd.getName().equalsIgnoreCase("autopickup")) {
            boolean current = plugin.autoPickupUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-pickup.default-enabled", true));
            plugin.autoPickupUsers.put(uuid, !current);
            player.sendMessage(plugin.getMsg(!current ? "autopickup-on" : "autopickup-off"));
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("autosmelt")) {
            boolean current = plugin.autoSmeltUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-smelt.default-enabled", true));
            plugin.autoSmeltUsers.put(uuid, !current);
            player.sendMessage(plugin.getMsg(!current ? "autosmelt-on" : "autosmelt-off"));
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("3x3")) {
            boolean current = plugin.mining3x3Users.getOrDefault(uuid, false);
            plugin.mining3x3Users.put(uuid, !current);
            player.sendMessage(plugin.getMsg(!current ? "mining3x3-on" : "mining3x3-off"));
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("automine")) {
            boolean current = plugin.autoMineUsers.getOrDefault(uuid, false);
            plugin.autoMineUsers.put(uuid, !current);
            player.sendMessage(plugin.getMsg(!current ? "automine-on" : "automine-off"));
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("booster")) {
            player.sendMessage(plugin.getBoosterConfig().getString("gui.main-title", "&8&l⚡ Booster Menu").replace("&", "§"));
            return true;
        }
        return false;
    }
}
