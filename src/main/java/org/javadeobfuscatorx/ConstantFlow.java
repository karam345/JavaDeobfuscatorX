package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import java.util.List;

final class ConstantFlow extends Interpreter<ConstantFlow.Fact> implements Opcodes {
    record Fact(BasicValue type, Number number) implements Value {
        @Override
        public int getSize() {
            return type.getSize();
        }

        boolean isNull() {
            return BasicInterpreter.NULL_TYPE.equals(type.getType());
        }
    }

    private final BasicInterpreter basic = new BasicInterpreter();

    ConstantFlow() {
        super(ASM9);
    }

    @Override
    public Fact newValue(Type type) {
        return fact(basic.newValue(type), null);
    }

    @Override
    public Fact newOperation(AbstractInsnNode insn) throws AnalyzerException {
        if (insn.getOpcode() == ACONST_NULL) return new Fact(new BasicValue(BasicInterpreter.NULL_TYPE), null);
        Number value = Rewrites.integer(insn);
        if (insn.getOpcode() == LCONST_0 || insn.getOpcode() == LCONST_1) {
            value = (long) (insn.getOpcode() - LCONST_0);
        } else if (insn instanceof LdcInsnNode load && load.cst instanceof Long literal) {
            value = literal;
        }
        return fact(basic.newOperation(insn), value);
    }

    @Override
    public Fact copyOperation(AbstractInsnNode insn, Fact value) {
        return value;
    }

    @Override
    public Fact unaryOperation(AbstractInsnNode insn, Fact input) throws AnalyzerException {
        return fact(basic.unaryOperation(insn, input.type()), unary(insn, input.number()));
    }

    @Override
    public Fact binaryOperation(AbstractInsnNode insn, Fact left, Fact right) throws AnalyzerException {
        return fact(basic.binaryOperation(insn, left.type(), right.type()), binary(insn.getOpcode(),
                left.number(), right.number()));
    }

    @Override
    public Fact ternaryOperation(AbstractInsnNode insn, Fact first, Fact second, Fact third) throws AnalyzerException {
        return fact(basic.ternaryOperation(insn, first.type(), second.type(), third.type()), null);
    }

    @Override
    public Fact naryOperation(AbstractInsnNode insn, List<? extends Fact> values) throws AnalyzerException {
        return fact(basic.naryOperation(insn, values.stream().map(Fact::type).toList()), null);
    }

    @Override
    public void returnOperation(AbstractInsnNode insn, Fact value, Fact expected) throws AnalyzerException {
        basic.returnOperation(insn, value.type(), expected.type());
    }

    @Override
    public Fact merge(Fact left, Fact right) {
        if (left.equals(right)) return left;
        // BasicValue equality ignores constants. Equal JVM types with different values must lose the fact.
        return fact(basic.merge(left.type(), right.type()), null);
    }

    private static Fact fact(BasicValue type, Number value) {
        return type == null ? null : new Fact(type, value);
    }

    static Number unary(AbstractInsnNode insn, Number value) {
        return switch (value) {
            case Integer integer -> switch (insn.getOpcode()) {
                case IINC -> integer + ((IincInsnNode) insn).incr;
                case I2L -> integer.longValue();
                default -> Rewrites.unary(insn.getOpcode(), integer);
            };
            case Long number -> switch (insn.getOpcode()) {
                case LNEG -> -number;
                case L2I -> number.intValue();
                default -> null;
            };
            case null, default -> null;
        };
    }

    static Number binary(int opcode, Number left, Number right) {
        if (left instanceof Integer a && right instanceof Integer b) return Rewrites.binary(opcode, a, b);
        if ((opcode == IMUL || opcode == IAND) && (Integer.valueOf(0).equals(left) || Integer.valueOf(0).equals(right))) return 0;
        if (opcode == IOR && (Integer.valueOf(-1).equals(left) || Integer.valueOf(-1).equals(right))) return -1;
        if (opcode == IREM && (Integer.valueOf(1).equals(right) || Integer.valueOf(-1).equals(right))) return 0;
        if (!(left instanceof Long a)) return null;
        if (right instanceof Integer b) {
            return switch (opcode) {
                case LSHL -> a << b;
                case LSHR -> a >> b;
                case LUSHR -> a >>> b;
                default -> null;
            };
        }
        if (!(right instanceof Long b)) return null;
        return switch (opcode) {
            case LADD -> a + b;
            case LSUB -> a - b;
            case LMUL -> a * b;
            case LDIV -> b == 0 ? null : a / b;
            case LREM -> b == 0 ? null : a % b;
            case LAND -> a & b;
            case LOR -> a | b;
            case LXOR -> a ^ b;
            case LCMP -> Long.compare(a, b);
            default -> null;
        };
    }
}
