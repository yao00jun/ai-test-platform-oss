#!/usr/bin/env bash
# AI-Test-Platform 唯一的 Linux 脚本（Windows 用 scripts/aitest.ps1 和根目录的 *.cmd）。
#
# 用法：scripts/aitest.sh <命令> [选项]
#
#   日常使用（源码目录和发行包都可用）
#     up                 一键启动：Java → MySQL → 配置 → 程序包 → 浏览器内核 → 后端
#     down               停止后端（源码目录下同时停止项目 MySQL；--keep-mysql 保留）
#     restart            重启后端
#     status             查看 MySQL / 后端 / 模型的状态和日志位置
#     logs               实时看后端日志（--errors 看错误日志，--tail 100）
#     check              启动前体检：Java、MySQL、目录、浏览器内核、模型配置（--test-model 真实调一次模型）
#     backup             一致备份到 instance/backups（--destination-directory DIR，--leave-stopped 备份后不重启）
#     upgrade            从当前新发行包就地升级旧安装目录（--target DIR；--yes 跳过确认）
#     restore            把备份恢复到一个新的空实例：restore --backup-directory DIR --instance-directory DIR
#     install-browsers   安装 Playwright 浏览器内核（--browsers chromium,firefox,webkit；--dry-run 只预览；--with-deps 同时装系统依赖，需要 root）
#     start / stop       只启停后端进程，不碰 MySQL（up/down 内部调用；stop --force 强制结束）
#
#   开发与发布（只在源码目录可用）
#     build              完整发行构建，输出到 artifacts/releases（--skip-tests 只用于调试包）
#     verify             跑检查不打包（--full 全量集成测试，--forks 1 机械硬盘用）
#     clean              清理构建过程文件和旧发行包（--what-if 只列不删，--keep-releases 2）
#     mysql              启动/初始化本项目专用 MySQL 8.4（--data-directory DIR 首次可指定数据目录）
#     reset-test-db      重建集成测试库（verify/build 自动调用）
#     maven <参数...>    用 Java 21 执行仓库内的 Maven Wrapper，参数原样传给 Maven
#
#   通用选项：--instance-directory DIR 操作默认 instance/ 之外的实例；--offline 离线模式（缺什么直接报错，不联网下载）。
#
# 工具查找顺序：config.json 里填的路径 → 环境变量（JAVA_HOME、MYSQL_HOME、PLAYWRIGHT_BROWSERS_PATH）→ 包里自带的 .tools/ → 本机已安装的
# → 最后才联网下载到 .tools/（Java 21 约 190 MB、MySQL 8.4 约 80 MB、Chromium 约 170 MB、构建源码还需 Node.js 24 约 30 MB），
# 都来自官方源并校验 SHA-256。找到的路径会写回 instance/config.json。需要 bash、curl、tar、xz、python3。
set -euo pipefail
OFFLINE=0; case "${AI_TEST_OFFLINE-}" in ''|0|false) ;; *) OFFLINE=1 ;; esac
export LC_ALL="${LC_ALL:-C.UTF-8}" LANG="${LANG:-C.UTF-8}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLS_ROOT="$PROJECT_ROOT/.tools"
RUNTIME_ROOT="$PROJECT_ROOT/.runtime"
MYSQL_RUNTIME="$RUNTIME_ROOT/mysql"
MYSQL_CONNECTION_FILE="$MYSQL_RUNTIME/connection.json"
FRONTEND="$PROJECT_ROOT/frontend"
DEV_JAR="$PROJECT_ROOT/backend/target/ai-test-platform-1.0.0-SNAPSHOT.jar"
RELEASE_JAR="$PROJECT_ROOT/app.jar"
PLAYWRIGHT_VERSION='1.62.0'
IS_SOURCE_TREE=0; [[ -f "$PROJECT_ROOT/backend/pom.xml" ]] && IS_SOURCE_TREE=1

# 固定版本的官方下载源。第一个地址不通时依次尝试后面的；下载后校验 SHA-256。
JDK_FOLDER='jdk-21.0.12.1+1'; JDK_FILE='OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz'
JDK_SHA256='ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94'
JDK_URLS=('https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz'
          'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz')
NODE_FOLDER='node-v24.21.0-linux-x64'; NODE_FILE='node-v24.21.0-linux-x64.tar.xz'
NODE_SHA256='fd8e59d5a511510f6a298afb548f18c7d2b1be404d8b4a27d94fbe49f56cb2d6'
NODE_URLS=('https://npmmirror.com/mirrors/node/v24.21.0/node-v24.21.0-linux-x64.tar.xz' 'https://nodejs.org/dist/v24.21.0/node-v24.21.0-linux-x64.tar.xz')
MYSQL_FOLDER='mysql-8.4.10-linux-glibc2.28-x86_64-minimal'; MYSQL_FILE='mysql-8.4.10-linux-glibc2.28-x86_64-minimal.tar.xz'
MYSQL_SHA256='88ac9cc85bed076c4c8c6e816c3369a6febfbb5db13e6e174f1d8971161e0b77'
MYSQL_URLS=('https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.10-linux-glibc2.28-x86_64-minimal.tar.xz')

step() { printf '\033[36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m    %s\033[0m\n' "$*"; }
warn() { printf '\033[33m    %s\033[0m\n' "$*"; }
note() { printf '\033[90m    %s\033[0m\n' "$*"; }
die()  { printf '\033[31m错误：%s\033[0m\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "需要命令 $1，请先安装（例如 sudo apt install $2）。"; }
need python3 python3; need curl curl; need tar tar

# ---------------------------------------------------------------- JSON 辅助（python3） ----------------------------------------------------------------
# json_get FILE KEY.PATH [DEFAULT]：读取一个值；对象/数组以 JSON 输出，布尔输出 true/false。
json_get() {
python3 - "$1" "$2" "${3-}" <<'PY'
import json,sys
data=json.load(open(sys.argv[1],encoding='utf-8'))
for key in sys.argv[2].split('.'):
    if isinstance(data,dict) and key in data: data=data[key]
    else: data=None; break
if data is None: print(sys.argv[3]); sys.exit()
if isinstance(data,bool): print('true' if data else 'false')
elif isinstance(data,(dict,list)): print(json.dumps(data,ensure_ascii=False))
else: print(data)
PY
}
# json_set FILE KEY=VALUE...：写入字符串/数字值（VALUE 以 json: 开头时按 JSON 解析）。文件不存在时新建。
json_set() {
python3 - "$@" <<'PY'
import json,sys,os
path=sys.argv[1]
data=json.load(open(path,encoding='utf-8')) if os.path.exists(path) else {}
for pair in sys.argv[2:]:
    key,value=pair.split('=',1)
    if value.startswith('json:'): value=json.loads(value[5:])
    node=data; parts=key.split('.')
    for part in parts[:-1]: node=node.setdefault(part,{})
    if value is None: node.pop(parts[-1],None)
    else: node[parts[-1]]=value
json.dump(data,open(path,'w',encoding='utf-8'),ensure_ascii=False,indent=2)
PY
}
json_del() { python3 - "$1" "$2" <<'PY'
import json,sys
path=sys.argv[1]; data=json.load(open(path,encoding='utf-8')); data.pop(sys.argv[2],None)
json.dump(data,open(path,'w',encoding='utf-8'),ensure_ascii=False,indent=2)
PY
}

# ---------------------------------------------------------------- 工具下载 ----------------------------------------------------------------
sha256_of() { sha256sum "$1" | cut -d' ' -f1; }
# fetch_tool NAME FOLDER FILE SHA256 URL... → 输出解压后的目录
fetch_tool() {
    local name="$1" folder="$2" file="$3" sha="$4"; shift 4
    local target="$TOOLS_ROOT/$folder" archive="$TOOLS_ROOT/$file"
    if [[ -d "$target" ]]; then echo "$target"; return; fi
    mkdir -p "$TOOLS_ROOT"
    if [[ ! -f "$archive" || "$(sha256_of "$archive")" != "$sha" ]]; then
        [[ $OFFLINE -eq 0 ]] || die "离线模式：缺少 $name。请把 $file 放到 $TOOLS_ROOT，或在 config.json 里指定已安装的路径。"
        local url downloaded=0
        for url in "$@"; do
            warn "下载 $name（$url）" >&2
            if curl -fL --retry 2 --connect-timeout 20 -o "$archive" "$url" 2>/dev/null && [[ "$(sha256_of "$archive")" == "$sha" ]]; then downloaded=1; break; fi
            warn '这个地址下载失败或校验不通过，换下一个地址。' >&2; rm -f "$archive"
        done
        [[ $downloaded -eq 1 ]] || die "无法下载 $name。请检查网络，或手动把 $file 放到 $TOOLS_ROOT 后重试。"
    fi
    note "解压 $file 到 .tools/" >&2
    case "$file" in *.tar.xz) tar -xJf "$archive" -C "$TOOLS_ROOT" ;; *.tar.gz) tar -xzf "$archive" -C "$TOOLS_ROOT" ;; esac
    [[ -d "$target" ]] || die "解压后没有找到 $target"
    echo "$target"
}

