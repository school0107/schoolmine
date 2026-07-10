package com.schoolmine;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class MineCommands implements CommandExecutor {
    private final SchoolMine plugin;

    public MineCommands(SchoolMine plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("schoolmine")) {
            if (!sender.hasPermission("schoolmine.admin")) { sender.sendMessage(plugin.getMsg("no-permission")); return true; }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                plugin.reloadConfig(); plugin.loadBoosterConfig();
                sender.sendMessage(plugin.getMsg("reload-success")); return true;
            } else if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("§6=== SchoolMine Status ===");
                sender.sendMessage("§eQueue BlockRemove: §f" + plugin.blockRemoveQueue.size());
                sender.sendMessage("§eActive Server Boosters: §f" + plugin.serverBoosters.size());
                return true;
            }
            sender.sendMessage("§eSử dụng: /schoolmine [reload|status]");
            return true;
        }

        if (!(sender instanceof Player player)) { sender.sendMessage("§cChỉ áp dụng trong game!"); return true; }
        UUID uuid = player.getUniqueId();

        switch (cmd.getName().toLowerCase()) {
            case "autopickup":
                boolean pickup = !plugin.autoPickupUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-pickup.default-enabled", true));
                plugin.autoPickupUsers.put(uuid, pickup);
                player.sendMessage(plugin.getMsg(pickup ? "autopickup-on" : "autopickup-off"));
                return true;
            case "autosmelt":
                boolean smelt = !plugin.autoSmeltUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-smelt.default-enabled", true));
                plugin.autoSmeltUsers.put(uuid, smelt);
                player.sendMessage(plugin.getMsg(smelt ? "autosmelt-on" : "autosmelt-off"));
                return true;
            case "3x3":
                boolean m3x3 = !plugin.mining3x3Users.getOrDefault(uuid, false);
                plugin.mining3x3Users.put(uuid, m3x3);
                player.sendMessage(plugin.getMsg(m3x3 ? "mining3x3-on" : "mining3x3-off"));
                return true;
            case "automine":
                boolean mine = !plugin.autoMineUsers.getOrDefault(uuid, false);
                plugin.autoMineUsers.put(uuid, mine);
                player.sendMessage(plugin.getMsg(mine ? "automine-on" : "automine-off"));
                return true;
            case "booster":
                if (args.length > 0 && args[0].equalsIgnoreCase("clearall")) {
                    if (!player.hasPermission("schoolmine.booster.admin")) { player.sendMessage(plugin.getMsg("no-permission")); return true; }
                    plugin.serverBoosters.clear(); plugin.playerBoosters.clear();
                    player.sendMessage("§aĐã dọn dẹp sạch toàn bộ Booster trên hệ thống.");
                    return true;
                }
                openBoosterMenu(player);
                return true;
        }
        return false;
    }

    private void openBoosterMenu(Player player) {
        String title = plugin.getBoosterConfig().getString("gui.main-title", "&8&l⚡ Booster Menu").replace("&", "§");
        Inventory inv = Bukkit.createInventory(null, 27, title);

        ItemStack pane = new ItemStack(Material.valueOf(plugin.getBoosterConfig().getString("gui.border-material", "GRAY_STAINED_GLASS_PANE")));
        ItemMeta paneMeta = pane.getItemMeta(); if(paneMeta != null) { paneMeta.setDisplayName(" "); pane.setItemMeta(paneMeta); }
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);

        ItemStack balance = new ItemStack(Material.SUNFLOWER);
        ItemMeta bMeta = balance.getItemMeta();
        if (bMeta != null) {
            bMeta.setDisplayName(plugin.getBoosterConfig().getString("gui.balance-item.name", "").replace("&", "§"));
            int pts = (plugin.getPlayerPointsAPI() != null) ? plugin.getPlayerPointsAPI().look(player.getUniqueId()) : 0;
            List<String> lore = new ArrayList<>();
            for(String s : plugin.getBoosterConfig().getStringList("gui.balance-item.lore")) lore.add(s.replace("{balance}", String.valueOf(pts)).replace("&", "§"));
            bMeta.setLore(lore); balance.setItemMeta(bMeta);
        }
        inv.setItem(4, balance);

        ItemStack bItem = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta iMeta = bItem.getItemMeta();
        if (iMeta != null) {
            iMeta.setDisplayName(plugin.getBoosterConfig().getString("gui.mining-3x3-personal.name", "").replace("&", "§"));
            iMeta.setLore(Collections.singletonList("§eClick để kích hoạt nhanh gói 5 phút (Gói mẫu)"));
            bItem.setItemMeta(iMeta);
        }
        inv.setItem(11, bItem);

        player.openInventory(inv);
    }
}
