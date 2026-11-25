package com.clericsbag.listener;

import com.clericsbag.ClericsBagPlugin;
import com.clericsbag.gui.BagGuiHolder;
import com.clericsbag.gui.BagGuiManager;
import com.clericsbag.item.ClericsBagStorage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class BagInventoryListener implements Listener {

    private final ClericsBagPlugin plugin;
    private final ClericsBagStorage storage;
    private final BagGuiManager guiManager;

    public BagInventoryListener(
            ClericsBagPlugin plugin,
            ClericsBagStorage storage,
            BagGuiManager guiManager
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.guiManager = guiManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof BagGuiHolder holder) {
            handleGuiClick(event, player, top, holder);
            return;
        }

        // Outside the GUI: quick-load potions onto bag OR right-click bag with empty cursor to open Order Editor
        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (storage.isClericsBag(clicked)) {
            if (storage.isSupportedPotion(cursor)) {
                event.setCancelled(true);
                if (!player.hasPermission("clericsbag.use")) {
                    player.sendActionBar(plugin.rawMsg("no-permission"));
                    return;
                }
                if (storage.appendPotion(clicked, cursor)) {
                    String name = storage.describePotion(cursor);
                    cursor.setAmount(cursor.getAmount() - 1);
                    player.setItemOnCursor(cursor.getAmount() > 0 ? cursor : new ItemStack(Material.AIR));
                    player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.9f, 1.1f);

                    int count = storage.countPotions(storage.readSlots(clicked));
                    String raw = plugin.getConfig().getString("messages.added-potion", "")
                            .replace("{potion}", name)
                            .replace("{count}", String.valueOf(count))
                            .replace("{max}", String.valueOf(storage.getMaxPotions()));
                    player.sendActionBar(plugin.parse(raw));
                } else {
                    String raw = plugin.getConfig().getString("messages.bag-full", "")
                            .replace("{max}", String.valueOf(storage.getMaxPotions()));
                    player.sendActionBar(plugin.parse(raw));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.75f);
                }
                return;
            }

            // Right-clicking Cleric's Bag with empty cursor opens the Order Editor GUI instead of vanilla bundle dump
            if (event.getClick() == ClickType.RIGHT && (cursor == null || cursor.getType().isAir())) {
                event.setCancelled(true);
                if (player.hasPermission("clericsbag.use")) {
                    Bukkit.getScheduler().runTask(plugin, () -> guiManager.openGui(player, clicked));
                }
                return;
            }

            // Block putting non-potion items into Cleric's Bag via vanilla bundle click
            if (cursor != null && !cursor.getType().isAir() && event.getClick() == ClickType.RIGHT) {
                event.setCancelled(true);
                return;
            }
        }

        // Block right-clicking a Cleric's Bag held on cursor onto another item (prevents vanilla bundle pickup)
        if (storage.isClericsBag(cursor) && event.getClick() == ClickType.RIGHT) {
            if (storage.isSupportedPotion(clicked)) {
                event.setCancelled(true);
                if (storage.appendPotion(cursor, clicked)) {
                    String name = storage.describePotion(clicked);
                    clicked.setAmount(clicked.getAmount() - 1);
                    if (clicked.getAmount() <= 0) {
                        event.setCurrentItem(new ItemStack(Material.AIR));
                    }
                    player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.9f, 1.1f);
                    int count = storage.countPotions(storage.readSlots(cursor));
                    String raw = plugin.getConfig().getString("messages.added-potion", "")
                            .replace("{potion}", name)
                            .replace("{count}", String.valueOf(count))
                            .replace("{max}", String.valueOf(storage.getMaxPotions()));
                    player.sendActionBar(plugin.parse(raw));
                } else {
                    String raw = plugin.getConfig().getString("messages.bag-full", "")
                            .replace("{max}", String.valueOf(storage.getMaxPotions()));
                    player.sendActionBar(plugin.parse(raw));
                }
            } else if (clicked != null && !clicked.getType().isAir()) {
                event.setCancelled(true);
            }
        }
    }

    private void handleGuiClick(InventoryClickEvent event, Player player, Inventory top, BagGuiHolder holder) {
        String openUid = holder.getBagUid();

        // Anti-dupe: never allow interacting with the open Cleric's Bag item itself
        if (isLockedBag(event.getCurrentItem(), openUid) || isLockedBag(event.getCursor(), openUid)) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.NUMBER_KEY) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (isLockedBag(hotbar, openUid)) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND && isLockedBag(player.getInventory().getItemInOffHand(), openUid)) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        boolean clickedTop = rawSlot >= 0 && rawSlot < top.getSize();

        // Control bar buttons & borders (slots 16..26)
        if (clickedTop && rawSlot >= BagGuiManager.POTION_SLOTS) {
            event.setCancelled(true);
            handleControlButton(player, top, holder, rawSlot);
            return;
        }

        // Clicking inside slots 0..15 (the 16 ordered potion slots)
        if (clickedTop) {
            int max = storage.getMaxPotions();
            if (rawSlot >= max) {
                event.setCancelled(true);
                return;
            }

            if (event.getClick() == ClickType.NUMBER_KEY) {
                ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
                if (hotbarItem != null && !hotbarItem.getType().isAir() && !storage.isSupportedPotion(hotbarItem)) {
                    event.setCancelled(true);
                    return;
                }
                if (hotbarItem != null && hotbarItem.getAmount() > 1) {
                    event.setCancelled(true);
                    return;
                }
            } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                ItemStack offhand = player.getInventory().getItemInOffHand();
                if (!offhand.getType().isAir() && (!storage.isSupportedPotion(offhand) || offhand.getAmount() > 1)) {
                    event.setCancelled(true);
                    return;
                }
            } else {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir()) {
                    if (!storage.isSupportedPotion(cursor)) {
                        event.setCancelled(true);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
                        return;
                    }
                    // If player somehow holds a stacked potion (>1), place only 1 into the slot cleanly
                    if (cursor.getAmount() > 1) {
                        event.setCancelled(true);
                        ItemStack existing = top.getItem(rawSlot);
                        if (existing == null || existing.getType().isAir()) {
                            ItemStack single = cursor.clone();
                            single.setAmount(1);
                            top.setItem(rawSlot, single);
                            cursor.setAmount(cursor.getAmount() - 1);
                            player.setItemOnCursor(cursor);
                            scheduleGuiSync(player, top, openUid);
                        }
                        return;
                    }
                }
            }

            scheduleGuiSync(player, top, openUid);
            return;
        }

        // Shift-clicking from player's bottom inventory into the bag GUI
        if (event.isShiftClick()) {
            event.setCancelled(true);
            ItemStack moving = event.getCurrentItem();
            if (!storage.isSupportedPotion(moving)) {
                return;
            }
            int max = storage.getMaxPotions();
            int freeSlot = -1;
            for (int i = 0; i < max; i++) {
                ItemStack slotItem = top.getItem(i);
                if (slotItem == null || slotItem.getType().isAir()) {
                    freeSlot = i;
                    break;
                }
            }
            if (freeSlot == -1) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.75f);
                return;
            }
            ItemStack single = moving.clone();
            single.setAmount(1);
            top.setItem(freeSlot, single);
            moving.setAmount(moving.getAmount() - 1);
            if (moving.getAmount() <= 0) {
                event.setCurrentItem(new ItemStack(Material.AIR));
            }
            player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.8f, 1.15f);
            scheduleGuiSync(player, top, openUid);
        }
    }

    private void handleControlButton(Player player, Inventory top, BagGuiHolder holder, int slot) {
        ItemStack[] current = guiManager.extractPotionSlots(top);
        List<ItemStack> ordered = storage.getOrderedNonEmpty(current);

        switch (slot) {
            case BagGuiManager.BTN_CYCLE -> {
                if (ordered.size() > 1) {
                    ItemStack first = ordered.remove(0);
                    ordered.add(first);
                    ItemStack[] updated = toSlotArray(ordered);
                    guiManager.applySlotsToGui(top, updated);
                    guiManager.saveGuiToPlayerBag(player, top, holder.getBagUid());
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.25f);
                }
            }
            case BagGuiManager.BTN_REVERSE -> {
                if (ordered.size() > 1) {
                    List<ItemStack> reversed = new ArrayList<>(ordered.size());
                    for (int i = ordered.size() - 1; i >= 0; i--) {
                        reversed.add(ordered.get(i));
                    }
                    ItemStack[] updated = toSlotArray(reversed);
                    guiManager.applySlotsToGui(top, updated);
                    guiManager.saveGuiToPlayerBag(player, top, holder.getBagUid());
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
                }
            }
            case BagGuiManager.BTN_COMPACT -> {
                ItemStack[] compacted = storage.compactSlots(current);
                guiManager.applySlotsToGui(top, compacted);
                guiManager.saveGuiToPlayerBag(player, top, holder.getBagUid());
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.35f);
            }
            case BagGuiManager.BTN_CLOSE -> {
                player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.8f, 1.0f);
                player.closeInventory();
            }
            default -> {
            }
        }
    }

    private ItemStack[] toSlotArray(List<ItemStack> ordered) {
        ItemStack[] slots = storage.createEmptySlotArray();
        int limit = Math.min(storage.getMaxPotions(), ordered.size());
        for (int i = 0; i < limit; i++) {
            slots[i] = ordered.get(i);
        }
        return slots;
    }

    private void scheduleGuiSync(Player player, Inventory top, String bagUid) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            guiManager.refreshControlBar(top);
            guiManager.saveGuiToPlayerBag(player, top, bagUid);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BagGuiHolder holder)) {
            return;
        }
        if (isLockedBag(event.getOldCursor(), holder.getBagUid())) {
            event.setCancelled(true);
            return;
        }
        int max = storage.getMaxPotions();
        boolean touchesTop = false;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                touchesTop = true;
                if (rawSlot >= max || !storage.isSupportedPotion(event.getOldCursor())) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (touchesTop) {
            scheduleGuiSync(player, top, holder.getBagUid());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getInventory();
        if (top.getHolder() instanceof BagGuiHolder holder) {
            guiManager.endSession(player, top, holder.getBagUid());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDropWhileOpen(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        String openUid = guiManager.getOpenBagUid(player);
        if (openUid != null && isLockedBag(event.getItemDrop().getItemStack(), openUid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHandWhileOpen(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        String openUid = guiManager.getOpenBagUid(player);
        if (openUid != null && (isLockedBag(event.getMainHandItem(), openUid) || isLockedBag(event.getOffHandItem(), openUid))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof BagGuiHolder holder) {
            guiManager.endSession(player, top, holder.getBagUid());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof BagGuiHolder holder) {
            guiManager.endSession(player, top, holder.getBagUid());
        }
    }

    private boolean isLockedBag(ItemStack item, String openUid) {
        if (openUid == null || !storage.isClericsBag(item)) {
            return false;
        }
        return openUid.equals(storage.getOrAssignBagUid(item));
    }
}