remember_source() { mkdir -p "$RUNTIME_ROOT/.sources"; printf '%s' "$2" > "$RUNTIME_ROOT/.sources/$1"; }
recall_source() { cat "$RUNTIME_ROOT/.sources/$1" 2>/dev/null || true; }
java_major() { [[ -x "$1/bin/java" && -f "$1/release" ]] && sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$1/release" | head -1; }
# 运行程序接受 Java 21 及更高（21 优先）；打包/测试只用 21。find_java [configuredJavaHome] [exact21] → java 路径，找不到返回 1
find_java() {
    local exact="${2:-0}" candidate major
    local -a ordered=() sources=()
    [[ -n "${1-}" ]] && { ordered+=("$1"); sources+=('config.json 的 javaHome'); }
    [[ -n "${AI_TEST_JAVA_HOME-}" ]] && { ordered+=("$AI_TEST_JAVA_HOME"); sources+=('环境变量 AI_TEST_JAVA_HOME'); }
    [[ -n "${JAVA_HOME-}" ]] && { ordered+=("$JAVA_HOME"); sources+=('环境变量 JAVA_HOME'); }
    [[ -d "$TOOLS_ROOT" ]] && while IFS= read -r candidate; do ordered+=("$candidate"); sources+=('.tools 自带'); done < <(find "$TOOLS_ROOT" -maxdepth 1 -type d -name 'jdk-*' | sort -r)
    local -a system=()
    if command -v java >/dev/null 2>&1; then system+=("$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"); fi
    for candidate in /usr/lib/jvm/*/ /opt/*jdk*/ /usr/local/*jdk*/; do [[ -d "$candidate" ]] && system+=("${candidate%/}"); done
    # 本机装了多个版本时优先 21，其次更高版本。
    local -a exact21=() higher=()
    for candidate in "${system[@]}"; do
        major="$(java_major "$candidate" || true)"; [[ -n "$major" ]] || continue
        if [[ "$major" -eq 21 ]]; then exact21+=("$candidate"); elif [[ "$major" -gt 21 ]]; then higher+=("$candidate"); fi
    done
    for candidate in "${exact21[@]}" "${higher[@]}"; do ordered+=("$candidate"); sources+=('本机已安装'); done
    local i
    for i in "${!ordered[@]}"; do
        candidate="${ordered[$i]}"; major="$(java_major "$candidate" || true)"
        [[ -n "$major" && "$major" -ge 21 ]] || continue
        [[ "$exact" -eq 1 && "$major" -ne 21 ]] && continue
        [[ "$major" -eq 21 ]] || warn "使用的是 Java $major（$candidate）。程序按 Java 21 测试，更高版本一般可用；如遇异常请改用 21。" >&2
        remember_source java "${sources[$i]}：$candidate（Java $major）"
        echo "$candidate/bin/java"; return 0
    done
    return 1
}
java_or_install() {
    local java
    if java="$(find_java "${1-}" "${2:-0}")"; then echo "$java"; return; fi
    [[ "${2:-0}" -eq 1 ]] && die '打包和测试需要 Java 21（正好 21）。请安装 JDK 21，或设置 AI_TEST_JAVA_HOME。'
    [[ $OFFLINE -eq 0 ]] || die '离线模式：没有找到 Java 21 或更高版本。请安装 JDK 21，或在 config.json 的 javaHome 里填写已安装的 JDK 目录。'
    warn 'Java 21 未安装，自动下载 Eclipse Temurin JDK 21（约 190 MB，一次性）。' >&2
    local home; home="$(fetch_tool 'Java 21' "$JDK_FOLDER" "$JDK_FILE" "$JDK_SHA256" "${JDK_URLS[@]}")"
    remember_source java ".tools 自带：$home（Java 21）"
    echo "$home/bin/java"
}
node_directory() {
    if command -v node >/dev/null 2>&1; then
        local major; major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
        [[ "$major" -ge 24 ]] && { dirname "$(readlink -f "$(command -v node)")"; return; }
    fi
    [[ -n "${AI_TEST_NODE_HOME-}" && -x "$AI_TEST_NODE_HOME/bin/node" ]] && { echo "$AI_TEST_NODE_HOME/bin"; return; }
    local local_node="$TOOLS_ROOT/$NODE_FOLDER"
    if [[ ! -x "$local_node/bin/node" ]]; then
        [[ $OFFLINE -eq 0 ]] || die '离线模式：没有找到 Node.js 24（打包源码需要）。请安装 Node.js 24，或设置环境变量 AI_TEST_NODE_HOME。'
        warn 'Node.js 24 未安装，自动下载到 .tools/（约 30 MB，一次性）。' >&2; local_node="$(fetch_tool 'Node.js 24' "$NODE_FOLDER" "$NODE_FILE" "$NODE_SHA256" "${NODE_URLS[@]}")"
    fi
    echo "$local_node/bin"
}
# 前端命令统一经 corepack 执行，自动使用 frontend/package.json 里钉住的 pnpm 版本。
pnpm() {
    local node_dir; node_dir="$(node_directory)"
    # corepack 按当前目录的 package.json 选 pnpm 版本，所以必须先进入 frontend/。
    ( cd "$FRONTEND" && PATH="$node_dir:$PATH" COREPACK_ENABLE_DOWNLOAD_PROMPT=0 COREPACK_HOME="$TOOLS_ROOT/corepack" "$node_dir/corepack" pnpm "$@" ) || die "前端命令失败：pnpm $*"
}
maven() {
    [[ $IS_SOURCE_TREE -eq 1 ]] || die 'maven 只能在源码目录使用。'
    local java; java="$(java_or_install '' 1)"
    local java_home; java_home="$(dirname "$(dirname "$java")")"
    [[ -x "$PROJECT_ROOT/backend/mvnw" ]] || chmod +x "$PROJECT_ROOT/backend/mvnw"
    JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Duser.language=en -Duser.country=US' \
        "$PROJECT_ROOT/backend/mvnw" -f "$PROJECT_ROOT/backend/pom.xml" "$@"
}

# ---------------------------------------------------------------- 项目 MySQL ----------------------------------------------------------------
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
mysql_env() {   # 让 .tools 里的 MySQL 找到 libaio；系统装了 libaio 时无副作用
    [[ -d "$TOOLS_ROOT/lib" ]] && export LD_LIBRARY_PATH="$TOOLS_ROOT/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; return 0
}
ensure_libaio() {   # ensure_libaio MYSQLD：mysqld 缺 libaio.so.1 时尽量不靠 root 补上
    mysql_env
    ldd "$1" 2>/dev/null | grep -q 'libaio.so.1 => not found' || return 0
    mkdir -p "$TOOLS_ROOT/lib"
    local system_lib; system_lib="$(ls /usr/lib/x86_64-linux-gnu/libaio.so.1t64* /usr/lib64/libaio.so.1* 2>/dev/null | head -1 || true)"
    if [[ -n "$system_lib" ]]; then ln -sf "$system_lib" "$TOOLS_ROOT/lib/libaio.so.1"
    elif command -v apt-get >/dev/null 2>&1; then
        warn 'MySQL 需要 libaio，正在下载 Ubuntu/Debian 的 libaio 包到 .tools/（不需要 root）。'
        ( cd "$TOOLS_ROOT/lib" && (apt-get download libaio1t64 >/dev/null 2>&1 || apt-get download libaio1 >/dev/null 2>&1) && \
          for deb in ./*.deb; do dpkg -x "$deb" ./extract; done && so="$(find ./extract -name 'libaio.so.1*' -type f | head -1)" && ln -sf "$(readlink -f "$so")" ./libaio.so.1 ) \
          || die '自动补齐 libaio 失败。请执行 sudo apt install libaio1t64（或 libaio1）后重试。'
    else
        die 'MySQL 需要 libaio 库。请安装它后重试（Ubuntu/Debian：sudo apt install libaio1t64；RHEL/CentOS：sudo dnf install libaio）。'
    fi
    mysql_env
    ldd "$1" | grep -q 'not found' && die "MySQL 还缺少系统库：$(ldd "$1" | grep 'not found' | tr '\n' ' ')"
    return 0
}
cmd_mysql() {
    local port=3307 mysql_home='' data_directory=''
    while [[ $# -gt 0 ]]; do case "$1" in
        --port) port="$2"; shift 2 ;; --mysql-home) mysql_home="$2"; shift 2 ;; --data-directory) data_directory="$2"; shift 2 ;;
        *) die "mysql 不认识的选项：$1" ;; esac; done
    mkdir -p "$MYSQL_RUNTIME" "$TOOLS_ROOT"
    local username='' password=''
    if [[ -f "$MYSQL_CONNECTION_FILE" ]]; then
        local pid; pid="$(json_get "$MYSQL_CONNECTION_FILE" pid '')"
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && grep -q mysqld "/proc/$pid/comm" 2>/dev/null; then echo "项目 MySQL 已在运行：127.0.0.1:$(json_get "$MYSQL_CONNECTION_FILE" port)"; return; fi
        mysql_home="$(json_get "$MYSQL_CONNECTION_FILE" home)"; port="$(json_get "$MYSQL_CONNECTION_FILE" port)"
        username="$(json_get "$MYSQL_CONNECTION_FILE" username)"; password="$(json_get "$MYSQL_CONNECTION_FILE" password)"
        [[ -n "$data_directory" ]] || data_directory="$(json_get "$MYSQL_CONNECTION_FILE" dataDirectory '')"
    fi
    local source=''
    [[ -n "$mysql_home" ]] && source='指定路径'
    [[ -n "$mysql_home" || ! -x "$TOOLS_ROOT/$MYSQL_FOLDER/bin/mysqld" ]] || { mysql_home="$TOOLS_ROOT/$MYSQL_FOLDER"; source='.tools 自带'; }
    [[ -n "$mysql_home" || ! -f "$TOOLS_ROOT/$MYSQL_FILE" ]] || { mysql_home="$(fetch_tool 'MySQL 8.4' "$MYSQL_FOLDER" "$MYSQL_FILE" "$MYSQL_SHA256" "${MYSQL_URLS[@]}")"; source='.tools 自带'; }
    if [[ -z "$mysql_home" ]]; then   # 本机已安装的 MySQL 8.4：MYSQL_HOME → PATH 上的 mysqld → 常见位置。只借程序文件，数据目录仍在本项目里。
        local candidate
        for candidate in "${MYSQL_HOME-}" "$(command -v mysqld >/dev/null 2>&1 && dirname "$(dirname "$(readlink -f "$(command -v mysqld)")")")" /usr/local/mysql /opt/mysql /usr; do
            [[ -n "$candidate" ]] || continue
            local bin="$candidate/bin/mysqld"; [[ -x "$bin" ]] || bin="$candidate/sbin/mysqld"; [[ -x "$bin" ]] || continue
            if "$bin" --version 2>/dev/null | grep -q ' 8\.4\.'; then mysql_home="$candidate"; source="本机已安装"; break; fi
        done
    fi
    if [[ -z "$mysql_home" ]]; then
        [[ $OFFLINE -eq 0 ]] || die '离线模式：本机没有 MySQL 8.4。请把 mysql-8.4.x-linux-glibc2.28-x86_64-minimal.tar.xz 放到 .tools/，或安装 MySQL 8.4 后设置环境变量 MYSQL_HOME。'
        warn '本机没有 MySQL 8.4，自动下载官方精简包（约 80 MB，一次性）。'
        mysql_home="$(fetch_tool 'MySQL 8.4' "$MYSQL_FOLDER" "$MYSQL_FILE" "$MYSQL_SHA256" "${MYSQL_URLS[@]}")"; source='.tools 自带'
    fi
    remember_source mysql "${source}：$mysql_home"
    local mysqld="$mysql_home/bin/mysqld" mysql="$mysql_home/bin/mysql"
    [[ -x "$mysqld" ]] || mysqld="$mysql_home/sbin/mysqld"
    [[ -x "$mysqld" ]] || die "找不到 $mysql_home 下的 mysqld"
    ensure_libaio "$mysqld"
    local version; version="$("$mysqld" --version)"
    [[ "$version" == *8.4.* ]] || die "本项目需要 MySQL 8.4 LTS，找到的是：$version"
    [[ -n "$data_directory" ]] || data_directory="$MYSQL_RUNTIME/data"
    mkdir -p "$data_directory"
    cat > "$MYSQL_RUNTIME/my.cnf" <<EOF
[mysqld]
basedir=$mysql_home
datadir=$data_directory
port=$port
bind-address=127.0.0.1
mysqlx=OFF
skip-log-bin
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone=+00:00
max-connections=100
innodb_buffer_pool_size=1G
innodb_flush_log_at_trx_commit=2
socket=$MYSQL_RUNTIME/mysql.sock
pid-file=$MYSQL_RUNTIME/mysqld.pid
log-error=$MYSQL_RUNTIME/mysql.log
EOF
    local first_start=0; [[ -d "$data_directory/mysql" ]] || first_start=1
    if [[ $first_start -eq 1 ]]; then
        note '首次初始化数据目录……'
        "$mysqld" --defaults-file="$MYSQL_RUNTIME/my.cnf" --initialize-insecure || die "MySQL 初始化失败，详情见 $MYSQL_RUNTIME/mysql.log"
    fi
    nohup "$mysqld" --defaults-file="$MYSQL_RUNTIME/my.cnf" >/dev/null 2>&1 &
    local pid=$! attempt
    for attempt in $(seq 1 120); do
        kill -0 "$pid" 2>/dev/null || die "MySQL 启动后立即退出，详情见 $MYSQL_RUNTIME/mysql.log（常见原因：端口 $port 被占用，或数据目录被另一个 MySQL 使用）。"
        port_open "$port" && break
        sleep 0.5
    done
    port_open "$port" || die "MySQL 在 60 秒内没有就绪，详情见 $MYSQL_RUNTIME/mysql.log"
    if [[ $first_start -eq 1 ]]; then
        local root_password app_password
        root_password="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"; app_password="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
        "$mysql" --host=127.0.0.1 --port="$port" --user=root --default-character-set=utf8mb4 <<EOF || die '创建平台数据库和账号失败。'
ALTER USER 'root'@'localhost' IDENTIFIED BY '$root_password';
CREATE DATABASE ai_test_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE ai_test_platform_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE ai_test_business_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'aitest'@'localhost' IDENTIFIED BY '$app_password';
GRANT ALL ON ai_test_platform.* TO 'aitest'@'localhost';
GRANT ALL ON ai_test_platform_test.* TO 'aitest'@'localhost';
GRANT ALL ON ai_test_business_test.* TO 'aitest'@'localhost';
EOF
        printf '[client]\nuser=root\npassword=%s\nhost=127.0.0.1\nport=%s\n' "$root_password" "$port" > "$MYSQL_RUNTIME/admin.cnf"; chmod 600 "$MYSQL_RUNTIME/admin.cnf"
        username='aitest'; password="$app_password"
    fi
    rm -f "$MYSQL_CONNECTION_FILE"
    json_set "$MYSQL_CONNECTION_FILE" "home=$mysql_home" "port=json:$port" "username=$username" "password=$password" "version=$version" "pid=json:$pid" "dataDirectory=$data_directory"
    chmod 600 "$MYSQL_CONNECTION_FILE"
    echo "项目 MySQL 8.4 已就绪：127.0.0.1:$port（账号密码在 .runtime/mysql/connection.json，已被 Git 忽略）"
}
project_mysql_pid() {   # 输出正在运行的项目 mysqld PID，否则空
    [[ -f "$MYSQL_CONNECTION_FILE" ]] || return 0
    local pid; pid="$(json_get "$MYSQL_CONNECTION_FILE" pid '')"
    [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && grep -q mysqld "/proc/$pid/comm" 2>/dev/null && echo "$pid"; return 0
}
stop_project_mysql() {
    local pid; pid="$(project_mysql_pid)"
    [[ -n "$pid" ]] || { ok '未运行'; return; }
    mysql_env
    local home; home="$(json_get "$MYSQL_CONNECTION_FILE" home)"
    if [[ -x "$home/bin/mysqladmin" && -f "$MYSQL_RUNTIME/admin.cnf" ]]; then "$home/bin/mysqladmin" --defaults-file="$MYSQL_RUNTIME/admin.cnf" shutdown 2>/dev/null || true; fi
    local attempt; for attempt in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    if kill -0 "$pid" 2>/dev/null; then warn '优雅关闭未成功，直接结束 mysqld 进程。'; kill "$pid" 2>/dev/null || true; sleep 2; fi
    ok "已停止 MySQL（PID $pid）"
}
cmd_reset_test_db() {
    local forks=3 quiet=0
    while [[ $# -gt 0 ]]; do case "$1" in --forks) forks="$2"; shift 2 ;; --quiet) quiet=1; shift ;; *) die "reset-test-db 不认识的选项：$1" ;; esac; done
    if [[ -n "${AI_TEST_INTEGRATION_DB_URL-}" ]]; then [[ $quiet -eq 1 ]] || echo '已设置 AI_TEST_INTEGRATION_DB_URL，不动外部测试库。'; return; fi
    [[ -f "$MYSQL_CONNECTION_FILE" && -f "$MYSQL_RUNTIME/admin.cnf" ]] || die '项目 MySQL 尚未初始化，先运行 scripts/aitest.sh mysql（或 up）。'
    local username mysql; username="$(json_get "$MYSQL_CONNECTION_FILE" username)"; mysql="$(json_get "$MYSQL_CONNECTION_FILE" home)/bin/mysql"
    [[ "$username" =~ ^[A-Za-z0-9_]+$ ]] || die '测试账号名不符合预期。'
    pgrep -x java >/dev/null 2>&1 && die '有 Java 进程正在运行。请先结束其他构建/测试，或执行 scripts/aitest.sh down，再重建测试库。'
    [[ "$forks" -ge 1 && "$forks" -le 16 ]] || die 'forks 必须在 1 到 16 之间。'
    mysql_env
    local sql='' fork suffix schema
    for fork in $(seq 1 "$forks"); do
        suffix=''; [[ $fork -gt 1 ]] && suffix="_$fork"
        for schema in "ai_test_platform_test$suffix" "ai_test_business_test$suffix"; do
            sql+="DROP DATABASE IF EXISTS $schema; CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL ON $schema.* TO '$username'@'localhost';"$'\n'
        done
    done
    sql+="SELECT CONCAT('DROP DATABASE \`', schema_name, '\`;') FROM information_schema.schemata WHERE schema_name LIKE 'ai_test_acceptance_%' OR (schema_name REGEXP '^ai_test_(platform|business)_test_[0-9]+$' AND CAST(SUBSTRING_INDEX(schema_name, '_', -1) AS UNSIGNED) > $forks);"
    local stale; stale="$(printf '%s' "$sql" | "$mysql" --defaults-file="$MYSQL_RUNTIME/admin.cnf" --default-character-set=utf8mb4 --connect-timeout=10 --batch --skip-column-names)" || die '重建测试库失败。'
    local count=0
    if [[ -n "$stale" ]]; then printf '%s\n' "$stale" | "$mysql" --defaults-file="$MYSQL_RUNTIME/admin.cnf" --batch || die '删除残留测试库失败。'; count="$(printf '%s\n' "$stale" | wc -l)"; fi
    [[ $quiet -eq 1 ]] || echo "已重建 $forks 组集成测试库$( [[ $count -gt 0 ]] && echo "，并删除 $count 个残留库" )。"
}

# ---------------------------------------------------------------- 实例配置 ----------------------------------------------------------------
# read_config INSTANCE_DIR [CONFIG_PATH]：校验 config.json 并导出 CFG_* 变量（与 aitest.ps1 的 Read-AiTestConfiguration 规则一致）。
read_config() {
    local instance config_path
    instance="$(cd "$1" 2>/dev/null && pwd || echo "$1")"
    config_path="${2:-$instance/config.json}"
    [[ -f "$config_path" ]] || die "找不到配置文件：$config_path。请复制 config.example.json 并填写数据库连接。"
    local exported
    exported="$(python3 - "$instance" "$config_path" <<'PY'
import json,sys,os,re,ipaddress,shlex
from urllib.parse import urlsplit,unquote
instance,config_path=sys.argv[1],sys.argv[2]
def fail(message): print('__ERROR__ '+message); sys.exit(0)
try: config=json.load(open(config_path,encoding='utf-8'))
except Exception as error: fail(f'配置文件不是合法 JSON：{error}')
if not isinstance(config,dict) or not isinstance(config.get('database'),dict): fail('配置文件必须包含 database 对象。')
try: port=int(str(config.get('port')))
except Exception: port=0
if not 1<=port<=65535: fail('port 必须是 1 到 65535 之间的整数。')
bind=str(config.get('bind') or '127.0.0.1')
try: address=ipaddress.ip_address(bind)
except ValueError: fail('bind 必须是 IP 地址，例如 127.0.0.1。')
if not (address.is_loopback or bind in ('0.0.0.0','::')): fail('bind 只能是本机回环地址或 0.0.0.0 / ::，这样本机的停止通道才可用。')
security=config.get('security',{})
if not isinstance(security,dict): fail('security 必须是一个对象。')
for flag in ('enabled','secureCookie'):
    security.setdefault(flag,False)
    if not isinstance(security[flag],bool): fail(f'security.{flag} 必须是 JSON 布尔值 true/false。')
for credential in ('username','password'):
    security.setdefault(credential,'')
    if not isinstance(security[credential],str): fail(f'security.{credential} 必须是字符串。')
try: minutes=int(str(security.get('sessionMinutes',30)))
except Exception: minutes=0
if not 1<=minutes<=1440: fail('security.sessionMinutes 必须是 1 到 1440 之间的整数。')
if security['enabled']:
    if not re.fullmatch(r'[^\W_]{0}[\w.@-]{1,64}',security['username']) or not re.fullmatch(r'[\w.@-]{1,64}',security['username']): fail('登录账号为 1 到 64 个字母、数字或 _.@- 字符。')
    pw=security['password']
    if not pw.strip() or len(pw)<12 or len(pw.encode('utf-8'))>72: fail('登录密码至少 12 个字符，且不超过 72 个 UTF-8 字节。')
elif not address.is_loopback: fail('监听非本机地址前必须先启用 security.enabled 并设置账号密码。')
url=str(config['database'].get('url',''))
if not url.startswith('jdbc:mysql://') or re.search(r'(?i)[?&](password|user|username|socketFactory|autoDeserialize)=',url): fail('database.url 必须是 MySQL JDBC 地址，账号密码写在单独的字段里。')
parts=urlsplit(url[5:])
name=unquote(parts.path.lstrip('/'))
if parts.username or parts.password or not re.fullmatch(r'[A-Za-z0-9_]{1,64}',name) or not parts.hostname: fail('database.url 必须指定一个库名，且不能内嵌账号密码。')
username=config['database'].get('username')
if not username or re.search(r'[\r\n\0]',str(username)): fail('需要填写 database.username。')
if config['database'].get('password') is None: fail('需要填写 database.password（可以为空字符串）。')
runtime=config.get('runtime') if isinstance(config.get('runtime'),dict) else {}
for key,default,low,high in (('heapMiB',1024,256,32768),('concurrency',8,1,64),('browserWorkers',2,1,8),('businessConnections',32,1,256)):
    try: value=int(str(runtime.get(key,default)))
    except Exception: value=-1
    if not low<=value<=high: fail(f'runtime.{key} 超出允许范围。')
    runtime[key]=value
paths=config.get('paths') if isinstance(config.get('paths'),dict) else {}
model=config.get('model') if isinstance(config.get('model'),dict) else {}
def resolve(p): return os.path.abspath(p if os.path.isabs(p) else os.path.join(instance,p))
storage=resolve(paths.get('storage') or 'data'); browsers=resolve(paths.get('browsers') or 'browsers')
roots=';'.join(resolve(r) for r in (paths.get('localFileRoots') or []) if r)
out={'CFG_INSTANCE':instance,'CFG_CONFIG_PATH':os.path.abspath(config_path),'CFG_PORT':port,'CFG_BIND':bind,'CFG_DB_URL':url,'CFG_DB_NAME':name,
 'CFG_DB_HOST':parts.hostname,'CFG_DB_PORT':parts.port or 3306,'CFG_DB_USER':username,'CFG_DB_PASSWORD':config['database']['password'],
 'CFG_STORAGE':storage,'CFG_BROWSERS':browsers,'CFG_FILE_ROOTS':roots,'CFG_JAVA_HOME':config.get('javaHome') or '','CFG_MYSQL_HOME':config.get('mysqlHome') or '',
 'CFG_MYSQL_SSL_MODE':config.get('mysqlSslMode') or '','CFG_MYSQL_SSL_CA':resolve(config['mysqlSslCa']) if config.get('mysqlSslCa') else '',
 'CFG_HEAP':runtime['heapMiB'],'CFG_CONCURRENCY':runtime['concurrency'],'CFG_BROWSER_WORKERS':runtime['browserWorkers'],'CFG_BUSINESS_CONNECTIONS':runtime['businessConnections'],
 'CFG_MODEL_BASE_URL':model.get('baseUrl') or '','CFG_MODEL_API_KEY':model.get('apiKey') or '','CFG_MODEL_NAME':model.get('modelName') or '',
 'CFG_AUTH_ENABLED':'true' if security['enabled'] else 'false','CFG_AUTH_USERNAME':security['username'],'CFG_AUTH_PASSWORD':security['password'],
 'CFG_SESSION_MINUTES':minutes,'CFG_SECURE_COOKIE':'true' if security['secureCookie'] else 'false',
 'CFG_RUN_DIR':os.path.join(instance,'run'),'CFG_LOG_DIR':os.path.join(instance,'logs')}
for key,value in out.items(): print(f'{key}={shlex.quote(str(value))}')
PY
)"
    [[ "$exported" == __ERROR__* ]] && die "${exported#__ERROR__ }"
    eval "$exported"
}
init_config() {   # init_config INSTANCE_DIR：没有 config.json 时从示例生成，并接上项目 MySQL
    local config_file="$1/config.json" template="$PROJECT_ROOT/deploy/config.example.json"
    [[ -f "$config_file" ]] && return 0
    [[ -f "$template" ]] || template="$PROJECT_ROOT/config.example.json"
    mkdir -p "$1"; cp "$template" "$config_file"
    if [[ -f "$MYSQL_CONNECTION_FILE" ]]; then
        json_set "$config_file" "database.url=jdbc:mysql://127.0.0.1:$(json_get "$MYSQL_CONNECTION_FILE" port)/ai_test_platform?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8" \
            "database.username=$(json_get "$MYSQL_CONNECTION_FILE" username)" "database.password=$(json_get "$MYSQL_CONNECTION_FILE" password)" \
            "mysqlHome=$(json_get "$MYSQL_CONNECTION_FILE" home)" "paths.storage=$PROJECT_ROOT/data" "paths.browsers=$TOOLS_ROOT/playwright-$PLAYWRIGHT_VERSION"
    fi
    chmod 600 "$config_file"
    echo "已生成实例配置：$config_file"
}
resolve_instance() { if [[ -n "${1-}" ]]; then mkdir -p "$1"; cd "$1" && pwd; else echo "$PROJECT_ROOT/instance"; fi; }
resolve_jar() {
    local jar="${1-}"
    [[ -n "$jar" ]] || { if [[ -f "$RELEASE_JAR" ]]; then jar="$RELEASE_JAR"; else jar="$DEV_JAR"; fi; }
    [[ -f "$jar" ]] || die "找不到程序包 $jar。源码目录请运行 scripts/aitest.sh up 自动打包，或 scripts/aitest.sh build。"
    echo "$jar"
}

# ---------------------------------------------------------------- 进程身份与状态 ----------------------------------------------------------------
proc_start_ticks() { awk '{print $22}' "/proc/$1/stat" 2>/dev/null || true; }   # 进程启动时刻（内核时钟计数），PID 复用时会不同
managed_pid() {   # managed_pid RUN_DIR → 仍在运行且身份一致的后端 PID，否则空
    local state="$1/state.json"; [[ -f "$state" ]] || return 0
    local pid ticks; pid="$(json_get "$state" pid '')"; ticks="$(json_get "$state" startedAt '')"
    [[ -n "$pid" && -n "$ticks" ]] && kill -0 "$pid" 2>/dev/null && [[ "$(proc_start_ticks "$pid")" == "$ticks" ]] && grep -q '^java$' "/proc/$pid/comm" 2>/dev/null && echo "$pid"; return 0
}
health_up() { [[ "$(curl -s -m 2 "$1/actuator/health" 2>/dev/null)" == *'"status":"UP"'* ]]; }

# ---------------------------------------------------------------- MySQL 客户端（备份/检查/恢复） ----------------------------------------------------------------
mysql_tool() {   # mysql_tool mysql|mysqldump [--input FILE] [--output FILE] ARGS... ：用实例配置里的库连接执行
    local tool="$1" input='' output=''; shift
    while [[ "${1-}" == --input || "${1-}" == --output ]]; do if [[ "$1" == --input ]]; then input="$2"; else output="$2"; fi; shift 2; done
    local executable
    if [[ -n "$CFG_MYSQL_HOME" ]]; then executable="$CFG_MYSQL_HOME/bin/$tool"; else executable="$(command -v "$tool" || true)"; fi
    [[ -x "$executable" ]] || die '请在 config.json 的 mysqlHome 里填写 MySQL 8.4 的安装目录，或安装 mysql 客户端。'
    mysql_env; mkdir -p "$CFG_RUN_DIR" "$CFG_LOG_DIR"
    local client_file="$CFG_RUN_DIR/mysql-$$.cnf" error_file
    error_file="$CFG_LOG_DIR/mysql-$(date -u +%Y%m%d-%H%M%S)-$$.log"
    { printf '[client]\nhost="%s"\nport=%s\nuser="%s"\npassword="%s"\ndefault-character-set=utf8mb4\n' "$CFG_DB_HOST" "$CFG_DB_PORT" "${CFG_DB_USER//\"/\\\"}" "${CFG_DB_PASSWORD//\"/\\\"}"
      [[ -n "$CFG_MYSQL_SSL_MODE" ]] && printf 'ssl-mode=%s\n' "$CFG_MYSQL_SSL_MODE"
      [[ -n "$CFG_MYSQL_SSL_CA" ]] && printf 'ssl-ca="%s"\n' "$CFG_MYSQL_SSL_CA"; } > "$client_file"
    chmod 600 "$client_file"
    local status=0
    # 没有 --input 时沿用调用方的标准输入（sql 函数经管道送 SQL 进来）。
    if [[ -n "$input" ]]; then exec 3< "$input"; else exec 3<&0; fi
    if [[ -n "$output" ]]; then
        [[ -e "$output" ]] && { rm -f "$client_file"; exec 3<&-; die '输出文件已存在，没有覆盖。'; }
        env -u MYSQL_PWD "$executable" --defaults-file="$client_file" --no-login-paths "$@" <&3 > "$output" 2>"$error_file" || status=$?
    else
        env -u MYSQL_PWD "$executable" --defaults-file="$client_file" --no-login-paths "$@" <&3 2>"$error_file" || status=$?
    fi
    exec 3<&-
    rm -f "$client_file"
    [[ $status -eq 0 ]] || die "MySQL 命令失败，详情见 $error_file"
    [[ -s "$error_file" ]] || rm -f "$error_file"
}
sql() { printf '%s' "$1" | mysql_tool mysql --batch --skip-column-names --raw --binary-mode "$CFG_DB_NAME"; }   # sql 'SELECT ...' → 结果文本
master_key_hash() { if [[ -n "${AI_TEST_MASTER_KEY-}" ]]; then printf '%s' "$AI_TEST_MASTER_KEY" | base64 -d | sha256sum; else sha256sum "$CFG_STORAGE/.master-key"; fi | cut -d' ' -f1 | tr 'a-f' 'A-F'; }

# ---------------------------------------------------------------- start / stop / check / install-browsers ----------------------------------------------------------------
cmd_start() {
    local instance='' config_path='' jar='' timeout=240
    while [[ $# -gt 0 ]]; do case "$1" in
        --instance-directory) instance="$2"; shift 2 ;; --config-path) config_path="$2"; shift 2 ;; --jar-path) jar="$2"; shift 2 ;; --startup-timeout-seconds) timeout="$2"; shift 2 ;;
        *) die "start 不认识的选项：$1" ;; esac; done
    instance="$(resolve_instance "$instance")"
    [[ -n "$config_path" ]] || init_config "$instance"
    read_config "$instance" "$config_path"
    [[ -f "$CFG_RUN_DIR/restore-incomplete.json" ]] && die '上一次恢复没有完成。请查看 run/restore-incomplete.json，并恢复到一个新的空实例后再启动。'
    local existing; existing="$(managed_pid "$CFG_RUN_DIR")"
    [[ -n "$existing" ]] && { echo "实例已在运行（PID $existing）：$(json_get "$CFG_RUN_DIR/state.json" baseUrl)"; return; }
    local previous_hash=''
    [[ -f "$CFG_RUN_DIR/state.json" ]] && previous_hash="$(json_get "$CFG_RUN_DIR/state.json" masterKeyHash '')"
    jar="$(resolve_jar "$jar")"
    local java; java="$(java_or_install "$CFG_JAVA_HOME")"
    mkdir -p "$CFG_RUN_DIR" "$CFG_LOG_DIR" "$CFG_STORAGE"
    port_open "$CFG_PORT" && die "端口 $CFG_PORT 已被其他程序占用，没有结束任何进程。请改 config.json 里的 port，或先关掉占用端口的程序。"
    local token; token="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    local probe_host="$CFG_BIND"; [[ "$probe_host" == 0.0.0.0 ]] && probe_host=127.0.0.1; [[ "$probe_host" == '::' ]] && probe_host='::1'; [[ "$probe_host" == *:* ]] && probe_host="[$probe_host]"
    local base_url="http://$probe_host:$CFG_PORT"
    local identity; identity="$(date -u +%Y%m%d-%H%M%S)-$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    local stdout="$CFG_LOG_DIR/application-$identity.log" stderr="$CFG_LOG_DIR/application-$identity.err.log"
    # 口令、模型 key 和主密钥只经环境变量传给子进程，不出现在命令行；父进程的 Spring/JVM 变量不能覆盖实例配置。
    local -a scrub=(); local name
    while IFS= read -r name; do scrub+=("-u" "$name"); done < <(env | cut -d= -f1 | grep -E '^(AI_TEST|SPRING|SERVER|AITEST|MANAGEMENT|LOGGING)_|^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)$' || true)
    local -a child=(AI_TEST_BIND="$CFG_BIND" AI_TEST_PORT="$CFG_PORT" AI_TEST_DB_URL="$CFG_DB_URL" AI_TEST_DB_USER="$CFG_DB_USER" AI_TEST_DB_PASSWORD="$CFG_DB_PASSWORD"
        AI_TEST_STORAGE="$CFG_STORAGE" AI_TEST_BROWSER_PATH="$CFG_BROWSERS" PLAYWRIGHT_BROWSERS_PATH="$CFG_BROWSERS" AI_TEST_CONCURRENCY="$CFG_CONCURRENCY" AI_TEST_BROWSER_WORKERS="$CFG_BROWSER_WORKERS"
        AI_TEST_MODEL_BASE_URL="$CFG_MODEL_BASE_URL" AI_TEST_MODEL_API_KEY="$CFG_MODEL_API_KEY" AI_TEST_MODEL_NAME="$CFG_MODEL_NAME" AI_TEST_AUTH_ENABLED="$CFG_AUTH_ENABLED"
        AI_TEST_AUTH_USERNAME="$CFG_AUTH_USERNAME" AI_TEST_AUTH_PASSWORD="$CFG_AUTH_PASSWORD" AI_TEST_SESSION_TIMEOUT="${CFG_SESSION_MINUTES}m" AI_TEST_SECURE_COOKIE="$CFG_SECURE_COOKIE"
        AI_TEST_FILE_ROOTS="$CFG_FILE_ROOTS" AI_TEST_SHUTDOWN_TOKEN="$token")
    [[ -n "${AI_TEST_MASTER_KEY-}" ]] && child+=(AI_TEST_MASTER_KEY="$AI_TEST_MASTER_KEY")
    ( cd "$PROJECT_ROOT" && exec env "${scrub[@]}" "${child[@]}" nohup "$java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Xms128m "-Xmx${CFG_HEAP}m" -jar "$jar" \
        "--aitest.execution.business-connections=$CFG_BUSINESS_CONNECTIONS" > "$stdout" 2> "$stderr" ) &
    local pid=$!
    sleep 0.3
    # nohup 经 exec 直接替换子 shell，所以 $! 就是 java 的 PID。
    local ticks; ticks="$(proc_start_ticks "$pid")"
    rm -f "$CFG_RUN_DIR/state.json"
    json_set "$CFG_RUN_DIR/state.json" "pid=json:$pid" "startedAt=$ticks" "baseUrl=$base_url" "token=$token" "jar=$jar" "configPath=$CFG_CONFIG_PATH" "stdout=$stdout" "stderr=$stderr" "status=STARTING"
    chmod 600 "$CFG_RUN_DIR/state.json"
    local deadline=$(( $(date +%s) + timeout )) ready=0
    while [[ $(date +%s) -lt $deadline ]]; do
        kill -0 "$pid" 2>/dev/null || die "程序启动后退出了。请查看日志：$stdout 和 $stderr"
        health_up "$base_url" && { ready=1; break; }
        sleep 0.5
    done
    [[ $ready -eq 1 ]] || { kill "$pid" 2>/dev/null || true; die "启动超时（$timeout 秒）。请查看日志：$stdout"; }
    local current_hash; current_hash="$(master_key_hash)"
    if [[ -n "$previous_hash" && "$previous_hash" != "$current_hash" ]]; then
        kill "$pid" 2>/dev/null || true
        die '当前主密钥与上次运行的实例不一致。请从备份恢复 .master-key，或设置与原密钥一致的 AI_TEST_MASTER_KEY；没有覆盖旧的密钥指纹。'
    fi
    json_set "$CFG_RUN_DIR/state.json" "masterKeyHash=$current_hash" "status=RUNNING"
    echo "AI-Test-Platform 已就绪：$base_url（PID $pid）"
    echo "日志：$stdout"
}
cmd_stop() {
    local instance='' config_path='' force=0 timeout=90
    while [[ $# -gt 0 ]]; do case "$1" in
        --instance-directory) instance="$2"; shift 2 ;; --config-path) config_path="$2"; shift 2 ;; --force) force=1; shift ;; --timeout-seconds) timeout="$2"; shift 2 ;;
        *) die "stop 不认识的选项：$1" ;; esac; done
    read_config "$(resolve_instance "$instance")" "$config_path"
    local pid; pid="$(managed_pid "$CFG_RUN_DIR")"
    [[ -n "$pid" ]] || { echo '这个实例没有正在运行的后端。'; return; }
    if [[ $force -eq 1 ]]; then kill -9 "$pid" 2>/dev/null || true
    else
        curl -s -m 10 -o /dev/null -f -X POST -H "X-AITest-Shutdown-Token: $(json_get "$CFG_RUN_DIR/state.json" token)" "$(json_get "$CFG_RUN_DIR/state.json" baseUrl)/internal/lifecycle/stop" \
            || die '优雅停止请求失败。请查看实例日志；确认是本实例的进程后可加 --force 强制结束。'
    fi
    local attempt; for attempt in $(seq 1 $((timeout * 2))); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done; : "$attempt"
    kill -0 "$pid" 2>/dev/null && die '进程还没有退出。请查看日志，或对同一实例加 --force 再停一次。'
    json_set "$CFG_RUN_DIR/state.json" "status=STOPPED" "stoppedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; json_del "$CFG_RUN_DIR/state.json" token
    echo "已停止后端（PID $pid）。"
}
cmd_check() {
    local instance='' config_path='' test_model=0
    while [[ $# -gt 0 ]]; do case "$1" in
        --instance-directory) instance="$2"; shift 2 ;; --config-path) config_path="$2"; shift 2 ;; --test-model) test_model=1; shift ;; *) die "check 不认识的选项：$1" ;; esac; done
    read_config "$(resolve_instance "$instance")" "$config_path"
    local java; java="$(find_java "$CFG_JAVA_HOME")" || die '没有找到 Java 21 或更高版本。运行 scripts/aitest.sh up 会自动下载，或在 config.json 的 javaHome 里填写 JDK 目录。'
    echo "Java：$(recall_source java)"
    local version; version="$(sql 'SELECT VERSION();')"
    [[ "$version" == 8.4.* ]] || die "需要 MySQL 8.4，实际连到的是 $version。"
    echo "MySQL：$version（库 $CFG_DB_NAME @ $CFG_DB_HOST:$CFG_DB_PORT）"
    mkdir -p "$CFG_STORAGE"; local probe="$CFG_STORAGE/.write-check-$$"; echo storage-check > "$probe" && rm -f "$probe"
    echo "存储目录可写：$CFG_STORAGE"
    local jar_for_check; if jar_for_check="$(resolve_jar '' 2>/dev/null)" && browsers_present "$CFG_BROWSERS" "$jar_for_check" "$java"; then echo "Chromium 已安装：$CFG_BROWSERS"
    else warn "配置的目录里没有本版本需要的 Chromium：$CFG_BROWSERS。执行 UI/PDF 前请运行 scripts/aitest.sh install-browsers（up 会自动装）。"; fi
    [[ -n "$CFG_MODEL_BASE_URL" && -n "$CFG_MODEL_API_KEY" && -n "$CFG_MODEL_NAME" ]] || echo '配置文件里的模型信息不完整：手工功能都能用；界面「模型设置」里保存的模型同样有效。'
    if [[ $test_model -eq 1 ]]; then
        local pid; pid="$(managed_pid "$CFG_RUN_DIR")"; [[ -n "$pid" ]] || die '请先启动实例，再测试它实际生效的模型配置。'
        local base; base="$(json_get "$CFG_RUN_DIR/state.json" baseUrl)"
        local jar="$CFG_RUN_DIR/check-cookies-$$.txt" csrf='' cookie_args=()
        if [[ "$CFG_AUTH_ENABLED" == true ]]; then
            csrf="$(curl -s -m 15 -c "$jar" "$base/api/auth/session" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("csrfToken",""))')"
            [[ -n "$csrf" ]] || die '无法读取平台会话。'
            curl -s -m 15 -f -o /dev/null -b "$jar" -c "$jar" -H "X-CSRF-TOKEN: $csrf" --data-urlencode "username=$CFG_AUTH_USERNAME" --data-urlencode "password=$CFG_AUTH_PASSWORD" "$base/api/auth/login" || { rm -f "$jar"; die '配置文件里的平台账号无法登录。'; }
            csrf="$(curl -s -m 15 -b "$jar" -c "$jar" "$base/api/auth/session" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("csrfToken",""))')"
            cookie_args=(-b "$jar" -c "$jar" -H "X-CSRF-TOKEN: $csrf")
        fi
        curl -s -m 120 -X POST "${cookie_args[@]}" "$base/api/settings/model/test"; echo
        [[ "$CFG_AUTH_ENABLED" == true ]] && curl -s -m 15 -o /dev/null -X POST "${cookie_args[@]}" "$base/api/auth/logout" || true
        rm -f "$jar"
    fi
}
# 问 Playwright 它需要哪些内核目录，检查是否齐全。playwright_locations BROWSERS_PATH JAR JAVA
playwright_locations() {
    PLAYWRIGHT_BROWSERS_PATH="$1" "$3" -jar "$2" --playwright-cli install --dry-run chromium 2>/dev/null | sed -n 's/^ *Install location: *//p'
}
browsers_present() {   # browsers_present BROWSERS_PATH JAR JAVA
    [[ -n "$1" && -d "$1" ]] || return 1
    local location found=0
    while IFS= read -r location; do [[ -n "$location" ]] || continue; found=1; [[ -f "$location/INSTALLATION_COMPLETE" ]] || return 1; done < <(playwright_locations "$1" "$2" "$3")
    [[ $found -eq 1 ]]
}
# 找已有的 Chromium：config.json 的 paths.browsers → PLAYWRIGHT_BROWSERS_PATH → .tools 自带 → Playwright 默认目录 ~/.cache/ms-playwright
find_browsers() {   # find_browsers CONFIGURED JAR JAVA → 目录，找不到返回 1
    local candidate label
    for candidate in "$1|config.json 的 paths.browsers" "${PLAYWRIGHT_BROWSERS_PATH-}|环境变量 PLAYWRIGHT_BROWSERS_PATH" "$TOOLS_ROOT/playwright-$PLAYWRIGHT_VERSION|.tools 自带" "$HOME/.cache/ms-playwright|Playwright 默认目录"; do
        label="${candidate#*|}"; candidate="${candidate%%|*}"
        [[ -n "$candidate" ]] || continue
        if browsers_present "$candidate" "$2" "$3"; then remember_source browsers "$label：$candidate"; echo "$candidate"; return 0; fi
    done
    return 1
}

cmd_install_browsers() {
    local instance='' config_path='' jar='' dry_run=0 with_deps=0 browsers='chromium'
    while [[ $# -gt 0 ]]; do case "$1" in
        --instance-directory) instance="$2"; shift 2 ;; --config-path) config_path="$2"; shift 2 ;; --jar-path) jar="$2"; shift 2 ;; --browsers) browsers="$2"; shift 2 ;;
        --dry-run) dry_run=1; shift ;; --with-deps) with_deps=1; shift ;; *) die "install-browsers 不认识的选项：$1" ;; esac; done
    instance="$(resolve_instance "$instance")"
    [[ -n "$config_path" ]] || init_config "$instance"
    read_config "$instance" "$config_path"
    jar="$(resolve_jar "$jar")"
    local java; java="$(java_or_install "$CFG_JAVA_HOME")"
    [[ $OFFLINE -eq 0 || $dry_run -eq 1 ]] || die "离线模式：不能联网安装浏览器内核。请把内核目录复制到 $CFG_BROWSERS，或在 config.json 的 paths.browsers 里指定已有的目录。"
    local -a args=(install); [[ $dry_run -eq 1 ]] && args+=(--dry-run); [[ $with_deps -eq 1 ]] && args+=(--with-deps)
    IFS=',' read -r -a list <<< "$browsers"; args+=("${list[@]}")
    # 国内网络：先走 npmmirror 的 Playwright 镜像，失败时 Playwright 会自动回退到官方源。
    env -u AI_TEST_STORAGE -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD PLAYWRIGHT_BROWSERS_PATH="$CFG_BROWSERS" \
        PLAYWRIGHT_DOWNLOAD_HOST="${PLAYWRIGHT_DOWNLOAD_HOST:-https://npmmirror.com/mirrors/playwright}" "$java" -jar "$jar" --playwright-cli "${args[@]}" \
        || die '浏览器内核安装失败，请看上面 Playwright 的输出。缺系统库时用 --with-deps（需要 root），或按提示 sudo apt install 相应的包。'
    echo "浏览器内核目录：$CFG_BROWSERS"
}

