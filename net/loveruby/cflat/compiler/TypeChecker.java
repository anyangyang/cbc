package net.loveruby.cflat.compiler;
import net.loveruby.cflat.ast.*;
import net.loveruby.cflat.type.*;
import net.loveruby.cflat.exception.*;
import java.util.*;

class TypeChecker extends Visitor {
    protected TypeTable typeTable;
    protected ErrorHandler errorHandler;
    private DefinedFunction currentFunction;

    // #@@range/ctor{
    public TypeChecker(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }
    // #@@}

    protected void check(StmtNode node) {
        visitStmt(node);
    }

    protected void check(ExprNode node) {
        visitExpr(node);
    }

    // #@@range/check_AST{
    public void check(AST ast) throws SemanticException {
        this.typeTable = ast.typeTable();
        for (DefinedVariable var : ast.definedVariables()) {
            checkVariable(var);
        }
        for (DefinedFunction f : ast.definedFunctions()) {
            currentFunction = f;
            checkReturnType(f);
            checkParamTypes(f);
            check(f.body());
        }
        if (errorHandler.errorOccured()) {
            throw new SemanticException("compile failed.");
        }
    }
    // #@@}

    protected void checkReturnType(DefinedFunction f) {
        if (isInvalidReturnType(f.returnType())) {
            error(f, "returns invalid type: " + f.returnType());
        }
    }

    protected void checkParamTypes(DefinedFunction f) {
        for (Parameter param : f.parameters()) {
            if (isInvalidParameterType(param.type())) {
                error(param, "invalid parameter type: " + param.type());
            }
        }
    }

    //
    // Statements
    //

    public Void visit(BlockNode node) {
        for (DefinedVariable var : node.variables()) {
            checkVariable(var);
        }
        for (StmtNode n : node.stmts()) {
            check(n);
        }
        return null;
    }

    protected void checkVariable(DefinedVariable var) {
        if (isInvalidVariableType(var.type())) {
            error(var, "invalid variable type");
            return;
        }
        if (var.hasInitializer()) {
            if (isInvalidLHSType(var.type())) {
                error(var, "invalid LHS type: " + var.type());
                return;
            }
            check(var.initializer());
            var.setInitializer(implicitCast(var.type(), var.initializer()));
        }
    }

    public Void visit(ExprStmtNode node) {
        check(node.expr());
        if (isInvalidStatementType(node.expr().type())) {
            error(node, "invalid statement type: " + node.expr().type());
            return null;
        }
        return null;
    }

    public Void visit(IfNode node) {
        super.visit(node);
        checkCond(node.cond());
        return null;
    }

    public Void visit(WhileNode node) {
        super.visit(node);
        checkCond(node.cond());
        return null;
    }

    public Void visit(ForNode node) {
        super.visit(node);
        checkCond(node.cond());
        return null;
    }

    protected void checkCond(ExprNode cond) {
        mustBeScalar(cond, "condition expression");
    }

    public Void visit(SwitchNode node) {
        super.visit(node);
        mustBeInteger(node.cond(), "condition expression");
        return null;
    }

    public Void visit(ReturnNode node) {
        super.visit(node);
        if (currentFunction.isVoid()) {
            if (node.expr() != null) {
                error(node, "returning value from void function");
            }
        }
        else {  // non-void function
            if (node.expr() == null) {
                error(node, "missing return value");
                return null;
            }
            if (node.expr().type().isVoid()) {
                error(node, "returning void");
                return null;
            }
            node.setExpr(implicitCast(currentFunction.returnType(),
                                      node.expr()));
        }
        return null;
    }

    //
    // Assignment Expressions
    //

    public Void visit(AssignNode node) {
        super.visit(node);
        if (! checkLHS(node.lhs())) return null;
        if (! checkRHS(node.rhs())) return null;
        node.setRHS(implicitCast(node.lhs().type(), node.rhs()));
        return null;
    }

    public Void visit(OpAssignNode node) {
        super.visit(node);
        if (! checkLHS(node.lhs())) return null;
        if (! checkRHS(node.rhs())) return null;
        if (node.operator().equals("+")
                || node.operator().equals("-")) {
            if (node.lhs().type().isPointer()) {
                if (! mustBeInteger(node.rhs(), node.operator())) return null;
                node.setRHS(multiplyPtrBaseSize(node.rhs(), node.lhs()));
                return null;
            }
        }
        if (! mustBeInteger(node.lhs(), node.operator())) return null;
        if (! mustBeInteger(node.rhs(), node.operator())) return null;
        Type l = integralPromotion(node.lhs().type());
        Type r = integralPromotion(node.rhs().type());
        Type opType = usualArithmeticConversion(l, r);
        if (! opType.isCompatible(l)
                && ! isSafeIntegerCast(node.rhs(), opType)) {
            warn(node, "incompatible implicit cast from "
                       + opType + " to " + l);
        }
        if (! r.isSameType(opType)) {
            // cast RHS
            node.setRHS(new CastNode(opType, node.rhs()));
        }
        return null;
    }

