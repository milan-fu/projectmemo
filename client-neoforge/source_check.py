#!/usr/bin/env python3
"""ProjectMemo NeoForge 工程守门脚本（单一事实源不变式 I2 / I3）。

背景：NeoForge 工程用 srcDir 直接引用 ../client 的共享源码，不复制文件；
      共享代码里对 Fabric API 的依赖靠一个同名同包的 2 方法垫片吸收。
      本脚本保证"两棵树走散"这类问题在编译前就被发现。

检查项：
  I2-a 共享源码里的 loader import，除垫片覆盖的那一个类以外，其余所在文件必须被 build.gradle exclude
  I2-b build.gradle 的 exclude 列表与脚本里的 EXCLUDED 必须一致
  I2-c 共享源码用到的垫片成员，垫片必须全部提供
  I3   两个工程的 minecraft_version / version 必须一致
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SHARED_JAVA = HERE.parent / "client" / "src" / "main" / "java"
SHARED_PROPS = HERE.parent / "client" / "gradle.properties"
OWN_PROPS = HERE / "gradle.properties"
BUILD_GRADLE = HERE / "build.gradle"
SHIM_ROOT = HERE / "src" / "main" / "java" / "net" / "fabricmc"

# 垫片提供的那个 Fabric API 类（共享代码可以随便用，成员由 I2-c 校验）
SHIMMED_CLASS = "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking"
# 允许不被 NeoForge 编译的共享文件（必须同时出现在 build.gradle 的 exclude 里）
EXCLUDED = {"MemoClientMod.java"}

LOADER_IMPORT_RE = re.compile(
    r"^\s*import\s+(net\.(?:fabricmc|neoforged|minecraftforge)[\w.]*)\s*;", re.M
)


def read_props(path: Path) -> dict:
    out = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def main() -> int:
    errors = []
    if not SHARED_JAVA.is_dir():
        print(f"[source_check] FAIL: 找不到共享源码目录 {SHARED_JAVA}", file=sys.stderr)
        return 1

    shim_members = set()
    if SHIM_ROOT.is_dir():
        for f in SHIM_ROOT.rglob("*.java"):
            shim_members |= set(re.findall(
                r"public\s+static\s+\S+\s+(\w+)\s*\(", f.read_text(encoding="utf-8", errors="replace")))

    unshimmed_files, used_members, shim_files = set(), set(), []
    for f in sorted(SHARED_JAVA.rglob("*.java")):
        text = f.read_text(encoding="utf-8", errors="replace")
        imports = set(LOADER_IMPORT_RE.findall(text))
        if not imports:
            continue
        others = imports - {SHIMMED_CLASS}
        if others:
            if f.name not in EXCLUDED:
                errors.append(
                    f"{f.name} 引用了垫片之外的 loader API {sorted(others)}，但不在 exclude 白名单里 "
                    f"—— 上游新增了 loader 相关代码，需决定 exclude 还是补垫片（见方案文档 §4.6）")
            unshimmed_files.add(f.name)
        else:
            shim_files.append(f.name)
            used_members |= set(re.findall(r"\bClientPlayNetworking\.(\w+)", text))

    # I2-b：build.gradle 的 exclude 与 EXCLUDED 一致
    gradle = BUILD_GRADLE.read_text(encoding="utf-8", errors="replace")
    gradle_excludes = set(re.findall(r"java\.exclude\s+'\*\*/([\w.]+)'", gradle))
    if gradle_excludes != EXCLUDED:
        errors.append(f"build.gradle 的 exclude {sorted(gradle_excludes)} 与本脚本白名单 {sorted(EXCLUDED)} 不一致")

    lack = sorted(used_members - shim_members)
    if lack:
        errors.append(f"垫片缺少成员: {lack}（共享代码用到 {sorted(used_members)}，垫片提供 {sorted(shim_members)}）")

    sp, op = read_props(SHARED_PROPS), read_props(OWN_PROPS)
    for key in ("minecraft_version", "version"):
        if sp.get(key) != op.get(key):
            errors.append(f"{key} 不一致: client={sp.get(key)!r} neoforge={op.get(key)!r}")

    print(f"[source_check] 垫片覆盖的共享文件: {shim_files}")
    print(f"[source_check] exclude 的共享文件: {sorted(gradle_excludes)}")
    print(f"[source_check] 垫片成员 {sorted(shim_members)} ⊇ 共享使用 {sorted(used_members)}")
    print(f"[source_check] 版本对齐 minecraft={op.get('minecraft_version')} version={op.get('version')}")
    if errors:
        for e in errors:
            print(f"[source_check] FAIL: {e}", file=sys.stderr)
        return 1
    print("[source_check] OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
