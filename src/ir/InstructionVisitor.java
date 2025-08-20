package ir;

import ir.value.instructions.*;

public interface InstructionVisitor<T> {
    T visit(AllocaInst inst);

    T visit(BinOperator inst);

    T visit(BranchInst inst);

    T visit(CallInst inst);

    T visit(CastInst inst);

    T visit(GEPInst inst);

    T visit(ICmpInst inst);

    T visit(FCmpInst inst);

    T visit(LoadInst inst);

    T visit(Phi inst);

    T visit(ReturnInst inst);

    T visit(StoreInst inst);

    T visit(SelectInst inst);

    // Vector instruction visitors
    T visitVectorAddInst(VectorAddInst inst);

    T visitVectorSubInst(VectorSubInst inst);

    T visitVectorMulInst(VectorMulInst inst);

    T visitVectorDivInst(VectorDivInst inst);

    T visitVectorICMPInst(VectorICMPInst inst);

    T visitVectorFCMPInst(VectorFCMPInst inst);

    T visitVectorLoadInst(VectorLoadInst inst);

    T visitVectorStoreInst(VectorStoreInst inst);

    T visitVectorExtractInst(VectorExtractInst inst);

    T visitVectorInsertInst(VectorInsertInst inst);
    
    T visitVectorGEPInst(VectorGEPInst inst);
   
    // add other vector instruction visitors
    T visit(MemPhi inst);
    T visit(LoadDepInst inst);
}
