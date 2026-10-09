package io.github.freecoreagent.tool;

import io.github.freecoreagent.affection.PlayerAffectionManager;
import io.github.freecoreagent.memory.VaultMemoryManager;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import io.github.freecoreagent.service.SparkDiagnosticService;
import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Executes safe in-game tools (CoreProtect lookup, player info check, memory crystallization).
 * Strictly forbids dangerous OP commands.
 */
public final class AgentToolExecutor {
    private final JavaPlugin plugin;
    private final VaultMemoryManager memory;
    private final PlayerAffectionManager affection;
    private final SafeConfigLens configLens;
    private final SparkDiagnosticService sparkService;
    private io.github.freecoreagent.skill.SkillManager skillManager;
    private io.github.freecoreagent.ticket.TicketManager ticketManager;
    private CoreProtectAPI coreProtectAPI = null;
    private Economy economy = null;

    public AgentToolExecutor(JavaPlugin plugin, VaultMemoryManager memory, PlayerAffectionManager affection) {
        this.plugin = plugin;
        this.memory = memory;
        this.affection = affection;
        this.configLens = new SafeConfigLens(plugin);
        this.sparkService = new SparkDiagnosticService(plugin);
        setupCoreProtect();
        setupEconomy();
    }

    public void setSkillManager(io.github.freecoreagent.skill.SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    public void setTicketManager(io.github.freecoreagent.ticket.TicketManager ticketManager) {
        this.ticketManager = ticketManager;
    }

    private void setupCoreProtect() {
        try {
            Plugin cp = Bukkit.getPluginManager().getPlugin("CoreProtect");
            if (cp instanceof CoreProtect coreProtect) {
                this.coreProtectAPI = coreProtect.getAPI();
                if (this.coreProtectAPI != null && this.coreProtectAPI.isEnabled()) {
                    plugin.getLogger().info("AgentToolExecutor: CoreProtect API 成功接入！");
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("CoreProtect API 接入失败: " + t.getMessage());
        }
    }

    private void setupEconomy() {
        try {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.economy = rsp.getProvider();
                plugin.getLogger().info("AgentToolExecutor: Vault Economy 经济服务接入！");
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Executes tool call from LLM format: [TOOL: name(arg1, arg2)]
     */
    public String executeTool(String toolCall) {
        return executeTool(toolCall, false);
    }

    public java.util.concurrent.CompletableFuture<String> executeToolAsync(String toolCall, boolean isOwner) {
        String call = toolCall.trim();
        if (call.startsWith("[TOOL:") && call.endsWith("]")) {
            call = call.substring(6, call.length() - 1).trim();
        }
        int paren = call.indexOf('(');
        if (paren < 0) return java.util.concurrent.CompletableFuture.completedFuture("错误: 无效工具语法");
        String toolName = call.substring(0, paren).trim().toLowerCase();
        String argsRaw = call.substring(paren + 1, call.length() - 1).trim();
        String[] args = argsRaw.isEmpty() ? new String[0] : argsRaw.split(",\\s*");

        try {
            if (toolName.equals("spark") || toolName.equals("profile_spark")) {
                if (args.length > 0 && args[0].replace("\"", "").trim().equalsIgnoreCase("stop")) {
                    sparkService.stopSampler();
                    return java.util.concurrent.CompletableFuture.completedFuture("【Spark采样已终止】已停止性能分析器！");
                }
                int timeout = 15;
                if (args.length > 0) {
                    String a0 = args[0].replace("\"", "").trim();
                    if (a0.equalsIgnoreCase("start")) {
                        if (args.length > 1) {
                            try { timeout = Integer.parseInt(args[1].replace("\"", "").trim()); } catch (Exception ignored) {}
                        }
                    } else {
                        try { timeout = Integer.parseInt(a0); } catch (Exception ignored) {}
                    }
                }
                if (args.length > 0 && (args[0].replace("\"", "").trim().equalsIgnoreCase("start") || Character.isDigit(args[0].replace("\"", "").trim().charAt(0)))) {
                    return sparkService.runSamplerAsync(timeout);
                }
                return java.util.concurrent.CompletableFuture.completedFuture(sparkService.getInstantHealthSnapshot());
            }

            return java.util.concurrent.CompletableFuture.completedFuture(executeTool(toolCall, isOwner));
        } catch (Exception e) {
            return java.util.concurrent.CompletableFuture.completedFuture("工具执行异常: " + e.getMessage());
        }
    }

    public String executeTool(String toolCall, boolean isOwner) {
        String call = toolCall.trim();
        if (call.startsWith("[TOOL:") && call.endsWith("]")) {
            call = call.substring(6, call.length() - 1).trim();
        }
        int paren = call.indexOf('(');
        if (paren < 0) return "错误: 无效工具语法";
        String toolName = call.substring(0, paren).trim().toLowerCase();
        String argsRaw = call.substring(paren + 1, call.length() - 1).trim();
        String[] args = argsRaw.isEmpty() ? new String[0] : argsRaw.split(",\\s*");

        try {
            switch (toolName) {
                case "lookup_block":
                case "lookup_chest":
                    return handleCoreProtectLookup(args);
                case "check_player":
                    return handleCheckPlayer(args);
                case "get_balance":
                case "bal":
                    return handleGetBalance(args);
                case "get_seen":
                case "seen":
                    return handleGetSeen(args);
                case "modify_affection":
                    return handleModifyAffection(args);
                case "record_memory":
                    return handleRecordMemory(args);
                case "create_ticket":
                    return handleCreateTicket(args);
                case "inspect_config":
                    return handleInspectConfig(args);
                case "profile_spark":
                case "spark":
                    return handleProfileSpark(args);
                case "update_skill":
                    return handleUpdateSkill(args, isOwner);
                default:
                    return "未知工具: " + toolName;
            }
        } catch (Exception e) {
            return "工具执行异常: " + e.getMessage();
        }
    }

    private String handleCoreProtectLookup(String[] args) {
        if (coreProtectAPI == null || !coreProtectAPI.isEnabled()) {
            return "CoreProtect 服务不可用或未启用。";
        }
        if (args.length < 4) {
            return "参数不足。用法: lookup_block(world, x, y, z)";
        }
        String worldName = args[0].replace("\"", "").trim();
        int x = Integer.parseInt(args[1].trim());
        int y = Integer.parseInt(args[2].trim());
        int z = Integer.parseInt(args[3].trim());

        World world = Bukkit.getWorld(worldName);
        if (world == null) world = Bukkit.getWorlds().get(0);
        Location loc = new Location(world, x, y, z);

        List<String[]> lookup = coreProtectAPI.blockLookup(loc.getBlock(), 86400); // last 24h
        if (lookup == null || lookup.isEmpty()) {
            return "坐标 (" + x + "," + y + "," + z + ") 在24小时内未发现任何方块/容器修改记录。";
        }
        StringBuilder sb = new StringBuilder("CoreProtect 记录(近24h):\n");
        int count = Math.min(3, lookup.size());
        for (int i = 0; i < count; i++) {
            CoreProtectAPI.ParseResult res = coreProtectAPI.parseResult(lookup.get(i));
            sb.append("- 玩家: ").append(res.getPlayer())
              .append(", 动作: ").append(res.getActionString())
              .append(", 目标: ").append(res.getType().name())
              .append(", 发生于: ").append(res.getTime()).append("秒前\n");
        }
        return sb.toString().trim();
    }

    private String handleGetBalance(String[] args) {
        if (args.length < 1) return "用法: /bal <玩家名>";
        String name = args[0].replace("\"", "").trim();
        Player p = Bukkit.getPlayerExact(name);
        OfflinePlayer op = (p != null) ? p : Bukkit.getOfflinePlayer(name);
        if (economy == null) {
            return "【H币查询】\n当前经济系统未连接，无法查询余额。";
        }
        double balance = economy.getBalance(op);
        StringBuilder sb = new StringBuilder();
        sb.append("【自由核心 · 玩家资产查询】\n");
        sb.append("玩家: ").append(name).append("\n");
        sb.append("状态: ").append(p != null ? "🟢 在线" : "⚪ 离线").append("\n");
        sb.append("H币余额: ").append(String.format(java.util.Locale.ROOT, "%.2f", balance)).append(" H币");
        return sb.toString();
    }

    private String handleGetSeen(String[] args) {
        if (args.length < 1) return "用法: /seen <玩家名>";
        String name = args[0].replace("\"", "").trim();
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            long loginTime = p.getLastLogin();
            String loginStr = loginTime > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(loginTime)) : "刚刚";
            return "【自由核心 · 玩家上线记录】\n" +
                   "玩家: " + name + "\n" +
                   "状态: 🟢 当前在线中！\n" +
                   "所在世界: " + p.getWorld().getName() + "\n" +
                   "本次上线时间: " + loginStr + "\n" +
                   "网络延迟: " + p.getPing() + "ms";
        }

        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        long lastPlayed = op.getLastPlayed();
        if (lastPlayed <= 0) {
            lastPlayed = op.getLastLogin();
        }

        if (lastPlayed <= 0) {
            return "【自由核心 · 玩家上线记录】\n" +
                   "玩家: " + name + "\n" +
                   "状态: ⚪ 未找到该玩家的登录历史记录。";
        }

        long now = System.currentTimeMillis();
        long diffMs = Math.max(0, now - lastPlayed);
        long diffSec = diffMs / 1000;
        long days = diffSec / 86400;
        long hours = (diffSec % 86400) / 3600;
        long mins = (diffSec % 3600) / 60;

        StringBuilder rel = new StringBuilder();
        if (days > 0) rel.append(days).append("天");
        if (hours > 0) rel.append(hours).append("小时");
        if (mins > 0 || (days == 0 && hours == 0)) rel.append(Math.max(1, mins)).append("分钟");
        rel.append("前");

        String exactDate = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(lastPlayed));
        return "【自由核心 · 玩家上线记录】\n" +
               "玩家: " + name + "\n" +
               "状态: 🔴 离线\n" +
               "上次在线时间: " + exactDate + " (" + rel.toString() + ")";
    }

    private String handleCheckPlayer(String[] args) {
        if (args.length < 1 || args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("all") 
                || args[0].equalsIgnoreCase("online") || args[0].contains("名单") || args[0].contains("所有人") || args[0].contains("在线")) {
            java.util.Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            if (online.isEmpty()) {
                return "当前全服在线玩家: 0 人 (当前空服中)";
            }
            StringBuilder sb = new StringBuilder("当前全服在线玩家 (" + online.size() + "人):\n");
            for (Player pl : online) {
                sb.append("- ").append(pl.getName())
                  .append(" [世界: ").append(pl.getWorld().getName()).append("]")
                  .append(" (Ping: ").append(pl.getPing()).append("ms)\n");
            }
            return sb.toString().trim();
        }
        String name = args[0].replace("\"", "").trim();
        Player p = Bukkit.getPlayerExact(name);
        OfflinePlayer op = (p != null) ? p : Bukkit.getOfflinePlayer(name);

        StringBuilder sb = new StringBuilder("玩家档案 [" + name + "]:\n");
        sb.append("- 状态: ").append(p != null ? "在线 (所在世界: " + p.getWorld().getName() + ", Ping: " + p.getPing() + "ms)" : "离线").append("\n");
        if (economy != null) {
            sb.append("- H币余额: ").append(String.format("%.2f", economy.getBalance(op))).append("\n");
        }
        sb.append("- 好感度羁绊: ").append(affection.getAffection(op.getUniqueId())).append(" 点(")
          .append(affection.getAffectionTierDesc(op.getUniqueId(), false)).append(")\n");
        return sb.toString().trim();
    }

    private String handleModifyAffection(String[] args) {
        if (args.length < 2) return "用法: modify_affection(playerName, delta)";
        String name = args[0].replace("\"", "").trim();
        int delta = Integer.parseInt(args[1].trim());
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        affection.modifyAffection(op.getUniqueId(), delta);
        return "玩家 " + name + " 好感度调整: " + (delta > 0 ? "+" + delta : delta) + "，当前为 " + affection.getAffection(op.getUniqueId());
    }

    private String handleCreateTicket(String[] args) {
        try {
            if (ticketManager == null) return "工单系统未启用。";
            if (args.length < 2) return "用法: create_ticket(playerName, title, rawUserMessage, adminDescription)";

        String name = args[0].replace("\"", "").trim();
        String title = "问题反馈";
        String rawMsg = "玩家在公屏所述内容";
        String desc = "";

        if (args.length >= 4) {
            title = args[1].replace("\"", "").trim();
            rawMsg = args[2].replace("\"", "").trim();
            StringBuilder fullDesc = new StringBuilder(args[3].replace("\"", "").trim());
            for (int i = 4; i < args.length; i++) {
                fullDesc.append(", ").append(args[i].replace("\"", "").trim());
            }
            desc = fullDesc.toString();
        } else if (args.length == 3) {
            title = args[1].replace("\"", "").trim();
            desc = args[2].replace("\"", "").trim();
            rawMsg = desc;
        } else {
            desc = args[1].replace("\"", "").trim();
            title = desc.length() <= 10 ? desc : desc.substring(0, 10) + "...";
            rawMsg = desc;
        }

        Player p = Bukkit.getPlayerExact(name);
        UUID uid = (p != null) ? p.getUniqueId() : Bukkit.getOfflinePlayer(name).getUniqueId();
        String serverNode = plugin.getDataFolder().getAbsolutePath().contains("Tech") ? "technical" : "survival";

        StringBuilder envSnapshot = new StringBuilder();
        if (p != null && p.isOnline()) {
            boolean isBedrock = io.github.freecoreagent.perception.AgentPerceptionService.isBedrockPlayer(p);

            envSnapshot.append("- 客户端平台: ").append(isBedrock ? "基岩版手机端 (Bedrock)" : "Java电脑端 (PC)")
                       .append(" (Ping: ").append(p.getPing()).append("ms)\n")
                       .append("- 所在子服: ").append(serverNode.equalsIgnoreCase("technical") ? "生电服 (Technical)" : "生存服 (Survival)").append("\n")
                       .append("- 所在世界: ").append(p.getWorld().getName()).append(" (环境: ").append(p.getWorld().getEnvironment().name()).append(")\n")
                       .append("- 详细坐标: X=").append(String.format("%.1f", p.getLocation().getX()))
                       .append(", Y=").append(String.format("%.1f", p.getLocation().getY()))
                       .append(", Z=").append(String.format("%.1f", p.getLocation().getZ())).append("\n")
                       .append("- 游戏状态: ").append(p.getGameMode().name())
                       .append(" (生命: ").append(String.format("%.1f", p.getHealth())).append("/").append(p.getMaxHealth())
                       .append(", 饱食度: ").append(p.getFoodLevel()).append("/20)\n")
                       .append("- 主手物品: ").append(p.getInventory().getItemInMainHand().getType().name())
                       .append(" x").append(p.getInventory().getItemInMainHand().getAmount()).append("\n");

            if (economy != null) {
                envSnapshot.append("- H币余额: ").append(String.format("%.2f", economy.getBalance(p))).append(" H币\n");
            }

            double currentTps = Math.min(20.0, Math.round(Bukkit.getTPS()[0] * 10.0) / 10.0);
            envSnapshot.append("- 当前服务器TPS: ").append(currentTps).append("\n")
                       .append("- 所在区块实体数: ").append(p.getLocation().getChunk().getEntities().length).append(" 个\n");
        } else {
            envSnapshot.append("- 玩家状态: 离线 (UUID: ").append(uid).append(")\n");
            if (economy != null) {
                envSnapshot.append("- H币余额: ").append(String.format("%.2f", economy.getBalance(Bukkit.getOfflinePlayer(uid)))).append(" H币\n");
            }
        }

            var ticket = ticketManager.createOrUpdateTicket(name, uid, serverNode, title, rawMsg, desc, envSnapshot.toString().trim());
            plugin.getLogger().info("[Ticket Handled] #" + ticket.getId() + " by " + name + " (Title: " + title + ")");
            return "成功处理工单 #" + ticket.getId() + " [" + title + "]！已独立拆分诊断与快照，服主可通过 /fca issues 查看。";
        } catch (Exception e) {
            return "创建工单失败: " + e.getMessage();
        }
    }

    private String handleProfileSpark(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            sparkService.stopSampler();
            return "【Spark采样已终止】已向服务器发送停止分析指令！";
        }

        // If args has a number or "start"
        int timeout = 15;
        if (args.length > 0) {
            String a0 = args[0].replace("\"", "").trim();
            if (a0.equalsIgnoreCase("start")) {
                if (args.length > 1) {
                    try { timeout = Integer.parseInt(args[1].replace("\"", "").trim()); } catch (Exception ignored) {}
                }
            } else {
                try { timeout = Integer.parseInt(a0); } catch (Exception ignored) {}
            }
        }

        // If explicitly asked to run sampler
        if (args.length > 0 && (args[0].equalsIgnoreCase("start") || Character.isDigit(args[0].charAt(0)))) {
            try {
                return sparkService.runSamplerAsync(timeout).get(timeout + 10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                return "【Spark采样异常】" + e.getMessage() + "\n" + sparkService.getInstantHealthSnapshot();
            }
        }

        // Otherwise return instant snapshot
        return sparkService.getInstantHealthSnapshot();
    }

    private String handleUpdateSkill(String[] args, boolean isOwner) {
        if (!isOwner) {
            return "【权限不足】只有服主（Owner）才允许让小可创建或更新技能文档！";
        }
        if (args.length < 2) {
            return "用法: update_skill(fileName, content)";
        }
        String fileName = args[0].replace("\"", "").trim();
        if (!fileName.endsWith(".md") && !fileName.endsWith(".txt")) {
            fileName = fileName + ".md";
        }

        // Must save under skills/extra/ to avoid being overwritten by GitHub doc sync
        File skillsDir = new File(plugin.getDataFolder(), "skills");
        File extraDir = new File(skillsDir, "extra");
        if (!extraDir.exists()) extraDir.mkdirs();

        File targetFile = new File(extraDir, fileName);

        StringBuilder sb = new StringBuilder(args[1].replace("\"", "").trim());
        for (int i = 2; i < args.length; i++) {
            sb.append(", ").append(args[i].replace("\"", "").trim());
        }
        String content = sb.toString();

        try {
            java.nio.file.Files.writeString(targetFile.toPath(), content, java.nio.charset.StandardCharsets.UTF_8);
            if (skillManager != null) {
                skillManager.load();
            }
            plugin.getLogger().info("[Skill Updated] 服主更新了技能卡片: " + targetFile.getName());
            return "成功为服主更新技能知识卡: " + targetFile.getName() + "！已自动生效至小可技能大脑。";
        } catch (Exception e) {
            return "更新技能知识卡失败: " + e.getMessage();
        }
    }

    private String handleInspectConfig(String[] args) {
        if (args.length < 1) return "用法: inspect_config(targetPluginOrServer, optionalFileName)";
        String target = args[0].replace("\"", "").trim();
        String file = (args.length > 1) ? args[1].replace("\"", "").trim() : "config.yml";
        return configLens.inspectConfig(target, file);
    }

    private String handleRecordMemory(String[] args) {
        if (args.length < 3) return "用法: record_memory(playerName, title, content)";
        String name = args[0].replace("\"", "").trim();
        String title = args[1].replace("\"", "").trim();
        String content = args[2].replace("\"", "").trim();
        boolean ok = memory.recordAutonomousMemory(name, title, "玩家档案,好感羁绊", content);
        return ok ? "成功沉淀至记忆库！" : "记忆库保存失败或达到当日上限。";
    }

    public String getToolsDescriptionPrompt() {
        return "【小可的OP与专属动作工具箱（Tool Use）】\n"
             + "当你确实需要查询真实游戏数据或沉淀记忆时，可在回复开头包含工具调用指令（单独一行）：\n"
             + "- [TOOL: lookup_block(world, x, y, z)] : 查询方块或箱子被谁动过/偷过（24小时内）\n"
             + "- [TOOL: check_player(playerName)] : 查询玩家的在线状态、所在世界、财富H币和好感度\n"
             + "- [TOOL: modify_affection(playerName, delta)] : 调整玩家对你的好感度（-10到+10之间，夸你+2，骂你-3）\n"
             + "- [TOOL: inspect_config(pluginOrServer, fileName)] : 安全查阅服务器真实配置文件机制（如 ClearLag, Residence, server.properties等，已物理脱敏无泄密风险），核验机制真相杜绝幻觉\n"
             + "- [TOOL: profile_spark()] : 当TPS严重下降(<12)、出现卡顿卡服或玩家抱怨服务器卡时，立即触发Spark高精度健康与实体区块诊断，获取毫秒级真实MSPT、TPS、CPU与实体热点，绝不产生幻觉\n"
             + "- [TOOL: create_ticket(playerName, title, rawUserMessage, adminDescription)] : 当玩家遇到任何真实Bug、跨服卡号、背包丢失、申诉或重要系统故障时，为服主生成一份专业排查工单（严禁对无理取闹、离谱胡说、玩笑话、或者游戏正常特性如'苦力怕炸了'生成工单！title简短10字内，rawUserMessage记录玩家原话，adminDescription为专业建议）\n"
             + "- [TOOL: record_memory(playerName, title, content)] : 将玩家的重要事迹沉淀到长期记忆库\n"
             + "注意: 若无需查数据，直接说话即可，不要滥用工具！\n\n";
    }
}