# ---------------------------------------------------------------- backup / restore ----------------------------------------------------------------
cmd_backup() {
    local instance='' config_path='' destination='' leave_stopped=0
    while [[ $# -gt 0 ]]; do case "$1" in
        --instance-directory) instance="$2"; shift 2 ;; --config-path) config_path="$2"; shift 2 ;; --destination-directory) destination="$2"; shift 2 ;; --leave-stopped) leave_stopped=1; shift ;;
        *) die "backup 不认识的选项：$1" ;; esac; done
    read_config "$(resolve_instance "$instance")" "$config_path"
    local was_running=0 jar=''; [[ -n "$(managed_pid "$CFG_RUN_DIR")" ]] && { was_running=1; jar="$(json_get "$CFG_RUN_DIR/state.json" jar)"; }
    [[ -n "${AI_TEST_MASTER_KEY-}" || -f "$CFG_STORAGE/.master-key" ]] || die "找不到主密钥 $CFG_STORAGE/.master-key（实例从未启动过？）。"
    local key_hash; key_hash="$(master_key_hash)"
    if [[ -f "$CFG_RUN_DIR/state.json" ]]; then local recorded; recorded="$(json_get "$CFG_RUN_DIR/state.json" masterKeyHash '')"; [[ -z "$recorded" || "$recorded" == "$key_hash" ]] || die '当前主密钥与上次运行的实例不一致，没有生成备份。'; fi
    [[ -n "$destination" ]] || destination="$CFG_INSTANCE/backups"
    mkdir -p "$destination"; destination="$(cd "$destination" && pwd)"
    [[ "$destination" == "$CFG_STORAGE" || "$destination" == "$CFG_STORAGE/"* ]] && die '备份目录不能放在正在使用的存储目录里面。'
    local backup_root; backup_root="$destination/backup-$(date -u +%Y%m%d-%H%M%S)-$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    [[ $was_running -eq 1 ]] && cmd_stop --instance-directory "$CFG_INSTANCE" --config-path "$CFG_CONFIG_PATH"
    local status=0
    (
        set -e
        local version; version="$(sql 'SELECT VERSION();')"; [[ "$version" == 8.4.* ]] || die '备份需要 MySQL 8.4。'
        mkdir -p "$backup_root/storage"
        mysql_tool mysqldump --output "$backup_root/database.sql" --single-transaction --skip-lock-tables --skip-add-locks --routines --events --triggers --hex-blob --set-gtid-purged=OFF --no-tablespaces "$CFG_DB_NAME"
        find "$CFG_STORAGE" -type l | grep -q . && die '存储目录里有链接文件，请先处理掉再做可移植备份。'
        ( cd "$CFG_STORAGE" && find . -mindepth 1 \( -path './.workers' -o -path './.render' \) -prune -o -print0 | tar --null -cf - --files-from=- ) | ( cd "$backup_root/storage" && tar -xf - )
        if [[ -n "${AI_TEST_MASTER_KEY-}" ]]; then printf '%s' "$AI_TEST_MASTER_KEY" | base64 -d > "$backup_root/storage/.master-key"; fi
        cp "$CFG_CONFIG_PATH" "$backup_root/configuration.json"
        local migration; migration="$(sql 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;')"
        python3 - "$backup_root" "$version" "$migration" "$CFG_DB_NAME" "$key_hash" <<'PY'
import json,sys,os,hashlib,datetime
root,version,migration,database,key_hash=sys.argv[1:]
files=[]
for directory,_,names in os.walk(root):
    for name in names:
        full=os.path.join(directory,name); rel=os.path.relpath(full,root).replace(os.sep,'/')
        files.append({'path':rel,'bytes':os.path.getsize(full),'sha256':hashlib.sha256(open(full,'rb').read()).hexdigest().upper()})
manifest={'formatVersion':'aitest.backup/v1','createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mysqlVersion':version,'schemaVersion':migration,
          'sourceDatabase':database,'masterKeyHash':key_hash,'files':files}
json.dump(manifest,open(os.path.join(root,'manifest.json'),'w',encoding='utf-8'),ensure_ascii=False,indent=2)
PY
        chmod -R go-rwx "$backup_root"
        echo "备份完成：$backup_root"
        echo '备份里包含解密密钥和数据库口令，请像保管数据库备份一样保管它。'
    ) || status=$?
    [[ $was_running -eq 1 && $leave_stopped -eq 0 ]] && cmd_start --instance-directory "$CFG_INSTANCE" --config-path "$CFG_CONFIG_PATH" --jar-path "$jar"
    return $status
}

