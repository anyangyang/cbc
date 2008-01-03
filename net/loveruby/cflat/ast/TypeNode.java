package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.*;

public class TypeNode extends Node {
    protected TypeRef typeRef;
    protected Type type;

    public TypeNode(TypeRef ref) {
        super();
        this.typeRef = ref;
    }

    public TypeNode(Type type) {
        super();
        this.type = type;
    }

    public void resolve(TypeTable tbl) {
        // FIXME
    }

    public TypeRef typeRef() {
        return typeRef;
    }

    public boolean isResolved() {
        return (type != null);
    }

    public void setType(Type t) {
        if (isResolved()) throw new Error("TypeNode#setType called twice");
        type = t;
    }

    public Type type() {
        if (type == null) throw new Error("TypeNode not resolved");
        return type;
    }

    protected void _dump(Dumper d) {
        d.printMember("typeref", typeRef);
        d.printMember("type", type);
    }

    public void accept(ASTVisitor visitor) {
        throw new Error("do not call TypeNode#accept");
    }
}
