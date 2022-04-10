package org.jetbrains.research.libsl.asg

sealed class Node {
    open val parent: NodeHolder = NodeHolder()
}

data class Library(
    val metadata: MetaNode,
    val imports: List<String>,
    val includes: List<String> ,
    val semanticTypes: List<Type>,
    val automata: List<Automaton>,
    val extensionFunctions: Map<String, List<Function>>,
    val globalVariables: Map<String, GlobalVariableDeclaration>
) : Node()

data class MetaNode(
    val name: String,
    val libraryVersion: String?,
    val language: String?,
    val url: String?,
    val lslVersion: Triple<UInt, UInt, UInt>?
) : Node() {
    val stringVersion: String?
        get() {
            if (lslVersion == null) return null
            return "${lslVersion.first}.${lslVersion.second}.${lslVersion.third}"
        }
}

sealed interface Type {
    val name: String
    val isPointer: Boolean
    val context: LslContext
    val generic: Type?

    val fullName: String
        get() = "${if (isPointer) "*" else ""}$name"

    val isArray: Boolean
        get() = (this as? TypeAlias)?.originalType?.isArray == true || this is ArrayType

    fun resolveFieldType(name: String): Type? {
        return when (this) {
            is EnumLikeSemanticType -> {
                this.entries.firstOrNull { it.first == name } ?: return null
                this.childrenType
            }
            is EnumType -> {
                this.entries.firstOrNull { it.first == name } ?: return null
                this.childrenType
            }
            is StructuredType -> {
                this.entries.firstOrNull { it.first == name }?.second
            }
            else -> null
        }
    }
}

sealed class LibslType : Type

data class RealType (
    val nameParts: List<String>,
    override val isPointer: Boolean,
    override val generic: Type?,
    override val context: LslContext
) : Type {
    override val name: String
        get() = nameParts.joinToString(".")

    override fun toString(): String = "${if (isPointer) "*" else ""}$name<${generic?.fullName}>"
}

data class SimpleType(
    override val name: String,
    val realType: Type,
    override val isPointer: Boolean,
    override val context: LslContext
) : LibslType() {
    override val generic: Type? = null
}

data class TypeAlias (
    override val name: String,
    val originalType: Type,
    override val context: LslContext
) : LibslType() {
    override val isPointer: Boolean = false
    override val generic: Type? = null
}

data class EnumLikeSemanticType(
    override val name: String,
    val type: Type,
    val entries: List<Pair<String, Atomic>>,
    override val context: LslContext
) : LibslType() {
    override val isPointer: Boolean = false
    override val generic: Type? = null

    val childrenType: Type = ChildrenType(name, context)
}

class ChildrenType(
    override val name: String,
    override val context: LslContext,
) : Type {
    override val generic: Type? = null
    override val isPointer: Boolean = false
}

data class StructuredType(
    override val name: String,
    val type: Type,
    override val generic: Type?,
    val entries: List<Pair<String, Type>>,
    override val context: LslContext
) : LibslType() {
    override val isPointer: Boolean = false
}

data class EnumType(
    override val name: String,
    val entries: List<Pair<String, Atomic>>,
    override val context: LslContext
) : LibslType() {
    override val isPointer: Boolean = false
    override val generic: Type? = null

    val childrenType: Type = ChildrenType(name, context)
}

data class ArrayType(
    override val name: String,
    override val isPointer: Boolean,
    override val generic: Type,
    override val context: LslContext
) :  LibslType()

data class Automaton(
    val name: String,
    val type: Type,
    var states: List<State>,
    var shifts: List<Shift>,
    var internalVariables: List<AutomatonVariableDeclaration>,
    var constructorVariables: List<Variable>,
    var localFunctions: List<Function>
) : Node() {
    val functions: List<Function>
        get() = localFunctions + (parent.node as Library).extensionFunctions[name].orEmpty()
    val variables: List<Variable>
        get() = internalVariables + constructorVariables
}

data class State(
    val name: String,
    val kind: StateKind,
    val isSelf: Boolean = false,
    val isAny: Boolean = false,
) : Node() {
    lateinit var automaton: Automaton
}

data class Shift(
    val from: State,
    val to: State,
    val functions: List<Function>
) : Node()

enum class StateKind {
    INIT, SIMPLE, FINISH;

    companion object {
        fun fromString(str: String) = when(str) {
            "finishstate" -> FINISH
            "state" -> SIMPLE
            "initstate" -> INIT
            else -> error("unknown state kind: $str")
        }
    }
}

data class Function(
    val name: String,
    val automatonName: String,
    var args: List<FunctionArgument>,
    val returnType: Type?,
    var contracts: List<Contract>,
    var statements: List<Statement>,
    val context: LslContext,
    val hasBody: Boolean
) : Node() {
    val automaton: Automaton by lazy { context.resolveAutomaton(automatonName) ?: error("unresolved automaton") }
    val qualifiedName: String by lazy { "${automaton.name}.$name" }
    lateinit var target: Automaton
    var resultVariable: Variable? = null
}

sealed class Statement: Node()

data class Assignment(
    val left: QualifiedAccess,
    val value: Expression
) : Statement()

