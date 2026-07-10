package com.schoolmine;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

public final class SchoolMine extends JavaPlugin {
    // Trạng thái bật/tắt tính năng của người chơi
    public final HashMap<UUID, Boolean> autoPickupUsers = new HashMap<>();
    public final HashMap<UUID, Boolean> autoSmeltUsers = new HashMap<>();
    public final HashMap<UUID, Boolean> mining3x3Users = new HashMap<>();

    private FileConfiguration boosterConfig;

    @Override
    public void onEnable() {
        // Khởi tạo config.yml và booster.yml mặc định
        saveDefaultConfig();
        saveResource("booster.yml", false);
        loadBoosterConfig();

        // Đăng ký Event & Commands
        getServer().getPluginManager().registerEvents(new MiningListener(this), this);
        
        MineCommands commands = new MineCommands(this);
        if (getCommand("autopickup") != null) getCommand("autopickup").setExecutor(commands);
        if (getCommand("autosmelt") != null) getCommand("autosmelt").setExecutor(commands);
        if (getCommand("3x3") != null) getCommand("3x3").setExecutor(commands);
        if (getCommand("schoolmine") != null) getCommand("schoolmine").setExecutor(commands);
    }

    public void loadBoosterConfig() {
        File boosterFile = new File(getDataFolder(), "booster.yml");
        this.boosterConfig = YamlConfiguration.loadConfiguration(boosterFile);
    }

    public FileConfiguration getBoosterConfig() {
        return this.boosterConfig;
    }

    // Tiện ích lấy tin nhắn dịch từ config.yml có kèm Prefix
    public String getMsg(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&6SchoolMine&8] &r");
        String message = getConfig().getString("messages." + path, "");
        return (prefix + message).replace("&", "§");
    }
}

/**
 * LỚP XỬ LÝ SỰ KIỆN ĐÀO BLOCK CHÍNH XÁC THEO CONFIG.YML ĐÃ TẢI LÊN
 */
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
        // Lấy trạng thái mặc định từ config nếu player chưa thiết lập toggle
        boolean usePickup = plugin.autoPickupUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-pickup.default-enabled", true));
        boolean useSmelt = plugin.autoSmeltUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-smelt.default-enabled", true));
        boolean use3x3 = plugin.mining3x3Users.getOrDefault(uuid, plugin.getConfig().getBoolean("3x3-mining.default-enabled", false));
        
        double multiplier = getDropMultiplier(player);

        // Đọc danh sách blacklist từ file config.yml của bạn
        List<String> pickupBlacklist = plugin.getConfig().getStringList("auto-pickup.blacklist");

        if (usePickup || useSmelt || multiplier > 1.0) {
            // Kiểm tra xem block có nằm trong blacklist không tự nhặt không
            if (pickupBlacklist.contains(block.getType().name())) {
                return; // Nếu nằm trong blacklist, để game xử lý rớt vật phẩm như bình thường
            }

            event.setDropItems(false); // Chặn game drop mặc định
            Collection<ItemStack> drops = block.getDrops(player.getInventory().getItemInMainHand());
            
            for (ItemStack drop : drops) {
                ItemStack finalDrop = drop.clone();
                
                // Thực hiện tự nung theo sơ đồ thiết lập trong `auto-smelt.smelt-map` của bạn
                if (useSmelt) {
                    finalDrop = processAutoSmelt(finalDrop);
                }
                
                // Áp dụng hệ số nhân Drop Multiplier dựa trên Permission của người chơi
                finalDrop.setAmount((int) (finalDrop.getAmount() * multiplier));

                if (usePickup) {
                    // Tự động nhặt vào túi đồ, nếu túi đầy -> rơi ra đất tại chỗ
                    if (!player.getInventory().addItem(finalDrop).isEmpty()) {
                        block.getWorld().dropItemNaturally(block.getLocation(), finalDrop);
                    }
                } else {
                    block.getWorld().dropItemNaturally(block.getLocation(), finalDrop);
                }
            }
            
            // Tự hút Exp vào người chơi
            player.giveExp(event.getExpToDrop());
            event.setExpToDrop(0);
        }

        // Xử lý logic 3x3 Mining nếu được kích hoạt
        if (use3x3) {
            List<String> whitelist3x3 = plugin.getConfig().getStringList("3x3-mining.whitelist");
            boolean requireTool = plugin.getConfig().getBoolean("3x3-mining.require-correct-tool", true);
            
            if (whitelist3x3.contains(block.getType().name())) {
                // To-Do: Viết hàm lấy BlockFace hướng nhìn để phá lan 3x3 quanh block hiện tại
                // nếu requireTool = true, cần kiểm tra player.getInventory().getItemInMainHand()
            }
        }
    }

    // Lấy hệ số nhân chuẩn xác từ Permission người chơi (lp user... mine.xN)
    private double getDropMultiplier(Player player) {
        for (double i = 5.0; i >= 1.2; i -= 0.2) {
            // Định dạng kiểm tra mine.x1.2, mine.x2.0, mine.x5.0 ...
            String perm = String.format(Locale.US, "mine.x%.1f", i).replace(".0", "");
            if (player.hasPermission(perm)) return i;
        }
        return 1.0;
    }

    // Đọc động từ bản đồ `auto-smelt.smelt-map` trong file config.yml của bạn
    private ItemStack processAutoSmelt(ItemStack item) {
        String originalMaterialName = item.getType().name();
        String configPath = "auto-smelt.smelt-map." + originalMaterialName;

        if (plugin.getConfig().contains(configPath)) {
            String targetMaterialName = plugin.getConfig().getString(configPath);
            if (targetMaterialName != null) {
                Material targetMat = Material.getMaterial(targetMaterialName);
                if (targetMat != null) {
                    item.setType(targetMat);
                }
            }
        }
        return item;
    }
}

