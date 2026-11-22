package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Interprets one proven path using only local memory and explicitly modeled operations. */
final class Decoder implements Opcodes {
    enum Identity { INTERNED, FRESH, BORROWED, BUILDER_RESULT }
    record Text(String value, Identity identity) {}
    record Evaluation(Text text, List<String> literals) {}
    private record MethodKey(String name, String descriptor) {}
    private record FieldKey(String name, String descriptor) {}
    private record Slot(Object data) implements Value {
        @Override public int getSize() { return data instanceof Long ? 2 : 1; }
    }
    private static final class Allocation {
        Text text;
    }
    private static final class BuilderAllocation {
        StringBuilder contents;
    }
    static final class Unsupported extends RuntimeException {
        Unsupported(String reason) { super(reason, null, false, false); }
    }

    private static final Slot EMPTY = new Slot(null);
    private final String owner;
    private final int version;
    private final Map<MethodKey, MethodNode> methods = new HashMap<>();
    private final Map<FieldKey, Object> constants = new HashMap<>();
    private final DecoderIndex.Budget archiveBudget;
    private int classSteps;
    private int classUnits = 512_000;

    Decoder(ClassNode node) {
        this(node, null);
    }

    Decoder(ClassNode node, DecoderIndex.Budget archiveBudget) {
        owner = node.name;
        version = node.version & 0xffff;
        this.archiveBudget = archiveBudget;
        classSteps = archiveBudget == null ? 100_000 : 2_000_000;
        if ((node.access & ACC_INTERFACE) != 0 || node.methods.stream().noneMatch(Decoder::stringDecoder)) return;
        for (var method : node.methods) {
            if (methods.put(new MethodKey(method.name, method.desc), method) != null) {
                methods.clear();
                break;
            }
        }
        if (!methods.isEmpty()) indexConstants(node);
    }

    private static boolean stringDecoder(MethodNode method) {
        return (method.access & ACC_STATIC) != 0
                && method.desc.endsWith(")Ljava/lang/String;");
    }

