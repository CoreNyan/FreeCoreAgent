package io.github.freecoreagent.perception;

import io.github.freecoreagent.config.LanguageManager;
import io.github.freecoreagent.service.AgentInteractionService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Listens to active in-game events and forwards chat & private msgs directly to interaction gateway.
 * All user-facing strings are strictly resolved via LanguageManager (lang/zh_CN.yml).
 */
public final class WorldEventPerceptionListener implements Listener {
    private final JavaPlugin plugin;
    private final LanguageManager lang;
    private final AgentPerceptionService perception;
    private final AgentInteractionService interaction;

    public WorldEventPerceptionListener(JavaPlugin plugin,
                                        LanguageManager lang,
                                        AgentPerceptionService perception,
                                        AgentInteractionService interaction) {
        this.plugin = plugin;
        this.lang = lang;
        this.perception = perception;
        this.interaction = interaction;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        interaction.dispatchPublicChat(event);
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