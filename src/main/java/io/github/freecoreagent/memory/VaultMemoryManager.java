package io.github.freecoreagent.memory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manages external DeepSeek Harness / FreeCore Memory Vault integration,
 * including auto-retrieval (RAG) and autonomous memory crystallization (recording).
 */
public final class VaultMemoryManager {
    private final JavaPlugin plugin;
    private final List<MemoryCard> memoryCards = new ArrayList<>();

    private LocalDate lastRecordDate = LocalDate.now();
    private final AtomicInteger dailyRecordedCount = new AtomicInteger(0);

    public record MemoryCard(String fileName, String title, Set<String> tags, String content, boolean isRestricted) {}

    public VaultMemoryManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        memoryCards.clear();
        if (!plugin.getConfig().getBoolean("memory.enabled", true)) return;

        String vaultPathStr = plugin.getConfig().getString("memory.vault-path", "C:\\Users\\Administrator\\.dsh\\memory-vault\\freecore-server");
        File vaultDir = new File(vaultPathStr);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) {
            plugin.getLogger().warning("Memory vault path does not exist or is not a directory: " + vaultPathStr);
            return;
        }

        scanDirectory(vaultDir);
        plugin.getLogger().info("Loaded " + memoryCards.size() + " memory vault cards from " + vaultPathStr);
    }

    private void scanDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) {
                    scanDirectory(f);
                }
            } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".md")) {
                parseMarkdownFile(f);
            }
        }
    }

    private void parseMarkdownFile(File file) {
        try {
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String title = file.getName().replace(".md", "");
            Set<String> tags = new HashSet<>();
            boolean restricted = false;

            if (raw.startsWith("---")) {
                int endIdx = raw.indexOf("---", 3);
                if (endIdx > 0) {
                    String frontmatter = raw.substring(3, endIdx);
                    for (String line : frontmatter.split("\\r?\\n")) {
                        line = line.trim();
                        if (line.startsWith("title:")) {
                            title = line.substring(6).trim();
                        } else if (line.startsWith("kind:") && (line.contains("diary") || line.contains("secret") || line.contains("preference"))) {
                            restricted = true;
                        } else if (line.startsWith("tags:")) {
                            String tagStr = line.substring(5).replace("[", "").replace("]", "").trim();
                            for (String t : tagStr.split(",")) {
                                if (!t.isBlank()) tags.add(t.trim().toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
            }

            if (file.getName().contains("secret") || file.getName().contains("user-preferences")) {
                restricted = true;
            }

            memoryCards.add(new MemoryCard(file.getName(), title, tags, raw, restricted));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read memory file: " + file.getName(), e);
        }
    }

    public List<MemoryCard> getMemoryCards() {
        return Collections.unmodifiableList(memoryCards);
    }

    /**
     * Finds the most relevant memory cards for a given user query.
     */
    public List<MemoryCard> findRelevantCards(String query, boolean isOwner) {
        if (memoryCards.isEmpty() || query == null || query.isBlank()) return Collections.emptyList();

        int maxCards = plugin.getConfig().getInt("memory.max-relevant-cards", 2);
        String[] keywords = query.toLowerCase(Locale.ROOT).split("[\\s,，。？！、]+");
        List<ScoredCard> scored = new ArrayList<>();

        for (MemoryCard card : memoryCards) {
            if (card.isRestricted() && !isOwner) continue;

            int score = 0;
            String lowerTitle = card.title().toLowerCase(Locale.ROOT);
            String lowerContent = card.content().toLowerCase(Locale.ROOT);

            for (String kw : keywords) {
                if (kw.length() < 2) continue;
                if (lowerTitle.contains(kw)) score += 5;
                for (String tag : card.tags()) {
                    if (tag.contains(kw)) score += 4;
                }
                if (lowerContent.contains(kw)) score += 1;
            }

            if (score > 0) {
                scored.add(new ScoredCard(card, score));
            }
        }

        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));

        List<MemoryCard> result = new ArrayList<>();
        for (int i = 0; i < Math.min(maxCards, scored.size()); i++) {
            result.add(scored.get(i).card());
        }
        return result;
    }

    public String buildMemoryPromptForQuery(String query, boolean isOwner) {
        List<MemoryCard> relevant = findRelevantCards(query, isOwner);
        if (relevant.isEmpty()) return "";

        int maxChars = plugin.getConfig().getInt("memory.max-card-characters", 1500);
        StringBuilder sb = new StringBuilder("\n\n【小可脑海中检索到的相关记忆卡】\n");
        for (MemoryCard card : relevant) {
            sb.append("--- [记忆卡: ").append(card.title()).append("] ---\n");
            String body = card.content();
            if (body.length() > maxChars) {
                body = body.substring(0, maxChars) + "\n...(篇幅过长已省略)";
            }
            sb.append(body.trim()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Persists an autonomous memory entry distilled by Agent personality.
     */
    public synchronized boolean recordAutonomousMemory(String player, String title, String tagsStr, String content) {
        if (!plugin.getConfig().getBoolean("memory.auto-capture", true)) return false;

        // Daily quota protection to prevent spamming Git commits
        LocalDate today = LocalDate.now();
        if (!today.equals(lastRecordDate)) {
            lastRecordDate = today;
            dailyRecordedCount.set(0);
        }
        int maxDaily = plugin.getConfig().getInt("memory.max-daily-records", 15);
        if (dailyRecordedCount.get() >= maxDaily) {
            plugin.getLogger().warning("Daily autonomous memory limit reached (" + maxDaily + "). Skipped: " + title);
            return false;
        }

        String vaultPathStr = plugin.getConfig().getString("memory.vault-path", "C:\\Users\\Administrator\\.dsh\\memory-vault\\freecore-server");
        File vaultDir = new File(vaultPathStr);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) {
            return false;
        }

        // 统一收录至小可的专属见闻手帐单文件，分类存放于 diary 子目录
        File diaryDir = new File(vaultDir, "diary");
        if (!diaryDir.exists()) {
            diaryDir.mkdirs();
        }
        File targetFile = new File(diaryDir, "corenyan-diary.md");

        String timeFormatted = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String entry = "\n---\n\n"
                + "## 📌 " + timeFormatted + " · " + title + "\n"
                + "- **见闻来源**: 与玩家 `" + player + "` 的游戏内互动\n"
                + "- **涉及玩家**: `" + player + "`\n"
                + "- **见闻正文**:\n"
                + "  " + content.trim().replace("\n", "\n  ") + "\n";

        try {
            if (!targetFile.exists()) {
                String header = "---\n"
                        + "kind: diary\n"
                        + "title: 小可的日常随笔与服务器见闻录\n"
                        + "tags: [小可日记, 玩家见闻, 运营备忘, 专属OP]\n"
                        + "created: " + LocalDateTime.now().toString() + "\n"
                        + "source: in-game-interaction\n"
                        + "status: approved\n"
                        + "---\n"
                        + "# 🐾 小可的日常随笔与服务器见闻录\n\n"
                        + "> 嘿嘿，这里是小可（CoreNyan）的专属见闻手帐！\n";
                Files.writeString(targetFile.toPath(), header + entry, StandardCharsets.UTF_8);
            } else {
                Files.writeString(targetFile.toPath(), entry, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            }
            dailyRecordedCount.incrementAndGet();
            plugin.getLogger().info("Agent successfully crystallized memory entry to " + targetFile.getName() + " (" + title + ")");
            // Reload cards to make it immediately searchable
            load();
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to append memory entry: " + targetFile.getName(), e);
            return false;
        }
    }

    private record ScoredCard(MemoryCard card, int score) {}
}
