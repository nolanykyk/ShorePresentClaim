package com.shore.rewardcrate;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record RewardDefinition(
        Material material,
        int amount,
        String displayName,
        List<String> lore,
        List<String> enchantments,
    List<String> commands,
    boolean giveItem,
    List<Integer> tiers,
    Integer minTier,
    Integer maxTier
) {

    public static RewardDefinition fromRawMap(Map<?, ?> raw) {
        String materialStr = asString(raw.get("material"), "STONE");
        Material material = Material.matchMaterial(materialStr);
        if (material == null) material = Material.STONE;

        int amount = asInt(raw.get("amount"), 1);
        if (amount < 1) amount = 1;

        String displayName = asString(raw.get("display-name"), null);
        List<String> lore = asStringList(raw.get("lore"));
        List<String> ench = asStringList(raw.get("enchantments"));
        List<String> commands = asStringList(raw.get("commands"));

        List<Integer> tiers = asIntList(raw.get("tiers"));
        Integer minTier = asNullableInt(raw.get("min-tier"));
        Integer tierAlias = asNullableInt(raw.get("tier"));
        Integer maxTier = asNullableInt(raw.get("max-tier"));

        // `tier` is an alias for `min-tier` (reward can be won by that tier or higher).
        if (tierAlias != null) {
            if (minTier == null || tierAlias > minTier) {
                minTier = tierAlias;
            }
        }
        if (minTier != null && minTier < 1) minTier = 1;
        if (maxTier != null && maxTier < 1) maxTier = 1;
        if (minTier != null && maxTier != null && maxTier < minTier) {
            int tmp = minTier;
            minTier = maxTier;
            maxTier = tmp;
        }

        // Optional: allow config to explicitly control whether the physical item is given.
        // Default behavior: if it's a command-based currency reward using shell/paper, don't give the item.
        boolean giveItem;
        if (raw.containsKey("give-item")) {
            giveItem = asBoolean(raw.get("give-item"), true);
        } else {
            boolean hasCommands = commands != null && !commands.isEmpty();
            boolean looksLikeCurrencyToken = material == Material.NAUTILUS_SHELL || material == Material.PAPER || material == Material.BARREL;
            giveItem = !(hasCommands && looksLikeCurrencyToken);
        }

        return new RewardDefinition(material, amount, displayName, lore, ench, commands, giveItem, tiers, minTier, maxTier);
    }

    private static String asString(Object o, String def) {
        if (o == null) return def;
        return String.valueOf(o);
    }

    private static int asInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static List<String> asStringList(Object o) {
        if (o == null) return Collections.emptyList();
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object el : list) {
                if (el != null) out.add(String.valueOf(el));
            }
            return List.copyOf(out);
        }
        return Collections.emptyList();
    }

    private static List<Integer> asIntList(Object o) {
        if (o == null) return Collections.emptyList();
        if (o instanceof List<?> list) {
            List<Integer> out = new ArrayList<>();
            for (Object el : list) {
                Integer v = asNullableInt(el);
                if (v != null && v >= 1) out.add(v);
            }
            return List.copyOf(out);
        }
        return Collections.emptyList();
    }

    private static Integer asNullableInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean asBoolean(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("true") || s.equals("yes") || s.equals("1")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("0")) return false;
        return def;
    }
}
