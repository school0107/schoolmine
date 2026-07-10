package com.schoolmine;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.UUID;

/**
 * CLASS CHÍNH CỦA PLUGIN (Trùng tên với file)
 */
public final class SchoolMine extends JavaPlugin {
    // Lưu trữ trạng thái toggle của từng người chơi 
    public final HashMap<UUID, Boolean> autoPickupUsers = new HashMap<>();
    public final HashMap<UUID, Boolean> mining3x3Users = new HashMap<>();
    public final HashMap<UUID, Boolean> autoSmeltUsers = new HashMap<>();
    public final HashMap<UUID, Boolean> autoMineUsers = new HashMap<>();

    @Override
    public void onEnable() {
        // Tạo file config.yml mặc định nếu chưa có [cite: 14]
        saveDefaultConfig();
        
        // Đăng ký Event xử lý đào block [cite: 14]
        getServer().getPluginManager().registerEvents(new MiningListener(this), this);
        
        // Đăng ký lệnh điều khiển 
        MineCommands commands = new MineCommands(this);
        if (getCommand("autopickup") != null) getCommand("autopickup").setExecutor(commands);
        if (getCommand("autosmelt") != null) getCommand("autosmelt").setExecutor(commands);
    }

    @Override
    public void onDisable() {
        // Xử lý khi tắt server (Lưu data nếu cần) 
    }
}

/**
 * CLASS XỬ LÝ SỰ KIỆN ĐÀO BLOCK (Auto Pickup, Auto Smelt, Multiplier)
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
        
        // Tránh lỗi vòng lặp vô hạn [cite: 3]
        if (event.isCancelled()) return;

        // 1. Kiểm tra trạng thái tính năng của người chơi [cite: 1, 3, 5]
        boolean usePickup = plugin.autoPickupUsers.getOrDefault(player.getUniqueId(), false);
        boolean useSmelt = plugin.autoSmeltUsers.getOrDefault(player.getUniqueId(), false);
        double multiplier = getDropMultiplier(player); [cite: 5]

        // Nếu một trong các tính năng nâng cao được bật, ta tự xử lý drop [cite: 2, 4, 5]
        if (usePickup || useSmelt || multiplier > 1.0) {
            event.setDropItems(false); // Chặn game drop item vật lý ra đất [cite: 2, 4]
            
            // Lấy danh sách item lẽ ra sẽ drop dựa trên tool đang cầm [cite: 3]
            Collection<ItemStack> drops = block.getDrops(player.getInventory().getItemInMainHand());
            
            for (ItemStack drop : drops) {
                ItemStack finalDrop = drop.clone();
                
                // Thực hiện tự động nung (Auto Smelt) [cite: 3, 4]
                if (useSmelt) {
                    finalDrop = smeltItem(finalDrop);
                }
                
                // Thực hiện nhân hệ số drop (Drop Multiplier) [cite: 5]
                finalDrop.setAmount((int) (finalDrop.getAmount() * multiplier));

                // Thực hiện tự động nhặt (Auto Pickup) [cite: 2, 3, 5]
                if (usePickup) {
                    // Thêm thẳng vào kho đồ, nếu kho đầy (addItem trả về vật phẩm thừa) -> drop tại chỗ [cite: 2]
                    if (!player.getInventory().addItem(finalDrop).isEmpty()) {
                        block.getWorld().dropItemNaturally(block.getLocation(), finalDrop);
                    }
                } else {
                    // Nếu không bật pickup thì drop vật phẩm đã xử lý ra đất tại vị trí block [cite: 2, 5]
                    block.getWorld().dropItemNaturally(block.getLocation(), finalDrop);
                }
            }
            
            // Tự động thu nạp kinh nghiệm (XP) thẳng vào người [cite: 2]
            player.giveExp(event.getExpToDrop());
            event.setExpToDrop(0);
        }
    }

    // Lấy hệ số nhân từ Permission của LuckPerms [cite: 5, 6]
    private double getDropMultiplier(Player player) {
        for (double i = 5.0; i >= 1.2; i -= 0.2) {
            if (player.hasPermission("mine.x" + i)) return i; [cite: 5, 15]
        }
        return 1.0;
    }

    // Định nghĩa công thức nung quặng (Auto Smelt Mapping) [cite: 4]
    private ItemStack smeltItem(ItemStack item) {
        switch (item.getType()) {
            case RAW_IRON -> item.setType(Material.IRON_INGOT); [cite: 4]
            case RAW_GOLD -> item.setType(Material.GOLD_INGOT); [cite: 4]
            case RAW_COPPER -> item.setType(Material.COPPER_INGOT); [cite: 4]
            case COBBLESTONE -> item.setType(Material.STONE); [cite: 4]
            case SAND -> item.setType(Material.GLASS); [cite: 4]
            default -> {}
        }
        return item;
    }
}

/**
 * CLASS XỬ LÝ CÁC LỆNH /autopickup VÀ /autosmelt
 */
class MineCommands implements CommandExecutor {
    private final SchoolMine plugin;

    public MineCommands(SchoolMine plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cChỉ người chơi mới có thể sử dụng lệnh này!");
            return true;
        }

        // Xử lý lệnh /autopickup [cite: 1, 2, 15]
        if (cmd.getName().equalsIgnoreCase("autopickup")) {
            if (!player.hasPermission("schoolmine.autopickup")) { [cite: 2, 15]
                player.sendMessage("§cBạn không có quyền sử dụng lệnh này!"); [cite: 15]
                return true;
            }
            boolean current = plugin.autoPickupUsers.getOrDefault(player.getUniqueId(), false); [cite: 2]
            plugin.autoPickupUsers.put(player.getUniqueId(), !current); [cite: 2]
            player.sendMessage("§aAutoPickup: " + (!current ? "§b§lBẬT" : "§c§lTẮT")); [cite: 2]
            return true;
        }

        // Xử lý lệnh /autosmelt [cite: 3, 4, 15]
        if (cmd.getName().equalsIgnoreCase("autosmelt")) {
            if (!player.hasPermission("schoolmine.autosmelt")) { [cite: 4, 15]
                player.sendMessage("§cBạn không có quyền sử dụng lệnh này!"); [cite: 15]
                return true;
            }
            boolean current = plugin.autoSmeltUsers.getOrDefault(player.getUniqueId(), false); [cite: 4]
            plugin.autoSmeltUsers.put(player.getUniqueId(), !current); [cite: 4]
            player.sendMessage("§aAutoSmelt: " + (!current ? "§b§lBẬT" : "§c§lTẮT")); [cite: 4]
            return true;
        }

        return false;
    }
}
