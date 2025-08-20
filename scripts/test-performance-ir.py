#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
后端集成测试（LLVM IR → lli）
1. SysY → C → x86-64（参考 clang 版本，作为参考输出 + 运行时间）
2. SysY → LLVM-IR（本编译器）→ sylib.bc → lli  （待测输出 + 运行时间）
3. 对比 stdout / return value，并统计 TOTAL 计时行，汇总平均耗时与倍率
   ─ 同步 test-performance.py 的时间行过滤与统计逻辑 ─

支持：
  ▸ --dir  <folder1> [folder2 …]   仅运行指定资源子目录
  ▸ --file <some.sy>               仅运行单个 .sy 文件
"""

from __future__ import annotations

import argparse, datetime, difflib, os, re, subprocess, sys
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
REPORT_PATH   = OUT_DIR / "report-ir.txt"
JAVA_CP       = os.pathsep.join((str(COMPILER_JAR), str(ANTLR_JAR)))

# ─────────── 目录准备 ───────────
for p in (C_DIR, OUT_DIR):
    p.mkdir(parents=True, exist_ok=True)

# 匹配 Timer@起始-结束: 后面跟 H-M-S-us 的格式，或 TOTAL: H-M-S-us
_TIMER_RE = re.compile(
    r'(?:Timer@[0-9A-Fa-f\-]+:\s*\d+H-\d+M-\d+S-\d+us)|'
    r'(?:TOTAL:\s*\d+H-\d+M-\d+S-\d+us)',
    re.I
)

def clean(lines: list[str]) -> list[str]:
    """
    逐行将 Timer@...us 和 TOTAL:...us 子串去除，
    并收尾去掉多余空格。保留空行（或根据需要再过滤）。
    """
    if not FILTER_TIMER:
        return lines

    out = []
    for line in lines:
        # 删除计时子串
        stripped = _TIMER_RE.sub('', line)
        # 合并连续空格并去除行尾空白
        stripped = re.sub(r'[ \t]{2,}', ' ', stripped).rstrip()
        out.append(stripped)
    return out


# TOTAL: 0H-0M-5S-32610us → 微秒
_total_pat = re.compile(r'TOTAL:\s*(\d+)H-(\d+)M-(\d+)S-(\d+)us')

def parse_total(line: str) -> int:
    m = _total_pat.search(line)
    if not m:
        return -1
    h, m_, s, us = map(int, m.groups())
    return ((h * 60 + m_) * 60 + s) * 1_000_000 + us

perf_data: list[tuple[str, int, int]] = []   # (tag, ref_us, our_us)

# ─────────── 基础工具 ───────────

def run(cmd: list[str] | tuple[str, ...], **kw):
    kw.setdefault("check", True)
    return subprocess.run(cmd, **kw)


def run_prog(cmd: list[str] | tuple[str, ...], stdin_file: Path | None, outfile):
    fin = open(stdin_file, "rb") if stdin_file and stdin_file.exists() else subprocess.DEVNULL

    # 对 lli 调大栈限制（避免递归深栈 segfault）
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

# ─────────── JAR 自动构建 ───────────

def jar_outdated() -> bool:
    if not COMPILER_JAR.exists():
        return True
    jar_t = COMPILER_JAR.stat().st_mtime
    src_t = max([p.stat().st_mtime for p in ROOT.rglob("*.java")]+
                [p.stat().st_mtime for p in ROOT.rglob("*.g4")], default=0.0)
    return src_t > jar_t

if jar_outdated():
    print("[info] rebuilding compiler JAR …")
    run(["gradle", "-q", "jar"], cwd=ROOT)
    if not COMPILER_JAR.exists():
        sys.exit("❌ gradle jar failed: jar not found")

# ─────────── sylib.bc 准备 ───────────
if not SYLIB_BC.exists():
    print("[info] building sylib.bc …")
    run([CLANG, "-O2", "-c", "-emit-llvm",
         str(LIB_DIR/"sylib.c"), "-o", str(SYLIB_BC)])

# ─────────── 辅助函数 ───────────

def gen_c(sy: Path) -> Path:
    """在同层级生成带 sylib.h 的 .c 文件供 clang 参考编译"""
    tgt = C_DIR / sy.relative_to(RESOURCE_ROOT)
    tgt = tgt.with_suffix(".c")
    tgt.parent.mkdir(parents=True, exist_ok=True)
    with open(tgt, "w") as o, open(sy) as s:
        o.write('#include "sylib.h"\n')
        o.write(s.read())
    return tgt

# ─────────── 报告 I/O ───────────
REPORT = None

def clog(msg=""): print(msg)

def rlog(msg=""):
    REPORT.write(msg + "\n")

# ─────────── 单用例 ───────────

def split_lines(lines: list[str]):
    if '---' in lines:
        i = lines.index('---')
        return lines[:i], lines[i+1] if i+1 < len(lines) else ''
    return lines, ''


def test_case(sy: Path) -> bool:
    rel = sy.relative_to(RESOURCE_ROOT)
    tag = str(rel)

    outd = OUT_DIR / rel.parent
    outd.mkdir(parents=True, exist_ok=True)

    base   = sy.stem
    exe    = outd / f"{base}.ref"
    ref_o  = outd / f"{base}.ref.out"
    raw_ll = outd / f"{base}.raw.ll"
    link   = outd / f"{base}.ll"
    lli_o  = outd / f"{base}.lli.out"
    stdin  = sy.with_suffix(".in")

    # --- 1. clang 参考 (x86) ---
    try:
        run([CLANG, "-w", "-fcommon", str(gen_c(sy)), str(LIBSYSY_A),
             "-I", str(LIB_DIR), "-static", "-fno-pie", "-O2", "-o", exe])
        with open(ref_o, "w") as f:
            rc_ref = run_prog([exe], stdin, f)
        ref_o.write_text(ref_o.read_text() + f"\n---\nRETVAL={rc_ref}\n")
    except subprocess.CalledProcessError as e:
        clog(f"[FAIL] {tag} (clang)"); rlog(f"[CLANG FAIL] {tag}\n{e}\n")
        return False

    # --- 2. 本编译器 → lli ---
    try:
        run(["java", "-Xss1024m", "-cp", JAVA_CP, "Compiler", "-O1",
             str(sy), "-emit-llvm", "-o", str(raw_ll)])
        run([LLVMLINK, raw_ll, SYLIB_BC, "-o", str(link)])
        with open(lli_o, "w") as f:
            rc_lli = run_prog([LLI, str(link)], stdin, f)
        lli_o.write_text(lli_o.read_text() + f"\n---\nRETVAL={rc_lli}\n")
    except subprocess.CalledProcessError as e:
        clog(f"[FAIL] {tag} (lli)"); rlog(f"[LLI FAIL] {tag}\n{e}\n")
        return False

    # --- 3. 对比 & 性能收集 ---
    ro, rr = split_lines(ref_o.read_text().splitlines())
    lo, lr = split_lines(lli_o.read_text().splitlines())

    # 解析 TOTAL
    tot_ref_us = next((parse_total(l) for l in ro if 'TOTAL:' in l), -1)
    tot_our_us = next((parse_total(l) for l in lo if 'TOTAL:' in l), -1)
    if tot_ref_us > 0 and tot_our_us > 0:
        perf_data.append((tag, tot_ref_us, tot_our_us))

    ro, lo = clean(ro), clean(lo)

    if ro == lo and rr == lr:
        clog(f"[PASS] {tag}")
        return True

    clog(f"[FAIL] {tag} (see report)")
    rlog(f"[FAIL] {tag}")
    if ro != lo:
        rlog("  ↳ stdout diff:")
        rlog('\n'.join(difflib.unified_diff(ro, lo, 'clang', 'lli', lineterm='')) or "   (one side empty)")
    if rr != lr:
        rlog(f"  ↳ return value: clang={rr or 'Ø'} | lli={lr or 'Ø'}")
    rlog()
    return False

# ─────────── 用例收集 ───────────

def collect_cases(dirs: list[str] | None):
    if not dirs:
        target_dirs = [RESOURCE_ROOT / d for d in ("functional", "hidden_functional",
                                                  "performance", "final_performance")]
    else:
        target_dirs = [RESOURCE_ROOT / d for d in dirs]
    missing = [d for d in target_dirs if not d.exists()]
    if missing:
        sys.exit("❌ 指定目录不存在: " + ", ".join(str(p) for p in missing))
    cases: list[Path] = []
    for d in target_dirs:
        cases += list(d.rglob("*.sy"))
    return cases

# ─────────── 主程 ───────────

def main():
    global REPORT

    ap = argparse.ArgumentParser(description="Run SysY IR tests")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--dir",  nargs="*", metavar="FOLDER")
    g.add_argument("--file", metavar="SYSY_FILE")
    args = ap.parse_args()

    # 待测文件列表
    if args.file:
        raw = Path(args.file)
        sy = (raw if raw.is_absolute() else (Path.cwd() / raw)).resolve()
        if not sy.exists():
            alt = (RESOURCE_ROOT / raw).resolve()
            if alt.exists():
                sy = alt
        if not sy.exists():
            sys.exit(f"❌ file not found: {args.file}")
        cases  = [sy]
        header = f"SINGLE FILE {sy.relative_to(RESOURCE_ROOT)}"
    else:
        cases  = collect_cases(args.dir)
        header = "Folders: " + (", ".join(args.dir) if args.dir else "ALL")

    REPORT = open(REPORT_PATH, "w", encoding="utf-8")
    rlog(f"# IR Report {datetime.datetime.now().isoformat()}")
    rlog(f"# {header}\n")

    total = passed = 0
    for sy in sorted(cases):
        total += 1
        if test_case(sy):
            passed += 1

    summary = f"\n=== {passed}/{total} tests passed ==="
    clog(summary); rlog(summary)

    # ─ 性能汇总 ─
    if perf_data:
        avg_ref = sum(r for _, r, _ in perf_data) / len(perf_data)
        avg_our = sum(r for _, _, r in perf_data) / len(perf_data)
        ratio   = avg_our / avg_ref if avg_ref else float('inf')
        perf_head = (f"\n⏱️  平均运行时间 our-lli : clang-x86 = "
                      f"{avg_our/1e6:.3f}s / {avg_ref/1e6:.3f}s "
                      f"(ratio ≈ {ratio:.2f}×)\n")
        clog(perf_head.strip()); rlog(perf_head)
        for tag, ref_us, our_us in perf_data:
            rlog(f"{tag}: {our_us/1e6:.3f}s vs {ref_us/1e6:.3f}s "
                 f"({our_us/ref_us:.2f}×)")

    REPORT.close(); clog(f"📄 详细报告: {REPORT_PATH}")

if __name__ == "__main__":
    main()
