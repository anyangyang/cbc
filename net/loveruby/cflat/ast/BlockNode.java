package net.loveruby.cflat.ast;
import java.util.*;

public class BlockNode extends Node {
    protected List variables;
    protected List stmts;

    public BlockNode(List vars, List ss) {
        variables = vars;
        stmts = ss;
    }

    public Iterator variables() {
        return variables.iterator();
    }

    public Iterator stmts() {
        return stmts.iterator();
    }

    public Node tailStmt() {
        if (stmts.isEmpty()) return null;
        return (Node)stmts.get(stmts.size() - 1);
    }

    protected Scope scope;

    public Scope scope() {
        return scope;
    }

    public void setScope(Scope s) {
        scope = s;
    }

    protected void _dump(Dumper d) {
        d.printNodeList("variables", variables);
        d.printNodeList("stmts", stmts);
    }

    public void accept(ASTVisitor visitor) {
        visitor.visit(this);
    }
}
