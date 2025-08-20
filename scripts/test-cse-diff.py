#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对比开启/关闭 CSE 优化后的 LLVM IR 差异

用法:
   python scripts/test-cse-diff.py --file test/resources/functional/00_main.sy
"""

import subprocess, argparse, sys, os
from pathlib import Path

# ─────────── 固定路径 ───────────
ROOT          = Path(__file__).resolve().parent.parent
COMPILER_JAR  = ROOT / "build/libs/compiler2025-nlvm-1.0.jar"
ANTLR_JAR     = ROOT / "lib/antlr-4.12.0-complete.jar"

RESOURCE_ROOT = ROOT / "test/resources"
OUT_DIR       = ROOT / "scripts/out"

# ─────────── 目录准备 ──────────
OUT_DIR.mkdir(parents=True, exist_ok=True)

def run(cmd, **kw):
    kw.setdefault("check", True)
    return subprocess.run(cmd, **kw)

# ─────────── 若 JAR 过期则自动构建 ───────────
def jar_outdated() -> bool:
    if not COMPILER_JAR.exists():
        return True
    jar_mtime = COMPILER_JAR.stat().st_mtime
    src_times = [p.stat().st_mtime for p in ROOT.rglob("*.java")]
    src_times += [p.stat().st_mtime for p in ROOT.rglob("*.g4")]
    newest_src = max(src_times, default=0.0)
    return newest_src > jar_mtime

if jar_outdated():
    print("[info] rebuilding compiler JAR …")
    run(["gradle", "-q", "jar"])
    if not COMPILER_JAR.exists():
        sys.exit("❌ gradle jar failed: jar not found")

JAVA_CP = os.pathsep.join((str(COMPILER_JAR), str(ANTLR_JAR)))

# ─────────── 单用例测试 ───────────
def test_cse_diff(sy: Path):
    rel  = sy.relative_to(RESOURCE_ROOT)
    tag  = str(rel)

    outd = OUT_DIR / rel.parent
    outd.mkdir(parents=True, exist_ok=True)

    base   = sy.stem
    no_opt_ll = outd / f"{base}.no-opt.ll"
    cse_ll    = outd / f"{base}.cse.ll"

    print(f"[*] Testing {tag}")

    # ---------- 1. 编译，不带 IR 优化 ----------
    try:
        print(f"  → Generating IR without IR passes: {no_opt_ll}")
        run(["java", "-Xss1024m", "-cp", JAVA_CP, "-Dir.passes=", "Compiler", sy, "-emit-llvm", "-o", no_opt_ll])
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {tag}  (Compiler error without passes)\n{e}")
        return

    # ---------- 2. 编译，仅带 CSE 优化 ----------
    try:
        print(f"  → Generating IR with only CSE pass: {cse_ll}")
        run(["java", "-Xss1024m", "-cp", JAVA_CP, "-Dir.passes=ConstantPropagation,CommonSubexpressionElimination,DeadCodeElimination", "Compiler", sy, "-emit-llvm", "-o", cse_ll])
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {tag}  (Compiler error with CSE)\n{e}")
        return

    # ---------- 3. 对比 ----------
    print(f"  → Comparing IR files...")
    try:
        result = subprocess.run(["diff", "-u", no_opt_ll, cse_ll], capture_output=True, text=True, check=False)
        if result.stdout:
            print("\n--- DIFF START ---")
            print(result.stdout)
            print("--- DIFF END ---\n")
            print(f"[DONE] {tag} - IR differs.")
        else:
            print(f"[DONE] {tag} - No difference in IR.")

    except FileNotFoundError:
        print("[ERROR] `diff` command not found. Please install it.")
    except Exception as e:
        print(f"[ERROR] Failed to run diff: {e}")


# ─────────── 主程序 ───────────
def main():
    ap = argparse.ArgumentParser(description="Compare LLVM IR with and without CSE optimization.")
    ap.add_argument("--file", metavar="SYSY_FILE", required=True,
                   help="run single .sy file (abs / rel / path relative to test/resources)")
    args = ap.parse_args()

    raw = Path(args.file)
    sy_path = (raw if raw.is_absolute() else (Path.cwd() / raw)).resolve()
    if not sy_path.exists():
        p2 = (RESOURCE_ROOT / raw).resolve()
        if p2.exists():
            sy_path = p2
    if not sy_path.exists():
        sys.exit(f"❌ file not found: {args.file}")

    test_cse_diff(sy_path)

if __name__ == "__main__":
    main()