data class Action(
    val name: String,
    val arguments: List<Expression>
) : Statement()

data class Contract(
    val name: String?,
    val expression: Expression,
    val kind: ContractKind
) : Node()

enum class ContractKind {
    REQUIRES, ENSURES
}

sealed class Expression: Node()

data class BinaryOpExpression(
    val left: Expression,
    val right: Expression,
    val op: ArithmeticBinaryOps
) : Expression()

enum class ArithmeticBinaryOps {
    ADD, SUB, MUL, DIV, AND, OR, XOR, MOD, EQ, NOT_EQ, GT, GT_EQ, LT, LT_EQ;
    companion object {
        fun fromString(str: String) = when (str) {
            "*" -> MUL
            "/" -> DIV
            "+" -> ADD
            "-" -> SUB
            "%" -> MOD
            "=" -> EQ
            "!=" -> NOT_EQ
            ">=" -> GT_EQ
            ">" -> GT
            "<=" -> LT_EQ
            "<" -> LT
            "&" -> AND
            "|" -> OR
            "^" -> XOR
            else -> error("unknown binary operator type")
        }
    }
}

data class UnaryOpExpression(
    val value: Expression,
    val op: ArithmeticUnaryOp
) : Expression()

enum class ArithmeticUnaryOp {
    MINUS, INVERSION
}

sealed class Variable : Expression() {
    abstract val name: String
    abstract val type: Type
    open val initValue: Expression? = null

    open val fullName: String
        get() = name
}

data class GlobalVariableDeclaration(
    override val name: String,
    override val type: Type,
    override val initValue: Expression?
) : Variable()

data class AutomatonVariableDeclaration(
    override val name: String,
    override val type: Type,
    override var initValue: Expression?
) : Variable() {
    lateinit var automaton: Automaton

    override val fullName: String
        get() = "${automaton.name}.${name}"
}

data class FunctionArgument(
    override val name: String,
    override val type: Type,
    val index: Int,
    val annotation: Annotation?
) : Variable() {
    lateinit var function: Function

    override val fullName: String
        get() = "${function.name}.$name"
}

data class ResultVariable(
    override val type: Type
) : Variable() {
    override val name: String = "result"
}

open class Annotation(
    val name: String,
    val values: List<Expression>
) {
    override fun toString(): String {
        return "Annotation(name='$name', values=$values)"
    }
}

class TargetAnnotation(
    name: String,
    values: List<Expression>,
    val targetAutomaton: Automaton
) : Annotation(name, values) {
    override fun toString(): String {
        return "TargetAnnotation(name='$name', values=$values, target=$targetAutomaton)"
    }
}

data class ConstructorArgument(
    override val name: String,
    override val type: Type,
) : Variable() {
    lateinit var automaton: Automaton

    override val fullName: String
        get() = "${automaton.name}.$name"
}

sealed class QualifiedAccess : Atomic() {
    abstract var childAccess: QualifiedAccess?
    abstract val type: Type

    override fun toString(): String = (childAccess?.toString() ?: "") + ":${type.fullName}"

    override val value: Any? = null

    val lastChild: QualifiedAccess
        get() = childAccess?.lastChild ?: childAccess ?: this
}

data class VariableAccess(
    val fieldName: String,
    override var childAccess: QualifiedAccess?,
    override val type: Type,
    val variable: Variable?
) : QualifiedAccess() {
    override fun toString(): String = "$fieldName${childAccess?.toString()?.let { ".$it" } ?: ""}"
}

data class AccessAlias(
    override var childAccess: QualifiedAccess?,
    override val type: Type
) : QualifiedAccess() {
    override fun toString(): String = "alias[${type.fullName}].${childAccess}"
}

data class RealTypeAccess(
    override val type: RealType
) : QualifiedAccess() {
    override var childAccess: QualifiedAccess? = null

    override fun toString(): String = type.name
}

data class ArrayAccess(
    var index: Atomic?,
    override val type: Type
) : QualifiedAccess() {
    override var childAccess: QualifiedAccess? = null

    override fun toString(): String = "${type.fullName}[index]"
}

data class AutomatonGetter(
    val automaton: Automaton,
    val arg: FunctionArgument,
    override var childAccess: QualifiedAccess?,
) : QualifiedAccess() {
    override val type: Type = automaton.type

    override fun toString(): String = "${automaton.name}(${arg.name}).${childAccess.toString()}"
}

data class OldValue(
    val value: QualifiedAccess
) : Expression()

data class CallAutomatonConstructor(
    val automaton: Automaton,
    val args: List<ArgumentWithValue>,
    val state: State
) : Atomic() {
    override val value: Any? = null
}

data class ArgumentWithValue(
    val variable: Variable,
    val init: Expression
)

sealed class Atomic : Expression() {
    abstract val value: Any?
}

data class IntegerLiteral(
    override val value: Int
) : Atomic()

data class FloatLiteral(
    override val value: Float
) : Atomic()

data class StringLiteral(
    override val value: String
) : Atomic()

data class BoolLiteral(
    override val value: Boolean
) : Atomic()