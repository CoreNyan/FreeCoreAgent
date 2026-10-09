package io.github.freecoreagent.redis;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Handles lightweight Redis Pub/Sub and cross-server player tracking.
 * Automatically discovers Redis credentials from HuskSync/config.yml or plugin config.
 */
public final class AgentRedisBridge {
    private static final String CHANNEL_BROADCAST = "freecore:agent:broadcast";
    private static final String CHANNEL_CONTROL = "freecore:agent:control";
    private static final String CHANNEL_CHAT_STREAM = "freecore:agent:chatstream";
    private static final String CHANNEL_TICKET_SYNC = "freecore:agent:tickets";
    public static final String CHANNEL_QQ_INBOUND = "freecore:agent:qq:inbound";
    public static final String CHANNEL_QQ_OUTBOUND = "freecore:agent:qq:outbound";
    public static final String CHANNEL_MC_INBOUND = "freecore:agent:mc:inbound";
    public static final String CHANNEL_MC_BROADCAST = "freecore:agent:mc:broadcast";
    public static final String CHANNEL_MC_PRIVATE = "freecore:agent:mc:private";
    public static final String CHANNEL_TOOL_REQUEST = "freecore:agent:tool:request";
    public static final String CHANNEL_TOOL_RESPONSE = "freecore:agent:tool:response";

    private final JavaPlugin plugin;
    private final String serverId;
    private final Consumer<String> onRemoteBroadcastReceived;
    private final Runnable onRemoteReloadRequested;
    private Consumer<String> onRemoteChatStreamReceived;
    private Runnable onRemoteTicketSyncReceived;
    private Consumer<String> onRemoteQqInboundReceived;
    private Consumer<String> onRemoteToolRequestReceived;

    private volatile boolean running = false;
    private Thread subscriberThread;

    public AgentRedisBridge(JavaPlugin plugin, Consumer<String> onRemoteBroadcastReceived, Runnable onRemoteReloadRequested) {
        this.plugin = plugin;
        this.serverId = plugin.getDataFolder().getAbsolutePath().contains("Tech") ? "technical" : "survival";
        this.onRemoteBroadcastReceived = onRemoteBroadcastReceived;
        this.onRemoteReloadRequested = onRemoteReloadRequested;
    }

    public void setOnRemoteChatStreamReceived(Consumer<String> onRemoteChatStreamReceived) {
        this.onRemoteChatStreamReceived = onRemoteChatStreamReceived;
    }

    public void setOnRemoteTicketSyncReceived(Runnable onRemoteTicketSyncReceived) {
        this.onRemoteTicketSyncReceived = onRemoteTicketSyncReceived;
    }

    public void setOnRemoteToolRequestReceived(Consumer<String> onRemoteToolRequestReceived) {
        this.onRemoteToolRequestReceived = onRemoteToolRequestReceived;
    }

    public void publishToolResponse(String id, String result) {
        org.json.simple.JSONObject obj = new org.json.simple.JSONObject();
        obj.put("id", id);
        obj.put("result", result);
        executeRedisAsync("PUBLISH", CHANNEL_TOOL_RESPONSE, obj.toJSONString());
    }

    public void publishMcInbound(String jsonPayload) {
        executeRedisAsync("PUBLISH", CHANNEL_MC_INBOUND, jsonPayload);
    }

    public record RedisCredentials(String host, int port, String password) {}

    public RedisCredentials getCredentials() {
        String host = plugin.getConfig().getString("redis.host", "");
        int port = plugin.getConfig().getInt("redis.port", 0);
        String pass = plugin.getConfig().getString("redis.password", "");

        if (!host.isBlank() && port > 0) {
            return new RedisCredentials(host, port, pass);
        }

        // Auto-discover from TAB/config.yml or HuskSync/config.yml
        try {
            File tabFile = new File(plugin.getDataFolder().getParentFile(), "TAB/config.yml");
            if (tabFile.exists()) {
                YamlConfiguration c = YamlConfiguration.loadConfiguration(tabFile);
                String url = c.getString("redis.url", "");
                if (url.contains("@")) {
                    // redis://:password@host:port/db
                    String afterAt = url.substring(url.indexOf('@') + 1);
                    String hostPort = afterAt.split("/")[0];
                    String[] hp = hostPort.split(":");
                    String tabHost = hp[0];
                    int tabPort = hp.length > 1 ? Integer.parseInt(hp[1]) : 6379;

                    String beforeAt = url.substring(0, url.indexOf('@'));
                    String tabPass = beforeAt.contains(":") ? beforeAt.substring(beforeAt.lastIndexOf(':') + 1) : "";
                    return new RedisCredentials(tabHost, tabPort, tabPass);
                }
            }

            File huskSyncFile = new File(plugin.getDataFolder().getParentFile(), "HuskSync/config.yml");
            if (huskSyncFile.exists()) {
                YamlConfiguration c = YamlConfiguration.loadConfiguration(huskSyncFile);
                return new RedisCredentials(
                        c.getString("redis.credentials.host", "127.0.0.1"),
                        c.getInt("redis.credentials.port", 6379),
                        c.getString("redis.credentials.password", "")
                );
            }
        } catch (Exception ignored) {}

        return new RedisCredentials("127.0.0.1", 6379, "");
    }

