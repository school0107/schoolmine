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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
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

public final class SchoolMine extends JavaPlugin implements Listener, CommandExecutor {

    // --- LƯU TRỮ TRẠNG THÁI KHAI THÁC ---
    public final Map<UUID, Boolean> autoPickupUsers = new HashMap<>();
    public final Map<UUID, Boolean> autoSmeltUsers = new HashMap<>();
    public final Map<UUID, Boolean> mining3x3Users = new HashMap<>();
    public final Map<UUID, Boolean> autoMineUsers = new HashMap<>();

    // --- QUẢN LÝ HÀNG ĐỢI BLOCK REMOVE ---
    public final Map<Location, Long> blockRemoveQueue = new HashMap<>();
    private File queueFile;
    private FileConfiguration queueStorage;

    // --- HỆ THỐNG BOOSTER & ĐỒNG BỘ ---
    private FileConfiguration boosterConfig;
    private PlayerPointsAPI ppAPI;
    
    // Lưu thời gian hết hạn (miliseconds). Key: "3x3", "multi", "nuker", "killshard", "all"
    public final Map<String, Long> serverBoosters = new HashMap<>();
    public final Map<UUID, Map<String, Long>> playerBoosters = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();

    private static boolean internal3x3LoopGuard = false;

    @Override
    public void onEnable() {
        // Khởi tạo thư mục và tệp cấu hình
        saveDefaultConfig();
        saveResource("booster.yml", false);
        loadBoosterConfig();

        // Tích hợp cứng mềm với PlayerPoints API
        if (getServer().getPluginManager().getPlugin("PlayerPoints") != null) {
            this.ppAPI = PlayerPoints.getInstance().getAPI();
        }

        // Tạo lập hệ thống hàng chờ tệp tin lưu trữ Block Remove
        queueFile = new File(getDataFolder(), "blockremove_queue.yml");
        if (!queueFile.exists()) {
            try { queueFile.createNewFile(); } catch (IOException ignored) {}
        }
        queueStorage = YamlConfiguration.loadConfiguration(queueFile);
        loadBlockQueue();

        // Đăng ký toàn diện sự kiện và các tập lệnh thực thi
        getServer().getPluginManager().registerEvents(this, this);
        String[] commands = {"autopickup", "autosmelt", "3x3", "automine", "booster", "schoolmine"};
        for (String cmd : commands) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(this);
        }

