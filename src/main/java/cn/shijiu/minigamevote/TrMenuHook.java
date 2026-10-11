package cn.shijiu.minigamevote;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主动刷新 TrMenu 菜单 —— 票数一变，就让所有正开着这个菜单的玩家立刻看到新票数。
 *
 * <p><b>为什么需要它</b>：TrMenu 把图标构建出来的 ItemStack 按会话 id 缓存住了
 * （{@code Item} 里是 {@code HashMap<sessionId, ItemStack>}），菜单开着的时候
 * lore 里的占位符不会再解析一次。菜单里给图标写 {@code update: 20} 是让每个玩家
 * 各自定时轮询；这里是另一种做法 —— 由插件在票数变化的那一刻主动推一次，
 * 既实时，又没有常驻的轮询开销（不开菜单的玩家完全不占资源）。
 *
 * <p><b>实现</b>：等价于对每个看着该菜单的玩家跑一遍 TrMenu 的 {@code update}
 * 动作，即 {@code Icon.onReset()} + {@code Icon.settingItem()}。
 *
 * <p>⚠ <b>软依赖</b>：TrMenu 不在 Maven 仓库里，编译期拿不到它的类，所以全程反射，
 * 装没装都不影响本插件启动。反射失败只是关掉这个功能并打一条警告；投票、计票、
 * 开服那些逻辑跟这个完全无关。
 */
public final class TrMenuHook {

    private final Logger log;

    private boolean ready;
    private boolean broken;

    // 反射缓存：每次投票都要用，别重复查
    private Object apiInstance;
    private Method apiGetMenuById;
    private Method menuGetViewers;
    private Method menuGetId;
    private Object sessionCompanion;
    private Method sessionCompanionGetSession;
    private Method sessionGetMenu;
    private Method sessionGetActiveIcons;
    private Method iconOnReset;
    private Method iconGetProperty;
    private Method iconSettingItem;

    public TrMenuHook(Logger log) {
        this.log = log;
    }

    /**
     * 启动时探测一次：把要用到的 TrMenu 类和方法反射出来缓存住。
     * TrMenu 版本对不上（类/方法名变了）会返回 false 并打一条警告。
     */
    public boolean prepare() {
        try {
            Class<?> api = Class.forName("me.arasple.mc.trmenu.api.TrMenuAPI");
            apiInstance = api.getField("INSTANCE").get(null);
            apiGetMenuById = api.getMethod("getMenuById", String.class);

            Class<?> menuClass = Class.forName("me.arasple.mc.trmenu.module.display.Menu");
            menuGetId = menuClass.getMethod("getId");
            menuGetViewers = menuClass.getMethod("getViewers");

            Class<?> sessionClass = Class.forName("me.arasple.mc.trmenu.module.display.MenuSession");
            Class<?> sessionCompanionClass =
                    Class.forName("me.arasple.mc.trmenu.module.display.MenuSession$Companion");
            // ⚠ Companion 字段挂在 MenuSession 上，不在 MenuSession$Companion 上
            //   （Kotlin 把 companion object 编译成独立类，但持有它的静态字段在宿主类里）
            sessionCompanion = sessionClass.getField("Companion").get(null);
            sessionCompanionGetSession = sessionCompanionClass.getMethod("getSession", Player.class);
            sessionGetMenu = sessionClass.getMethod("getMenu");
            sessionGetActiveIcons = sessionClass.getMethod("getActiveIcons");

            Class<?> iconClass = Class.forName("me.arasple.mc.trmenu.module.display.icon.Icon");
            Class<?> iconPropertyClass =
                    Class.forName("me.arasple.mc.trmenu.module.display.icon.IconProperty");
            iconOnReset = iconClass.getMethod("onReset", sessionClass);
            iconGetProperty = iconClass.getMethod("getProperty", sessionClass);
            iconSettingItem = iconClass.getMethod("settingItem", sessionClass, iconPropertyClass, String.class);

            ready = true;
            return true;
        } catch (Throwable t) {
            broken = true;
            log.log(Level.WARNING,
                    "检测到 TrMenu 但接入失败（多半是版本不匹配）—— 菜单只在玩家点击时刷新，"
                            + "投票功能不受影响。要所有人实时看到票数，在菜单图标上加 update: 40", t);
            return false;
        }
    }

    public boolean available() {
        return ready && !broken;
    }

    /**
     * 刷新所有正看着指定菜单的玩家。
     *
     * <p>⚠ 必须在主线程调用 —— 它直接改玩家的背包视图。
     */
    public void refresh(String menuId) {
        if (!available() || menuId == null || menuId.isEmpty()) {
            return;
        }
        try {
            Object menu = apiGetMenuById.invoke(apiInstance, menuId);
            if (menu == null) {
                return;   // 菜单还没加载、或 id 写错了 —— 静默跳过，不刷日志
            }
            Set<?> viewers = (Set<?>) menuGetViewers.invoke(menu);
            if (viewers == null || viewers.isEmpty()) {
                return;
            }
            for (Object name : viewers) {
                Player player = Bukkit.getPlayerExact(String.valueOf(name));
                if (player == null || !player.isOnline()) {
                    continue;
                }
                refreshPlayer(player, menuId);
            }
        } catch (Throwable t) {
            broken = true;
            log.log(Level.WARNING, "刷新 TrMenu 菜单时出错，已关闭主动刷新", t);
        }
    }

    private void refreshPlayer(Player player, String menuId) throws Exception {
        Object session = sessionCompanionGetSession.invoke(sessionCompanion, player);
        if (session == null) {
            return;
        }
        Object menu = sessionGetMenu.invoke(session);
        if (menu == null || !menuId.equals(String.valueOf(menuGetId.invoke(menu)))) {
            return;   // 这个玩家开的不是投票菜单
        }
        Set<?> icons = (Set<?>) sessionGetActiveIcons.invoke(session);
        if (icons == null) {
            return;
        }
        for (Object icon : icons) {
            // 就是 TrMenu 的 update 动作做的事：重置动画帧循环 + 重建物品
            iconOnReset.invoke(icon, session);
            Object property = iconGetProperty.invoke(icon, session);
            iconSettingItem.invoke(icon, session, property, menuId);
        }
    }
}
