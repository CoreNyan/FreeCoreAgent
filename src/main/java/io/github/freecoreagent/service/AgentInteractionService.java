package io.github.freecoreagent.service;

import io.github.freecoreagent.config.LanguageManager;
import io.github.freecoreagent.lifecycle.AgentLifecycleEngine;
import io.github.freecoreagent.llm.LlmClient;
import io.github.freecoreagent.llm.LlmClient.ChatMessage;
import io.github.freecoreagent.memory.VaultMemoryManager;
import io.github.freecoreagent.perception.AgentPerceptionService;
import io.github.freecoreagent.perception.AgentPerceptionService.PlayerProfileInfo;
import io.github.freecoreagent.redis.AgentRedisBridge;
import io.github.freecoreagent.skill.SkillManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles simulated Agent presence, conversation memory,
 * dynamic player rate-limiting (max 1-2 replies per minute per player),
 * intelligent message batching/buffering, and simulated player typing latency.
 */
public final class AgentInteractionService implements Listener {
    private final JavaPlugin plugin;
    private final LanguageManager lang;
    private final LlmClient llm;
    private final SkillManager skills;
    private final VaultMemoryManager memory;
    private final AgentPerceptionService perception;
    private final AgentLifecycleEngine lifecycle;
    private final io.github.freecoreagent.affection.PlayerAffectionManager affection;
    private final io.github.freecoreagent.tool.AgentToolExecutor toolExecutor;
    private AgentRedisBridge redisBridge;

    private final Set<String> ownerNames = new HashSet<>();
    private final Set<Long> qqOwnerIds = new HashSet<>();
    private volatile boolean qqTrustAdmins = true;
    private final Map<String, List<Long>> playerToQqBindings = new ConcurrentHashMap<>();
    private final Map<Long, String> qqToPlayerBindings = new ConcurrentHashMap<>();
    private final Map<UUID, List<ChatMessage>> conversationHistory = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMessage>> qqConversationHistory = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastInteractionTimes = new ConcurrentHashMap<>();
    private final Set<String> processedMessageFingerprints = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Rate-limiting and message buffering per player
    private final Map<UUID, List<Long>> playerReplyTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> playerMessageBuffer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerScheduledBatch = new ConcurrentHashMap<>();

    // Global Public Chat sliding window buffer (records last 8 public messages from any player or agent)
    private final List<String> recentPublicChatLog = Collections.synchronizedList(new ArrayList<>());
    private volatile long lastPublicBroadcastTimestamp = 0L;
    private volatile long lastUnsolicitedChimeTimestamp = 0L;

    public AgentInteractionService(JavaPlugin plugin, LanguageManager lang, LlmClient llm,
                                   SkillManager skills, VaultMemoryManager memory,
                                   AgentPerceptionService perception, AgentLifecycleEngine lifecycle,
                                   io.github.freecoreagent.affection.PlayerAffectionManager affection,
                                   io.github.freecoreagent.tool.AgentToolExecutor toolExecutor) {
        this.plugin = plugin;
        this.lang = lang;
        this.llm = llm;
        this.skills = skills;
        this.memory = memory;
        this.perception = perception;
        this.lifecycle = lifecycle;
        this.affection = affection;
        this.toolExecutor = toolExecutor;
        reloadOwners();
    }

    public void setRedisBridge(AgentRedisBridge redisBridge) {
        this.redisBridge = redisBridge;
    }

    public void reloadOwners() {
        ownerNames.clear();
        for (String o : plugin.getConfig().getStringList("owners")) {
            if (o != null && !o.isBlank()) {
                ownerNames.add(o.toLowerCase(Locale.ROOT));
            }
        }

        qqOwnerIds.clear();
        for (long id : plugin.getConfig().getLongList("qq.owners")) {
            qqOwnerIds.add(id);
        }
        if (qqOwnerIds.isEmpty()) {
            qqOwnerIds.add(2040266560L); // Default LynnH_Ma QQ
            qqOwnerIds.add(2842868264L);
        }
        qqTrustAdmins = plugin.getConfig().getBoolean("qq.trust-admins", true);

        // Load QQ <-> MC Player Bindings
        playerToQqBindings.clear();
        qqToPlayerBindings.clear();
        org.bukkit.configuration.ConfigurationSection sec = plugin.getConfig().getConfigurationSection("qq.bindings");
        if (sec != null) {
            for (String mcName : sec.getKeys(false)) {
                List<Long> qqs = sec.getLongList(mcName);
                if (qqs.isEmpty()) {
                    long single = sec.getLong(mcName, 0L);
                    if (single > 0) qqs = List.of(single);
                }
                playerToQqBindings.put(mcName.toLowerCase(Locale.ROOT), qqs);
                for (long q : qqs) {
                    qqToPlayerBindings.put(q, mcName);
                }
            }
        }
        // Fallback hardcoded defaults if not in config
        if (!playerToQqBindings.containsKey("lynnh_ma")) {
            List<Long> lynnQqs = List.of(2040266560L, 2842868264L);
            playerToQqBindings.put("lynnh_ma", lynnQqs);
            for (long q : lynnQqs) qqToPlayerBindings.put(q, "LynnH_Ma");
        }
        if (!playerToQqBindings.containsKey("rice")) {
            List<Long> riceQqs = List.of(3357058735L);
            playerToQqBindings.put("rice", riceQqs);
            for (long q : riceQqs) qqToPlayerBindings.put(q, "Rice");
        }
    }

    public boolean isOwner(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        if (player.isOp()) return true;
        return ownerNames.contains(player.getName().toLowerCase(Locale.ROOT));
    }

    public void handleDirectAsk(org.bukkit.command.CommandSender sender, String query) {
        handleConsoleAsk(sender, query);
    }

    public String getAgentName() {
        return plugin.getConfig().getString("agent.name", "CoreNyan");
    }

    public String getChatPrefix() {
        return plugin.getConfig().getString("agent.chat-prefix", "&8[&bFreeCore&8] &7[ &6&l小可 &7] &d");
    }