    private void indexConstants(ClassNode node) {
        for (var field : node.fields) {
            int flags = ACC_PRIVATE | ACC_STATIC | ACC_FINAL;
            if ((field.access & flags) != flags) continue;
            Object value = switch (field.desc) {
                case "I" -> field.value instanceof Integer integer ? integer : null;
                case "J" -> field.value instanceof Long wide ? wide : null;
                case "Ljava/lang/String;" -> field.value instanceof String text && text.length() <= Strings.LIMIT
                        ? new Text(text, Identity.INTERNED) : null;
                default -> null;
            };
            if (value != null) constants.put(new FieldKey(field.name, field.desc), value);
        }
        if (constants.isEmpty()) return;
        for (var method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof FieldInsnNode write && write.getOpcode() == PUTSTATIC && write.owner.equals(owner)) {
                    constants.remove(new FieldKey(write.name, write.desc));
                }
            }
        }
    }

    MethodNode target(MethodInsnNode call) {
        if (call.getOpcode() != INVOKESTATIC || call.itf || !call.owner.equals(owner) || call.name.startsWith("<")) return null;
        var method = methods.get(new MethodKey(call.name, call.desc));
        return method != null && (method.access & (ACC_PRIVATE | ACC_STATIC)) == (ACC_PRIVATE | ACC_STATIC) ? method : null;
    }

    MethodNode entry(MethodInsnNode call, boolean external) {
        if (call.getOpcode() != INVOKESTATIC || call.itf || !call.owner.equals(owner) || call.name.startsWith("<")) return null;
        var method = methods.get(new MethodKey(call.name, call.desc));
        int flags = external ? ACC_PUBLIC | ACC_STATIC : ACC_STATIC;
        return method != null && (method.access & flags) == flags ? method : null;
    }

    Evaluation evaluate(MethodNode method, List<?> inputs) throws AnalyzerException {
        var path = new PathInterpreter();
        var args = inputs.stream().map(value -> new Slot(value instanceof String text ? new Text(text, Identity.BORROWED) : value)).toList();
        var result = asText(path.run(method, args, 0).data);
        return new Evaluation(result, List.copyOf(path.literals));
    }

    private final class PathInterpreter extends Interpreter<Slot> {
        private final LinkedHashSet<String> literals = new LinkedHashSet<>();
        private int steps = 8_192;
        private int units = 32_768;
        private int depth;

        PathInterpreter() { super(ASM9); }

        Slot run(MethodNode method, List<? extends Slot> args, int level) throws AnalyzerException {
            if (level >= 8 || method.instructions.size() > 4096 || method.maxLocals + method.maxStack > 512 || !method.tryCatchBlocks.isEmpty()
                    || (method.access & (ACC_NATIVE | ACC_ABSTRACT | ACC_SYNCHRONIZED)) != 0) {
                throw new Unsupported("Decoder body, handlers or call depth outside supported limits");
            }
            var frame = new Frame<Slot>(method.maxLocals, method.maxStack);
            for (int i = 0; i < frame.getLocals(); i++) frame.setLocal(i, EMPTY);
            int local = 0;
            var types = Type.getArgumentTypes(method.desc);
            for (int i = 0; i < types.length; i++) {
                Slot slot = args.get(i);
                requireType(types[i], slot.data);
                frame.setLocal(local, slot);
                local += slot.getSize();
            }

            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (--steps < 0 || --classSteps < 0 || archiveBudget != null && !archiveBudget.takeStep()) {
                    throw new Unsupported("Decoder instruction budget exhausted");
                }
                int opcode = insn.getOpcode();
                if (opcode < 0) continue;
                if (opcode == ARETURN || opcode == IRETURN || opcode == LRETURN) {
                    Slot result = peek(frame, 0);
                    Type returnType = Type.getReturnType(method.desc);
                    requireType(returnType, result.data);
                    if (opcode == IRETURN) {
                        int value = integer(result);
                        return new Slot(switch (returnType.getSort()) {
                            case Type.BOOLEAN -> value & 1;
                            case Type.BYTE -> (int) (byte) value;
                            case Type.CHAR -> (int) (char) value;
                            case Type.SHORT -> (int) (short) value;
                            default -> value;
                        });
                    }
                    return result;
                }
                if (insn instanceof JumpInsnNode jump) {
                    boolean taken = branch(opcode, frame);
                    if (taken) insn = jump.label;
                } else if (insn instanceof TableSwitchInsnNode table) {
                    int key = integer(frame.pop());
                    insn = key >= table.min && key <= table.max ? table.labels.get(key - table.min) : table.dflt;
                } else if (insn instanceof LookupSwitchInsnNode lookup) {
                    int index = java.util.Collections.binarySearch(lookup.keys, integer(frame.pop()));
                    insn = index < 0 ? lookup.dflt : lookup.labels.get(index);
                } else {
                    if (opcode == RETURN || opcode == FRETURN || opcode == DRETURN || opcode == RET
                            || opcode == MONITORENTER || opcode == MONITOREXIT || opcode == ATHROW) throw opcode(insn);
                    depth = level;
                    frame.execute(insn, this);
                }
            }
            throw new Unsupported("Decoder path did not return a supported value");
        }

        @Override public Slot newValue(Type type) { return EMPTY; }

        @Override
        public Slot newOperation(AbstractInsnNode insn) {
            Integer value = Rewrites.integer(insn);
            if (value != null) return new Slot(value);
            return switch (insn.getOpcode()) {
                case LCONST_0, LCONST_1 -> new Slot((long) (insn.getOpcode() - LCONST_0));
                case ACONST_NULL -> new Slot(StringRecovery.NULL);
                case LDC -> switch (((LdcInsnNode) insn).cst) {
                    case String text -> {
                        rememberLiteral(text);
                        yield new Slot(new Text(text, Identity.INTERNED));
                    }
                    case Long wide -> new Slot(wide);
                    default -> throw opcode(insn);
                };
                case NEW -> switch (((TypeInsnNode) insn).desc) {
                    case "java/lang/String" -> new Slot(new Allocation());
                    case "java/lang/StringBuilder" -> {
                        if (version < V1_5) throw new Unsupported("StringBuilder is newer than the classfile target");
                        yield new Slot(new BuilderAllocation());
                    }
                    default -> throw opcode(insn);
                };
                case GETSTATIC -> {
                    var read = (FieldInsnNode) insn;
                    Object constant = read.owner.equals(owner) ? constants.get(new FieldKey(read.name, read.desc)) : null;
                    if (constant != null) yield new Slot(constant);
                    Charset charset = Strings.charset(read);
                    if (charset == null || version < V1_7) throw new Unsupported("Decoder reads unsupported static state");
                    yield new Slot(charset);
                }
                default -> throw opcode(insn);
            };
        }

        @Override
        public Slot copyOperation(AbstractInsnNode insn, Slot value) {
            if (value.data == null) throw opcode(insn);
            return value;
        }

        @Override
        public Slot unaryOperation(AbstractInsnNode insn, Slot input) {
            return switch (insn.getOpcode()) {
                case NEWARRAY -> {
                    int length = integer(input);
                    if (length < 0) throw new NegativeArraySizeException();
                    allocate(length);
                    yield new Slot(switch (((IntInsnNode) insn).operand) {
                        case T_BYTE -> new byte[length];
                        case T_CHAR -> new char[length];
                        case T_INT -> new int[length];
                        default -> throw opcode(insn);
                    });
                }
                case ARRAYLENGTH -> new Slot(switch (input.data) {
                    case byte[] array -> array.length;
                    case char[] array -> array.length;
                    case int[] array -> array.length;
                    default -> throw opcode(insn);
                });
                default -> {
                    Number value = ConstantFlow.unary(insn, number(input));
                    if (value == null) throw opcode(insn);
                    yield new Slot(value);
                }
            };
        }

        @Override
        public Slot binaryOperation(AbstractInsnNode insn, Slot left, Slot right) {
            int op = insn.getOpcode();
            return switch (op) {
                case BALOAD -> new Slot((int) ((byte[]) left.data)[integer(right)]);
                case CALOAD -> new Slot((int) ((char[]) left.data)[integer(right)]);
                case IALOAD -> new Slot(((int[]) left.data)[integer(right)]);
                default -> {
                    Number value = ConstantFlow.binary(op, number(left), number(right));
                    if (value == null) {
                        if (op == IDIV || op == IREM || op == LDIV || op == LREM) throw new ArithmeticException("Division by zero");
                        throw opcode(insn);
                    }
                    yield new Slot(value);
                }
            };
        }

        @Override
        public Slot ternaryOperation(AbstractInsnNode insn, Slot array, Slot index, Slot value) {
            int i = integer(index);
            int n = integer(value);
            switch (insn.getOpcode()) {
                case BASTORE -> ((byte[]) array.data)[i] = (byte) n;
                case CASTORE -> ((char[]) array.data)[i] = (char) n;
                case IASTORE -> ((int[]) array.data)[i] = n;
                default -> throw opcode(insn);
            }
            return null;
        }

        @Override
        public Slot naryOperation(AbstractInsnNode insn, List<? extends Slot> values) throws AnalyzerException {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                var args = values.stream().map(slot -> slot.data() instanceof Text || slot.data() instanceof Allocation
                        ? asText(slot.data()).value : slot.data()).toList();
                String text = StringConcat.evaluate(indy, args);
                if (text == null || version < V9) throw new Unsupported("Unsupported invokedynamic bootstrap or operands");
                for (Object argument : indy.bsmArgs) if (argument instanceof String literal) rememberLiteral(literal);
                return fresh(text);
            }
            if (!(insn instanceof MethodInsnNode call)) throw opcode(insn);
            var nested = target(call);
            if (nested != null) return run(nested, values, depth + 1);
            var codec = Strings.codec(call);
            if (codec != null) {
                if (version < codec.minimumVersion()) throw new Unsupported("Codec is newer than the classfile target");
                return new Slot(codec);
            }
            if (!values.isEmpty() && values.getFirst().data() instanceof Strings.Codec decoder && decoder.decoder(call)) {
                String text = asText(values.get(1).data()).value;
                allocate(text.length());
                return new Slot(decoder.decode(text));
            }
            if (call.owner.equals("java/lang/StringBuilder") && !call.itf && !values.isEmpty()
                    && values.getFirst().data() instanceof BuilderAllocation builder) {
                return builderOperation(call, values, builder);
            }
            if (!call.owner.equals("java/lang/String") || call.itf) throw new Unsupported("Decoder calls an unsupported method");
            if (call.getOpcode() == INVOKESPECIAL && call.name.equals("<init>")) {
                if (!(values.getFirst().data() instanceof Allocation allocation) || allocation.text != null) throw opcode(insn);
                String text = switch (call.desc) {
                    case "([C)V" -> new String((char[]) values.get(1).data());
                    case "([BLjava/nio/charset/Charset;)V" -> Strings.strictText((byte[]) values.get(1).data(), (Charset) values.get(2).data());
                    case "(Ljava/lang/String;)V" -> asText(values.get(1).data()).value;
                    case "()V" -> "";
                    default -> throw new Unsupported("Unsupported String constructor or implicit charset");
                };
                allocation.text = (Text) fresh(text).data;
                return null;
            }
            if (call.getOpcode() != INVOKEVIRTUAL) throw opcode(insn);
            String text = asText(values.getFirst().data()).value;
            return switch (call.name + call.desc) {
                case "length()I" -> new Slot(text.length());
                case "charAt(I)C" -> new Slot((int) text.charAt(integer(values.get(1))));
                case "toCharArray()[C" -> {
                    allocate(text.length());
                    yield new Slot(text.toCharArray());
                }
                default -> throw new Unsupported("Decoder calls an unsupported String operation");
            };
        }

        private Slot builderOperation(MethodInsnNode call, List<? extends Slot> values, BuilderAllocation builder) {
            if (call.getOpcode() == INVOKESPECIAL && call.name.equals("<init>") && builder.contents == null) {
                builder.contents = switch (call.desc) {
                    case "()V" -> new StringBuilder();
                    case "(I)V" -> {
                        int capacity = integer(values.get(1));
                        if (capacity < 0) throw new NegativeArraySizeException();
                        allocate(capacity);
                        yield new StringBuilder(capacity);
                    }
                    case "(Ljava/lang/String;)V" -> {
                        String value = asText(values.get(1).data()).value;
                        allocate(value.length());
                        yield new StringBuilder(value);
                    }
                    default -> throw new Unsupported("Unsupported StringBuilder constructor");
                };
                return null;
            }
            if (call.getOpcode() != INVOKEVIRTUAL || builder.contents == null) throw opcode(call);
            if (call.name.equals("toString") && call.desc.equals("()Ljava/lang/String;")) {
                allocate(builder.contents.length());
                return new Slot(new Text(builder.contents.toString(), Identity.BUILDER_RESULT));
            }
            if (call.name.equals("reverse") && call.desc.equals("()Ljava/lang/StringBuilder;")) {
                allocate(builder.contents.length());
                builder.contents.reverse();
                return values.getFirst();
            }
            if (!call.name.equals("append") || !call.desc.endsWith(")Ljava/lang/StringBuilder;")) throw opcode(call);
            Slot argument = values.size() == 2 ? values.get(1) : EMPTY;
            String text = switch (call.desc) {
                case "(Ljava/lang/String;)Ljava/lang/StringBuilder;" -> argument.data == StringRecovery.NULL
                        ? "null" : asText(argument.data).value;
                case "(I)Ljava/lang/StringBuilder;" -> Integer.toString(integer(argument));
                case "(J)Ljava/lang/StringBuilder;" -> {
                    if (!(argument.data instanceof Long value)) throw opcode(call);
                    yield value.toString();
                }
                case "(C)Ljava/lang/StringBuilder;" -> {
                    int value = integer(argument);
                    if (value != (char) value) throw opcode(call);
                    yield Character.toString((char) value);
                }
                case "(Z)Ljava/lang/StringBuilder;" -> switch (integer(argument)) {
                    case 0 -> "false";
                    case 1 -> "true";
                    default -> throw opcode(call);
                };
                case "([C)Ljava/lang/StringBuilder;" -> new String((char[]) argument.data);
                default -> throw new Unsupported("Unsupported StringBuilder append overload");
            };
            if (text.length() > Strings.LIMIT - builder.contents.length()) throw new Unsupported("StringBuilder text budget exhausted");
            allocate(text.length());
            builder.contents.append(text);
            return values.getFirst();
        }

        private Slot fresh(String text) {
            allocate(text.length());
            return new Slot(new Text(text, Identity.FRESH));
        }

        private void rememberLiteral(String text) {
            if (literals.add(text)) {
                if (literals.size() > 64) throw new Unsupported("Decoder string constant budget exhausted");
                allocate(text.length());
            }
        }

        private void allocate(int length) {
            if (length > Strings.LIMIT || (units -= length) < 0 || (classUnits -= length) < 0) {
                throw new Unsupported("Decoder text or array budget exhausted");
            }
        }

        @Override public void returnOperation(AbstractInsnNode insn, Slot value, Slot expected) { throw opcode(insn); }
        @Override public Slot merge(Slot left, Slot right) { throw new IllegalStateException("No merge on a proven path"); }
    }

    private static boolean branch(int opcode, Frame<Slot> frame) {
        if (opcode == GOTO) return true;
        Slot right = frame.pop();
        if (opcode == IFNULL || opcode == IFNONNULL) {
            if (right.data == null || right.data instanceof Number) throw new Unsupported("Unknown reference in decoder branch");
            return (right.data == StringRecovery.NULL) == (opcode == IFNULL);
        }
        if (opcode >= IFEQ && opcode <= IFLE) return Rewrites.matches(Integer.compare(integer(right), 0), opcode - IFEQ);
        if (opcode >= IF_ICMPEQ && opcode <= IF_ICMPLE) {
            return Rewrites.matches(Integer.compare(integer(frame.pop()), integer(right)), opcode - IF_ICMPEQ);
        }
        throw new Unsupported("Unsupported decoder branch opcode " + opcode);
    }

    private static Slot peek(Frame<Slot> frame, int fromTop) {
        return frame.getStack(frame.getStackSize() - 1 - fromTop);
    }

    private static Unsupported opcode(AbstractInsnNode insn) {
        return new Unsupported("Unsupported decoder opcode " + insn.getOpcode());
    }

    private static int integer(Slot slot) {
        if (slot.data instanceof Integer value) return value;
        throw new Unsupported("Expected integer operand in decoder");
    }

    private static Number number(Slot slot) {
        if (slot.data instanceof Number value) return value;
        throw new Unsupported("Expected numeric operand in decoder");
    }

    private static Text asText(Object object) {
        if (object instanceof Text text) return text;
        if (object instanceof Allocation allocation && allocation.text != null) return allocation.text;
        if (object == StringRecovery.NULL) throw new NullPointerException("Null decoder string");
        throw new Unsupported("Expected initialized String in decoder");
    }

    private static void requireType(Type type, Object value) {
        boolean supported = switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> value instanceof Integer;
            case Type.LONG -> value instanceof Long;
            case Type.ARRAY -> switch (type.getDescriptor()) {
                case "[B" -> value instanceof byte[];
                case "[C" -> value instanceof char[];
                case "[I" -> value instanceof int[];
                default -> false;
            };
            case Type.OBJECT -> type.equals(StringRecovery.STRING) && (value instanceof Text || value == StringRecovery.NULL
                    || value instanceof Allocation allocation && allocation.text != null);
            default -> false;
        };
        if (!supported) throw new Unsupported("Unsupported decoder parameter or result type");
    }
}
