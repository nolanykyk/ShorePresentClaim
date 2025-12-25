package com.shore.rewardcrate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;
import java.util.regex.Pattern;

public final class TextUtil {
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private TextUtil() {}

    public static Component colorize(String input) {
        if (input == null) return Component.empty();

        // Paper/Adventure legacy serializer understands the "§x§R§R§G§G§B§B" hex format.
        // Convert "&#RRGGBB" -> "§x§R§R§G§G§B§B", then convert '&' -> '§'.
        String s = HEX.matcher(input).replaceAll(match -> {
            String hex = match.group(1).toLowerCase(Locale.ROOT);
            StringBuilder out = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                out.append('§').append(c);
            }
            return out.toString();
        });

        s = s.replace('&', '§');
        return LEGACY_SECTION.deserialize(s)
            .decoration(TextDecoration.ITALIC, false);
    }
}
