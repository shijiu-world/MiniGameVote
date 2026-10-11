#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# 假 MC 服务端 —— 用来在 MCSManager 面板上"占位"，验证接口调用
#
# 原理：MCSM 判断实例是不是"运行中"，只看它启动的那个进程还在不在。
#       所以只要脚本不退出的挂着，面板上就是「运行中」（status = 3），
#       跟里面跑的是不是真的 java 完全没关系。
#       内存占用 ≈ 1MB（一个 bash + 一个 sleep），不加载世界、不开端口。
#
# 用法：
#   1. 传到服务器，chmod +x fake-mc.sh
#   2. MCSM 新建实例，启动命令填：/opt/fake-mc/fake-mc.sh <名字>
#      比如 /opt/fake-mc/fake-mc.sh hide
#   3. 点开启 → 面板显示"运行中"；点停止 → 脚本收到 SIGTERM 干净退出
#
# 注意：实例类型选「通用/自定义」那类，别选 Minecraft。
#       选 Minecraft 的话面板会额外发 stop 命令、尝试解析 MC 协议，
#       对假实例来说是噪音（不影响状态判定，但控制台会有怪东西）。
# ─────────────────────────────────────────────────────────────────────────────

NAME="${1:-FakeServer}"

echo "[$NAME] 假装加载世界里…"
sleep 1
echo "[$NAME] Done (1.0s)! For help, type \"help\""
echo "[$NAME] ⚠ 这只是个假服务端：不开端口、不读磁盘、不吃内存"

# 面板点「停止」会先发 SIGTERM。接住它，干净退出，状态才会正常变成"已停止"。
trap 'echo "[$NAME] 收到停止信号，退出"; exit 0' TERM INT

# 挂着别退出。顺便把控制台里敲的东西回显出来，看着像那么回事。
while true; do
    # 读 stdin（面板控制台输入），30 秒没输入就继续下一轮。
    if read -r -t 30 line 2>/dev/null; then
        case "$line" in
            stop | end | exit)
                echo "[$NAME] 停止"
                exit 0
                ;;
            "")
                ;;
            *)
                echo "[$NAME] 收到命令：$line"
                ;;
        esac
    else
        # read 超时或 stdin 已关闭。
        # ⚠ 这里必须睡一下：stdin 一旦 EOF，read 会立刻返回失败，
        #   不 sleep 就变成空转死循环，一个实例吃满一个 CPU 核。
        sleep 5
    fi
done
