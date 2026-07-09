package dev.schoolmine.schoolmine;

import java.io.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;


// ═══════════════════════════════════════
// PlayerData.java
// ═══════════════════════════════════════
class PlayerData {
    private final UUID uuid;
    private boolean autoPickup, mining3x3, autoSmelt, autoMine;
    public PlayerData(UUID uuid, boolean autoPickup, boolean mining3x3, boolean autoSmelt, boolean autoMine) {
        this.uuid=uuid; this.autoPickup=autoPickup; this.mining3x3=mining3x3; this.autoSmelt=autoSmelt; this.autoMine=autoMine;
    }
    public UUID getUuid(){return uuid;}
    public boolean isAutoPickup(){return autoPickup;} public void setAutoPickup(boolean v){autoPickup=v;}
    public boolean isMining3x3(){return mining3x3;} public void setMining3x3(boolean v){mining3x3=v;}
    public boolean isAutoSmelt(){return autoSmelt;} public void setAutoSmelt(boolean v){autoSmelt=v;}
    public boolean isAutoMine(){return autoMine;} public void setAutoMine(boolean v){autoMine=v;}
}


// ═══════════════════════════════════════
// ConfigManager.java
// ═══════════════════════════════════════
class ConfigManager {
    private final SchoolMine plugin;
    private boolean autopickupDefaultEnabled; private Set<Material> autopickupBlacklist;
    private boolean mining3x3DefaultEnabled; private Set<Material> mining3x3Whitelist; private boolean mining3x3RequireCorrectTool; private int mining3x3Radius;
    private boolean autosmeltDefaultEnabled; private Map<Material,Material> smeltMap;
    private int autoSaveInterval; private int max3x3Blocks;
    private String prefix; private Map<String,String> messages;
    public ConfigManager(SchoolMine p){plugin=p;load();}
    public void reload(){load();}
    private void load(){
        FileConfiguration cfg=plugin.getConfig();
        autopickupDefaultEnabled=cfg.getBoolean("auto-pickup.default-enabled",true);
        autopickupBlacklist=loadMats(cfg,"auto-pickup.blacklist");
        mining3x3DefaultEnabled=cfg.getBoolean("3x3-mining.default-enabled",false);
        mining3x3Whitelist=loadMats(cfg,"3x3-mining.whitelist");
        mining3x3RequireCorrectTool=cfg.getBoolean("3x3-mining.require-correct-tool",true);
        mining3x3Radius=Math.max(1,Math.min(cfg.getInt("3x3-mining.radius",1),3));
        autosmeltDefaultEnabled=cfg.getBoolean("auto-smelt.default-enabled",false);
        smeltMap=new EnumMap<>(Material.class);
        ConfigurationSection ss=cfg.getConfigurationSection("auto-smelt.smelt-map");
        if(ss!=null)for(String k:ss.getKeys(false)){Material f=Material.matchMaterial(k),t=Material.matchMaterial(ss.getString(k,""));if(f!=null&&t!=null)smeltMap.put(f,t);}
        autoSaveInterval=cfg.getInt("performance.auto-save-interval",300);
        max3x3Blocks=Math.max(1,Math.min(cfg.getInt("performance.max-3x3-blocks",27),27));
        messages=new HashMap<>();prefix=cfg.getString("messages.prefix","&8[&6SchoolMine&8] &r");
        ConfigurationSection ms=cfg.getConfigurationSection("messages");
        if(ms!=null)for(String k:ms.getKeys(false))messages.put(k,ms.getString(k,""));
    }
    private Set<Material> loadMats(FileConfiguration cfg,String path){
        Set<Material> s=EnumSet.noneOf(Material.class);
        for(String m:cfg.getStringList(path)){Material mat=Material.matchMaterial(m);if(mat!=null)s.add(mat);}
        return s;
    }
    public String getMessage(String key){return colorize(prefix+messages.getOrDefault(key,"&cMessage not found: "+key));}
    private String colorize(String s){return s==null?"":s.replace("&","\u00A7");}
    public boolean isAutopickupDefaultEnabled(){return autopickupDefaultEnabled;}
    public Set<Material> getAutopickupBlacklist(){return autopickupBlacklist;}
    public boolean isMining3x3DefaultEnabled(){return mining3x3DefaultEnabled;}
    public Set<Material> getMining3x3Whitelist(){return mining3x3Whitelist;}
    public boolean isMining3x3RequireCorrectTool(){return mining3x3RequireCorrectTool;}
    public int getMining3x3Radius(){return mining3x3Radius;}
    public boolean isAutosmeltDefaultEnabled(){return autosmeltDefaultEnabled;}
    public Map<Material,Material> getSmeltMap(){return smeltMap;}
    public Material getSmeltResult(Material m){return smeltMap.get(m);}
    public int getAutoSaveInterval(){return autoSaveInterval;}
    public int getMax3x3Blocks(){return max3x3Blocks;}
}


// ═══════════════════════════════════════
// PlayerDataManager.java
// ═══════════════════════════════════════
class PlayerDataManager {
    private final SchoolMine plugin;
    private final File dataFolder;
    private final ConcurrentHashMap<UUID,PlayerData> cache = new ConcurrentHashMap<>();
    public PlayerDataManager(SchoolMine p){plugin=p;dataFolder=new File(p.getDataFolder(),"playerdata");dataFolder.mkdirs();}
    public PlayerData get(Player p){return cache.computeIfAbsent(p.getUniqueId(),this::load);}
    public PlayerData get(UUID uuid){return cache.computeIfAbsent(uuid,this::load);}
    private PlayerData load(UUID uuid){
        File f=new File(dataFolder,uuid+".yml");
        ConfigManager cm=plugin.getConfigManager();
        if(!f.exists())return new PlayerData(uuid,cm.isAutopickupDefaultEnabled(),cm.isMining3x3DefaultEnabled(),cm.isAutosmeltDefaultEnabled(),false);
        try{YamlConfiguration y=YamlConfiguration.loadConfiguration(f);
            return new PlayerData(uuid,y.getBoolean("autopickup",cm.isAutopickupDefaultEnabled()),y.getBoolean("mining3x3",cm.isMining3x3DefaultEnabled()),y.getBoolean("autosmelt",cm.isAutosmeltDefaultEnabled()),y.getBoolean("automine",false));
        }catch(Exception e){return new PlayerData(uuid,cm.isAutopickupDefaultEnabled(),cm.isMining3x3DefaultEnabled(),cm.isAutosmeltDefaultEnabled(),false);}
    }
    public void save(UUID uuid){
        PlayerData d=cache.get(uuid);if(d==null)return;
        File f=new File(dataFolder,uuid+".yml");f.getParentFile().mkdirs();
        YamlConfiguration y=new YamlConfiguration();
        y.set("autopickup",d.isAutoPickup());y.set("mining3x3",d.isMining3x3());y.set("autosmelt",d.isAutoSmelt());y.set("automine",d.isAutoMine());
        try{y.save(f);}catch(IOException e){plugin.getLogger().warning("Failed to save "+uuid);}
    }
    public void saveAll(){cache.keySet().forEach(this::save);}
    public void clearCache(){cache.clear();}
    public void unload(UUID uuid){save(uuid);cache.remove(uuid);}
}


