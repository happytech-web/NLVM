#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate IR (no IR diff) and optionally run:

• --run-mode lli     : our.ll/ref.ll + sylib.bc → lli
• --run-mode cross   : ref.s → ARMv7/qemu-arm, our.s → AArch64/qemu-aarch64
  - 两种模式都会输出 per-case ratio；末尾输出平均比值


Assumptions:
  - Reference backend emits ARMv7 (armv7ve).
  - Our backend emits AArch64 (armv8).

Usage
  python scripts/compare-BUAA.py --dir performance --run-mode cross --save-s # 跑后端
  python scripts/compare-BUAA.py --dir performance --run-mode lli # 不跑后端
  python scripts/compare-BUAA.py --file test/resources/performance/01_mm1.sy --run-mode lli # 单文件
"""

from __future__ import annotations
import argparse, datetime, os, re, subprocess, sys, time
from pathlib import Path

# ---- paths ----
ROOT = Path(__file__).resolve().parent.parent
RESOURCE_ROOT = ROOT / "test" / "resources"

RUN_ID = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
OUT_ROOT = ROOT / "scripts" / "out" / "ir-dump" / RUN_ID
OUT_ROOT.mkdir(parents=True, exist_ok=True)
REPORT = OUT_ROOT / "report.txt"

# our compiler
MY_COMPILER_JAR = ROOT / "build" / "libs" / "compiler2025-nlvm-1.0.jar"
ANTLR_JAR = next((ROOT / "lib").glob("antlr-*-complete.jar"), None)
if not ANTLR_JAR:
    sys.exit("❌ lib/ 下未找到 antlr-*-complete.jar")
MY_CP = os.pathsep.join((str(MY_COMPILER_JAR), str(ANTLR_JAR)))

# reference repo
REF_ROOT     = ROOT / "references" / "BUAA"
REF_BUILD_SH = ROOT / "references" / "build-BUAA.sh"
REF_JAR      = REF_ROOT / "build" / "libs" / "ref-compiler.jar"

# toolchains & runtimes
LIB_DIR = ROOT / "sysy-runtime"
SYLIB_C = LIB_DIR / "sylib.c"

# lli
CLANG    = os.environ.get("CLANG", "clang")
LLVMLINK = os.environ.get("LLVMLINK", "llvm-link")
LLI      = os.environ.get("LLI", "lli")
SYLIB_BC = LIB_DIR / "x86-64" / "sylib.bc"

# AArch64 (ours)
CC_A64     = os.environ.get("AARCH64_CC", "aarch64-unknown-linux-gnu-gcc")
QEMU_A64   = os.environ.get("QEMU_A64", "qemu-aarch64")
QEMU_A64_SYSROOT = os.environ.get("QEMU_A64_SYSROOT", "/usr/aarch64-linux-gnu")
LIBSYSY_A64 = LIB_DIR / "aarch64" / "libsysy.a"   # 若不存在则回退直接用 sylib.c

# ARMv7 (reference)
CC_ARM     = os.environ.get("ARM_CC", "armv7l-unknown-linux-gnueabihf-gcc")
QEMU_ARM   = os.environ.get("QEMU_ARM", "qemu-arm")
QEMU_ARM_SYSROOT = os.environ.get("QEMU_ARM_SYSROOT", "/usr/arm-linux-gnueabihf")
LIBSYSY_ARM = LIB_DIR / "armv7" / "libsysy.a"      # 若不存在则回退直接用 sylib.c

# ---- helpers ----
def sh(cmd, cwd: Path | None = None, check=True):
    return subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=check)

def jar_outdated(jar: Path, src_roots: list[Path]) -> bool:
    if not jar.exists(): return True
    jar_t = jar.stat().st_mtime
    latest = 0.0
    for root in src_roots:
        if not root.exists(): continue
        for p in root.rglob("*.java"): latest = max(latest, p.stat().st_mtime)
        for p in root.rglob("*.g4"):   latest = max(latest, p.stat().st_mtime)
    return latest > jar_t

def ensure_our_jar():
    if jar_outdated(MY_COMPILER_JAR, [ROOT / "src", ROOT / "gen"]):
        print("[info] rebuilding our compiler JAR …")
        gradlew = ROOT / "gradlew"
        if gradlew.exists(): sh([str(gradlew), "-q", "jar"], cwd=ROOT)
        else:                sh(["gradle", "-q", "jar"], cwd=ROOT)
        if not MY_COMPILER_JAR.exists():
            sys.exit("❌ our jar not found after build")

def ensure_ref_jar():
    if not REF_ROOT.exists():
        sys.exit(f"❌ reference repo not found: {REF_ROOT}")
    if not REF_BUILD_SH.exists():
        sys.exit(f"❌ build.sh not found: {REF_BUILD_SH}")
    sh(["bash", str(REF_BUILD_SH)])
    if not REF_JAR.exists():
        sys.exit(f"❌ reference jar not found after build: {REF_JAR}")

_TOTAL_RE = re.compile(r"TOTAL:\s*(\d+)H-(\d+)M-(\d+)S-(\d+)us", re.I)
def parse_total_us(text: str) -> int:
    m = _TOTAL_RE.search(text)
    if not m: return -1
    h, m_, s, us = map(int, m.groups())
    return ((h*60 + m_) * 60 + s) * 1_000_000 + us

def run_capture(cmd: list[str], stdin_file: Path | None, cwd: Path | None = None):
    fin = open(stdin_file, "rb") if stdin_file and stdin_file.exists() else subprocess.DEVNULL
    try:
        t0 = time.perf_counter()
        r = subprocess.run(cmd, cwd=str(cwd) if cwd else None,
                           stdin=fin, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           text=True)
        t1 = time.perf_counter()
        return r.returncode, r.stdout, (t1 - t0)
    finally:
        if fin is not subprocess.DEVNULL: fin.close()

# ---- generators ----
def gen_our_ll(sy: Path, out_ll: Path):
    out_ll.parent.mkdir(parents=True, exist_ok=True)
    sh(["java", "-Xss1024m", "-cp", MY_CP, "Compiler", "-O1",
        str(sy), "-emit-llvm", "-o", str(out_ll)])

def gen_our_s(sy: Path, out_s: Path):
    out_s.parent.mkdir(parents=True, exist_ok=True)
    sh(["java", "-Xss1024m", "-cp", MY_CP, "Compiler", "-O1", "-S", "-o", str(out_s), str(sy)])

def gen_ref_ll_and_s(sy: Path, case_dir: Path, O1: bool) -> tuple[Path, Path]:
    case_dir.mkdir(parents=True, exist_ok=True)
    ir = case_dir / "llvm_ir.ll"
    tmp_s = case_dir / "out.s"
    ref_s = case_dir / "ref.s"
    if ir.exists(): ir.unlink()
    if tmp_s.exists(): tmp_s.unlink()

    args = ["java", "-jar", str(REF_JAR)]
    if O1: args.append("-O1")  # default on
    args += ["-o", "out.s", str(sy)]
    sh(args, cwd=case_dir)

    if not ir.exists(): sys.exit(f"❌ reference IR not generated: {ir}")
    if tmp_s.exists(): tmp_s.replace(ref_s)
    return ir, ref_s

# ---- lli mode ----
def ensure_sylib_bc():
    if SYLIB_BC.exists(): return
    print("[info] building sylib.bc …")
    SYLIB_BC.parent.mkdir(parents=True, exist_ok=True)
    sh([CLANG, "-O2", "-c", "-emit-llvm", str(SYLIB_C), "-o", str(SYLIB_BC)])

class LinkSkip(Exception):
    """用来指示某侧 IR 在 llvm-link 时报错，需要跳过此用例。"""
    def __init__(self, side: str, reason: str, errfile: Path):
        super().__init__(reason)
        self.side = side
        self.reason = reason
        self.errfile = errfile

def _try_link(inputs: list[str], out_bc: Path, errfile: Path, side: str):
    """调用 llvm-link，失败则把输出写入 errfile 并抛 LinkSkip。"""
    out_bc.parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run([LLVMLINK] + inputs + ["-o", str(out_bc)],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if r.returncode != 0 or not out_bc.exists():
        errfile.write_text(r.stdout or "", encoding="utf-8")
        # 识别一些常见错误关键词，给更友好的 reason
        reason = "invalid IR (llvm-link failed)"
        txt = (r.stdout or "")
        if "does not dominate" in txt or "dominate all uses" in txt:
            reason = "invalid IR: dominance"
        elif "Broken function" in txt or "verify" in txt:
            reason = "invalid IR: verification failed"
        raise LinkSkip(side, reason, errfile)
    return out_bc

def run_mode_lli(our_ll: Path, ref_ll: Path, stdin_path: Path | None, case_dir: Path):
    ensure_sylib_bc()
    ref_link = case_dir / "ref.link.bc"
    our_link = case_dir / "our.link.bc"

    # 分别尝试 link，任何一侧失败都抛 LinkSkip
    ref_err = case_dir / "ref.link.err"
    our_err = case_dir / "our.link.err"
    _try_link([str(ref_ll), str(SYLIB_BC)], ref_link, ref_err, side="ref")
    _try_link([str(our_ll), str(SYLIB_BC)], our_link, our_err, side="our")

    rc_ref, out_ref, wall_ref = run_capture([LLI, str(ref_link)], stdin_path, cwd=case_dir)
    rc_our, out_our, wall_our = run_capture([LLI, str(our_link)], stdin_path, cwd=case_dir)
    tot_ref = parse_total_us(out_ref);  tot_ref = tot_ref if tot_ref >= 0 else int(wall_ref * 1_000_000)
    tot_our = parse_total_us(out_our);  tot_our = tot_our if tot_our >= 0 else int(wall_our * 1_000_000)
    return (rc_ref, tot_ref, out_ref), (rc_our, tot_our, out_our)

# ---- cross mode (ref=armv7, our=aarch64) ----
def link_and_run_aarch64(our_s: Path, stdin_path: Path | None, case_dir: Path):
    our_elf = case_dir / "our.a64.elf"
    rt_inputs = [str(LIBSYSY_A64)] if LIBSYSY_A64.exists() else [str(SYLIB_C)]
    sh([CC_A64, "-static", "-O2", "-fno-pie", str(our_s)] + rt_inputs + ["-I", str(LIB_DIR), "-o", str(our_elf)])
    qemu = [QEMU_A64] + (["-L", QEMU_A64_SYSROOT] if QEMU_A64_SYSROOT else [])
    return run_capture(qemu + [str(our_elf)], stdin_path, cwd=case_dir)

def link_and_run_armv7(ref_s: Path, stdin_path: Path | None, case_dir: Path):
    ref_elf = case_dir / "ref.arm.elf"
    common_flags = ["-static", "-O2", "-fno-pie", "-march=armv7ve", "-mfpu=vfpv3-d16", "-mfloat-abi=hard"]
    rt_inputs = [str(LIBSYSY_ARM)] if LIBSYSY_ARM.exists() else [str(SYLIB_C)]
    sh([CC_ARM] + common_flags + [str(ref_s)] + rt_inputs + ["-I", str(LIB_DIR), "-o", str(ref_elf)])
    qemu = [QEMU_ARM] + (["-L", QEMU_ARM_SYSROOT] if QEMU_ARM_SYSROOT else [])
    return run_capture(qemu + [str(ref_elf)], stdin_path, cwd=case_dir)

# ---- perf buckets ----
perf_lli: list[tuple[str, int, int]] = []    # (tag, ref_us, our_us)
perf_cross: list[tuple[str, int, int]] = []  # (tag, ref_us, our_us)

# ---- collect ----
def collect_cases(file_arg: str | None, dirs: list[str] | None) -> list[Path]:
    if file_arg:
        p = Path(file_arg)
        if not p.is_absolute(): p = (Path.cwd() / p).resolve()
        if not p.exists():
            alt = (RESOURCE_ROOT / file_arg).resolve()
            if alt.exists(): p = alt
        if not p.exists(): sys.exit(f"❌ file not found: {file_arg}")
        return [p]
    buckets = dirs if dirs else ["functional", "hidden_functional", "performance", "final_performance"]
    cases: list[Path] = []
    for b in buckets:
        d = RESOURCE_ROOT / b
        if d.exists(): cases += list(d.rglob("*.sy"))
    return sorted(cases)

# ---- one case ----
def do_case(sy: Path, args) -> str:
    rel = sy.relative_to(RESOURCE_ROOT) if sy.is_relative_to(RESOURCE_ROOT) else Path(sy.name)
    case_dir = OUT_ROOT / rel.parent / sy.stem
    case_dir.mkdir(parents=True, exist_ok=True)

    our_ll = case_dir / "our.ll"
    ref_ll = case_dir / "ref.ll"
    our_s  = case_dir / "our.s"
    ref_s  = case_dir / "ref.s"

    if args.skip_existing and our_ll.exists() and ref_ll.exists():
        print(f"[SKIP] {rel} (exists)")
        return "SKIP"

    # gen IR
    try:
        gen_our_ll(sy, our_ll)
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {rel} (our IR) -> {e}"); return "FAIL"
    try:
        ref_ir, _ = gen_ref_ll_and_s(sy, case_dir, args.O1)
        ref_ir.replace(ref_ll)
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] {rel} (ref IR) -> {e}"); return "FAIL"

    # need .s?
    if args.save_s or args.run_mode == "cross":
        try:
            gen_our_s(sy, our_s)
        except subprocess.CalledProcessError as e:
            print(f"[FAIL] {rel} (our .s) -> {e}"); return "FAIL"
        if not ref_s.exists():
            print(f"[FAIL] {rel} (ref .s missing: {ref_s})"); return "FAIL"

    # run
    if args.run_mode == "lli":
        stdin_path = sy.with_suffix(".in")
        try:
            (rc_ref, us_ref, out_ref), (rc_our, us_our, out_our) = run_mode_lli(our_ll, ref_ll, stdin_path, case_dir)
        except LinkSkip as e:
            # 只跳过，给出提示，写入报告，不中断总体流程
            print(f"[SKIP lli] {rel}  ({e.side} {e.reason})  see {e.errfile}")
            with open(REPORT, "a", encoding="utf-8") as rpt:
                rpt.write(f"SKIP lli {rel} ({e.side} {e.reason}) err={e.errfile}\n")
            return "SKIP"

        ratio = (us_our / us_ref) if us_ref > 0 else float("inf")
        print(f"[TIME lli]   {rel}  our={us_our/1e6:.3f}s  ref={us_ref/1e6:.3f}s  ratio={ratio:.2f}×")
        (case_dir / "run.ref.out").write_text(out_ref, encoding="utf-8")
        (case_dir / "run.our.out").write_text(out_our, encoding="utf-8")
        with open(REPORT, "a", encoding="utf-8") as rpt:
            rpt.write(f"TIME lli {rel} our={us_our/1e6:.3f}s ref={us_ref/1e6:.3f}s ratio={ratio:.2f}×\n")
        perf_lli.append((str(rel), us_ref, us_our))

    elif args.run_mode == "cross":
        stdin_path = sy.with_suffix(".in")
        rc_ref, out_ref, wall_ref = link_and_run_armv7(ref_s, stdin_path, case_dir)
        rc_our, out_our, wall_our = link_and_run_aarch64(our_s, stdin_path, case_dir)
        us_ref = parse_total_us(out_ref);  us_ref = us_ref if us_ref >= 0 else int(wall_ref * 1_000_000)
        us_our = parse_total_us(out_our);  us_our = us_our if us_our >= 0 else int(wall_our * 1_000_000)
        ratio  = (us_our / us_ref) if us_ref > 0 else float("inf")
        print(f"[TIME cross] {rel}  our(a64)={us_our/1e6:.3f}s  ref(armv7)={us_ref/1e6:.3f}s  ratio={ratio:.2f}× (ISA-diff)")
        (case_dir / "run.ref.out").write_text(out_ref, encoding="utf-8")
        (case_dir / "run.our.out").write_text(out_our, encoding="utf-8")
        with open(REPORT, "a", encoding="utf-8") as rpt:
            rpt.write(f"TIME cross {rel} our(a64)={us_our/1e6:.3f}s ref(armv7)={us_ref/1e6:.3f}s ratio={ratio:.2f}× (ISA-diff)\n")
        perf_cross.append((str(rel), us_ref, us_our))

    print(f"[OK] {rel} → our.ll, ref.ll" + (" (+our.s,ref.s)" if (args.save_s or args.run_mode == "cross") else ""))
    return "OK"

# ---- main ----
def main():
    ap = argparse.ArgumentParser(description="IR dump (+ optional run & time: lli / cross)")
    ap.add_argument("--file", help="single .sy file")
    ap.add_argument("--dirs", nargs="*", help="subset folders under test/resources")
    ap.add_argument("--no-O1", dest="O1", action="store_false", help="disable -O1 for reference (default: on)")
    ap.set_defaults(O1=True)
    ap.add_argument("--save-s", action="store_true", help="keep our.s / ref.s")
    ap.add_argument("--skip-existing", action="store_true", help="skip if our.ll & ref.ll exist")
    ap.add_argument("--run-mode", choices=["lli","cross"], help="how to run & time programs")
    args = ap.parse_args()

    ensure_our_jar()
    ensure_ref_jar()

    with open(REPORT, "w", encoding="utf-8") as rpt:
        rpt.write(f"# IR Dump Report {datetime.datetime.now().isoformat()}\n")
        rpt.write(f"# RUN MODE: {args.run_mode or 'none'}\n")
        rpt.write(f"# REF OPT:  {'-O1' if args.O1 else '-O0'}\n\n")

    cases = collect_cases(args.file, args.dirs)
    if not cases: print("No cases found."); return

    total=ok=skipped=failed=0
    for sy in cases:
        try:
            status = do_case(sy, args)
        except KeyboardInterrupt:
            rel = sy.relative_to(RESOURCE_ROOT) if sy.is_relative_to(RESOURCE_ROOT) else sy.name
            print(f"[SKIP] {rel} (Ctrl-C)")
            status = "SKIP"

        total += 1
        if status == "OK": ok += 1
        elif status == "SKIP": skipped += 1
        else: failed += 1

        with open(REPORT, "a", encoding="utf-8") as rpt:
            rel = sy.relative_to(RESOURCE_ROOT) if sy.is_relative_to(RESOURCE_ROOT) else Path(sy.name)
            rpt.write(f"{status:5s} {rel}\n")

    summary = f"\n=== OK:{ok}  SKIP:{skipped}  FAIL:{failed}  TOTAL:{total} ==="
    print(summary)

    # 平均比值汇总
    if perf_lli:
        avg_ref = sum(r for _, r, _ in perf_lli) / len(perf_lli)
        avg_our = sum(o for _, _, o in perf_lli) / len(perf_lli)
        ratio   = (avg_our / avg_ref) if avg_ref else float("inf")
        line = f"⏱️ LLI AVG   our={avg_our/1e6:.3f}s  ref={avg_ref/1e6:.3f}s  ratio≈{ratio:.2f}×"
        print(line)
        with open(REPORT, "a", encoding="utf-8") as rpt: rpt.write(line + "\n")

    if perf_cross:
        avg_ref = sum(r for _, r, _ in perf_cross) / len(perf_cross)
        avg_our = sum(o for _, _, o in perf_cross) / len(perf_cross)
        ratio   = (avg_our / avg_ref) if avg_ref else float("inf")
        line = f"⏱️ CROSS AVG our(a64)={avg_our/1e6:.3f}s  ref(armv7)={avg_ref/1e6:.3f}s  ratio≈{ratio:.2f}× (ISA-diff)"
        print(line)
        with open(REPORT, "a", encoding="utf-8") as rpt: rpt.write(line + "\n")

    with open(REPORT, "a", encoding="utf-8") as rpt:
        rpt.write(summary + "\n")
        rpt.write(f"Out dir: {OUT_ROOT}\n")

    print(f"📄 Report: {REPORT}")
    print(f"📂 Outputs under: {OUT_ROOT}")

if __name__ == "__main__":
    main()

