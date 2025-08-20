package backend.mir.inst;

import backend.mir.operand.Operand;

import java.util.*;

public class PlaceHolder extends Inst {

    // 内存指令集合，用于快速判断一个指令是否属于内存指令
    public static final Set<Mnemonic> PLACE_HOLER_SET = new HashSet<>(
            Arrays.asList(Mnemonic.RESTORE_PSEUDO, Mnemonic.SAVE_PSEUDO)
    );

    public PlaceHolder(Mnemonic mnemonic) {
        super(mnemonic);
    }
    @Override
    public List<Operand> getDefs() {
        return new ArrayList<>();
    }

    @Override
    public List<Operand> getUses() {
        return new ArrayList<>();
    }

    @Override
    public List<Operand> getOperands() {
        return new ArrayList<>();
    }

    @Override
    public boolean validate() {
        return true;
    }

    @Override
    public Inst clone() {
        return new PlaceHolder(mnemonic);
    }

    @Override
    public String toString() {
        return mnemonic.getText();
    }
}