/**
 * LỚP ĐIỀU KHIỂN LỆNH ĐỒNG BỘ TIN NHẮN TỪ CONFIG.YML CỦA BẠN
 */
class MineCommands implements CommandExecutor {
    private final SchoolMine plugin;

    public MineCommands(SchoolMine plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        // Lệnh Admin hệ thống: /schoolmine reload
        if (cmd.getName().equalsIgnoreCase("schoolmine")) {
            if (!sender.hasPermission("schoolmine.admin") && !sender.hasPermission("schoolmine.reload")) {
                sender.sendMessage(plugin.getMsg("no-permission"));
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                plugin.reloadConfig();
                plugin.loadBoosterConfig();
                sender.sendMessage(plugin.getMsg("reload-success"));
                return true;
            }
            sender.sendMessage("§eSử dụng: /schoolmine reload để nạp lại cấu hình.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cChỉ người chơi mới thực hiện được lệnh này!");
            return true;
        }

        UUID uuid = player.getUniqueId();

        // Xử lý lệnh /autopickup
        if (cmd.getName().equalsIgnoreCase("autopickup")) {
            if (!player.hasPermission("schoolmine.autopickup")) {
                player.sendMessage(plugin.getMsg("no-permission"));
                return true;
            }
            boolean current = plugin.autoPickupUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-pickup.default-enabled", true));
            plugin.autoPickupUsers.put(uuid, !current);
            player.sendMessage(plugin.getMsg(!current ? "autopickup-on" : "autopickup-off"));
            return true;
        }

        // Xử lý lệnh /autosmelt
        if (cmd.getName().equalsIgnoreCase("autosmelt")) {
            if (!player.hasPermission("schoolmine.autosmelt")) {
                player.sendMessage(plugin.getMsg("no-permission"));
                return true;
            }
            boolean current = plugin.autoSmeltUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-smelt.default-enabled", true));
            plugin.autoSmeltUsers.put(uuid, !current);
            player.sendMessage(plugin.getMsg(!current ? "autosmelt-on" : "autosmelt-off"));
            return true;
        }

        // Xử lý lệnh /3x3
        if (cmd.getName().equalsIgnoreCase("3x3")) {
            if (!player.hasPermission("schoolmine.3x3")) {
                player.sendMessage(plugin.getMsg("no-permission"));
                return true;
            }
            boolean current = plugin.mining3x3Users.getOrDefault(uuid, plugin.getConfig().getBoolean("3x3-mining.default-enabled", false));
            plugin.mining3x3Users.put(uuid, !current);
            player.sendMessage(plugin.getMsg(!current ? "mining3x3-on" : "mining3x3-off"));
            return true;
        }

        return false;
    }
}
