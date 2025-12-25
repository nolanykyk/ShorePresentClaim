package com.shore.rewardcrate;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class RewardCratePlugin extends JavaPlugin {

    private NamespacedKey crateKey;
    private RewardCrateService crateService;
    private RewardCrateListener listener;

    @Override
    public void onEnable() {
        migrateConfigIfNeeded();
        saveDefaultConfig();

        this.crateKey = new NamespacedKey(this, "crate_id");
        reloadCrateConfig();

        this.listener = new RewardCrateListener(crateService);
        Bukkit.getPluginManager().registerEvents(listener, this);

        getLogger().info("Enabled with " + crateService.getCrateItems().size() + " crate items and " + crateService.getRewards().size() + " rewards.");
    }

    private void migrateConfigIfNeeded() {
        // If the plugin name changes, Bukkit changes the data folder.
        // Migrate existing config from the previous folder name so users don't think
        // the plugin is ignoring their config.
        File newConfig = new File(getDataFolder(), "config.yml");
        if (newConfig.exists()) return;

        File pluginsDir = getDataFolder().getParentFile();
        if (pluginsDir == null) return;

        File oldFolder = new File(pluginsDir, "ShorePresentsClaim");
        File oldConfig = new File(oldFolder, "config.yml");
        if (!oldConfig.exists()) return;

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder for config migration.");
            return;
        }

        try {
            Files.copy(oldConfig.toPath(), newConfig.toPath(), StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("Migrated config.yml from ShorePresentsClaim to RewardCrate.");
        } catch (IOException e) {
            getLogger().warning("Failed to migrate old config.yml: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rewardcrate")) {
            return false;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rewardcrate.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            reloadConfig();
            reloadCrateConfig();
            sender.sendMessage("§aRewardCrate config reloaded.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            if (!sender.hasPermission("rewardcrate.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            sender.sendMessage("§eCrate items: §f" + String.join(", ", crateService.getCrateItems().keySet()));
            return true;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("rewardcrate.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }

            String crateId = args[2];
            ItemStack base = crateService.getCrateItems().get(crateId);
            if (base == null) {
                sender.sendMessage("§cUnknown crate item id. Use /rewardcrate list");
                return true;
            }

            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
            }
            if (amount < 1) amount = 1;
            if (amount > 64) amount = 64;

            ItemStack give = base.clone();
            give.setAmount(amount);

            var leftovers = target.getInventory().addItem(give);
            for (ItemStack rem : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), rem);
            }

            sender.sendMessage("§aGave §f" + amount + "§a x §f" + crateId + "§a to §f" + target.getName() + "§a.");
            return true;
        }

        sender.sendMessage("§cUsage: /rewardcrate reload | /rewardcrate list | /rewardcrate give <player> <crateId> [amount]");
        return true;
    }

    private void reloadCrateConfig() {
        FileConfiguration cfg = getConfig();

        GuiConfig gui = GuiConfig.fromConfig(cfg.getConfigurationSection("gui"));

        ConfigurationSection tiersSection = cfg.getConfigurationSection("tiers");
        boolean qualityGating = tiersSection == null || tiersSection.getBoolean("quality-gating", true);
        boolean requireTierMatch = tiersSection != null && tiersSection.getBoolean("require-tier-match", false);
        double coalChance = tiersSection == null ? 0.25D : tiersSection.getDouble("coal-chance", 0.25D);
        if (coalChance < 0D) coalChance = 0D;
        if (coalChance > 1D) coalChance = 1D;

        Map<String, ItemStack> crateItems = new LinkedHashMap<>();
        Map<String, Integer> crateItemTiers = new LinkedHashMap<>();
        ConfigurationSection crateSection = cfg.getConfigurationSection("crate-items");
        if (crateSection != null) {
            for (String key : crateSection.getKeys(false)) {
                ConfigurationSection itemSection = crateSection.getConfigurationSection(key);
                if (itemSection == null) continue;
                ItemStack stack = ItemFactory.fromSimpleItemSection(itemSection);
                if (stack == null) continue;

                // Per request: hard-code item6 to always be PAPER.
                if (key.equalsIgnoreCase("item6") && stack.getType() != Material.PAPER) {
                    stack.setType(Material.PAPER);
                }

                ItemFactory.tagString(stack, crateKey, key);
                crateItems.put(key, stack);

                int tier = itemSection.getInt("tier", 1);
                if (tier < 1) tier = 1;
                crateItemTiers.put(key, tier);
            }
        }

        List<RewardDefinition> rewards = new ArrayList<>();
        if (cfg.isList("rewards")) {
            for (Object raw : cfg.getList("rewards")) {
                if (!(raw instanceof Map<?, ?> rawMap)) continue;
                rewards.add(RewardDefinition.fromRawMap(rawMap));
            }
        }

        applyHardcodedLootboxRewards(rewards);

        int inferredMaxTier = 1;
        for (int tier : crateItemTiers.values()) {
            if (tier > inferredMaxTier) inferredMaxTier = tier;
        }
        int maxTier = tiersSection == null ? inferredMaxTier : tiersSection.getInt("max-tier", inferredMaxTier);
        if (maxTier < 1) maxTier = 1;

        this.crateService = new RewardCrateService(this, crateKey, gui, crateItems, crateItemTiers, rewards, maxTier, qualityGating, requireTierMatch, coalChance);
        if (listener != null) {
            listener.setService(crateService);
        }
    }

    private static void applyHardcodedLootboxRewards(List<RewardDefinition> rewards) {
        // Per request: hard-code these rewards into the jar so they always exist.
        // They should NOT give the barrel item; they should only run the command.

        // Note: many lootbox plugins treat the lootbox id as case-sensitive.
        String vortexCmd = "shorelootboxgive {player} Vortex 1";
        String celestialCmd = "shorelootboxgive {player} Celestial 1";

        rewards.removeIf(r -> {
            if (r == null) return false;
            if (r.material() != Material.BARREL) return false;
            if (r.commands() == null) return false;
            for (String cmd : r.commands()) {
                if (cmd == null) continue;
                String c = cmd.trim();
                // Remove any previous hard-coded variants (case differences, etc.) before re-adding.
                if (c.equalsIgnoreCase(vortexCmd) || c.equalsIgnoreCase(celestialCmd)) return true;
                if (c.equalsIgnoreCase("shorelootboxgive {player} vortex 1") || c.equalsIgnoreCase("shorelootboxgive {player} celestial 1")) return true;
            }
            return false;
        });

        rewards.add(new RewardDefinition(
                Material.BARREL,
                1,
                "&#00FF46&lVORTEX LOOTBOX",
                List.of(),
                List.of(),
                List.of(vortexCmd),
                false,
                List.of(),
                5,
                null
        ));

        rewards.add(new RewardDefinition(
                Material.BARREL,
                1,
                "&#00C7FF&lC&#00C7FF&lE&#00C7FF&lL&#40D2BF&lE&#80DD80&lS&#BFE840&lT&#FFF300&lI&#FFF300&lA&#FFF300&lL &#00C7FF&lL&#00C7FF&lO&#00C7FF&lO&#55D6AA&lT&#AAE455&lB&#FFF300&lO&#FFF300&lX",
                List.of(),
                List.of(),
                List.of(celestialCmd),
                false,
                List.of(),
                5,
                null
        ));
    }

    public NamespacedKey getCrateKey() {
        return crateKey;
    }
}