release_version() {
    local root="$1"
    [[ -f "$root/release.json" ]] || { echo '未知版本'; return; }
    json_get "$root/release.json" version '未知版本'
}

copy_missing_tree() {
    local source="$1" destination="$2"
    [[ -d "$source" ]] || return 0
    mkdir -p "$destination"
    # cp -n keeps files already supplied by the old installation and still
    # propagates permission, disk and other I/O failures to the caller.
    cp -a -n -- "$source/." "$destination/" || die "复制发行包工具目录失败：$source -> $destination"
}

cmd_upgrade() {
    local target='' yes=0
    while [[ $# -gt 0 ]]; do case "$1" in
        --target) target="$2"; shift 2 ;; --yes) yes=1; shift ;;
        *) die "upgrade 不认识的选项：$1" ;;
    esac; done
    [[ -n "$target" ]] || die '用法：upgrade --target <旧安装目录> [--yes]'
    [[ $IS_SOURCE_TREE -eq 0 && -f "$RELEASE_JAR" ]] || die 'upgrade 必须从包含 app.jar 的新发行包运行。'
    target="$(cd "$target" && pwd)" || die "旧安装目录不存在：$target"
    [[ "$target" != "$PROJECT_ROOT" ]] || die '旧安装目录不能是当前新发行包目录。'
    for required in app.jar scripts/aitest.sh instance; do [[ -e "$target/$required" ]] || die "旧安装目录缺少 $required，不能升级。"; done
    local old_version new_version; old_version="$(release_version "$target")"; new_version="$(release_version "$PROJECT_ROOT")"
    echo "旧版本：$old_version"; echo "新版本：$new_version"
    if [[ $yes -eq 0 ]]; then
        read -r -p '升级会先备份并停止旧实例，然后修改旧文件夹。确认继续请输入 YES：' answer
        [[ "$answer" == YES ]] || die '已取消升级，没有修改旧安装目录。'
    fi
    step '1/6 备份旧实例'
    bash "$target/scripts/aitest.sh" backup --instance-directory "$target" --leave-stopped
    step '2/6 停止旧实例和数据库'
    bash "$target/scripts/aitest.sh" down --instance-directory "$target"
    local parent leaf rollback; parent="$(dirname "$target")"; leaf="$(basename "$target")"
    rollback="$parent/${leaf}-升级前-$(date +%Y%m%d-%H%M%S)"
    [[ ! -e "$rollback" ]] || rollback="$rollback-$$"
    step '3/6 创建升级前回退副本'
    cp -a "$target" "$rollback"
    ok "回退副本：$rollback"
    [[ -d "$target/.runtime/mysql/data" ]] || warn '旧实例的 MySQL 数据目录不在安装文件夹内，升级前副本不包含数据库；逻辑备份仍然已保存。'
    step '4/6 替换程序文件'
    local directory name
    for directory in scripts docs database licenses; do
        [[ -d "$PROJECT_ROOT/$directory" ]] || continue
        rm -rf "$target/$directory"
        cp -a "$PROJECT_ROOT/$directory" "$target/$directory"
    done
    for name in app.jar README.md NOTICE.md release.json SHA256SUMS config.example.json source.zip; do
        [[ -f "$PROJECT_ROOT/$name" ]] && cp -f "$PROJECT_ROOT/$name" "$target/$name"
    done
    copy_missing_tree "$PROJECT_ROOT/.tools" "$target/.tools"
    ok '已保留 instance、data、.runtime，并补齐未覆盖的 .tools 子目录。'
    step '5/6 启动新版本'
    bash "$target/scripts/aitest.sh" up --instance-directory "$target" --no-browser
    read_config "$target"
    local url; url="$(json_get "$CFG_RUN_DIR/state.json" baseUrl)"
    health_up "$url" || die "升级后健康检查未通过，请查看 $(json_get "$CFG_RUN_DIR/state.json" stdout '')"
    step '6/6 完成'
    echo "升级完成：$target（$new_version）"
    echo "如需回退：停止实例，把旧目录改名，再把 $rollback 改回 $leaf。"
}

