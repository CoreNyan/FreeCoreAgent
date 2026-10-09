package io.github.freecoreagent.perception;

import io.github.freecoreagent.config.LanguageManager;
import io.github.freecoreagent.service.AgentInteractionService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to active in-game events and forwards chat & private msgs directly to interaction gateway.
 * Listens to both modern Paper AsyncChatEvent and legacy AsyncPlayerChatEvent with deduplication.
 * All user-facing strings are strictly resolved via LanguageManager (lang/zh_CN.yml).
 */
public final class WorldEventPerceptionListener implements Listener {
    private final JavaPlugin plugin;
    private final LanguageManager lang;
    private final AgentPerceptionService perception;
    private final AgentInteractionService interaction;
    private final Map<String, Long> recentChatDeduplication = new ConcurrentHashMap<>();

    public WorldEventPerceptionListener(JavaPlugin plugin,
                                        LanguageManager lang,
                                        AgentPerceptionService perception,
                                        AgentInteractionService interaction) {
        this.plugin = plugin;
        this.lang = lang;
        this.perception = perception;
        this.interaction = interaction;
    }

    private boolean isDuplicate(Player player, String message) {
        String key = player.getUniqueId().toString() + ":" + message.trim();
        long now = System.currentTimeMillis();
        Long last = recentChatDeduplication.put(key, now);
        if (last != null && (now - last) < 800) {
            return true;
        }
        // Cleanup old entries
        if (recentChatDeduplication.size() > 100) {
            recentChatDeduplication.entrySet().removeIf(e -> (now - e.getValue()) > 5000);
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (message.isEmpty() || isDuplicate(player, message)) return;
        interaction.dispatchPublicChatDirect(player, message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage() != null ? event.getMessage().trim() : "";
        if (message.isEmpty() || isDuplicate(player, message)) return;
        interaction.dispatchPublicChatDirect(player, message);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().trim();
        if (!msg.startsWith("/")) return;

        String[] parts = msg.substring(1).split("\\s+", 3);
        if (parts.length < 2) return;

        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (cmd.equals("msg") || cmd.equals("tell") || cmd.equals("w") || cmd.equals("m") || cmd.equals("whisper")) {
            String target = parts[1].toLowerCase(Locale.ROOT);
            if (target.contains("corenyan") || target.contains("小可")) {
                event.setCancelled(true);
                Player player = event.getPlayer();

                if (parts.length < 3 || parts[2].trim().isEmpty()) {
                    player.sendMessage(lang.message("command.msg-usage",
                            "{command}", parts[0],
                            "{target}", interaction.getAgentName()));
                    return;
                }

                String content = parts[2].trim();
                player.sendMessage(lang.message("chat.private-format-to",
                        "{receiver}", interaction.getAgentName(),
                        "{message}", content));

                interaction.dispatchPrivateMessage(player, content);
            }
        }
    }
}