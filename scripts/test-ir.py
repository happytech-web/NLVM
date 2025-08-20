#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
后端集成测试（LLVM IR → lli）
1. SysY → C → x86-64（参考结果）
2. SysY → LLVM-IR（本编译器）→ sylib.bc → lli  运行
3. 对比 stdout / return code

支持：
   ▸ --dir  <folder1> [folder2 …]   按资源库子目录批量跑
   ▸ --file <some.sy>               只跑单个 SysY 源
"""

import difflib, subprocess, datetime, argparse, sys, os
from pathlib import Path

# ─────────── 固定路径 ───────────
ROOT          = Path(__file__).resolve().parent.parent
COMPILER_JAR  = ROOT / "build/libs/compiler2025-nlvm-1.0.jar"
ANTLR_JAR     = ROOT / "lib/antlr-4.9.3-complete.jar"

RESOURCE_ROOT = ROOT / "test/resources"      # functional/… 均在此下
C_DIR         = ROOT / "test/tmp_c"          # 生成 *.c 临时目录
LIB_DIR       = ROOT / "sysy-runtime"
OUT_DIR       = ROOT / "scripts/out"

CLANG      = "clang"
LLVMLINK   = "llvm-link"
LLI        = "lli"

LIBSYSY_A  = LIB_DIR / "x86-64/libsysy.a"
SYLIB_BC   = LIB_DIR / "x86-64/sylib.bc"

FILTER_TIMER  = True
REPORT_PATH   = OUT_DIR / "report.txt"

# ─────────── 目录准备 ──────────
C_DIR.mkdir(parents=True, exist_ok=True)
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

# ─────────── sylib.bc (一次生成即可) ───────────
if not SYLIB_BC.exists():
    print("[info] building sylib.bc …")
    run([CLANG, "-O2", "-c", "-emit-llvm",
         str(LIB_DIR / "sylib.c"), "-o", str(SYLIB_BC)])

# ─────────── 工具函数 ───────────
def clean(lines):
    if not FILTER_TIMER:
        return lines
    return [l for l in lines if not (l.startswith("TOTAL:") or l.startswith("Timer#"))]

def run_prog(cmd, stdin_file, outfile):
    fin = open(stdin_file, "rb") if stdin_file and stdin_file.exists() else subprocess.DEVNULL
    
    # 为 lli 增加 ulimit
    is_lli = cmd[0] == LLI
    if is_lli and sys.platform != "win32":
        cmd_str = "ulimit -s unlimited; " + " ".join(f"'{c}'" for c in cmd)
    else:
        cmd_str = " ".join(f"'{c}'" for c in cmd)

    try:
        r = subprocess.run(cmd_str, shell=True, stdin=fin, stdout=outfile,
                           stderr=subprocess.STDOUT, check=False)
        return r.returncode
    finally:
        if fin is not subprocess.DEVNULL:
            fin.close()

def gen_c(sy: Path) -> Path:
    """生成带 #include "sylib.h" 的 .c 文件（保持层级）"""
    tgt = C_DIR / sy.relative_to(RESOURCE_ROOT)
    tgt = tgt.with_suffix(".c")
    tgt.parent.mkdir(parents=True, exist_ok=True)
    with open(tgt, "w") as o, open(sy) as s:
        o.write('#include "sylib.h"\n')
        o.write(s.read())
    return tgt

# ─────────── 报告 I/O ───────────
REPORT = None   # 在 main() 中打开
def clog(msg=""): print(msg)
def rlog(msg=""): REPORT.write(msg + "\n")

JAVA_CP = os.pathsep.join((str(COMPILER_JAR), str(ANTLR_JAR)))