    /** allow safe implicit cast from integer literal like:
     *
     *    char c = 0;
     *
     *  "0" has a type integer, but we can cast (int)0 to (char)0 safely.
     */
    protected boolean isSafeIntegerCast(Node node, Type type) {
        if (! type.isInteger()) return false;
        IntegerType t = (IntegerType)type;
        if (! (node instanceof IntegerLiteralNode)) return false;
        IntegerLiteralNode n = (IntegerLiteralNode)node;
        return t.isInDomain(n.value());
    }

    protected boolean checkLHS(ExprNode lhs) {
        if (lhs.isParameter()) {
            // parameter is always assignable.
            return true;
        }
        else if (isInvalidLHSType(lhs.type())) {
            error(lhs, "invalid LHS expression type: " + lhs.type());
            return false;
        }
        return true;
    }

    //
    // Expressions
    //

    public Void visit(CondExprNode node) {
        super.visit(node);
        checkCond(node.cond());
        Type t = node.thenExpr().type();
        Type e = node.elseExpr().type();
        if (t.isSameType(e)) {
            return null;
        }
        else if (t.isCompatible(e)) {   // insert cast on thenBody
            node.setThenExpr(new CastNode(e, node.thenExpr()));
        }
        else if (e.isCompatible(t)) {   // insert cast on elseBody
            node.setElseExpr(new CastNode(t, node.elseExpr()));
        }
        else {
            invalidCastError(node.thenExpr(), e, t);
        }
        return null;
    }

    // #@@range/BinaryOpNode{
    public Void visit(BinaryOpNode node) {
        super.visit(node);
        if (node.operator().equals("+") || node.operator().equals("-")) {
            Type t = expectsSameIntegerOrPointerDiff(node);
            if (t != null) node.setType(t);
        }
        else if (node.operator().equals("*")
                || node.operator().equals("/")
                || node.operator().equals("%")
                || node.operator().equals("&")
                || node.operator().equals("|")
                || node.operator().equals("^")
                || node.operator().equals("<<")
                || node.operator().equals(">>")) {
            Type t = expectsSameInteger(node);
            if (t != null) node.setType(t);
        }
        else if (node.operator().equals("==")
                || node.operator().equals("!=")
                || node.operator().equals("<")
                || node.operator().equals("<=")
                || node.operator().equals(">")
                || node.operator().equals(">=")) {
            Type t = expectsComparableScalars(node);
            if (t != null) node.setType(t);
        }
        else {
            throw new Error("unknown binary operator: " + node.operator());
        }
        return null;
    }
    // #@@}

    public Void visit(LogicalAndNode node) {
        super.visit(node);
        Type t = expectsComparableScalars(node);
        if (t != null) node.setType(t);
        return null;
    }

    public Void visit(LogicalOrNode node) {
        super.visit(node);
        Type t = expectsComparableScalars(node);
        if (t != null) node.setType(t);
        return null;
    }

    /**
     * For + and -, only following types of expression are valid:
     *
     *   * integer + integer
     *   * pointer + integer
     *   * integer + pointer
     *   * integer - integer
     *   * pointer - integer
     */
    protected Type expectsSameIntegerOrPointerDiff(BinaryOpNode node) {
        if (node.left().type().isDereferable()) {
            if (node.left().type().baseType().isVoid()) {
                wrongTypeError(node.left(), node.operator());
                return null;
            }
            mustBeInteger(node.right(), node.operator());
            node.setRight(multiplyPtrBaseSize(node.right(), node.left()));
            return node.left().type();
        }
        else if (node.right().type().isDereferable()) {
            if (node.operator().equals("-")) {
                error(node, "invalid operation integer-pointer");
                return null;
            }
            if (node.right().type().baseType().isVoid()) {
                wrongTypeError(node.right(), node.operator());
                return null;
            }
            mustBeInteger(node.left(), node.operator());
            node.setLeft(multiplyPtrBaseSize(node.left(), node.right()));
            return node.right().type();
        }
        else {
            return expectsSameInteger(node);
        }
    }

    protected BinaryOpNode multiplyPtrBaseSize(ExprNode expr, ExprNode ptr) {
        return new BinaryOpNode(integralPromotedExpr(expr), "*", ptrBaseSize(ptr));
    }

    protected ExprNode integralPromotedExpr(ExprNode expr) {
        Type t = integralPromotion(expr.type());
        if (t.isSameType(expr.type())) {
            return expr;
        }
        else {
            return new CastNode(t, expr);
        }
    }