// ═══════════════════════════════════════
// BlockRemoveManager.java
// ═══════════════════════════════════════
class BlockRemoveManager {
    private final SchoolMine plugin; private final File dataFile;
    private final TreeMap<Long,List<String>> removeQueue=new TreeMap<>();
    private boolean enabled; private long delayMs; private Set<Material> blacklist;
    private Set<String> worldWhitelist; private boolean worldWhitelistEnabled;
    private int maxQueueSize,checkIntervalTicks; private boolean persist;
    private int totalQueued=0,schedulerTaskId=-1;
    public BlockRemoveManager(SchoolMine p){plugin=p;dataFile=new File(p.getDataFolder(),"blockremove_queue.yml");reload();if(persist)loadFromDisk();startScheduler();}
    public void reload(){
        var cfg=plugin.getConfig();
        enabled=cfg.getBoolean("block-remove.enabled",true);delayMs=(long)cfg.getInt("block-remove.delay-seconds",120)*1000L;
        maxQueueSize=cfg.getInt("block-remove.max-queue-size",100000);checkIntervalTicks=Math.max(1,cfg.getInt("block-remove.check-interval-ticks",40));
        persist=cfg.getBoolean("block-remove.persist-on-restart",true);
        blacklist=EnumSet.noneOf(Material.class);
        for(String s:cfg.getStringList("block-remove.blacklist")){Material m=Material.matchMaterial(s);if(m!=null)blacklist.add(m);}
        worldWhitelist=new HashSet<>();List<String> wl=cfg.getStringList("block-remove.world-whitelist");worldWhitelistEnabled=!wl.isEmpty();worldWhitelist.addAll(wl);
        if(schedulerTaskId!=-1){Bukkit.getScheduler().cancelTask(schedulerTaskId);schedulerTaskId=-1;}
        if(enabled)startScheduler();
    }
    public boolean shouldTrack(Location loc){if(!enabled)return false;if(worldWhitelistEnabled){World w=loc.getWorld();if(w==null||!worldWhitelist.contains(w.getName()))return false;}return true;}
    public boolean isBlacklisted(Material m){return blacklist.contains(m);}
    public void queueBlock(Location loc){if(totalQueued>=maxQueueSize)return;long e=System.currentTimeMillis()+delayMs;removeQueue.computeIfAbsent(e,k->new ArrayList<>()).add(ser(loc));totalQueued++;}
    private void startScheduler(){if(!enabled)return;schedulerTaskId=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,checkIntervalTicks,checkIntervalTicks).getTaskId();}
    private void tick(){if(removeQueue.isEmpty())return;long now=System.currentTimeMillis();int p=0;while(!removeQueue.isEmpty()&&p<200){Map.Entry<Long,List<String>> e=removeQueue.firstEntry();if(e.getKey()>now)break;removeQueue.pollFirstEntry();for(String s:e.getValue()){Location l=deser(s);totalQueued--;if(l==null)continue;World w=l.getWorld();if(w==null)continue;Block b=w.getBlockAt(l);if(!b.getType().isAir())b.setType(Material.AIR,false);p++;}}}
    public void saveToDisk(){if(!persist||removeQueue.isEmpty())return;YamlConfiguration y=new YamlConfiguration();List<String> es=new ArrayList<>();long now=System.currentTimeMillis();for(var e:removeQueue.entrySet()){long r=e.getKey()-now;if(r<=0)continue;for(String l:e.getValue())es.add(r+":"+l);}y.set("entries",es);try{y.save(dataFile);}catch(IOException ex){plugin.getLogger().warning("Failed to save decay queue");}}
    private void loadFromDisk(){if(!dataFile.exists())return;YamlConfiguration y=YamlConfiguration.loadConfiguration(dataFile);long now=System.currentTimeMillis();int loaded=0;for(String e:y.getStringList("entries")){int c=e.indexOf(':');if(c<0)continue;try{long r=Long.parseLong(e.substring(0,c));String l=e.substring(c+1);removeQueue.computeIfAbsent(now+r,k->new ArrayList<>()).add(l);totalQueued++;loaded++;if(totalQueued>=maxQueueSize)break;}catch(NumberFormatException ignored){}}if(loaded>0)plugin.getLogger().info("[BlockRemove] Loaded "+loaded+" pending blocks.");dataFile.delete();}
    public void shutdown(){if(schedulerTaskId!=-1)Bukkit.getScheduler().cancelTask(schedulerTaskId);saveToDisk();}
    public int getQueueSize(){return totalQueued;} public boolean isEnabled(){return enabled;}
    private static String ser(Location l){return l.getWorld().getName()+","+l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ();}
    private Location deser(String s){String[] p=s.split(",");if(p.length!=4)return null;try{World w=Bukkit.getWorld(p[0]);if(w==null)return null;return new Location(w,Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));}catch(NumberFormatException e){return null;}}
}


// ═══════════════════════════════════════
// BoosterManager.java
// ═══════════════════════════════════════
class BoosterManager {
    public enum BoosterType{MINING_3X3,MULTI_BLOCK,NUKER,KILL_SHARD}
    public static class BoosterData{
        public final UUID owner; public final BoosterManager.BoosterType type; public long expiresAt; public BossBar bossBar;
        public BoosterData(UUID owner,BoosterManager.BoosterType type,long expiresAt){this.owner=owner;this.type=type;this.expiresAt=expiresAt;}
        public boolean isExpired(){return System.currentTimeMillis()>=expiresAt;}
        public long remainingMs(){return Math.max(0,expiresAt-System.currentTimeMillis());}
        public boolean isServer(){return owner==null;}
    }
    private final SchoolMine plugin;
    private final ConcurrentHashMap<UUID,Map<BoosterManager.BoosterType,BoosterManager.BoosterData>> playerBoosters=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BoosterManager.BoosterType,BoosterManager.BoosterData> serverBoosters=new ConcurrentHashMap<>();
    private int tickTaskId=-1;
    public BoosterManager(SchoolMine p){plugin=p;startTicker();}
    public void activatePlayerBooster(Player player,BoosterManager.BoosterType type,long durationMs){
        Map<BoosterManager.BoosterType,BoosterManager.BoosterData> map=playerBoosters.computeIfAbsent(player.getUniqueId(),k->new ConcurrentHashMap<>());
        BoosterManager.BoosterData ex=map.get(type);
        if(ex!=null&&!ex.isExpired()){ex.expiresAt+=durationMs;updateBossBarProgress(ex);}
        else{long expiry=System.currentTimeMillis()+durationMs;BoosterManager.BoosterData d=new BoosterManager.BoosterData(player.getUniqueId(),type,expiry);d.bossBar=createBossBar(type,false);d.bossBar.addPlayer(player);map.put(type,d);updateBossBarProgress(d);}
    }
    public void activateServerBooster(Player activator,BoosterManager.BoosterType type,long durationMs){
        BoosterManager.BoosterData ex=serverBoosters.get(type);
        if(ex!=null&&!ex.isExpired()){ex.expiresAt+=durationMs;}
        else{long expiry=System.currentTimeMillis()+durationMs;BoosterManager.BoosterData d=new BoosterManager.BoosterData(null,type,expiry);d.bossBar=createBossBar(type,true);for(Player p:Bukkit.getOnlinePlayers())d.bossBar.addPlayer(p);serverBoosters.put(type,d);}
        broadcastServerBoosterTitle(activator,type,durationMs);
    }
    private void broadcastServerBoosterTitle(Player activator,BoosterManager.BoosterType type,long durationMs){
        var cfg=plugin.getBoosterConfig();
        if(!cfg.getBoolean("server-booster-title.enabled",true))return;
        String typeName=typeName(type);
        String title=colorize(cfg.getString("server-booster-title.title","&6&l⚡ SERVER BOOSTER ⚡"));
        String subtitle=colorize(cfg.getString("server-booster-title.subtitle","&e{player} &7kích hoạt &a{type}!").replace("{player}",activator.getName()).replace("{type}",typeName).replace("{duration}",formatTime(durationMs)));
        int fi=cfg.getInt("server-booster-title.fade-in",10),st=cfg.getInt("server-booster-title.stay",60),fo=cfg.getInt("server-booster-title.fade-out",10);
        for(Player p:Bukkit.getOnlinePlayers())p.sendTitle(title,subtitle,fi,st,fo);
    }
    public void onPlayerJoin(Player p){for(BoosterManager.BoosterData d:serverBoosters.values())if(!d.isExpired())d.bossBar.addPlayer(p);Map<BoosterManager.BoosterType,BoosterManager.BoosterData> m=playerBoosters.get(p.getUniqueId());if(m!=null)for(BoosterManager.BoosterData d:m.values())if(!d.isExpired())d.bossBar.addPlayer(p);}
    public void onPlayerQuit(Player p){Map<BoosterManager.BoosterType,BoosterManager.BoosterData> m=playerBoosters.get(p.getUniqueId());if(m!=null)for(BoosterManager.BoosterData d:m.values())if(d.bossBar!=null)d.bossBar.removePlayer(p);}
    public boolean hasBooster(UUID uuid,BoosterManager.BoosterType type){BoosterManager.BoosterData s=serverBoosters.get(type);if(s!=null&&!s.isExpired())return true;Map<BoosterManager.BoosterType,BoosterManager.BoosterData> m=playerBoosters.get(uuid);if(m==null)return false;BoosterManager.BoosterData d=m.get(type);return d!=null&&!d.isExpired();}
    public BoosterManager.BoosterData getPlayerBooster(UUID uuid,BoosterManager.BoosterType type){Map<BoosterManager.BoosterType,BoosterManager.BoosterData> m=playerBoosters.get(uuid);if(m==null)return null;BoosterManager.BoosterData d=m.get(type);return(d!=null&&!d.isExpired())?d:null;}
    public BoosterManager.BoosterData getServerBooster(BoosterManager.BoosterType type){BoosterManager.BoosterData d=serverBoosters.get(type);return(d!=null&&!d.isExpired())?d:null;}
    public void removePlayerBooster(UUID uuid,BoosterManager.BoosterType type){Map<BoosterManager.BoosterType,BoosterManager.BoosterData> m=playerBoosters.get(uuid);if(m==null)return;BoosterManager.BoosterData d=m.remove(type);if(d!=null&&d.bossBar!=null)d.bossBar.removeAll();}
    public void removeServerBooster(BoosterManager.BoosterType type){BoosterManager.BoosterData d=serverBoosters.remove(type);if(d!=null&&d.bossBar!=null)d.bossBar.removeAll();}
    public void removeAllBoosters(UUID uuid){for(BoosterManager.BoosterType t:BoosterManager.BoosterType.values())removePlayerBooster(uuid,t);}
    public void removeAllServerBoosters(){for(BoosterManager.BoosterType t:BoosterManager.BoosterType.values())removeServerBooster(t);}
    private void startTicker(){tickTaskId=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L).getTaskId();}
    private void tick(){long now=System.currentTimeMillis();
        for(var e:serverBoosters.entrySet()){BoosterManager.BoosterData d=e.getValue();if(d.isExpired()){if(d.bossBar!=null)d.bossBar.removeAll();serverBoosters.remove(e.getKey());}else updateBossBarProgress(d);}
        for(var pe:playerBoosters.entrySet()){UUID uuid=pe.getKey();Player p=Bukkit.getOnlinePlayers().stream().filter(pl->pl.getUniqueId().equals(uuid)).findFirst().orElse(null);
            for(var be:pe.getValue().entrySet()){BoosterManager.BoosterData d=be.getValue();if(d.isExpired()){if(d.bossBar!=null)d.bossBar.removeAll();pe.getValue().remove(be.getKey());}else if(p!=null)updateBossBarProgress(d);}}}
    private void updateBossBarProgress(BoosterManager.BoosterData data){if(data.bossBar==null)return;long r=data.remainingMs();data.bossBar.setTitle("§6"+(data.isServer()?"[SERVER] ":"")+bossBarLabel(data.type)+" §7- §e"+formatTime(r));data.bossBar.setProgress(Math.max(0.0,Math.min(1.0,r/(double)(2*60*60*1000))));}
    private String bossBarLabel(BoosterManager.BoosterType t){return switch(t){case MINING_3X3->"⛏ 3x3";case MULTI_BLOCK->"✦ x2 Block";case NUKER->"☢ Nuker";case KILL_SHARD->"⚔ Kill Shard";};}
    private String typeName(BoosterManager.BoosterType t){return switch(t){case MINING_3X3->"3x3 Mining";case MULTI_BLOCK->"x2 Block";case NUKER->"Nuker";case KILL_SHARD->"Kill Shard";};}
    private BossBar createBossBar(BoosterManager.BoosterType type,boolean server){
        String title=(server?"[SERVER] ":"")+switch(type){case MINING_3X3->"⛏ 3x3 Mining Booster";case MULTI_BLOCK->"✦ x2 Block Booster";case NUKER->"☢ Nuker Booster";case KILL_SHARD->"⚔ Kill Shard Booster";};
        BarColor color=switch(type){case MINING_3X3->BarColor.YELLOW;case MULTI_BLOCK->BarColor.GREEN;case NUKER->BarColor.RED;case KILL_SHARD->BarColor.PURPLE;};
        return Bukkit.createBossBar(title,color,BarStyle.SEGMENTED_10);
    }
    public static String formatTime(long ms){long s=ms/1000;if(s<60)return s+"s";long m=s/60;if(m<60)return m+"m "+(s%60)+"s";long h=m/60;return h+"h "+(m%60)+"m";}
    private String colorize(String s){return s==null?"":s.replace("&","\u00A7");}
    public void shutdown(){if(tickTaskId!=-1)Bukkit.getScheduler().cancelTask(tickTaskId);for(BoosterManager.BoosterData d:serverBoosters.values())if(d.bossBar!=null)d.bossBar.removeAll();for(var m:playerBoosters.values())for(BoosterManager.BoosterData d:m.values())if(d.bossBar!=null)d.bossBar.removeAll();}
}


