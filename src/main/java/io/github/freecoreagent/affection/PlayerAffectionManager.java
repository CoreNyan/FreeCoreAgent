package io.github.freecoreagent.affection;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic player affection (-100 to 100), tags, and interpersonal bonds.
 */
public final class PlayerAffectionManager {
    private final JavaPlugin plugin;
    private final File dataFile;
    private final Map<UUID, Integer> affectionMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> nicknamesMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> impressionsMap = new ConcurrentHashMap<>();

    public PlayerAffectionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "affections.yml");
    }

    public void load() {
        affectionMap.clear();
        nicknamesMap.clear();
        impressionsMap.clear();
        if (!dataFile.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        if (config.isConfigurationSection("players")) {
            for (String uuidStr : config.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uid = UUID.fromString(uuidStr);
                    int aff = config.getInt("players." + uuidStr + ".affection", 0);
                    String nick = config.getString("players." + uuidStr + ".nickname", "");
                    String imp = config.getString("players." + uuidStr + ".impression", "");
                    affectionMap.put(uid, aff);
                    if (!nick.isBlank()) nicknamesMap.put(uid, nick);
                    if (!imp.isBlank()) impressionsMap.put(uid, imp);
                } catch (Exception ignored) {}
            }
        }
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : affectionMap.entrySet()) {
            String path = "players." + entry.getKey().toString();
            config.set(path + ".affection", entry.getValue());
            String nick = nicknamesMap.get(entry.getKey());
            if (nick != null && !nick.isBlank()) {
                config.set(path + ".nickname", nick);
            }
            String imp = impressionsMap.get(entry.getKey());
            if (imp != null && !imp.isBlank()) {
                config.set(path + ".impression", imp);
            }
        }
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save affections.yml: " + e.getMessage());
        }
    }

    public int getAffection(UUID uuid) {
        return affectionMap.getOrDefault(uuid, 0);
    }

    public void modifyAffection(UUID uuid, int delta) {
        int cur = getAffection(uuid);
        int next = Math.max(-100, Math.min(100, cur + delta));
        affectionMap.put(uuid, next);
        save();
    }

    public String getAffectionTierDesc(UUID uuid, boolean isOwner) {
        if (isOwner) {
            return "【最亲密的服主伙伴】(好感度 MAX，拌嘴但也最护短)";
        }
        int val = getAffection(uuid);
        if (val >= 60) return "【铁杆好友】(非常亲近，说话更甜更俏皮，偏爱)";
        if (val >= 20) return "【熟络老玩家】(嘴硬心软，热心且乐于帮助)";
        if (val >= -10) return "【普通玩家关系】(经典傲娇，嘴硬吐槽，正常回答)";
        if (val >= -40) return "【记小本本的捣蛋鬼】(有些嫌弃，毒舌加倍)";
        return "【死对头黑名单】(极度嫌弃与高冷，懒得理睬)";
    }

    public String getNickname(UUID uuid) {
        return nicknamesMap.getOrDefault(uuid, "");
    }

    public void setNickname(UUID uuid, String nick) {
        if (nick == null || nick.isBlank()) nicknamesMap.remove(uuid);
        else nicknamesMap.put(uuid, nick);
        save();
    }

    public String getImpression(UUID uuid) {
        return impressionsMap.getOrDefault(uuid, "");
    }

    public void setImpression(UUID uuid, String impression) {
        if (impression == null || impression.isBlank()) impressionsMap.remove(uuid);
        else impressionsMap.put(uuid, impression);
        save();
    }
}
