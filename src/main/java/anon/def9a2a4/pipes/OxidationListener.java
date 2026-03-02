package anon.def9a2a4.pipes;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import anon.def9a2a4.pipes.PipeManager.PipeData;

import java.util.*;

/**
 * 处理铜管的随机氧化、涂蜡（蜜脾右键）和打磨（斧头右键）机制，
 * 尽可能模仿原版铜块的行为：粒子效果、声音、物品消耗、工具耐久损耗。
 */
public class OxidationListener implements Listener {

    private final PipesPlugin plugin;
    private final WeakHashMap<World, PipeManager> pipeManagerMap;
    private final Random random = new Random();

    // 配置加载后填充
    private final Map<String, String> oxidationTransitions = new HashMap<>();  // 铜管 → 氧化铜管
    private final Map<String, String> waxTransitions = new HashMap<>();         // 铜管 → 涂蜡铜管
    private final Map<String, String> scrapeWaxTransitions = new HashMap<>();   // 涂蜡铜管 → 铜管（去蜡）
    private final Map<String, String> scrapeOxidationTransitions = new HashMap<>(); // 氧化铜管 → 铜管（打磨）

    private int checkIntervalTicks = 1200;
    private int chanceNumerator = 21;
    private int chanceDenominator = 1096;
    private boolean enabled = true;

    private BukkitTask oxidationTask;

    public OxidationListener(PipesPlugin plugin, WeakHashMap<World, PipeManager> pipeManagerMap) {
        this.plugin = plugin;
        this.pipeManagerMap = pipeManagerMap;
        loadConfig();
        startOxidationTask();
    }

    /** 重新加载配置（/pipes reload 时调用）。 */
    public void reload() {
        loadConfig();
        stopOxidationTask();
        startOxidationTask();
    }

    /** 关闭时停止氧化任务。 */
    public void shutdown() {
        stopOxidationTask();
    }

    private void loadConfig() {
        oxidationTransitions.clear();
        waxTransitions.clear();
        scrapeWaxTransitions.clear();
        scrapeOxidationTransitions.clear();

        var config = plugin.getConfig();
        enabled = config.getBoolean("oxidation.enabled", true);
        checkIntervalTicks = config.getInt("oxidation.check-interval-ticks", 1200);
        chanceNumerator = config.getInt("oxidation.chance-numerator", 21);
        chanceDenominator = config.getInt("oxidation.chance-denominator", 1096);

        loadMap(config.getConfigurationSection("oxidation.transitions"), oxidationTransitions);
        loadMap(config.getConfigurationSection("oxidation.wax"), waxTransitions);
        loadMap(config.getConfigurationSection("oxidation.scrape-wax"), scrapeWaxTransitions);
        loadMap(config.getConfigurationSection("oxidation.scrape-oxidation"), scrapeOxidationTransitions);

        plugin.getLogger().info("[Oxidation] Loaded: " +
                oxidationTransitions.size() + " oxidation transition(s), " +
                waxTransitions.size() + " wax transition(s).");
    }

    private void loadMap(ConfigurationSection section, Map<String, String> target) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null && !value.isEmpty()) {
                target.put(key, value);
            }
        }
    }

    private void startOxidationTask() {
        if (!enabled || oxidationTransitions.isEmpty()) return;

        final int interval = Math.max(1, checkIntervalTicks);
        final int num = chanceNumerator;
        final int denom = Math.max(1, chanceDenominator);

        oxidationTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (PipeManager manager : pipeManagerMap.values()) {
                    manager.tickOxidation(oxidationTransitions, num, denom, random);
                }
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    private void stopOxidationTask() {
        if (oxidationTask != null && !oxidationTask.isCancelled()) {
            oxidationTask.cancel();
        }
        oxidationTask = null;
    }

    // ===================== 玩家交互事件 =====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 仅处理主手右键方块
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return;

        PipeManager manager = pipeManagerMap.get(block.getWorld());
        if (manager == null) return;

        PipeData pipeData = manager.getPipeData(block.getLocation());
        if (pipeData == null) return;

        Player player = event.getPlayer();
        ItemStack handItem = event.getItem();
        if (handItem == null || handItem.getType() == Material.AIR) return;

        String variantId = pipeData.variant().getId();
        Material handType = handItem.getType();

        // ── 蜜脾涂蜡 ──────────────────────────────────────────────────
        if (handType == Material.HONEYCOMB) {
            String waxedId = waxTransitions.get(variantId);
            if (waxedId == null) return;

            PipeVariant waxedVariant = plugin.getVariantRegistry().getVariant(waxedId);
            if (waxedVariant == null) return;

            event.setCancelled(true);
            manager.convertPipeVariant(block.getLocation(), waxedVariant);

            // 非创造模式消耗蜜脾
            if (player.getGameMode() != GameMode.CREATIVE) {
                handItem.setAmount(handItem.getAmount() - 1);
            }

            // 原版涂蜡效果
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            block.getWorld().spawnParticle(Particle.WAX_ON, center, 7, 0.3, 0.3, 0.3, 0);
            block.getWorld().playSound(block.getLocation(), Sound.ITEM_HONEYCOMB_WAX_ON, 1.0f, 1.0f);
            player.swingMainHand();
        }

        // ── 斧头打磨 ──────────────────────────────────────────────────
        else if (isAxe(handType)) {
            // 优先尝试去蜡
            String unwaxedId = scrapeWaxTransitions.get(variantId);
            if (unwaxedId != null) {
                PipeVariant unwaxedVariant = plugin.getVariantRegistry().getVariant(unwaxedId);
                if (unwaxedVariant == null) return;

                event.setCancelled(true);
                manager.convertPipeVariant(block.getLocation(), unwaxedVariant);

                Location center = block.getLocation().add(0.5, 0.5, 0.5);
                block.getWorld().spawnParticle(Particle.WAX_OFF, center, 7, 0.3, 0.3, 0.3, 0);
                block.getWorld().playSound(block.getLocation(), Sound.ITEM_AXE_WAX_OFF, 1.0f, 1.0f);
                player.swingMainHand();
                damageAxe(player, handItem);
                return;
            }

            // 再尝试去氧化
            String scrapedId = scrapeOxidationTransitions.get(variantId);
            if (scrapedId != null) {
                PipeVariant scrapedVariant = plugin.getVariantRegistry().getVariant(scrapedId);
                if (scrapedVariant == null) return;

                event.setCancelled(true);
                manager.convertPipeVariant(block.getLocation(), scrapedVariant);

                Location center = block.getLocation().add(0.5, 0.5, 0.5);
                block.getWorld().spawnParticle(Particle.SCRAPE, center, 7, 0.3, 0.3, 0.3, 0);
                block.getWorld().playSound(block.getLocation(), Sound.ITEM_AXE_SCRAPE, 1.0f, 1.0f);
                player.swingMainHand();
                damageAxe(player, handItem);
            }
        }
    }

    private boolean isAxe(Material material) {
        return switch (material) {
            case WOODEN_AXE, STONE_AXE, IRON_AXE, GOLDEN_AXE, DIAMOND_AXE, NETHERITE_AXE -> true;
            default -> false;
        };
    }

    /** 对斧头施加 1 点耐久损耗（遵循原版不死图腾、无坚不摧魔咒）。 */
    private void damageAxe(Player player, ItemStack axe) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        axe.damage(1, player);
    }
}
