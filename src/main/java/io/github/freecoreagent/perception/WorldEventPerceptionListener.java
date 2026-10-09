package io.github.freecoreagent.perception;

import io.github.freecoreagent.service.AgentInteractionService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Listens to active in-game events and forwards chat directly to interaction gateway.
 */
public final class WorldEventPerceptionListener implements Listener {
    private final JavaPlugin plugin;
    private final AgentPerceptionService perception;
    private final AgentInteractionService interaction;

    public WorldEventPerceptionListener(JavaPlugin plugin,
                                        AgentPerceptionService perception,
                                        AgentInteractionService interaction) {
        this.plugin = plugin;
        this.perception = perception;
        this.interaction = interaction;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        interaction.dispatchPublicChat(event);
    }
}