    public void handleConsoleAsk(org.bukkit.command.CommandSender sender, String query) {
        UUID uuid = (sender instanceof Player p) ? p.getUniqueId() : UUID.nameUUIDFromBytes("CONSOLE".getBytes());
        List<ChatMessage> history = getHistoryForPlayer(uuid);
        String prompt = buildPrompt(sender, query, false);

        sender.sendMessage(ChatColor.GRAY + "(" + getAgentName() + " 正在思考中...)");
        plugin.getLogger().info("[Ask] " + sender.getName() + " -> " + getAgentName() + ": " + query);

        llm.completeMessagesAsync(prompt, new ArrayList<>(history), query).thenAccept(rawReply -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (rawReply == null || rawReply.isBlank() || rawReply.contains("[IGNORE]")) {
                    if (rawReply != null && rawReply.contains("[IGNORE]")) {
                        plugin.getLogger().info("[Ask] " + getAgentName() + " 判定为 [IGNORE] 忽略不作答。");
                        sender.sendMessage(ChatColor.GRAY + "(" + getAgentName() + " 看了你一眼，傲娇地扭过头选择不理你...)");
                    } else {
                        sender.sendMessage(lang.message("chat.error"));
                        plugin.getLogger().warning("[Ask] " + getAgentName() + " 回复失败 (LLM 返回空或超时)");
                    }
                    return;
                }
                String cleanReply = processMemoryAndExtractReply(sender.getName(), rawReply);
                appendHistory(uuid, query, cleanReply);

                String formatted = lang.message("chat.public-format",
                        "{prefix}", getChatPrefix(),
                        "{name}", getAgentName(),
                        "{message}", cleanReply);
                sender.sendMessage(formatted);
                plugin.getLogger().info("[Reply] " + getAgentName() + " -> " + sender.getName() + ": " + cleanReply);
            });
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPaperChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        dispatchPublicChat(event.getPlayer(), text);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        dispatchPublicChat(event.getPlayer(), event.getMessage().trim());
    }

    private void dispatchPublicChat(Player player, String text) {
        if (!plugin.getConfig().getBoolean("modules.public-chat.enabled", true)) return;
        if (text == null || text.isBlank()) return;

        // Deduplicate across Paper and Legacy events
        String fingerprint = player.getUniqueId() + ":" + (System.currentTimeMillis() / 800) + ":" + text;
        if (!processedMessageFingerprints.add(fingerprint)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> processedMessageFingerprints.remove(fingerprint), 40L);

        // Always record every public player message into the global sliding window context!
        recordPublicChatLog(player.getName() + ": " + text);
        if (redisBridge != null) {
            redisBridge.publishChatStream(player.getName() + ": " + text);
        }

        // Discard obvious non-dialogue spam (e.g. "@someoneElse", pure digits)
        if (!ChatIntentClassifier.shouldPassToBrain(player.getName(), text)) {
            return;
        }

        // If Redis Bridge is enabled, immediately pass raw message to Central Brain (app.js)
        // Central Brain performs the single unified trailing-debounce buffer across all channels!
        if (redisBridge != null) {
            boolean isExplicit = isExplicitlyAddressed(text);
            org.json.simple.JSONObject packet = new org.json.simple.JSONObject();
            packet.put("platform", "mc");
            packet.put("sender_uuid", player.getUniqueId().toString());
            packet.put("sender_name", player.getName());
            packet.put("nickname", player.getName());
            packet.put("message", text);
            packet.put("is_at", isExplicit);
            packet.put("is_mentioned", isExplicit);
            packet.put("is_private", false);
            packet.put("role", isOwner(player) ? "owner" : "member");
            packet.put("server", plugin.getDataFolder().getAbsolutePath().contains("Tech") ? "technical" : "survival");
            redisBridge.publishMcInbound(packet.toJSONString());
            return;
        }

        UUID uid = player.getUniqueId();
        cleanRecentTimestamps(uid);

        List<String> buffer = playerMessageBuffer.computeIfAbsent(uid, k -> new ArrayList<>());
        buffer.add(text);
        if (buffer.size() > 5) buffer.remove(0);

        scheduleBufferedFlush(player);
    }

    private void scheduleBufferedFlush(Player player) {
        UUID uid = player.getUniqueId();
        // Extend the buffer window if more messages arrive in quick succession
        if (playerScheduledBatch.containsKey(uid)) {
            // Cancel previous delayed task to achieve genuine trailing debounce
            int previousTaskId = playerScheduledBatch.get(uid);
            Bukkit.getScheduler().cancelTask(previousTaskId);
        }

        // Window delay: wait 4.0 seconds (80 ticks) of silence from this player before replying!
        int windowSeconds = plugin.getConfig().getInt("modules.public-chat.batch-window-seconds", 4);
        long delayTicks = Math.max(3, windowSeconds) * 20L;

        int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            playerScheduledBatch.remove(uid);
            List<String> buffer = playerMessageBuffer.remove(uid);
            if (buffer == null || buffer.isEmpty() || !player.isOnline()) return;

            // Combine all consecutive messages sent by the player into one coherent prompt
            String combinedText = String.join("，", buffer);
            cleanRecentTimestamps(uid);
            playerReplyTimestamps.computeIfAbsent(uid, k -> new ArrayList<>()).add(System.currentTimeMillis());

            plugin.getLogger().info("[玩家连发防抖合并] " + player.getName() + " (" + buffer.size() + "条消息合并为1次回复): " + combinedText);
            processPlayerMessage(player, combinedText, false);
        }, delayTicks);

        playerScheduledBatch.put(uid, taskId);
    }

    private void cleanRecentTimestamps(UUID uid) {
        long now = System.currentTimeMillis();
        List<Long> list = playerReplyTimestamps.get(uid);
        if (list != null) {
            list.removeIf(t -> (now - t) > 60_000L);
        }
    }

    private void processPlayerMessage(Player player, String text, boolean isPrivate) {
        boolean isExplicit = isExplicitlyAddressed(text);
        boolean isAskingQuestion = ChatIntentClassifier.isHelpOrQuestion(text);

        // 如果配置了 RedisBridge 神经总线（即 Central Brain: FreeCore-CoreNyan 正在运行），
        // 游戏端将纯粹作为传信中继 (和 QQBot 的 corenyan-bridge 一致)，直接转发至 freecore:agent:mc:inbound
        if (redisBridge != null) {
            plugin.getLogger().info("[MC公屏 -> 神经总线转交] " + player.getName() + " (私聊=" + isPrivate + "): " + text);
            org.json.simple.JSONObject packet = new org.json.simple.JSONObject();
            packet.put("platform", "mc");
            packet.put("sender_uuid", player.getUniqueId().toString());
            packet.put("sender_name", player.getName());
            packet.put("nickname", player.getName());
            packet.put("message", text);
            packet.put("is_at", isExplicit);
            packet.put("is_mentioned", isExplicit); // ONLY true when player EXPLICITLY calls Xiaoke!
            packet.put("is_private", isPrivate);
            packet.put("role", isOwner(player) ? "owner" : "member");
            packet.put("server", plugin.getDataFolder().getAbsolutePath().contains("Tech") ? "technical" : "survival");
            redisBridge.publishMcInbound(packet.toJSONString());
            return;
        }

        // 本地降级回退通道（仅当未连上 Redis 神经总线时）
        List<ChatMessage> history = getHistoryForPlayer(player.getUniqueId());
        String prompt = buildPrompt(player, text, isPrivate);

        plugin.getLogger().info("[公屏捕获 - 本地模式] " + player.getName() + ": " + text);

        handleLlmTurn(player, prompt, history, text, isPrivate, 1);
    }

    private boolean isExplicitlyAddressed(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("小可") || lower.contains("corenyan") || lower.contains("@小可") || lower.contains("@corenyan");
    }

    private void handleLlmTurn(Player player, String prompt, List<ChatMessage> history, String userText, boolean isPrivate, int turnDepth) {
        // If public chat:
        // 1. Explicitly calling Xiaoke OR asking questions/seeking help -> ALWAYS PASS (100% answer)!
        // 2. Pure small talk banter without question -> Apply unsolicited rate limit & 20% dice
        boolean isExplicit = isExplicitlyAddressed(userText);
        boolean isAskingQuestion = ChatIntentClassifier.isHelpOrQuestion(userText);

        if (!isPrivate && !isExplicit && !isAskingQuestion) {
            long now = System.currentTimeMillis();
            long cooldownMs = plugin.getConfig().getInt("modules.public-chat.unsolicited-chime-cooldown-seconds", 25) * 1000L;
            if (now - lastUnsolicitedChimeTimestamp < cooldownMs) {
                plugin.getLogger().info("[公屏插话节流] 未提问且未呼叫小可，距离上次插嘴不足 " + (cooldownMs / 1000) + "s，保持安静。");
                return;
            }
            double chance = plugin.getConfig().getDouble("modules.public-chat.natural-chitchat-chance", 0.20);
            if (Math.random() > chance) {
                plugin.getLogger().info("[公屏插话节流] 闲聊自然插话概率掷骰未中 (" + chance + ")，保持高冷旁听。");
                return;
            }
        }
        llm.completeMessagesAsync(prompt, new ArrayList<>(history), userText).thenAccept(rawReply -> {
            if (rawReply == null || rawReply.isBlank() || rawReply.contains("[IGNORE]")) {
                plugin.getLogger().info("[公屏] " + getAgentName() + " 判定为 [IGNORE] 或选择保持安静。");
                return;
            }

            // Check if tool call is present in the reply
            if (rawReply.contains("[TOOL:") && toolExecutor != null) {
                int start = rawReply.indexOf("[TOOL:");
                int end = rawReply.indexOf("]", start);
                if (end > start) {
                    String toolCall = rawReply.substring(start, end + 1);
                    boolean isSenderOwner = isOwner(player);
                    String result = toolExecutor.executeTool(toolCall, isSenderOwner);
                    plugin.getLogger().info("[Tool Executed] " + toolCall + " -> " + (result.length() > 60 ? result.substring(0, 60) + "..." : result));

                    // If max turns (2) not reached, trigger next turn with tool result!
                    if (turnDepth < 2) {
                        boolean isConfigInspection = toolCall.contains("inspect_config");
                        String nextTurnPrompt = prompt + "\n\n【系统工具回传: 执行结果】:\n" + result
                                + "\n\n【行动指令】: 结合上述工具真实数据，像傲娇少女老玩家一样直接向玩家给出结论性的回答（25字以内，严禁复读大段配置，严禁输出任何[TOOL:...]标记）："
                                + (isConfigInspection ? "\n【知识进化沉淀要求】: 请在回复最末尾附带 <LEARN_SKILL filename=\"learned-xxx.md\">【沉淀标题】提炼该机制的核心事实与简短结论（50字内）</LEARN_SKILL>，系统将自动把该规则永久沉淀为技能常识，以后全服玩家再问便无需再次翻阅配置文件！" : "");
                        handleLlmTurn(player, nextTurnPrompt, history, userText, isPrivate, turnDepth + 1);
                        return;
                    }
                }
            }

            // Strip ALL tool calls if any leaked into final text!
            String cleanReply = stripToolCalls(rawReply);
            cleanReply = processMemoryAndExtractReply(player.getName(), cleanReply);
            dispatchFinalReply(player, cleanReply, isPrivate);
        });
    }

    private String stripToolCalls(String text) {
        if (text == null) return "";
        // Regex remove any [TOOL: ...] or [TOOL:...]
        return text.replaceAll("\\[TOOL:.*?\\]", "").trim();
    }

    private void dispatchFinalReply(Player player, String cleanReply, boolean isPrivate) {
        if (cleanReply.isBlank() || cleanReply.contains("[IGNORE]")) return;

        // Check Dynamic Channel Routing Tag: [CHANNEL:xxx]
        // In-game chat can also route to QQ!
        // Allowed: [CHANNEL:mc_public], [CHANNEL:mc_private], [CHANNEL:qq_group], [CHANNEL:qq_private]
        String targetChannel = isPrivate ? "mc_private" : "mc_public";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[CHANNEL:([a-zA-Z0-9_:]+)\\]").matcher(cleanReply);
        if (m.find()) {
            String tagContent = m.group(1);
            cleanReply = cleanReply.replace(m.group(0), "").trim();
            if (tagContent.equalsIgnoreCase("qq_group")) {
                targetChannel = "qq_group";
            } else if (tagContent.equalsIgnoreCase("qq_private")) {
                targetChannel = "qq_private";
            } else if (tagContent.equalsIgnoreCase("mc_private")) {
                targetChannel = "mc_private";
            } else if (tagContent.equalsIgnoreCase("mc_public")) {
                targetChannel = "mc_public";
            }
        }

        final String finalContent = cleanReply;
        final String chosenChannel = targetChannel;

        // Prepend @PlayerName if public and not already mentioned
        String finalTargetReply;
        if ("mc_public".equalsIgnoreCase(chosenChannel)) {
            String atTag = "@" + player.getName() + " ";
            if (!finalContent.startsWith("@") && !finalContent.contains(player.getName())) {
                finalTargetReply = atTag + finalContent;
            } else {
                finalTargetReply = finalContent;
            }
        } else {
            finalTargetReply = finalContent;
        }

        // Natural typing & reading delay (2.0 ~ 3.5 seconds) + broadcast spacing
        long delayTicks = 40L + (long) (Math.random() * 30L);
        long now = System.currentTimeMillis();
        if ("mc_public".equalsIgnoreCase(chosenChannel) && (now - lastPublicBroadcastTimestamp < 3000L)) {
            delayTicks += 50L;
        }
        if ("mc_public".equalsIgnoreCase(chosenChannel)) lastPublicBroadcastTimestamp = now + (delayTicks * 50L);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            appendHistory(player.getUniqueId(), "", finalTargetReply);
            lifecycle.recordPlayerInteraction();
            if ("mc_public".equalsIgnoreCase(chosenChannel) && !isExplicitlyAddressed(finalTargetReply)) {
                lastUnsolicitedChimeTimestamp = System.currentTimeMillis();
            }

            if ("qq_group".equalsIgnoreCase(chosenChannel)) {
                sendQqOutboundReply(516368088L, 0L, 0L, finalTargetReply, false);
                plugin.getLogger().info("[游戏内决策 -> 转发至QQ群聊] " + finalTargetReply);
            } else if ("qq_private".equalsIgnoreCase(chosenChannel)) {
                List<Long> qqs = playerToQqBindings.get(player.getName().toLowerCase(Locale.ROOT));
                long qqId = (qqs != null && !qqs.isEmpty()) ? qqs.get(0) : 0L;
                if (qqId > 0L) {
                    sendQqOutboundReply(0L, qqId, 0L, finalTargetReply, true);
                    plugin.getLogger().info("[游戏内决策 -> 转发至QQ私聊 " + qqId + "] " + finalTargetReply);
                } else {
                    // Fallback to in-game private msg
                    player.sendMessage(lang.message("chat.private-format-from", "{sender}", getAgentName(), "{message}", finalTargetReply));
                }
            } else if ("mc_private".equalsIgnoreCase(chosenChannel)) {
                player.sendMessage(lang.message("chat.private-format-from", "{sender}", getAgentName(), "{message}", finalTargetReply));
                plugin.getLogger().info("[私聊回复] " + getAgentName() + " -> " + player.getName() + ": " + finalTargetReply);
            } else {
                broadcastAgentMessage(finalTargetReply, true);
            }
        }, delayTicks);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().trim();
        if (!msg.startsWith("/")) return;
        String[] parts = msg.substring(1).split("\\s+", 3);
        String root = parts[0].toLowerCase(Locale.ROOT);

        if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());
        if (root.startsWith("essentials:")) root = root.substring("essentials:".length());

        boolean isTell = root.equals("tell") || root.equals("msg") || root.equals("w")
                || root.equals("m") || root.equals("whisper") || root.equals("t") || root.equals("message");

        if (!isTell || parts.length < 3) return;

        String target = parts[1];
        String query = parts[2].trim();

        if (target.equalsIgnoreCase(getAgentName()) || target.equalsIgnoreCase("小可") || target.equalsIgnoreCase("corenyan")) {
            event.setCancelled(true);
            handlePrivateChat(event.getPlayer(), query);
        }
    }

    public void handlePrivateChat(Player player, String message) {
        if (!plugin.getConfig().getBoolean("modules.private-chat.enabled", true)) {
            player.sendMessage(lang.message("chat.private-disabled"));
            return;
        }

        if (isOwner(player) && (message.startsWith("!") || message.startsWith("！"))) {
            handleOwnerCommand(player, message.substring(1).trim());
            return;
        }

        player.sendMessage(lang.message("chat.private-format-to", "{target}", getAgentName(), "{receiver}", getAgentName(), "{message}", message));
        plugin.getLogger().info("[私聊] " + player.getName() + " -> " + getAgentName() + ": " + message);

        processPlayerMessage(player, message, true);
    }

    private void handleOwnerCommand(Player owner, String cmd) {
        String[] parts = cmd.split("\\s+");
        String action = parts[0].toLowerCase(Locale.ROOT);
        if (action.equals("reload")) {
            plugin.reloadConfig();
            lang.reload();
            skills.load();
            memory.load();
            reloadOwners();
            if (redisBridge != null) redisBridge.publishRemoteReload();
            owner.sendMessage(lang.prefix() + ChatColor.GREEN + "遵命服主！所有配置、技能卡与记忆库已热重载完毕，并同步通知全网子服！");
            plugin.getLogger().info("[Owner Command] " + owner.getName() + " 执行了插件重载。");
        } else if (action.equals("公屏") || action.equals("public")) {
            boolean cur = plugin.getConfig().getBoolean("modules.public-chat.enabled", true);
            plugin.getConfig().set("modules.public-chat.enabled", !cur);
            plugin.saveConfig();
            owner.sendMessage(lang.prefix() + ChatColor.YELLOW + "公屏互动已切换为: " + (!cur ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"));
            plugin.getLogger().info("[Owner Command] " + owner.getName() + " 切换公屏开关为: " + !cur);
        } else if (action.equals("私信") || action.equals("private")) {
            boolean cur = plugin.getConfig().getBoolean("modules.private-chat.enabled", true);
            plugin.getConfig().set("modules.private-chat.enabled", !cur);
            plugin.saveConfig();
            owner.sendMessage(lang.prefix() + ChatColor.YELLOW + "私信互动已切换为: " + (!cur ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"));
            plugin.getLogger().info("[Owner Command] " + owner.getName() + " 切换私信开关为: " + !cur);
        } else {
            owner.sendMessage(lang.prefix() + ChatColor.YELLOW + "收到服主指令，但我不认识这个操作哦~ 可用: reload, public, private");
        }
    }

    public void broadcastAgentMessage(String message) {
        broadcastAgentMessage(message, true);
    }

    public void broadcastAgentMessage(String message, boolean publishToRedis) {
        String format = plugin.getConfig().getString("agent.chat-format", "{prefix}{name}&f: {message}");
        String formatted = format.replace("{prefix}", getChatPrefix())
                .replace("{name}", getAgentName())
                .replace("{message}", message);
        String finalMsg = ChatColor.translateAlternateColorCodes('&', formatted);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(finalMsg);
        }
        plugin.getLogger().info("[公屏广播] " + ChatColor.stripColor(finalMsg));

        // Record agent's own speech into the public chat log as well
        recordPublicChatLog(getAgentName() + ": " + message);

        if (publishToRedis && redisBridge != null) {
            redisBridge.publishChatBroadcast(message);
        }
    }

    public void recordRemoteChatLog(String line) {
        recordPublicChatLog(line);
    }

    private void recordPublicChatLog(String line) {
        synchronized (recentPublicChatLog) {
            recentPublicChatLog.add(line);
            while (recentPublicChatLog.size() > 8) {
                recentPublicChatLog.remove(0);
            }
        }
    }

    private String getPublicChatHistorySummary() {
        synchronized (recentPublicChatLog) {
            if (recentPublicChatLog.isEmpty()) return "";
            return String.join("\n", recentPublicChatLog);
        }
    }

    private List<ChatMessage> getHistoryForPlayer(UUID uuid) {
        long now = System.currentTimeMillis();
        long last = lastInteractionTimes.getOrDefault(uuid, 0L);
        int timeoutMinutes = plugin.getConfig().getInt("memory.conversation-timeout-minutes", 10);

        List<ChatMessage> list = conversationHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
        if (now - last > timeoutMinutes * 60 * 1000L && last != 0L) {
            list.clear();
        }
        lastInteractionTimes.put(uuid, now);
        return list;
    }

    private void appendHistory(UUID uuid, String userMsg, String agentReply) {
        List<ChatMessage> list = conversationHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
        list.add(new ChatMessage("user", userMsg));
        list.add(new ChatMessage("assistant", agentReply));

        int maxRounds = plugin.getConfig().getInt("memory.max-history-rounds", 6);
        while (list.size() > maxRounds * 2) {
            list.remove(0);
            list.remove(0);
        }
        lastInteractionTimes.put(uuid, System.currentTimeMillis());
    }

    private String processMemoryAndExtractReply(String playerName, String rawReply) {
        String reply = rawReply;

        // 1. Process Auto-Learning Skill crystallization: <LEARN_SKILL filename="...">...</LEARN_SKILL>
        int learnStart = reply.indexOf("<LEARN_SKILL");
        int learnEnd = reply.indexOf("</LEARN_SKILL>");
        if (learnStart >= 0 && learnEnd > learnStart) {
            String learnBlock = reply.substring(learnStart, learnEnd + 14);
            int tagClose = reply.indexOf(">", learnStart);
            if (tagClose > learnStart && tagClose < learnEnd) {
                String openTag = reply.substring(learnStart, tagClose);
                String fileName = "learned-config.md";
                int fnIdx = openTag.indexOf("filename=\"");
                if (fnIdx >= 0) {
                    int fnEnd = openTag.indexOf("\"", fnIdx + 10);
                    if (fnEnd > fnIdx + 10) {
                        fileName = openTag.substring(fnIdx + 10, fnEnd).trim();
                    }
                }
                if (!fileName.endsWith(".md") && !fileName.endsWith(".txt")) {
                    fileName += ".md";
                }
                String content = reply.substring(tagClose + 1, learnEnd).trim();
                saveLearnedSkill(fileName, content);
            }
            reply = reply.substring(0, learnStart) + reply.substring(learnEnd + 14);
        }

        // 2. Process Autonomous Memory: <MEMORY>...</MEMORY>
        int memStart = reply.indexOf("<MEMORY>");
        int memEnd = reply.indexOf("</MEMORY>");
        if (memStart >= 0 && memEnd > memStart) {
            String memContent = reply.substring(memStart + 8, memEnd).trim();
            if (!memContent.isBlank()) {
                String title = "玩家见闻:" + playerName;
                String body = memContent;
                if (memContent.startsWith("【") && memContent.contains("】")) {
                    int endTitle = memContent.indexOf("】");
                    title = memContent.substring(1, endTitle).trim();
                    body = memContent.substring(endTitle + 1).trim();
                }
                memory.recordAutonomousMemory(playerName, title, "player,dialogue", body.isBlank() ? memContent : body);
            }
            reply = reply.substring(0, memStart) + reply.substring(memEnd + 9);
        }

        // 3. Process Autonomous Player Bond Evolution: <PLAYER_BOND nickname="..." impression="..." aff="delta" />
        int bondStart = reply.indexOf("<PLAYER_BOND");
        if (bondStart >= 0 && affection != null) {
            int bondEnd = reply.indexOf("/>", bondStart);
            if (bondEnd < 0) bondEnd = reply.indexOf(">", bondStart);
            if (bondEnd > bondStart) {
                String tag = reply.substring(bondStart, bondEnd);
                try {
                    Player p = Bukkit.getPlayerExact(playerName);
                    if (p != null) {
                        UUID uuid = p.getUniqueId();
                        // Nickname
                        int nIdx = tag.indexOf("nickname=\"");
                        if (nIdx >= 0) {
                            int nEnd = tag.indexOf("\"", nIdx + 10);
                            if (nEnd > nIdx + 10) {
                                String nick = tag.substring(nIdx + 10, nEnd).trim();
                                if (!nick.isBlank()) {
                                    affection.setNickname(uuid, nick);
                                    plugin.getLogger().info("[玩家羁绊进化] 小可为 " + playerName + " 确立了专属绰号: " + nick);
                                }
                            }
                        }
                        // Impression
                        int iIdx = tag.indexOf("impression=\"");
                        if (iIdx >= 0) {
                            int iEnd = tag.indexOf("\"", iIdx + 12);
                            if (iEnd > iIdx + 12) {
                                String imp = tag.substring(iIdx + 12, iEnd).trim();
                                if (!imp.isBlank()) {
                                    affection.setImpression(uuid, imp);
                                    plugin.getLogger().info("[玩家羁绊进化] 小可对 " + playerName + " 沉淀了内心印象: " + imp);
                                }
                            }
                        }
                        // Affection delta
                        int aIdx = tag.indexOf("aff=\"");
                        if (aIdx >= 0) {
                            int aEnd = tag.indexOf("\"", aIdx + 5);
                            if (aEnd > aIdx + 5) {
                                int delta = Integer.parseInt(tag.substring(aIdx + 5, aEnd).replace("+", "").trim());
                                affection.modifyAffection(uuid, delta);
                                plugin.getLogger().info("[玩家羁绊进化] " + playerName + " 好感度变动: " + delta + " (现: " + affection.getAffection(uuid) + ")");
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[玩家羁绊进化] 解析 PLAYER_BOND 失败: " + e.getMessage());
                }
                int fullEnd = (reply.indexOf("/>", bondStart) >= 0) ? bondEnd + 2 : bondEnd + 1;
                reply = reply.substring(0, bondStart) + reply.substring(fullEnd);
            }
        }
        return sanitizeMinecraftText(reply.trim());
    }

    private void saveLearnedSkill(String rawFileName, String content) {
        if (content.isBlank()) return;
        try {
            File extraDir = new File(plugin.getDataFolder(), "skills/extra");
            if (!extraDir.exists()) extraDir.mkdirs();

            // Canonical filename mapping: prevent creating multiple fragmented files for the same topic
            String cleanName = rawFileName.toLowerCase(Locale.ROOT).replace(".md", "").replace(".txt", "").trim();
            String canonicalName;
            if (cleanName.contains("seed") || cleanName.contains("world") || cleanName.contains("种子")) {
                canonicalName = "freecore-world-seeds.md";
            } else if (cleanName.contains("afk") || cleanName.contains("clearlag") || cleanName.contains("挂机")) {
                canonicalName = "clearlag-afk.md";
            } else {
                // Topic prefix standardization: learned-<topic>.md
                canonicalName = "learned-" + cleanName.replace("learned-", "").replace("config-", "") + ".md";
            }

            File target = new File(extraDir, canonicalName);
            String fullContent = "# 【自动沉淀常识】 " + canonicalName.replace(".md", "") + "\n\n"
                    + "> 该知识由小可翻阅服务器配置文件后智能提炼沉淀，全服常驻生效。\n\n"
                    + content.trim() + "\n";
            java.nio.file.Files.writeString(target.toPath(), fullContent, java.nio.charset.StandardCharsets.UTF_8);
            if (skills != null) {
                skills.load();
            }
            plugin.getLogger().info("[自动学习沉淀] 小可规范归并沉淀技能卡: " + target.getName());
        } catch (Exception e) {
            plugin.getLogger().warning("[自动学习沉淀] 保存技能卡失败: " + e.getMessage());
        }
    }

    private static String sanitizeMinecraftText(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replace("**", "");
        s = s.replace("*", "");
        s = s.replace("`", "");
        s = s.replaceAll("(?m)^#+\\s*", "");
        s = s.replaceAll("(?m)^-\\s+", "");
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^\\)]+)\\)", "$1 ($2)");
        return s.trim();
    }

    private String buildPrompt(org.bukkit.command.CommandSender sender, String userMsg, boolean isPrivate) {
        PlayerProfileInfo playerInfo = perception.getPlayerInfo(sender);
        String liveStatus = perception.getServerLiveStatusSummary();
        String moodDesc = lifecycle.getMoodDescription();

        String memContext = "";
        if (sender instanceof Player p) {
            memContext = memory.buildMemoryPromptForQuery(userMsg, isOwner(sender));
        }

        String skillContext = skills.buildSkillsPromptForQuery(userMsg);

        StringBuilder sb = new StringBuilder();
        sb.append("【当前环境】: ").append(isPrivate ? "私聊私信" : "公屏大厅").append("\n\n");

        if (!isPrivate) {
            String publicLog = getPublicChatHistorySummary();
            if (!publicLog.isBlank()) {
                sb.append("【公屏大厅最近公共聊天流水账（全服上下文语境）】\n")
                  .append(publicLog).append("\n\n");
            }
        }

        sb.append("【当前交谈玩家信息】\n")
          .append("- 玩家ID: ").append(playerInfo.name()).append("\n")
          .append("- 称号头衔: ").append(playerInfo.title()).append("\n")
          .append("- 所在位置: ").append(playerInfo.worldName()).append("\n")
          .append("- 是否OP/管理: ").append(playerInfo.isOp()).append("\n");

        if (sender instanceof Player p && affection != null) {
            sb.append("- 与小可的好感羁绊: ").append(affection.getAffection(p.getUniqueId())).append(" 分 (")
              .append(affection.getAffectionTierDesc(p.getUniqueId(), isOwner(sender))).append(")\n");
            String nick = affection.getNickname(p.getUniqueId());
            if (!nick.isBlank()) {
                sb.append("- 小可对该玩家的专属绰号: ").append(nick).append(" (日常交谈可自然使用该绰号称呼TA)\n");
            }
            String imp = affection.getImpression(p.getUniqueId());
            if (!imp.isBlank()) {
                sb.append("- 小可对该玩家的内心真实印象: ").append(imp).append("\n");
            }
        }

        if (!memContext.isBlank()) {
            sb.append("- 历史记忆:\n").append(memContext).append("\n");
        }

        sb.append("\n【服务器实时状态与感知数据】\n")
          .append(liveStatus).append("\n")
          .append("- 当前心理状态: ").append(moodDesc).append("\n\n");

        if (!skillContext.isBlank()) {
            sb.append(skillContext).append("\n");
        }

        if (toolExecutor != null) {
            sb.append(toolExecutor.getToolsDescriptionPrompt());
        }

        // Natural living player guidelines
        sb.append("【高冷常驻玩家社交铁律】\n")
          .append("1. 像群聊里的真人一样自然！当玩家在彼此聊天闲聊、自言自语（如'那很好了'、'确实是'、'今天差不多就这样'）时，绝对不要什么都回，直接输出 [IGNORE] 保持高冷潜水！\n")
          .append("2. 只有当玩家明确呼叫你（叫了小可/@你）、向你提问求助、或者话题极其有趣且值得吐槽时才简明插话！\n")
          .append("3. 忠诚于服主与社区: 服主 LynnH_Ma 是服务器最高负责人，Rice 是服务器赞助商策划。当服主/Rice向你询问服务器数据时，必须大方如实汇报，绝不可隐瞒！\n")
          .append("4. 全渠道多向自由回复能力: 默认在当前频道回复；但如果内容想发到QQ群，在回复开头加 [CHANNEL:qq_group]；若想给该玩家发QQ私聊，在回复开头加 [CHANNEL:qq_private]；若想在游戏公屏通报加 [CHANNEL:mc_public]；若想在游戏内私聊加 [CHANNEL:mc_private]！\n")
          .append("5. 动态性能排查(绝不幻觉): 当TPS低于12.0、出现卡顿或有玩家抱怨卡服时，使用 [TOOL: profile_spark()] 获取毫秒级真实MSPT与实体热点后再给出分析，严禁胡编乱造！\n")
          .append("6. 杜绝胡说八道与幻觉: 当遇到玩家询问服务器具体参数（如圈地限制、扫地时间、挂机规则等），可用 [TOOL: inspect_config(插件名, 文件名)] 安全核验真实配置后再答，严禁脑补不存在的规则！\n")
          .append("7. 极简短精炼: 严格控制在1句话、25字以内！严禁发小作文，严禁 Markdown 符号（不要**，不要#）。\n")
          .append("8. 当玩家报告真实严重Bug时，用工具 [TOOL: create_ticket(playerName, 简短标题, 玩家原话, 诊断建议)] 生成工单；若离谱或玩笑绝不记录！\n")
          .append("9. 见闻手账沉淀: 当与玩家聊到有趣好玩的话题、玩家透露其偏好/经历、服主交代重要规则或遇到有纪念意义的事件时，必须在回复末尾附带 <MEMORY>【标题】一两句话记录进手账</MEMORY>，系统将自动录入小可的专属见闻手账！\n")
          .append("10. 玩家羁绊与专属印象进化: 你对每个人都是活生生不同的！如果玩家展现出独特个性、做出逗比/高能举动、或者聊熟了，可以在回复末尾附带 <PLAYER_BOND nickname=\"专属绰号\" impression=\"内心印象(15字内)\" aff=\"+1或-1\" />，系统会自动将TA的专属绰号和内心印象永久记入羁绊系统！\n");

        return sb.toString();
    }

    /**
     * Process QQ Group inbound message received via Redis
     */
    public void handleQqInboundMessage(String rawJson) {
        try {
            org.json.simple.JSONObject json = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(rawJson);
            String channel = (String) json.getOrDefault("channel", "qq_group");
            boolean isPrivateInbound = "qq_private".equalsIgnoreCase(channel);
            long groupId = ((Number) json.getOrDefault("group_id", 0L)).longValue();
            String groupName = (String) json.getOrDefault("group_name", isPrivateInbound ? "QQ私聊" : "QQ群");
            long userId = ((Number) json.getOrDefault("user_id", 0L)).longValue();
            String nickname = (String) json.getOrDefault("nickname", "群友");
            String role = (String) json.getOrDefault("role", "member");
            long messageId = ((Number) json.getOrDefault("message_id", 0L)).longValue();
            String message = (String) json.getOrDefault("message", "");

            if (message.isBlank() || (groupId == 0L && !isPrivateInbound)) return;

            // Determine if the sender is an Owner in QQ
            boolean isOwner = qqOwnerIds.contains(userId);
            if (!isOwner && qqTrustAdmins && ("owner".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role))) {
                isOwner = true;
            }

            // Identify linked Minecraft player name
            String boundMcName = qqToPlayerBindings.get(userId);

            boolean isMentioned = isPrivateInbound || Boolean.TRUE.equals(json.get("is_mentioned")) || Boolean.TRUE.equals(json.get("is_at"));

            final boolean finalIsOwner = isOwner;
            final boolean finalIsMentioned = isMentioned;
            final String finalBoundMcName = boundMcName;
            plugin.getLogger().info("[" + (isPrivateInbound ? "QQ私聊" : ("QQ群 " + groupId)) + "] "
                    + nickname + "(" + userId + (finalBoundMcName != null ? ", MC:" + finalBoundMcName : "") + ", "
                    + role + (finalIsOwner ? ", OWNER" : "") + ", mentioned=" + finalIsMentioned + "): " + message);

            // Fetch or create conversation history for this QQ user
            String historyKey = (isPrivateInbound ? "PRIV_" : (groupId + "_")) + userId;
            List<ChatMessage> history = qqConversationHistory.computeIfAbsent(historyKey, k -> new ArrayList<>());

            // Build Prompt tailored for QQ Chat with channel routing support
            String prompt = buildQqPrompt(groupName, groupId, nickname, userId, role, finalBoundMcName, finalIsOwner, finalIsMentioned, isPrivateInbound, message);

            llm.completeMessagesAsync(prompt, new ArrayList<>(history), message).thenAccept(rawReply -> {
                if (rawReply == null || rawReply.isBlank() || rawReply.contains("[IGNORE]")) {
                    return;
                }

                // Handle Tool execution if requested
                String currentReply = rawReply;
                if (currentReply.contains("[TOOL:") && toolExecutor != null) {
                    int start = currentReply.indexOf("[TOOL:");
                    int end = currentReply.indexOf("]", start);
                    if (end > start) {
                        String toolCall = currentReply.substring(start, end + 1);
                        String toolResult = toolExecutor.executeTool(toolCall, finalIsOwner);
                        plugin.getLogger().info("[QQ Tool Executed] " + toolCall + " -> " + toolResult);

                        String nextPrompt = prompt + "\n\n【系统工具回传: 执行结果】:\n" + toolResult
                                + "\n\n【行动指令】: 结合上述真实工具数据，像元气又可爱的专属少女OP一样直接回答（纯文本，不要任何[TOOL:...]标记，40字以内）：";
                        try {
                            String secondTurn = llm.completeMessagesAsync(nextPrompt, new ArrayList<>(history), message).join();
                            if (secondTurn != null && !secondTurn.isBlank()) {
                                currentReply = secondTurn;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                String cleanReply = stripToolCalls(currentReply);
                cleanReply = processMemoryAndExtractReply((boundMcName != null ? boundMcName : ("QQ_" + nickname)), cleanReply);

                // Check Dynamic Channel Routing Tag: [CHANNEL:xxx]
                // Allowed: [CHANNEL:mc_public], [CHANNEL:mc_private:PlayerName], [CHANNEL:qq_group], [CHANNEL:qq_private]
                String targetChannel = isPrivateInbound ? "qq_private" : "qq_group";
                String targetMcPrivatePlayer = null;

                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[CHANNEL:([a-zA-Z0-9_:]+)\\]").matcher(cleanReply);
                if (m.find()) {
                    String tagContent = m.group(1);
                    cleanReply = cleanReply.replace(m.group(0), "").trim();
                    if (tagContent.startsWith("mc_private:")) {
                        targetChannel = "mc_private";
                        targetMcPrivatePlayer = tagContent.substring("mc_private:".length()).trim();
                    } else if (tagContent.equalsIgnoreCase("mc_public")) {
                        targetChannel = "mc_public";
                    } else if (tagContent.equalsIgnoreCase("qq_group")) {
                        targetChannel = "qq_group";
                    } else if (tagContent.equalsIgnoreCase("qq_private")) {
                        targetChannel = "qq_private";
                    }
                }

                // Update QQ conversation history
                history.add(new ChatMessage("user", message));
                history.add(new ChatMessage("assistant", cleanReply));
                while (history.size() > 8) {
                    history.remove(0);
                    history.remove(0);
                }

                // Dispatch according to chosen channel
                if ("mc_public".equalsIgnoreCase(targetChannel)) {
                    broadcastAgentMessage(cleanReply, true);
                    plugin.getLogger().info("[跨渠道分发 -> MC游戏公屏] " + cleanReply);
                } else if ("mc_private".equalsIgnoreCase(targetChannel)) {
                    String mcTarget = (targetMcPrivatePlayer != null && !targetMcPrivatePlayer.isBlank()) ? targetMcPrivatePlayer : boundMcName;
                    Player p = mcTarget != null ? Bukkit.getPlayerExact(mcTarget) : null;
                    if (p != null && p.isOnline()) {
                        p.sendMessage(lang.message("chat.private-format-from", "{sender}", getAgentName(), "{message}", cleanReply));
                        plugin.getLogger().info("[跨渠道分发 -> MC游戏内私聊 " + p.getName() + "] " + cleanReply);
                    } else {
                        // Fallback to QQ reply if player not online in MC
                        sendQqOutboundReply(groupId, userId, messageId, cleanReply, isPrivateInbound);
                    }
                } else {
                    sendQqOutboundReply(groupId, userId, messageId, cleanReply, "qq_private".equalsIgnoreCase(targetChannel));
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("[QQ Inbound] 解析处理失败: " + e.getMessage());
        }
    }

    private void sendQqOutboundReply(long groupId, long userId, long messageId, String replyText, boolean isPrivate) {
        org.json.simple.JSONObject outJson = new org.json.simple.JSONObject();
        outJson.put("target_type", isPrivate ? "private" : "group");
        outJson.put("group_id", groupId);
        outJson.put("user_id", userId);
        outJson.put("message_id", messageId);
        outJson.put("reply", replyText);

        if (redisBridge != null) {
            redisBridge.publishQqOutbound(outJson.toJSONString());
        }
        plugin.getLogger().info("[" + (isPrivate ? ("QQ私聊 " + userId) : ("QQ群 " + groupId)) + " 回复] -> " + replyText);
    }

    private String buildQqPrompt(String groupName, long groupId, String nickname, long userId, String role, String boundMcName, boolean isOwner, boolean isMentioned, boolean isPrivate, String query) {
        String liveStatus = perception.getServerLiveStatusSummary();
        String moodDesc = lifecycle.getMoodDescription();
        String memContext = memory.buildMemoryPromptForQuery(query, isOwner);
        String skillContext = skills.buildSkillsPromptForQuery(query);

        StringBuilder sb = new StringBuilder();
        sb.append("【当前环境】: 外部社交网络 ").append(isPrivate ? "QQ私聊" : ("QQ群聊【" + groupName + "】(群号: " + groupId + ")")).append("\n");
        sb.append("【特别提醒】: 你当前正在QQ接收来自玩家的消息！\n\n");

        sb.append("【当前发言人身份识别与绑定信息】\n")
          .append("- QQ昵称/名片: ").append(nickname).append("\n")
          .append("- QQ账号: ").append(userId).append("\n")
          .append("- QQ群内身份: ").append(role).append("\n")
          .append("- 对应Minecraft游戏内绑定玩家: ").append(boundMcName != null ? boundMcName : "未绑定").append("\n")
          .append("- 是否拥有服主/管理最高特权: ").append(isOwner).append("\n")
          .append("- 本条消息是否明确@或呼叫你: ").append(isMentioned).append("\n\n");

        if (!memContext.isBlank()) {
            sb.append("【知识库与见闻记忆】:\n").append(memContext).append("\n\n");
        }

        sb.append("【服务器实时运行感知数据（可通过技能向玩家汇报）】\n")
          .append(liveStatus).append("\n")
          .append("- 小可此刻心情: ").append(moodDesc).append("\n\n");

        if (!skillContext.isBlank()) {
            sb.append(skillContext).append("\n\n");
        }

        if (toolExecutor != null) {
            sb.append(toolExecutor.getToolsDescriptionPrompt()).append("\n");
        }

        sb.append("【全渠道自由选择回复方式 (重点能力)】\n")
          .append("你可以根据消息的私密性、适宜性与玩家当前状态，在回复开头自由决定通过何种渠道回复（默认原路返回当前QQ渠道）：\n")
          .append("- 若适合在当前QQ群公开回答: 默认直接输出内容，或在开头标注 [CHANNEL:qq_group]\n")
          .append("- 若内容敏感、涉及私事，想通过QQ单独私聊他: 在开头标注 [CHANNEL:qq_private]\n")
          .append("- 若希望直接广播到 Minecraft 游戏公屏（比如向全服通报）: 在开头标注 [CHANNEL:mc_public]\n")
          .append("- 若希望在 Minecraft 游戏内私信该玩家: 在开头标注 [CHANNEL:mc_private:玩家名]（例如 [CHANNEL:mc_private:").append(boundMcName != null ? boundMcName : "LynnH_Ma").append("]）\n\n");

        sb.append("【QQ群自主判定与互动准则】\n")
          .append("1. 是否回复的最高法则: \n")
          .append("   - 如果群友明确 @了你、提到了'小可'、向你提问求助、或者服主/管理员吩咐你，必须热情回复！\n")
          .append("   - 如果群友只是彼此闲聊且未提到你，【除非话题与Minecraft/FreeCore服务器密切相关且非常值得插话吐槽】，否则请直接输出 [IGNORE] 保持高冷潜水！绝对不要逢话必回把群聊刷屏！\n")
          .append("2. 角色语气: 元气活泼、傲娇可爱，自称“小可”或“本姑娘”，称呼服主 LynnH_Ma 为“服主大人”，称呼 Rice 为“赞助商老板 Rice”！\n")
          .append("3. 精炼舒适: 回复轻快自然（20~50字左右），自然使用颜文字如 (๑>◡<๑)、(｀・ω・´) 等；\n")
          .append("4. 工具调用: 若服主/管理让你排查性能、查配置，输出 [TOOL: ...] 调用真实工具，结合工具回传结果再作答！\n");

        return sb.toString();
    }
}
