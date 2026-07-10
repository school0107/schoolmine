package com.schoolmine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class MineListener implements Listener {
    private final SchoolMine plugin;
    private boolean internal3x3LoopGuard = false;

    public MineListener(SchoolMine plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (internal3x3LoopGuard) return;

        Player player = event.getPlayer();
        Block block = event.getBlock();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        boolean b3x3 = plugin.serverBoosters.getOrDefault("3x3", 0L) > now || plugin.serverBoosters.getOrDefault("all", 0L) > now ||
                (plugin.playerBoosters.containsKey(uuid) && (plugin.playerBoosters.get(uuid).getOrDefault("3x3", 0L) > now || plugin.playerBoosters.get(uuid).getOrDefault("all", 0L) > now));

        boolean is3x3 = plugin.mining3x3Users.getOrDefault(uuid, plugin.getConfig().getBoolean("3x3-mining.default-enabled", false)) || b3x3;
        boolean isPickup = plugin.autoPickupUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-pickup.default-enabled", true));
        boolean isSmelt = plugin.autoSmeltUsers.getOrDefault(uuid, plugin.getConfig().getBoolean("auto-smelt.default-enabled", true));

        List<String> whitelist3x3 = plugin.getConfig().getStringList("3x3-mining.whitelist");

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

        List<String> pickupBlacklist = plugin.getConfig().getStringList("auto-pickup.blacklist");
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
                if (plugin.getConfig().contains(path)) {
                    Material mat = Material.getMaterial(plugin.getConfig().getString(path, ""));
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
        boolean multiBoost = plugin.serverBoosters.getOrDefault("multi", 0L) > now || plugin.serverBoosters.getOrDefault("all", 0L) > now ||
                (plugin.playerBoosters.containsKey(player.getUniqueId()) && (plugin.playerBoosters.get(player.getUniqueId()).getOrDefault("multi", 0L) > now || plugin.playerBoosters.get(player.getUniqueId()).getOrDefault("all", 0L) > now));

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

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        boolean hasNukerBoost = plugin.serverBoosters.getOrDefault("nuker", 0L) > now || plugin.serverBoosters.getOrDefault("all", 0L) > now ||
                (plugin.playerBoosters.containsKey(uuid) && (plugin.playerBoosters.get(uuid).getOrDefault("nuker", 0L) > now || plugin.playerBoosters.get(uuid).getOrDefault("all", 0L) > now));

        if (!plugin.autoMineUsers.getOrDefault(uuid, false) && !hasNukerBoost) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.getType().name().contains("PICKAXE")) return;

        int interval = plugin.getBoosterConfig().getInt("nuker.check-interval-ticks", 15);
        if (player.getTicksLived() % interval != 0) return;

        int radius = plugin.getConfig().getInt("auto-mine.default-radius", 2);
        for (int i = 6; i >= 1; i--) {
            if (player.hasPermission("automine." + i)) { radius = i; break; }
        }

        Location loc = player.getLocation();
        List<String> whitelist = plugin.getBoosterConfig().getStringList("nuker.whitelist");
        int maxBlocks = plugin.getBoosterConfig().getInt("nuker.max-blocks-per-check", 25);
        int broken = 0;

        double speedMult = 1.0;
        if (tool.getType().name().contains("DIAMOND")) speedMult = 2.5;
        else if (tool.getType().name().contains("NETHERITE")) speedMult = 3.0;
        if (tool.hasItemMeta() && tool.getItemMeta().hasEnchant(Enchantment.EFFICIENCY)) {
            speedMult += (tool.getItemMeta().getEnchantLevel(Enchantment.EFFICIENCY) * 0.4);
        }

        for (int x = -radius; x <= radius && broken < maxBlocks * speedMult; x++) {
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

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("block-remove.enabled", true)) return;
        Player p = event.getPlayer();
        if (p.hasPermission("schoolmine.breakblock") && !plugin.getConfig().getBoolean("schoolmine.breakblock", true)) return;

        List<String> worlds = plugin.getConfig().getStringList("block-remove.world-whitelist");
        if (!worlds.isEmpty() && !worlds.contains(event.getBlock().getWorld().getName())) return;
        if (plugin.blockRemoveQueue.size() >= plugin.getConfig().getInt("block-remove.max-queue-size", 100000)) return;

        long delay = plugin.getConfig().getLong("block-remove.delay-seconds", 120) * 1000L;
        plugin.blockRemoveQueue.put(event.getBlock().getLocation(), System.currentTimeMillis() + delay);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        long now = System.currentTimeMillis();
        boolean hasShardBoost = plugin.serverBoosters.getOrDefault("killshard", 0L) > now || plugin.serverBoosters.getOrDefault("all", 0L) > now ||
                (plugin.playerBoosters.containsKey(killer.getUniqueId()) && (plugin.playerBoosters.get(killer.getUniqueId()).getOrDefault("killshard", 0L) > now || plugin.playerBoosters.get(killer.getUniqueId()).getOrDefault("all", 0L) > now));

        if (hasShardBoost) {
            List<String> cmds = plugin.getBoosterConfig().getStringList("kill-shard.commands");
            for (String c : cmds) {
                String dynamicCmd = c.replace("{player}", killer.getName()).replace("{victim}", victim.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), dynamicCmd);
            }
            killer.sendMessage(plugin.getBoosterConfig().getString("kill-shard.message", "").replace("&", "§").replace("{victim}", victim.getName()));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.loadPlayerData(event.getPlayer().getUniqueId());
        plugin.updatePlayerBossBar(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.savePlayerData(uuid);
        plugin.clearBossBar(uuid);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = plugin.getBoosterConfig().getString("gui.main-title", "&8&l⚡ Booster Menu").replace("&", "§");
        if (!event.getView().getTitle().equals(title)) return;
        event.setCancelled(true);

        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
        Player p = (Player) event.getWhoClicked();

        if (event.getRawSlot() == 11) {
            p.closeInventory();
            int cost = 5;
            
            if (plugin.getPlayerPointsAPI() != null && plugin.getPlayerPointsAPI().take(p.getUniqueId(), cost)) {
                long currentExpiry = plugin.playerBoosters.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).getOrDefault("all", System.currentTimeMillis());
                if (currentExpiry < System.currentTimeMillis()) currentExpiry = System.currentTimeMillis();
                
                plugin.playerBoosters.get(p.getUniqueId()).put("all", currentExpiry + (5 * 60 * 1000L));
                p.sendMessage("§a⚡ Mua thành công Booster 3x3 Personal thời hạn 5 phút!");
            } else {
                p.sendMessage("§cBạn không có đủ số dư PlayerPoints để giao dịch!");
            }
        }
    }
}
