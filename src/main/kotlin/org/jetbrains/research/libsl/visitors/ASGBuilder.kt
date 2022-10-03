package org.jetbrains.research.libsl.visitors

import org.antlr.v4.runtime.RuleContext
import org.jetbrains.research.libsl.LibSLParser
import org.jetbrains.research.libsl.LibSLParserBaseVisitor
import org.jetbrains.research.libsl.asg.*
import org.jetbrains.research.libsl.asg.Function
import org.jetbrains.research.libsl.errors.*

class ASGBuilder(
    private val context: LslContext,
    val errorManager: ErrorManager
    ) : LibSLParserBaseVisitor<Node>() {
    override fun visitFile(ctx: LibSLParser.FileContext): Library {
        val header = ctx.header()
        val libraryName = header.libraryName?.processIdentifier() ?: error("no library name specified")
        val language = header.lang?.processIdentifier()?.removeSurrounding("\"", "\"")
        val libraryVersion = header.ver?.processIdentifier()?.removeSurrounding("\"", "\"")
        val lslVersion = header.lslver.processIdentifier().removeSurrounding("\"", "\"").split(".").let {
            val parts = it.map { part -> part.toUInt() }
            Triple(parts[0], parts[1], parts[2])
        }
        val url = header?.link?.processIdentifier()?.removeSurrounding("\"", "\"")

        val meta = MetaNode(libraryName, libraryVersion, language, url, lslVersion)
        val imports = ctx.globalStatement().mapNotNull { it.ImportStatement() }.map {
            parseStringTokenStringSemicolon(it.processIdentifier(), "import")
        }.toMutableList()
        val includes = ctx.globalStatement().mapNotNull { it.IncludeStatement() }.map {
            parseStringTokenStringSemicolon(it.processIdentifier(), "include")
        }.toMutableList()

        val automata = ctx.globalStatement()
            .mapNotNull { it.topLevelDecl()?.automatonDecl() }
            .map { visitAutomatonDecl(it) }
            .toMutableList()
        val nonlocalFunctions = ctx.globalStatement()
            .mapNotNull { it.topLevelDecl()?.functionDecl() }
            .map { visitFunctionDecl(it) }

        val types = context.typeStorage.map { it.value }.toMutableList()

        val library = Library(
            meta,
            imports,
            includes,
            types,
            automata,
            nonlocalFunctions.fold(mutableMapOf()) { old, curr ->
                    old.getOrPut(curr.automatonName) { mutableListOf()}.add(curr); old
                },
            context.globalVariables
        )

        automata.forEach { it.parent.node = library }
        nonlocalFunctions.forEach { it.parent.node = library }

        return library
    }

    override fun visitAutomatonDecl(ctx: LibSLParser.AutomatonDeclContext): Automaton {
        val name = ctx.name.processIdentifier()
        val automaton = context.resolveAutomaton(name)
        val states = automaton!!.states
        val shifts = ctx.automatonStatement()?.filter { it.automatonShiftDecl() != null }?.flatMap { shiftCtx ->
            val parsedShift = shiftCtx.automatonShiftDecl()
            val toName = parsedShift.to?.processIdentifier()!!
            if (parsedShift.identifierList() != null) {
                parsedShift.identifierList().Identifier().map { fromIdentifier ->
                    val fromName = fromIdentifier.processIdentifier()

                    processShift(fromName, toName, shiftCtx, states, automatonName = name)
                }
            } else {
                val fromName = parsedShift.from.processIdentifier()
                listOf(processShift(fromName, toName, shiftCtx, states, automatonName = name))
            }
        }.orEmpty().toMutableList()

        val functions = ctx.automatonStatement()
            .mapNotNull { it.functionDecl() }
            .map { visitFunctionDecl(it) }
            .toMutableList()

        automaton.apply {
            this.shifts = shifts
            this.localFunctions = functions
        }

        functions.forEach { it.parent.node = automaton }

        return automaton
    }

    private fun processShift(
        fromName: String,
        toName: String,
        shiftCtx: LibSLParser.AutomatonStatementContext,
        states: List<State>,
        automatonName: String
    ): Shift {
        val parsedShift = shiftCtx.automatonShiftDecl()
        val fromState = if (fromName == "any") {
            State("any", StateKind.SIMPLE, isAny = true)
        } else {
            val state = states.firstOrNull { it.name == fromName }
            if (state == null) {
                errorManager(UnresolvedState(fromName, parsedShift.position()))
                State("unresolved", StateKind.SIMPLE)
            } else state
        }

        val toState = if (toName == "self") {
            State("self", StateKind.SIMPLE, isSelf = true)
        } else {
            val state = states.firstOrNull { it.name == toName }
            if (state == null) {
                errorManager(UnresolvedState(toName, parsedShift.position()))
                State("unresolved", StateKind.SIMPLE)
            } else state
        }

        val functions = parsedShift.functionsList()?.functionsListPart()?.mapNotNull { part ->
            val functionName = part.name.processIdentifier()
            val functionArgs = part.Identifier().drop(1).map { type ->
                val typeName = type.processIdentifier()
                val resolved = context.resolveType(typeName)
                if (resolved == null) {
                    errorManager(UnresolvedType(typeName, type.symbol.position()))
                    return@mapNotNull null
                }
                resolved
            }

            val resolved = context.resolveFunction(functionName, automatonName = automatonName, argsType=functionArgs)
                ?: context.resolveFunction(functionName, automatonName = automatonName)

            if (resolved == null) {
                errorManager(UnresolvedFunction(functionName, part.name.position()))
                null
            } else {
                resolved
            }
        }?.toMutableList() ?: error("empty functions list in shift $fromName -> $toName")

        return Shift(
            fromState,
            toState,
            functions
        )
    }

    override fun visitAssignmentRight(ctx: LibSLParser.AssignmentRightContext): Expression = when {
        ctx.expression() != null -> {
            visitExpression(ctx.expression())
        }
        ctx.callAutomatonConstructorWithNamedArgs() != null -> {
            visitCallAutomatonConstructorWithNamedArgs(ctx.callAutomatonConstructorWithNamedArgs())
        }
        else -> error("can't parse init value in variable")
    }

    override fun visitCallAutomatonConstructorWithNamedArgs(ctx: LibSLParser.CallAutomatonConstructorWithNamedArgsContext): Atomic {
        val calleeName = ctx.name.processIdentifier()
        val calleeAutomaton = context.resolveAutomaton(calleeName) ?: error("can't resolve automaton $calleeName")

        val args = ctx.namedArgs()?.argPair()?.mapNotNull { pair ->
            val name = pair.name.processIdentifier()
            if (name == "state") {
                null
            } else {
                val targetVariable = calleeAutomaton.constructorVariables.first { it.name == name }
                val value = visitExpression(pair.expression())
                ArgumentWithValue(targetVariable, value)
            }
        }.orEmpty()

        val stateName = ctx.namedArgs()?.argPair()?.firstOrNull { it.name.processIdentifier() == "state" }?.expressionAtomic()?.text
        val state = calleeAutomaton.states.firstOrNull { it.name == stateName } ?: error("unresolved state: $stateName")

        return CallAutomatonConstructor(calleeAutomaton, args, state)
    }

    override fun visitFunctionDecl(ctx: LibSLParser.FunctionDeclContext): Function {
        val (ownerAutomatonName, functionName) = parseFunctionName(ctx)
        var argIndex = 0
        val args = ctx.functionDeclArgList()?.parameter()?.map { arg ->
            val argName = arg.name.processIdentifier()
            val argAnnotationName = arg.annotation()?.Identifier()?.processIdentifier()

            val argType = if (argAnnotationName == "target") {
                val targetAutomatonName = arg.type.processIdentifier()

                context.resolveAutomaton(targetAutomatonName)?.type
            } else {
                context.resolveType(arg.type.processIdentifier())
            }

            if (argType == null) {
                error("unresolved type")
            }

            val annotation = arg.annotation()?.let { anno ->
                val name = anno.Identifier().processIdentifier()
                val values = anno.valuesAndIdentifiersList()?.expression()?.map { atomic ->
                    visitExpression(atomic)
                }.orEmpty().toMutableList()

                if (name == "target") {
                    val targetAutomatonName = arg.type.processIdentifier()
                    val targetAutomaton = context.resolveAutomaton(targetAutomatonName)!!

                    TargetAnnotation(name, values, targetAutomaton)
                } else {
                    Annotation(name, values)
                }
            }

            FunctionArgument(argName, argType, argIndex++, annotation)
        }.orEmpty().toMutableList()

        val typeName = ctx.functionType?.processIdentifier()
        val type = if (typeName != null) context.resolveType(typeName) ?: error("unresolved type: $typeName") else null

        val preamble = ctx.functionPreamble()?.preamblePart()?.map { part ->
            when {
                part.requiresContract() != null -> {
                    val contractName = part.requiresContract().name?.processIdentifier()
                    val contractExpression = visitExpression(part.requiresContract().expression())
                    Contract(contractName, contractExpression, ContractKind.REQUIRES)
                }
                part.ensuresContract() != null -> {
                    val contractName = part.ensuresContract().name?.processIdentifier()
                    val contractExpression = visitExpression(part.ensuresContract().expression())
                    Contract(contractName, contractExpression, ContractKind.ENSURES)
                }
                else -> error("unknown function statement's type: $ownerAutomatonName.$functionName")
            }
        }.orEmpty().toMutableList()

        val statementList = ctx.functionBody()?.functionBodyStatements()?.map { variableStatement ->
            when {
                variableStatement.variableAssignment() != null -> {
                    val variableAssignment = variableStatement.variableAssignment()
                    val value = visitAssignmentRight(variableAssignment.assignmentRight())
                    val variable = visitQualifiedAccess(variableAssignment.qualifiedAccess())

                    Assignment(variable, value)
                }

                variableStatement.action() != null -> {
                    val action = variableStatement.action()
                    val actionName = action.Identifier().processIdentifier()
                    val actionArgs = action
                        .valuesAndIdentifiersList()
                        ?.expression()
                        ?.map { visitExpression(it) }
                        .orEmpty()
                        .toMutableList()

                    Action(
                        actionName,
                        actionArgs
                    )
                }

                else -> error("unknown statement type")
            }
        }.orEmpty().toMutableList()

        val argumentWithTargetAnno = args.firstOrNull { it.annotation?.name == "target" }
        val resolvedFunction = context.resolveFunction(
            functionName,
            automatonName=ownerAutomatonName,
            argsType = args.map { it.type },
            returnType = type
        ) ?: error("error on parsing function: $ownerAutomatonName.$functionName")

        if (argumentWithTargetAnno?.annotation?.name == "target") {
            val target = (argumentWithTargetAnno.annotation as TargetAnnotation).targetAutomaton
            resolvedFunction.target = target
        } else {
            val automatonName = resolvedFunction.automatonName
            resolvedFunction.target = context.resolveAutomaton(automatonName) ?: error("unresolved automaton: $automatonName")
        }

        args.forEach { arg ->
            arg.function = resolvedFunction
        }

        return resolvedFunction.apply {
            contracts = preamble
            statements = statementList
            this.args = args
        }
    }

    override fun visitEnsuresContract(ctx: LibSLParser.EnsuresContractContext): Contract {
        return Contract(
            name = ctx.name?.processIdentifier(),
            expression = visitExpression(ctx.expression()),
            kind = ContractKind.ENSURES
        )
    }

    override fun visitRequiresContract(ctx: LibSLParser.RequiresContractContext): Contract {
        return Contract(
            name = ctx.name?.processIdentifier(),
            expression = visitExpression(ctx.expression()),
            kind = ContractKind.REQUIRES
        )
    }

    override fun visitExpression(ctx: LibSLParser.ExpressionContext): Expression {
        return when {
            ctx.apostrophe != null -> OldValue(visitQualifiedAccess(ctx.qualifiedAccess()))
            ctx.MINUS() != null -> {
                val content = visitExpression(ctx.expression(0))

                UnaryOpExpression(content, ArithmeticUnaryOp.MINUS)
            }
            ctx.EXCLAMATION() != null -> {
                val content = visitExpression(ctx.expression(0))

                UnaryOpExpression(content, ArithmeticUnaryOp.INVERSION)
            }
            ctx.expressionAtomic() != null -> visitExpressionAtomic(ctx.expressionAtomic())
            ctx.qualifiedAccess() != null -> visitQualifiedAccess(ctx.qualifiedAccess())
            ctx.lbracket != null -> visitExpression(ctx.expression().first())
            else -> {
                val left = visitExpression(ctx.expression(0))
                val right = visitExpression(ctx.expression(1))
                val op = ArithmeticBinaryOps.fromString(ctx.op.processIdentifier())

                BinaryOpExpression(left, right, op)
            }
        }
    }

    internal fun processAssignmentRight(ctx: LibSLParser.AssignmentRightContext) = when {
        ctx.expression() != null -> {
            visitExpression(ctx.expression())
        }
        ctx.callAutomatonConstructorWithNamedArgs() != null -> {
            visitCallAutomatonConstructorWithNamedArgs(ctx.callAutomatonConstructorWithNamedArgs())
        }
        else -> error("can't parse init value in variable")
    }

    override fun visitExpressionAtomic(ctx: LibSLParser.ExpressionAtomicContext): Atomic = when {
        ctx.primitiveLiteral()?.integerNumber() != null -> IntegerLiteral(ctx.primitiveLiteral().integerNumber().text.toInt())
        ctx.primitiveLiteral()?.floatNumber() != null -> FloatLiteral(ctx.primitiveLiteral().floatNumber().text.toFloat())
        ctx.primitiveLiteral()?.DoubleQuotedString() != null -> StringLiteral(ctx.primitiveLiteral().DoubleQuotedString().processIdentifier().removeDoubleQuotes())
        ctx.primitiveLiteral()?.bool != null -> BoolLiteral(ctx.primitiveLiteral()!!.bool!!.processIdentifier().toBoolean())
        ctx.qualifiedAccess() != null -> visitQualifiedAccess(ctx.qualifiedAccess())
        else -> error("unknown atomic type")
    }

    override fun visitQualifiedAccess(ctx: LibSLParser.QualifiedAccessContext): QualifiedAccess {
        return when {
            ctx.periodSeparatedFullName() != null -> {
                val names = ctx.periodSeparatedFullName().Identifier().map { it.processIdentifier() }
                val name = names.first()

                val variable = resolveVariableDependingOnContext(ctx, name, context) ?: error("can't resolve variable: $name")
                val baseType = variable.type
                VariableAccess(
                    name,
                    resolvePeriodSeparatedChain(baseType, names.drop(1)),
                    baseType,
                    variable
                )
            }

            ctx.simpleCall() != null -> {
                val automatonName = ctx.simpleCall().Identifier(0).processIdentifier()
                val automaton = context.resolveAutomaton(automatonName) ?: error("unresolved automaton: $automatonName")
                val functionCtx = ctx.getParentOfType<LibSLParser.FunctionDeclContext>() ?: error("access not in function")
                val function = resolveFunctionByCtx(functionCtx) ?: error("unresolved function")

                val argName = ctx.simpleCall().Identifier(1).processIdentifier()
                val arg = function.args.firstOrNull { it.name == argName } ?: error("unresolved argument: $argName")

                AutomatonGetter(
                    automaton,
                    arg,
                    visitQualifiedAccess(ctx.qualifiedAccess())
                )
            }

            ctx.qualifiedAccess() != null -> {
                val parentQualifiedAccess = visitQualifiedAccess(ctx.qualifiedAccess())

                return if (ctx.expressionAtomic() != null) {
                    val indexCtx = ctx.expressionAtomic()
                    val index = visitExpressionAtomic(indexCtx)

                    parentQualifiedAccess.apply {
                        val child = ArrayAccess(
                            index,
                            lastChild.type
                        )

                        if (!(lastChild.type.isArray || lastChild is ArrayAccess && lastChild.type.isArray) ) {
                            error("variable access can't be performed on non-array type: ${parentQualifiedAccess.type.name}")
                        }
                        lastChild.childAccess = child
                    }
                } else {
                    parentQualifiedAccess
                }
            }
            else -> error("unknown qualified access kind")
        }
    }

    private fun resolvePeriodSeparatedChain(parentType: Type, names: List<String>): QualifiedAccess? {
        if (names.isEmpty()) return null
        val name = names.first()
        return when(parentType) {
            is StructuredType -> {
                val entry = parentType.entries.firstOrNull { it.first == name }
                    ?: error("unresolved field $name in type ${parentType.name}")
                VariableAccess(
                    name,
                    resolvePeriodSeparatedChain(entry.second, names.drop(1)),
                    entry.second,
                    null
                )
            }
            is EnumType -> {
                val entry = parentType.entries.firstOrNull { it.first == name }
                    ?: error("unresolved field $name in type ${parentType.name}")
                VariableAccess(
                    entry.first,
                    null,
                    parentType,
                    null
                )
            }
            is EnumLikeSemanticType -> {
                val entry = parentType.entries.firstOrNull { it.first == name }
                    ?: error("unresolved field $name in type ${parentType.name}")
                VariableAccess(
                    entry.first,
                    null,
                    parentType,
                    null
                )
            }
            is TypeAlias -> {
                resolvePeriodSeparatedChain(parentType.originalType, names.drop(1))
            }
            else -> {
                if (names.size == 1) {
                    VariableAccess(
                        name,
                        null,
                        parentType.resolveFieldType(name) ?: error("can't resolve field $name in type ${parentType.name}"),
                        null
                    )
                } else {
                    error("can't resolve access chain. Unsupported part type: ${parentType::class.java}")
                }
            }
        }
    }

    private fun resolveFunctionByCtx(ctx: LibSLParser.FunctionDeclContext): Function? {
        var argumentIndex = 0
        val args = ctx.functionDeclArgList()?.parameter()?.map { arg ->
            val argName = arg.name.processIdentifier()
            val argType = context.resolveType(arg.type.processIdentifier()) ?: error("unresolved type")
            val annotation = arg.annotation()?.let { anno ->
                Annotation(anno.Identifier().processIdentifier(), anno.valuesAndIdentifiersList().expression().map { atomic ->
                    visitExpression(atomic)
                }.toMutableList())
            }
            FunctionArgument(argName, argType, argumentIndex++, annotation)
        }.orEmpty()

        return if (ctx.name.Identifier().size > 1) {
            // an extension function case
            val automatonName = ctx.name.Identifier(0)?.processIdentifier()
            val funcName = ctx.name.processIdentifier().removePrefix("$automatonName.")
            context.resolveFunction(funcName, automatonName, args)
        } else {
            // a standalone function case
            val funcName = ctx.name.Identifier().first().processIdentifier()
            val automaton = ctx.parent?.parent as? LibSLParser.AutomatonDeclContext
                ?: error("non-extension function $funcName not in automaton")
            val automatonName = automaton.name.processIdentifier()
            context.resolveFunction(funcName, automatonName, args)
        }
    }

    private fun resolveVariableDependingOnContext(ctx: RuleContext, variableName: String, context: LslContext): Variable? {
        val res = when (ctx) {
            is LibSLParser.FunctionDeclContext -> {
                val func = resolveFunctionByCtx(ctx)!!
                if (variableName == "result") {
                    func.resultVariable
                } else {
                    func.args.firstOrNull { it.name == variableName }
                        ?: func.automaton.variables.firstOrNull {
                            it.name == variableName
                        }
                }
            }
            is LibSLParser.AutomatonDeclContext -> {
                val automatonName = ctx.name.processIdentifier()
                val automaton = context.resolveAutomaton(automatonName)!!
                automaton.variables.firstOrNull { it.name == variableName }
            }
            else -> null
        }
        return if (ctx.parent == null) {
            context.resolveVariable(variableName)
        } else {
            res ?: resolveVariableDependingOnContext(ctx.parent, variableName, context)
        }
    }
}
