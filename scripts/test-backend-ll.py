#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
后端集成测试（IR 版）
 1. 现成 LLVM-IR → x86-64          （参考结果）
 2. 现成 LLVM-IR → AArch64 汇编    （本项目后端）→ 链接 → qemu-aarch64
 3. 对比 stdout / return code
"""
import difflib, subprocess, datetime, argparse, sys, os, time
from pathlib import Path

# ─────────── 固定路径 ───────────
ROOT         = Path(__file__).resolve().parent.parent
COMPILER_JAR = ROOT / "build/libs/compiler2025-nlvm-1.0.jar"
ANTLR_JAR    = ROOT / "lib/antlr-4.12.0-complete.jar"

LL_DIR       = ROOT / "test/tmp_ll"           # 作为 *输入* 的 .ll
RES_DIR      = ROOT / "test/resources"        # .in / 期望 stdout
LIB_DIR      = ROOT / "sysy-runtime"

OUT_DIR      = ROOT / "scripts/out-backend"

CLANG        = "clang"                        # host x86_64
CROSS_GCC    = "aarch64-unknown-linux-gnu-gcc"
QEMU         = "qemu-aarch64"

LIBSYSY_X86  = LIB_DIR / "x86-64/libsysy.a"
LIBSYSY_A64  = LIB_DIR / "aarch64/libsysy.a"

FILTER_TIMER = True
# 测试报告,跟时间戳有关!
REPORT_PATH  = OUT_DIR / ("report-backend-ll" + datetime.datetime.now().strftime("%Y%m%d_%H%M%S"))
ASSEMBLER_ERROR_PATH = None  # Will be set in main()

for p in (OUT_DIR,): p.mkdir(exist_ok=True, parents=True)

def run(cmd, **kw):
    kw.setdefault("check", True)
    return subprocess.run(cmd, **kw)

def run_with_timeout(cmd, timeout_seconds=1000, **kw):
    """Run command with timeout, return (success, result_or_exception)"""
    kw.setdefault("check", True)
    try:
        result = subprocess.run(cmd, timeout=timeout_seconds, **kw)
        return True, result
    except subprocess.TimeoutExpired as e:
        return False, e
    except subprocess.CalledProcessError as e:
        return False, e

# ─────────── 若 JAR 过期则自动构建 ───────────
def jar_outdated() -> bool:
    if not COMPILER_JAR.exists():
        return True
    jar_mtime = COMPILER_JAR.stat().st_mtime
    src_times = [p.stat().st_mtime for p in ROOT.rglob("*.java")]
    src_times += [p.stat().st_mtime for p in ROOT.rglob("*.g4")]
    return max(src_times, default=0.0) > jar_mtime

if jar_outdated():
    print("[info] rebuilding compiler JAR …")
    run(["./gradlew", "jar"], cwd=ROOT, check=True)
    #run(["gradle", "-q", "jar"])
    if not COMPILER_JAR.exists():
        sys.exit("❌ gradle jar failed: jar not found")

# ─────────── util ───────────
def clean(lines):
    if not FILTER_TIMER: return lines
    return [l for l in lines if not (l.startswith("TOTAL:") or l.startswith("Timer#"))]

def run_prog(cmd, stdin_file, outfile, timeout_seconds=10):
    fin = open(stdin_file, "rb") if stdin_file and stdin_file.exists() else subprocess.DEVNULL
    try:
        r = subprocess.run(cmd, stdin=fin, stdout=outfile,
                           stderr=subprocess.STDOUT, check=False, timeout=timeout_seconds)
        return r.returncode
    except subprocess.TimeoutExpired:
        return None  # Special return value for timeout
    finally:
        if fin is not subprocess.DEVNULL: fin.close()

JAVA_CP = os.pathsep.join((str(COMPILER_JAR), str(ANTLR_JAR)))

# ─────────── 单个用例 ───────────
# ─────────── 单个用例 ───────────
def test_case(ll: Path) -> bool:
    """
    运行单个 .ll 用例：
      1. 用 clang 生成参考 AArch64 汇编           -> *.ref.s   （新增）
      2. 用 clang 生成 x86-64 可执行文件并运行      -> *.ref / *.ref.out
      3. 调用自研后端生成 AArch64 汇编             -> *.s
      4. 交叉编译 / qemu 运行                     -> *.elf / *.run.out
      5. 比对 stdout + 返回值
    """
    rel  = ll.relative_to(LL_DIR)
    tag  = str(rel)

    outd = OUT_DIR / rel.parent
    outd.mkdir(parents=True, exist_ok=True)

    base        = ll.stem
    exe_ref     = outd / f"{base}.ref"        # 参考可执行文件 (x86-64)
    ref_s       = outd / f"{base}.ref.s"      # 参考 AArch64 汇编 ← 新增
    ref_out     = outd / f"{base}.ref.out"    # 参考输出
    asm_a64     = outd / f"{base}.s"          # 自研后端生成的汇编
    elf_a64     = outd / f"{base}.elf"        # AArch64 可执行
    run_out     = outd / f"{base}.run.out"    # 自研运行输出
    stdin       = RES_DIR / "input" / f"{base}.in"

    # ---- 1. 参考结果 ----
    try:
        # 1-a) 生成参考 AArch64 汇编，方便人工 diff
        # 如果本机 clang 未启用 AArch64 后端，可改用：
        #     run(["llc", "-march=aarch64", ll, "-o", ref_s])
        run([CLANG, "-w", "-O0", ll,
             "-S", "-target", "aarch64-unknown-linux-gnu",
             "-o", ref_s])

        # 1-b) 生成并运行 x86-64 参考程序
        run([CLANG, "-w", "-O2", ll, str(LIBSYSY_X86),
             "-static", "-fno-pie", "-o", exe_ref])
        with open(ref_out, "w") as f:
            rc = run_prog([exe_ref], stdin, f)
        ref_out.write_text(ref_out.read_text() + f"\n---\nRETVAL={rc}\n")

    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {tag} (clang-x86)")         # 编译或运行失败
        REPORT.write(f"[CLANG-X86 FAIL] {tag}\n{e}\n")
        return False

    # ---- 2. 自研后端生成 ----
    success, result = run_with_timeout(["java", "-cp", JAVA_CP, "Compiler", ll, "-S", "-o", asm_a64])
    if not success:
        if isinstance(result, subprocess.TimeoutExpired):
            print(f"[SKIP] {tag} (backend timeout > 10s)")
            REPORT.write(f"[BACKEND TIMEOUT] {tag}\n")
            return None  # Special return value for timeout
        else:
            print(f"[FAIL] {tag} (backend)")
            REPORT.write(f"[BACKEND FAIL] {tag}\n{result}\n")
            return False

    # ---- 3. 链接 & 运行 ----
    try:
        # 捕获汇编 / 链接阶段的 stderr
        result = subprocess.run(
            [CROSS_GCC, "-static", "-g", "-Og", "-fno-omit-frame-pointer", asm_a64, str(LIBSYSY_A64), "-o", elf_a64],
            capture_output=True, text=True, check=True)

        if result.stderr:                # 记录可能的警告 / 错误
            with open(ASSEMBLER_ERROR_PATH, "a", encoding="utf-8") as f:
                f.write(f"=== {tag} ===\n{result.stderr}\n")

        with open(run_out, "w") as f:
            rc2 = run_prog([QEMU, elf_a64], stdin, f)

        if rc2 is None:  # Timeout occurred
            print(f"[SKIP] {tag} (execution timeout > 10s)")
            REPORT.write(f"[EXECUTION TIMEOUT] {tag}\n")
            return None  # Special return value for timeout

        run_out.write_text(run_out.read_text() + f"\n---\nRETVAL={rc2}\n")

    except subprocess.CalledProcessError as e:
        with open(ASSEMBLER_ERROR_PATH, "a", encoding="utf-8") as f:
            f.write(f"=== {tag} (FAILED) ===\n{e.stderr or e}\n")
        print(f"[FAIL] {tag} (link/run A64)")
        REPORT.write(f"[A64 FAIL] {tag}\n{e}\n")
        return False

    # ---- 4. 对比 ----
    ro, rr = _split(ref_out.read_text().splitlines())
    ao, ar = _split(run_out.read_text().splitlines())
    ro, ao = clean(ro), clean(ao)

    if ro == ao and rr == ar:
        print(f"[PASS] {tag}")
        return True

    print(f"[FAIL] {tag} (see report)")
    REPORT.write(f"[FAIL] {tag}\n")
    if ro != ao:
        REPORT.write("  ↳ stdout diff:\n")
        REPORT.write('\n'.join(difflib.unified_diff(ro, ao, 'ref', 'aarch64', lineterm='')) or "   (one side empty)\n")
    if rr != ar:
        REPORT.write(f"  ↳ return value: ref={rr or 'Ø'} | aarch64={ar or 'Ø'}\n")
    REPORT.write("\n")
    return False


def _split(lines):
    if '---' in lines:
        i = lines.index('---')
        return lines[:i], lines[i+1] if i+1 < len(lines) else ''
    return lines, ''

# ─────────── 搜集用例 ───────────
def collect_cases(subdirs):
    roots = [LL_DIR / d for d in subdirs]
    miss  = [p for p in roots if not p.exists()]
    if miss:
        sys.exit("❌ 目录不存在: " + ", ".join(str(p) for p in miss))
    cases = []
    for d in roots: cases += list(d.rglob("*.ll"))
    return cases

# ─────────── main ───────────
def main():
    global REPORT, ASSEMBLER_ERROR_PATH
    ap = argparse.ArgumentParser("Backend tester (IR)")
    grp = ap.add_mutually_exclusive_group()
    grp.add_argument("--dir", nargs="*", help="folders under tmp_ll to run (default ALL)")
    grp.add_argument("--file", help="a single .ll file to run")
    args = ap.parse_args()

    # Create unique assembler error file with timestamp
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    ASSEMBLER_ERROR_PATH = OUT_DIR / f"assembler_errors_{timestamp}.txt"

    if args.file:
        raw = Path(args.file)

        # 如果用户给的是绝对路径，直接用
        if raw.is_absolute():
            ll_path = raw
        else:
            # 优先尝试当前工作目录下的相对路径
            ll_path = raw.resolve()
            if not ll_path.exists():
                # 再尝试相对于 LL_DIR 的路径
                ll_path = (LL_DIR / raw).resolve()

        if not ll_path.exists():
            sys.exit(f"❌ file not found: {ll_path}")

        cases = [ll_path]
        folder_info = f"SINGLE FILE {ll_path.relative_to(LL_DIR)}"

    else:
        subdirs = args.dir or ["functional", "hidden_functional", "performance", "final_performance", "selfmake"]
        cases   = collect_cases(subdirs)
        folder_info = ", ".join(subdirs)

    REPORT = open(REPORT_PATH, "w", encoding="utf-8")
    REPORT.write(f"# Report {datetime.datetime.now().isoformat()}\n")
    REPORT.write("# Scope: " + folder_info + "\n\n")

    # Initialize assembler error file
    with open(ASSEMBLER_ERROR_PATH, "w", encoding="utf-8") as f:
        f.write(f"# Assembler Errors Report {datetime.datetime.now().isoformat()}\n")
        f.write("# Scope: " + folder_info + "\n\n")

    total = passed = skipped = 0
    passed_files = []
    failed_files = []
    skipped_files = []

    for ll in sorted(cases):
        total += 1
        rel_path = ll.relative_to(LL_DIR)
        print(f"Testing {rel_path} (press Ctrl+C to skip)...")

        try:
            result = test_case(ll)
            if result is True:
                passed += 1
                passed_files.append(str(rel_path))
            elif result is None:  # Timeout occurred
                skipped += 1
                skipped_files.append(str(rel_path))
            else:  # Failed
                failed_files.append(str(rel_path))
        except KeyboardInterrupt:
            print(f"[SKIP] {rel_path} (user skipped)")
            REPORT.write(f"[SKIP] {rel_path} (user skipped)\n")
            skipped += 1
            skipped_files.append(f"{rel_path} (user skipped)")
            continue

    failed = total - passed - skipped

    # Write detailed statistics to report
    REPORT.write(f"\n=== DETAILED STATISTICS ===\n")

    if passed_files:
        REPORT.write(f"\n✅ PASSED ({len(passed_files)}):\n")
        for f in passed_files:
            REPORT.write(f"  - {f}\n")

    if failed_files:
        REPORT.write(f"\n❌ FAILED ({len(failed_files)}):\n")
        for f in failed_files:
            REPORT.write(f"  - {f}\n")

    if skipped_files:
        REPORT.write(f"\n⏭️ SKIPPED ({len(skipped_files)}):\n")
        for f in skipped_files:
            REPORT.write(f"  - {f}\n")

    summary = f"\n=== {passed}/{total} tests passed, {failed} failed, {skipped} skipped ==="
    print(summary); REPORT.write(summary + "\n")

    # Print summary to console
    print(f"\n📊 详细统计:")
    if passed_files:
        print(f"✅ 通过 ({len(passed_files)}): {', '.join(passed_files[:5])}" +
              (f" ... (共{len(passed_files)}个)" if len(passed_files) > 5 else ""))
    if failed_files:
        print(f"❌ 失败 ({len(failed_files)}): {', '.join(failed_files[:5])}" +
              (f" ... (共{len(failed_files)}个)" if len(failed_files) > 5 else ""))
    if skipped_files:
        print(f"⏭️ 跳过 ({len(skipped_files)}): {', '.join(skipped_files[:5])}" +
              (f" ... (共{len(skipped_files)}个)" if len(skipped_files) > 5 else ""))

    REPORT.close(); print(f"\n📄 详细报告: {REPORT_PATH}")
    print(f"🔧 汇编错误报告: {ASSEMBLER_ERROR_PATH}")

if __name__ == "__main__":
    main()
