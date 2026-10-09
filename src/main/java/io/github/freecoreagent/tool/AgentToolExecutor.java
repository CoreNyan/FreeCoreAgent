package io.github.freecoreagent.tool;

import io.github.freecoreagent.service.SparkDiagnosticService;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes safe in-game tools (CoreProtect lookup, player info check, spark, inspect_config).
 * All cognitive brain operations (memory, skills, affection) are managed exclusively by CoreNyan.
 */
public final class AgentToolExecutor {
    private final JavaPlugin plugin;
    private final SafeConfigLens configLens;
    private final SparkDiagnosticService sparkService;
    private io.github.freecoreagent.ticket.TicketManager ticketManager;

    public AgentToolExecutor(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configLens = new SafeConfigLens(plugin);
        this.sparkService = new SparkDiagnosticService(plugin);
    }

    public void setTicketManager(io.github.freecoreagent.ticket.TicketManager ticketManager) {
        this.ticketManager = ticketManager;
    }

    public String executeTool(String toolName, String[] args) {
        return executeTool(toolName, args, false);
    }

    public String executeTool(String toolName, String[] args, boolean isOwner) {
        try {
            switch (toolName.toLowerCase().trim()) {
                case "query_block_history":
                    return handleQueryBlockHistory(args);
                case "query_container":
                    return handleQueryContainer(args);
                case "list_players":
                    return handleListPlayers(args);
                case "check_player":
                    return handleCheckPlayer(args);
                case "get_seen":
                    return handleGetSeen(args);
                case "create_ticket":
                    return handleCreateTicket(args);
                case "inspect_config":
                    return handleInspectConfig(args);
                case "profile_spark":
                    return handleProfileSpark(args);
                default:
                    return "未知游戏工具: " + toolName;
            }
        } catch (Exception e) {
            return "执行工具 " + toolName + " 发生异常: " + e.getMessage();
        }
    }

        private String handleListPlayers(String[] args) {
        var players = Bukkit.getOnlinePlayers();
        StringBuilder sb = new StringBuilder();
        sb.append("服务器当前在线玩家 (").append(players.size()).append(" 人):");
        if (players.isEmpty()) {
            sb.append(" 当前无玩家在线");
        } else {
            for (var p : players) {
                sb.append(" ").append(p.getName());
            }
        }
        return sb.toString();
    }
    private String handleProfileSpark(String[] args) {
        try {
            var future = sparkService.runSamplerAsync(15);
            return future.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "执行 Spark 采样异常: " + e.getMessage();
        }
    }

    private String handleInspectConfig(String[] args) {
        if (args.length < 2) return "用法: inspect_config(pluginOrServer, fileName)";
        String pluginOrServer = args[0].replace("\"", "").trim();
        String fileName = args[1].replace("\"", "").trim();
        return configLens.inspectConfig(pluginOrServer, fileName);
    }

    private String handleCreateTicket(String[] args) {
        if (ticketManager == null) return "工单管理服务未就绪。";
        if (args.length < 4) return "用法: create_ticket(playerName, title, rawUserMessage, adminDescription)";

        String playerName = args[0].replace("\"", "").trim();
        String title = args[1].replace("\"", "").trim();
        String rawMsg = args[2].replace("\"", "").trim();
        String adminDesc = args[3].replace("\"", "").trim();

        Player p = Bukkit.getPlayerExact(playerName);
        UUID uuid = p != null ? p.getUniqueId() : UUID.nameUUIDFromBytes(playerName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String server = plugin.getDataFolder().getAbsolutePath().contains("Tech") ? "technical" : "survival";

        var ticket = ticketManager.createOrUpdateTicket(playerName, uuid, server, title, rawMsg, adminDesc, "In-Game Tool");
        return "【工单已成功创建】编号: #" + ticket.getId() + "，标题: [" + ticket.getTitle() + "]。已自动写入待办面板。";
    }

    private String handleCheckPlayer(String[] args) {
        if (args.length < 1) return "用法: check_player(playerName)";
        String name = args[0].replace("\"", "").trim();
        Player p = Bukkit.getPlayerExact(name);
        OfflinePlayer op = (p != null) ? p : Bukkit.getOfflinePlayer(name);

        StringBuilder sb = new StringBuilder();
        sb.append("玩家档案: ").append(name).append("\n");
        sb.append("- 状态: ").append(p != null ? "在线" : "离线").append("\n");
        if (p != null) {
            sb.append("- 所在世界: ").append(p.getWorld().getName()).append("\n");
            sb.append("- 生命值: ").append((int) p.getHealth()).append("/").append((int) p.getMaxHealth()).append("\n");
            sb.append("- 经验等级: ").append(p.getLevel()).append("\n");
        }

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            Economy econ = rsp.getProvider();
            sb.append("- 账户财富: ").append(econ.getBalance(op)).append(" H币\n");
        }
        return sb.toString().trim();
    }

