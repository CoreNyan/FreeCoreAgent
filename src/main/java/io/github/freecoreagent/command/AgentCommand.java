package io.github.freecoreagent.command;

import io.github.freecoreagent.config.LanguageManager;
import io.github.freecoreagent.llm.LlmClient;
import io.github.freecoreagent.memory.VaultMemoryManager;
import io.github.freecoreagent.service.AgentInteractionService;
import io.github.freecoreagent.skill.SkillManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main command executor for /freecoreagent (/fca) with memory status and commands.
 */
public final class AgentCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final LanguageManager lang;
    private final AgentInteractionService interaction;
    private final SkillManager skills;
    private final VaultMemoryManager memory;
    private final LlmClient llm;
    private io.github.freecoreagent.ticket.TicketGui ticketGui;

    public AgentCommand(JavaPlugin plugin, LanguageManager lang, AgentInteractionService interaction, SkillManager skills, VaultMemoryManager memory, LlmClient llm) {
        this.plugin = plugin;
        this.lang = lang;
        this.interaction = interaction;
        this.skills = skills;
        this.memory = memory;
        this.llm = llm;
    }

    public void setTicketGui(io.github.freecoreagent.ticket.TicketGui ticketGui) {
        this.ticketGui = ticketGui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!interaction.isOwner(sender)) {
            sender.sendMessage(lang.message("command.only-owner"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(lang.message("command.usage"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            plugin.reloadConfig();
            lang.reload();
            skills.load();
            memory.load();
            interaction.reloadOwners();
            if (plugin instanceof io.github.freecoreagent.FreeCoreAgentPlugin fcaPlugin && fcaPlugin.getRedisBridge() != null) {
                fcaPlugin.getRedisBridge().publishRemoteReload();
            }
            sender.sendMessage(lang.prefix() + lang.message("command.reload-success") + " (已同步通知全网络子服热重载)");
            return true;
        }

        if (sub.equals("status")) {
            String model = plugin.getConfig().getString("llm.model", "deepseek-chat");
            boolean pub = plugin.getConfig().getBoolean("modules.public-chat.enabled", true);
            boolean pri = plugin.getConfig().getBoolean("modules.private-chat.enabled", true);
            sender.sendMessage(lang.message("command.status.header"));
            sender.sendMessage(lang.message("command.status.model", "{model}", model));
            sender.sendMessage(lang.message("command.status.public-chat", "{status}", pub ? lang.message("format.enabled") : lang.message("format.disabled")));
            sender.sendMessage(lang.message("command.status.private-chat", "{status}", pri ? lang.message("format.enabled") : lang.message("format.disabled")));
            sender.sendMessage(lang.message("command.status.loaded-skills", "{count}", skills.getSkills().size()));
            sender.sendMessage(ChatColor.YELLOW + "挂载统一记忆库卡片数: " + ChatColor.AQUA + memory.getMemoryCards().size() + ChatColor.GRAY + " (路径: " + plugin.getConfig().getString("memory.vault-path") + ")");
            sender.sendMessage(lang.message("command.status.footer"));
            return true;
        }

        if (sub.equals("toggle")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "用法: /fca toggle <public|private>");
                return true;
            }
            String mod = args[1].toLowerCase(Locale.ROOT);
            if (mod.equals("public") || mod.equals("公屏")) {
                boolean cur = plugin.getConfig().getBoolean("modules.public-chat.enabled", true);
                plugin.getConfig().set("modules.public-chat.enabled", !cur);
                plugin.saveConfig();
                sender.sendMessage(lang.prefix() + lang.message("command.toggle.public", "{status}", !cur ? lang.message("format.enabled") : lang.message("format.disabled")));
            } else if (mod.equals("private") || mod.equals("私信")) {
                boolean cur = plugin.getConfig().getBoolean("modules.private-chat.enabled", true);
                plugin.getConfig().set("modules.private-chat.enabled", !cur);
                plugin.saveConfig();
                sender.sendMessage(lang.prefix() + lang.message("command.toggle.private", "{status}", !cur ? lang.message("format.enabled") : lang.message("format.disabled")));
            } else {
                sender.sendMessage(lang.message("command.toggle.invalid"));
            }
            return true;
        }

        if (sub.equals("ask")) {
            if (args.length < 2) {
                sender.sendMessage(lang.message("command.ask-usage"));
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) sb.append(args[i]).append(" ");
            String query = sb.toString().trim();

            sender.sendMessage(lang.message("chat.thinking"));
            interaction.handleDirectAsk(sender, query);
            return true;
        }

        if (sub.equals("issues") || sub.equals("tickets") || sub.equals("工单")) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(ChatColor.RED + "控制台无法打开GUI面板，请在游戏内输入 /fca issues 查看！");
                return true;
            }
            if (ticketGui != null) {
                ticketGui.openGui(player, 0);
            } else {
                sender.sendMessage(ChatColor.RED + "工单GUI模块尚未加载！");
            }
            return true;
        }

        if (sub.equals("skin")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "小可换肤用法:");
                sender.sendMessage(ChatColor.GOLD + "  /fca skin --url <皮肤图片直链> [-s|--slim]" + ChatColor.GRAY + " (如使用外链，自动通过Mineskin官方签名)");
                sender.sendMessage(ChatColor.GOLD + "  /fca skin <玩家名>" + ChatColor.GRAY + " (复制指定玩家/NPC的皮肤，如 /fca skin anatoly_lancelot)");
                return true;
            }
            if (plugin instanceof io.github.freecoreagent.FreeCoreAgentPlugin fcaPlugin && fcaPlugin.getTabPlayerManager() != null) {
                if (args[1].equalsIgnoreCase("--url")) {
                    if (args.length < 3) {
                        sender.sendMessage(ChatColor.RED + "请输入皮肤图片直链地址！");
                        return true;
                    }
                    String url = args[2];
                    boolean slim = false;
                    for (int i = 3; i < args.length; i++) {
                        if (args[i].equalsIgnoreCase("-s") || args[i].equalsIgnoreCase("--slim")) slim = true;
                    }
                    sender.sendMessage(ChatColor.YELLOW + "正在为小可请求并生成签名皮肤数据 (Mineskin/Yggdrasil)，请稍候...");
                    boolean finalSlim = slim;
                    fcaPlugin.getTabPlayerManager().setSkinFromUrl(url, finalSlim, success -> {
                        if (success) {
                            sender.sendMessage(ChatColor.GREEN + "小可的皮肤已成功更换并全服同步生效！");
                            if (fcaPlugin.getRedisBridge() != null) {
                                fcaPlugin.getRedisBridge().publishRemoteReload();
                            }
                        } else {
                            sender.sendMessage(ChatColor.RED + "换肤失败！请检查图片直链是否有效（需为标准 64x64 或 64x32 PNG）。");
                        }
                    });
                    return true;
                } else {
                    String targetPlayer = args[1];
                    sender.sendMessage(ChatColor.YELLOW + "正在从皮肤服务器获取玩家 " + targetPlayer + " 的皮肤数据...");
                    fcaPlugin.getTabPlayerManager().setSkinFromPlayer(targetPlayer, success -> {
                        if (success) {
                            sender.sendMessage(ChatColor.GREEN + "小可已成功换上玩家 " + targetPlayer + " 的皮肤并全服同步！");
                            if (fcaPlugin.getRedisBridge() != null) {
                                fcaPlugin.getRedisBridge().publishRemoteReload();
                            }
                        } else {
                            sender.sendMessage(ChatColor.RED + "换肤失败！未找到玩家 " + targetPlayer + " 的有效皮肤数据。");
                        }
                    });
                    return true;
                }
            } else {
                sender.sendMessage(ChatColor.RED + "Tab虚拟玩家模块未启用！");
                return true;
            }
        }

        if (sub.equals("say")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "用法: /fca say <想要小可说的话>");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) sb.append(args[i]).append(" ");
            String broadcastText = sb.toString().trim();
            interaction.broadcastAgentMessage(broadcastText);
            plugin.getLogger().info("[Console Say] " + sender.getName() + " 让小可发言: " + broadcastText);
            return true;
        }

        sender.sendMessage(lang.message("command.usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!interaction.isOwner(sender)) return Collections.emptyList();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            List<String> list = new ArrayList<>();
            for (String sub : List.of("reload", "status", "issues", "toggle", "ask", "say", "skin")) {
                if (sub.startsWith(p)) list.add(sub);
            }
            return list;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            List<String> list = new ArrayList<>();
            for (String sub : List.of("public", "private")) {
                if (sub.startsWith(p)) list.add(sub);
            }
            return list;
        }
        return Collections.emptyList();
    }
}