cmd_restore() {
    local backup='' instance='' config_path=''
    while [[ $# -gt 0 ]]; do case "$1" in
        --backup-directory) backup="$2"; shift 2 ;; --instance-directory) instance="$2"; shift 2 ;; --config-path) config_path="$2"; shift 2 ;; *) die "restore 不认识的选项：$1" ;; esac; done
    [[ -n "$backup" && -n "$instance" ]] || die '用法：restore --backup-directory <备份目录> --instance-directory <新实例目录>'
    read_config "$(resolve_instance "$instance")" "$config_path"
    [[ -n "$(managed_pid "$CFG_RUN_DIR")" ]] && die '请先停止目标实例再恢复。'
    local incomplete="$CFG_RUN_DIR/restore-incomplete.json"
    [[ -f "$incomplete" ]] && die '这个目标实例有一次未完成的恢复。请换一个新的空库和空目录；未完成的现场已保留供检查。'
    backup="$(cd "$backup" && pwd)"
    [[ -d "$CFG_STORAGE" ]] && [[ -n "$(ls -A "$CFG_STORAGE")" ]] && die '恢复只接受空的目标存储目录。'
    # 清单校验：路径归属、重复、链接、长度与哈希、必需文件、密钥一致。
    local key_hash; key_hash="$(python3 - "$backup" <<'PY'