    private String handleGetSeen(String[] args) {
        if (args.length < 1) return "用法: get_seen(playerName)";
        String name = args[0].replace("\"", "").trim();
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (op.isOnline()) {
            return "玩家 " + name + " 当前正活跃在线！";
        }
        long last = op.getLastSeen();
        if (last <= 0) {
            return "未在服务器档案中找到玩家 " + name + " 的历史登录记录。";
        }
        long diff = System.currentTimeMillis() - last;
        long minutes = diff / (60 * 1000);
        long hours = minutes / 60;
        long days = hours / 24;
        String dur = days > 0 ? (days + " 天前") : (hours > 0 ? (hours + " 小时前") : (minutes + " 分钟前"));
        return "玩家 " + name + " 上次在线时间为: " + dur;
    }

    private String handleQueryBlockHistory(String[] args) {
        if (args.length < 4) return "用法: query_block_history(world, x, y, z)";
        String worldName = args[0].replace("\"", "").trim();
        int x = Integer.parseInt(args[1].trim());
        int y = Integer.parseInt(args[2].trim());
        int z = Integer.parseInt(args[3].trim());

        var world = Bukkit.getWorld(worldName);
        if (world == null) return "未知世界: " + worldName;

        CoreProtectAPI cp = getCoreProtect();
        if (cp == null) return "CoreProtect 插件未安装或不可用。";

        Block block = world.getBlockAt(x, y, z);
        List<String[]> lookup = cp.blockLookup(block, 86400 * 7); // 7天
        if (lookup == null || lookup.isEmpty()) return "该方块过去 7 天内无任何修改记录。";

        StringBuilder sb = new StringBuilder("方块修改历史记录:\n");
        int count = 0;
        for (String[] row : lookup) {
            CoreProtectAPI.ParseResult result = cp.parseResult(row);
            sb.append(String.format("- [%s] 玩家 %s 执行了 %s (方块: %s)\n",
                    result.getTime(), result.getPlayer(), result.getActionString(), result.getType().name()));
            if (++count >= 5) break;
        }
        return sb.toString().trim();
    }

    private String handleQueryContainer(String[] args) {
        if (args.length < 4) return "用法: query_container(world, x, y, z)";
        String worldName = args[0].replace("\"", "").trim();
        int x = Integer.parseInt(args[1].trim());
        int y = Integer.parseInt(args[2].trim());
        int z = Integer.parseInt(args[3].trim());

        var world = Bukkit.getWorld(worldName);
        if (world == null) return "未知世界: " + worldName;

        Block block = world.getBlockAt(x, y, z);
        if (!(block.getState() instanceof Container container)) {
            return "坐标 (" + x + "," + y + "," + z + ") 处的方块不是容器。";
        }

        Inventory inv = container.getInventory();
        StringBuilder sb = new StringBuilder("容器内主要物品清单:\n");
        int found = 0;
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                sb.append("- ").append(item.getType().name()).append(" x").append(item.getAmount()).append("\n");
                if (++found >= 10) break;
            }
        }
        if (found == 0) return "该容器内没有任何物品 (空容器)。";
        return sb.toString().trim();
    }

    private CoreProtectAPI getCoreProtect() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (plugin instanceof CoreProtect cp) {
            CoreProtectAPI api = cp.getAPI();
            return api.isEnabled() ? api : null;
        }
        return null;
    }
}