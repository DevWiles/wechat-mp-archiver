#!/usr/bin/env bash
# 一键运行（Git Bash）：滚到底 → 扫链接 → 抓正文
set -euo pipefail
cd "$(dirname "$0")"

command -v java >/dev/null || { echo "[错误] 没找到 java，请先安装 JDK 17+"; exit 1; }
if [ ! -f target/wx-archiver.jar ]; then
    echo "首次运行，正在编译..."
    mvn -q -B package
fi

read -rp "公众号名称 [回车默认 韩数同学]: " NAME;  NAME=${NAME:-韩数同学}
read -rp "公众号 biz [回车默认 Mzg3ODIwNDg5OA==]: " BIZ;  BIZ=${BIZ:-Mzg3ODIwNDg5OA==}
read -rp "输出目录 [回车默认 out/$NAME]: " OUT;  OUT=${OUT:-out/$NAME}
read -rp "每篇间隔秒数 [回车默认 3]: " DELAY;  DELAY=${DELAY:-3}
mkdir -p "$OUT"
URLS="$OUT/urls.txt"

echo; echo "===== 第 1 步：获取文章链接 ====="
reuse=n
if [ -s "$URLS" ]; then
    read -rp "已有链接清单 $URLS，直接复用？[Y/n] " reuse; reuse=${reuse:-y}
fi
if [[ ! $reuse =~ ^[Yy] ]]; then
    echo "请先在微信电脑版里打开「$NAME」的主页。"
    read -rp "自动慢速滚动到底？[Y=自动 / n=我自己手动滚，回车=Y] " mode; mode=${mode:-y}
    while [[ $mode =~ ^[Yy] ]]; do
        read -rp "回车后，切到微信公众号主页，把鼠标放在文章列表上别动，静止 3 秒自动开始..." _
        if java -jar target/wx-archiver.jar scroll --biz "$BIZ" --out "$URLS"; then
            break
        fi
        read -rp "滚动没有完成。R=重试 / M=改为手动滚 / Q=退出 [回车=R] " ans; ans=${ans:-r}
        case $ans in
            [Qq]*) exit 1 ;;
            [Mm]*) mode=n ;;
        esac
    done
    if [[ ! $mode =~ ^[Yy] ]]; then
        read -rp "请手动把历史文章一直滚到底（直到不再加载新文章），然后按回车继续..." _
        java -jar target/wx-archiver.jar scan --biz "$BIZ" --out "$URLS"
    fi
    [ -s "$URLS" ] || { echo "[提示] 没扫到任何链接：确认主页已滚到底、biz 是否正确"; exit 1; }
fi

echo; echo "===== 第 2 步：抓取正文（可随时 Ctrl+C，下次运行自动续抓） ====="
java -jar target/wx-archiver.jar fetch --biz "$BIZ" --urls "$URLS" --out "$OUT" --delay "$DELAY"
echo; echo "完成，输出在：$OUT"
