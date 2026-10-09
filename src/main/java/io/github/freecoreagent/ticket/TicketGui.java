package io.github.freecoreagent.ticket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-level Interactive Chest GUI for OP / LynnH_Ma:
 * Level 1: Ticket list overview with Quick Status Cycle (Right Click) and Open Details (Left Click)
 * Level 2: Comprehensive Ticket Detail view with:
 *   - [Paper] 玩家对AI说的原话
 *   - [Book/Quill] 诊断排查建议 (独立物品显示)
 *   - [Compass] 上下文环境快照对照 (独立物品显示)
 *   - [Buttons] 状态切换与删除按钮
 */
public final class TicketGui implements Listener {
    private static final String LIST_TITLE_PREFIX = "§8[FC] §6§l小可工单 (Issues)";
    private static final String DETAIL_TITLE_PREFIX = "§8[FC] §6§l工单详情 #";

    private final JavaPlugin plugin;
    private final TicketManager ticketManager;

    public TicketGui(JavaPlugin plugin, TicketManager ticketManager) {
        this.plugin = plugin;
        this.ticketManager = ticketManager;
    }

    // ================= LEVEL 1: LIST VIEW =================
    public void openGui(Player player, int page) {
        List<TicketManager.Ticket> all = ticketManager.getAllTickets();
        int pageSize = 45;
        int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / pageSize));
        int curPage = Math.max(0, Math.min(page, totalPages - 1));

        String title = LIST_TITLE_PREFIX + " §7(" + (curPage + 1) + "/" + totalPages + ")";
        if (title.length() > 32) {
            title = "§6工单列表 §7(" + (curPage + 1) + "/" + totalPages + ")";
        }

        Inventory inv = Bukkit.createInventory(null, 54, title);

        int start = curPage * pageSize;
        int end = Math.min(start + pageSize, all.size());

        for (int i = start; i < end; i++) {
            TicketManager.Ticket t = all.get(i);
            inv.setItem(i - start, createTicketSummaryItem(t));
        }

        // Bottom navigation bar
        if (curPage > 0) {
            inv.setItem(45, createNavItem(Material.ARROW, "§a◀ 上一页", "§7前往第 " + curPage + " 页"));
        }

        long openCount = all.stream().filter(t -> t.getStatus() == TicketManager.TicketStatus.OPEN).count();
        long completedCount = all.stream().filter(t -> t.getStatus() == TicketManager.TicketStatus.COMPLETED).count();
        long notPlannedCount = all.stream().filter(t -> t.getStatus() == TicketManager.TicketStatus.NOT_PLANNED).count();

        inv.setItem(49, createNavItem(Material.BOOK, "§e§l工单统计概览",
                "§7总工单数: §f" + all.size() + " 个",
                "§a● 未完成: §f" + openCount + " 个",
                "§9● 已完成: §f" + completedCount + " 个",
                "§7● Not planned: §f" + notPlannedCount + " 个",
                "",
                "§e[左键点击] 打开二级详情界面",
                "§e[右键点击] 快速切换状态",
                "§c[Shift+右键] 彻底删除工单"));

        if (curPage < totalPages - 1) {
            inv.setItem(53, createNavItem(Material.ARROW, "§a下一页 ▶", "§7前往第 " + (curPage + 2) + " 页"));
        }

        player.openInventory(inv);
        try {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
        } catch (Throwable ignored) {}
    }

    private ItemStack createTicketSummaryItem(TicketManager.Ticket t) {
        Material mat = Material.BOOK;
        if (t.getStatus() == TicketManager.TicketStatus.OPEN) {
            mat = Material.WRITABLE_BOOK;
        } else if (t.getStatus() == TicketManager.TicketStatus.COMPLETED) {
            mat = Material.ENCHANTED_BOOK;
        } else if (t.getStatus() == TicketManager.TicketStatus.NOT_PLANNED) {
            mat = Material.BOOK;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String shortTitle = t.getTitle();
            if (shortTitle.length() > 16) shortTitle = shortTitle.substring(0, 16) + "..";
            meta.setDisplayName(t.getStatus().getColor() + "§l#" + t.getId() + " §f" + shortTitle + " §7(" + t.getStatus().getDisplayName() + ")");
            List<String> lore = new ArrayList<>();
            lore.add("§7标题: §e" + t.getTitle());
            lore.add("§7反馈人: §b" + t.getReporterName());
            lore.add("§7状态: " + t.getStatus().getFormatted());
            lore.add("§7时间: §f" + t.getFormattedDate());
            lore.add("§7子服: §a" + (t.getServerNode().equalsIgnoreCase("technical") ? "生电服" : "生存服"));
            lore.add("");
            lore.add("§6【玩家原话】: §f" + truncate(t.getRawPlayerMessage(), 28));
            lore.add("§d【排查建议】: §7" + truncate(t.getDiagnosticSuggestion(), 28));
            lore.add("");
            lore.add("§e▶ [左键点击] §f进入二级详情面板查看独立诊断与快照");
            lore.add("§a▶ [右键点击] §7切换状态 (未完成/已完成/Not planned)");
            lore.add("§c▶ [Shift+右键] §4删除工单");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ================= LEVEL 2: DETAIL VIEW =================
    public void openDetailGui(Player player, int ticketId, int fromPage) {
        TicketManager.Ticket t = ticketManager.getTicket(ticketId);
        if (t == null) {
            player.sendMessage(ChatColor.RED + "该工单不存在或已被删除！");
            openGui(player, fromPage);
            return;
        }

        String title = DETAIL_TITLE_PREFIX + t.getId() + " §7详情";
        if (title.length() > 32) title = "§6工单 #" + t.getId() + " 详细信息";

        Inventory inv = Bukkit.createInventory(null, 54, title);

        // Slot 13: Core Ticket Information Card
        ItemStack infoCard = new ItemStack(Material.NAME_TAG);
        ItemMeta infoMeta = infoCard.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName("§e§l工单概况 #" + t.getId() + " §f" + t.getTitle());
            List<String> lore = new ArrayList<>();
            lore.add("§7反馈玩家: §b" + t.getReporterName() + " §8(UUID: " + t.getReporterUuid() + ")");
            lore.add("§7提交时间: §f" + t.getFormattedDate());
            lore.add("§7所在子服: §a" + (t.getServerNode().equalsIgnoreCase("technical") ? "生电服" : "生存服"));
            lore.add("§7当前状态: " + t.getStatus().getFormatted());
            infoMeta.setLore(lore);
            infoCard.setItemMeta(infoMeta);
        }
        inv.setItem(13, infoCard);

        // Slot 19: Raw Player Message Card (玩家对AI说的原话)
        ItemStack rawMsgCard = new ItemStack(Material.PAPER);
        ItemMeta rawMeta = rawMsgCard.getItemMeta();
        if (rawMeta != null) {
            rawMeta.setDisplayName("§6§l玩家原话记录 (Raw Dialogue)");
            List<String> lore = new ArrayList<>();
            lore.add("§7玩家在聊天框中所述的原句：");
            lore.add("");
            for (String line : wrapText(t.getRawPlayerMessage(), 36)) {
                lore.add("§f\"§b" + line + "§f\"");
            }
            lore.add("");
            lore.add("§8用于服主核对真实语境，防止谎报恶搞");
            rawMeta.setLore(lore);
            rawMsgCard.setItemMeta(rawMeta);
        }
        inv.setItem(19, rawMsgCard);

        // Slot 22: Diagnostic Suggestion Card (诊断排查建议 - 独立物品)
        ItemStack diagCard = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta diagMeta = diagCard.getItemMeta();
        if (diagMeta != null) {
            diagMeta.setDisplayName("§d§l诊断与排查建议 (AI Diagnosis)");
            List<String> lore = new ArrayList<>();
            lore.add("§7小可结合服务器架构给出的建议：");
            lore.add("");
            for (String line : wrapText(t.getDiagnosticSuggestion(), 36)) {
                lore.add("§e" + line);
            }
            diagMeta.setLore(lore);
            diagCard.setItemMeta(diagMeta);
        }
        inv.setItem(22, diagCard);

        // Slot 25: Environment & Context Snapshot Card (上下文排查快照对照 - 独立物品)
        ItemStack snapCard = new ItemStack(Material.COMPASS);
        ItemMeta snapMeta = snapCard.getItemMeta();
        if (snapMeta != null) {
            snapMeta.setDisplayName("§b§l环境快照与上下文对照 (Snapshot)");
            List<String> lore = new ArrayList<>();
            lore.add("§7案发当时玩家的精确上下文数据：");
            lore.add("");
            for (String line : wrapText(t.getEnvironmentSnapshot(), 36)) {
                lore.add("§f" + line);
            }
            snapMeta.setLore(lore);
            snapCard.setItemMeta(snapMeta);
        }
        inv.setItem(25, snapCard);

        // Slot 38: Set to OPEN
        inv.setItem(38, createActionButton(Material.LIME_DYE, "§a§l标记为: 未完成 (OPEN)", "§7将该工单状态重新设为未完成"));

        // Slot 40: Set to COMPLETED
        inv.setItem(40, createActionButton(Material.BLUE_DYE, "§9§l标记为: 已完成 (COMPLETED)", "§7已修复此问题或处理完毕"));

        // Slot 42: Set to NOT_PLANNED
        inv.setItem(42, createActionButton(Material.GRAY_DYE, "§7§l标记为: Not planned", "§7该问题非Bug、或暂时无需处理"));

        // Slot 45: Back Button
        inv.setItem(45, createNavItem(Material.ARROW, "§c◀ 返回工单列表", "§7返回第 " + (fromPage + 1) + " 页"));

        // Slot 53: Delete Button
        inv.setItem(53, createActionButton(Material.BARRIER, "§4§l删除此工单", "§c点击彻底销毁此工单记录"));

        player.openInventory(inv);
        try {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
        } catch (Throwable ignored) {}
    }

    private ItemStack createActionButton(Material mat, String name, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(List.of(desc));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createNavItem(Material mat, String name, String... lores) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(List.of(lores));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String truncate(String text, int max) {
        if (text == null) return "无";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    private List<String> wrapText(String text, int maxLen) {
        List<String> lines = new ArrayList<>();
        if (text == null) return lines;
        String[] paragraphs = text.split("\n");
        for (String p : paragraphs) {
            while (p.length() > maxLen) {
                lines.add(p.substring(0, maxLen));
                p = p.substring(maxLen);
            }
            if (!p.isEmpty()) lines.add(p);
        }
        return lines;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.contains("工单")) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;

        int slot = event.getRawSlot();

        // Level 2 (Detail View)
        if (title.contains("详情") || title.contains("详细信息")) {
            handleDetailClick(player, title, slot);
            return;
        }

        // Level 1 (List View)
        int page = extractCurrentPage(title);
        if (slot == 45) {
            openGui(player, page - 1);
            return;
        }
        if (slot == 53) {
            openGui(player, page + 1);
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        String name = ChatColor.stripColor(meta.getDisplayName());
        if (!name.startsWith("#")) return;

        try {
            int ticketId = Integer.parseInt(name.split(" ")[0].substring(1));
            TicketManager.Ticket t = ticketManager.getTicket(ticketId);
            if (t == null) return;

            if (event.getClick() == ClickType.SHIFT_RIGHT) {
                // Shift + Right Click -> Delete
                ticketManager.deleteTicket(ticketId);
                player.sendMessage(ChatColor.RED + "已删除工单 #" + ticketId + "！");
                try {
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.7f, 1.0f);
                } catch (Throwable ignored) {}
                openGui(player, page);
            } else if (event.getClick().isRightClick()) {
                // Right Click -> Quick cycle status
                TicketManager.TicketStatus nextStatus = t.getStatus().next();
                ticketManager.updateStatus(ticketId, nextStatus);
                player.sendMessage(ChatColor.GOLD + "工单 #" + ticketId + " 状态更新为: " + nextStatus.getFormatted());
                try {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.3f);
                } catch (Throwable ignored) {}
                openGui(player, page);
            } else if (event.getClick().isLeftClick()) {
                // Left Click -> Open Level 2 Detail View!
                openDetailGui(player, ticketId, page);
            }
        } catch (Exception ignored) {}
    }

    private void handleDetailClick(Player player, String title, int slot) {
        int ticketId = extractTicketIdFromTitle(title);
        if (ticketId <= 0) return;

        TicketManager.Ticket t = ticketManager.getTicket(ticketId);
        if (t == null) {
            openGui(player, 0);
            return;
        }

        if (slot == 45) {
            openGui(player, 0);
            return;
        }

        if (slot == 38) {
            ticketManager.updateStatus(ticketId, TicketManager.TicketStatus.OPEN);
            player.sendMessage(ChatColor.GREEN + "工单 #" + ticketId + " 状态已改为: 未完成 (OPEN)");
            openDetailGui(player, ticketId, 0);
            return;
        }

        if (slot == 40) {
            ticketManager.updateStatus(ticketId, TicketManager.TicketStatus.COMPLETED);
            player.sendMessage(ChatColor.BLUE + "工单 #" + ticketId + " 状态已改为: 已完成 (COMPLETED)");
            openDetailGui(player, ticketId, 0);
            return;
        }

        if (slot == 42) {
            ticketManager.updateStatus(ticketId, TicketManager.TicketStatus.NOT_PLANNED);
            player.sendMessage(ChatColor.GRAY + "工单 #" + ticketId + " 状态已改为: Not planned");
            openDetailGui(player, ticketId, 0);
            return;
        }

        if (slot == 53) {
            ticketManager.deleteTicket(ticketId);
            player.sendMessage(ChatColor.RED + "已删除工单 #" + ticketId + "！");
            openGui(player, 0);
            return;
        }
    }

    private int extractTicketIdFromTitle(String title) {
        try {
            int hash = title.indexOf('#');
            if (hash >= 0) {
                String sub = title.substring(hash + 1).split(" ")[0].trim();
                return Integer.parseInt(sub);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private int extractCurrentPage(String title) {
        try {
            int l = title.lastIndexOf('(');
            int r = title.lastIndexOf('/');
            if (l > 0 && r > l) {
                String pStr = ChatColor.stripColor(title.substring(l + 1, r)).trim();
                return Integer.parseInt(pStr) - 1;
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