// ═══════════════════════════════════════
// BoosterMenu.java
// ═══════════════════════════════════════
class BoosterMenu implements InventoryHolder {
    public enum MenuPage{MAIN,PERSONAL_3X3,SERVER_3X3,PERSONAL_MULTI,SERVER_MULTI,PERSONAL_NUKER,SERVER_NUKER,PERSONAL_KILL_SHARD,SERVER_KILL_SHARD}
    private final SchoolMine plugin;private final Player player;private final BoosterMenu.MenuPage page;private Inventory inv;
    public BoosterMenu(SchoolMine p,Player pl,BoosterMenu.MenuPage pg){plugin=p;player=pl;page=pg;}
    public Inventory build(){switch(page){case MAIN->buildMain();case PERSONAL_3X3->buildPurchase(BoosterManager.BoosterType.MINING_3X3,false);case SERVER_3X3->buildPurchase(BoosterManager.BoosterType.MINING_3X3,true);case PERSONAL_MULTI->buildPurchase(BoosterManager.BoosterType.MULTI_BLOCK,false);case SERVER_MULTI->buildPurchase(BoosterManager.BoosterType.MULTI_BLOCK,true);case PERSONAL_NUKER->buildPurchase(BoosterManager.BoosterType.NUKER,false);case SERVER_NUKER->buildPurchase(BoosterManager.BoosterType.NUKER,true);case PERSONAL_KILL_SHARD->buildPurchase(BoosterManager.BoosterType.KILL_SHARD,false);case SERVER_KILL_SHARD->buildPurchase(BoosterManager.BoosterType.KILL_SHARD,true);}return inv;}
    private YamlConfiguration cfg(){return plugin.getBoosterConfig();}
    private void buildMain(){
        var cfg=cfg();inv=Bukkit.createInventory(this,54,c(cfg.getString("gui.main-title","&8&l⚡ Booster Menu")));
        fillBorder(inv,mat(cfg.getString("gui.border-material","GRAY_STAINED_GLASS_PANE")));
        var bm=plugin.getBoosterManager();PlayerPointsAPI pp=plugin.getPlayerPointsAPI();int balance=pp!=null?pp.look(player.getUniqueId()):0;
        inv.setItem(4,item(cfg,"gui.balance-item",Material.SUNFLOWER,ph("balance",""+balance)));
        inv.setItem(19,item(cfg,"gui.mining-3x3-personal",Material.DIAMOND_PICKAXE,ph("status",status(cfg,bm.getPlayerBooster(player.getUniqueId(),BoosterManager.BoosterType.MINING_3X3)))));
        inv.setItem(21,item(cfg,"gui.mining-3x3-server",Material.NETHERITE_PICKAXE,ph("status",status(cfg,bm.getServerBooster(BoosterManager.BoosterType.MINING_3X3)))));
        inv.setItem(23,item(cfg,"gui.multi-block-personal",Material.EMERALD,ph("status",status(cfg,bm.getPlayerBooster(player.getUniqueId(),BoosterManager.BoosterType.MULTI_BLOCK)))));
        inv.setItem(25,item(cfg,"gui.multi-block-server",Material.EMERALD_BLOCK,ph("status",status(cfg,bm.getServerBooster(BoosterManager.BoosterType.MULTI_BLOCK)))));
        inv.setItem(38,item(cfg,"gui.nuker-personal",Material.TNT,ph("status",status(cfg,bm.getPlayerBooster(player.getUniqueId(),BoosterManager.BoosterType.NUKER)))));
        inv.setItem(40,item(cfg,"gui.nuker-server",Material.TNT_MINECART,ph("status",status(cfg,bm.getServerBooster(BoosterManager.BoosterType.NUKER)))));
        inv.setItem(47,item(cfg,"gui.kill-shard-personal",Material.AMETHYST_SHARD,ph("status",status(cfg,bm.getPlayerBooster(player.getUniqueId(),BoosterManager.BoosterType.KILL_SHARD)))));
        inv.setItem(51,item(cfg,"gui.kill-shard-server",Material.AMETHYST_CLUSTER,ph("status",status(cfg,bm.getServerBooster(BoosterManager.BoosterType.KILL_SHARD)))));}
    private void buildPurchase(BoosterManager.BoosterType type,boolean server){
        var cfg=cfg();String typeName=tname(type),scope=server?"Server":"Personal";
        inv=Bukkit.createInventory(this,54,c(cfg.getString("gui.purchase-title","&8&l⚡ {type} [{scope}]").replace("{type}",typeName).replace("{scope}",scope)));
        fillBorder(inv,mat(cfg.getString("gui.border-material","GRAY_STAINED_GLASS_PANE")));
        String base="boosters."+tkey(type)+"."+(server?"server":"personal");
        String[]dKeys={"5m","10m","30m","2h"};String[]dLabels={"5 Phút","10 Phút","30 Phút","2 Giờ"};int[]slots={20,22,24,31};int[]defPrices=defPrices(type,server);
        PlayerPointsAPI pp=plugin.getPlayerPointsAPI();int balance=pp!=null?pp.look(player.getUniqueId()):0;
        for(int i=0;i<4;i++){int price=cfg.getInt(base+".price-"+dKeys[i],defPrices[i]);boolean afford=balance>=price;
            String affordStr=afford?c(cfg.getString("gui.afford-yes","&aClick để mua")):c(cfg.getString("gui.afford-no","&cKhông đủ! (&e{balance}&c/&e{price}&c)").replace("{balance}",""+balance).replace("{price}",""+price));
            inv.setItem(slots[i],item(cfg,"gui.purchase-item",Material.CLOCK,ph("duration",dLabels[i],"type",typeName,"scope",scope,"price",""+price,"afford_status",affordStr)));}
        BoosterManager.BoosterData active=server?plugin.getBoosterManager().getServerBooster(type):plugin.getBoosterManager().getPlayerBooster(player.getUniqueId(),type);
        inv.setItem(4,item(cfg,"gui.info-item",Material.PAPER,ph("type",typeName,"scope",scope,"status",status(cfg,active))));
        inv.setItem(49,item(cfg,"gui.back-button",Material.ARROW,new String[0][]));}
    private String status(YamlConfiguration cfg,BoosterManager.BoosterData d){return d!=null?c(cfg.getString("gui.status-active","&aĐang: &e{time}").replace("{time}",BoosterManager.formatTime(d.remainingMs()))):c(cfg.getString("gui.status-inactive","&cChưa kích hoạt"));}
    private ItemStack item(YamlConfiguration cfg,String path,Material fallback,String[][]phs){
        String ms=cfg.getString(path+".material");Material mat=ms!=null?Material.matchMaterial(ms):null;if(mat==null)mat=fallback;
        String name=cfg.getString(path+".name"," ");List<String> loreRaw=cfg.getStringList(path+".lore");
        for(String[]ph:phs){name=name.replace("{"+ph[0]+"}",ph[1]);}name=c(name);
        List<String> lore=new ArrayList<>();for(String l:loreRaw){for(String[]ph:phs)l=l.replace("{"+ph[0]+"}",ph[1]);lore.add(c(l));}
        return make(mat,name,lore);}
    private String[][]ph(String...kv){List<String[]>l=new ArrayList<>();for(int i=0;i<kv.length;i+=2)l.add(new String[]{kv[i],kv[i+1]});return l.toArray(new String[0][]);}
    private int[]defPrices(BoosterManager.BoosterType t,boolean server){int b=switch(t){case MINING_3X3->100;case MULTI_BLOCK->150;case NUKER->200;case KILL_SHARD->200;};int m=server?5:1;return new int[]{b*m,b*2*m,b*5*m,b*15*m};}
    private String tname(BoosterManager.BoosterType t){return switch(t){case MINING_3X3->"3x3 Mining";case MULTI_BLOCK->"x2 Block";case NUKER->"Nuker";case KILL_SHARD->"Kill Shard";};}
    private String tkey(BoosterManager.BoosterType t){return switch(t){case MINING_3X3->"mining-3x3";case MULTI_BLOCK->"multi-block";case NUKER->"nuker";case KILL_SHARD->"kill-shard";};}
    public long getDurationMsFromSlot(int slot){return switch(slot){case 20->5*60*1000L;case 22->10*60*1000L;case 24->30*60*1000L;case 31->2*60*60*1000L;default->-1L;};}
    public String getDurationKeyFromSlot(int slot){return switch(slot){case 20->"5m";case 22->"10m";case 24->"30m";case 31->"2h";default->null;};}
    public BoosterMenu.MenuPage getPage(){return page;}
    public BoosterManager.BoosterType getTypeFromPage(){return switch(page){case PERSONAL_3X3,SERVER_3X3->BoosterManager.BoosterType.MINING_3X3;case PERSONAL_NUKER,SERVER_NUKER->BoosterManager.BoosterType.NUKER;case PERSONAL_KILL_SHARD,SERVER_KILL_SHARD->BoosterManager.BoosterType.KILL_SHARD;default->BoosterManager.BoosterType.MULTI_BLOCK;};}
    public boolean isServerPage(){return page==BoosterMenu.MenuPage.SERVER_3X3||page==BoosterMenu.MenuPage.SERVER_MULTI||page==BoosterMenu.MenuPage.SERVER_NUKER||page==BoosterMenu.MenuPage.SERVER_KILL_SHARD;}
    @Override public Inventory getInventory(){return inv;}
    private static String c(String s){return s==null?"":s.replace("&","\u00A7");}
    private static Material mat(String s){Material m=s!=null?Material.matchMaterial(s):null;return m!=null?m:Material.GRAY_STAINED_GLASS_PANE;}
    public static ItemStack make(Material mat,String name,List<String>lore){ItemStack i=new ItemStack(mat);ItemMeta m=i.getItemMeta();if(m!=null){m.setDisplayName(name);m.setLore(lore);i.setItemMeta(m);}return i;}
    public static void fillBorder(Inventory inv,Material mat){ItemStack g=make(mat," ",List.of());int size=inv.getSize(),rows=size/9;for(int i=0;i<9;i++)inv.setItem(i,g);for(int i=size-9;i<size;i++)inv.setItem(i,g);for(int r=1;r<rows-1;r++){inv.setItem(r*9,g);inv.setItem(r*9+8,g);}}
}


