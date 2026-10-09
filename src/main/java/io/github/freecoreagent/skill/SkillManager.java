package io.github.freecoreagent.skill;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads knowledge files (Skills) with distinct categories:
 * - docs/ (Official synced website docs from GitHub CoreNyan/freecore-docs)
 * - extra/ (Extra local operational / mechanism notes)
 * - Root persona style cards
 */
public final class SkillManager {
    public enum SkillCategory {
        DOCS("官网同步文档"),
        EXTRA("服主额外补充"),
        PERSONA("核心人设规则");

        private final String displayName;
        SkillCategory(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private final JavaPlugin plugin;
    private final List<SkillEntry> skills = new ArrayList<>();
    private SkillEntry styleCard = null;

    public record SkillEntry(String name, String content, SkillCategory category) {}

    public SkillManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        skills.clear();
        styleCard = null;
        if (!plugin.getConfig().getBoolean("skills.enabled", true)) return;

        String dirName = plugin.getConfig().getString("skills.directory", "skills");
        File skillDir = new File(plugin.getDataFolder(), dirName);
        if (!skillDir.exists()) {
            skillDir.mkdirs();
        }

        // Subdirectories: docs/ and extra/
        File docsDir = new File(skillDir, "docs");
        File extraDir = new File(skillDir, "extra");
        if (!docsDir.exists()) docsDir.mkdirs();
        if (!extraDir.exists()) extraDir.mkdirs();

        // 1. Scan root skills/ folder
        scanFolder(skillDir, false, SkillCategory.EXTRA);

        // 2. Scan skills/docs/ folder (Official website docs)
        scanFolder(docsDir, true, SkillCategory.DOCS);

        // 3. Scan skills/extra/ folder (Server supplements)
        scanFolder(extraDir, true, SkillCategory.EXTRA);

        long docsCount = skills.stream().filter(s -> s.category() == SkillCategory.DOCS).count();
        long extraCount = skills.stream().filter(s -> s.category() == SkillCategory.EXTRA).count();
        int total = skills.size() + (styleCard != null ? 1 : 0);

        plugin.getLogger().info("Loaded " + total + " skills (官网文档 docs/: " + docsCount + ", 补充机制 extra/: " + extraCount + ", 人设风格: " + (styleCard != null) + ")");
    }

    private void scanFolder(File folder, boolean recursive, SkillCategory defaultCategory) {
        if (!folder.exists() || !folder.isDirectory()) return;
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory() && recursive) {
                scanFolder(file, true, defaultCategory);
                continue;
            }
            String name = file.getName();
            if (name.endsWith(".sha") || (!name.endsWith(".md") && !name.endsWith(".txt"))) {
                continue;
            }

            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                String lowerName = name.toLowerCase(Locale.ROOT);

                if (lowerName.contains("style") || lowerName.contains("persona")) {
                    styleCard = new SkillEntry(name, content, SkillCategory.PERSONA);
                } else {
                    SkillCategory cat = defaultCategory;
                    if (file.getParentFile().getName().equalsIgnoreCase("docs") || lowerName.startsWith("doc-")) {
                        cat = SkillCategory.DOCS;
                    }
                    skills.add(new SkillEntry(name, content, cat));
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load skill file: " + file.getName(), e);
            }
        }
    }

    public List<SkillEntry> getSkills() {
        return Collections.unmodifiableList(skills);
    }

    /**
     * Injects the persistent Persona Style Guide, plus top relevant tutorial cards.
     */
    public String buildSkillsPromptForQuery(String query) {
        StringBuilder sb = new StringBuilder();

        // 1. ALWAYS inject the persona style guide to anchor tsundere/girl tone!
        if (styleCard != null) {
            sb.append("\n\n【核心人格与交流方式指导规范（必须严格遵守）】\n")
              .append(styleCard.content().trim())
              .append("\n\n");
        }

        if (skills.isEmpty() || query == null || query.isBlank()) {
            return sb.toString();
        }

        // 2. Selectively add tutorial cards based on user query
        String[] keywords = query.toLowerCase(Locale.ROOT).split("[\\s,，。？！、]+");
        List<ScoredSkill> scored = new ArrayList<>();

        for (SkillEntry s : skills) {
            int score = 0;
            String lowerName = s.name().toLowerCase(Locale.ROOT);
            String lowerContent = s.content().toLowerCase(Locale.ROOT);

            // Match categories & keywords
            if (query.contains("基岩") || query.contains("手机") || query.contains("端口") || query.contains("port") || query.contains("地址") || query.contains("ip") || query.contains("进服") || query.contains("连接")) {
                if (lowerName.contains("server-info")) score += 40;
            }
            if (query.contains("群") || query.contains("qq") || query.contains("官网") || query.contains("皮肤站") || query.contains("线路")) {
                if (lowerName.contains("server-info") || lowerName.contains("group") || lowerName.contains("entry")) score += 30;
            }
            if (query.contains("圈地") || query.contains("领地") || query.contains("res") || query.contains("权限")) {
                if (lowerName.contains("residence")) score += 25;
            }
            if (query.contains("规则") || query.contains("违规") || query.contains("封禁") || query.contains("举报") || query.contains("红石")) {
                if (lowerName.contains("rule")) score += 20;
            }
            if (query.contains("种子") || query.contains("seed")) {
                if (lowerName.contains("seed")) score += 50;
            }
            if (query.contains("新人") || query.contains("入坑") || query.contains("开荒") || query.contains("指南") || query.contains("菜单") || query.contains("指令") || query.contains("玩法")) {
                if (lowerName.contains("entry") || lowerName.contains("basic") || lowerName.contains("faq") || lowerName.contains("server-info")) score += 15;
            }

            for (String kw : keywords) {
                if (kw.length() < 2) continue;
                if (lowerName.contains(kw)) score += 5;
                if (lowerContent.contains(kw)) score += 1;
            }

            if (score > 0) {
                scored.add(new ScoredSkill(s, score));
            }
        }

        if (!scored.isEmpty()) {
            scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
            int limit = Math.min(2, scored.size());
            sb.append("【服务器专有教学/规则库检索】\n");
            for (int i = 0; i < limit; i++) {
                SkillEntry entry = scored.get(i).skill();
                sb.append("--- [").append(entry.category().getDisplayName()).append(": ").append(entry.name()).append("] ---\n");
                String body = entry.content();
                if (body.length() > 2500) {
                    body = body.substring(0, 2500) + "\n...(篇幅过长已截断)";
                }
                sb.append(body.trim()).append("\n\n");
            }
        }

        return sb.toString();
    }

    private record ScoredSkill(SkillEntry skill, int score) {}
}
