package io.github.freecoreagent.tool;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * FreeCore Safe Config Lens:
 * Read-only, sandboxed, redacted config inspection gateway.
 * Allows CoreNyan to inspect game mechanics and configurations for diagnostic accuracy
 * while strictly barring database passwords, secret keys, player data, and token leaks.
 */
public final class SafeConfigLens {

    // Sensitive files / folders strictly forbidden from access
    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "authme", "luckperms", "tab", "key.pem", "forwarding.secret",
            "redis.conf", "velocity.secret", "database", "credentials", "token"
    );

    private static final Set<String> FORBIDDEN_EXTENSIONS = Set.of(
            ".db", ".sqlite", ".dat", ".pem", ".key", ".log", ".gz", ".zst", ".secret"
    );

    // Regex pattern to dynamically scrub sensitive key-values
    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
            "(?i)(password|secret|token|auth|rcon|api[-_]?key|license|webhook|private[-_]?key)\\s*[:=]\\s*([\"'][^\"']*[\"']|\\S+)"
    );

    private static final Pattern SENSITIVE_HOST_PATTERN = Pattern.compile(
            "(?i)(host|ip|address|mysql_host)\\s*[:=]\\s*([\"'][^\"']*[\"']|\\S+)"
    );

    private final JavaPlugin plugin;
    private final File serverRoot;
    private final File pluginsDir;

    public SafeConfigLens(JavaPlugin plugin) {
        this.plugin = plugin;
        File df = plugin.getDataFolder().getAbsoluteFile();
        this.pluginsDir = (df.getParentFile() != null) ? df.getParentFile() : new File("plugins").getAbsoluteFile();
        this.serverRoot = (pluginsDir.getParentFile() != null) ? pluginsDir.getParentFile() : new File(".").getAbsoluteFile();
    }

    /**
     * Inspects a plugin config or server-level config file.
     * @param target "server" or plugin name (e.g., "ClearLag", "Residence", "server.properties")
     * @param fileName config file name (e.g. "config.yml")
     * @return sanitized, masked content preview
     */
    public String inspectConfig(String target, String fileName) {
        if (target == null || target.isBlank()) {
            return "错误: 请指定要查阅的插件名或目标（例如: ClearLag, Residence, server.properties）。";
        }

        String targetLower = target.trim().toLowerCase(Locale.ROOT);
        String fileLower = (fileName == null) ? "" : fileName.trim().toLowerCase(Locale.ROOT);

        // Security Check 1: Forbidden Keywords
        for (String f : FORBIDDEN_NAMES) {
            if (targetLower.contains(f) || fileLower.contains(f)) {
                return "【安全访问拦截】目标属于核心凭据/数据库保密区域（" + f + "），系统物理封锁，禁止读取！";
            }
        }

        // Security Check 2: Forbidden Extensions
        for (String ext : FORBIDDEN_EXTENSIONS) {
            if (targetLower.endsWith(ext) || fileLower.endsWith(ext)) {
                return "【安全访问拦截】禁止读取二进制、数据库或密钥格式文件（" + ext + "）！";
            }
        }

        // Locate File
        File targetFile = null;
        if (targetLower.equals("server") || targetLower.equals("server.properties") || targetLower.equals("properties")) {
            targetFile = new File(serverRoot, "server.properties");
        } else if (targetLower.equals("paper") || targetLower.equals("paper-global.yml")) {
            File p1 = new File(serverRoot, "config/paper-global.yml");
            File p2 = new File(serverRoot, "paper-global.yml");
            targetFile = p1.exists() ? p1 : p2;
        } else if (targetLower.equals("spigot") || targetLower.equals("spigot.yml")) {
            targetFile = new File(serverRoot, "spigot.yml");
        } else if (targetLower.equals("bukkit") || targetLower.equals("bukkit.yml")) {
            targetFile = new File(serverRoot, "bukkit.yml");
        } else {
            // Plugin Directory
            File pluginDir = new File(pluginsDir, target.trim());
            if (!pluginDir.exists()) {
                // Try case-insensitive search
                File[] list = pluginsDir.listFiles();
                if (list != null) {
                    for (File f : list) {
                        if (f.isDirectory() && f.getName().equalsIgnoreCase(target.trim())) {
                            pluginDir = f;
                            break;
                        }
                    }
                }
            }

            if (!pluginDir.exists()) {
                return "未找到插件目录: " + target + "。已安装的常见机制插件可在插件列表查询。";
            }

            String realName = (fileName == null || fileName.isBlank()) ? "config.yml" : fileName.trim();
            targetFile = new File(pluginDir, realName);
        }

        if (targetFile == null || !targetFile.exists() || !targetFile.isFile()) {
            return "目标配置文件不存在: " + (targetFile != null ? targetFile.getName() : fileName);
        }

        // Canonical Path Sandbox Validation: Must reside under serverRoot
        try {
            String canonicalTarget = targetFile.getCanonicalPath();
            String canonicalRoot = serverRoot.getCanonicalPath();
            if (!canonicalTarget.startsWith(canonicalRoot)) {
                return "【安全拦截】路径越界尝试！";
            }

            // Read lines and scrub
            List<String> lines = Files.readAllLines(targetFile.toPath(), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append("# 来源: ").append(targetFile.getName()).append(" (已通过安全脱敏网关)\n");

            int count = 0;
            for (String line : lines) {
                // Skip huge comment noise if needed, but keep structure
                String scrubbed = scrubSensitiveData(line);
                sb.append(scrubbed).append("\n");
                count++;
                if (count >= 300) { // Limit to 300 lines max to prevent prompt explosion
                    sb.append("\n# ... [已截断，仅展示前 300 行核心配置] ...\n");
                    break;
                }
            }

            return sb.toString();
        } catch (IOException e) {
            return "读取配置异常: " + e.getMessage();
        }
    }

    /**
     * Replaces sensitive fields with system redaction markers.
     */
    public String scrubSensitiveData(String line) {
        if (line == null) return "";
        // 1. Password / Secret / Token
        String result = SENSITIVE_KV_PATTERN.matcher(line).replaceAll("$1: \"****** [REDACTED_SECRET]\"");
        // 2. IP / Host
        result = SENSITIVE_HOST_PATTERN.matcher(result).replaceAll("$1: \"[INTERNAL_HOST]\"");
        return result;
    }
}