// ═══════════════════════════════════════
// BlockBreakListener.java
// ═══════════════════════════════════════
class BlockBreakListener implements Listener {
    private final SchoolMine plugin;
    private final Set<UUID> currently3x3 = ConcurrentHashMap.newKeySet();
    public BlockBreakListener(SchoolMine p) { plugin = p; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        UUID uuid = player.getUniqueId();
        if (currently3x3.contains(uuid)) return;

        ConfigManager cm = plugin.getConfigManager();
        PlayerData data = plugin.getPlayerDataManager().get(player);
        var bm = plugin.getBoosterManager();
        Block block = event.getBlock();
        Material blockType = block.getType();
        ItemStack tool = player.getInventory().getItemInMainHand();

        boolean has3x3Booster = bm.hasBooster(uuid, BoosterManager.BoosterType.MINING_3X3);
        boolean hasMultiBooster = bm.hasBooster(uuid, BoosterManager.BoosterType.MULTI_BLOCK);
        // Permission-based drop multiplier
        double permMult = AutoMineTask.getDropMultiplier(player);

        // ── 3x3 Mining ──
        if ((data.isMining3x3() || has3x3Booster) && cm.getMining3x3Whitelist().contains(blockType)
                && (!cm.isMining3x3RequireCorrectTool() || isCorrectTool(player, blockType))) {
            event.setCancelled(true);
            currently3x3.add(uuid);
            try { do3x3(player, block, data, cm, hasMultiBooster, permMult); }
            finally { currently3x3.remove(uuid); }
            return;
        }

        // ── AutoPickup ──
        if (data.isAutoPickup() && !cm.getAutopickupBlacklist().contains(blockType)) {
            event.setDropItems(false);
            Collection<ItemStack> drops = applySmelt(block.getDrops(tool, player), blockType, data, cm);
            drops = applyMultipliers(drops, hasMultiBooster, permMult);
            giveItems(player, block, drops);
            int xp = event.getExpToDrop(); if (xp > 0) { player.giveExp(xp); event.setExpToDrop(0); }
            return;
        }

        // ── AutoSmelt only ──
        if (data.isAutoSmelt()) {
            Material sr = cm.getSmeltResult(blockType);
            if (sr != null) {
                event.setDropItems(false);
                for (ItemStack d : block.getDrops(tool, player)) {
                    int amt = applyMult(d.getAmount(), hasMultiBooster, permMult);
                    dropItem(block, new ItemStack(sr, amt));
                }
                return;
            }
        }

        // ── Multi booster / perm multiplier only ──
        if (hasMultiBooster || permMult != 1.0) {
            event.setDropItems(false);
            for (ItemStack d : block.getDrops(tool, player))
                dropItem(block, new ItemStack(d.getType(), applyMult(d.getAmount(), hasMultiBooster, permMult)));
        }
    }

    private void do3x3(Player player, Block center, PlayerData data, ConfigManager cm,
                        boolean multiBooster, double permMult) {
        int radius = cm.getMining3x3Radius();
        BlockFace face = getMainFace(player.getLocation().getDirection());
        List<Block> blocks = getAreaBlocks(center, face, radius, cm.getMax3x3Blocks());
        for (Block b : blocks) {
            Material type = b.getType();
            if (type.isAir() || type.name().equals("BEDROCK") || !cm.getMining3x3Whitelist().contains(type)) continue;
            ItemStack tool = player.getInventory().getItemInMainHand();
            Collection<ItemStack> drops = applySmelt(b.getDrops(tool, player), type, data, cm);
            drops = applyMultipliers(drops, multiBooster, permMult);
            if (data.isAutoPickup() && !cm.getAutopickupBlacklist().contains(type)) giveItems(player, b, drops);
            else for (ItemStack d : drops) dropItem(b, d);
            b.setType(Material.AIR, false);
        }
    }

