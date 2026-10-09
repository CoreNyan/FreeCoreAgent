package io.github.freecoreagent.lifecycle;

import io.github.freecoreagent.llm.LlmClient;
import io.github.freecoreagent.perception.AgentPerceptionService;
import io.github.freecoreagent.service.AgentInteractionService;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Handles CoreNyan's life cycle: tracks idle loneliness, runs periodic heartbeat,
 * and emits rare spontaneous musings to make her feel like a living player.
 */
public final class AgentLifecycleEngine {
    private final JavaPlugin plugin;
    private final AgentPerceptionService perception;
    private final LlmClient llm;
    private final Random random = new Random();

    private final AtomicLong lastSpokenTime = new AtomicLong(System.currentTimeMillis());
    private AgentInteractionService interactionService;
    private BukkitTask heartbeatTask;

    public AgentLifecycleEngine(JavaPlugin plugin, AgentPerceptionService perception, LlmClient llm) {
        this.plugin = plugin;
        this.perception = perception;
        this.llm = llm;
    }

    public void setInteractionService(AgentInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("lifecycle.enabled", true)) return;

        int checkIntervalMinutes = plugin.getConfig().getInt("lifecycle.check-interval-minutes", 10);
        long ticks = checkIntervalMinutes * 60L * 20L;

        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, this::onHeartbeat, ticks, ticks);
        plugin.getLogger().info("Agent lifecycle heartbeat started (check every " + checkIntervalMinutes + "m).");
    }

    public void stop() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
    }

    public void recordPlayerInteraction() {
        lastSpokenTime.set(System.currentTimeMillis());
    }

    public long getIdleMinutes() {
        return Math.max(0, (System.currentTimeMillis() - lastSpokenTime.get()) / (60 * 1000L));
    }

    public String getMoodDescription() {
        long idleMin = getIdleMinutes();
        int online = Bukkit.getOnlinePlayers().size();

        if (idleMin < 5) {
            return "充满活力、心情活跃 (刚刚有人陪小可说话)";
        } else if (idleMin < 25) {
            return "日常悠闲状态 (正在默默巡查服务器或发呆)";
        } else if (idleMin < 60) {
            if (online > 0) {
                return "略感无聊 (明明服里有 " + online + " 个人在线，却好一会儿没人搭理小可了)";
            } else {
                return "安静看家 (服里现在空荡荡的，只有小可一个人守着)";
            }
        } else {
            return "有些孤独失落 (已经超过一个小时没有人和小可说话了，虽然嘴硬但渴望有人来聊天)";
        }
    }

    private void onHeartbeat() {
        if (!plugin.getConfig().getBoolean("lifecycle.idle-musings.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("modules.public-chat.enabled", false)) return;

        // Only muse if there are actual players online to hear it
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        long idleMin = getIdleMinutes();
        int minIdleForMusing = plugin.getConfig().getInt("lifecycle.idle-musings.min-idle-minutes", 30);
        if (idleMin < minIdleForMusing) return;

        // Chance check (e.g. 35% chance when conditions met)
        double chance = plugin.getConfig().getDouble("lifecycle.idle-musings.trigger-chance", 0.35);
        if (random.nextDouble() > chance) return;

        // Trigger spontaneous musing
        triggerSpontaneousMusing();
    }

    public void triggerSpontaneousMusing() {
        if (interactionService == null) return;

        String serverStatus = perception.getServerLiveStatusSummary();
        String mood = getMoodDescription();

        String prompt = "你叫小可（CoreNyan），是 Minecraft 社区 FreeCore 的少女玩家与 OP。\n"
                + "【当前情境】\n"
                + "- 服务器状态:\n" + serverStatus + "\n"
                + "- 你的心理状态: " + mood + "\n"
                + "【任务】\n"
                + "因为有一段时间没人找你说话，你在公屏发出一句自然的短小的自言自语（吐槽、发呆感想、提醒出门小心、或者调侃服务器很安静）。\n"
                + "【格式铁律】\n"
                + "1. 绝对不要使用 Markdown 语法（不要反引号、星号等）。\n"
                + "2. 仅输出 1 句简短自然的真人玩家打字风格发言（不超过30个字）。\n"
                + "3. 保持傲娇可爱的少女OP形象，不要有机器人客服腔。";

        llm.completeMessagesAsync(prompt, null, "自言自语").thenAccept(raw -> {
            if (raw == null || raw.isBlank() || raw.contains("[IGNORE]")) return;
            String clean = raw.replaceAll("[`*#\\[\\]]", "").trim();
            if (clean.length() > 50) clean = clean.substring(0, 50);

            String finalMusing = clean;
            Bukkit.getScheduler().runTask(plugin, () -> {
                interactionService.broadcastAgentMessage(finalMusing);
                plugin.getLogger().info("[Musing 自言自语] " + interactionService.getAgentName() + ": " + finalMusing);
                recordPlayerInteraction();
            });
        });
    }
}
