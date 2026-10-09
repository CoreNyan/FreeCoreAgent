package io.github.freecoreagent.perception;

import io.github.freecoreagent.affection.PlayerAffectionManager;
import io.github.freecoreagent.llm.LlmClient;
import io.github.freecoreagent.service.AgentInteractionService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to active in-game events (deaths, advancements, first joins)
 * and feeds them to Agent brain for lively in-game commentary and reactions.
 */
public final class WorldEventPerceptionListener implements Listener {
    private final JavaPlugin plugin;
    private final LlmClient llm;
    private final AgentInteractionService interaction;
    private final PlayerAffectionManager affection;

    private final Map<String, Long> eventCooldowns = new ConcurrentHashMap<>();

    public WorldEventPerceptionListener(JavaPlugin plugin, LlmClient llm,
                                        AgentInteractionService interaction,
                                        PlayerAffectionManager affection) {
        this.plugin = plugin;
        this.llm = llm;
        this.interaction = interaction;
        this.affection = affection;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("modules.world-events.death-reaction", false)) return;

        Player player = event.getEntity();
        String deathMsg = event.getDeathMessage();
        if (deathMsg == null || deathMsg.isBlank()) return;

        // Strip colors
        deathMsg = ChatColor.stripColor(deathMsg);

        // Cooldown: at most 1 death commentary every 120 seconds to prevent spam
        long now = System.currentTimeMillis();
        long last = eventCooldowns.getOrDefault("death", 0L);
        if (now - last < 120_000L) return;
        eventCooldowns.put("death", now);

        String context = "【突发事件: 玩家死亡广播】\n"
                + "- 遇难玩家: " + player.getName() + "\n"
                + "- 死因报告: " + deathMsg + "\n"
                + "- 是否服主/OP: " + (player.isOp() || player.getName().equalsIgnoreCase("LynnH_Ma")) + "\n"
                + "【行动规则】: 必须严格在句首带有 @" + player.getName() + " 指名吐槽或慰问（20字以内）。严禁不带名字瞎说！如果觉得无关紧要输出 [IGNORE]。";

        triggerAutonomousReaction(context, player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.getConfig().getBoolean("modules.world-events.advancement-reaction", false)) return;

        String key = event.getAdvancement().getKey().getKey();
        // Ignore recipe unlock spam
        if (key.startsWith("recipes/")) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        long last = eventCooldowns.getOrDefault("advancement", 0L);
        if (now - last < 120_000L) return;
        eventCooldowns.put("advancement", now);

        String context = "【突发事件: 玩家达成游戏成就】\n"
                + "- 玩家: " + player.getName() + "\n"
                + "- 成就条目: " + key + "\n"
                + "【行动规则】: 必须严格在句首带有 @" + player.getName() + " 祝贺或调侃（20字以内）。严禁不带名字瞎说！无需发言输出 [IGNORE]。";

        triggerAutonomousReaction(context, player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("modules.world-events.newbie-welcome", true)) return;

        Player player = event.getPlayer();
        if (!player.hasPlayedBefore()) {
            // First time joining FreeCore
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                String context = "【突发事件: 崭新萌新首次踏入 FreeCore】\n"
                        + "- 萌新玩家: " + player.getName() + "\n"
                        + "【行动规则】: 必须严格在句首带有 @" + player.getName() + "。以元气傲娇的常驻少女OP身份真诚欢迎新人（20字以内，比如'欢迎来FreeCore，有不懂的尽管问我哦~'）。严禁把/menu和加群机械地拼在一起！";
                triggerAutonomousReaction(context, player.getName());
            }, 60L); // 3s later
        }
    }

    private void triggerAutonomousReaction(String systemContext, String targetPlayerName) {
        String prompt = systemContext + "\n\n【行文规范】: 必须严格在开头带上 @" + targetPlayerName + " 指名道姓！控制在1句话20字以内，严禁 Markdown 符号（不要**，不要#）。若不想说话输出 [IGNORE]。";

        llm.completeMessagesAsync(prompt, new ArrayList<>(), "请做出反应：").thenAccept(raw -> {
            if (raw == null || raw.isBlank() || raw.contains("[IGNORE]")) return;

            String clean = raw.replaceAll("(?m)^#+\\s*", "").replace("**", "").replace("*", "").replace("`", "").trim();
            if (!clean.startsWith("@" + targetPlayerName)) {
                clean = "@" + targetPlayerName + " " + clean;
            }
            if (clean.length() > 40) clean = clean.substring(0, 40);

            long delayTicks = 40L + (long) (Math.random() * 30L);
            String finalMsg = clean;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                interaction.broadcastAgentMessage(finalMsg, true);
            }, delayTicks);
        });
    }
}