    private Collection<ItemStack> applyMultipliers(Collection<ItemStack> drops, boolean multiBooster, double permMult) {
        if (!multiBooster && permMult == 1.0) return drops;
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack d : drops) {
            if (d == null || d.getType().isAir()) continue;
            out.add(new ItemStack(d.getType(), applyMult(d.getAmount(), multiBooster, permMult)));
        }
        return out;
    }

    private int applyMult(int amount, boolean multiBooster, double permMult) {
        double mult = multiBooster ? 2.0 : 1.0;
        // Stack multipliers: booster x2 * perm x1.5 = x3
        mult *= permMult;
        return (int) Math.max(1, Math.round(amount * mult));
    }

    private List<Block> getAreaBlocks(Block center, BlockFace face, int radius, int max) {
        List<Block> r = new ArrayList<>();
        for (int a = -radius; a <= radius; a++) for (int b = -radius; b <= radius; b++) {
            Block rel = switch (face) { case NORTH, SOUTH -> center.getRelative(a,b,0); case EAST, WEST -> center.getRelative(0,b,a); default -> center.getRelative(a,0,b); };
            r.add(rel); if (r.size() >= max) return r; }
        return r;
    }
    private BlockFace getMainFace(Vector dir) { double ax=Math.abs(dir.getX()),ay=Math.abs(dir.getY()),az=Math.abs(dir.getZ()); if(ay>ax&&ay>az)return dir.getY()>0?BlockFace.UP:BlockFace.DOWN; if(ax>=az)return dir.getX()>0?BlockFace.EAST:BlockFace.WEST; return dir.getZ()>0?BlockFace.SOUTH:BlockFace.NORTH; }
    private Collection<ItemStack> applySmelt(Collection<ItemStack> drops, Material bt, PlayerData data, ConfigManager cm) { if(!data.isAutoSmelt())return drops; Material bb=cm.getSmeltResult(bt); List<ItemStack> out=new ArrayList<>(); for(ItemStack d:drops){if(d==null||d.getType().isAir())continue; Material r=bb!=null?bb:cm.getSmeltResult(d.getType()); out.add(r!=null?new ItemStack(r,d.getAmount()):d);} return out; }
    private void giveItems(Player player, Block block, Collection<ItemStack> drops) { PlayerInventory inv=player.getInventory(); for(ItemStack d:drops){if(d==null||d.getType().isAir())continue; HashMap<Integer,ItemStack> lft=inv.addItem(d.clone()); if(!lft.isEmpty())for(ItemStack l:lft.values())dropItem(block,l);} }
    private void dropItem(Block block, ItemStack item) { block.getLocation().getWorld().dropItemNaturally(block.getLocation(), item); }
    private boolean isCorrectTool(Player player, Material bt) { ItemStack h=player.getInventory().getItemInMainHand(); if(h==null)return false; String t=h.getType().name(),b=bt.name(); boolean ore=b.contains("_ORE")||b.equals("ANCIENT_DEBRIS"),log=b.endsWith("_LOG")||b.endsWith("_WOOD"),stone=b.contains("STONE")||b.contains("DEEPSLATE")||b.contains("COBBLE")||b.contains("OBSIDIAN")||b.contains("NETHERRACK")||b.contains("GRANITE")||b.contains("DIORITE")||b.contains("ANDESITE")||b.contains("TUFF")||b.contains("CALCITE")||b.contains("SANDSTONE"),oreBlock=b.endsWith("_BLOCK")&&(b.contains("COAL")||b.contains("IRON")||b.contains("COPPER")||b.contains("GOLD")||b.contains("DIAMOND")||b.contains("EMERALD")||b.contains("NETHERITE")||b.contains("LAPIS")||b.contains("REDSTONE")||b.contains("QUARTZ")||b.contains("RAW")||b.contains("AMETHYST")); if(ore||stone||oreBlock)return t.endsWith("_PICKAXE"); if(log)return t.endsWith("_AXE"); return true; }
}


// ═══════════════════════════════════════
// BlockPlaceListener.java
// ═══════════════════════════════════════
class BlockPlaceListener implements Listener{
    private final SchoolMine plugin;
    public BlockPlaceListener(SchoolMine p){plugin=p;}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onBlockPlace(BlockPlaceEvent event){
        if(!event.getPlayer().hasPermission("schoolmine.breakblock"))return;
        var rm=plugin.getBlockRemoveManager();var loc=event.getBlockPlaced().getLocation();
        if(!rm.shouldTrack(loc))return;if(rm.isBlacklisted(event.getBlockPlaced().getType()))return;rm.queueBlock(loc);}
}


// ═══════════════════════════════════════
// PlayerJoinListener.java
// ═══════════════════════════════════════
class PlayerJoinListener implements Listener{
    private final SchoolMine plugin;
    public PlayerJoinListener(SchoolMine p){plugin=p;}
    @EventHandler public void onJoin(PlayerJoinEvent e){plugin.getBoosterManager().onPlayerJoin(e.getPlayer());}
}


// ═══════════════════════════════════════
// PlayerQuitListener.java
// ═══════════════════════════════════════
class PlayerQuitListener implements Listener{
    private final SchoolMine plugin;
    public PlayerQuitListener(SchoolMine p){plugin=p;}
    @EventHandler public void onQuit(PlayerQuitEvent e){plugin.getPlayerDataManager().unload(e.getPlayer().getUniqueId());plugin.getBoosterManager().onPlayerQuit(e.getPlayer());}
}


// ═══════════════════════════════════════
// NukerTask.java
// ═══════════════════════════════════════
class NukerTask implements Runnable {
    private final SchoolMine plugin;
    private final Map<UUID,Location> lastLoc=new HashMap<>();
    private int taskId=-1;
    public NukerTask(SchoolMine p){plugin=p;}
    public void start(){int interval=Math.max(1,boosterCfg().getInt("nuker.check-interval-ticks",15));taskId=Bukkit.getScheduler().runTaskTimer(plugin,this,interval,interval).getTaskId();}
    public void stop(){if(taskId!=-1)Bukkit.getScheduler().cancelTask(taskId);}
    private YamlConfiguration boosterCfg(){return plugin.getBoosterConfig();}
    @Override public void run(){
        var cfg=boosterCfg();
        int radius=cfg.getInt("nuker.radius",4);int max=cfg.getInt("nuker.max-blocks-per-check",25);
        boolean reqPick=cfg.getBoolean("nuker.require-pickaxe",true);boolean aboveFeet=cfg.getBoolean("nuker.only-above-feet",true);
        boolean applySmelt=cfg.getBoolean("nuker.apply-autosmelt",true);
        Set<Material> wl=EnumSet.noneOf(Material.class);for(String s:cfg.getStringList("nuker.whitelist")){Material m=Material.matchMaterial(s);if(m!=null)wl.add(m);}
        if(wl.isEmpty())return;
        for(Player p:Bukkit.getOnlinePlayers()){
            UUID uuid=p.getUniqueId();if(!plugin.getBoosterManager().hasBooster(uuid,BoosterManager.BoosterType.NUKER)){lastLoc.remove(uuid);continue;}
            if(reqPick&&!p.getInventory().getItemInMainHand().getType().name().endsWith("_PICKAXE"))continue;
            Location cur=p.getLocation();Location last=lastLoc.get(uuid);
            if(last!=null&&sameBlock(last,cur))continue;lastLoc.put(uuid,cur.clone());
            mine(p,cur,radius,wl,max,aboveFeet,applySmelt);}
    }
    private void mine(Player player,Location center,int radius,Set<Material> wl,int max,boolean aboveFeet,boolean applySmelt){
        World world=center.getWorld();if(world==null)return;
        int cx=center.getBlockX(),cy=center.getBlockY(),cz=center.getBlockZ(),radSq=radius*radius,dyMin=aboveFeet?0:-radius,mined=0;
        ItemStack tool=player.getInventory().getItemInMainHand();
        boolean doSmelt=applySmelt&&player.hasPermission("schoolmine.autosmelt")&&plugin.getPlayerDataManager().get(player).isAutoSmelt();
        var cm=plugin.getConfigManager();
        for(int dx=-radius;dx<=radius&&mined<max;dx++)for(int dy=dyMin;dy<=radius&&mined<max;dy++)for(int dz=-radius;dz<=radius&&mined<max;dz++){
            if(dx*dx+dy*dy+dz*dz>radSq)continue;Block b=world.getBlockAt(cx+dx,cy+dy,cz+dz);Material type=b.getType();
            if(type.isAir()||!wl.contains(type))continue;
            Collection<ItemStack> drops=b.getDrops(tool,player);
            if(doSmelt)drops=smelt(drops,type,cm);
            give(player,b,drops);b.setType(Material.AIR,false);mined++;}
    }
    private Collection<ItemStack> smelt(Collection<ItemStack> drops,Material type,ConfigManager cm){
        Material bb=cm.getSmeltResult(type);List<ItemStack> out=new ArrayList<>();
        for(ItemStack d:drops){if(d==null||d.getType().isAir())continue;Material r=bb!=null?bb:cm.getSmeltResult(d.getType());out.add(r!=null?new ItemStack(r,d.getAmount()):d);}return out;}
    private void give(Player player,Block block,Collection<ItemStack> drops){
        PlayerInventory inv=player.getInventory();
        for(ItemStack d:drops){if(d==null||d.getType().isAir())continue;HashMap<Integer,ItemStack> lft=inv.addItem(d.clone());if(!lft.isEmpty())for(ItemStack l:lft.values())block.getWorld().dropItemNaturally(block.getLocation(),l);}}
    private boolean sameBlock(Location a,Location b){return a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ()&&a.getWorld()==b.getWorld();}
}


// ═══════════════════════════════════════
// AutoMineTask.java
// ═══════════════════════════════════════
class AutoMineTask implements Runnable {

    private final SchoolMine plugin;
    // Per-player: remaining ticks until next mine cycle
    private final Map<UUID, Integer> cooldowns = new HashMap<>();
    private int taskId = -1;

