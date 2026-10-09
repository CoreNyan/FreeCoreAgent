package io.github.freecoreagent.tab;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Native Virtual Tab Player & Tab-Completion Injector for CoreNyan.
 * - Injects CoreNyan into all online players' client Tablists with 0 entity overhead.
 * - Supports custom prefix/format, realistic 18ms latency, and LISTED status.
 * - Injects CoreNyan into /msg, /tell, /w, /m tab-completion suggestions.
 */
public final class VirtualTabPlayerManager implements Listener {
    private final JavaPlugin plugin;
    public static final UUID CORENYAN_UUID = UUID.nameUUIDFromBytes("CoreNyan:FreeCoreCommunity".getBytes(StandardCharsets.UTF_8));
    private static final String CORENYAN_NAME = "CoreNyan";

    private GameProfile gameProfile;
    private ClientboundPlayerInfoUpdatePacket cachedAddPacket;
    private ClientboundPlayerInfoRemovePacket cachedRemovePacket;

    public VirtualTabPlayerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        initProfileAndPackets();
    }

    private String currentSkinValue = null;
    private String currentSkinSignature = null;

    private UUID activeCoreNyanUuid = CORENYAN_UUID;

    private void initProfileAndPackets() {
        // Load custom texture value & signature from config, or fallback to signed local Blessing skin
        loadSkinProperties();

        // Always lock to CORENYAN_UUID so Proxy FCTabBridge never evicts CoreNyan!
        this.activeCoreNyanUuid = CORENYAN_UUID;

        com.google.common.collect.Multimap<String, Property> multi = com.google.common.collect.LinkedHashMultimap.create();
        if (currentSkinValue != null && !currentSkinValue.isEmpty()) {
            if (currentSkinSignature != null && !currentSkinSignature.isEmpty()) {
                multi.put("textures", new Property("textures", currentSkinValue, currentSkinSignature));
            } else {
                multi.put("textures", new Property("textures", currentSkinValue));
            }
        }
        com.mojang.authlib.properties.PropertyMap pmap = new com.mojang.authlib.properties.PropertyMap(multi);

        this.gameProfile = new GameProfile(CORENYAN_UUID, CORENYAN_NAME, pmap);

        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
        );

        String rawDisplay = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("agent.tab-display", " &7[ &6&l小可 &7] &dCoreNyan"));

        net.minecraft.network.chat.Component nmsDisplay = net.minecraft.network.chat.Component.literal(rawDisplay);

        ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                CORENYAN_UUID,
                gameProfile,
                true, // listed = true! (Shows in Tablist)
                18,   // latency = 18ms (Green bar)
                GameType.SURVIVAL,
                nmsDisplay,
                false,
                0,
                null
        );

        this.cachedAddPacket = new ClientboundPlayerInfoUpdatePacket(actions, entry);
        this.cachedRemovePacket = new ClientboundPlayerInfoRemovePacket(Collections.singletonList(CORENYAN_UUID));
    }

    private static UUID extractUuidFromSkin(String base64) {
        if (base64 == null || base64.isBlank()) return CORENYAN_UUID;
        try {
            String jsonStr = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            org.json.simple.JSONObject json = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(jsonStr);
            String rawId = (String) json.get("profileId");
            if (rawId != null && rawId.length() == 32) {
                String formatted = rawId.substring(0, 8) + "-" + rawId.substring(8, 12) + "-" +
                        rawId.substring(12, 16) + "-" + rawId.substring(16, 20) + "-" + rawId.substring(20);
                return UUID.fromString(formatted);
            }
        } catch (Throwable ignored) {}
        return CORENYAN_UUID;
    }

    private void loadSkinProperties() {
        String cfgVal = plugin.getConfig().getString("agent.tab-skin-value");
        String cfgSig = plugin.getConfig().getString("agent.tab-skin-signature");
        if (cfgVal != null && !cfgVal.isBlank() && cfgSig != null && !cfgSig.isBlank()) {
            this.currentSkinValue = cfgVal;
            this.currentSkinSignature = cfgSig;
            return;
        }

        // Fetch signed texture directly from local Blessing Skin Yggdrasil API for CoreNyan
        try {
            java.net.URI uri = java.net.URI.create("http://127.0.0.1:25567/api/yggdrasil/sessionserver/session/minecraft/profile/9e3ede35dadf3beca70bdd346297965a?unsigned=false");
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(3)).GET().build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                org.json.simple.JSONObject json = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(resp.body());
                org.json.simple.JSONArray props = (org.json.simple.JSONArray) json.get("properties");
                if (props != null) {
                    for (Object o : props) {
                        org.json.simple.JSONObject p = (org.json.simple.JSONObject) o;
                        if ("textures".equals(p.get("name"))) {
                            this.currentSkinValue = (String) p.get("value");
                            this.currentSkinSignature = (String) p.get("signature");
                            plugin.getConfig().set("agent.tab-skin-value", this.currentSkinValue);
                            plugin.getConfig().set("agent.tab-skin-signature", this.currentSkinSignature);
                            plugin.saveConfig();
                            plugin.getLogger().info("Successfully loaded signed Blessing Skin texture for CoreNyan tablist avatar!");
                            return;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to query Blessing Skin Yggdrasil: " + t.getMessage());
        }
    }

    public void setSkinFromUrl(String url, boolean slim, java.util.function.Consumer<Boolean> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Use Citizens MojangSkinGenerator (api.mineskin.org) to generate official Mojang-signed texture
                org.json.simple.JSONObject gen = net.citizensnpcs.util.MojangSkinGenerator.generateFromURL(url, slim);
                if (gen != null && gen.containsKey("texture")) {
                    org.json.simple.JSONObject texture = (org.json.simple.JSONObject) gen.get("texture");
                    String val = (String) texture.get("value");
                    String sig = (String) texture.get("signature");
                    if (val != null && sig != null) {
                        this.currentSkinValue = val;
                        this.currentSkinSignature = sig;
                        plugin.getConfig().set("agent.tab-skin-value", val);
                        plugin.getConfig().set("agent.tab-skin-signature", sig);
                        plugin.saveConfig();
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            reload();
                            if (callback != null) callback.accept(true);
                        });
                        return;
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Error generating skin from URL: " + t.getMessage());
            }
            if (callback != null) {
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
            }
        });
    }

    public void setSkinFromPlayer(String playerName, java.util.function.Consumer<Boolean> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                java.net.URI uri = java.net.URI.create("http://127.0.0.1:25567/api/yggdrasil/api/users/profiles/minecraft/" + playerName);
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(3)).GET().build();
                java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    org.json.simple.JSONObject pJson = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(resp.body());
                    String uuidStr = (String) pJson.get("id");
                    java.net.URI sessionUri = java.net.URI.create("http://127.0.0.1:25567/api/yggdrasil/sessionserver/session/minecraft/profile/" + uuidStr + "?unsigned=false");
                    java.net.http.HttpRequest sReq = java.net.http.HttpRequest.newBuilder(sessionUri).timeout(java.time.Duration.ofSeconds(3)).GET().build();
                    java.net.http.HttpResponse<String> sResp = client.send(sReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (sResp.statusCode() == 200) {
                        org.json.simple.JSONObject sJson = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(sResp.body());
                        org.json.simple.JSONArray props = (org.json.simple.JSONArray) sJson.get("properties");
                        for (Object o : props) {
                            org.json.simple.JSONObject prop = (org.json.simple.JSONObject) o;
                            if ("textures".equals(prop.get("name"))) {
                                this.currentSkinValue = (String) prop.get("value");
                                this.currentSkinSignature = (String) prop.get("signature");
                                plugin.getConfig().set("agent.tab-skin-value", this.currentSkinValue);
                                plugin.getConfig().set("agent.tab-skin-signature", this.currentSkinSignature);
                                plugin.saveConfig();
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    reload();
                                    if (callback != null) callback.accept(true);
                                });
                                return;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Error setting skin from player " + playerName + ": " + t.getMessage());
            }
            if (callback != null) {
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
            }
        });
    }

    public void reload() {
        initProfileAndPackets();
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendRemovePacket(p);
            Bukkit.getScheduler().runTaskLater(plugin, () -> sendAddPacket(p), 2L);
        }
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Broadcast to all currently online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendAddPacket(p);
        }

        // Heartbeat every 1.5s to ensure CoreNyan stays permanently listed in Tab across any proxy cleans
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                sendAddPacket(p);
            }
        }, 20L, 30L);
    }

    public void stop() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendRemovePacket(p);
        }
    }

    public void sendAddPacket(Player player) {
        if (player == null || !player.isOnline()) return;
        try {
            ServerPlayer sp = ((CraftPlayer) player).getHandle();
            sp.connection.send(cachedAddPacket);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to send virtual tab packet to " + player.getName() + ": " + t.getMessage());
        }
    }

    public void sendRemovePacket(Player player) {
        if (player == null || !player.isOnline()) return;
        try {
            ServerPlayer sp = ((CraftPlayer) player).getHandle();
            sp.connection.send(cachedRemovePacket);
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Delay 10 ticks (0.5s) to send after vanilla initial packet burst
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sendAddPacket(event.getPlayer());
        }, 10L);
    }

    /**
     * Intercepts tab completion for commands like /msg, /tell, /w, /m, /tpa
     * so players can Tab-complete "CoreNyan".
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTabComplete(TabCompleteEvent event) {
        String buffer = event.getBuffer().trim().toLowerCase(Locale.ROOT);
        String[] parts = buffer.split("\\s+");

        if (parts.length == 1 || (parts.length == 2 && !buffer.endsWith(" "))) {
            String cmd = parts[0].replace("/", "");
            if (cmd.equals("msg") || cmd.equals("tell") || cmd.equals("w") || cmd.equals("m") || cmd.equals("tpa")) {
                List<String> completions = new ArrayList<>(event.getCompletions());
                String prefix = parts.length > 1 ? parts[1] : "";
                if (CORENYAN_NAME.toLowerCase(Locale.ROOT).startsWith(prefix) && !completions.contains(CORENYAN_NAME)) {
                    completions.add(0, CORENYAN_NAME);
                    event.setCompletions(completions);
                }
            }
        }
    }
}
