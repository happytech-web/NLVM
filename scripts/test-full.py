#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
前后端联合测试（E2E）
1. SysY → C → x86-64                  （参考）
2. SysY → .ll + .s → ELF → qemu-aarch64 （本编译器）
3. 比对 stdout / return code
"""

import argparse, datetime, difflib, os, subprocess, sys
from pathlib import Path

# ───── 固定路径 ─────
ROOT         = Path(__file__).resolve().parent.parent
COMPILER_JAR = ROOT / "build/libs/compiler2025-nlvm-1.0.jar"
ANTLR_JAR    = ROOT / "lib/antlr-4.12.0-complete.jar"

RES_DIR      = ROOT / "test/resources"
C_TMP_DIR    = ROOT / "test/tmp_c"
LIB_DIR      = ROOT / "sysy-runtime"
OUT_DIR      = ROOT / "scripts/out-e2e"

CLANG        = "clang"
CROSS_GCC    = "aarch64-unknown-linux-gnu-gcc"
QEMU         = "qemu-aarch64"

LIBSYSY_X86  = LIB_DIR / "x86-64/libsysy.a"
LIBSYSY_A64  = LIB_DIR / "aarch64/libsysy.a"

FILTER_TIMER = True
REPORT_PATH  = OUT_DIR / "report-e2e.txt"
JAVA_CP      = os.pathsep.join((str(COMPILER_JAR), str(ANTLR_JAR)))

for p in (C_TMP_DIR, OUT_DIR):
    p.mkdir(parents=True, exist_ok=True)

# ───── util ─────
def run(cmd, **kw):
    kw.setdefault("check", True)
    return subprocess.run(cmd, **kw)

def run_prog(cmd, stdin_file, outfile):
    fin = open(stdin_file, "rb") if stdin_file and stdin_file.exists() else subprocess.DEVNULL
    try:
        r = subprocess.run(cmd, stdin=fin, stdout=outfile,
                           stderr=subprocess.STDOUT, check=False)
        return r.returncode
    finally:
        if fin is not subprocess.DEVNULL: fin.close()

def jar_outdated() -> bool:
    if not COMPILER_JAR.exists(): return True
    jar_time = COMPILER_JAR.stat().st_mtime
    src_time = max(
        [p.stat().st_mtime for p in ROOT.rglob("*.java")] +
        [p.stat().st_mtime for p in ROOT.rglob("*.g4")], default=0.0)
    return src_time > jar_time

if jar_outdated():
    print("[info] rebuilding compiler JAR …")
    run(["./gradlew", "-q", "jar"], cwd=ROOT)
    if not COMPILER_JAR.exists():
        sys.exit("❌ gradle jar failed: jar not found")

def clean(lines):
    if not FILTER_TIMER: return lines
    return [l for l in lines if not (l.startswith("TOTAL:") or l.startswith("Timer#"))]

def split_lines(lines):
    if '---' in lines:
        i = lines.index('---')
        return lines[:i], lines[i + 1] if i + 1 < len(lines) else ''
    return lines, ''

def gen_c(sy: Path) -> Path:
    tgt = C_TMP_DIR / sy.relative_to(RES_DIR)
    tgt = tgt.with_suffix(".c")
    tgt.parent.mkdir(parents=True, exist_ok=True)
    with open(tgt, "w") as o, open(sy) as s:
        o.write('#include "sylib.h"\n')
        o.write(s.read())
    return tgt

# ───── 单用例 ─────
def test_case(sy: Path, report) -> bool:
    rel = sy.relative_to(RES_DIR)
    tag = str(rel)

    outd = OUT_DIR / rel.parent
    outd.mkdir(parents=True, exist_ok=True)

    base     = sy.stem
    exe_ref  = outd / f"{base}.ref"
    ref_out  = outd / f"{base}.ref.out"
    asm_a64  = outd / f"{base}.s"
    llvm_out = outd / f"{base}.ll"   # Driver 会自动写这个文件
    elf_a64  = outd / f"{base}.elf"
    run_out  = outd / f"{base}.run.out"
    stdin    = sy.with_suffix(".in")

    # --- 1. 参考结果 (x86) ---
    try:
        run([CLANG, "-w", "-fcommon", "-O2", str(gen_c(sy)), str(LIBSYSY_X86),
             "-I", str(LIB_DIR), "-static", "-fno-pie", "-o", exe_ref])
        with open(ref_out, "w") as f:
            rc_ref = run_prog([exe_ref], stdin, f)
        ref_out.write_text(ref_out.read_text() + f"\n---\nRETVAL={rc_ref}\n")
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {tag} (clang-x86)")
        report.write(f"[CLANG FAIL] {tag}\n{e}\n")
        return False

    # --- 2. 调用编译器：一次生成 .ll + .s ---
    try:
        run(["java", "-Xss1024m", "-cp", JAVA_CP,
             "Compiler", str(sy),
             "-emit-llvm", "-S",
             "-o", str(asm_a64),
             "-O1"])
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {tag} (compiler)")
        report.write(f"[COMPILER FAIL] {tag}\n{e}\n")
        return False

    # --- 3. 链接 & 运行 ---
    try:
        run([CROSS_GCC, "-static", "-g", "-Og", "-fno-omit-frame-pointer",
             asm_a64, str(LIBSYSY_A64), "-o", elf_a64])
        with open(run_out, "w") as f:
            rc_run = run_prog([QEMU, elf_a64], stdin, f)
        run_out.write_text(run_out.read_text() + f"\n---\nRETVAL={rc_run}\n")
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {tag} (link/run)")
        report.write(f"[RUN FAIL] {tag}\n{e}\n")
        return False

    # --- 4. 对比 ---
    ro, rr = split_lines(ref_out.read_text().splitlines())
    ao, ar = split_lines(run_out.read_text().splitlines())
    ro, ao = clean(ro), clean(ao)

    if ro == ao and rr == ar:
        print(f"[PASS] {tag}")
        return True

    print(f"[FAIL] {tag} (see report)")
    report.write(f"[FAIL] {tag}\n")
    if ro != ao:
        report.write("  ↳ stdout diff:\n")
        report.write('\n'.join(difflib.unified_diff(
            ro, ao, 'x86', 'aarch64', lineterm='')) or "   (one side empty)\n")
    if rr != ar:
        report.write(f"  ↳ return value: x86={rr or 'Ø'} | aarch64={ar or 'Ø'}\n")
    report.write("\n")
    return False

# ───── 收集用例 ─────
def collect_cases(selected_dirs):
    if not selected_dirs:
        dirs = [RES_DIR / d for d in ("functional", "hidden_functional",
                                      "performance", "final_performance")]
    else:
        dirs = [RES_DIR / d for d in selected_dirs]
    miss = [d for d in dirs if not d.exists()]
    if miss:
        sys.exit("❌ 指定目录不存在: " + ", ".join(str(p) for p in miss))
    cases = []
    for d in dirs: cases += d.rglob("*.sy")
    return cases

# ───── main ─────
def main():
    ap = argparse.ArgumentParser("Run E2E SysY tests")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--dir",  nargs="*", metavar="FOLDER")
    g.add_argument("--file", metavar="SYSY_FILE")
    args = ap.parse_args()

    if args.file:
        raw = Path(args.file)
        sy  = (raw if raw.is_absolute() else (Path.cwd() / raw)).resolve()
        if not sy.exists():
            p2 = (RES_DIR / raw).resolve()
            if p2.exists(): sy = p2
        if not sy.exists():
            sys.exit(f"❌ file not found: {args.file}")
        cases  = [sy]
        header = f"SINGLE FILE {sy.relative_to(RES_DIR)}"
    else:
        cases  = collect_cases(args.dir)
        header = "Folders: " + (", ".join(args.dir) if args.dir else "ALL")

    with open(REPORT_PATH, "w", encoding="utf-8") as report:
        report.write(f"# E2E Report {datetime.datetime.now().isoformat()}\n")
        report.write(f"# {header}\n\n")

        total = passed = skipped = 0
        for sy in sorted(cases):
            total += 1
            try:
                if test_case(sy, report): passed += 1
            except KeyboardInterrupt:
                skipped += 1
                tag = sy.relative_to(RES_DIR)
                print(f"[SKIP] {tag} (user skipped)")
                report.write(f"[SKIP] {tag} (user skipped)\n")
                continue

        summary = f"\n=== {passed}/{total} tests passed, {skipped} skipped ==="
        print(summary)
        report.write(summary + "\n")

    print(f"📄 详细报告: {REPORT_PATH}")

if __name__ == "__main__":
    main()
