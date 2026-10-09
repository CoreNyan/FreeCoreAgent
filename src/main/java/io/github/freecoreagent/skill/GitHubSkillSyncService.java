package io.github.freecoreagent.skill;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Automatically synchronizes markdown docs from GitHub repo CoreNyan/freecore-docs
 * into the local plugins/FreeCoreAgent/skills directory.
 */
public final class GitHubSkillSyncService {
    private static final String GITHUB_REPO_API = "https://api.github.com/repos/CoreNyan/freecore-docs/contents/docs";
    private final JavaPlugin plugin;
    private final SkillManager skillManager;

    public GitHubSkillSyncService(JavaPlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
    }

    public void startAutoSync(long intervalHours) {
        if (!plugin.getConfig().getBoolean("skills.auto-sync.enabled", true)) return;

        long ticks = Math.max(1, intervalHours) * 60L * 60L * 20L;
        // Run initial check asynchronously after 20 seconds, then periodically
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            plugin.getLogger().info("[SkillSync] 正在从 GitHub (CoreNyan/freecore-docs) 检查教程文档更新...");
            syncDocsRecursively("");
        }, 400L, ticks);
    }

    public void syncNowAsync(Runnable onComplete) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            syncDocsRecursively("");
            if (onComplete != null) {
                Bukkit.getScheduler().runTask(plugin, onComplete);
            }
        });
    }

    private void syncDocsRecursively(String subPath) {
        try {
            String apiEndpoint = GITHUB_REPO_API + (subPath.isBlank() ? "" : "/" + subPath);
            HttpURLConnection conn = (HttpURLConnection) new URL(apiEndpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "FreeCoreAgent-DocSync");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);

            if (conn.getResponseCode() != 200) {
                plugin.getLogger().warning("[SkillSync] 请求 GitHub API 状态异常: " + conn.getResponseCode());
                return;
            }

            try (InputStream in = conn.getInputStream();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JSONParser parser = new JSONParser();
                Object parsed = parser.parse(reader);
                if (!(parsed instanceof JSONArray items)) return;

                File skillDir = new File(plugin.getDataFolder(), plugin.getConfig().getString("skills.directory", "skills"));
                if (!skillDir.exists()) skillDir.mkdirs();

                boolean updatedAny = false;

                for (Object obj : items) {
                    if (!(obj instanceof JSONObject item)) continue;
                    String type = (String) item.get("type");
                    String name = (String) item.get("name");
                    String path = (String) item.get("path");

                    if ("dir".equalsIgnoreCase(type)) {
                        if (name.startsWith(".") || name.startsWith("@")) continue;
                        syncDocsRecursively((subPath.isBlank() ? "" : subPath + "/") + name);
                    } else if ("file".equalsIgnoreCase(type) && name.endsWith(".md")) {
                        // e.g. path = "docs/rules/server-rules.md"
                        String cleanTargetName = "doc-" + path.replace("docs/", "").replace("/", "-");
                        String targetSha = (String) item.get("sha");

                        // Save directly into skills/docs/ directory!
                        File docsSubDir = new File(skillDir, "docs");
                        if (!docsSubDir.exists()) docsSubDir.mkdirs();

                        File targetFile = new File(docsSubDir, cleanTargetName);
                        File shaFile = new File(docsSubDir, cleanTargetName + ".sha");

                        String currentSha = "";
                        if (shaFile.exists()) {
                            currentSha = Files.readString(shaFile.toPath(), StandardCharsets.UTF_8).trim();
                        }

                        if (!currentSha.equals(targetSha)) {
                            // Need to fetch file content
                            String content = fetchFileContent((String) item.get("url"));
                            if (content != null && !content.isBlank()) {
                                Files.writeString(targetFile.toPath(), content, StandardCharsets.UTF_8);
                                Files.writeString(shaFile.toPath(), targetSha, StandardCharsets.UTF_8);
                                plugin.getLogger().info("[SkillSync] 成功自动同步教学文档: " + cleanTargetName);
                                updatedAny = true;
                            }
                        }
                    }
                }

                if (updatedAny) {
                    Bukkit.getScheduler().runTask(plugin, skillManager::load);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[SkillSync] 同步 GitHub 教程文档时发生错误: " + e.getMessage());
        }
    }

    private String fetchFileContent(String fileApiUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(fileApiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "FreeCoreAgent-DocSync");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);

            if (conn.getResponseCode() != 200) return null;

            try (InputStream in = conn.getInputStream();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JSONParser parser = new JSONParser();
                JSONObject obj = (JSONObject) parser.parse(reader);
                String encoding = (String) obj.get("encoding");
                String content = (String) obj.get("content");
                if ("base64".equalsIgnoreCase(encoding) && content != null) {
                    String clean = content.replaceAll("\\s+", "");
                    byte[] bytes = Base64.getDecoder().decode(clean);
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
