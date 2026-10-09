package io.github.freecoreagent;

import io.github.freecoreagent.command.AgentCommand;
import io.github.freecoreagent.config.LanguageManager;
import io.github.freecoreagent.lifecycle.AgentLifecycleEngine;
import io.github.freecoreagent.llm.LlmClient;
import io.github.freecoreagent.memory.VaultMemoryManager;
import io.github.freecoreagent.perception.AgentPerceptionService;
import io.github.freecoreagent.redis.AgentRedisBridge;
import io.github.freecoreagent.affection.PlayerAffectionManager;
import io.github.freecoreagent.perception.WorldEventPerceptionListener;
import io.github.freecoreagent.tool.AgentToolExecutor;
import io.github.freecoreagent.ticket.TicketManager;
import io.github.freecoreagent.ticket.TicketGui;
import io.github.freecoreagent.service.AgentInteractionService;
import io.github.freecoreagent.skill.GitHubSkillSyncService;
import io.github.freecoreagent.skill.SkillManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class FreeCoreAgentPlugin extends JavaPlugin {
    private LanguageManager lang;
    private LlmClient llm;
    private SkillManager skills;
    private VaultMemoryManager memory;
    private AgentPerceptionService perception;
    private AgentLifecycleEngine lifecycle;
    private AgentInteractionService interaction;
    private AgentRedisBridge redisBridge;
    private GitHubSkillSyncService skillSync;
    private PlayerAffectionManager affection;
    private AgentToolExecutor toolExecutor;
    private TicketManager ticketManager;
    private TicketGui ticketGui;
    private io.github.freecoreagent.tab.VirtualTabPlayerManager tabPlayerManager;

    @Override
    public void onEnable() {
        // Save default config and ensure fresh disk reload
        this.saveDefaultConfig();
        this.reloadConfig();

        // Init language manager
        this.lang = new LanguageManager(this);
        this.lang.load();

        // Init LLM client
        this.llm = new LlmClient(this);

        // Init Skill knowledge base
        this.skills = new SkillManager(this);
        this.skills.load();

        // Init Memory Vault manager
        this.memory = new VaultMemoryManager(this);
        this.memory.load();

        // Init Perception Service
        this.perception = new AgentPerceptionService(this);

        // Init Affection Manager
        this.affection = new PlayerAffectionManager(this);
        this.affection.load();

        // Init Safe Tool Executor
        this.toolExecutor = new AgentToolExecutor(this, this.memory, this.affection);
        this.toolExecutor.setSkillManager(this.skills);

        // Init Ticket Manager & GUI
        this.ticketManager = new TicketManager(this);
        this.ticketManager.load();
        this.toolExecutor.setTicketManager(this.ticketManager);
        this.ticketGui = new TicketGui(this, this.ticketManager);
        this.getServer().getPluginManager().registerEvents(this.ticketGui, this);

        // Init Lifecycle & Mood Engine
        this.lifecycle = new AgentLifecycleEngine(this, this.perception, this.llm);

        // Init Interaction service
        this.interaction = new AgentInteractionService(this, this.lang, this.llm, this.skills, this.memory, this.perception, this.lifecycle, this.affection, this.toolExecutor);
        this.lifecycle.setInteractionService(this.interaction);
        if (this.getConfig().getBoolean("lifecycle.enabled", false)) {
            this.lifecycle.start();
        } else {
            this.getLogger().info("Agent 生命周期与定时自言自语已关闭 (纯被动按需互动模式)");
        }

        // Init Redis Bridge for Cross-Server Sync
        this.redisBridge = new AgentRedisBridge(
                this,
                // Received remote broadcast from other server
                msg -> this.interaction.broadcastAgentMessage(msg, false),
                // Received remote reload signal
                () -> {
                    this.reloadConfig();
                    this.lang.load();
                    this.skills.load();
                    this.memory.load();
                    this.interaction.reloadOwners();
                    if (this.tabPlayerManager != null) {
                        this.tabPlayerManager.reload();
                    }
                    this.getLogger().info("收到跨服 Redis 信号，本子服 FreeCoreAgent 配置已同步热重载完毕！");
                }
        );
        this.redisBridge.setOnRemoteChatStreamReceived(line -> this.interaction.recordRemoteChatLog(line));
        this.redisBridge.setOnRemoteToolRequestReceived(reqJson -> {
            try {
                org.json.simple.JSONObject obj = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(reqJson);
                String id = (String) obj.get("id");
                String toolCall = (String) obj.get("tool");
                if (id != null && toolCall != null && this.toolExecutor != null) {
                    this.toolExecutor.executeToolAsync(toolCall, true).thenAccept(result -> {
                        this.redisBridge.publishToolResponse(id, result);
                        this.getLogger().info("[Remote Tool RPC] 执行了来自中枢的大脑指令: " + toolCall + " -> " + (result.length() > 50 ? result.substring(0, 50) + "..." : result));
                    });
                }
            } catch (Exception ignored) {}
        });
        this.redisBridge.setOnRemoteTicketSyncReceived(() -> {
            this.ticketManager.load();
            this.getLogger().info("收到跨服工单同步信号，tickets.yml 已重新加载！");
        });
        this.redisBridge.start();
        this.ticketManager.setRedisBridge(this.redisBridge);
        this.perception.setRedisBridge(this.redisBridge);
        this.interaction.setRedisBridge(this.redisBridge);

        this.getServer().getPluginManager().registerEvents(this.interaction, this);

        // Register World Event Perception Listener
        this.getServer().getPluginManager().registerEvents(
                new WorldEventPerceptionListener(this, this.llm, this.interaction, this.affection), this);

        // Auto Sync GitHub Docs for Skills
        this.skillSync = new GitHubSkillSyncService(this, this.skills);
        this.skillSync.startAutoSync(this.getConfig().getLong("skills.auto-sync.interval-hours", 6L));

        // Init Virtual Tab Player Manager
        if (this.getConfig().getBoolean("agent.tab-player.enabled", true)) {
            this.tabPlayerManager = new io.github.freecoreagent.tab.VirtualTabPlayerManager(this);
            this.tabPlayerManager.start();
            this.getLogger().info("Virtual Tab Player (CoreNyan) 已成功载入并注入全服 Tab 列表与私聊补全！");
        }

        // Register Commands
        AgentCommand cmd = new AgentCommand(this, this.lang, this.interaction, this.skills, this.memory, this.llm);
        cmd.setTicketGui(this.ticketGui);
        PluginCommand fca = this.getCommand("freecoreagent");
        if (fca != null) {
            fca.setExecutor(cmd);
            fca.setTabCompleter(cmd);
        }

        this.getLogger().info("=========================================");
        this.getLogger().info("  FreeCoreAgent (小可 CoreNyan) 已成功装载！");
        this.getLogger().info("  公屏互动: " + this.getConfig().getBoolean("modules.public-chat.enabled", true));
        this.getLogger().info("  私信互动: " + this.getConfig().getBoolean("modules.private-chat.enabled", true));
        this.getLogger().info("  跨服 Redis 神经系统: 已连接 (全网互通与同步激活)");
        this.getLogger().info("  生命周期心跳与活人感知系统: 已激活 (状态: " + this.lifecycle.getMoodDescription() + ")");
        this.getLogger().info("=========================================");
    }

    @Override
    public void onDisable() {
        if (this.tabPlayerManager != null) {
            this.tabPlayerManager.stop();
        }
        if (this.redisBridge != null) {
            this.redisBridge.stop();
        }
        if (this.lifecycle != null) {
            this.lifecycle.stop();
        }
        this.getLogger().info("FreeCoreAgent (小可) 暂时退服休息啦~");
    }

    public LanguageManager getLang() { return lang; }
    public LlmClient getLlm() { return llm; }
    public SkillManager getSkills() { return skills; }
    public VaultMemoryManager getMemory() { return memory; }
    public AgentPerceptionService getPerception() { return perception; }
    public AgentLifecycleEngine getLifecycle() { return lifecycle; }
    public AgentInteractionService getInteraction() { return interaction; }
    public AgentRedisBridge getRedisBridge() { return redisBridge; }
    public io.github.freecoreagent.tab.VirtualTabPlayerManager getTabPlayerManager() { return tabPlayerManager; }
}
