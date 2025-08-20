#!/usr/bin/env python3
"""
compare_pgm.py  usage:
    python compare_pgm.py 36_rotate.run.out 36_rotate.out

- 打印头部信息（宽、高、maxVal）
- 找到第一处 / 最后一处像素差
- 统计总差异点数、最大/平均误差
- 额外生成差分 PGM，白=相同，越黑=差异越大，方便用 ImageMagick / VSCode 预览
"""
import re, sys, pathlib, itertools, statistics

def load_p2(path: pathlib.Path):
    txt = path.read_text()
    nums = list(map(int, re.findall(r"-?\d+", txt)))
    if nums[0] == 2:            # 跳过 “P2” 里的那个 2
        nums = nums[1:]
    w, h, maxv, *pix = nums
    assert len(pix) >= w*h, f"{path} too few pixels?"
    return w, h, maxv, pix[:w*h]

def diff(a, b, w):
    for i, (pa, pb) in enumerate(zip(a, b)):
        if pa != pb:
            y, x = divmod(i, w)
            return i, x, y, pa, pb
    return None

def main(p_run, p_ref):
    w1,h1,m1,pix1 = load_p2(p_run)
    w2,h2,m2,pix2 = load_p2(p_ref)
    print(f"RUN : {p_run.name}  -> {w1}×{h1}  max={m1}  pixels={len(pix1)}")
    print(f"REF : {p_ref.name}  -> {w2}×{h2}  max={m2}  pixels={len(pix2)}")
    if (w1,h1)!=(w2,h2):
        print("‼️  尺寸不一致，后面就没法比了"); return
    if len(pix1)!=len(pix2):
        print(f"‼️  像素数不同 (run:{len(pix1)}  ref:{len(pix2)}) — 可能多打印/少打印？")

    first = diff(pix1, pix2, w1)
    if first:
        idx,x,y,pa,pb = first
        print(f"🔸 first diff @ (x={x}, y={y})  run={pa}  ref={pb}  (flat idx {idx})")
    else:
        print("✅  两份输出完全一致!")
        return

    # 全量统计
    diffs = [abs(a-b) for a,b in zip(pix1,pix2) if a!=b]
    print(f"总差异 {len(diffs)} 像素;  maxΔ={max(diffs)},  meanΔ={statistics.mean(diffs):.2f}")

    # 生成差分图（同尺寸 PGM）
    diff_pix = [m1 - abs(a-b) for a,b in itertools.zip_longest(pix1,pix2,fillvalue=0)]
    diff_txt = "P2\n{} {}\n{}\n{}\n".format(w1,h1,m1," ".join(map(str,diff_pix)))
    diff_path = p_run.with_suffix(".diff.pgm")
    diff_path.write_text(diff_txt)
    print(f"差分图已写入 {diff_path}  (白=相同, 越黑=差异越大)")

if __name__ == "__main__":
    if len(sys.argv)!=3: print("python compare_pgm.py <run.out> <ref.out>"); sys.exit(1)
    main(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