import json,sys,os,hashlib
root=sys.argv[1]
def fail(m): print('__ERROR__ '+m); sys.exit(0)
try: manifest=json.load(open(os.path.join(root,'manifest.json'),encoding='utf-8'))
except Exception: fail('备份缺少 manifest.json 或它不是合法 JSON。')
if manifest.get('formatVersion')!='aitest.backup/v1' or not manifest.get('files'): fail('备份不完整或格式不支持。')
seen=set(); resolved=set()
for f in manifest['files']:
    p=f['path']
    if p in seen: fail('备份清单里有重复路径。')
    seen.add(p)
    if p not in ('database.sql','configuration.json') and not p.startswith('storage/'): fail('备份清单里有意料之外的文件。')
    if not p or os.path.isabs(p) or ':' in p: fail('备份内的路径必须是非空的相对路径。')
    full=os.path.realpath(os.path.join(root,p))
    if not full.startswith(os.path.realpath(root)+os.sep): fail('备份内的路径越出了它所在的目录。')
    if full in resolved: fail('备份里同一个文件出现了多个名字。')
    resolved.add(full)
    current=os.path.join(root,p)
    while os.path.realpath(current)!=os.path.realpath(root):
        if os.path.islink(current): fail('备份里不接受链接。')
        current=os.path.dirname(current)
    if not os.path.isfile(full) or os.path.getsize(full)!=f['bytes'] or hashlib.sha256(open(full,'rb').read()).hexdigest().upper()!=str(f['sha256']).upper(): fail(f'备份文件校验失败：{p}')
for required in ('database.sql','configuration.json','storage/.master-key'):
    if required not in seen: fail(f'备份缺少必需文件：{required}')
key=open(os.path.join(root,'storage/.master-key'),'rb').read()
if len(key)!=32 or hashlib.sha256(key).hexdigest().upper()!=str(manifest['masterKeyHash']).upper(): fail('备份里的主密钥与清单不一致。')
print(manifest['masterKeyHash'],manifest['schemaVersion'])
PY
)"
    [[ "$key_hash" == __ERROR__* ]] && die "${key_hash#__ERROR__ }"
    local schema_version="${key_hash#* }"; key_hash="${key_hash%% *}"
    if [[ -n "${AI_TEST_MASTER_KEY-}" ]]; then [[ "$(printf '%s' "$AI_TEST_MASTER_KEY" | base64 -d | sha256sum | cut -d' ' -f1 | tr 'a-f' 'A-F')" == "${key_hash^^}" ]] || die '恢复前请清除 AI_TEST_MASTER_KEY，或让它与备份密钥一致。'; fi
    [[ "$(sql 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();')" == 0 ]] || die '恢复只接受已存在的空数据库，没有改动任何表。'
    [[ "$(sql 'SELECT VERSION();')" == 8.4.* ]] || die '恢复需要 MySQL 8.4。'
    grep -qE '^\s*(USE\s|(CREATE|DROP|ALTER)\s+DATABASE\s)' "$backup/database.sql" && die '这个导出文件会切换数据库，不是平台的可移植实例备份。'
    mkdir -p "$CFG_RUN_DIR"
    json_set "$incomplete" "startedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" "backup=$backup" "targetDatabase=$CFG_DB_NAME" "storage=$CFG_STORAGE" "phase=COPY_FILES"
    local status=0
    (
        set -e
        mkdir -p "$CFG_STORAGE"
        ( cd "$backup/storage" && tar -cf - . ) | ( cd "$CFG_STORAGE" && tar -xf - )
        json_set "$incomplete" "phase=IMPORT_DATABASE"
        mysql_tool mysql --input "$backup/database.sql" --binary-mode "$CFG_DB_NAME"
        [[ "$(sql 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;')" == "$schema_version" ]] || die '恢复后的数据库迁移版本与备份不一致。'
        local reference; reference="$CFG_INSTANCE/restored-configuration-$(date -u +%Y%m%d%H%M%S).json"
        cp "$backup/configuration.json" "$reference"; chmod 600 "$reference"
        rm -f "$incomplete"
        echo "已把数据库和受管文件恢复到空实例：$CFG_INSTANCE"
        echo "目标实例的连接、端口和路径配置保持不变；原配置另存为：$reference"
    ) || status=$?
    [[ $status -eq 0 ]] || die "恢复没有完成。现场已保留，$incomplete 会阻止启动。请查看 MySQL 日志、修复原因后，用新的空目标重新恢复。"
}

