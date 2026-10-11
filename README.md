# MiniGameVote

大厅小游戏投票插件（Bukkit/Paper）。`/votegame` 打开 TrMenu 菜单，点物品投票；
某个小游戏票数达标后，**异步**发 HTTP 请求开启小游戏。

开启方式只有一种：调 MCSManager 面板接口启动实例。
一个小游戏 = 面板上一个实例（`games.<id>.uuid`）。`[mcsm] enabled = false` 时只计票不联网（调试用）。

## 玩法流程

1. 玩家 `/votegame` → 由控制台执行 `menu.open-command` 打开 TrMenu 菜单
2. 点某个小游戏（或 `/votegame <id>`）→ 投一票，一人一票
3. 玩家退出子服（掉线 / 切服）→ 他投的票自动作废，也收不到后续播报
3. 每投一票的播报（`progress-audience`）：默认只发给**已经投过票**的玩家，
   没投票的人不会被打扰；投票者本人收到的是 `vote-success` 那条确认，不重复发
4. 某个小游戏票数 ≥ `threshold`：
   - 并发查所有小游戏实例的状态码，`2 启动中 / 3 运行中` 就算占用
   - **只要有一个实例在跑** → 广播"本轮作废" → 重置票数
   - **全都停止** → 启动该小游戏对应的实例 → 重置票数
   - 处理期间又有游戏达标 → 记 pending，本轮结束后立刻再走一遍

所有 HTTP 都在独立线程池（`java.net.http.HttpClient`）上跑，主线程零阻塞。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/votegame` | 打开投票菜单 | `minigamevote.vote`（默认所有人） |
| `/votegame <id>` | 直接投票 | 同上 |
| `/votegame stats` | 看票数排名（**票数降序**） | 同上 |
| `/votegameadmin reload` | 重载配置（⚠ 清空票数） | `minigamevote.admin`（默认 op） |
| `/votegameadmin reset` | 重置票数 | 同上 |
| `/votegameadmin stats` | 看票数排名（同上） | 同上 |
| `/votegameadmin threshold <数>` | 查看 / 改阈值 | 同上 |
| `/votegameadmin open` | 给全体在线玩家弹菜单 | 同上 |
| `/votegameadmin test [url]` | 测一次 HTTP（看状态码和响应，apikey 打码） | 同上 |
| `/votegameadmin env` | 看环境变量读到没（只显示名字，不显示值） | 同上 |
| `/votegameadmin mcsm` | 看 MCSM 配置和拼好的地址（apikey 打码） | 同上 |
| `/votegameadmin version` | 看版本与运行状态 | 同上 |

## 投票菜单（TrMenu，软依赖）

仓库里的 [`menu/minigamevote.yml`](menu/minigamevote.yml) 是一份可直接用的菜单，
把它复制到 **任意装了 TrMenu 的服务端** 的 `plugins/TrMenu/menus/` 下，然后 `trm reload`。

- 玩家 `/votegame` → 插件让控制台执行 `[menu] open-command`（默认 `trm open minigamevote %player%`）
- 点和占位符是一一对应的：`%minigamevote_votes_<id>%` 里的 `<id>` 必须等于 `config.toml` 里
  `[games.<id>]` 那个节名，改游戏时两边都要改
- 不装 TrMenu 也能用，只是 `/votegame` 开不出菜单，得靠 `/votegame <id>` 或者自己接别的菜单插件

> 菜单文件所在的服务端和投票插件所在的服务端必须是**同一个**（占位符读的是本地票数）。

## PlaceholderAPI（软依赖，装了才注册）

| 占位符 | 含义 |
| --- | --- |
| `%minigamevote_votes_<id>%` | 某个小游戏当前票数 |
| `%minigamevote_threshold%` | 阈值 |
| `%minigamevote_total%` | 已投票人数 |
| `%minigamevote_leading%` | 当前票数最高的小游戏 |
| `%minigamevote_mine%` | 自己投了谁（没投显示"未投票"） |
| `%minigamevote_mine_id%` | 自己投的 id（没投为空） |

## MCSManager 模式

只对接**当前版本**面板，接口与认证方式写死在插件里，不提供配置开关：

```
GET /api/protected_instance/open?uuid=<实例>&daemonId=<守护>   启动实例
GET /api/instance?uuid=<实例>&daemonId=<守护>                 查实例状态（每个小游戏一次）
Authorization: Bearer <apikey>                              认证请求头
```

实例状态码：`-1 忙碌 / 0 停止 / 1 停止中 / 2 启动中 / 3 运行中`。
默认 `busy-status = [2, 3]`，即"启动中/运行中"都算有小游戏在跑 → 本轮投票作废。

```toml
[mcsm]
enabled = true
url = "http://面板地址:端口"
daemon-id = "守护进程uuid"
apikey = "${env:MCSM_KEY}"     # 🔐 走环境变量
busy-status = [2, 3]           # 哪些状态码算"正在跑"
on-query-error = "idle"        # 查不到时：idle=继续开 / busy=作废本轮
watch = []                     # 额外算作"占用"的实例 uuid，一般留空

