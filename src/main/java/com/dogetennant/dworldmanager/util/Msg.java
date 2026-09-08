package com.dogetennant.dworldmanager.util;

import com.dogetennant.dworldmanager.DWorldManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Resolves a configurable message string (config.yml "messages" key, "&" colour codes, %placeholder% tokens). */
public final class Msg {

    private Msg() {
    }

    /** kv is a flat list of alternating token/value pairs, e.g. Msg.of(plugin, "key", "default", "world", worldName, "count", "5"). */
    public static String text(DWorldManager plugin, String key, String defaultText, String... kv) {
        String raw = plugin.getConfigManager().getMessage(key, defaultText);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("%" + kv[i] + "%", kv[i + 1]);
        }
        return raw;
    }

    public static Component of(DWorldManager plugin, String key, String defaultText, String... kv) {
        return raw(text(plugin, key, defaultText, kv));
    }

    /** Deserializes a string that already contains "&" colour codes (e.g. one built via text() elsewhere). */
    public static Component raw(String legacyText) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
    }
}