# ─────────── 单用例测试 ───────────
def test_case(sy: Path) -> bool:
    rel  = sy.relative_to(RESOURCE_ROOT)
    tag  = str(rel)

    outd = OUT_DIR / rel.parent
    outd.mkdir(parents=True, exist_ok=True)

    base   = sy.stem
    exe    = outd / f"{base}.ref"
    ref_o  = outd / f"{base}.ref.out"
    raw    = outd / f"{base}.raw.ll"
    link   = outd / f"{base}.ll"
    lli_o  = outd / f"{base}.ll.out"

    stdin  = sy.with_suffix(".in")

    # ---------- clang 参考 ----------
    try:
        run([CLANG, "-w", "-fcommon", str(gen_c(sy)), str(LIBSYSY_A),
             "-I", str(LIB_DIR), "-static", "-fno-pie", "-o", exe])
        with open(ref_o, "w") as f:
            rc_ref = run_prog([exe], stdin, f)
        ref_o.write_text(ref_o.read_text() + f"\n---\nRETVAL={rc_ref}\n")
    except subprocess.CalledProcessError as e:
        clog(f"[FAIL] {tag}  (clang error)"); rlog(f"[CLANG FAIL] {tag}\n{e}\n")
        return False

    # ---------- 本编译器 + lli ----------
    try:
        #passes_to_run = "CFGAnalysis,Mem2regPass,GVN,ArrayLayoutOptimizationPass"
        #passes_to_run = "CFGAnalysis,Mem2reg,ArrayLayoutOptimizationPass"
        #print(f"[info] passes_to_run: {passes_to_run}")
        #passes_to_run = "CFGAnalysis,Mem2regPass,DominanceAnalysis"
        #passes_to_run = "CFGAnalysis,Mem2regPass,ArrayAliasAnalysis"
        run(["java", "-Xss1024m", "-Ddebug=true", "-cp", JAVA_CP, "Compiler", sy, "-emit-llvm" ,"-o", raw, "-O1"])
        run([LLVMLINK, raw, SYLIB_BC, "-o", link])
        with open(lli_o, "w") as f:
            rc_lli = run_prog([LLI, str(link)], stdin, f)
        lli_o.write_text(lli_o.read_text() + f"\n---\nRETVAL={rc_lli}\n")
    except subprocess.CalledProcessError as e:
        clog(f"[FAIL] {tag}  (lli error)"); rlog(f"[LLI FAIL] {tag}\n{e}\n")
        return False

    # ---------- 对比 ----------
    def _split(lines):
        if '---' in lines:
            i = lines.index('---')
            return lines[:i], lines[i+1] if i+1 < len(lines) else ''
        return lines, ''

    ro, rr = _split(ref_o.read_text().splitlines())
    lo, lr = _split(lli_o.read_text().splitlines())
    ro, lo = clean(ro), clean(lo)

    if ro == lo and rr == lr:
        clog(f"[PASS] {tag}")
        return True

    clog(f"[FAIL] {tag}  (see report)")
    rlog(f"[FAIL] {tag}")
    if ro != lo:
        rlog("  ↳ stdout diff:")
        rlog('\n'.join(difflib.unified_diff(ro, lo, 'clang', 'lli', lineterm='')) or "   (one side empty)")
    if rr != lr:
        rlog(f"  ↳ return value: clang={rr or 'Ø'} | lli={lr or 'Ø'}")
    rlog()
    return False

# ─────────── 用例收集 ───────────
def collect_cases(selected_dirs):
    if not selected_dirs:
        dirs = [RESOURCE_ROOT / d for d in
                ("functional", "hidden_functional", "performance", "final_performance")]
    else:
        dirs = [RESOURCE_ROOT / d for d in selected_dirs]
    missing = [d for d in dirs if not d.exists()]
    if missing:
        sys.exit("❌ 指定目录不存在: " + ", ".join(str(p) for p in missing))
    cases = []
    for d in dirs:
        cases += list(d.rglob("*.sy"))
    return cases

# ─────────── 主程序 ───────────
def main():
    global REPORT

    ap = argparse.ArgumentParser(description="Run SysY tests")
    g  = ap.add_mutually_exclusive_group()
    g.add_argument("--dir", nargs="*", metavar="FOLDER",
                   help="subfolder(s) under test/resources/ to run "
                        "(default: run all top-level suites)")
    g.add_argument("--file", metavar="SYSY_FILE",
                   help="run single .sy file (abs / rel / "
                        "path relative to test/resources)")
    args = ap.parse_args()

    # ── 解析待测文件列表 ───────────────────────────────
    if args.file:
        raw = Path(args.file)
        sy_path = (raw if raw.is_absolute() else (Path.cwd() / raw)).resolve()
        if not sy_path.exists():
            p2 = (RESOURCE_ROOT / raw).resolve()
            if p2.exists():
                sy_path = p2
        if not sy_path.exists():
            sys.exit(f"❌ file not found: {args.file}")
        cases  = [sy_path]
        header = f"SINGLE FILE {sy_path.relative_to(RESOURCE_ROOT)}"
    else:
        cases  = collect_cases(args.dir)
        header = "Folders: " + (", ".join(args.dir) if args.dir else "ALL")

    REPORT = open(REPORT_PATH, "w", encoding="utf-8")
    rlog(f"# Report {datetime.datetime.now().isoformat()}")
    rlog(f"# {header}\n")

    total = passed = 0
    for sy in sorted(cases):
        total += 1
        if test_case(sy):
            passed += 1

    summary = f"\n=== {passed}/{total} tests passed ==="
    clog(summary); rlog(summary)
    REPORT.close(); clog(f"详细报告: {REPORT_PATH}")

if __name__ == "__main__":
    main()
