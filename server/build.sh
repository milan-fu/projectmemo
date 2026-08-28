#!/usr/bin/env bash
# ProjectMemo (memo-server) 编译脚本 —— 复用 plus-bridge 模式：纯 javac + jar + shade
set -euo pipefail
cd "$(dirname "$0")"

LIB=${LIB:-../../lib}
LIB_ABS="$(cd "$LIB" && pwd)"
CP="$LIB_ABS/*"

SRC=src/main/java/com/sthstrange/projectmemo
RES=src/main/resources
OUT=build
CLS="$OUT/classes"

rm -rf "$CLS"
mkdir -p "$CLS"

echo "[build] compiling java (release 21)..."
javac -encoding UTF-8 -proc:none --release 21 -cp "$CP" -d "$CLS" "$SRC"/*.java

echo "[build] copying resources..."
cp "$RES"/plugin.yml "$RES"/config.yml "$CLS"/

echo "[build] shading deps (json + adventure-plain)..."
(
  cd "$CLS"
  for dep in "$LIB_ABS"/json-*.jar "$LIB_ABS"/adventure-text-serializer-plain-*.jar; do
    [ -f "$dep" ] && unzip -oq "$dep" -d . || true
  done
  find . -name '*.SF' -delete 2>/dev/null || true
  find . -name '*.RSA' -delete 2>/dev/null || true
  find . -name '*.DSA' -delete 2>/dev/null || true
  find . -name 'module-info.class' -delete 2>/dev/null || true
  rm -rf ./META-INF 2>/dev/null || true
)

echo "[build] packaging jar..."
( cd "$CLS" && jar cf "../../$OUT/ProjectMemo-1.0.1.jar" . )

echo "[build] done:"
ls -la "$OUT/ProjectMemo-1.0.1.jar"
