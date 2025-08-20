#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DEPRECATED!!!
DEPRECATED!!!
DEPRECATED!!!
please check test-backend-ll.py

后端集成测试：
 1. SysY → C → x86_64 参考执行
 2. SysY → C → LLVM-IR → ARM64 汇编 → AArch64 ELF → qemu-aarch64
 3. 对比 stdout / return code
"""
import difflib, subprocess, datetime, argparse, sys, os, shutil, textwrap
from pathlib import Path

# ─────────── 固定路径 ───────────
ROOT           = Path(__file__).resolve().parent.parent
COMPILER_JAR   = ROOT / "build/libs/compiler2025-nlvm-1.0.jar"
ANTLR_JAR      = ROOT / "lib/antlr-4.9.3-complete.jar"

RESOURCE_ROOT  = ROOT / "test/resources"
C_DIR          = ROOT / "test/tmp_c"          # 生成的 *.c
LL_DIR         = ROOT / "test/tmp_ll"         # 生成的 *.ll
LIB_DIR        = ROOT / "sysy-runtime"
OUT_DIR        = ROOT / "scripts/out-backend"

CLANG          = "clang"                      # host x86-64
CROSS_GCC      = "aarch64-unknown-linux-gnu-gcc"
QEMU           = "qemu-aarch64"

LIBSYSY_X86    = LIB_DIR / "x86-64/libsysy.a"
LIBSYSY_A64    = LIB_DIR / "aarch64/libsysy.a"

FILTER_TIMER   = True
REPORT_PATH    = OUT_DIR / "report.txt"

# ─────────── 目录准备 ───────────
for p in (C_DIR, LL_DIR, OUT_DIR):
    p.mkdir(parents=True, exist_ok=True)

def run(cmd, **kw):
    kw.setdefault("check", True)
    return subprocess.run(cmd, **kw)

# ─────────── 若 jar 过期则自动构建 ─
def jar_outdated() -> bool:
    """若 JAR 不存在或早于最新 .java/.g4 文件则返回 True"""
    if not COMPILER_JAR.exists():
        return True
    jar_mtime = COMPILER_JAR.stat().st_mtime

    java_mtime = max((p.stat().st_mtime for p in ROOT.rglob("*.java")), default=0.0)
    g4_mtime   = max((p.stat().st_mtime for p in ROOT.rglob("*.g4")),   default=0.0)
    newest_src = max(java_mtime, g4_mtime)

    return newest_src > jar_mtime

if jar_outdated():
    print("[info] Rebuilding compiler JAR …")
    run(["gradle", "-q", "jar"])
    if not COMPILER_JAR.exists():
        print("❌ gradle jar failed: jar not found"); sys.exit(1)

# ─────────── 工具函数 ───────────
def clean(lines):
    if not FILTER_TIMER:
        return lines
    return [l for l in lines if not (l.startswith("TOTAL:") or l.startswith("Timer#"))]

def run_prog(cmd, stdin_file, outfile):
    fin = open(stdin_file, "rb") if stdin_file and stdin_file.exists() else subprocess.DEVNULL
    try:
        r = subprocess.run(cmd, stdin=fin, stdout=outfile,
                           stderr=subprocess.STDOUT, check=False)
        return r.returncode
    finally:
        if fin is not subprocess.DEVNULL:
            fin.close()

def sy_to_c(sy: Path) -> Path:
    tgt = C_DIR / sy.relative_to(RESOURCE_ROOT)
    tgt = tgt.with_suffix(".c")
    tgt.parent.mkdir(parents=True, exist_ok=True)
    with open(tgt, "w") as o, open(sy) as s:
        o.write('#include "sylib.h"\n')
        o.write(s.read())
    return tgt

def c_to_ll(c_file: Path) -> Path:
    ll = LL_DIR / c_file.relative_to(C_DIR)
    ll = ll.with_suffix(".ll")
    ll.parent.mkdir(parents=True, exist_ok=True)
    run([CLANG, "-S", "-emit-llvm", "-O0", "-fno-discard-value-names",
         "-I", str(LIB_DIR), "-o", ll, c_file])
    return ll

# ─────────── 报告 I/O ───────────
REPORT = open(REPORT_PATH, "w", encoding="utf-8")
def clog(msg=""): print(msg)
def rlog(msg=""): REPORT.write(msg + "\n")

JAVA_CP = os.pathsep.join((str(COMPILER_JAR), str(ANTLR_JAR)))

# ─────────── 测单个用例 ──────────
def test_case(sy: Path) -> bool:
    rel   = sy.relative_to(RESOURCE_ROOT)
    outd  = OUT_DIR / rel.parent
    outd.mkdir(parents=True, exist_ok=True)

    base     = sy.stem
    exe_ref  = outd / f"{base}.ref"
    ref_out  = outd / f"{base}.ref.out"

    asm_a64  = outd / f"{base}.s"
    elf_a64  = outd / f"{base}.elf"
    run_out  = outd / f"{base}.run.out"

    stdin    = sy.with_suffix(".in")
    tag      = str(rel)

    # ---------- 1. 参考执行 ----------
    try:
        cfile = sy_to_c(sy)
        run([CLANG, "-w", "-O2", "-fcommon", cfile, str(LIBSYSY_X86),
             "-I", str(LIB_DIR), "-static", "-fno-pie", "-o", exe_ref])
        with open(ref_out, "w") as f:
            rc = run_prog([exe_ref], stdin, f)
        ref_out.write_text(ref_out.read_text() + f"\n---\nRETVAL={rc}\n")
    except subprocess.CalledProcessError as e:
        clog(f"[FAIL] {tag} (clang-x86 error)"); rlog(f"[CLANG-X86 FAIL] {tag}\n{e}\n")
        return False

    # ---------- 2. 生成 LLVM-IR ----------
    llfile = c_to_ll(cfile)

    # ---------- 3. 调用后端生成 A64 汇编 ----------
    try:
        run(["java", "-cp", JAVA_CP, "Compiler", llfile, "-o", asm_a64])
    except subprocess.CalledProcessError as e:
        clog(f"[FAIL] {tag} (backend compile)"); rlog(f"[BACKEND FAIL] {tag}\n{e}\n"); return False

    # ---------- 4. A64 链接 & 运行 ----------
    try:
        run([CROSS_GCC, "-static", asm_a64, str(LIBSYSY_A64), "-o", elf_a64])
        with open(run_out, "w") as f:
            rc2 = run_prog([QEMU, elf_a64], stdin, f)
        run_out.write_text(run_out.read_text() + f"\n---\nRETVAL={rc2}\n")
    except subprocess.CalledProcessError as e:
        clog(f"[FAIL] {tag} (link/run A64)"); rlog(f"[A64 FAIL] {tag}\n{e}\n"); return False

    # ---------- 5. 对比 ----------
    ref_lines = ref_out.read_text().splitlines()
    run_lines = run_out.read_text().splitlines()

    def split(ls):
        if '---' in ls:
            i = ls.index('---')
            return ls[:i], ls[i+1] if i+1 < len(ls) else ''
        return ls, ''
    ro, rr = split(ref_lines)
    ao, ar = split(run_lines)
    ro, ao = clean(ro), clean(ao)

    if ro == ao and rr == ar:
        clog(f"[PASS] {tag}")
        return True

    clog(f"[FAIL] {tag} (see report)")
    rlog(f"[FAIL] {tag}")
    if ro != ao:
        rlog("  ↳ stdout diff:")
        rlog('\n'.join(difflib.unified_diff(ro, ao, 'ref', 'aarch64', lineterm='')) or "   (one side empty)")
    if rr != ar:
        rlog(f"  ↳ return value: ref={rr or 'Ø'} | aarch64={ar or 'Ø'}")
    rlog()
    return False

# ─────────── 用例收集 ───────────
def collect_cases(selected):
    if not selected:
        dirs = [RESOURCE_ROOT/d for d in
                ("functional","hidden_functional","performance","final_performance")]
    else:
        dirs = [RESOURCE_ROOT/d for d in selected]
    missing = [d for d in dirs if not d.exists()]
    if missing:
        sys.exit("❌ 目录不存在: " + ", ".join(str(p) for p in missing))
    cases=[]
    for d in dirs: cases += list(d.rglob("*.sy"))
    return cases

# ─────────── main ───────────────
def main():
    ap = argparse.ArgumentParser("Backend tester")
    ap.add_argument("--dir", nargs="*", help="subfolder(s) to run")
    args = ap.parse_args()

    cases = collect_cases(args.dir)
    rlog(f"# Report {datetime.datetime.now().isoformat()}")
    rlog("# Folders: " + (", ".join(args.dir) if args.dir else "ALL") + "\n")

    total=passed=0
    for sy in sorted(cases):
        total+=1
        if test_case(sy): passed+=1

    summary=f"\n=== {passed}/{total} tests passed ==="
    clog(summary); rlog(summary)
    REPORT.close(); clog(f"详细报告: {REPORT_PATH}")

if __name__ == "__main__":
    main()
