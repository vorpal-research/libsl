package org.jetbrains.research.libsl.visitors

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.TerminalNode
import org.jetbrains.research.libsl.LibSLLexer
import org.jetbrains.research.libsl.LibSLParser
import org.jetbrains.research.libsl.LibSLParserBaseVisitor
import org.jetbrains.research.libsl.asg.*
import org.jetbrains.research.libsl.asg.BoolLiteral
import org.jetbrains.research.libsl.errors.*
import java.io.File

class Resolver(
    private val context: LslContext,
    private val basePath: String,
    val errorManager: ErrorManager
    ) : LibSLParserBaseVisitor<Unit>() {
    private val asgBuilderVisitor = ASGBuilder(context, errorManager)

    override fun visitFile(ctx: LibSLParser.FileContext) {
        ctx.globalStatement().mapNotNull { it.ImportStatement() }.forEach { processImportStatement(it) }

        val typeSections = ctx.globalStatement().mapNotNull { it.typesSection() }
        if (typeSections.size > 1) {
            errorManager(MoreThanOneTypesSection(typeSections[1].position()))
        }
        for (statement in ctx.globalStatement()) {
            when {
                statement.typesSection() != null -> visitTypesSection(statement.typesSection())
                statement.enumBlock() != null -> visitEnumBlock(statement.enumBlock())
                statement.typeDefBlock() != null -> visitTypeDefBlock(statement.typeDefBlock())
                statement.typealiasStatement() != null -> visitTypealiasStatement(statement.typealiasStatement())
            }
        }

        val automata = ctx.globalStatement().mapNotNull { it.topLevelDecl()?.automatonDecl() }
        for (automatonCtx in automata) {
            val typeName = automatonCtx.type.processIdentifier()
            val type = context.resolveType(typeName)
            if (type == null) {
                errorManager(UnresolvedType(typeName, automatonCtx.type.position()))
                continue
            }

            val variables = automatonCtx.automatonStatement().mapNotNull { it.variableDecl() }.mapNotNull { variable ->
                val variableName = variable.nameWithType().name.processIdentifier()
                val variableTypeName = variable.nameWithType().type.text
                val variableType = context.resolveType(variableTypeName)
                if (variableType == null) {
                    errorManager(UnresolvedType(variableTypeName, variable.nameWithType().type.position()))
                    null
                } else {
                    AutomatonVariableDeclaration(
                        variableName,
                        variableType,
                        null
                    )
                }
            }.toMutableList()

            val constructorVariables = automatonCtx.nameWithType().mapNotNull { cVar ->
                val argName = cVar.name.processIdentifier()
                val argTypeName = cVar.type.text
                val argType = context.resolveType(argTypeName)
                if (argType == null) {
                    errorManager(UnresolvedType(argTypeName, cVar.type.position()))
                    null
                } else {
                    ConstructorArgument(
                        argName,
                        argType
                    )
                }
            }.toMutableList()

            val states = automatonCtx.automatonStatement()?.filter { it.automatonStateDecl() != null }?.flatMap { statesCtx ->
                statesCtx.automatonStateDecl().identifierList().Identifier().map { stateCtx ->
                    val keyword = statesCtx.start.processIdentifier()
                    val stateName = stateCtx.processIdentifier()
                    val stateKind = StateKind.fromString(keyword)
                    State(stateName, stateKind)
                }
            }.orEmpty().toMutableList()

            val automaton = Automaton(
                automatonCtx.name.processIdentifier(),
                type,
                states,
                mutableListOf(),
                variables,
                constructorVariables,
                mutableListOf(),
            )

            context.storeResolvedAutomaton(automaton)
            variables.forEach { it.automaton = automaton }
            constructorVariables.forEach { it.automaton = automaton }
            states.forEach { it.automaton = automaton }
        }

        ctx.globalStatement().mapNotNull { it.topLevelDecl()?.variableDecl() }.map { variableDecl ->
            val nameWithType = variableDecl.nameWithType()
            val typeName = nameWithType.type.text
            val name = nameWithType.name.processIdentifier()
            val type = context.resolveType(typeName)
            if (type == null) {
                errorManager(UnresolvedType(typeName, nameWithType.type.position()))
                return@map
            }
            val init = if (variableDecl.assignmentRight() != null){
                asgBuilderVisitor.processAssignmentRight(variableDecl.assignmentRight())
            } else {
                errorManager(UninitializedGlobalVariable(name, nameWithType.name.position()))
                return@map
            }

            val variable = GlobalVariableDeclaration(
                name,
                type,
                init
            )
            context.storeGlobalVariable(variable)
        }

        for (automaton in automata) {
            visitAutomatonDecl(automaton)
        }

        for (extensionFunction in ctx.globalStatement().mapNotNull { it.topLevelDecl()?.functionDecl() }) {
            visitFunctionDecl(extensionFunction)
        }
    }

    override fun visitTypesSection(ctx: LibSLParser.TypesSectionContext) {
        for (semanticTypeContext in ctx.semanticTypeDecl()) {
            val type = when {
                semanticTypeContext.simpleSemanticType() != null -> {
                    val semanticType = semanticTypeContext.simpleSemanticType().semanticName.text
                    val realTypeCtx = semanticTypeContext.simpleSemanticType().realName
                    val resolvedRealType = context.resolveType(realTypeCtx.text)
                        ?: processRealTypeIdentifier(realTypeCtx)
                    val isPointer = realTypeCtx.asterisk != null

                    SimpleType(semanticType, resolvedRealType, isPointer, context)
                }
                semanticTypeContext.blockType() != null -> {
                    val blockType = semanticTypeContext.blockType()
                    val semanticType = blockType.semanticName.processIdentifier()
                    val realName = blockType.realName
                    val resolvedRealType = context.resolveType(realName.text)
                        ?: processRealTypeIdentifier(realName)
                    val body = blockType.blockTypeStatement().map { statement ->
                        statement.Identifier().processIdentifier() to resolvePrimitiveLiteral(statement.expressionAtomic().primitiveLiteral())
                    }
                    // val genericTypeCtx = blockType.typeIdentifier().generic
                    // todo? val genericType = genericTypeCtx?.let { processRealTypeIdentifier(it) }
                    EnumLikeSemanticType(semanticType, resolvedRealType, body, context)
                }
                else -> error("unknown type's type")
            }

            context.storeResolvedType(type)
        }
    }

    override fun visitTypealiasStatement(ctx: LibSLParser.TypealiasStatementContext) {
        val name = ctx.left.text
        val resolvedRealType = context.resolveType(ctx.right.text)
            ?: processRealTypeIdentifier(ctx.right)

        check(resolvedRealType is AliassableType)

        context.storeResolvedType(TypeAlias(
            name,
            resolvedRealType,
            context
        ))
    }

    override fun visitTypeDefBlock(ctx: LibSLParser.TypeDefBlockContext) {
        val name = ctx.name.name.processIdentifier()
        val typeIdentifier = ctx.typeIdentifier()
        val resolvedRealType = context.resolveType(typeIdentifier.text)
            ?: processRealTypeIdentifier(typeIdentifier)
        val generic = if (typeIdentifier.generic != null) {
            processRealTypeIdentifier(typeIdentifier.generic)
        } else {
            null
        }

        val entries = ctx.typeDefBlockStatement().map { statement ->
            statement.nameWithType().let { it.name.processIdentifier() to processRealTypeIdentifier(it.type) }
        }

        context.storeResolvedType(StructuredType(
            name,
            resolvedRealType,
            generic,
            entries,
            context
        ))
    }

    private fun resolvePrimitiveLiteral(primitiveLiteralContext: LibSLParser.PrimitiveLiteralContext): Atomic {
        return when {
            primitiveLiteralContext.bool != null -> {
                if (primitiveLiteralContext.bool.processIdentifier() == "true") {
                    BoolLiteral(true)
                } else {
                    BoolLiteral(false)
                }
            }
            primitiveLiteralContext.DoubleQuotedString() != null -> {
                val literal = primitiveLiteralContext.DoubleQuotedString().processIdentifier().removeDoubleQuotes()
                StringLiteral(literal)
            }
            primitiveLiteralContext.floatNumber() != null -> {
                FloatLiteral(primitiveLiteralContext.floatNumber().text.toFloat())
            }
            primitiveLiteralContext.integerNumber() != null -> {
                IntegerLiteral(primitiveLiteralContext.integerNumber().text.toInt())
            }
            else -> error("unknown primitive literal type")
        }
    }

    private fun processRealTypeIdentifier(ctx: LibSLParser.TypeIdentifierContext): Type {
        val name = ctx.periodSeparatedFullName().Identifier().map { it.processIdentifier() }
        val generic = ctx.generic?.let { processRealTypeIdentifier(it) }
        val isPointer = ctx.asterisk != null

        return if (name.size == 1 && name.first() == "array" && generic != null) {
            ArrayType(
                name.first(),
                isPointer,
                generic,
                context
            )
        } else {
            return context.resolveType(name.joinToString("."))
                ?: RealType(
                name,
                isPointer,
                generic,
                context
            )
        }
    }

    override fun visitAutomatonDecl(ctx: LibSLParser.AutomatonDeclContext) {
        val name = ctx.name.processIdentifier()
        val automaton = context.resolveAutomaton(name)!!

        ctx.automatonStatement()
            .mapNotNull { it.variableDecl() }
            .forEach { decl ->
                val variableName = decl.nameWithType().name.processIdentifier()
                val automatonVariable = automaton.internalVariables.first { it.name == variableName }

                if (decl.assignmentRight() != null) {
                    automatonVariable.initValue = asgBuilderVisitor.processAssignmentRight(decl.assignmentRight())
                }
            }

        context.storeResolvedAutomaton(automaton)

        for (functionDecl in ctx.automatonStatement().mapNotNull { it.functionDecl() }) {
            visitFunctionDecl(functionDecl)
        }
    }

    override fun visitFunctionDecl(ctx: LibSLParser.FunctionDeclContext) {
        val (automatonName, name) = parseFunctionName(ctx)
        if (automatonName == null) {
            errorManager(UnspecifiedAutomaton(name, ctx.position()))
            return
        }

        val typeName = ctx.functionType?.processIdentifier()
        val returnType = if (typeName != null) {
            val resolved = context.resolveType(typeName)
            if (resolved == null) {
                errorManager(UnresolvedType(typeName, ctx.functionType.position()))
                return
            }
            resolved
        } else null

        var argumentIndex = 0
        val args = ctx.functionDeclArgList()?.parameter()?.mapNotNull { arg ->
            val argTypeName = arg.type.processIdentifier()
            val annotationName = arg.annotation()?.Identifier()?.processIdentifier()
            val argType = if (annotationName == "target"){
                val targetAutomaton = context.resolveAutomaton(argTypeName)
                targetAutomaton?.type
            } else {
                context.resolveType(argTypeName)
            }
            if (argType == null) {
                errorManager(UnresolvedType(argTypeName, arg.type.position()))
                return@mapNotNull null
            }
            FunctionArgument(arg.name.processIdentifier(), argType, argumentIndex++,null)
        }.orEmpty().toMutableList()

        val hasBody = ctx.functionBody() != null
        val func = Function(
            name,
            automatonName,
            args,
            returnType,
            mutableListOf(),
            mutableListOf(),
            context = context,
            hasBody = hasBody
        )

        context.storeResolvedFunction(func)

        args.forEach { it.function = func }
        if (returnType != null) {
            val result = ResultVariable(returnType)
            func.resultVariable = result
        }
    }

    override fun visitEnumBlock(ctx: LibSLParser.EnumBlockContext) {
        val semanticType = ctx.typeIdentifier().text
        val body = ctx.enumBlockStatement().map { statement ->
            statement.Identifier().processIdentifier() to IntegerLiteral(statement.integerNumber().text.toInt())
        }
        context.storeResolvedType(
            EnumType(
                semanticType,
                body,
                context
            ))
    }

    private fun processImportStatement(terminal: TerminalNode) {
        // todo: forbid a recursive imports
        val importString = parseStringTokenStringSemicolon(terminal.processIdentifier(), "import")
        val filePath = "$basePath/$importString.lsl"
        val file = File(filePath)

        if (!file.exists()) {
            errorManager(UnresolvedImportPath(filePath, terminal.symbol.position()))
            return
        }

        val stream = CharStreams.fromString(file.readText())
        val lexer = LibSLLexer(stream)
        val tokenStream = CommonTokenStream(lexer)
        val parser = LibSLParser(tokenStream)

        val newContext = LslContext()
        newContext.init()
        context.import(newContext)
        val resolver = Resolver(newContext, basePath, errorManager)
        val fileCtx = parser.file()
        resolver.visitFile(fileCtx)
        val asgBuilder = ASGBuilder(context, errorManager)
        asgBuilder.visitFile(fileCtx)
    }
}
