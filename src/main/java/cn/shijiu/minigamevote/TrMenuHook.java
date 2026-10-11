package cn.shijiu.minigamevote;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
 *
 * <p>⚠ <b>懒接入</b>：接入是在第一次刷新时才做的（{@link #prepare()}），不是只在插件
 * 启用时做一次。原因：服里的 TrMenu 很可能是用 PlugMan 热加载的，或者比本插件晚启用 ——
 * 只在 onEnable 探测一次的话，那种情况下会永远停在「没接入」，
 * 而且日志里只有一行不起眼的 info，极难发现。
 */
public final class TrMenuHook {

    private final Logger log;
    private final Plugin plugin;

    private boolean ready;
    private boolean broken;

    // 反射缓存：每次投票都要用，别重复查
    private Object apiInstance;
    private Method apiGetMenuById;
    private Method menuGetViewers;
    private Method menuGetId;
    private Method menuGetIcons;
    private Object sessionCompanion;
    private Method sessionCompanionGetSession;
    private Method sessionGetMenu;
    private Method sessionGetActiveIcons;
    private Method iconOnReset;
    private Method iconGetProperty;
    private Method iconSettingItem;

    // 诊断用：上次刷新的情况，/votegameadmin trmenu 会显示
    private long lastRefreshAt;
    private int lastPlayers;
    private int lastIcons;
    private int refreshCount;
    private String lastError;

    public TrMenuHook(Plugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
    }

    /**
     * 探测一次：把要用到的 TrMenu 类和方法反射出来缓存住。
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
            // 回退用：会话的 activeIcons 为空时（有的版本/条件下会这样），直接用菜单的图标全集
            menuGetIcons = menuClass.getMethod("getIcons");

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
            lastError = null;
            return true;
        } catch (Throwable t) {
            broken = true;
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.log(Level.WARNING,
                    "检测到 TrMenu 但接入失败（多半是版本不匹配）—— 菜单只在玩家点击时刷新，"
                            + "投票功能不受影响。要所有人实时看到票数，在菜单图标上加 update: 40", t);
            return false;
        }
    }

    public boolean available() {
        return ensureReady();
    }

    /** 还没接过就再试一次（TrMenu 可能是后装的 / 热加载的） */
    private boolean ensureReady() {
        if (ready) {
            return true;
        }
        if (broken || plugin == null) {
            return false;
        }
        if (plugin.getServer().getPluginManager().getPlugin("TrMenu") == null) {
            return false;   // 压根没装，不用白费劲
        }
        log.info("检测到 TrMenu（可能是后加载的），正在接入菜单主动刷新…");
        return prepare();
    }

    /**
     * 刷新所有正看着指定菜单的玩家。
     *
     * <p>⚠ 必须在主线程调用 —— 它直接改玩家的背包视图。
     *
     * @return 被刷新的玩家数（0 = 没人开着这个菜单，或刷新失败）
     */
    public int refresh(String menuId) {
        if (!ensureReady() || menuId == null || menuId.isEmpty()) {
            return 0;
        }
        try {
            Object menu = apiGetMenuById.invoke(apiInstance, menuId);
            if (menu == null) {
                // 菜单还没加载、或 id 写错了 —— 只记到诊断里，不刷日志
                lastError = "找不到菜单 " + menuId + "（id 写错，或菜单还没加载/没执行 trm reload）";
                return 0;
            }
            Set<?> viewers = (Set<?>) menuGetViewers.invoke(menu);
            if (viewers == null || viewers.isEmpty()) {
                lastPlayers = 0;
                lastIcons = 0;
                lastError = null;
                return 0;
            }
            int players = 0;
            int icons = 0;
            for (Object name : viewers) {
                Player player = Bukkit.getPlayerExact(String.valueOf(name));
                if (player == null || !player.isOnline()) {
                    continue;
                }
                int n = refreshPlayer(player, menuId, menu);
                if (n > 0) {
                    players++;
                    icons += n;
                }
            }
            lastPlayers = players;
            lastIcons = icons;
            lastRefreshAt = System.currentTimeMillis();
            refreshCount++;
            lastError = null;
            return players;
        } catch (Throwable t) {
            broken = true;
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.log(Level.WARNING, "刷新 TrMenu 菜单时出错，已关闭主动刷新", t);
            return 0;
        }
    }

    private int refreshPlayer(Player player, String menuId, Object menu) throws Exception {
        Object session = sessionCompanionGetSession.invoke(sessionCompanion, player);
        if (session == null) {
            return 0;
        }
        Object menuOfSession = sessionGetMenu.invoke(session);
        if (menuOfSession == null || !menuId.equals(String.valueOf(menuGetId.invoke(menuOfSession)))) {
            return 0;   // 这个玩家开的不是投票菜单
        }
        Set<?> icons = (Set<?>) sessionGetActiveIcons.invoke(session);
        if (icons == null || icons.isEmpty()) {
            // 会话里没记激活图标（有的版本会这样）→ 退一步，用菜单配置的图标全集
            icons = (Set<?>) menuGetIcons.invoke(menu);
        }
        if (icons == null || icons.isEmpty()) {
            return 0;
        }
        for (Object icon : icons) {
            // 就是 TrMenu 的 update 动作做的事：重置动画帧循环 + 重建物品
            iconOnReset.invoke(icon, session);
            Object property = iconGetProperty.invoke(icon, session);
            // ⚠ 第三个参数传 null：settingItem 里会拿它跟当前菜单 id 比对，不一致就静默 return。
            //   上面已经确认过这个会话属于我们的菜单了，传 null 等于跳过那道比对，少一个静默失败点。
            iconSettingItem.invoke(icon, session, property, null);
        }
        return icons.size();
    }

    // ------------------------------------------------------------------
    // 诊断（/votegameadmin trmenu）
    // ------------------------------------------------------------------

    public long lastRefreshAt() {
        return lastRefreshAt;
    }

    public int lastPlayers() {
        return lastPlayers;
    }

    public int lastIcons() {
        return lastIcons;
    }

    public int refreshCount() {
        return refreshCount;
    }

    public String lastError() {
        return lastError;
    }
}