    protected IntegerLiteralNode ptrBaseSize(ExprNode ptr) {
        return integerLiteral(ptr.location(),
                              typeTable.ptrDiffTypeRef(),
                              ptr.type().baseType().size());
    }

    protected IntegerLiteralNode integerLiteral(Location loc, TypeRef ref, long n) {
        IntegerLiteralNode node = new IntegerLiteralNode(loc, ref, n);
        bindType(node.typeNode());
        return node;
    }

    protected void bindType(TypeNode t) {
        t.setType(typeTable.get(t.typeRef()));
    }

    // +, -, *, /, %, &, |, ^, <<, >>
    // #@@range/expectsSameInteger{
    protected Type expectsSameInteger(BinaryOpNode node) {
        if (! mustBeInteger(node.left(), node.operator())) return null;
        if (! mustBeInteger(node.right(), node.operator())) return null;
        return arithmeticImplicitCast(node);
    }
    // #@@}

    // ==, !=, >, >=, <, <=, &&, ||
    protected Type expectsComparableScalars(BinaryOpNode node) {
        if (! mustBeScalar(node.left(), node.operator())) return null;
        if (! mustBeScalar(node.right(), node.operator())) return null;
        if (node.left().type().isDereferable()) {
            ExprNode right = forcePointerType(node.left(), node.right());
            node.setRight(right);
            return node.left().type();
        }
        if (node.right().type().isDereferable()) {
            ExprNode left = forcePointerType(node.right(), node.left());
            node.setLeft(left);
            return node.right().type();
        }
        return arithmeticImplicitCast(node);
    }

    // cast slave node to master node.
    protected ExprNode forcePointerType(ExprNode master, ExprNode slave) {
        if (master.type().isCompatible(slave.type())) {
            // needs no cast
            return slave;
        }
        else {
            warn(slave, "incompatible implicit cast from "
                       + slave.type() + " to " + master.type());
            return new CastNode(master.type(), slave);
        }
    }

    // Processes usual arithmetic conversion for binary operations.
    // #@@range/arithmeticImplicitCast{
    protected Type arithmeticImplicitCast(BinaryOpNode node) {
        Type r = integralPromotion(node.right().type());
        Type l = integralPromotion(node.left().type());
        Type target = usualArithmeticConversion(l, r);
        if (! l.isSameType(target)) {
            // insert cast on left expr
            node.setLeft(new CastNode(target, node.left()));
        }
        if (! r.isSameType(target)) {
            // insert cast on right expr
            node.setRight(new CastNode(target, node.right()));
        }
        return target;
    }
    // #@@}

    // +, -, !, ~
    public Void visit(UnaryOpNode node) {
        super.visit(node);
        if (node.operator().equals("!")) {
            mustBeScalar(node.expr(), node.operator());
        }
        else {
            mustBeInteger(node.expr(), node.operator());
        }
        return null;
    }

    // ++x, --x
    public Void visit(PrefixOpNode node) {
        super.visit(node);
        expectsScalarLHS(node);
        return null;
    }

    // x++, x--
    public Void visit(SuffixOpNode node) {
        super.visit(node);
        expectsScalarLHS(node);
        return null;
    }

    protected void expectsScalarLHS(UnaryArithmeticOpNode node) {
        if (node.expr().isParameter()) {
            // parameter is always a scalar.
        }
        else if (node.expr().type().isArray()) {
            // We cannot modify non-parameter array.
            wrongTypeError(node.expr(), node.operator());
            return;
        }
        else {
            mustBeScalar(node.expr(), node.operator());
        }
        if (node.expr().type().isInteger()) {
            Type opType = integralPromotion(node.expr().type());
            if (! node.expr().type().isSameType(opType)) {
                node.setOpType(opType);
            }
            node.setAmount(1);
        }
        else if (node.expr().type().isDereferable()) {
            if (node.expr().type().baseType().isVoid()) {
                // We cannot increment/decrement void*
                wrongTypeError(node.expr(), node.operator());
                return;
            }
            node.setAmount(node.expr().type().baseType().size());
        }
        else {
            throw new Error("must not happen");
        }
    }

    /**
     * For EXPR(ARG), checks:
     *
     *   * The number of argument matches function prototype.
     *   * ARG matches function prototype.
     *   * ARG is neither a struct nor an union.
     */
    public Void visit(FuncallNode node) {
        super.visit(node);
        FunctionType type = node.functionType();
        if (! type.acceptsArgc(node.numArgs())) {
            error(node, "wrong number of argments: " + node.numArgs());
            return null;
        }
        // Check type of only mandatory parameters.
        Iterator<ExprNode> args = node.arguments().iterator();
        List<ExprNode> newArgs = new ArrayList<ExprNode>();
        for (Type param : type.paramTypes()) {
            ExprNode arg = args.next();
            newArgs.add(checkRHS(arg) ? implicitCast(param, arg) : arg);
        }
        while (args.hasNext()) {
            newArgs.add(args.next());
        }
        node.replaceArgs(newArgs);
        return null;
    }

