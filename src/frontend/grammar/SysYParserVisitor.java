package frontend.grammar;

// Generated from /home/ni/Desktop/compiler2025-nlvm/util/SysYParser.g4 by ANTLR 4.12.0
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link SysYParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface SysYParserVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link SysYParser#syProgram}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyProgram(SysYParser.SyProgramContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syCompUnit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyCompUnit(SysYParser.SyCompUnitContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syBType}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyBType(SysYParser.SyBTypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syModifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyModifier(SysYParser.SyModifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syVarDecl}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyVarDecl(SysYParser.SyVarDeclContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syVarDef}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyVarDef(SysYParser.SyVarDefContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syInitVal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyInitVal(SysYParser.SyInitValContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syFuncDef}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyFuncDef(SysYParser.SyFuncDefContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syFuncType}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyFuncType(SysYParser.SyFuncTypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syFuncFParams}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyFuncFParams(SysYParser.SyFuncFParamsContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syFuncFParam}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyFuncFParam(SysYParser.SyFuncFParamContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syBlock}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyBlock(SysYParser.SyBlockContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syBlockItem}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyBlockItem(SysYParser.SyBlockItemContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syIfStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyIfStmt(SysYParser.SyIfStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syWhileStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyWhileStmt(SysYParser.SyWhileStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syBlockStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyBlockStmt(SysYParser.SyBlockStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syExpStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyExpStmt(SysYParser.SyExpStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syBreakStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyBreakStmt(SysYParser.SyBreakStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syContinueStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyContinueStmt(SysYParser.SyContinueStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syReturnStmt}
	 * labeled alternative in {@link SysYParser#syStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyReturnStmt(SysYParser.SyReturnStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syAddSubExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyAddSubExp(SysYParser.SyAddSubExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syParenExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyParenExp(SysYParser.SyParenExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syNumberExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyNumberExp(SysYParser.SyNumberExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syRelationalExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyRelationalExp(SysYParser.SyRelationalExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syUnaryExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyUnaryExp(SysYParser.SyUnaryExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syMulDivModExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyMulDivModExp(SysYParser.SyMulDivModExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syArrayAccessExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyArrayAccessExp(SysYParser.SyArrayAccessExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syAssignExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyAssignExp(SysYParser.SyAssignExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syLValExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyLValExp(SysYParser.SyLValExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syEqualityExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyEqualityExp(SysYParser.SyEqualityExpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syCallExp}
	 * labeled alternative in {@link SysYParser#syExp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyCallExp(SysYParser.SyCallExpContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syUnaryOp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyUnaryOp(SysYParser.SyUnaryOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syUnaryCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyUnaryCond(SysYParser.SyUnaryCondContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syExpAsCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyExpAsCond(SysYParser.SyExpAsCondContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syNotCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyNotCond(SysYParser.SyNotCondContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syParenCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyParenCond(SysYParser.SyParenCondContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syEqualityCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyEqualityCond(SysYParser.SyEqualityCondContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syLogicalOrCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyLogicalOrCond(SysYParser.SyLogicalOrCondContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syRelationalCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyRelationalCond(SysYParser.SyRelationalCondContext ctx);
	/**
	 * Visit a parse tree produced by the {@code syLogicalAndCond}
	 * labeled alternative in {@link SysYParser#syCond}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyLogicalAndCond(SysYParser.SyLogicalAndCondContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syLVal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyLVal(SysYParser.SyLValContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syNumber}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyNumber(SysYParser.SyNumberContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syIntConst}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyIntConst(SysYParser.SyIntConstContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syFloatConst}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyFloatConst(SysYParser.SyFloatConstContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syFuncRParams}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyFuncRParams(SysYParser.SyFuncRParamsContext ctx);
	/**
	 * Visit a parse tree produced by {@link SysYParser#syFuncRParam}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSyFuncRParam(SysYParser.SyFuncRParamContext ctx);
}