package io.github.freecoreagent;

import io.github.freecoreagent.command.AgentCommand;
import io.github.freecoreagent.config.LanguageManager;
import io.github.freecoreagent.perception.AgentPerceptionService;
import io.github.freecoreagent.redis.AgentRedisBridge;
import io.github.freecoreagent.perception.WorldEventPerceptionListener;
import io.github.freecoreagent.tool.AgentToolExecutor;
import io.github.freecoreagent.ticket.TicketManager;
import io.github.freecoreagent.ticket.TicketGui;
import io.github.freecoreagent.service.AgentInteractionService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * FreeCoreAgent Plugin Main Class
 * Pure Gateway, Event Perception Sensor and Remote Tool RPC Executor.
 * Cognitive Brain, LLM Prompts, Memory Vault and Skills are managed exclusively by FreeCore-CoreNyan.
 */
public final class FreeCoreAgentPlugin extends JavaPlugin {
    private LanguageManager lang;
    private AgentPerceptionService perception;
    private AgentInteractionService interaction;
    private AgentRedisBridge redisBridge;
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

        // Init Perception Service
        this.perception = new AgentPerceptionService(this);

        // Init Safe Tool Executor
        this.toolExecutor = new AgentToolExecutor(this);

        // Init Ticket Manager & GUI
        this.ticketManager = new TicketManager(this);
        this.ticketManager.load();
        this.toolExecutor.setTicketManager(this.ticketManager);
        this.ticketGui = new TicketGui(this, this.ticketManager);
        this.getServer().getPluginManager().registerEvents(this.ticketGui, this);

        // Init Interaction service
        this.interaction = new AgentInteractionService(this, this.lang, this.perception, this.toolExecutor);

        // Init Redis Bridge for Cross-Server Sync and CoreNyan Communication
        this.redisBridge = new AgentRedisBridge(
                this,
                // Received remote broadcast from other server
                msg -> this.interaction.broadcastAgentMessage(msg, false),
                // Received remote reload signal
                () -> {
                    this.reloadConfig();
                    this.lang.load();
                    this.interaction.reloadOwners();
                    this.getLogger().info("已接收远端信号并热重载配置！");
                }
        );

        // Wire up Remote Tool RPC from CoreNyan
        this.redisBridge.setOnRemoteToolRequestReceived(reqJson -> {
            try {
                org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
                org.json.simple.JSONObject obj = (org.json.simple.JSONObject) parser.parse(reqJson);
                String id = (String) obj.get("id");
                String tool = (String) obj.get("tool");
                org.json.simple.JSONArray argsArr = (org.json.simple.JSONArray) obj.get("args");
                String[] args = new String[argsArr != null ? argsArr.size() : 0];
                if (argsArr != null) {
                    for (int i = 0; i < argsArr.size(); i++) {
                        args[i] = String.valueOf(argsArr.get(i));
                    }
                }
                String result = this.toolExecutor.executeTool(tool, args, true);
                this.redisBridge.publishToolResponse(id, result);
            } catch (Exception e) {
                this.getLogger().warning("处理远程工具 RPC 异常: " + e.getMessage());
            }
        });

        this.redisBridge.start();
        this.interaction.setRedisBridge(this.redisBridge);

        // Register in-game public chat listener
        this.getServer().getPluginManager().registerEvents(new WorldEventPerceptionListener(this, this.perception, this.interaction), this);

        // Register Command Executor
        AgentCommand commandExecutor = new AgentCommand(this, this.lang, this.interaction);
        commandExecutor.setTicketGui(this.ticketGui);
        PluginCommand cmd = this.getCommand("freecoreagent");
        if (cmd != null) {
            cmd.setExecutor(commandExecutor);
            cmd.setTabCompleter(commandExecutor);
        }

        // Init Virtual TAB Player for CoreNyan
        this.tabPlayerManager = new io.github.freecoreagent.tab.VirtualTabPlayerManager(this);
        this.tabPlayerManager.start();

        this.getLogger().info("FreeCoreAgent 网关插件启动完毕！(认知中枢与记忆交由 CoreNyan 统一管理)");
    }

    @Override
    public void onDisable() {
        if (this.tabPlayerManager != null) {
            this.tabPlayerManager.stop();
        }
        if (this.redisBridge != null) {
            this.redisBridge.stop();
        }
        this.getLogger().info("FreeCoreAgent 网关插件已卸载。");
    }

    public LanguageManager getLang() { return lang; }
    public AgentPerceptionService getPerception() { return perception; }
    public AgentInteractionService getInteraction() { return interaction; }
    public AgentRedisBridge getRedisBridge() { return redisBridge; }
    public io.github.freecoreagent.tab.VirtualTabPlayerManager getTabPlayerManager() { return tabPlayerManager; }
}