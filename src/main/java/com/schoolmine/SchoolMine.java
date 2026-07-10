package com.schoolmine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class SchoolMine extends JavaPlugin implements Listener {
    // Trạng thái bật/tắt tính năng cá nhân của người chơi
    public final HashMap<UUID, Boolean> autoPickupUsers = new HashMap<>();
    public final HashMap<UUID, Boolean> autoSmeltUsers = new HashMap<>();
    public final HashMap<UUID, Boolean> mining3x3Users = new HashMap<>();
    public final HashMap<UUID, Boolean> autoMineUsers = new HashMap<>();

    // Hệ thống hàng đợi xóa Block tự động (Block Remove Queue)
    public final Map<Location, Long> blockRemoveQueue = new HashMap<>();
    private FileConfiguration boosterConfig;
    private FileConfiguration queueStorage;
    private File queueFile;
    private PlayerPointsAPI ppAPI;

    // Quản lý thời gian Booster (Lưu theo mili-giây thời điểm hết hạn)
    public final Map<String, Long> serverBoosters = new HashMap<>(); // key: type (3x3, multi, nuker, all...)
    public final Map<UUID, Map<String, Long>> playerBoosters = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();

    private static boolean internal3x3Processing = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("booster.yml", false);
        loadBoosterConfig();
        
        // Kết nối API PlayerPoints cứng mềm an toàn
        if (getServer().getPluginManager().getPlugin("PlayerPoints") != null) {
            this.ppAPI = PlayerPoints.getInstance().getAPI();
        }
        
        // Cấu hình và nạp dữ liệu hàng chờ Block
        queueFile = new File(getDataFolder(), "blockremove_queue.yml");
        if (!queueFile.exists()) {
            try { queueFile.createNewFile(); } catch (IOException ignored) {}
        }
        queueStorage = YamlConfiguration.loadConfiguration(queueFile);
        loadBlockQueue();

        // Đăng ký toàn bộ trình sự kiện
        getServer().getPluginManager().registerEvents(this, this);

        // Đăng ký bộ lệnh thực thi
        MineCommands commands = new MineCommands(this);
        String[] cmdList = {"autopickup", "autosmelt", "3x3", "automine", "booster", "schoolmine"};
        for (String cmd : cmdList) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(commands);
        }

        // Vòng lặp Scheduler kiểm tra và dọn dẹp khối Block định kỳ
        int blockTicks = getConfig().getInt("block-remove.check-interval-ticks", 40);
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
        }.runTaskTimer(this, blockTicks, blockTicks);

        // Vòng lặp Scheduler xử lý hiển thị thời gian Booster thông qua BossBar (Cập nhật mỗi 20 ticks = 1 giây)
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    updatePlayerBossBar(player, now);
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (getConfig().getBoolean("block-remove.persist-on-restart", true)) {
            saveBlockQueue();
        }
        for (BossBar bar : playerBossBars.values()) {
            bar.removeAll();
        }
    }

    public void loadBoosterConfig() {
        File boosterFile = new File(getDataFolder(), "booster.yml");
        this.boosterConfig = YamlConfiguration.loadConfiguration(boosterFile);
    }

    public FileConfiguration getBoosterConfig() { return this.boosterConfig; }
    public PlayerPointsAPI getPlayerPointsAPI() { return this.ppAPI; }

    public String getMsg(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&6SchoolMine&8] &r");
        String message = getConfig().getString("messages." + path, "");
        return (prefix + message).replace("&", "§");
    }

    // --- XỬ LÝ SỰ KIỆN KHAI THÁC QUẶNG CỐT LÕI ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (internal3x3Processing) return;

        Player player = event.getPlayer();
        Block block = event.getBlock();
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();
        boolean hasActive3x3Booster = serverBoosters.getOrDefault("3x3", 0L) > now || 
                (playerBoosters.containsKey(uuid) && playerBoosters.get(uuid).getOrDefault("3x3", 0L) > now);

        boolean use3x3 = mining3x3Users.getOrDefault(uuid, getConfig().getBoolean("3x3-mining.default-enabled", false)) || hasActive3x3Booster;
        boolean usePickup = autoPickupUsers.getOrDefault(uuid, getConfig().getBoolean("auto-pickup.default-enabled", true));
        boolean useSmelt = autoSmeltUsers.getOrDefault(uuid, getConfig().getBoolean("auto-smelt.default-enabled", true));

        List<String> whitelist3x3 = getConfig().getStringList("3x3-mining.whitelist");

        // 1. Logic Đào 3x3 mở rộng diện tích vuông góc với hướng nhìn mặt
        if (use3x3 && whitelist3x3.contains(block.getType().name())) {
            event.setCancelled(true);
            internal3x3Processing = true;
            
            BlockFace face = getPlayerFacingFace(player);
            Location center = block.getLocation();
            
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        int tx = center.getBlockX() + (face.getModX() == 0 ? x : 0);
                        int ty = center.getBlockY() + (face.getModY() == 0 ? y : 0);
                        int tz = center.getBlockZ() + (face.getModZ() == 0 ? z : (face.getModX() != 0 ? x : y));
                        
                        if (face.getModY() != 0) {
                            tx = center.getBlockX() + x; tz = center.getBlockZ() + z; ty = center.getBlockY();
                        } else if (face.getModX() != 0) {
                            ty = center.getBlockY() + y; tz = center.getBlockZ() + z; tx = center.getBlockX();
                        } else {
                            tx = center.getBlockX() + x; ty = center.getBlockY() + y; tz = center.getBlockZ();
                        }

                        Block target = block.getWorld().getBlockAt(tx, ty, tz);
                        if (whitelist3x3.contains(target.getType().name()) && target.getType() != Material.AIR) {
                            BlockBreakEvent targetEvent = new BlockBreakEvent(target, player);
                            Bukkit.getPluginManager().callEvent(targetEvent);
                            if (!targetEvent.isCancelled()) {
                                processBlockReward(player, target, usePickup, useSmelt, now);
                                target.setType(Material.AIR);
                            }
                        }
                    }
                }
            }
            internal3x3Processing = false;
            return;
        }

        // 2. Xử lý thông thường nếu không kích hoạt hoặc block không nằm trong danh sách 3x3
        List<String> pickupBlacklist = getConfig().getStringList("auto-pickup.blacklist");
        if (pickupBlacklist.contains(block.getType().name())) return;

        event.setDropItems(false);
        processBlockReward(player, block, usePickup, useSmelt, now);
        player.giveExp(event.getExpToDrop());
        event.setExpToDrop(0);
    }

    private void processBlockReward(Player player, Block b, boolean pickup, boolean smelt, long now) {
        Collection<ItemStack> drops = b.getDrops(player.getInventory().getItemInMainHand());
        double multiplier = getDropMultiplier(player, now);

        for (ItemStack drop : drops) {
            ItemStack finalItem = drop.clone();
            if (smelt) {
                finalItem = processAutoSmeltItem(finalItem);
            }
            finalItem.setAmount((int) (finalItem.getAmount() * multiplier));

            if (pickup) {
                if (!player.getInventory().addItem(finalItem).isEmpty()) {
                    b.getWorld().dropItemNaturally(b.getLocation(), finalItem);
                }
            } else {
                b.getWorld().dropItemNaturally(b.getLocation(), finalItem);
            }
        }
    }

    private double getDropMultiplier(Player player, long now) {
        boolean hasMultiBooster = serverBoosters.getOrDefault("multi", 0L) > now || 
                (playerBoosters.containsKey(player.getUniqueId()) && playerBoosters.get(player.getUniqueId()).getOrDefault("multi", 0L) > now);
        
        if (hasMultiBooster) return 2.0; // Booster nhân x2 sản lượng khai thác mặc định

        for (double i = 5.0; i >= 1.2; i -= 0.2) {
            String perm = String.format(Locale.US, "mine.x%.1f", i).replace(".0", "");
            if (player.hasPermission(perm)) return i;
        }
        return 1.0;
    }

    private ItemStack processAutoSmeltItem(ItemStack item) {
        String original = item.getType().name();
        String path = "auto-smelt.smelt-map." + original;
        if (getConfig().contains(path)) {
            String target = getConfig().getString(path);
            if (target != null) {
                Material mat = Material.getMaterial(target);
                if (mat != null) item.setType(mat);
            }
        }
        return item;
    }

    private BlockFace getPlayerFacingFace(Player player) {
        double pitch = player.getLocation().getPitch();
        if (pitch > 45) return BlockFace.UP;
        if (pitch < -45) return BlockFace.DOWN;
        float yaw = player.getLocation().getYaw();
        if (yaw < 0) yaw += 360;
        if (yaw >= 315 || yaw < 45) return BlockFace.NORTH;
        if (yaw >= 45 && yaw < 135) return BlockFace.EAST;
        if (yaw >= 135 && yaw < 225) return BlockFace.SOUTH;
        return BlockFace.WEST;
    }

    // --- TÍNH NĂNG 5: AUTO MINE / NUKER DI CHUYỂN ---
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!autoMineUsers.getOrDefault(player.getUniqueId(), false)) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.getType().name().contains("PICKAXE")) return;

        int radius = getConfig().getInt("auto-mine.default-radius", 2);
        for (int i = 6; i >= 1; i--) {
            if (player.hasPermission("automine." + i)) { radius = i; break; }
        }

        int interval = getBoosterConfig().getInt("nuker.check-interval-ticks", 15);
        if (player.getTicksLived() % interval != 0) return;

        Location loc = player.getLocation();
        List<String> whitelist = getConfig().getStringList("3x3-mining.whitelist");
        int maxBlocks = getBoosterConfig().getInt("nuker.max-blocks-per-check", 25);
        int broken = 0;

        double speedFactor = 1.0;
        if (tool.getType().name().contains("DIAMOND")) speedFactor = 2.5;
        else if (tool.getType().name().contains("NETHERITE")) speedFactor = 3.0;

        if (tool.hasItemMeta() && tool.getItemMeta() != null) {
            if (tool.getItemMeta().hasEnchant(Enchantment.EFFICIENCY)) {
                speedFactor += (tool.getItemMeta().getEnchantLevel(Enchantment.EFFICIENCY) * 0.4);
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

    // --- TÍNH NĂNG 6: HÀNG ĐỢI XÓA BLOCK REMOVE QUEUE ---
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!getConfig().getBoolean("block-remove.enabled", true)) return;
        Player player = event.getPlayer();
        if (player.hasPermission("schoolmine.breakblock") && !getConfig().getBoolean("schoolmine.breakblock", true)) return;

        List<String> worlds = getConfig().getStringList("block-remove.world-whitelist");
        if (!worlds.isEmpty() && !worlds.contains(event.getBlock().getWorld().getName())) return;

        if (blockRemoveQueue.size() >= getConfig().getInt("block-remove.max-queue-size", 100000)) return;

        long delay = getConfig().getLong("block-remove.delay-seconds", 120) * 1000L;
        blockRemoveQueue.put(event.getBlock().getLocation(), System.currentTimeMillis() + delay);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        updatePlayerBossBar(event.getPlayer(), System.currentTimeMillis());
    }

    private void updatePlayerBossBar(Player player, long now) {
        UUID uuid = player.getUniqueId();
        long serverRemaining = serverBoosters.getOrDefault("all", 0L) - now;
        long playerRemaining = (playerBoosters.containsKey(uuid)) ? playerBoosters.get(uuid).getOrDefault("all", 0L) - now : 0L;

        long highestRemaining = Math.max(serverRemaining, playerRemaining);

        if (highestRemaining > 0) {
            String title = "§6§l⚡ BOOSTER ĐANG HOẠT ĐỘNG: §e" + (highestRemaining / 1000L) + "s còn lại";
            if (!playerBossBars.containsKey(uuid)) {
                BossBar bar = Bukkit.createBossBar(title, BarColor.GOLD, BarStyle.SOLID);
                bar.addPlayer(player);
                playerBossBars.put(uuid, bar);
            } else {
                playerBossBars.get(uuid).setTitle(title);
            }
        } else {
            if (playerBossBars.containsKey(uuid)) {
                playerBossBars.get(uuid).removeAll();
                playerBossBars.remove(uuid);
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
        if (!queueStorage.contains("queue") || queueStorage.getConfigurationSection("queue") == null) return;
        for (String key : queueStorage.getConfigurationSection("queue").getKeys(false)) {
            String path = "queue." + key;
            String wName = queueStorage.getString(path + ".world");
            if (wName == null || Bukkit.getWorld(wName) == null) continue;
            World w = Bukkit.getWorld(wName);
            int x = queueStorage.getInt(path + ".x");
            int y = queueStorage.getInt(path + ".y");
            int z = queueStorage.getInt(path + ".z");
            long t = queueStorage.getLong(path + ".time");
            blockRemoveQueue.put(new Location(w, x, y, z), t);
        }
    }
}

// --- BỘ ĐIỀU KHIỂN COMMANDS CHUYÊN BIỆT ---
class MineCommands implements CommandExecutor {
    private final SchoolMine plugin;
    public MineCommands(SchoolMine plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("schoolmine")) {
            if (!sender.hasPermission("schoolmine.admin")) {
                sender.sendMessage(plugin.getMsg("no-permission")); return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                plugin.reloadConfig(); plugin.loadBoosterConfig();
                sender.sendMessage(plugin.getMsg("reload-success")); return true;
            } else if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("§6=== Trạng thái SchoolMine ===");
                sender.sendMessage("§eHàng chờ BlockRemove: §f" + plugin.blockRemoveQueue.size());
                sender.sendMessage("§eNgười chơi AutoPickup: §f" + plugin.autoPickupUsers.size());
                return true;
            }
            sender.sendMessage("§eSử dụng: /schoolmine [reload|status]");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cChỉ người chơi trong game mới thực hiện được!"); return true;
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
            if (args.length > 0 && args[0].equalsIgnoreCase("clearall")) {
                if (!player.hasPermission("schoolmine.booster.admin")) {
                    player.sendMessage(plugin.getMsg("no-permission")); return true;
                }
                plugin.serverBoosters.clear(); plugin.playerBoosters.clear();
                player.sendMessage("§aĐã xóa toàn bộ các Booster đang chạy.");
                return true;
            }
            // Mở giả lập tiêu thụ PlayerPoints mua gói Booster đồng bộ
            if (plugin.getPlayerPointsAPI() != null) {
                int points = plugin.getPlayerPointsAPI().look(player.getUniqueId());
                player.sendMessage("§a[PlayerPoints] Số dư của bạn: §e" + points + " Points");
            }
            player.sendMessage(plugin.getBoosterConfig().getString("gui.main-title", "&8&l⚡ Booster Menu").replace("&", "§"));
            return true;
        }
        return false;
    }
}
