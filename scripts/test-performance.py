#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
前后端联合测试（E2E）
1. SysY → C → aarch64 (cross-gcc) → qemu-aarch64      （参考实现）
2. SysY → .ll + .s → ELF → qemu-aarch64（本编译器）
3. 比对 stdout / return code；在 performance 阶段统计运行时间差异
"""

import argparse, datetime, difflib, os, re, subprocess, sys
from pathlib import Path

# ───── 固定路径 ─────
ROOT         = Path(__file__).resolve().parent.parent
COMPILER_JAR = ROOT / "build/libs/compiler2025-nlvm-1.0.jar"
ANTLR_JAR    = ROOT / "lib/antlr-4.12.0-complete.jar"

RES_DIR      = ROOT / "test/resources"
C_TMP_DIR    = ROOT / "test/tmp_c"
LIB_DIR      = ROOT / "sysy-runtime"
OUT_DIR      = ROOT / "scripts/out-e2e"

CROSS_GCC    = "aarch64-unknown-linux-gnu-gcc"
QEMU         = "qemu-aarch64"

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

# ───── 编译器 jar 是否需重建 ─────
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

# ───── 过滤计时行 ─────
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
        stripped = _TIMER_RE.sub('', line)
        stripped = re.sub(r'[ \t]{2,}', ' ', stripped).rstrip()
        out.append(stripped)
    return out

# ───── TOTAL 解析 / 性能收集 ─────
def parse_total(us_line: str) -> int:
    """
    将形如 “TOTAL: 0H-0M-5S-32610us” 解析为 **微秒**；找不到返回 -1
    """
    m = re.search(r'TOTAL:\s*(\d+)H-(\d+)M-(\d+)S-(\d+)us', us_line)
    if not m:
        return -1
    h, m_, s, us = map(int, m.groups())
    return ((h * 60 + m_) * 60 + s) * 1_000_000 + us

perf_data: list[tuple[str, int, int]] = []   # (tag, ref_us, run_us)

def split_lines(lines):
    if '---' in lines:
        i = lines.index('---')
        return lines[:i], lines[i + 1] if i + 1 < len(lines) else ''
    return lines, ''


def gen_c(sy: Path) -> Path:
    """
    生成参考侧 .c：
      1) 加 #include "sylib.h"
      2) 把 'const int NAME = EXPR;' 改成 'enum { NAME = EXPR };' 以便全局数组维度合法
      3) 识别多维数组声明 (int/float)，将 get{,f}array(A) / put{,f}array(n, A)
         自动改写为 get{,f}array(&A[0]...[0]) / put{,f}array(n, &A[0]...[0])
    """
    tgt = C_TMP_DIR / sy.relative_to(RES_DIR)  # 如果在 test-performance-ir 里，改成 RESOURCE_ROOT
    tgt = tgt.with_suffix(".c")
    tgt.parent.mkdir(parents=True, exist_ok=True)

    src = sy.read_text()

    # 1) const int → enum { ... };
    const_int_re = re.compile(r'^\s*const\s+int\s+([A-Za-z_]\w*)\s*=\s*([^;]+);', re.M)
    src = const_int_re.sub(r'enum { \1 = \2 };', src)

    # 2) 收集多维数组声明：float/int 名称 + 维度数
    #    只处理简单声明行（无初始化），足够覆盖绝大多数 SysY 用例
    #    例: "float input[1500][1500];"  "int a[10][20][30];"
    md_decl_re = re.compile(
        r'^\s*(float|int)\s+([A-Za-z_]\w*)\s*((?:\s*\[[^\]]+\])+)\s*;', re.M)
    # name -> dims_count
    array_dims: dict[str, int] = {}
    for m in md_decl_re.finditer(src):
        basety, name, dims_blob = m.groups()
        dims = re.findall(r'\[[^\]]+\]', dims_blob)
        if len(dims) >= 2:  # 仅多维（≥2）
            array_dims[name] = len(dims)

    if array_dims:
        # 构造把 A → &A[0]...[0] 的映射（按各自维度个数）
        def addr_of_zero(name: str) -> str:
            return '&' + name + ''.join('[0]' for _ in range(array_dims[name]))

        # 3a) getarray / getfarray 单实参： getfarray(A) → getfarray(&A[0]...[0])
        #     仅替换我们收集到的多维数组名字
        names_alt = '|'.join(map(re.escape, array_dims.keys()))
        get_pat = re.compile(rf'\b(getfarray|getarray)\s*\(\s*({names_alt})\s*\)')
        src = get_pat.sub(lambda m: f"{m.group(1)}({addr_of_zero(m.group(2))})", src)

        # 3b) putarray / putfarray 二实参： putfarray(n, A) → putfarray(n, &A[0]...[0])
        put_pat = re.compile(rf'\b(putfarray|putarray)\s*\(\s*([^,]+?)\s*,\s*({names_alt})\s*\)')
        def _put_sub(m):
            fun = m.group(1)
            narg = m.group(2).strip()
            name = m.group(3)
            return f"{fun}({narg}, {addr_of_zero(name)})"
        src = put_pat.sub(_put_sub, src)

    with open(tgt, "w") as o:
        o.write('#include "sylib.h"\n')
        o.write(src)
    return tgt



# ───── 单用例 ─────
def test_case(sy: Path, report) -> bool:
    rel = sy.relative_to(RES_DIR)
    tag = str(rel)

    outd = OUT_DIR / rel.parent
    outd.mkdir(parents=True, exist_ok=True)

    base     = sy.stem
    elf_ref  = outd / f"{base}.ref.elf"
    ref_out  = outd / f"{base}.ref.out"
    asm_a64  = outd / f"{base}.s"
    llvm_out = outd / f"{base}.ll"   # Driver 会自动写这个文件
    elf_a64  = outd / f"{base}.elf"
    run_out  = outd / f"{base}.run.out"
    stdin    = sy.with_suffix(".in")

    # --- 1. 参考结果 (aarch64 via qemu) ---
    try:
        run([
            CROSS_GCC,
            "-w", "-fcommon", "-O2",
            str(gen_c(sy)),
            str(LIBSYSY_A64),
            "-I", str(LIB_DIR),
            "-static", "-fno-pie",
            "-o", elf_ref
        ])
        with open(ref_out, "w") as f:
            rc_ref = run_prog([QEMU, elf_ref], stdin, f)
        ref_out.write_text(ref_out.read_text() + f"\n---\nRETVAL={rc_ref}\n")
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {tag} (ref-aarch64)")
        report.write(f"[REF AARCH64 FAIL] {tag}\n{e}\n")
        return False

    # --- 2. 调用编译器：一次生成 .ll + .s ---
    try:
        run(["java", "-Xss1024m", "-cp", JAVA_CP,
             "Compiler", str(sy), "-O1",
             "-emit-llvm", "-S",
             "-o", str(asm_a64)])
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {tag} (compiler)")
        report.write(f"[COMPILER FAIL] {tag}\n{e}\n")
        return False

    # --- 3. 链接 & 运行（本编译器产物） ---
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

    # 额外：提取 TOTAL 用时
    tot_ref_us = next((parse_total(l) for l in ro if 'TOTAL:' in l), -1)
    tot_run_us = next((parse_total(l) for l in ao if 'TOTAL:' in l), -1)
    if tot_ref_us > 0 and tot_run_us > 0:
        perf_data.append((tag, tot_ref_us, tot_run_us))

    ro, ao = clean(ro), clean(ao)

    if ro == ao and rr == ar:
        print(f"[PASS] {tag}")
        return True

    print(f"[FAIL] {tag} (see report)")
    report.write(f"[FAIL] {tag}\n")
    if ro != ao:
        report.write("  ↳ stdout diff:\n")
        report.write('\n'.join(difflib.unified_diff(
            ro, ao, 'ref-aarch64', 'our-aarch64', lineterm='')) or "   (one side empty)\n")
    if rr != ar:
        report.write(f"  ↳ return value: ref={rr or 'Ø'} | our={ar or 'Ø'}\n")
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
    for d in dirs:
        cases += d.rglob("*.sy")
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
            if p2.exists():
                sy = p2
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
                if test_case(sy, report):
                    passed += 1
            except KeyboardInterrupt:
                skipped += 1
                tag = sy.relative_to(RES_DIR)
                print(f"[SKIP] {tag} (user skipped)")
                report.write(f"[SKIP] {tag} (user skipped)\n")
                continue

        summary = f"\n=== {passed}/{total} tests passed, {skipped} skipped ==="
        print(summary)
        report.write(summary + "\n")

        # ───── 性能汇总 ─────
        if perf_data:
            avg_ref = sum(r for _, r, _ in perf_data) / len(perf_data)
            avg_run = sum(r for _, _, r in perf_data) / len(perf_data)
            ratio   = avg_run / avg_ref
            perf_head = (f"\n⏱️  平均运行时间 our-AArch64/qemu : ref-AArch64/qemu "
                         f"= {avg_run/1e6:.3f}s / {avg_ref/1e6:.3f}s "
                         f"(ratio ≈ {ratio:.2f}×)\n")
            print(perf_head.strip())
            with open(REPORT_PATH, "a", encoding="utf-8") as report2:
                report2.write(perf_head)
                for tag, ref_us, run_us in perf_data:
                    report2.write(f"{tag}: {run_us/1e6:.3f}s vs "
                                  f"{ref_us/1e6:.3f}s "
                                  f"({run_us/ref_us:.2f}×)\n")

    print(f"📄 详细报告: {REPORT_PATH}")

if __name__ == "__main__":
    main()
