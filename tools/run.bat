@echo off
setlocal enabledelayedexpansion

rem ─────────────────────────────────────────────────────────────────────────────
rem 假 MC 服务端 —— 用来在 MCSManager 面板上"占位"，验证接口调用
rem
rem 原理：MCSM 判断实例是不是"运行中"，只看它启动的那个进程还在不在。
rem       所以只要脚本不退出的挂着，面板上就是「运行中」（status = 3），
rem       跟里面跑的是不是真的 java 完全没关系。
rem       内存占用 ≈ 1MB（一个 cmd + 一个 sleep），不加载世界、不开端口。
rem
rem 用法：
rem   1. 放到服务器上（比如 C:\fake-mc\fake-mc.bat）
rem   2. MCSM 新建实例，启动命令填：C:\fake-mc\fake-mc.bat hide
rem   3. 点开启 → 面板显示"运行中"；点停止 → 脚本收到终止信号干净退出
rem
rem 注意：实例类型选「通用/自定义」那类，别选 Minecraft。
rem       选 Minecraft 的话面板会额外发 stop 命令、尝试解析 MC 协议，
rem       对假实例来说是噪音（不影响状态判定，但控制台会有怪东西）。
rem ─────────────────────────────────────────────────────────────────────────────

set "NAME=%~1"
if "%NAME%"=="" set "NAME=FakeServer"

echo [%NAME%] 假装加载世界里…
timeout /t 1 /nobreak >nul
echo [%NAME%] Done (1.0s)! For help, type "help"
echo [%NAME%] ⚠ 这只是个假服务端：不开端口、不读磁盘、不吃内存

rem 面板点「停止」会终止本进程。我们通过检测自身 PID 是否还存在来模拟。
rem 这里用一个循环保持进程存活，同时监听控制台输入。

:loop
rem 尝试读取一行输入，超时 30 秒
set "line="
set /p "line="
if errorlevel 1 goto timeout_case

rem 有输入，处理命令
if /i "%line%"=="stop" goto stop
if /i "%line%"=="end" goto stop
if /i "%line%"=="exit" goto stop
if "%line%"=="" goto loop
echo [%NAME%] 收到命令：%line%
goto loop

:timeout_case
rem 超时或 stdin 关闭，睡 5 秒防止空转
timeout /t 5 /nobreak >nul
goto loop

:stop
echo [%NAME%] 停止
exit /b 0