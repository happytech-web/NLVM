package pass.MCPass.util;

import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;

import java.util.*;

public class LiveInterval {
    private final Register reg;
    private final List<Range> ranges = new ArrayList<>();

    public LiveInterval(Register reg) {
        this.reg = reg;
    }

    // 添加范围
    public void addRange(int start, int end) {
        ranges.add(new Range(start, end));
        mergeRanges();
    }

    // 扩展到某个位置
    //TODO:CHECK
    public void extendTo(int pos) {
        if (ranges.isEmpty()) {
            addRange(pos, pos);
        } else {
            // 找到最早的范围
            ranges.sort(Comparator.comparingInt(Range::start).thenComparingInt(Range::end));

            Range first = ranges.get(0);
            Range last = ranges.get(ranges.size() - 1);

            if (pos < first.start) {
                addRange(pos,first.end);
            } else if (pos > last.end) {
                addRange(last.start, pos);
            } else {
                for (Range range : ranges) {
                    if (pos >= range.start && pos <= range.end) {
                        // 已经在范围内，不需要扩展
                        return;
                    } else {
                        Range nextRange = ranges.get(ranges.indexOf(range) + 1);
                        if (pos < nextRange.start && pos > range.end) {
                            // 在两个范围之间，扩展到这个位置
                            addRange(range.end + 1, pos);
                            return;

                        }
                    }
                }
            }
            mergeRanges();
        }
    }

    // 合并重叠的范围
    private void mergeRanges() {
        if (ranges.size() <= 1) return;

        ranges.sort(Comparator.comparingInt(Range::start).thenComparingInt(Range::end));

        List<Range> merged = new ArrayList<>();
        Range current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            Range next = ranges.get(i);
            if (current.end >= next.start - 1) {
                // 合并
                current = new Range(current.start, Math.max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        ranges.clear();
        ranges.addAll(merged);
    }

    // 检查是否与另一个区间重叠
    public boolean overlaps(LiveInterval other) {
        for (Range r1 : this.ranges) {
            for (Range r2 : other.ranges) {
                if (r1.overlaps(r2)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 获取起始位置
    public int getStart() {
        return ranges.isEmpty() ? Integer.MAX_VALUE : ranges.get(0).start;
    }

    // 获取结束位置
    public int getEnd() {
        return ranges.isEmpty() ? Integer.MIN_VALUE : ranges.get(ranges.size() - 1).end;
    }

    // 检查某个位置是否在活跃区间内
    public boolean covers(int pos) {
        for (Range range : ranges) {
            if (pos >= range.start && pos <= range.end) {
                return true;
            }
        }
        return false;
    }

    // 分割活跃区间（用于溢出处理）
    public LiveInterval splitAt(int pos) {
        LiveInterval newInterval = new LiveInterval(reg);
        List<Range> newRanges = new ArrayList<>();

        for (Range range : ranges) {
            if (range.end <= pos) {
                // 完全在分割点之前
                continue;
            } else if (range.start >= pos) {
                // 完全在分割点之后
                newRanges.add(range);
            } else {
                // 跨越分割点
                ranges.add(new Range(range.start, pos));
                newRanges.add(new Range(pos, range.end));
            }
        }

        // 移除分割后的范围
        ranges.removeIf(r -> r.start >= pos);
        newInterval.ranges.addAll(newRanges);

        return newInterval;
    }

    public Register getReg() {
        return reg;
    }

    public List<Range> getRanges() {
        return new ArrayList<>(ranges);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LiveInterval for ").append(reg.getName()).append(": ");
        for (Range range : ranges) {
            sb.append("[").append(range.start).append(", ").append(range.end).append("] ");
        }
        return sb.toString();
    }

    // 单个范围
    public record Range(int start, int end) {

        public boolean overlaps(Range other) {
            return !(this.end < other.start || other.end < this.start);
        }
    }
}

