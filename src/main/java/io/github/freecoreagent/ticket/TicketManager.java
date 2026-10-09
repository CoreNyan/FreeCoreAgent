package io.github.freecoreagent.ticket;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages ticket records (like GitHub Issues) reported by players to CoreNyan.
 */
public final class TicketManager {
    public enum TicketStatus {
        OPEN("未完成", "§a"),
        COMPLETED("已完成", "§9"),
        NOT_PLANNED("Not planned", "§7");

        private final String displayName;
        private final String color;

        TicketStatus(String displayName, String color) {
            this.displayName = displayName;
            this.color = color;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getColor() {
            return color;
        }

        public String getFormatted() {
            return color + "● " + displayName;
        }

        public TicketStatus next() {
            if (this == OPEN) return COMPLETED;
            if (this == COMPLETED) return NOT_PLANNED;
            return OPEN;
        }
    }

    public static final class Ticket {
        private final int id;
        private final String reporterName;
        private final UUID reporterUuid;
        private final String serverNode;
        private final long createdAt;
        private String title;
        private String rawPlayerMessage;
        private String diagnosticSuggestion; // 诊断建议
        private String environmentSnapshot;   // 上下文排查对照 / 环境快照
        private TicketStatus status;

        public Ticket(int id, String reporterName, UUID reporterUuid, String serverNode, long createdAt,
                      String title, String rawPlayerMessage, String diagnosticSuggestion,
                      String environmentSnapshot, TicketStatus status) {
            this.id = id;
            this.reporterName = reporterName;
            this.reporterUuid = reporterUuid;
            this.serverNode = serverNode;
            this.createdAt = createdAt;
            this.title = (title == null || title.isBlank()) ? "未知问题反馈" : title;
            this.rawPlayerMessage = (rawPlayerMessage == null || rawPlayerMessage.isBlank()) ? "无记录" : rawPlayerMessage;
            this.diagnosticSuggestion = (diagnosticSuggestion == null || diagnosticSuggestion.isBlank()) ? "暂无排查建议" : diagnosticSuggestion;
            this.environmentSnapshot = (environmentSnapshot == null || environmentSnapshot.isBlank()) ? "暂无快照数据" : environmentSnapshot;
            this.status = status;
        }

        public int getId() { return id; }
        public String getReporterName() { return reporterName; }
        public UUID getReporterUuid() { return reporterUuid; }
        public String getServerNode() { return serverNode; }
        public long getCreatedAt() { return createdAt; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getRawPlayerMessage() { return rawPlayerMessage; }
        public void setRawPlayerMessage(String rawPlayerMessage) { this.rawPlayerMessage = rawPlayerMessage; }
        public String getDiagnosticSuggestion() { return diagnosticSuggestion; }
        public void setDiagnosticSuggestion(String diagnosticSuggestion) { this.diagnosticSuggestion = diagnosticSuggestion; }
        public String getEnvironmentSnapshot() { return environmentSnapshot; }
        public void setEnvironmentSnapshot(String environmentSnapshot) { this.environmentSnapshot = environmentSnapshot; }
        public TicketStatus getStatus() { return status; }
        public void setStatus(TicketStatus status) { this.status = status; }

        public String getFormattedDate() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(createdAt));
        }
    }

    private final JavaPlugin plugin;
    private final File dataFile;
    private final Map<Integer, Ticket> ticketMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerLastTicketTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerLastTicketId = new ConcurrentHashMap<>();
    private io.github.freecoreagent.redis.AgentRedisBridge redisBridge;
    private int nextId = 1;