    public AutoMineTask(SchoolMine p) { plugin = p; }

    public void start() {
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L).getTaskId();
    }

    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }

    @Override
    public void run() {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.isConfigurationSection("auto-mine")) return;

        int baseInterval = Math.max(1, cfg.getInt("auto-mine.base-interval-ticks", 20));
        int maxBlocks = cfg.getInt("auto-mine.max-blocks-per-tick", 20);
        boolean onlyAboveFeet = cfg.getBoolean("auto-mine.only-above-feet", true);
        boolean requirePickaxe = cfg.getBoolean("auto-mine.require-pickaxe", true);
        double effBonus = cfg.getDouble("auto-mine.efficiency-bonus-per-level", 0.4);
        boolean applyFortune = cfg.getBoolean("auto-mine.apply-fortune", true);
        boolean applySilkTouch = cfg.getBoolean("auto-mine.apply-silk-touch", true);

        Set<Material> whitelist = EnumSet.noneOf(Material.class);
        for (String s : cfg.getStringList("auto-mine.whitelist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) whitelist.add(m);
        }
        if (whitelist.isEmpty()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerData data = plugin.getPlayerDataManager().get(player);
            if (!data.isAutoMine()) { cooldowns.remove(uuid); continue; }

            ItemStack tool = player.getInventory().getItemInMainHand();
            if (requirePickaxe && !isPickaxe(tool)) { cooldowns.remove(uuid); continue; }

            // Radius from permission automine.1..6, fallback to config
            int radius = getAutoMineRadius(player, cfg.getInt("auto-mine.radius", 3));

            int interval = calcInterval(tool, baseInterval, effBonus, cfg);
            int cd = cooldowns.getOrDefault(uuid, 0);
            if (cd > 0) { cooldowns.put(uuid, cd - 1); continue; }
            cooldowns.put(uuid, interval - 1);

            mine(player, tool, radius, onlyAboveFeet, whitelist, maxBlocks,
                 applyFortune, applySilkTouch, cfg);
        }
    }

    /**
     * Radius từ permission automine.1 đến automine.6
     * Nếu không có permission cụ thể, dùng fallback từ config
     */
    private int getAutoMineRadius(Player player, int fallback) {
        for (int r = 6; r >= 1; r--) {
            if (player.hasPermission("automine." + r)) return r;
        }
        return fallback;
    }

    /**
     * Drop multiplier từ permission mine.x1.2, mine.x1.4, mine.x1.6, mine.x1.8, mine.x2 ... mine.x5
     * Trả về float multiplier (1.0 = bình thường)
     */
    public static double getDropMultiplier(Player player) {
        // Check integer multipliers x2..x5 (higher first)
        for (int i = 5; i >= 2; i--) {
            if (player.hasPermission("mine.x" + i)) return i;
        }
        // Check decimal multipliers x1.2, x1.4, x1.6, x1.8
        double[] decimals = {1.8, 1.6, 1.4, 1.2};
        for (double d : decimals) {
            String perm = "mine.x" + String.valueOf(d).replace(".0", "");
            if (player.hasPermission(perm)) return d;
        }
        return 1.0;
    }

    private int calcInterval(ItemStack tool, int base, double effBonus, FileConfiguration cfg) {
        if (tool == null || tool.getType().isAir()) return base;
        double speed = cfg.getDouble("auto-mine.tool-speed." + tool.getType().name(), 1.0);
        int eff = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        speed += eff * effBonus;
        if (speed <= 0) speed = 0.1;
        return (int) Math.max(1, base / speed);
    }

    private void mine(Player player, ItemStack tool, int radius, boolean onlyAboveFeet,
                      Set<Material> whitelist, int maxBlocks, boolean applyFortune,
                      boolean applySilkTouch, FileConfiguration cfg) {
        Location center = player.getLocation();
        World world = center.getWorld();
        if (world == null) return;

        ConfigManager cm = plugin.getConfigManager();
        PlayerData data = plugin.getPlayerDataManager().get(player);
        boolean doSmelt = cfg.getBoolean("auto-mine.apply-autosmelt", true)
            && player.hasPermission("schoolmine.autosmelt") && data.isAutoSmelt();
        boolean doPickup = cfg.getBoolean("auto-mine.apply-autopickup", true) && data.isAutoPickup();

        double dropMult = getDropMultiplier(player);
        boolean hasSilk = applySilkTouch && tool != null && tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0;
        int fortune = (applyFortune && tool != null) ? tool.getEnchantmentLevel(Enchantment.FORTUNE) : 0;

        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int radSq = radius * radius, dyMin = onlyAboveFeet ? 0 : -radius, mined = 0;

        for (int dx = -radius; dx <= radius && mined < maxBlocks; dx++)
            for (int dy = dyMin; dy <= radius && mined < maxBlocks; dy++)
                for (int dz = -radius; dz <= radius && mined < maxBlocks; dz++) {
                    if (dx*dx + dy*dy + dz*dz > radSq) continue;
                    Block block = world.getBlockAt(cx+dx, cy+dy, cz+dz);
                    Material type = block.getType();
                    if (type.isAir() || type.name().equals("BEDROCK") || !whitelist.contains(type)) continue;

                    Collection<ItemStack> drops = buildDrops(block, tool, type, hasSilk, fortune, dropMult, doSmelt, cm);
                    if (doPickup && !cm.getAutopickupBlacklist().contains(type)) {
                        giveItems(player, block, drops);
                    } else {
                        for (ItemStack drop : drops)
                            block.getWorld().dropItemNaturally(block.getLocation(), drop);
                    }
                    block.setType(Material.AIR, false);
                    mined++;
                }
    }

    private Collection<ItemStack> buildDrops(Block block, ItemStack tool, Material type,
                                              boolean silkTouch, int fortune, double dropMult,
                                              boolean doSmelt, ConfigManager cm) {
        if (silkTouch) {
            int amt = (int) Math.max(1, Math.round(dropMult));
            return Collections.singletonList(new ItemStack(type, amt));
        }

        Collection<ItemStack> raw = block.getDrops(tool, null);
        List<ItemStack> result = new ArrayList<>();
        Random rng = new Random();

        for (ItemStack drop : raw) {
            if (drop == null || drop.getType().isAir()) continue;
            int amount = drop.getAmount();

            // Fortune bonus
            if (fortune > 0) {
                int roll = rng.nextInt(fortune + 2);
                if (roll > 1) amount *= roll;
            }

            // Drop multiplier from permission
            if (dropMult != 1.0) {
                amount = (int) Math.max(1, Math.round(amount * dropMult));
            }

            // AutoSmelt
            if (doSmelt) {
                Material smelt = cm.getSmeltResult(type);
                if (smelt == null) smelt = cm.getSmeltResult(drop.getType());
                if (smelt != null) { result.add(new ItemStack(smelt, amount)); continue; }
            }
            result.add(new ItemStack(drop.getType(), amount));
        }
        return result;
    }

    private void giveItems(Player player, Block block, Collection<ItemStack> drops) {
        PlayerInventory inv = player.getInventory();
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) continue;
            HashMap<Integer, ItemStack> left = inv.addItem(drop.clone());
            if (!left.isEmpty())
                for (ItemStack l : left.values())
                    block.getWorld().dropItemNaturally(block.getLocation(), l);
        }
    }

    private boolean isPickaxe(ItemStack item) {
        return item != null && item.getType().name().endsWith("_PICKAXE");
    }
}