    public Void visit(ArefNode node) {
        super.visit(node);
        mustBeInteger(node.index(), "[]");
        return null;
    }

    public Void visit(CastNode node) {
        super.visit(node);
        if (! node.expr().type().isCastableTo(node.type())) {
            invalidCastError(node, node.expr().type(), node.type());
        }
        return null;
    }

    //
    // Utilities
    //

    protected boolean checkRHS(ExprNode rhs) {
        if (isInvalidRHSType(rhs.type())) {
            error(rhs, "invalid RHS expression type: " + rhs.type());
            return false;
        }
        return true;
    }

    // Processes forced-implicit-cast.
    // Applied To: return expr, assignment RHS, funcall argument
    protected ExprNode implicitCast(Type targetType, ExprNode expr) {
        if (expr.type().isSameType(targetType)) {
            return expr;
        }
        else if (expr.type().isCastableTo(targetType)) {
            if (! expr.type().isCompatible(targetType)
                    && ! isSafeIntegerCast(expr, targetType)) {
                warn(expr, "incompatible implicit cast from "
                           + expr.type() + " to " + targetType);
            }
            return new CastNode(targetType, expr);
        }
        else {
            invalidCastError(expr, expr.type(), targetType);
            return expr;
        }
    }

    // Process integral promotion (integers only).
    // #@@range/integralPromotion{
    protected Type integralPromotion(Type t) {
        if (!t.isInteger()) {
            throw new Error("integralPromotion for " + t);
        }
        Type intType = typeTable.signedInt();
        if (t.size() < intType.size()) {
            return intType;
        }
        else {
            return t;
        }
    }
    // #@@}

    // Usual arithmetic conversion for ILP32 platform (integers only).
    // Size of l, r >= sizeof(int).
    // #@@range/usualArithmeticConversion{
    protected Type usualArithmeticConversion(Type l, Type r) {
        Type s_int = typeTable.signedInt();
        Type u_int = typeTable.unsignedInt();
        Type s_long = typeTable.signedLong();
        Type u_long = typeTable.unsignedLong();
        if (    (l.isSameType(u_int) && r.isSameType(s_long))
             || (r.isSameType(u_int) && l.isSameType(s_long))) {
            return u_long;
        }
        else if (l.isSameType(u_long) || r.isSameType(u_long)) {
            return u_long;
        }
        else if (l.isSameType(s_long) || r.isSameType(s_long)) {
            return s_long;
        }
        else if (l.isSameType(u_int)  || r.isSameType(u_int)) {
            return u_int;
        }
        else {
            return s_int;
        }
    }
    // #@@}

    protected boolean isInvalidStatementType(Type t) {
        return t.isStruct() || t.isUnion();
    }

    protected boolean isInvalidReturnType(Type t) {
        return t.isStruct() || t.isUnion() || t.isArray();
    }

    protected boolean isInvalidParameterType(Type t) {
        return t.isStruct() || t.isUnion() || t.isVoid()
                || t.isIncompleteArray();
    }

    protected boolean isInvalidVariableType(Type t) {
        return t.isVoid() || (t.isArray() && ! t.isAllocatedArray());
    }

    protected boolean isInvalidLHSType(Type t) {
        // Array is OK if it is declared as a type of parameter.
        return t.isStruct() || t.isUnion() || t.isVoid() || t.isArray();
    }

    protected boolean isInvalidRHSType(Type t) {
        return t.isStruct() || t.isUnion() || t.isVoid();
    }

    protected boolean mustBeInteger(ExprNode expr, String op) {
        if (! expr.type().isInteger()) {
            wrongTypeError(expr, op);
            return false;
        }
        return true;
    }

    protected boolean mustBeScalar(ExprNode expr, String op) {
        if (! expr.type().isScalar()) {
            wrongTypeError(expr, op);
            return false;
        }
        return true;
    }

    protected void invalidCastError(Node n, Type l, Type r) {
        error(n, "invalid cast from " + l + " to " + r);
    }

    protected void wrongTypeError(ExprNode expr, String op) {
        error(expr, "wrong operand type for " + op + ": " + expr.type());
    }

    protected void warn(Node n, String msg) {
        errorHandler.warn(n.location(), msg);
    }

    protected void error(Node n, String msg) {
        errorHandler.error(n.location(), msg);
    }
}