# ---------------------------------------------------------------- 一键工作流 ----------------------------------------------------------------
# 没有 config.json → 用自带 MySQL；config.json 指向本机且端口等于自带实例端口（默认 3307）→ 自带；其余视为外部数据库。
use_project_mysql() {
    [[ -f "$1/config.json" ]] || return 0
    local project_port=3307; [[ -f "$MYSQL_CONNECTION_FILE" ]] && project_port="$(json_get "$MYSQL_CONNECTION_FILE" port 3307)"
    python3 - "$1/config.json" "$project_port" <<'PY'
import json,sys
from urllib.parse import urlsplit
try:
    url=json.load(open(sys.argv[1],encoding='utf-8'))['database']['url']; parts=urlsplit(url[5:])
    sys.exit(0 if (parts.hostname in ('127.0.0.1','localhost','::1') and (parts.port or 3306)==int(sys.argv[2])) else 1)
except Exception: sys.exit(0)
PY
}
cmd_up() {
    local instance='' build=0
    while [[ $# -gt 0 ]]; do case "$1" in --instance-directory) instance="$2"; shift 2 ;; --build) build=1; shift ;; --no-browser) shift ;; *) die "up 不认识的选项：$1" ;; esac; done
    instance="$(resolve_instance "$instance")"
    local configured_java='' configured_mysql=''
    if [[ -f "$instance/config.json" ]]; then configured_java="$(json_get "$instance/config.json" javaHome '')"; configured_mysql="$(json_get "$instance/config.json" mysqlHome '')"; fi
    [[ $OFFLINE -eq 0 ]] || note '离线模式：只使用本机和 .tools 里已有的组件，不联网下载。'
    step '1/6 Java 21'
    local java; java="$(java_or_install "$configured_java")"; ok "$(recall_source java)"
    step '2/6 MySQL 8.4'
    if use_project_mysql "$instance"; then
        local -a mysql_args=(); [[ -n "$configured_mysql" && ! -f "$MYSQL_CONNECTION_FILE" ]] && mysql_args=(--mysql-home "$configured_mysql")
        cmd_mysql "${mysql_args[@]}" | while IFS= read -r line; do ok "$line"; done
        note "$(recall_source mysql)"
    else ok '使用 config.json 里配置的数据库'; fi
    step '3/6 实例配置'
    init_config "$instance" | while IFS= read -r line; do ok "$line"; done
    json_set "$instance/config.json" "javaHome=$(dirname "$(dirname "$java")")"
    if use_project_mysql "$instance" && [[ -f "$MYSQL_CONNECTION_FILE" ]]; then json_set "$instance/config.json" "mysqlHome=$(json_get "$MYSQL_CONNECTION_FILE" home)"; fi
    read_config "$instance"; ok "配置文件：$CFG_CONFIG_PATH"
    step '4/6 程序包'
    local jar
    if [[ -f "$RELEASE_JAR" ]]; then jar="$RELEASE_JAR"
    elif [[ $IS_SOURCE_TREE -eq 1 ]]; then
        jar="$DEV_JAR"
        if [[ $build -eq 1 || ! -f "$jar" ]]; then
            warn '程序包不存在或要求重新打包（跳过测试），约需几分钟；Maven 首次还要下载依赖。'
            pnpm install --frozen-lockfile; pnpm build
            maven -B -ntp -Pdistribution -DskipTests clean package || die '后端打包失败，请看上面 Maven 的输出。'
        fi
    else die '这个目录既没有 app.jar，也不是源码目录。'; fi
    ok "$jar"
    step '5/6 Playwright 浏览器内核'
    local browsers
    if browsers="$(find_browsers "$CFG_BROWSERS" "$jar" "$java")"; then
        ok "Chromium 已就绪：$(recall_source browsers)"
        if [[ "$browsers" != "$CFG_BROWSERS" ]]; then json_set "$CFG_CONFIG_PATH" "paths.browsers=$browsers"; read_config "$instance"; fi
    elif [[ $OFFLINE -eq 1 ]]; then
        warn "离线模式：没有找到 Chromium 内核。UI 自动化和 PDF 功能不可用，其余功能正常。请把内核目录复制到 $CFG_BROWSERS，或在 config.json 的 paths.browsers 里指定。"
    else
        warn 'Chromium 缺失，自动安装（约 170 MB，一次性）。'
        cmd_install_browsers --instance-directory "$instance" --jar-path "$jar" | while IFS= read -r line; do ok "$line"; done \
            || { warn '浏览器内核安装失败。不影响 UI 自动化和 PDF 之外的功能，稍后再运行 up 会重试。'; }
    fi
    step '6/6 后端服务'
    cmd_start --instance-directory "$instance" --jar-path "$jar" | while IFS= read -r line; do ok "$line"; done
    local url; url="$(json_get "$CFG_RUN_DIR/state.json" baseUrl)"
    echo; printf '\033[32m✔ 平台已就绪：%s\033[0m\n' "$url"
    [[ -n "$CFG_MODEL_BASE_URL" && -n "$CFG_MODEL_NAME" ]] || warn '模型未配置：手工功能全部可用；AI 生成/诊断请在网页右上角「模型设置」里填写 OpenAI 兼容服务。'
    note '停止：scripts/aitest.sh down    状态：scripts/aitest.sh status    日志：scripts/aitest.sh logs'
}
cmd_down() {
    local instance='' keep_mysql=0 force=0
    while [[ $# -gt 0 ]]; do case "$1" in --instance-directory) instance="$2"; shift 2 ;; --keep-mysql) keep_mysql=1; shift ;; --force) force=1; shift ;; *) die "down 不认识的选项：$1" ;; esac; done
    instance="$(resolve_instance "$instance")"
    step '1/2 后端服务'
    if [[ -f "$instance/config.json" ]]; then
        read_config "$instance"
        if [[ -n "$(managed_pid "$CFG_RUN_DIR")" ]]; then
            if ! cmd_stop --instance-directory "$instance" | while IFS= read -r line; do ok "$line"; done; then
                [[ $force -eq 1 ]] || exit 1
                warn '优雅停止失败，按 --force 强制结束。'; cmd_stop --instance-directory "$instance" --force | while IFS= read -r line; do ok "$line"; done
            fi
        else ok '未运行'; fi
    else ok '尚无实例配置，跳过'; fi
    step '2/2 项目 MySQL'
    if [[ $keep_mysql -eq 1 ]]; then ok '按要求保留运行'; else stop_project_mysql; fi
    echo; printf '\033[32m✔ 已全部停止。再次启动：scripts/aitest.sh up\033[0m\n'
}
cmd_status() {
    local instance=''
    while [[ $# -gt 0 ]]; do case "$1" in --instance-directory) instance="$2"; shift 2 ;; *) die "status 不认识的选项：$1" ;; esac; done
    instance="$(resolve_instance "$instance")"
    show() { if [[ "$2" == 1 ]]; then printf '  \033[32m● %-6s %s\033[0m\n' "$1" "$3"; else printf '  \033[90m○ %-6s %s\033[0m\n' "$1" "$3"; fi; }
    printf '\033[36mAI-Test-Platform 本地状态\033[0m\n'
    if [[ -f "$MYSQL_CONNECTION_FILE" ]]; then
        local pid port; pid="$(project_mysql_pid)"; port="$(json_get "$MYSQL_CONNECTION_FILE" port)"
        if [[ -n "$pid" ]]; then show MySQL 1 "127.0.0.1:$port  PID $pid"; else show MySQL 0 "未运行（端口 $port）"; fi
    else show MySQL 0 '尚未初始化，scripts/aitest.sh up 会自动创建'; fi
    if [[ -f "$instance/config.json" ]]; then
        read_config "$instance"
        local pid; pid="$(managed_pid "$CFG_RUN_DIR")"
        if [[ -n "$pid" ]]; then
            local url; url="$(json_get "$CFG_RUN_DIR/state.json" baseUrl)"
            show 后端 1 "$url  PID $pid  健康检查 $(health_up "$url" && echo 通过 || echo 未通过)"
        else show 后端 0 "未运行（将监听 http://127.0.0.1:$CFG_PORT）"; fi
        [[ -f "$CFG_RUN_DIR/state.json" ]] && printf '           \033[90m日志 %s\033[0m\n' "$(json_get "$CFG_RUN_DIR/state.json" stdout '')"
        if [[ -n "$CFG_MODEL_BASE_URL" && -n "$CFG_MODEL_NAME" ]]; then show 模型 1 "$CFG_MODEL_NAME @ $CFG_MODEL_BASE_URL"; else show 模型 0 '配置文件未填写；网页「模型设置」里保存的配置优先生效'; fi
    else show 后端 0 '尚无实例配置'; fi
    printf '\033[36m环境（来源：路径）\033[0m\n'
    local configured_java=''; [[ -f "$instance/config.json" ]] && configured_java="$(json_get "$instance/config.json" javaHome '')"
    if find_java "$configured_java" >/dev/null 2>&1; then echo "  Java      $(recall_source java)"; else printf '  \033[33mJava      未找到 Java 21 或更高版本\033[0m\n'; fi
    if [[ -f "$MYSQL_CONNECTION_FILE" ]]; then local home; home="$(json_get "$MYSQL_CONNECTION_FILE" home)"; echo "  MySQL     $([[ "$home" == "$TOOLS_ROOT"/* ]] && echo '.tools 自带' || echo '本机已安装')：$home"
    elif [[ -f "$instance/config.json" ]] && ! use_project_mysql "$instance"; then echo "  MySQL     config.json 里配置的外部数据库：$CFG_DB_HOST:$CFG_DB_PORT"
    else printf '  \033[90mMySQL     尚未准备\033[0m\n'; fi
    if [[ -f "$instance/config.json" ]]; then
        local jar_for_status; if jar_for_status="$(resolve_jar '' 2>/dev/null)" && java_for_status="$(find_java "$configured_java" 2>/dev/null)" && browsers_present "$CFG_BROWSERS" "$jar_for_status" "$java_for_status"; then echo "  Chromium  就绪：$CFG_BROWSERS"; else printf '  \033[33mChromium  缺失：%s\033[0m\n' "$CFG_BROWSERS"; fi
    else printf '  \033[90mChromium  尚未配置\033[0m\n'; fi
    echo "  离线模式  $([[ $OFFLINE -eq 1 ]] && echo '开（AI_TEST_OFFLINE）' || echo '关')"
}
cmd_logs() {
    local instance='' errors=0 tail=60
    while [[ $# -gt 0 ]]; do case "$1" in --instance-directory) instance="$2"; shift 2 ;; --errors) errors=1; shift ;; --tail) tail="$2"; shift 2 ;; *) die "logs 不认识的选项：$1" ;; esac; done
    read_config "$(resolve_instance "$instance")"
    [[ -f "$CFG_RUN_DIR/state.json" ]] || die '后端还没有启动过，没有日志。'
    local file; if [[ $errors -eq 1 ]]; then file="$(json_get "$CFG_RUN_DIR/state.json" stderr)"; else file="$(json_get "$CFG_RUN_DIR/state.json" stdout)"; fi
    note "正在实时显示 $file（按 Ctrl+C 退出）"
    exec tail -n "$tail" -F "$file"
}

# ---------------------------------------------------------------- 构建与验证 ----------------------------------------------------------------
cmd_verify() {
    local settings='' full=0 forks=3
    while [[ $# -gt 0 ]]; do case "$1" in --maven-settings) settings="$2"; shift 2 ;; --full) full=1; shift ;; --forks) forks="$2"; shift 2 ;; --include-browser) die 'Linux 版暂不提供 --include-browser 浏览器流程，请在 Windows 上运行。' ;; *) die "verify 不认识的选项：$1" ;; esac; done
    [[ $IS_SOURCE_TREE -eq 1 ]] || die 'verify 只能在源码目录使用。'
    pnpm install --frozen-lockfile; pnpm lint; pnpm test:unit; pnpm build
    cmd_reset_test_db --forks "$forks"
    local -a args=(-B -ntp verify "-Daitest.it.forks=$forks"); [[ $full -eq 1 ]] && args+=(-Pnightly); [[ -n "$settings" ]] && args+=(-s "$(readlink -f "$settings")")
    maven "${args[@]}" || die '后端验证失败，请看上面 Maven 的输出。'
    echo '所选检查全部通过。公司模型的生成质量仍需在真实环境单独验收。'
}
cmd_build() {
    local skip_tests=0 settings='' output='' forks=3
    while [[ $# -gt 0 ]]; do case "$1" in --skip-tests) skip_tests=1; shift ;; --maven-settings) settings="$2"; shift 2 ;; --output-directory) output="$2"; shift 2 ;; --forks) forks="$2"; shift 2 ;; *) die "build 不认识的选项：$1" ;; esac; done
    [[ $IS_SOURCE_TREE -eq 1 ]] || die 'build 只能在源码目录使用。'
    pnpm install --frozen-lockfile; pnpm lint; [[ $skip_tests -eq 1 ]] || pnpm test:unit; pnpm build
    [[ $skip_tests -eq 1 ]] || cmd_reset_test_db --forks "$forks"
    # shellcheck disable=SC2054
    local -a args=(-B -ntp -Pdistribution,nightly clean verify "-Daitest.it.forks=$forks"); [[ -n "$settings" ]] && args+=(-s "$(readlink -f "$settings")"); [[ $skip_tests -eq 1 ]] && args+=(-DskipTests)
    maven "${args[@]}" || die '后端验证或打包失败，请看上面 Maven 的输出。'
    local version; version="$(python3 -c 'import re,sys; print(re.search(r"<version>([^<]+)</version>", open(sys.argv[1],encoding="utf-8").read()).group(1))' "$PROJECT_ROOT/backend/pom.xml")"
    local jar="$PROJECT_ROOT/backend/target/ai-test-platform-$version.jar"
    python3 - "$jar" <<'PY' || exit 1
import zipfile,sys
with zipfile.ZipFile(sys.argv[1]) as z:
    names=set(z.namelist())
    for entry in ('BOOT-INF/classes/static/index.html','BOOT-INF/classes/com/aitest/AiTestApplication.class'):
        if entry not in names: print('发行 JAR 不完整，缺少 '+entry); sys.exit(1)
    if sum(1 for n in names if n.startswith('BOOT-INF/classes/db/migration/V') and n.endswith('.sql'))<23: print('发行 JAR 缺少数据库迁移脚本。'); sys.exit(1)
PY
    local revision=source dirty=null
    if [[ -d "$PROJECT_ROOT/.git" ]]; then revision="$(git -C "$PROJECT_ROOT" rev-parse --short=12 HEAD)"; [[ -z "$(git -C "$PROJECT_ROOT" status --porcelain)" ]] && dirty=false || dirty=true; fi
    [[ -n "$output" ]] || output="$PROJECT_ROOT/artifacts/releases"
    mkdir -p "$output"; output="$(cd "$output" && pwd)"
    local release_root; release_root="$output/ai-test-platform-$version-$(date -u +%Y%m%d-%H%M%S)-$revision"
    [[ -e "$release_root" ]] && die '发行目录已存在，没有覆盖任何文件。'
    mkdir -p "$release_root/scripts" "$release_root/database" "$release_root/docs"
    cp "$jar" "$release_root/app.jar"; cp "$PROJECT_ROOT/deploy/config.example.json" "$release_root/config.example.json"
    local file
    for file in "$PROJECT_ROOT"/docs/*.md "$PROJECT_ROOT"/docs/*.sql; do
        [[ -f "$file" ]] || continue
        case "$(basename "$file")" in roadmap.md|codex-implementation-prompt.md|session-handoff-*.md) continue ;; esac
        cp "$file" "$release_root/docs/"
    done
    [[ -d "$PROJECT_ROOT/docs/prompts" ]] && cp -r "$PROJECT_ROOT/docs/prompts" "$release_root/docs/prompts"
    cp -r "$PROJECT_ROOT/licenses" "$release_root/licenses"
    cp "$SCRIPT_DIR/aitest.ps1" "$SCRIPT_DIR/aitest.sh" "$release_root/scripts/"; chmod +x "$release_root/scripts/aitest.sh"
    cp "$PROJECT_ROOT"/*.cmd "$release_root/" 2>/dev/null || true
    cp -r "$PROJECT_ROOT/backend/src/main/resources/db/migration" "$release_root/database/migration"
    cp "$PROJECT_ROOT/README.md" "$PROJECT_ROOT/NOTICE.md" "$release_root/"
    ( cd "$PROJECT_ROOT" && { find backend/src backend/.mvn frontend/src frontend/public frontend/tests scripts docs licenses deploy -type f 2>/dev/null;
        for f in .gitignore .gitattributes .editorconfig README.md NOTICE.md backend-pom-template.xml frontend-package-template.json backend/pom.xml backend/mvnw backend/mvnw.cmd \
                 frontend/.gitignore frontend/README.md frontend/THIRD_PARTY_NOTICES.md frontend/package.json frontend/pnpm-lock.yaml frontend/index.html frontend/tsconfig.json \
                 frontend/vite.config.ts frontend/vitest.config.ts frontend/eslint.config.js frontend/playwright.config.ts frontend/playwright.ai.config.ts frontend/playwright.auth.config.ts *.cmd; do [[ -f "$f" ]] && echo "$f"; done; } \
        | sort -u | python3 -c '
import sys,zipfile,os
with zipfile.ZipFile(sys.argv[1],"w",zipfile.ZIP_DEFLATED) as z:
    for line in sys.stdin:
        p=line.rstrip("\n")
        if os.path.islink(p): raise SystemExit("源码包不接受链接文件。")
        z.write(p,p)' "$release_root/source.zip" )
    local node_version; node_version="$(PATH="$(node_directory):$PATH" node -p 'process.versions.node')"
    json_set "$release_root/release.json" "version=$version" "sourceRevision=$revision" "uncommittedSource=json:$dirty" "builtAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" "node=$node_version" \
        "pnpm=$(json_get "$FRONTEND/package.json" packageManager)" "testsSkipped=json:$([[ $skip_tests -eq 1 ]] && echo true || echo false)"
    ( cd "$release_root" && find . -type f ! -name SHA256SUMS | sort | sed 's#^\./##' | xargs sha256sum > SHA256SUMS )
    ( cd "$output" && python3 -c '
import sys,zipfile,os
root=sys.argv[1]
with zipfile.ZipFile(root+".zip","w",zipfile.ZIP_DEFLATED) as z:
    for d,_,names in os.walk(root):
        for n in names:
            full=os.path.join(d,n); z.write(full,os.path.relpath(full,root))' "$release_root" )
    echo "发行目录：$release_root"; echo "发行压缩包：$release_root.zip"; echo "压缩包 SHA-256：$(sha256_of "$release_root.zip")"
}
cmd_clean() {
    local keep=1 what_if=0
    while [[ $# -gt 0 ]]; do case "$1" in --keep-releases) keep="$2"; shift 2 ;; --what-if) what_if=1; shift ;; *) die "clean 不认识的选项：$1" ;; esac; done
    pgrep -x java >/dev/null 2>&1 && die '有 Java 进程正在运行。请先结束构建/测试，或执行 scripts/aitest.sh down。'
    local -a targets=() entry
    if [[ -d "$RUNTIME_ROOT" ]]; then
        for entry in "$RUNTIME_ROOT"/* "$RUNTIME_ROOT"/.[!.]*; do
            [[ -e "$entry" ]] || continue
            case "$(basename "$entry")" in mysql|pnpm-shim|dev|cdx-schemas|bom-validator-classes|maven-central-settings.xml|build.cmd|run-release-build.ps1|sbom-*|*.ps1|*.mjs|*.java|*.xml|*.cmd) continue ;; esac
            targets+=("$entry")
        done
    fi
    local releases="$PROJECT_ROOT/artifacts/releases"
    if [[ -d "$releases" ]]; then
        local -a keepers=(); while IFS= read -r entry; do keepers+=("$(basename "${entry%.zip}")"); done < <(ls -t "$releases"/*.zip 2>/dev/null | head -n "$keep")
        for entry in "$releases"/*; do
            local base; base="$(basename "$entry")"; base="${base%.zip}"; base="${base%.acceptance.json}"; base="${base%.dependencies.json}"; base="${base%.sbom.json}"
            local keep_it=0 k; for k in "${keepers[@]}"; do [[ "$k" == "$base" ]] && keep_it=1; done
            [[ $keep_it -eq 1 ]] || targets+=("$entry")
        done
    fi
    for entry in frontend/test-results frontend/playwright-report backend/target/failsafe-reports backend/target/surefire-reports; do [[ -e "$PROJECT_ROOT/$entry" ]] && targets+=("$PROJECT_ROOT/$entry"); done
    local bytes=0; for entry in "${targets[@]}"; do bytes=$((bytes + $(du -sb "$entry" | cut -f1))); done
    if [[ $what_if -eq 1 ]]; then for entry in "${targets[@]}"; do echo "将删除 $entry"; done; else for entry in "${targets[@]}"; do rm -rf "$entry"; done; fi
    echo "$([[ $what_if -eq 1 ]] && echo 将删除 || echo 已删除) ${#targets[@]} 项，共 $(python3 -c "print(round($bytes/2**30,1))") GB。"
}

# ---------------------------------------------------------------- 命令分发 ----------------------------------------------------------------
command="${1:-help}"; [[ $# -gt 0 ]] && shift
# 通用的 --offline 开关，任何命令都可以带。
if [[ " $* " == *" --offline "* ]]; then
    OFFLINE=1; filtered=(); for argument in "$@"; do [[ "$argument" == --offline ]] || filtered+=("$argument"); done; set -- "${filtered[@]}"
fi
case "$command" in
    up) cmd_up "$@" ;; down) cmd_down "$@" ;; restart) cmd_down --keep-mysql "$@"; cmd_up "$@" ;; status) cmd_status "$@" ;; logs) cmd_logs "$@" ;;
    check) cmd_check "$@" ;; start) cmd_start "$@" ;; stop) cmd_stop "$@" ;; backup) cmd_backup "$@" ;; restore) cmd_restore "$@" ;; install-browsers) cmd_install_browsers "$@" ;;
    build) cmd_build "$@" ;; verify) cmd_verify "$@" ;; clean) cmd_clean "$@" ;; mysql) cmd_mysql "$@" ;; reset-test-db) cmd_reset_test_db "$@" ;; maven) maven "$@" ;; upgrade) cmd_upgrade "$@" ;;
    help|-h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//' ;;
    *) die "未知命令：$command。运行 scripts/aitest.sh help 查看用法。" ;;
esac
