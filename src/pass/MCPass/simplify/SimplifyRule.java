package pass.MCPass.simplify;

import backend.mir.MachineBlock;

public interface SimplifyRule {
    /** 在一个基本块上尝试一次；如有修改返回 true */
    boolean apply(MachineBlock block);
}