    public void start() {
        stop();
        running = true;
        subscriberThread = new Thread(this::runSubscriber, "FreeCoreAgent-RedisSub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        startOnlinePresenceHeartbeat();
        plugin.getLogger().info("AgentRedisBridge started on server node: " + serverId);
    }

    private void startOnlinePresenceHeartbeat() {
        // Register CoreNyan as online player in Redis so /msg and cross-server commands tab-complete CoreNyan!
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!running) return;
            try {
                executeRedisAsync("SET", "fce:online:name:corenyan", serverId, "EX", "60");
                executeRedisAsync("SET", "fce:online:name:corenyan:realname", "CoreNyan", "EX", "60");
                executeRedisAsync("SET", "fce:online:name:corenyan:uuid", "c8846a11-73a4-3acf-b524-11179afdd76d", "EX", "60");
            } catch (Throwable ignored) {}
        }, 10L, 20L * 25L); // Every 25s refresh
    }

    public void stop() {
        running = false;
        if (subscriberThread != null) {
            subscriberThread.interrupt();
            subscriberThread = null;
        }
    }

    public void publishChatBroadcast(String formattedMessage) {
        String payload = serverId + "|" + formattedMessage;
        executeRedisAsync("PUBLISH", CHANNEL_BROADCAST, payload);
    }

    public void publishRemoteReload() {
        String payload = serverId + "|RELOAD";
        executeRedisAsync("PUBLISH", CHANNEL_CONTROL, payload);
    }

    public Map<String, List<String>> getNetworkOnlinePlayers() {
        Map<String, List<String>> map = new ConcurrentHashMap<>();
        try {
            RedisCredentials creds = getCredentials();
            try (Socket socket = new Socket(creds.host(), creds.port())) {
                socket.setSoTimeout(1500);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                if (!creds.password().isBlank()) {
                    sendCommand(out, "AUTH", creds.password());
                    readResponse(in);
                }

                sendCommand(out, "KEYS", "fce:online:name:*");
                String[] keys = readArrayResponse(in);

                if (keys != null) {
                    for (String key : keys) {
                        sendCommand(out, "GET", key);
                        String srv = readBulkString(in);
                        if (srv != null && !srv.isBlank()) {
                            String playerName = key.substring("fce:online:name:".length());
                            map.computeIfAbsent(srv.toLowerCase(), k -> new ArrayList<>()).add(playerName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // fallback if redis error
        }
        return map;
    }

    private void runSubscriber() {
        while (running) {
            try {
                RedisCredentials creds = getCredentials();
                try (Socket socket = new Socket(creds.host(), creds.port())) {
                    socket.setSoTimeout(0); // block indefinitely
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();

                    if (!creds.password().isBlank()) {
                        sendCommand(out, "AUTH", creds.password());
                        readResponse(in);
                    }

                    sendCommand(out, "SUBSCRIBE", CHANNEL_BROADCAST, CHANNEL_CONTROL, CHANNEL_CHAT_STREAM, CHANNEL_TICKET_SYNC, CHANNEL_MC_BROADCAST, CHANNEL_MC_PRIVATE, CHANNEL_TOOL_REQUEST);

                    while (running) {
                        String[] array = readArrayResponse(in);
                        if (array != null && array.length == 3 && "message".equalsIgnoreCase(array[0])) {
                            String channel = array[1];
                            String message = array[2];
                            handleIncomingMessage(channel, message);
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void handleIncomingMessage(String channel, String message) {
        if (CHANNEL_MC_BROADCAST.equals(channel)) {
            if (onRemoteBroadcastReceived != null) {
                Bukkit.getScheduler().runTask(plugin, () -> onRemoteBroadcastReceived.accept(message));
            }
            return;
        } else if (CHANNEL_MC_PRIVATE.equals(channel)) {
            try {
                org.json.simple.JSONObject obj = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(message);
                String targetPlayer = (String) obj.get("target_player");
                String privMsg = (String) obj.get("message");
                if (targetPlayer != null && privMsg != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        org.bukkit.entity.Player p = Bukkit.getPlayerExact(targetPlayer);
                        if (p != null && p.isOnline()) {
                            String agentName = "小可";
                            if (plugin instanceof io.github.freecoreagent.FreeCoreAgentPlugin) {
                                io.github.freecoreagent.FreeCoreAgentPlugin fca = (io.github.freecoreagent.FreeCoreAgentPlugin) plugin;
                                if (fca.getInteraction() != null) {
                                    agentName = fca.getInteraction().getAgentName();
                                }
                                String formatted = fca.getLang().message(
                                        "chat.private-format-from",
                                        "{sender}", agentName,
                                        "{message}", privMsg
                                );
                                p.sendMessage(formatted);
                            } else {
                                String formatted = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                        "&8[&bFreeCore&8] &7[&6&l小可 &7] &d-> &e你&f: " + privMsg);
                                p.sendMessage(formatted);
                            }
                            plugin.getLogger().info("[MC私信投递成功 -> " + p.getName() + "] " + privMsg);
                        }
                    });
                }
            } catch (Exception ignored) {}
            return;
        } else if (CHANNEL_TOOL_REQUEST.equals(channel)) {
            if (onRemoteToolRequestReceived != null) {
                Bukkit.getScheduler().runTask(plugin, () -> onRemoteToolRequestReceived.accept(message));
            }
            return;
        }

        int idx = message.indexOf('|');
        if (idx < 0) return;
        String senderServer = message.substring(0, idx);
        String payload = message.substring(idx + 1);

        // Ignore messages sent by ourselves
        if (senderServer.equalsIgnoreCase(serverId)) return;

        if (CHANNEL_BROADCAST.equals(channel)) {
            if (onRemoteBroadcastReceived != null) {
                Bukkit.getScheduler().runTask(plugin, () -> onRemoteBroadcastReceived.accept(payload));
            }
        } else if (CHANNEL_CONTROL.equals(channel)) {
            if ("RELOAD".equalsIgnoreCase(payload) && onRemoteReloadRequested != null) {
                Bukkit.getScheduler().runTask(plugin, onRemoteReloadRequested);
            }
        } else if (CHANNEL_CHAT_STREAM.equals(channel)) {
            if (onRemoteChatStreamReceived != null) {
                Bukkit.getScheduler().runTask(plugin, () -> onRemoteChatStreamReceived.accept(payload));
            }
        } else if (CHANNEL_TICKET_SYNC.equals(channel)) {
            if (onRemoteTicketSyncReceived != null) {
                Bukkit.getScheduler().runTask(plugin, onRemoteTicketSyncReceived);
            }
        }
    }

    public void publishTicketSync() {
        String payload = serverId + "|SYNC";
        executeRedisAsync("PUBLISH", CHANNEL_TICKET_SYNC, payload);
    }

    public void publishChatStream(String playerMsgLine) {
        String payload = serverId + "|" + playerMsgLine;
        executeRedisAsync("PUBLISH", CHANNEL_CHAT_STREAM, payload);
    }

    public void publishQqOutbound(String jsonPayload) {
        executeRedisAsync("PUBLISH", CHANNEL_QQ_OUTBOUND, jsonPayload);
    }

    private void executeRedisAsync(String... command) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                RedisCredentials creds = getCredentials();
                try (Socket socket = new Socket(creds.host(), creds.port())) {
                    socket.setSoTimeout(2000);
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();

                    if (!creds.password().isBlank()) {
                        sendCommand(out, "AUTH", creds.password());
                        readResponse(in);
                    }

                    sendCommand(out, command);
                    readResponse(in);
                }
            } catch (Exception ignored) {}
        });
    }

    private static void sendCommand(OutputStream out, String... args) throws IOException {
        StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(bytes.length).append("\r\n").append(arg).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readResponse(InputStream in) throws IOException {
        int first = in.read();
        if (first == -1) return null;
        if (first == '+' || first == ':') return readLine(in);
        if (first == '-') throw new IOException("Redis error: " + readLine(in));
        if (first == '$') {
            int len = Integer.parseInt(readLine(in));
            if (len == -1) return null;
            byte[] data = in.readNBytes(len);
            in.read(); in.read(); // \r\n
            return new String(data, StandardCharsets.UTF_8);
        }
        return readLine(in);
    }

    private static String readBulkString(InputStream in) throws IOException {
        int first = in.read();
        if (first != '$') {
            if (first == -1) return null;
            return readLine(in);
        }
        int len = Integer.parseInt(readLine(in));
        if (len == -1) return null;
        byte[] data = in.readNBytes(len);
        in.read(); in.read(); // \r\n
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String[] readArrayResponse(InputStream in) throws IOException {
        int first = in.read();
        if (first != '*') {
            if (first == -1) return null;
            readLine(in);
            return null;
        }
        int count = Integer.parseInt(readLine(in));
        if (count == -1) return null;
        String[] arr = new String[count];
        for (int i = 0; i < count; i++) {
            arr[i] = readResponse(in);
        }
        return arr;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\r') {
            bos.write(c);
        }
        if (c == '\r') in.read(); // read \n
        return bos.toString(StandardCharsets.UTF_8);
    }
}
