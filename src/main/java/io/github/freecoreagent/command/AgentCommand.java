package io.github.freecoreagent.command;

import io.github.freecoreagent.config.LanguageManager;
import io.github.freecoreagent.service.AgentInteractionService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main command executor for /freecoreagent (/fca) gateway management.
 */
public final class AgentCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final LanguageManager lang;
    private final AgentInteractionService interaction;
    private io.github.freecoreagent.ticket.TicketGui ticketGui;

    public AgentCommand(JavaPlugin plugin, LanguageManager lang, AgentInteractionService interaction) {
        this.plugin = plugin;
        this.lang = lang;
        this.interaction = interaction;
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
            interaction.reloadOwners();
            if (plugin instanceof io.github.freecoreagent.FreeCoreAgentPlugin fcaPlugin && fcaPlugin.getRedisBridge() != null) {
                fcaPlugin.getRedisBridge().publishRemoteReload();
            }
            sender.sendMessage(lang.prefix() + lang.message("command.reload-success") + " (已同步通知全网络子服热重载)");
            return true;
        }

        if (sub.equals("status")) {
            sender.sendMessage(ChatColor.GOLD + "=== FreeCoreAgent 网关状态 ===");
            sender.sendMessage(ChatColor.YELLOW + "认知中枢: " + ChatColor.GREEN + "FreeCore-CoreNyan (Redis 神经总线)");
            sender.sendMessage(ChatColor.YELLOW + "Redis 桥接: " + (plugin instanceof io.github.freecoreagent.FreeCoreAgentPlugin fca && fca.getRedisBridge().isRunning() ? ChatColor.GREEN + "已连通" : ChatColor.RED + "未连通"));
            sender.sendMessage(ChatColor.YELLOW + "网关身份: " + ChatColor.AQUA + interaction.getAgentName());
            return true;
        }

        if (sub.equals("issues") || sub.equals("tickets") || sub.equals("todo")) {
            if (sender instanceof Player p && ticketGui != null) {
                ticketGui.openGui(p, 0);
                return true;
            }
            sender.sendMessage(ChatColor.RED + "该命令仅限玩家在游戏内打开工单 GUI。");
            return true;
        }

        if (sub.equals("say")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "用法: /fca say <消息内容>");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                sb.append(args[i]).append(" ");
            }
            interaction.broadcastAgentMessage(sb.toString().trim(), true);
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
            for (String sub : List.of("reload", "status", "issues", "say")) {
                if (sub.startsWith(p)) list.add(sub);
            }
            return list;
        }
        return Collections.emptyList();
    }
}