    public TicketManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "tickets.yml");
    }

    public void setRedisBridge(io.github.freecoreagent.redis.AgentRedisBridge redisBridge) {
        this.redisBridge = redisBridge;
    }

    public synchronized void load() {
        ticketMap.clear();
        if (!dataFile.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        nextId = config.getInt("next-id", 1);
        if (config.isConfigurationSection("tickets")) {
            for (String key : config.getConfigurationSection("tickets").getKeys(false)) {
                try {
                    int id = Integer.parseInt(key);
                    String reporter = config.getString("tickets." + key + ".reporter", "未知");
                    String uuidStr = config.getString("tickets." + key + ".uuid", "");
                    UUID uuid = uuidStr.isBlank() ? UUID.randomUUID() : UUID.fromString(uuidStr);
                    String srv = config.getString("tickets." + key + ".server", "survival");
                    long time = config.getLong("tickets." + key + ".time", System.currentTimeMillis());
                    String title = config.getString("tickets." + key + ".title", "问题反馈");
                    String rawMsg = config.getString("tickets." + key + ".raw-message", "无");
                    String diag = config.getString("tickets." + key + ".diagnostic", "");
                    String env = config.getString("tickets." + key + ".snapshot", "");

                    // Fallback backward compatibility with old 'content' field
                    if (diag.isBlank() && env.isBlank()) {
                        String oldContent = config.getString("tickets." + key + ".content", "");
                        int splitIdx = oldContent.indexOf("【玩家环境与上下文诊断快照】");
                        if (splitIdx >= 0) {
                            diag = oldContent.substring(0, splitIdx).replace("【排查诊断建议】:", "").trim();
                            env = oldContent.substring(splitIdx).replace("【玩家环境与上下文诊断快照】:", "").trim();
                        } else {
                            diag = oldContent;
                            env = "无快照";
                        }
                    }

                    String statusStr = config.getString("tickets." + key + ".status", "OPEN");
                    TicketStatus status;
                    try {
                        status = TicketStatus.valueOf(statusStr.toUpperCase(Locale.ROOT));
                    } catch (Exception e) {
                        status = TicketStatus.OPEN;
                    }

                    ticketMap.put(id, new Ticket(id, reporter, uuid, srv, time, title, rawMsg, diag, env, status));
                    if (id >= nextId) nextId = id + 1;
                } catch (Exception ignored) {}
            }
        }
    }

    public synchronized void save() {
        save(true);
    }

    public synchronized void save(boolean syncToRedis) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("next-id", nextId);
        for (Ticket t : ticketMap.values()) {
            String path = "tickets." + t.getId();
            config.set(path + ".reporter", t.getReporterName());
            config.set(path + ".uuid", t.getReporterUuid().toString());
            config.set(path + ".server", t.getServerNode());
            config.set(path + ".time", t.getCreatedAt());
            config.set(path + ".title", t.getTitle());
            config.set(path + ".raw-message", t.getRawPlayerMessage());
            config.set(path + ".diagnostic", t.getDiagnosticSuggestion());
            config.set(path + ".snapshot", t.getEnvironmentSnapshot());
            config.set(path + ".status", t.getStatus().name());
        }
        try {
            config.save(dataFile);
            if (syncToRedis && redisBridge != null) {
                redisBridge.publishTicketSync();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save tickets.yml: " + e.getMessage());
        }
    }

    public synchronized Ticket createOrUpdateTicket(String reporterName, UUID reporterUuid, String serverNode,
                                                    String title, String rawMsg, String diag, String env) {
        long now = System.currentTimeMillis();
        Long lastTime = playerLastTicketTime.get(reporterUuid);
        Integer lastId = playerLastTicketId.get(reporterUuid);

        if (lastTime != null && (now - lastTime < 90_000L) && lastId != null) {
            Ticket existing = ticketMap.get(lastId);
            if (existing != null && existing.getStatus() == TicketStatus.OPEN) {
                if (title != null && !title.isBlank() && !title.equals("未知问题")) {
                    existing.setTitle(title);
                }
                if (rawMsg != null && !rawMsg.isBlank()) {
                    existing.setRawPlayerMessage(existing.getRawPlayerMessage() + " | " + rawMsg);
                }
                existing.setDiagnosticSuggestion(diag);
                existing.setEnvironmentSnapshot(env);
                save();
                playerLastTicketTime.put(reporterUuid, now);
                return existing;
            }
        }

        int id = nextId++;
        Ticket t = new Ticket(id, reporterName, reporterUuid, serverNode, now, title, rawMsg, diag, env, TicketStatus.OPEN);
        ticketMap.put(id, t);
        playerLastTicketTime.put(reporterUuid, now);
        playerLastTicketId.put(reporterUuid, id);
        save();
        return t;
    }

    public synchronized boolean updateStatus(int id, TicketStatus newStatus) {
        Ticket t = ticketMap.get(id);
        if (t == null) return false;
        t.setStatus(newStatus);
        save();
        return true;
    }

    public synchronized boolean deleteTicket(int id) {
        boolean removed = ticketMap.remove(id) != null;
        if (removed) save();
        return removed;
    }

    public Ticket getTicket(int id) {
        return ticketMap.get(id);
    }

    public List<Ticket> getAllTickets() {
        List<Ticket> list = new ArrayList<>(ticketMap.values());
        list.sort((a, b) -> Integer.compare(b.getId(), a.getId())); // newest first
        return list;
    }
}
