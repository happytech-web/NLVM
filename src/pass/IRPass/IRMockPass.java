package pass.IRPass;

import pass.IRPassType;
import pass.Pass.IRPass;

public class IRMockPass implements IRPass {
    public IRMockPass() {
    }

    @Override
    public IRPassType getType() {
        return IRPassType.IRMockPass;
    }

    @Override
    public void run() {/* do nothing */}
}
