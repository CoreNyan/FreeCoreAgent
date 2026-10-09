package io.github.freecoreagent.service;

import io.github.freecoreagent.config.LanguageManager;
import io.github.freecoreagent.perception.AgentPerceptionService;
import io.github.freecoreagent.redis.AgentRedisBridge;
import io.github.freecoreagent.tool.AgentToolExecutor;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FreeCoreAgent Interaction and Messaging Gateway
 * Forwards player speech directly to Redis freecore:agent:mc:inbound for Central Brain (CoreNyan).
 * Receives broadcast and private replies from Redis and delivers them in-game.
 * All formatting is strictly managed by LanguageManager.
 */
public final class AgentInteractionService {
    private final JavaPlugin plugin;
    private final LanguageManager lang;
    private final AgentPerceptionService perception;
    private final AgentToolExecutor toolExecutor;
    private final Set<String> ownerNames = new HashSet<>();
    private AgentRedisBridge redisBridge;

    public AgentInteractionService(JavaPlugin plugin, LanguageManager lang,
                                   AgentPerceptionService perception,
                                   AgentToolExecutor toolExecutor) {
        this.plugin = plugin;
        this.lang = lang;
        this.perception = perception;
        this.toolExecutor = toolExecutor;
        reloadOwners();
    }

    public void setRedisBridge(AgentRedisBridge redisBridge) {
        this.redisBridge = redisBridge;
    }

    public void reloadOwners() {
        ownerNames.clear();
        List<String> list = plugin.getConfig().getStringList("owners");
        for (String s : list) {
            ownerNames.add(s.toLowerCase());
        }
    }

    public boolean isOwner(CommandSender sender) {
        if (sender.isOp()) return true;
        return ownerNames.contains(sender.getName().toLowerCase());
    }

    public void dispatchPublicChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        dispatchPublicChatDirect(player, message);
    }

    public void dispatchPublicChatDirect(Player player, String message) {
        if (message == null || message.isBlank()) return;

        // Check if player explicitly mentioned Xiaoke
        boolean isExplicit = isExplicitlyAddressed(message);

        // Forward directly to Central Brain via Redis Nervous Bus
        if (redisBridge != null && redisBridge.isRunning()) {
            redisBridge.publishMcInbound(
                    player.getUniqueId().toString(),
                    player.getName(),
                    message,
                    isExplicit,
                    false
            );
            return;
        }

        plugin.getLogger().warning("[Interaction] Redis 桥接未连接，无法将游戏内发言投递至 CoreNyan 中枢大脑。");
    }

    public void dispatchPrivateMessage(Player player, String message) {
        if (redisBridge != null && redisBridge.isRunning()) {
            redisBridge.publishMcInbound(
                    player.getUniqueId().toString(),
                    player.getName(),
                    message,
                    true,
                    true
            );
            return;
        }
        player.sendMessage(lang.message("chat.not-connected"));
    }

    public boolean isExplicitlyAddressed(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase();
        return lower.contains("小可") ||
               lower.contains("corenyan") ||
               lower.contains("@小可") ||
               lower.contains("@corenyan") ||
               lower.contains("小可酱");
    }

    public void broadcastAgentMessage(String rawMessage, boolean logToConsole) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            String formatted = lang.message("chat.public-format",
                    "{prefix}", lang.prefix(),
                    "{name}", getAgentName(),
                    "{message}", rawMessage);

            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(formatted);
            }
            plugin.getLogger().info("[小可全服广播] " + rawMessage);
        });
    }

    public void sendPrivateAgentMessage(Player player, String rawMessage) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            String formatted = lang.message("chat.private-format-from",
                    "{sender}", getAgentName(),
                    "{message}", rawMessage);
            player.sendMessage(formatted);
        });
    }

    public String getAgentName() {
        return plugin.getConfig().getString("agent.name", "CoreNyan");
    }
}