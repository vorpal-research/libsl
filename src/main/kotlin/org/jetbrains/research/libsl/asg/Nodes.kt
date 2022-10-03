package org.jetbrains.research.libsl.asg

import org.jetbrains.research.libsl.utils.IPrinter
import org.jetbrains.research.libsl.utils.IPrinter.Companion.SPACE

sealed class Node {
    open val parent: NodeHolder = NodeHolder()
}

data class Library(
    val metadata: MetaNode,
    val imports: MutableList<String> = mutableListOf(),
    val includes: MutableList<String> = mutableListOf(),
    val semanticTypes: MutableList<Type> = mutableListOf(),
    val automata: MutableList<Automaton> = mutableListOf(),
    val extensionFunctions: MutableMap<String, MutableList<Function>> = mutableMapOf(),
    val globalVariables: MutableMap<String, GlobalVariableDeclaration> = mutableMapOf()
) : Node(), IPrinter {
    override fun dumpToString(): String = buildString {
        appendLine(metadata.dumpToString())
        append(formatImports(imports))
        append(formatIncludes(includes))
    }

    private fun formatImports(imports: Collection<String>): String {
        return simpleCollectionFormatter(prefix = "import$SPACE", suffix = ";", imports)
    }
    private fun formatIncludes(includes: Collection<String>): String {
        return simpleCollectionFormatter(prefix = "include$SPACE", suffix = ";", includes)
    }
}

class MetaNode(
    var name: String,
    val libraryVersion: String? = null,
    val language: String? = null,
    var url: String? = null,
    val lslVersion: Triple<UInt, UInt, UInt>
) : Node(), IPrinter {
    val stringVersion: String
        get() {
            return "${lslVersion.first}.${lslVersion.second}.${lslVersion.third}"
        }

    // libsl "$libslVersion";
    // library $libraryName version "$libraryVersion" language "$language" url "libraryUrl"
    override fun dumpToString(): String = buildString {
        appendLine("libsl \"$stringVersion\";")
        append("library $name")

        if (libraryVersion != null) {
            append(SPACE + "version $libraryVersion")
        }

        if (language != null) {
            append(SPACE + "language \"$language\"")
        }

        if (url != null) {
            append(SPACE + "url \"$url\"")
        }
        appendLine(";")
    }
}

sealed interface Type : IPrinter {
    val name: String
    val isPointer: Boolean
    val context: LslContext
    val generic: Type?

    val fullName: String
        get() = "${if (isPointer) "*" else ""}$name${if (generic != null) "<${generic!!.fullName}>" else ""}"

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

sealed interface LibslType : Type

data class RealType (
    val nameParts: List<String>,
    override val isPointer: Boolean = false,
    override val generic: Type? = null,
    override val context: LslContext
) : Type {
    override val name: String
        get() = nameParts.joinToString(".")

    override fun toString(): String = "${if (isPointer) "*" else ""}$name<${generic?.fullName}>"

    override fun dumpToString(): String {
        return toString()
    }
}

data class SimpleType(
    override val name: String,
    val realType: Type,
    override val isPointer: Boolean = false,
    override val context: LslContext
) : LibslType {
    override val generic: Type? = null

    override fun dumpToString(): String {
        return "$name(${realType.dumpToString()})"
    }
}

sealed interface AliassableType : LibslType

data class TypeAlias (
    override val name: String,
    val originalType: AliassableType,
    override val context: LslContext
) : LibslType {
    override val isPointer: Boolean = false
    override val generic: Type? = null

    override fun dumpToString(): String {
        return "typealias $name = ${originalType.fullName};"
    }
}

data class EnumLikeSemanticType(
    override val name: String,
    val type: Type,
    val entries: List<Pair<String, Atomic>>,
    override val context: LslContext
) : LibslType {
    override val isPointer: Boolean = false
    override val generic: Type? = null

    val childrenType: Type = ChildrenType(name, context)

    override fun dumpToString(): String = buildString {
        appendLine("$name(${type.fullName}) {")
        val formattedEntries = entries.map { (k, v) -> "$k: ${v.value}" }
        withIndent(simpleCollectionFormatter("", ";", formattedEntries))
        appendLine("}")
    }
}

class ChildrenType(
    override val name: String,
    override val context: LslContext,
) : Type {
    override val generic: Type? = null
    override val isPointer: Boolean = false

    override fun dumpToString(): String {
        error("unsupported operation exception")
    }
}

data class StructuredType(
    override val name: String,
    val type: Type,
    override val generic: Type? = null,
    val entries: List<Pair<String, Type>>,
    override val context: LslContext
) : AliassableType {
    override val isPointer: Boolean = false

    override fun dumpToString(): String = buildString {
        appendLine("${type.fullName} {")
        val formattedEntries = entries.map { (k, v) -> "$k: ${v.fullName}" }
        withIndent(simpleCollectionFormatter("", ";", formattedEntries))
        appendLine("}")
    }
}

data class EnumType(
    override val name: String,
    val entries: List<Pair<String, Atomic>>,
    override val context: LslContext
) : AliassableType {
    override val isPointer: Boolean = false
    override val generic: Type? = null

    val childrenType: Type = ChildrenType(name, context)

    override fun dumpToString(): String = buildString {
        appendLine("$name {")
        val formattedEntries = entries.map { (k, v) -> "$k: ${v.value}" }
        withIndent(simpleCollectionFormatter("", ";", formattedEntries))
        appendLine("}")
    }
}

data class ArrayType(
    override val name: String,
    override val isPointer: Boolean = false,
    override val generic: Type,
    override val context: LslContext
) :  AliassableType {
    override fun dumpToString(): String {
        return fullName
    }
}

data class Automaton(
    val name: String,
    val type: Type,
    var states: MutableList<State> = mutableListOf(),
    var shifts: MutableList<Shift> = mutableListOf(),
    var internalVariables: MutableList<AutomatonVariableDeclaration> = mutableListOf(),
    var constructorVariables: MutableList<ConstructorArgument> = mutableListOf(),
    var localFunctions: MutableList<Function> = mutableListOf()
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
    val functions: MutableList<Function> = mutableListOf()
) : Node()

enum class StateKind {
    INIT, SIMPLE, FINISH;

    internal companion object {
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
    var args: MutableList<FunctionArgument> = mutableListOf(),
    val returnType: Type?,
    var contracts: MutableList<Contract> = mutableListOf(),
    var statements: MutableList<Statement> = mutableListOf(),
    val hasBody: Boolean = statements.isNotEmpty(),
    var target: Automaton? = null,
    val context: LslContext
) : Node() {
    val automaton: Automaton by lazy { context.resolveAutomaton(automatonName) ?: error("unresolved automaton") }
    val qualifiedName: String
        get() = "${automaton.name}.$name"
    var resultVariable: Variable? = null
}

sealed class Statement: Node()

data class Assignment(
    val left: QualifiedAccess,
    val value: Expression
) : Statement()

data class Action(
    val name: String,
    val arguments: MutableList<Expression> = mutableListOf()
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
            else -> error("unknown binary operator type: $str. Only ${ArithmeticBinaryOps.values()} are allowed")
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
    var annotation: Annotation? = null
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
    val values: MutableList<Expression> = mutableListOf()
) {
    override fun toString(): String {
        return "Annotation(name='$name', values=$values)"
    }
}

class TargetAnnotation(
    name: String,
    values: MutableList<Expression>,
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