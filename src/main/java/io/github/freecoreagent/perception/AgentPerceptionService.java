package io.github.freecoreagent.perception;

import io.github.freecoreagent.redis.AgentRedisBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Perception service allowing CoreNyan to perceive live in-game context:
 * player titles/tags, world locations, server TPS, loaded plugins,
 * and cross-server network-wide online players via Redis!
 */
public final class AgentPerceptionService {
    private final JavaPlugin plugin;
    private final boolean hasPapi;
    private AgentRedisBridge redisBridge;

    public AgentPerceptionService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.hasPapi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    public void setRedisBridge(AgentRedisBridge redisBridge) {
        this.redisBridge = redisBridge;
    }

    public record PlayerProfileInfo(String name, String title, String worldName, boolean isOp) {}

    public PlayerProfileInfo getPlayerInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return new PlayerProfileInfo("CONSOLE", "服主指挥官", "后台控制中心", true);
        }

        String title = "无特殊称号";
        if (hasPapi) {
            try {
                String tag = PlaceholderAPI.setPlaceholders(player, "%fotiatags_tag%");
                if (tag.isBlank() || tag.equals("%fotiatags_tag%")) {
                    tag = PlaceholderAPI.setPlaceholders(player, "%vault_prefix%");
                }
                if (tag.isBlank() || tag.equals("%vault_prefix%")) {
                    tag = PlaceholderAPI.setPlaceholders(player, "%luckperms_prefix%");
                }
                if (!tag.isBlank() && !tag.startsWith("%")) {
                    title = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', tag)).trim();
                }
            } catch (Exception ignored) {}
        }

        org.bukkit.World.Environment env = player.getWorld().getEnvironment();
        String worldName;
        if (env == org.bukkit.World.Environment.NETHER) {
            worldName = "下界 (地狱)";
        } else if (env == org.bukkit.World.Environment.THE_END) {
            worldName = "末地";
        } else {
            worldName = player.getWorld().getName().contains("lobby") ? "主城大厅" : "主世界";
        }

        boolean isBedrock = isBedrockPlayer(player);
        String clientPlatform = isBedrock ? "基岩版手机端 (Bedrock)" : "Java电脑端 (PC)";

        return new PlayerProfileInfo(player.getName(), title.isEmpty() ? "普通居民" : title, worldName + " [" + clientPlatform + "]", player.isOp());
    }

    public static boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        // 1. Check Floodgate API
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
                org.geysermc.floodgate.api.FloodgateApi fg = org.geysermc.floodgate.api.FloodgateApi.getInstance();
                if (fg.isFloodgatePlayer(player.getUniqueId()) || fg.isFloodgateId(player.getUniqueId())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        // 2. Check Floodgate UUID pattern: 00000000-0000-0000-0009-xxxxxxxxxxxx
        // In Geyser/Floodgate architecture, all bedrock player UUIDs have their MSB set to 00000000-0000-0000-0009
        long msb = player.getUniqueId().getMostSignificantBits();
        if (msb == 9L) {
            return true;
        }

        // 3. Check username prefix or character
        if (player.getName().startsWith(".") || player.getName().startsWith("*")) {
            return true;
        }

        return false;
    }

    public String getLoadedPluginsSummary() {
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        List<String> enabledList = new ArrayList<>();
        for (Plugin p : plugins) {
            if (p.isEnabled()) {
                enabledList.add(p.getName());
            }
        }
        return "当前服务器已启用的插件清单 (" + enabledList.size() + "个): " + String.join(", ", enabledList);
    }

    public String getServerLiveStatusSummary() {
        // Cross-server network players via Redis
        int totalNetworkCount = 0;
        StringBuilder networkSummary = new StringBuilder();

        if (redisBridge != null) {
            Map<String, List<String>> networkMap = redisBridge.getNetworkOnlinePlayers();
            if (!networkMap.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : networkMap.entrySet()) {
                    String serverName = entry.getKey().equalsIgnoreCase("technical") ? "生电服" : "生存服";
                    List<String> pList = entry.getValue();
                    totalNetworkCount += pList.size();
                    networkSummary.append("\n  - ").append(serverName).append(" (").append(pList.size()).append("人): ")
                            .append(String.join(", ", pList));
                }
            }
        }

        // Fallback to local players if redis returned empty
        if (totalNetworkCount == 0) {
            List<String> localNames = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                localNames.add(p.getName());
            }
            totalNetworkCount = localNames.size();
            networkSummary.append(localNames.isEmpty() ? "当前没有玩家在线" : String.join(", ", localNames));
        }

        // TPS
        double[] tpsArr = Bukkit.getTPS();
        double currentTps = Math.min(20.0, Math.round(tpsArr[0] * 10.0) / 10.0);

        // Day/night
        World mainWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        String timeStr = "白天";
        if (mainWorld != null) {
            long time = mainWorld.getTime();
            if (time >= 13000 && time <= 23000) {
                timeStr = "夜晚";
            } else if (time >= 12000) {
                timeStr = "傍晚";
            } else {
                timeStr = "白天";
            }
        }

        // World seeds summary
        StringBuilder seedsSummary = new StringBuilder();
        for (World w : Bukkit.getWorlds()) {
            seedsSummary.append("\n  - 世界 [").append(w.getName()).append("]: 种子=").append(w.getSeed());
        }

        return "- 全服总在线人数: " + totalNetworkCount + " 人"
                + "\n- 各子服在线名单:" + networkSummary
                + "\n- 本地当前 TPS: " + currentTps + " (最高20.0)"
                + "\n- 本地时间: " + timeStr
                + "\n- 本服世界种子数据:" + seedsSummary
                + "\n- " + getLoadedPluginsSummary();
    }
}