[games.hide]
name = "&b躲猫猫"
uuid = "这个游戏对应的实例uuid"   # daemon-id 可选，不在一个守护上时才写
```

`/votegameadmin mcsm` 会打印拼好的地址和每个小游戏对应的实例，用来核对；
`/votegameadmin test` 直接打一次查询地址，看状态码和响应体。

## 配置（config.toml）

用 **TOML** 写，插件第一次启动会在 `plugins/MiniGameVote/` 生成默认文件。
改完 `/votegameadmin reload` 生效（⚠ reload 会清空当前票数）。

### 密钥：用环境变量 `${env:XXX}`

`[mcsm]` 的 `apikey` 别写明文，用 `${env:变量名}`，插件启动时读一次：

```toml
[mcsm]
apikey = "${env:MCSM_KEY}"
```

- `${env:MCSM_KEY:-devkey}` —— 变量没设置时用兜底值
- 没设置且没兜底 → 按**空字符串**处理（绝不把 `${env:XXX}` 原样发给面板），
  后台打 WARNING，`/votegameadmin env` 标红
- 环境变量在**开服脚本里 export**（Windows bat 用 `set`），改完要重启服务端，不是 reload
- apikey 只走请求头，不会拼进 URL；打印地址时仍会做一次打码兜底

> ⚠️ **仓库里的 `config.toml` 全是占位符**（面板地址、守护 uuid、实例 uuid）。
> 仓库是公开的，真值只允许写在你自己服务器上的 `plugins/MiniGameVote/config.toml`。
> 真实 apikey 务必用 `${env:XXX}` 环境变量，别写死在文件里。

### 主要字段

| 字段 | 说明 |
| --- | --- |
| `threshold` | 几票触发 |
| `progress-audience` | 每票播报范围：`voters`=只发给已投票的玩家（默认）/ `all`=全服 / `none`=不播报 |
| `[mcsm] enabled / url / daemon-id / apikey / busy-status / on-query-error / watch` | MCSM 面板接口与判定规则 |
| `[http] timeout-ms / user-agent` | 网络参数（查状态和开实例都用） |
| `[menu] enabled / open-command / reopen-after-vote` | `/votegame` 打开菜单用的命令 |
| `[sounds]` | 音效枚举名 |
| `[messages]` | 全部文案，支持 `&` 颜色码；`stats-line` 里多了 `%rank%` |
| `[games.<id>] name / uuid / daemon-id` | 小游戏；id 就是 `/votegame <id>` 用的那个，`uuid` 是面板实例 |

`on-query-error`：`idle` = 查状态失败（超时/连不上面板）当作空闲、继续开服；
`busy` = 当作占用、作废本轮（更保守，防止重复开服）。

### 票数排名

`/votegame stats` 和 `/votegameadmin stats` 都按**票数降序**输出：票数高的在前，
票数相同的按 `config.toml` 里 `[games.*]` 的先后顺序（排序是稳定的）。

`stats-line` 里可以用 `%rank%` 打名次（从 1 起），默认模板就是 `"&7#%rank% &f%game% &7：&e%votes%&7/&e%threshold%"`；
不想要名次把 `#%rank%` 删掉即可。

> ⚠️ 改文案只对**新生成**的 config.toml 生效 —— 插件不会覆盖你已经改过的那份，
> 想加名次就手动在 `plugins/MiniGameVote/config.toml` 的 `stats-line` 里加上 `%rank%`。

## TOML 解析

自带零依赖解析器 `Toml.java`，只认本插件用到的语法。两个必须注意的点：

- 行尾注释要**按引号状态**扫，否则 `enabled = true # 说明` 会把整串当字符串，布尔判断翻成 false
- 值类型判断 fail-safe：配错了退回默认值，绝不因为一行写歪把插件搞挂

## 构建

```bash
./build.sh            # 本机构建（javac + jar，离线），产物 target/MiniGameVote-<版本>.jar
mvn -B package        # CI 走这条（联网拉 paper-api）
```

本机构建需要用环境变量给路径（默认值未必对得上你的机器）：

```bash
JDK=D:/Java/jdk25  M2=C:/Users/你/.m2/repository  PAPI_JAR=D:/xxx/PlaceholderAPI.jar  ./build.sh
```

版本号唯一真源是 `pom.xml` 的 `<version>`；`plugin.yml` 里写 `${project.version}`，
Maven 走 resources filtering、build.sh 走 sed 替换，改版本只改一处。

> ⚠️ `config.toml` **绝不能**走 Maven filtering：里面有 `${main}` 这类域名变量会被当成 Maven 属性吃掉。
> `pom.xml` 里已经把它排除在外了，别改回去。

## 发版

GitHub Actions（[`.github/workflows/release.yml`](.github/workflows/release.yml)）：

```bash
# 1. 改 pom.xml 的 <version>，提交并推送
git push origin main

# 2. 打同名 tag
git tag v1.0.0
git push origin v1.0.0      # 触发构建 → 自动构建 jar 并挂 Release
```

- 只有 `git push --tags` 推送 **tag** 才发 Release；Actions 页面手动 Run workflow 只构建并上传构建产物
- tag 和 push 分两步执行，别用 `&&` 串（本地曾经串着跑导致 tag 先落地、代码没上去）
