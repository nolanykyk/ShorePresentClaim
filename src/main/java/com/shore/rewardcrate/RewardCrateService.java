package com.shore.rewardcrate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

public final class RewardCrateService {

    private static final Pattern STRIP_HEX_AMP = Pattern.compile("&#[A-Fa-f0-9]{6}");
    private static final Pattern STRIP_LEGACY_AMP = Pattern.compile("&[0-9A-FK-ORa-fk-or]");

    private final RewardCratePlugin plugin;
    private final NamespacedKey crateKey;
    private final GuiConfig gui;
    private final Map<String, ItemStack> crateItems;
    private final Map<String, Integer> crateItemTiers;
    private final List<RewardDefinition> rewards;
    private final int maxTier;
    private final boolean qualityGating;
    private final boolean requireTierMatch;
    private final double coalChance;
    private final Random random = new SecureRandom();

    private final Map<UUID, Boolean> claimedThisOpen = new HashMap<>();
    private final Map<UUID, BukkitTask> closeTasks = new HashMap<>();
    private final Map<UUID, String> openCrateIds = new HashMap<>();
    private final Map<UUID, Integer> openTiers = new HashMap<>();

    public RewardCrateService(
            RewardCratePlugin plugin,
            NamespacedKey crateKey,
            GuiConfig gui,
            Map<String, ItemStack> crateItems,
            Map<String, Integer> crateItemTiers,
            List<RewardDefinition> rewards,
            int maxTier,
            boolean qualityGating,
            boolean requireTierMatch,
            double coalChance
    ) {
        this.plugin = plugin;
        this.crateKey = crateKey;
        this.gui = gui;
        this.crateItems = Map.copyOf(crateItems);
        this.crateItemTiers = Map.copyOf(crateItemTiers);
        this.rewards = List.copyOf(rewards);
        this.maxTier = Math.max(1, maxTier);
        this.qualityGating = qualityGating;
        this.requireTierMatch = requireTierMatch;
        this.coalChance = Math.max(0D, Math.min(1D, coalChance));
    }

    public GuiConfig getGui() {
        return gui;
    }

    public Map<String, ItemStack> getCrateItems() {
        return crateItems;
    }

    public List<RewardDefinition> getRewards() {
        return rewards;
    }

    public boolean isCrateItem(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;

        String tagged = ItemFactory.readTagString(stack, crateKey);
        if (tagged != null && crateItems.containsKey(tagged)) {
            return true;
        }

        // Fallback: match by material + visible display name (so items given by other plugins still work)
        // Comparing Components directly is brittle (different serializers can produce structurally different Components).
        String incomingName = normalizeName(plainName(stack));
        if (incomingName == null || incomingName.isBlank()) return false;

        for (ItemStack configured : crateItems.values()) {
            if (configured.getType() != stack.getType()) continue;
            String configuredName = normalizeName(plainName(configured));
            if (configuredName == null || configuredName.isBlank()) continue;
            if (configuredName.equalsIgnoreCase(incomingName)) return true;
        }

        return false;
    }

    public String matchCrateId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return null;
        String tagged = ItemFactory.readTagString(stack, crateKey);
        if (tagged != null && crateItems.containsKey(tagged)) return tagged;

        String incomingName = normalizeName(plainName(stack));
        if (incomingName == null || incomingName.isBlank()) return null;

        for (Map.Entry<String, ItemStack> entry : crateItems.entrySet()) {
            ItemStack configured = entry.getValue();
            if (configured.getType() != stack.getType()) continue;
            String configuredName = normalizeName(plainName(configured));
            if (configuredName == null || configuredName.isBlank()) continue;
            if (configuredName.equalsIgnoreCase(incomingName)) return entry.getKey();
        }