        // Tải dữ liệu toàn bộ người chơi đang online phòng hờ trường hợp reload
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerData(p.getUniqueId());
        }

        // Vòng lặp dọn dẹp khối Block Remove Queue định kỳ
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

        // Vòng lặp cập nhật trạng thái BossBar hiển thị Booster hàng giây
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

    public String getMsg(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&6SchoolMine&8] &r");
        String msg = getConfig().getString("messages." + path, "");
        return (prefix + msg).replace("&", "§");
    }

    // --- TÍNH NĂNG 1, 2, 3, 5: XỬ LÝ KHAI THÁC & AUTO PICKUP / AUTO SMELT / DROP MULTIPLIER ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (internal3x3LoopGuard) return;

        Player player = event.getPlayer();
        Block block = event.getBlock();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Kiểm tra điều kiện Booster thời gian thực
        boolean b3x3 = serverBoosters.getOrDefault("3x3", 0L) > now || serverBoosters.getOrDefault("all", 0L) > now ||
                (playerBoosters.containsKey(uuid) && (playerBoosters.get(uuid).getOrDefault("3x3", 0L) > now || playerBoosters.get(uuid).getOrDefault("all", 0L) > now));

        boolean is3x3 = mining3x3Users.getOrDefault(uuid, getConfig().getBoolean("3x3-mining.default-enabled", false)) || b3x3;
        boolean isPickup = autoPickupUsers.getOrDefault(uuid, getConfig().getBoolean("auto-pickup.default-enabled", true));
        boolean isSmelt = autoSmeltUsers.getOrDefault(uuid, getConfig().getBoolean("auto-smelt.default-enabled", true));

        List<String> whitelist3x3 = getConfig().getStringList("3x3-mining.whitelist");

        // Đào vùng mở rộng 3x3
        if (is3x3 && whitelist3x3.contains(block.getType().name())) {
            event.setCancelled(true);
            internal3x3LoopGuard = true;

            BlockFace face = getTargetBlockFace(player);
            Location center = block.getLocation();

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        int tx = center.getBlockX(), ty = center.getBlockY(), tz = center.getBlockZ();
                        if (face.getModY() != 0) {
                            tx += x; tz += z;
                        } else if (face.getModX() != 0) {
                            ty += y; tz += z;
                        } else {
                            tx += x; ty += y;
                        }

                        Block target = block.getWorld().getBlockAt(tx, ty, tz);
                        if (whitelist3x3.contains(target.getType().name()) && target.getType() != Material.AIR) {
                            BlockBreakEvent subEvent = new BlockBreakEvent(target, player);
                            Bukkit.getPluginManager().callEvent(subEvent);
                            if (!subEvent.isCancelled()) {
                                rewardBlockItems(player, target, isPickup, isSmelt, now);
                                target.setType(Material.AIR);
                            }
                        }
                    }
                }
            }
            internal3x3LoopGuard = false;
            return;
        }

        // Phá block đơn thường lẻ
        List<String> pickupBlacklist = getConfig().getStringList("auto-pickup.blacklist");
        if (pickupBlacklist.contains(block.getType().name())) return;

        event.setDropItems(false);
        rewardBlockItems(player, block, isPickup, isSmelt, now);
        player.giveExp(event.getExpToDrop());
        event.setExpToDrop(0);
    }

    private void rewardBlockItems(Player player, Block b, boolean pickup, boolean smelt, long now) {
        Collection<ItemStack> drops = b.getDrops(player.getInventory().getItemInMainHand());
        double mult = calculateDropMultiplier(player, now);

        for (ItemStack drop : drops) {
            ItemStack finalDrop = drop.clone();
            if (smelt) {
                String path = "auto-smelt.smelt-map." + finalDrop.getType().name();
                if (getConfig().contains(path)) {
                    Material mat = Material.getMaterial(getConfig().getString(path, ""));
                    if (mat != null) finalDrop.setType(mat);
                }
            }
            finalDrop.setAmount((int) (finalDrop.getAmount() * mult));

            if (pickup) {
                if (!player.getInventory().addItem(finalDrop).isEmpty()) {
                    b.getWorld().dropItemNaturally(b.getLocation(), finalDrop);
                }
            } else {
                b.getWorld().dropItemNaturally(b.getLocation(), finalDrop);
            }
        }
    }

    private double calculateDropMultiplier(Player player, long now) {
        boolean multiBoost = serverBoosters.getOrDefault("multi", 0L) > now || serverBoosters.getOrDefault("all", 0L) > now ||
                (playerBoosters.containsKey(player.getUniqueId()) && (playerBoosters.get(player.getUniqueId()).getOrDefault("multi", 0L) > now || playerBoosters.get(player.getUniqueId()).getOrDefault("all", 0L) > now));

        double base = 1.0;
        for (double i = 5.0; i >= 1.2; i -= 0.2) {
            String node = String.format(Locale.US, "mine.x%.1f", i).replace(".0", "");
            if (player.hasPermission(node)) { base = i; break; }
        }
        return multiBoost ? base * 2.0 : base;
    }

    private BlockFace getTargetBlockFace(Player player) {
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

    // --- TÍNH NĂNG 4 & 6: AUTO MINE RADIUS & NUKER MOVEMENT ---
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        boolean hasNukerBoost = serverBoosters.getOrDefault("nuker", 0L) > now || serverBoosters.getOrDefault("all", 0L) > now ||
                (playerBoosters.containsKey(uuid) && (playerBoosters.get(uuid).getOrDefault("nuker", 0L) > now || playerBoosters.get(uuid).getOrDefault("all", 0L) > now));

        if (!autoMineUsers.getOrDefault(uuid, false) && !hasNukerBoost) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.getType().name().contains("PICKAXE")) return;

        int interval = boosterConfig.getInt("nuker.check-interval-ticks", 15);
        if (player.getTicksLived() % interval != 0) return;

        int radius = getConfig().getInt("auto-mine.default-radius", 2);
        for (int i = 6; i >= 1; i--) {
            if (player.hasPermission("automine." + i)) { radius = i; break; }
        }

        Location loc = player.getLocation();
        List<String> whitelist = boosterConfig.getStringList("nuker.whitelist");
        int maxBlocks = boosterConfig.getInt("nuker.max-blocks-per-check", 25);
        int broken = 0;

        double speedMult = 1.0;
        if (tool.getType().name().contains("DIAMOND")) speedMult = 2.5;
        else if (tool.getType().name().contains("NETHERITE")) speedMult = 3.0;
        if (tool.hasItemMeta() && tool.getItemMeta().hasEnchant(Enchantment.EFFICIENCY)) {
            speedMult += (tool.getItemMeta().getEnchantLevel(Enchantment.EFFICIENCY) * 0.4);
        }

        for (int x = -radius; x <= radius && broken < maxBlocks * speedMult; x++) {
            for (int y = 0; y <= radius; y++) { // Chỉ quét từ ngang chân trở lên
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

    // --- TÍNH NĂNG 7: BLOCK REMOVE PLACEMENT QUEUE ---
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!getConfig().getBoolean("block-remove.enabled", true)) return;
        Player p = event.getPlayer();
        if (p.hasPermission("schoolmine.breakblock") && !getConfig().getBoolean("schoolmine.breakblock", true)) return;

        List<String> worlds = getConfig().getStringList("block-remove.world-whitelist");
        if (!worlds.isEmpty() && !worlds.contains(event.getBlock().getWorld().getName())) return;
        if (blockRemoveQueue.size() >= getConfig().getInt("block-remove.max-queue-size", 100000)) return;

        long delay = getConfig().getLong("block-remove.delay-seconds", 120) * 1000L;
        blockRemoveQueue.put(event.getBlock().getLocation(), System.currentTimeMillis() + delay);
    }

    // --- TÍNH NĂNG 8.4: KILL SHARD BOOSTER REWARD ---
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        long now = System.currentTimeMillis();
        boolean hasShardBoost = serverBoosters.getOrDefault("killshard", 0L) > now || serverBoosters.getOrDefault("all", 0L) > now ||
                (playerBoosters.containsKey(killer.getUniqueId()) && (playerBoosters.get(killer.getUniqueId()).getOrDefault("killshard", 0L) > now || playerBoosters.get(killer.getUniqueId()).getOrDefault("all", 0L) > now));

        if (hasShardBoost) {
            List<String> cmds = boosterConfig.getStringList("kill-shard.commands");
            for (String c : cmds) {
                String dynamicCmd = c.replace("{player}", killer.getName()).replace("{victim}", victim.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), dynamicCmd);
            }
            killer.sendMessage(boosterConfig.getString("kill-shard.message", "").replace("&", "§").replace("{victim}", victim.getName()));
        }
    }

    // --- HỆ THỐNG ĐỒNG BỘ DỮ LIỆU & BOSSBAR ---
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        loadPlayerData(event.getPlayer().getUniqueId());
        updatePlayerBossBar(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        savePlayerData(uuid);
        if (playerBossBars.containsKey(uuid)) {
            playerBossBars.get(uuid).removeAll();
            playerBossBars.remove(uuid);
        }
    }

    private void updatePlayerBossBar(Player p, long now) {
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

    private void loadPlayerData(UUID uuid) {
        File f = new File(getDataFolder(), "playerdata/" + uuid + ".yml");
        if (!f.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        autoPickupUsers.put(uuid, cfg.getBoolean("autopickup", true));
        autoSmeltUsers.put(uuid, cfg.getBoolean("autosmelt", true));
        mining3x3Users.put(uuid, cfg.getBoolean("mining3x3", false));
        autoMineUsers.put(uuid, cfg.getBoolean("automine", false));
    }

    private void savePlayerData(UUID uuid) {
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

    // --- BỘ ĐIỀU HƯỚNG MỆNH LỆNH (COMMAND EXECUTOR) ---
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("schoolmine")) {
            if (!sender.hasPermission("schoolmine.admin")) { sender.sendMessage(getMsg("no-permission")); return true; }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig(); loadBoosterConfig();
                sender.sendMessage(getMsg("reload-success")); return true;
            } else if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("§6=== SchoolMine Status ===");
                sender.sendMessage("§eQueue BlockRemove: §f" + blockRemoveQueue.size());
                sender.sendMessage("§eActive Server Boosters: §f" + serverBoosters.size());
                return true;
            }
            sender.sendMessage("§eSử dụng: /schoolmine [reload|status]");
            return true;
        }

        if (!(sender instanceof Player player)) { sender.sendMessage("§cChỉ áp dụng trong game!"); return true; }
        UUID uuid = player.getUniqueId();

        switch (cmd.getName().toLowerCase()) {
            case "autopickup":
                boolean pickup = !autoPickupUsers.getOrDefault(uuid, getConfig().getBoolean("auto-pickup.default-enabled", true));
                autoPickupUsers.put(uuid, pickup);
                player.sendMessage(getMsg(pickup ? "autopickup-on" : "autopickup-off"));
                return true;
            case "autosmelt":
                boolean smelt = !autoSmeltUsers.getOrDefault(uuid, getConfig().getBoolean("auto-smelt.default-enabled", true));
                autoSmeltUsers.put(uuid, smelt);
                player.sendMessage(getMsg(smelt ? "autosmelt-on" : "autosmelt-off"));
                return true;
            case "3x3":
                boolean m3x3 = !mining3x3Users.getOrDefault(uuid, false);
                mining3x3Users.put(uuid, m3x3);
                player.sendMessage(getMsg(m3x3 ? "mining3x3-on" : "mining3x3-off"));
                return true;
            case "automine":
                boolean mine = !autoMineUsers.getOrDefault(uuid, false);
                autoMineUsers.put(uuid, mine);
                player.sendMessage(getMsg(mine ? "automine-on" : "automine-off"));
                return true;
            case "booster":
                if (args.length > 0 && args[0].equalsIgnoreCase("clearall")) {
                    if (!player.hasPermission("schoolmine.booster.admin")) { player.sendMessage(getMsg("no-permission")); return true; }
                    serverBoosters.clear(); playerBoosters.clear();
                    player.sendMessage("§aĐã dọn dẹp sạch toàn bộ Booster trên hệ thống.");
                    return true;
                }
                openBoosterMenu(player);
                return true;
        }
        return false;
    }

    // --- GIAO DIỆN GUI BOOSTER MUA BẰNG PLAYERPOINTS ---
    private void openBoosterMenu(Player player) {
        String title = boosterConfig.getString("gui.main-title", "&8&l⚡ Booster Menu").replace("&", "§");
        Inventory inv = Bukkit.createInventory(null, 27, title);

        // Fill background border panes
        ItemStack pane = new ItemStack(Material.valueOf(boosterConfig.getString("gui.border-material", "GRAY_STAINED_GLASS_PANE")));
        ItemMeta paneMeta = pane.getItemMeta(); if(paneMeta != null) { paneMeta.setDisplayName(" "); pane.setItemMeta(paneMeta); }
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);

        // Sunflower Balance Item
        ItemStack balance = new ItemStack(Material.SUNFLOWER);
        ItemMeta bMeta = balance.getItemMeta();
        if (bMeta != null) {
            bMeta.setDisplayName(boosterConfig.getString("gui.balance-item.name", "").replace("&", "§"));
            int pts = (ppAPI != null) ? ppAPI.look(player.getUniqueId()) : 0;
            List<String> lore = new ArrayList<>();
            for(String s : boosterConfig.getStringList("gui.balance-item.lore")) lore.add(s.replace("{balance}", String.valueOf(pts)).replace("&", "§"));
            bMeta.setLore(lore); balance.setItemMeta(bMeta);
        }
        inv.setItem(4, balance);

        // Tạo item tùy chọn mua đại diện: 3x3 Personal Booster
        ItemStack bItem = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta iMeta = bItem.getItemMeta();
        if (iMeta != null) {
            iMeta.setDisplayName(boosterConfig.getString("gui.mining-3x3-personal.name", "").replace("&", "§"));
            iMeta.setLore(Collections.singletonList("§eClick để kích hoạt nhanh gói 5 phút (Gói mẫu)"));
            bItem.setItemMeta(iMeta);
        }
        inv.setItem(11, bItem);

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = boosterConfig.getString("gui.main-title", "&8&l⚡ Booster Menu").replace("&", "§");
        if (!event.getView().getTitle().equals(title)) return;
        event.setCancelled(true);

        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
        Player p = (Player) event.getWhoClicked();

        // Xử lý logic mua thử nghiệm một gói (ví dụ: Slot 11 - 3x3 Personal, giá mặc định 5 Points)
        if (event.getRawSlot() == 11) {
            p.closeInventory(); // Đóng ngay lập tức để chống spam nhấp chuột
            int cost = 5;
            
            if (ppAPI != null && ppAPI.take(p.getUniqueId(), cost)) {
                long currentExpiry = playerBoosters.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).getOrDefault("all", System.currentTimeMillis());
                if (currentExpiry < System.currentTimeMillis()) currentExpiry = System.currentTimeMillis();
                
                playerBoosters.get(p.getUniqueId()).put("all", currentExpiry + (5 * 60 * 1000L)); // Cộng dồn 5 phút vĩnh viễn
                p.sendMessage("§a⚡ Mua thành công Booster 3x3 Personal thời hạn 5 phút!");
            } else {
                p.sendMessage("§cBạn không có đủ số dư PlayerPoints để giao dịch!");
            }
        }
    }
}