// ═══════════════════════════════════════
// BoosterMenuListener.java
// ═══════════════════════════════════════
class BoosterMenuListener implements Listener {
    private final SchoolMine plugin;
    public BoosterMenuListener(SchoolMine p){plugin=p;}
    @EventHandler public void onClick(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player player))return;
        Inventory clicked=event.getClickedInventory();if(clicked==null)return;
        Object holder=clicked.getHolder();if(!(holder instanceof BoosterMenu menu))return;
        event.setCancelled(true);int slot=event.getSlot();if(event.getCurrentItem()==null)return;
        switch(menu.getPage()){
            case MAIN->handleMain(player,slot);
            case PERSONAL_3X3,SERVER_3X3,PERSONAL_MULTI,SERVER_MULTI,PERSONAL_NUKER,SERVER_NUKER,PERSONAL_KILL_SHARD,SERVER_KILL_SHARD->handlePurchase(player,menu,slot);}
    }
    private void handleMain(Player player,int slot){
        BoosterMenu.MenuPage next=switch(slot){case 19->BoosterMenu.MenuPage.PERSONAL_3X3;case 21->BoosterMenu.MenuPage.SERVER_3X3;case 23->BoosterMenu.MenuPage.PERSONAL_MULTI;case 25->BoosterMenu.MenuPage.SERVER_MULTI;case 38->BoosterMenu.MenuPage.PERSONAL_NUKER;case 40->BoosterMenu.MenuPage.SERVER_NUKER;case 47->BoosterMenu.MenuPage.PERSONAL_KILL_SHARD;case 51->BoosterMenu.MenuPage.SERVER_KILL_SHARD;default->null;};
        if(next==null)return;player.closeInventory();player.openInventory(new BoosterMenu(plugin,player,next).build());}
    private void handlePurchase(Player player,BoosterMenu menu,int slot){
        if(slot==49){player.closeInventory();player.openInventory(new BoosterMenu(plugin,player,BoosterMenu.MenuPage.MAIN).build());return;}
        long durationMs=menu.getDurationMsFromSlot(slot);String dKey=menu.getDurationKeyFromSlot(slot);
        if(durationMs<0||dKey==null)return;
        BoosterManager.BoosterType type=menu.getTypeFromPage();boolean server=menu.isServerPage();
        String typeKey=switch(type){case MINING_3X3->"mining-3x3";case MULTI_BLOCK->"multi-block";case NUKER->"nuker";case KILL_SHARD->"kill-shard";};
        String cfgPath="boosters."+typeKey+"."+(server?"server":"personal")+".price-"+dKey;
        int base=switch(type){case MINING_3X3->100;case MULTI_BLOCK->150;case NUKER->200;case KILL_SHARD->200;};
        int mult=server?5:1;int[]mults={1,2,5,15};int idx=switch(dKey){case"5m"->0;case"10m"->1;case"30m"->2;default->3;};
        int price=plugin.getBoosterConfig().getInt(cfgPath,base*mult*mults[idx]);
        PlayerPointsAPI pp=plugin.getPlayerPointsAPI();
        if(pp==null){player.sendMessage("§c[SchoolMine] PlayerPoints không khả dụng!");player.closeInventory();return;}
        int balance=pp.look(player.getUniqueId());
        if(balance<price){player.sendMessage("§c[SchoolMine] Không đủ điểm! Cần §e"+price+"§c, bạn có §e"+balance+"§c.");player.closeInventory();return;}
        if(!pp.take(player.getUniqueId(),price)){player.sendMessage("§c[SchoolMine] Giao dịch thất bại!");player.closeInventory();return;}
        String typeName=switch(type){case MINING_3X3->"3x3 Mining";case MULTI_BLOCK->"x2 Block";case NUKER->"Nuker";case KILL_SHARD->"Kill Shard";};
        BoosterManager bm=plugin.getBoosterManager();
        if(server){bm.activateServerBooster(player,type,durationMs);for(Player p:Bukkit.getOnlinePlayers())p.sendMessage("§6[SchoolMine] §e"+player.getName()+" §đã kích hoạt §a"+typeName+" Server Booster! §7(§e"+BoosterManager.formatTime(durationMs)+"§7)");}
        else{bm.activatePlayerBooster(player,type,durationMs);player.sendMessage("§a[SchoolMine] Kích hoạt §e"+typeName+"§a thành công! (§e"+BoosterManager.formatTime(durationMs)+"§a)");}
        player.closeInventory();}
}


// ═══════════════════════════════════════
// KillShardListener.java
// ═══════════════════════════════════════
class KillShardListener implements Listener{
    private final SchoolMine plugin;
    public KillShardListener(SchoolMine p){plugin=p;}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onPlayerDeath(PlayerDeathEvent event){
        Player victim=event.getEntity();Player killer=victim.getKiller();if(killer==null)return;
        if(!plugin.getBoosterManager().hasBooster(killer.getUniqueId(),BoosterManager.BoosterType.KILL_SHARD))return;
        var cfg=plugin.getBoosterConfig();List<String> cmds=cfg.getStringList("kill-shard.commands");
        String msg=cfg.getString("kill-shard.message","&a+Shard!");
        for(String cmd:cmds)Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd.replace("{player}",killer.getName()).replace("{victim}",victim.getName()));
        killer.sendMessage(msg.replace("{player}",killer.getName()).replace("{victim}",victim.getName()).replace("&","\u00A7"));}
}


// ═══════════════════════════════════════
// AutoPickupCommand.java
// ═══════════════════════════════════════
class AutoPickupCommand implements CommandExecutor,TabCompleter{
    private final SchoolMine plugin;
    public AutoPickupCommand(SchoolMine p){plugin=p;}
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(!(sender instanceof Player player)){sender.sendMessage("§cOnly players!");return true;}
        if(!player.hasPermission("schoolmine.autopickup")){player.sendMessage(plugin.getConfigManager().getMessage("no-permission"));return true;}
        var data=plugin.getPlayerDataManager().get(player);
        boolean n=args.length==0?!data.isAutoPickup():args[0].equalsIgnoreCase("on");
        if(args.length>0&&!args[0].equalsIgnoreCase("on")&&!args[0].equalsIgnoreCase("off")){player.sendMessage(plugin.getConfigManager().getMessage("usage-autopickup"));return true;}
        data.setAutoPickup(n);player.sendMessage(plugin.getConfigManager().getMessage(n?"autopickup-on":"autopickup-off"));return true;}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){return a.length==1?Arrays.asList("on","off"):List.of();}
}


// ═══════════════════════════════════════
// Mining3x3Command.java
// ═══════════════════════════════════════
class Mining3x3Command implements CommandExecutor,TabCompleter{
    private final SchoolMine plugin;
    public Mining3x3Command(SchoolMine p){plugin=p;}
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(!(sender instanceof Player player)){sender.sendMessage("§cOnly players!");return true;}
        if(!player.hasPermission("schoolmine.3x3")){player.sendMessage(plugin.getConfigManager().getMessage("no-permission"));return true;}
        var data=plugin.getPlayerDataManager().get(player);
        boolean n=args.length==0?!data.isMining3x3():args[0].equalsIgnoreCase("on");
        if(args.length>0&&!args[0].equalsIgnoreCase("on")&&!args[0].equalsIgnoreCase("off")){player.sendMessage(plugin.getConfigManager().getMessage("usage-3x3"));return true;}
        data.setMining3x3(n);player.sendMessage(plugin.getConfigManager().getMessage(n?"mining3x3-on":"mining3x3-off"));return true;}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){return a.length==1?Arrays.asList("on","off"):List.of();}
}


// ═══════════════════════════════════════
// AutoSmeltCommand.java
// ═══════════════════════════════════════
class AutoSmeltCommand implements CommandExecutor,TabCompleter{
    private final SchoolMine plugin;
    public AutoSmeltCommand(SchoolMine p){plugin=p;}
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(!(sender instanceof Player player)){sender.sendMessage("§cOnly players!");return true;}
        if(!player.hasPermission("schoolmine.autosmelt")){player.sendMessage(plugin.getConfigManager().getMessage("no-permission"));return true;}
        var data=plugin.getPlayerDataManager().get(player);
        boolean n=args.length==0?!data.isAutoSmelt():args[0].equalsIgnoreCase("on");
        if(args.length>0&&!args[0].equalsIgnoreCase("on")&&!args[0].equalsIgnoreCase("off")){player.sendMessage(plugin.getConfigManager().getMessage("usage-autosmelt"));return true;}
        data.setAutoSmelt(n);player.sendMessage(plugin.getConfigManager().getMessage(n?"autosmelt-on":"autosmelt-off"));return true;}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){return a.length==1?Arrays.asList("on","off"):List.of();}
}


// ═══════════════════════════════════════
// AutoMineCommand.java
// ═══════════════════════════════════════
class AutoMineCommand implements CommandExecutor, TabCompleter {
    private final SchoolMine plugin;
    public AutoMineCommand(SchoolMine p){plugin=p;}
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(!(sender instanceof Player player)){sender.sendMessage("§cOnly players!");return true;}
        if(!player.hasPermission("schoolmine.automine")){player.sendMessage(plugin.getConfigManager().getMessage("no-permission"));return true;}
        PlayerData data=plugin.getPlayerDataManager().get(player);
        boolean newState;
        if(args.length==0)newState=!data.isAutoMine();
        else switch(args[0].toLowerCase()){case"on"->newState=true;case"off"->newState=false;default->{player.sendMessage("§eUsage: /automine [on|off]");return true;}}
        data.setAutoMine(newState);
        player.sendMessage(plugin.getConfigManager().getMessage(newState?"automine-on":"automine-off"));
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){if(a.length==1)return Arrays.asList("on","off");return List.of();}
}


