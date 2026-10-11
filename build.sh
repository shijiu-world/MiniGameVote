#!/usr/bin/env bash
# 本机构建 MiniGameVote-<版本>.jar（javac + jar，离线，不联网）
#
# 版本号唯一真源是 pom.xml 的 <version>；plugin.yml 里写的是 ${project.version}，
# 这里用 sed 替换掉，所以改版本只改 pom.xml 一处。
#
# 用法：
#   ./build.sh
#   PAPI_JAR=D:/path/PlaceholderAPI-2.12.3.jar ./build.sh    # 找不到 PAPI 时手动指定
#
# 需要改本机的默认路径时，用环境变量覆盖，别直接改这个文件：
#   JDK=<javac 所在的 JAVA_HOME>  M2=<本地 maven 仓库>  PAPI_JAR=<PlaceholderAPI.jar>
set -e
cd "$(dirname "$0")"

# 默认 JDK：优先 JAVA_HOME。Windows 上没设就留空，下面会提示。
JDK="${JDK:-${JAVA_HOME:-}}"
# 本地 maven 仓库：Windows 默认 %USERPROFILE%\.m2\repository
M2="${M2:-${HOME:-$USERPROFILE}/.m2/repository}"
PAPI_JAR="${PAPI_JAR:-}"
if [ -z "$JDK" ]; then
    echo "找不到 JDK：请设置 JAVA_HOME，或用 JDK=<JAVA_HOME> ./build.sh 指定"
    exit 1
fi

# ---------------------------------------------------------------------------
# 版本号：取 <artifactId>minigamevote</artifactId> 之后的第一个 <version>
# ---------------------------------------------------------------------------
V=$(awk '/<\/artifactId>/{f=1} f&&/<version>/{gsub(/.*<version>|<\/version>.*/,"");print;exit}' pom.xml)
if [ -z "$V" ]; then
    echo "从 pom.xml 读不到版本号"
    exit 1
fi
echo "版本: $V"

# ---------------------------------------------------------------------------
# classpath
#
# ⚠ 坑：Paper 1.21+ 的服务端 jar 是 paperclip 包装，org.bukkit 不在 jar 根目录。
#     这里直接用 ~/.m2 里的 paper-api。
# ⚠ 坑 2：光有 paper-api 不够 —— Player 实现了 adventure 的接口、方法签名带
#         jetbrains 的 @Nullable，缺了会报「无法访问 xxx 的类文件」。
# ---------------------------------------------------------------------------
API_JAR=""
for f in "$M2"/io/papermc/paper/paper-api/*/paper-api-*.jar; do
    [ -f "$f" ] && API_JAR="$f" && break
done
if [ -z "$API_JAR" ]; then
    echo "找不到 paper-api（$M2/io/papermc/paper/paper-api）"
    exit 1
fi

if [ -z "$PAPI_JAR" ]; then
    for f in "$M2"/me/clip/placeholderapi/*/placeholderapi-*.jar; do
        [ -f "$f" ] && PAPI_JAR="$f" && break
    done
fi
# 上面没找到时，再去 PAPI_SEARCH 指定的目录找一遍：
#   PAPI_SEARCH="D:/xxx/plugins" ./build.sh
if [ -z "$PAPI_JAR" ] && [ -n "${PAPI_SEARCH:-}" ]; then
    for f in "$PAPI_SEARCH"/PlaceholderAPI*.jar; do
        [ -f "$f" ] && PAPI_JAR="$f" && break
    done
fi
if [ -z "$PAPI_JAR" ]; then
    echo "找不到 PlaceholderAPI 的 jar（软依赖，但编译 PlaceholderExpansion 需要它）"
    echo "指定一个： PAPI_JAR=D:/path/PlaceholderAPI-2.12.3.jar ./build.sh"
    exit 1
fi

CP="$API_JAR;$PAPI_JAR"

# ⚠ 坑 3：~/.m2 里 adventure 同时有 4.14 和 4.20 两份，两个都进 classpath 会让
#         javac 挑到旧的那份（PlainTextComponentSerializer.plain() 之类的新 API 就"消失"了）。
#         所以只取版本号最大的那份目录。
KYORI_LATEST=""
for d in "$M2"/net/kyori/*/*/; do
    d="${d%/}"
    v="${d##*/}"
    case "$v" in
        4.*)
            [[ "$v" > "$KYORI_LATEST" ]] && KYORI_LATEST="$v"
            ;;
    esac
done
if [ -n "$KYORI_LATEST" ]; then
    echo "adventure  : $KYORI_LATEST（最新那份排在最前）"
    for f in "$M2"/net/kyori/*/"$KYORI_LATEST"/*.jar; do
        [ -f "$f" ] && CP="$CP;$f"
    done
    # 剩下的（examination / option / ansi 这些非 4.x 的）补在后面，否则缺 net.kyori.examination.Examinable
    for f in "$M2"/net/kyori/*/*/*.jar; do
        case "$f" in
            */"$KYORI_LATEST"/*) continue ;;
        esac
        [ -f "$f" ] && CP="$CP;$f"
    done
else
    for f in "$M2"/net/kyori/*/*/*.jar; do
        [ -f "$f" ] && CP="$CP;$f"
    done
fi

# CommandSender 那边还有 spigot 的 sendMessage(BaseComponent)，缺了 bungeecord-chat 会报"无法访问"
for f in "$M2"/net/md-5/bungeecord-chat/*/bungeecord-chat-*.jar; do
    [ -f "$f" ] && CP="$CP;$f" && break
done
for f in "$M2"/org/jetbrains/annotations/*/annotations-*.jar; do
    [ -f "$f" ] && CP="$CP;$f" && break
done

echo "paper-api : $API_JAR"
echo "papi      : $PAPI_JAR"

rm -rf out target
mkdir -p out target

echo "编译（--release 17，兼容 1.17+ 服务端）..."
"$JDK/bin/javac" -encoding UTF-8 --release 17 \
    -cp "$CP" \
    -d out \
    $(find src/main/java -name '*.java')

# 资源：把 plugin.yml 里的 ${project.version} 换成真实版本
sed "s/\${project.version}/$V/g" src/main/resources/plugin.yml > out/plugin.yml
# 注意：config.toml 里含 ${main} 这类域名变量，这里不能做任何变量替换
cp src/main/resources/config.toml out/

"$JDK/bin/jar" cf "target/MiniGameVote-$V.jar" -C out .

echo
echo "产物：target/MiniGameVote-$V.jar"
"$JDK/bin/jar" tf "target/MiniGameVote-$V.jar"
