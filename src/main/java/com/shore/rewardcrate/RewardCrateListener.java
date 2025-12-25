package com.shore.rewardcrate;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.block.Action;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RewardCrateListener implements Listener {

    private RewardCrateService service;

    // Prevent double-trigger (Interact + Place) from consuming twice.
    private final Map<UUID, Long> lastUseMs = new HashMap<>();

    public RewardCrateListener(RewardCrateService service) {
        this.service = service;
    }

    public void setService(RewardCrateService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        // If they are interacting with our crate GUI, block breaking should be prevented.
        Inventory top = player.getOpenInventory().getTopInventory();
        if (service.isOurGui(player, top)) {
            event.setCancelled(true);
            return;
        }

        // Also prevent block breaking while holding a crate item (mainhand or offhand).
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (service.isCrateItem(main) || service.isCrateItem(off)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack inHand = event.getItemInHand();
        if (!service.isCrateItem(inHand)) {
            return;
        }

        String crateId = service.matchCrateId(inHand);
        if (crateId == null) {
            return;
        }

        // "Placed" crate: cancel placement, consume item, open GUI.
        event.setCancelled(true);

        // Even if another event handler already consumed/opened this tick, never allow the block to place.
        if (!tryMarkUse(player)) {
            return;
        }

        consumeIfEnabled(player, event.getHand());
        service.openCrate(player, crateId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == null) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack stack = event.getItem();
        if (stack == null || stack.getType() == Material.AIR) return;
        if (!service.isCrateItem(stack)) {
            return;
        }

        String crateId = service.matchCrateId(stack);
        if (crateId == null) {
            return;
        }

        event.setCancelled(true);

        // Prevent double-trigger (Interact + Place) from consuming twice.
        if (!tryMarkUse(player)) {
            return;
        }

        consumeIfEnabled(player, event.getHand());
        service.openCrate(player, crateId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (!service.isOurGui(player, top)) {
            return;
        }

        event.setCancelled(true);

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) return;

        if (!service.getGui().claimSlots().contains(rawSlot)) return;
        if (!service.canClaim(player)) return;

        service.markClaimed(player);
        ItemStack display = service.claimRandomReward(player);
        if (display != null) {
            top.setItem(rawSlot, display);
        }
        // Per request: don't close immediately; close 5 seconds after claiming.
        service.scheduleDelayedClose(player, 20L * 5);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!service.isOurGui(player, top)) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!service.isOurGui(player, event.getView().getTopInventory())) {
            return;
        }
        // Per request: if the player closes early, cancel the pending auto-close.
        service.cancelDelayedClose(player);
        service.clearSession(player);
    }

    private void consumeIfEnabled(Player player, EquipmentSlot slot) {
        if (!service.getGui().consumeItem()) return;

        ItemStack item = (slot == EquipmentSlot.OFF_HAND) ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return;

        int amt = item.getAmount();
        if (amt <= 1) {
            if (slot == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            } else {
                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            }
        } else {
            item.setAmount(amt - 1);
        }
    }

    private boolean tryMarkUse(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        Long last = lastUseMs.get(id);
        if (last != null && (now - last) < 250L) {
            return false;
        }
        lastUseMs.put(id, now);
        return true;
    }
}
