package cn.shijiu.minigamevote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/** 消息工具：配置里的 & 颜色码统一走 legacy ampersand 解析（支持 &a、&#RRGGBB）。 */
public final class Msg {

    private Msg() {
    }

    public static Component component(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    public static void send(CommandSender target, String text) {
        if (target == null || text == null || text.isEmpty()) {
            return;
        }
        target.sendMessage(component(text));
    }

    public static void broadcast(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Bukkit.broadcast(component(text));
    }

    /** 把 %key% 依次替换掉；value 为 null 时替换成空串。 */
    public static String fill(String text, String... kv) {
        String out = text == null ? "" : text;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("%" + kv[i] + "%", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return out;
    }
}
