package pass;

import java.util.function.Supplier;
import pass.MCPass.*;
import pass.Pass.*;

/*
 * MCPassFactory: create the MCPass here
 */
public enum MCPassType implements PassType<MCPass> {
    InstSimplify(InstSimplifyPass::new),
    RegAllocPass(RegAllocPass::new),
    FrameLoweringPass(FrameLowerPass::new),
    PostRASpillAddrProp(PostRASpillAddrPropPass::new),
    // add more mcpass here
    ;

    private final Supplier<MCPass> supplier;

    MCPassType(Supplier<MCPass> constructor) {
        this.supplier = constructor;
    }

    @Override
    public Supplier<MCPass> constructor() {
        return supplier;
    }
}
