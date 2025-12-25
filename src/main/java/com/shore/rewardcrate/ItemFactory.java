package com.shore.rewardcrate;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ItemFactory {

    private ItemFactory() {}

    public static ItemStack fromSimpleItemSection(ConfigurationSection section) {
        if (section == null) return null;
        String materialStr = section.getString("material");
        if (materialStr == null) return null;
        Material material = Material.matchMaterial(materialStr);
        if (material == null) return null;

        ItemStack stack = new ItemStack(material, Math.max(1, section.getInt("amount", 1)));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String displayName = section.getString("display-name");
        if (displayName != null) {
            meta.displayName(TextUtil.colorize(displayName));
        }

        String name = section.getString("name");
        if (name != null) {
            meta.displayName(TextUtil.colorize(name));
        }

        List<String> loreStr = section.getStringList("lore");
        if (!loreStr.isEmpty()) {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : loreStr) {
                lore.add(TextUtil.colorize(line));
            }
            meta.lore(lore);
        }

        List<String> enchStr = section.getStringList("enchantments");
        if (!enchStr.isEmpty()) {
            applyEnchantments(meta, enchStr);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack fromRewardDisplay(RewardDefinition reward) {
        ItemStack stack = new ItemStack(reward.material(), Math.max(1, reward.amount()));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        if (reward.displayName() != null) {
            meta.displayName(TextUtil.colorize(reward.displayName()));
        }

        // Show lore for currency-style rewards (money/shells) so the GUI displays the amount.
        // Keep other reward items loreless as requested previously.
        boolean hasCommands = reward.commands() != null && !reward.commands().isEmpty();
        boolean isCurrencyToken = reward.material() == Material.NAUTILUS_SHELL || reward.material() == Material.PAPER;
        if (hasCommands && isCurrencyToken && reward.lore() != null && !reward.lore().isEmpty()) {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : reward.lore()) {
                lore.add(TextUtil.colorize(line));
            }
            meta.lore(lore);
        } else {
            meta.lore(null);
        }

        if (reward.enchantments() != null && !reward.enchantments().isEmpty()) {
            applyEnchantments(meta, reward.enchantments());
        }

        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack fromRewardGive(RewardDefinition reward) {
        ItemStack stack = new ItemStack(reward.material(), Math.max(1, reward.amount()));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        // Keep custom names, but don't include lore in the actual given item.
        if (reward.displayName() != null) {
            meta.displayName(TextUtil.colorize(reward.displayName()));
        }
        meta.lore(null);

        if (reward.enchantments() != null && !reward.enchantments().isEmpty()) {
            applyEnchantments(meta, reward.enchantments());
        }

        stack.setItemMeta(meta);
        return stack;
    }

    public static void tagString(ItemStack stack, NamespacedKey key, String value) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(key, PersistentDataType.STRING, value);
        stack.setItemMeta(meta);
    }

    public static String readTagString(ItemStack stack, NamespacedKey key) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static void applyEnchantments(ItemMeta meta, List<String> enchStr) {
        for (String token : enchStr) {
            if (token == null || token.isBlank()) continue;
            String[] parts = token.split(":", 2);
            String rawName = parts[0].trim();
            int level = 1;
            if (parts.length == 2) {
                try {
                    level = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    level = 1;
                }
            }
            if (level < 1) level = 1;

            Enchantment ench = toEnchantment(rawName);
            if (ench == null) continue;

            if (meta instanceof EnchantmentStorageMeta bookMeta) {
                bookMeta.addStoredEnchant(ench, level, true);
            } else {
                meta.addEnchant(ench, level, true);
            }
        }
    }

    private static Enchantment toEnchantment(String rawName) {
        if (rawName == null) return null;
        String key = rawName.trim().toLowerCase(Locale.ROOT);
        key = key.replace(' ', '_');
        return Enchantment.getByKey(NamespacedKey.minecraft(key));
    }
}
