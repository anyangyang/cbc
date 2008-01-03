package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.*;
import java.util.*;

abstract public class ComplexTypeDefinition extends TypeDefinition {
    protected List members;     // List<Slot>
    protected TypeNode typeNode;

    public ComplexTypeDefinition(TypeRef ref, String name, List membs) {
        super(name);
        members = membs;
        typeNode = new TypeNode(ref);
    }

    public boolean isComplexType() {
        return true;
    }

    abstract public String type();

    public List members() {
        return members;
    }

    public TypeRef typeRef() {
        return typeNode.typeRef();
    }

    public TypeNode typeNode() {
        return typeNode;
    }

    protected void _dump(Dumper d) {
        d.printMember("name", name);
        d.printNodeList("members", members);
    }
}
