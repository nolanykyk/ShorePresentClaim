package com.shore.rewardcrate;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record GuiConfig(
        String title,
        boolean consumeItem,
        Material fillerMaterial,
        String fillerName,
        List<Integer> claimSlots,
        Material claimMaterial,
        String claimName,
        List<String> claimLore
) {

    public static GuiConfig fromConfig(ConfigurationSection section) {
        if (section == null) {
            return new GuiConfig("Reward Crate", true, Material.GRAY_STAINED_GLASS_PANE, " ", List.of(13), Material.CHEST, "Click to claim", List.of());
        }

        String title = section.getString("title", "Reward Crate");
        boolean consumeItem = section.getBoolean("consume-item", true);

        ConfigurationSection filler = section.getConfigurationSection("filler");
        Material fillerMat = Material.GRAY_STAINED_GLASS_PANE;
        String fillerName = " ";
        if (filler != null) {
            fillerMat = Material.matchMaterial(filler.getString("material", "GRAY_STAINED_GLASS_PANE"));
            if (fillerMat == null) fillerMat = Material.GRAY_STAINED_GLASS_PANE;
            fillerName = filler.getString("name", " ");
        }

        List<Integer> claimSlots = new ArrayList<>();
        for (int slot : section.getIntegerList("claim-slots")) {
            if (slot >= 0 && slot <= 26) claimSlots.add(slot);
        }
        if (claimSlots.isEmpty()) claimSlots.add(13);

        ConfigurationSection claimItem = section.getConfigurationSection("claim-item");
        Material claimMat = Material.CHEST;
        String claimName = "Click to claim";
        List<String> claimLore = Collections.emptyList();
        if (claimItem != null) {
            claimMat = Material.matchMaterial(claimItem.getString("material", "CHEST"));
            if (claimMat == null) claimMat = Material.CHEST;
            claimName = claimItem.getString("name", "Click to claim");
            claimLore = claimItem.getStringList("lore");
        }

        return new GuiConfig(title, consumeItem, fillerMat, fillerName, List.copyOf(claimSlots), claimMat, claimName, List.copyOf(claimLore));
    }
}