        return null;
    }

    public int tierForCrateId(String crateId) {
        if (crateId == null) return 1;
        return Math.max(1, crateItemTiers.getOrDefault(crateId, 1));
    }

    private static String plainName(ItemStack stack) {
        var meta = stack.getItemMeta();
        if (meta == null) return null;
        Component name = meta.displayName();
        if (name == null) return null;
        return PlainTextComponentSerializer.plainText().serialize(name).trim();
    }

    private static String normalizeName(String s) {
        if (s == null) return null;
        String out = STRIP_HEX_AMP.matcher(s).replaceAll("");
        out = STRIP_LEGACY_AMP.matcher(out).replaceAll("");
        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    public void openCrate(Player player, String crateId) {
        cancelDelayedClose(player);
        openCrateIds.put(player.getUniqueId(), crateId);
        openTiers.put(player.getUniqueId(), tierForCrateId(crateId));
        RewardCrateHolder holder = new RewardCrateHolder(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, InventoryType.BARREL, TextUtil.colorize(gui.title()));
        holder.setInventory(inv);

        claimedThisOpen.put(player.getUniqueId(), false);

        ItemStack filler = new ItemStack(gui.fillerMaterial(), 1);
        var fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(TextUtil.colorize(gui.fillerName()));
            filler.setItemMeta(fillerMeta);
        }

        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        ItemStack claim = new ItemStack(gui.claimMaterial(), 1);
        var claimMeta = claim.getItemMeta();
        if (claimMeta != null) {
            claimMeta.displayName(TextUtil.colorize(gui.claimName()));
            if (gui.claimLore() != null && !gui.claimLore().isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : gui.claimLore()) {
                    lore.add(TextUtil.colorize(line));
                }
                claimMeta.lore(lore);
            }
            claim.setItemMeta(claimMeta);
        }

        for (int slot : gui.claimSlots()) {
            if (slot >= 0 && slot < inv.getSize()) {
                inv.setItem(slot, claim);
            }
        }

        player.openInventory(inv);
    }

    public void scheduleDelayedClose(Player player, long delayTicks) {
        cancelDelayedClose(player);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                closeTasks.remove(player.getUniqueId());
                return;
            }

            Inventory top = player.getOpenInventory().getTopInventory();
            if (isOurGui(player, top)) {
                player.closeInventory();
            }
            closeTasks.remove(player.getUniqueId());
        }, delayTicks);
        closeTasks.put(player.getUniqueId(), task);
    }

    public void cancelDelayedClose(Player player) {
        BukkitTask task = closeTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }

    public boolean isOurGui(Player player, Inventory top) {
        if (top == null) return false;
        if (!(top.getHolder() instanceof RewardCrateHolder holder)) return false;
        return holder.getPlayerId().equals(player.getUniqueId());
    }

    public boolean canClaim(Player player) {
        return !claimedThisOpen.getOrDefault(player.getUniqueId(), false);
    }

    public void markClaimed(Player player) {
        claimedThisOpen.put(player.getUniqueId(), true);
    }

    public void clearSession(Player player) {
        cancelDelayedClose(player);
        claimedThisOpen.remove(player.getUniqueId());
        openCrateIds.remove(player.getUniqueId());
        openTiers.remove(player.getUniqueId());
    }

    public void shutdown() {
        for (BukkitTask task : closeTasks.values()) {
            if (task != null) task.cancel();
        }
        closeTasks.clear();
        claimedThisOpen.clear();
        openCrateIds.clear();
        openTiers.clear();
    }

    public void giveRandomReward(Player player) {
        // Backwards-compatible helper; kept but now delegates.
        claimRandomReward(player);
    }

    public ItemStack claimRandomReward(Player player) {
        if (rewards.isEmpty()) {
            player.sendMessage(Component.text("No rewards configured."));
            return null;
        }

        int tier = Math.max(1, openTiers.getOrDefault(player.getUniqueId(), 1));
        int cappedTier = Math.min(tier, maxTier);

        // 25% (configurable) chance to get coal from any present.
        if (random.nextDouble() < coalChance) {
            ItemStack coal = new ItemStack(Material.COAL, 1);
            Map<Integer, ItemStack> remaining = player.getInventory().addItem(coal);
            for (ItemStack rem : remaining.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rem);
            }
            return coal;
        }

        List<RewardDefinition> eligible = eligibleRewardsForTier(cappedTier);
        if (eligible.isEmpty()) eligible = rewards;
        RewardDefinition reward = eligible.get(random.nextInt(eligible.size()));

        // Run commands first (optional)
        if (reward.commands() != null) {
            for (String cmd : reward.commands()) {
                System.out.println(cmd);
                if (cmd == null || cmd.isBlank()) continue;
                System.out.println("retarded");
                String finalCmd = cmd.replace("{player}", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
            }
        }

        // Give physical item only when configured
        if (reward.giveItem()) {
            ItemStack stack = ItemFactory.fromRewardGive(reward);
            Map<Integer, ItemStack> remaining = player.getInventory().addItem(stack);
            for (ItemStack rem : remaining.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rem);
            }
        }

        // Always return a display item so the GUI can show what was won.
        return ItemFactory.fromRewardDisplay(reward);
    }

    private List<RewardDefinition> eligibleRewardsForTier(int tier) {
        if (rewards.isEmpty()) return List.of();

        List<RewardDefinition> allowedByRules = new ArrayList<>();
        for (RewardDefinition r : rewards) {
            if (!hasAnyTierRules(r)) continue;
            if (isAllowedByExplicitTierRules(r, tier)) {
                allowedByRules.add(r);
            }
        }

        // Strict mode: only rewards with explicit tier rules are eligible.
        if (requireTierMatch) {
            return allowedByRules;
        }

        // If any rewards define explicit rules, still allow the others unless quality-gating is enabled.
        // We apply quality-gating only to rewards without explicit rules.
        if (!qualityGating) {
            // If no explicit rules, everything is eligible
            if (allowedByRules.size() == rewards.size()) return allowedByRules;

            List<RewardDefinition> out = new ArrayList<>();
            for (RewardDefinition r : rewards) {
                if (hasAnyTierRules(r)) {
                    if (isAllowedByExplicitTierRules(r, tier)) out.add(r);
                } else {
                    out.add(r);
                }
            }
            return out;
        }

        int n = rewards.size();
        double threshold = maxTier <= 0 ? 0D : (Math.max(1, Math.min(tier, maxTier)) - 1) / (double) maxTier;
        if (n == 1) threshold = 0D;

        List<RewardDefinition> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            RewardDefinition r = rewards.get(i);
            if (hasAnyTierRules(r)) {
                if (isAllowedByExplicitTierRules(r, tier)) out.add(r);
                continue;
            }

            double frac = (n <= 1) ? 1D : (i / (double) (n - 1));
            if (frac >= threshold) {
                out.add(r);
            }
        }

        return out;
    }

    private static boolean hasAnyTierRules(RewardDefinition r) {
        return (r.tiers() != null && !r.tiers().isEmpty()) || r.minTier() != null || r.maxTier() != null;
    }

    private static boolean isAllowedByExplicitTierRules(RewardDefinition r, int tier) {
        if (r.tiers() != null && !r.tiers().isEmpty()) {
            return r.tiers().contains(tier);
        }
        if (r.minTier() != null && tier < r.minTier()) return false;
        if (r.maxTier() != null && tier > r.maxTier()) return false;
        return true;
    }
}