// ═══════════════════════════════════════
// SchoolMineCommand.java
// ═══════════════════════════════════════
class SchoolMineCommand implements CommandExecutor,TabCompleter{
    private final SchoolMine plugin;
    public SchoolMineCommand(SchoolMine p){plugin=p;}
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(args.length==0){help(sender);return true;}
        switch(args[0].toLowerCase()){
            case"reload"->{if(!sender.hasPermission("schoolmine.reload")){sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));return true;}plugin.reload();sender.sendMessage(plugin.getConfigManager().getMessage("reload-success"));}
            case"status"->{if(!(sender instanceof Player p)){sender.sendMessage("§cOnly players!");return true;}var d=plugin.getPlayerDataManager().get(p);var bm=plugin.getBoosterManager();p.sendMessage("§8[§6SchoolMine§8] §7Status:");p.sendMessage("§7AutoPickup: "+(d.isAutoPickup()?"§aON":"§cOFF"));p.sendMessage("§73x3: "+(d.isMining3x3()?"§aON":"§cOFF"));p.sendMessage("§7AutoSmelt: "+(d.isAutoSmelt()?"§aON":"§cOFF"));p.sendMessage("§7AutoMine: "+(d.isAutoMine()?"§aON":"§cOFF"));p.sendMessage("§7BlockRemove Queue: §e"+plugin.getBlockRemoveManager().getQueueSize());}
            default->help(sender);}
        return true;}
    private void help(CommandSender s){s.sendMessage("§8§m-----§r§8[§6SchoolMine§8]§8§m-----");s.sendMessage("§e/autopickup §7- Toggle auto pickup");s.sendMessage("§e/3x3 §7- Toggle 3x3 mining");s.sendMessage("§e/autosmelt §7- Toggle auto smelt");s.sendMessage("§e/automine §7- Toggle auto mine");s.sendMessage("§e/booster §7- Open booster menu");s.sendMessage("§e/schoolmine reload|status §7- Admin");s.sendMessage("§8§m------------------");}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){return a.length==1?Arrays.asList("reload","status","help"):List.of();}
}


// ═══════════════════════════════════════
// BoosterCommand.java
// ═══════════════════════════════════════
class BoosterCommand implements CommandExecutor,TabCompleter{
    private final SchoolMine plugin;
    public BoosterCommand(SchoolMine p){plugin=p;}
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(!(sender instanceof Player player)){if(args.length>=1&&args[0].equalsIgnoreCase("clearall")){plugin.getBoosterManager().removeAllServerBoosters();sender.sendMessage("[SchoolMine] Removed all server boosters.");}else sender.sendMessage("Use in-game or: /booster clearall");return true;}
        if(args.length==0){if(!player.hasPermission("schoolmine.booster")){player.sendMessage(plugin.getConfigManager().getMessage("no-permission"));return true;}player.openInventory(new BoosterMenu(plugin,player,BoosterMenu.MenuPage.MAIN).build());return true;}
        switch(args[0].toLowerCase()){
            case"clear"->{if(!player.hasPermission("schoolmine.booster.admin")){player.sendMessage(plugin.getConfigManager().getMessage("no-permission"));return true;}if(args.length<2){player.sendMessage("§cUsage: /booster clear <player|server> [type]");return true;}if(args[1].equalsIgnoreCase("server")){clearBoosters(player,null,args.length>=3?args[2]:"all");}else{Player t=Bukkit.getOnlinePlayers().stream().filter(p->p.getName().equalsIgnoreCase(args[1])).findFirst().orElse(null);if(t==null){player.sendMessage("§cPlayer not found.");return true;}clearBoosters(player,t,args.length>=3?args[2]:"all");}}
            case"clearall"->{if(!player.hasPermission("schoolmine.booster.admin")){player.sendMessage(plugin.getConfigManager().getMessage("no-permission"));return true;}plugin.getBoosterManager().removeAllServerBoosters();for(Player p:Bukkit.getOnlinePlayers())plugin.getBoosterManager().removeAllBoosters(p.getUniqueId());player.sendMessage("§a[SchoolMine] Đã xóa toàn bộ booster.");}
            default->player.openInventory(new BoosterMenu(plugin,player,BoosterMenu.MenuPage.MAIN).build());}
        return true;}
    private void clearBoosters(Player admin,Player target,String typeArg){
        BoosterManager bm=plugin.getBoosterManager();
        if(target==null){switch(typeArg.toLowerCase()){case"3x3"->{bm.removeServerBooster(BoosterManager.BoosterType.MINING_3X3);admin.sendMessage("§aXóa server 3x3.");}case"multi"->{bm.removeServerBooster(BoosterManager.BoosterType.MULTI_BLOCK);admin.sendMessage("§aXóa server multi.");}case"nuker"->{bm.removeServerBooster(BoosterManager.BoosterType.NUKER);admin.sendMessage("§aXóa server nuker.");}case"killshard"->{bm.removeServerBooster(BoosterManager.BoosterType.KILL_SHARD);admin.sendMessage("§aXóa server killshard.");}default->{bm.removeAllServerBoosters();admin.sendMessage("§aXóa tất cả server booster.");}}}
        else{switch(typeArg.toLowerCase()){case"3x3"->{bm.removePlayerBooster(target.getUniqueId(),BoosterManager.BoosterType.MINING_3X3);admin.sendMessage("§aXóa 3x3 của "+target.getName());}case"multi"->{bm.removePlayerBooster(target.getUniqueId(),BoosterManager.BoosterType.MULTI_BLOCK);admin.sendMessage("§aXóa multi của "+target.getName());}case"nuker"->{bm.removePlayerBooster(target.getUniqueId(),BoosterManager.BoosterType.NUKER);admin.sendMessage("§aXóa nuker của "+target.getName());}case"killshard"->{bm.removePlayerBooster(target.getUniqueId(),BoosterManager.BoosterType.KILL_SHARD);admin.sendMessage("§aXóa killshard của "+target.getName());}default->{bm.removeAllBoosters(target.getUniqueId());admin.sendMessage("§aXóa tất cả booster của "+target.getName());}}}
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){if(a.length==1)return Arrays.asList("clear","clearall");if(a.length==2&&a[0].equalsIgnoreCase("clear"))return Arrays.asList("server","player");if(a.length==3&&a[0].equalsIgnoreCase("clear"))return Arrays.asList("3x3","multi","nuker","killshard","all");return List.of();}
}


// ═══════════════════════════════════════
// SchoolMine.java
// ═══════════════════════════════════════
final class SchoolMine extends JavaPlugin {
    private static SchoolMine instance;
    private ConfigManager configManager;
    private PlayerDataManager playerDataManager;
    private BlockRemoveManager blockRemoveManager;
    private BoosterManager boosterManager;
    private PlayerPointsAPI playerPointsAPI;
    private NukerTask nukerTask;
    private AutoMineTask autoMineTask;
    private YamlConfiguration boosterConfig;
    private File boosterConfigFile;

    @Override public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadBoosterConfig();
        configManager = new ConfigManager(this);
        playerDataManager = new PlayerDataManager(this);
        blockRemoveManager = new BlockRemoveManager(this);
        boosterManager = new BoosterManager(this);

        if (Bukkit.getPluginManager().getPlugin("PlayerPoints") != null) {
            playerPointsAPI = PlayerPoints.getInstance().getAPI();
            getLogger().info("Hooked into PlayerPoints!");
        } else {
            getLogger().warning("PlayerPoints not found! Booster purchase disabled.");
        }

        reg("autopickup", new AutoPickupCommand(this));
        reg("3x3", new Mining3x3Command(this));
        reg("autosmelt", new AutoSmeltCommand(this));
        reg("automine", new AutoMineCommand(this));
        reg("schoolmine", new SchoolMineCommand(this));
        reg("booster", new BoosterCommand(this));

        Bukkit.getPluginManager().registerEvents(new BlockBreakListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BlockPlaceListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BoosterMenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(new KillShardListener(this), this);

        nukerTask = new NukerTask(this); nukerTask.start();
        autoMineTask = new AutoMineTask(this); autoMineTask.start();

        int interval = configManager.getAutoSaveInterval();
        if (interval > 0) Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> playerDataManager.saveAll(), interval * 20L, interval * 20L);

        getLogger().info("SchoolMine v1.0.0 enabled!");
    }

    @Override public void onDisable() {
        if (playerDataManager != null) playerDataManager.saveAll();
        if (blockRemoveManager != null) blockRemoveManager.shutdown();
        if (boosterManager != null) boosterManager.shutdown();
        if (nukerTask != null) nukerTask.stop();
        if (autoMineTask != null) autoMineTask.stop();
        getLogger().info("SchoolMine disabled.");
    }

    private void reg(String name, Object cmd) {
        var c = getCommand(name); if (c == null) return;
        if (cmd instanceof org.bukkit.command.CommandExecutor ex) c.setExecutor(ex);
        if (cmd instanceof org.bukkit.command.TabCompleter tc) c.setTabCompleter(tc);
    }

    public void reload() {
        reloadConfig(); configManager.reload();
        playerDataManager.saveAll(); playerDataManager.clearCache();
        blockRemoveManager.reload(); reloadBoosterConfig();
    }

    private void loadBoosterConfig() {
        boosterConfigFile = new File(getDataFolder(), "booster.yml");
        if (!boosterConfigFile.exists()) { getDataFolder().mkdirs(); saveResource("booster.yml", false); }
        boosterConfig = YamlConfiguration.loadConfiguration(boosterConfigFile);
    }

    public void reloadBoosterConfig() { loadBoosterConfig(); }
    public YamlConfiguration getBoosterConfig() { if (boosterConfig == null) loadBoosterConfig(); return boosterConfig; }

    public static SchoolMine getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public BlockRemoveManager getBlockRemoveManager() { return blockRemoveManager; }
    public BoosterManager getBoosterManager() { return boosterManager; }
    public PlayerPointsAPI getPlayerPointsAPI() { return playerPointsAPI; }
}

