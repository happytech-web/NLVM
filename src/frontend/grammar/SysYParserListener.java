package frontend.grammar;

// Generated from /home/ni/Desktop/compiler2025-nlvm/util/SysYParser.g4 by ANTLR 4.12.0
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link SysYParser}.
 */
public interface SysYParserListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link SysYParser#syProgram}.
	 * @param ctx the parse tree
	 */
	void enterSyProgram(SysYParser.SyProgramContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syProgram}.
	 * @param ctx the parse tree
	 */
	void exitSyProgram(SysYParser.SyProgramContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syCompUnit}.
	 * @param ctx the parse tree
	 */
	void enterSyCompUnit(SysYParser.SyCompUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syCompUnit}.
	 * @param ctx the parse tree
	 */
	void exitSyCompUnit(SysYParser.SyCompUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syBType}.
	 * @param ctx the parse tree
	 */
	void enterSyBType(SysYParser.SyBTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syBType}.
	 * @param ctx the parse tree
	 */
	void exitSyBType(SysYParser.SyBTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syModifier}.
	 * @param ctx the parse tree
	 */
	void enterSyModifier(SysYParser.SyModifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syModifier}.
	 * @param ctx the parse tree
	 */
	void exitSyModifier(SysYParser.SyModifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syVarDecl}.
	 * @param ctx the parse tree
	 */
	void enterSyVarDecl(SysYParser.SyVarDeclContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syVarDecl}.
	 * @param ctx the parse tree
	 */
	void exitSyVarDecl(SysYParser.SyVarDeclContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syVarDef}.
	 * @param ctx the parse tree
	 */
	void enterSyVarDef(SysYParser.SyVarDefContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syVarDef}.
	 * @param ctx the parse tree
	 */
	void exitSyVarDef(SysYParser.SyVarDefContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syInitVal}.
	 * @param ctx the parse tree
	 */
	void enterSyInitVal(SysYParser.SyInitValContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syInitVal}.
	 * @param ctx the parse tree
	 */
	void exitSyInitVal(SysYParser.SyInitValContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syFuncDef}.
	 * @param ctx the parse tree
	 */
	void enterSyFuncDef(SysYParser.SyFuncDefContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syFuncDef}.
	 * @param ctx the parse tree
	 */
	void exitSyFuncDef(SysYParser.SyFuncDefContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syFuncType}.
	 * @param ctx the parse tree
	 */
	void enterSyFuncType(SysYParser.SyFuncTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syFuncType}.
	 * @param ctx the parse tree
	 */
	void exitSyFuncType(SysYParser.SyFuncTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syFuncFParams}.
	 * @param ctx the parse tree
	 */
	void enterSyFuncFParams(SysYParser.SyFuncFParamsContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syFuncFParams}.
	 * @param ctx the parse tree
	 */
	void exitSyFuncFParams(SysYParser.SyFuncFParamsContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syFuncFParam}.
	 * @param ctx the parse tree
	 */
	void enterSyFuncFParam(SysYParser.SyFuncFParamContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syFuncFParam}.
	 * @param ctx the parse tree
	 */
	void exitSyFuncFParam(SysYParser.SyFuncFParamContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syBlock}.
	 * @param ctx the parse tree
	 */
	void enterSyBlock(SysYParser.SyBlockContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syBlock}.
	 * @param ctx the parse tree
	 */
	void exitSyBlock(SysYParser.SyBlockContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syBlockItem}.
	 * @param ctx the parse tree
	 */
	void enterSyBlockItem(SysYParser.SyBlockItemContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syBlockItem}.
	 * @param ctx the parse tree
	 */
	void exitSyBlockItem(SysYParser.SyBlockItemContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syIfStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void enterSyIfStmt(SysYParser.SyIfStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syIfStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void exitSyIfStmt(SysYParser.SyIfStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syWhileStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void enterSyWhileStmt(SysYParser.SyWhileStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syWhileStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void exitSyWhileStmt(SysYParser.SyWhileStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syBlockStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void enterSyBlockStmt(SysYParser.SyBlockStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syBlockStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void exitSyBlockStmt(SysYParser.SyBlockStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syExpStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void enterSyExpStmt(SysYParser.SyExpStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syExpStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void exitSyExpStmt(SysYParser.SyExpStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syBreakStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void enterSyBreakStmt(SysYParser.SyBreakStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syBreakStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void exitSyBreakStmt(SysYParser.SyBreakStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syContinueStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void enterSyContinueStmt(SysYParser.SyContinueStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syContinueStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void exitSyContinueStmt(SysYParser.SyContinueStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syReturnStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void enterSyReturnStmt(SysYParser.SyReturnStmtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syReturnStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 */
	void exitSyReturnStmt(SysYParser.SyReturnStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syAddSubExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyAddSubExp(SysYParser.SyAddSubExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syAddSubExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyAddSubExp(SysYParser.SyAddSubExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syParenExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyParenExp(SysYParser.SyParenExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syParenExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyParenExp(SysYParser.SyParenExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syNumberExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyNumberExp(SysYParser.SyNumberExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syNumberExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyNumberExp(SysYParser.SyNumberExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syRelationalExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyRelationalExp(SysYParser.SyRelationalExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syRelationalExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyRelationalExp(SysYParser.SyRelationalExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syUnaryExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyUnaryExp(SysYParser.SyUnaryExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syUnaryExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyUnaryExp(SysYParser.SyUnaryExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syMulDivModExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyMulDivModExp(SysYParser.SyMulDivModExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syMulDivModExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyMulDivModExp(SysYParser.SyMulDivModExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syArrayAccessExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyArrayAccessExp(SysYParser.SyArrayAccessExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syArrayAccessExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyArrayAccessExp(SysYParser.SyArrayAccessExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syAssignExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyAssignExp(SysYParser.SyAssignExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syAssignExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyAssignExp(SysYParser.SyAssignExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syLValExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyLValExp(SysYParser.SyLValExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syLValExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyLValExp(SysYParser.SyLValExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syEqualityExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyEqualityExp(SysYParser.SyEqualityExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syEqualityExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyEqualityExp(SysYParser.SyEqualityExpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syCallExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void enterSyCallExp(SysYParser.SyCallExpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syCallExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 */
	void exitSyCallExp(SysYParser.SyCallExpContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syUnaryOp}.
	 * @param ctx the parse tree
	 */
	void enterSyUnaryOp(SysYParser.SyUnaryOpContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syUnaryOp}.
	 * @param ctx the parse tree
	 */
	void exitSyUnaryOp(SysYParser.SyUnaryOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syUnaryCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void enterSyUnaryCond(SysYParser.SyUnaryCondContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syUnaryCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void exitSyUnaryCond(SysYParser.SyUnaryCondContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syExpAsCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void enterSyExpAsCond(SysYParser.SyExpAsCondContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syExpAsCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void exitSyExpAsCond(SysYParser.SyExpAsCondContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syNotCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void enterSyNotCond(SysYParser.SyNotCondContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syNotCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void exitSyNotCond(SysYParser.SyNotCondContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syParenCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void enterSyParenCond(SysYParser.SyParenCondContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syParenCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void exitSyParenCond(SysYParser.SyParenCondContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syEqualityCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void enterSyEqualityCond(SysYParser.SyEqualityCondContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syEqualityCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void exitSyEqualityCond(SysYParser.SyEqualityCondContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syLogicalOrCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void enterSyLogicalOrCond(SysYParser.SyLogicalOrCondContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syLogicalOrCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void exitSyLogicalOrCond(SysYParser.SyLogicalOrCondContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syRelationalCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void enterSyRelationalCond(SysYParser.SyRelationalCondContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syRelationalCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void exitSyRelationalCond(SysYParser.SyRelationalCondContext ctx);
	/**
	 * Enter a parse tree produced by the {@code syLogicalAndCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void enterSyLogicalAndCond(SysYParser.SyLogicalAndCondContext ctx);
	/**
	 * Exit a parse tree produced by the {@code syLogicalAndCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 */
	void exitSyLogicalAndCond(SysYParser.SyLogicalAndCondContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syLVal}.
	 * @param ctx the parse tree
	 */
	void enterSyLVal(SysYParser.SyLValContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syLVal}.
	 * @param ctx the parse tree
	 */
	void exitSyLVal(SysYParser.SyLValContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syNumber}.
	 * @param ctx the parse tree
	 */
	void enterSyNumber(SysYParser.SyNumberContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syNumber}.
	 * @param ctx the parse tree
	 */
	void exitSyNumber(SysYParser.SyNumberContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syIntConst}.
	 * @param ctx the parse tree
	 */
	void enterSyIntConst(SysYParser.SyIntConstContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syIntConst}.
	 * @param ctx the parse tree
	 */
	void exitSyIntConst(SysYParser.SyIntConstContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syFloatConst}.
	 * @param ctx the parse tree
	 */
	void enterSyFloatConst(SysYParser.SyFloatConstContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syFloatConst}.
	 * @param ctx the parse tree
	 */
	void exitSyFloatConst(SysYParser.SyFloatConstContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syFuncRParams}.
	 * @param ctx the parse tree
	 */
	void enterSyFuncRParams(SysYParser.SyFuncRParamsContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syFuncRParams}.
	 * @param ctx the parse tree
	 */
	void exitSyFuncRParams(SysYParser.SyFuncRParamsContext ctx);
	/**
	 * Enter a parse tree produced by {@link SysYParser#syFuncRParam}.
	 * @param ctx the parse tree
	 */
	void enterSyFuncRParam(SysYParser.SyFuncRParamContext ctx);
	/**
	 * Exit a parse tree produced by {@link SysYParser#syFuncRParam}.
	 * @param ctx the parse tree
	 */
	void exitSyFuncRParam(SysYParser.SyFuncRParamContext ctx);
}