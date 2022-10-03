package org.jetbrains.research.libsl.asg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonSerializer

val librarySerializer = JsonSerializer<Library> { src, _, context ->
    JsonObject().apply {
        addProperty("name", src.metadata.name)

        // add meta-information
        addProperty("lslVersion", src.metadata.stringVersion)

        if (src.metadata.language != null) {
            addProperty("lang", src.metadata.language)
        }
        if (src.metadata.libraryVersion != null) {
            addProperty("library-version", src.metadata.libraryVersion)
        }
        if (src.metadata.url != null) {
            addProperty("url", src.metadata.url)
        }

        if (src.imports.isNotEmpty()) {
            add("imports", JsonArray().apply {
                src.imports.forEach { import ->
                    add(import)
                }
            })
        }

        if (src.includes.isNotEmpty()) {
            add("includes", JsonArray().apply {
                src.includes.forEach { include ->
                    add(include)
                }
            })
        }

        if (src.semanticTypes.isNotEmpty()) {
            add("types", JsonArray().apply {
                src.semanticTypes.filter { it !is PrimitiveType }.forEach { type ->
                    add(context.serialize(type, Type::class.java))
                }
            })
        }

        if (src.automata.isNotEmpty()) {
            add("automata", JsonArray().apply {
                src.automata.forEach { automaton ->
                    add(context.serialize(automaton, Automaton::class.java))
                }
            })
        }

        if (src.globalVariables.isNotEmpty()) {
            add("variables", JsonArray().apply {
                src.globalVariables.forEach { (_, variable) ->
                    add(context.serialize(variable, Variable::class.java))
                }
            })
        }
    }
}

val automatonSerializer = JsonSerializer<Automaton> { src, _, context ->
    JsonObject().apply {
        addProperty("name", src.name)
        addProperty("type", src.type.fullName)

        add("constructorVariables", JsonArray().apply {
            src.constructorVariables.forEach { variable ->
                val variableObject = JsonObject().apply {
                    addProperty("name", variable.name)
                    addProperty("type", variable.type.fullName)
                }
                add(variableObject)
            }
        })

        add("variables", JsonArray().apply {
            src.internalVariables.forEach { variable ->
                val variableObject = JsonObject().apply {
                    addProperty("name", variable.name)
                    addProperty("type", variable.type.fullName)
                }
                add(variableObject)
            }
        })

        add("states", JsonArray().apply {
            src.states.forEach { state ->
                val stateObject = JsonObject().apply {
                    addProperty("name", state.name)
                    addProperty("kind", state.kind.name)
                }
                add(stateObject)
            }
        })

        add("shifts", JsonArray().apply {
            src.shifts.forEach { shift ->
                val stateObject = JsonObject().apply {
                    addProperty("from", shift.from.name)
                    addProperty("to", shift.to.name)
                    add("functions", JsonObject().apply { shift.functions.forEach { func ->
                        addProperty("name", func.name)
                        add("args", context.serialize(func.args.map { it.type.fullName }))
                    } })
                }
                add(stateObject)
            }
        })

        add("functions", JsonArray().apply{
            src.functions.forEach { func ->
                add(context.serialize(func, Function::class.java))
            }
        })
    }
}

val variableSerializer = JsonSerializer<Variable> { src, _, context ->
    JsonObject().apply {
        addProperty("name", src.name)
        addProperty("fullName", src.fullName)
        add("type", context.serialize(src.type, Type::class.java))
        if (src.initValue != null) {
            add("initValue", context.serialize(src.initValue, Expression::class.java))
        }

        when (src) {
            is AutomatonVariableDeclaration -> {
                addProperty("kind", "automatonVariable")
                addProperty("automaton", src.automaton.name)
            }
            is ConstructorArgument -> {
                addProperty("kind", "constructorArgument")
                addProperty("automaton", src.automaton.name)
            }
            is FunctionArgument -> {
                addProperty("kind", "functionArgument")
                addProperty("function", src.function.qualifiedName)
                if (src.annotation != null) {
                    add("annotation", context.serialize(src.annotation, Annotation::class.java))
                }
                add("functionArgTypes", JsonArray().apply { src.function.args.forEach { add(it.type.name) } })
            }
            is GlobalVariableDeclaration -> {
                addProperty("kind", "global")
            }
            is ResultVariable -> {
                addProperty("kind", "result")
                addProperty("name", "result")
            }
        }
    }
}

val annotationSerializer = JsonSerializer<Annotation> { src, _, context ->
    JsonObject().apply {
        addProperty("name", src.name)
        add("args", JsonArray().apply {
            src.values.forEach { add(context.serialize(it, Expression::class.java)) }
        })
        if (src is TargetAnnotation) {
            addProperty("automatonName", src.targetAutomaton.name)
        }
    }
}

val typeSerializer = JsonSerializer<Type> { src, _, context ->
    JsonObject().apply {
        addProperty("name", src.name)
        addProperty("isPointer", src.isPointer)
        when (src) {
            is EnumLikeSemanticType -> {
                addProperty("kind", "enumLike")
                add("type", context.serialize(src.type, Type::class.java))
                add("entries", JsonArray().apply { src.entries.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("name", entry.first)
                        add("value", context.serialize(entry.second, Expression::class.java))
                    })
                } })
            }
            is EnumType -> {
                addProperty("kind", "enum")
                add("entries", JsonArray().apply { src.entries.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("name", entry.first)
                        add("value", context.serialize(entry.second, Expression::class.java))
                    })
                } })
            }
            is SimpleType -> {
                addProperty("kind", "simple")
                add("generic", context.serialize(src.generic, Type::class.java))
                add("originalType", context.serialize(src.realType, Type::class.java))
            }
            is StructuredType -> {
                addProperty("kind", "structured")
                add("entries", JsonArray().apply { src.entries.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("name", entry.first)
                        add("type", context.serialize(entry.second, Type::class.java))
                    })
                } })
            }
            is TypeAlias -> {
                addProperty("kind", "alias")
                add("originalType", context.serialize(src.originalType, Type::class.java))
            }
            is RealType -> {
                addProperty("kind", "real")
                add("generic", context.serialize(src.generic, Type::class.java))
            }
            is ArrayType -> {
                addProperty("kind", "array")
                add("type", context.serialize(src.generic, Type::class.java))
            }
            is ChildrenType -> error("children type can't be serialized")
            is PrimitiveType -> {
                addProperty("kind", src.name)
                when (src) {
                    is IntType -> add("capacity", context.serialize(src.capacity))
                    is FloatType -> add("capacity", context.serialize(src.capacity))
                    is UnsignedType -> add("capacity", context.serialize(src.capacity))
                }
            }
        }
    }
}

val functionSerializer = JsonSerializer<Function> { src, _, context ->
    JsonObject().apply {
        addProperty("name", src.name)
        addProperty("automaton", src.automatonName)
        addProperty("returnType", src.returnType?.fullName)
        addProperty("target", src.target?.name)
        addProperty("hasBody", src.hasBody)

        add("args", JsonArray().apply {
            src.args.forEach { arg ->
                add(context.serialize(arg, FunctionArgument::class.java))
            }
        })

        add("contracts", JsonArray().apply {
            src.contracts.forEach { contract ->
                add(JsonObject().apply {
                    addProperty("name", contract.name)
                    add("kind", context.serialize(contract.kind))
                    add("expression", context.serialize(contract.expression, Expression::class.java))
                })
            }
        })

        add("statements", JsonArray().apply {
            src.statements.forEach { statement ->
                add(context.serialize(statement, Statement::class.java))
            }
        })
    }
}

val expressionSerializer = JsonSerializer<Expression> { src, _, context ->
    JsonObject().apply {
        when(src) {
            is BinaryOpExpression -> {
                addProperty("kind", "binary")
                addProperty("op", src.op.name)
                add("left", context.serialize(src.left, Expression::class.java))
                add("right", context.serialize(src.right, Expression::class.java))
            }
            is FloatLiteral ->  {
                addProperty("kind", "float")
                addProperty("value", src.value)
            }
            is IntegerLiteral -> {
                addProperty("kind", "integer")
                addProperty("value", src.value)
            }
            is StringLiteral -> {
                addProperty("kind", "string")
                addProperty("value", src.value)
            }
            is UnaryOpExpression -> {
                addProperty("kind", "unary")
                when (src.op) {
                    ArithmeticUnaryOp.MINUS -> {
                        addProperty("unaryOp", "minus")
                    }
                    ArithmeticUnaryOp.INVERSION -> {
                        addProperty("unaryOp", "inversion")
                    }
                }
                add("value", context.serialize(src.value, Expression::class.java))
            }
            is Variable -> {
                addProperty("kind", "variable")
                add("variable", context.serialize(src, Variable::class.java))
            }
            is OldValue -> {
                addProperty("kind", "oldValue")
                add("value", context.serialize(src.value, Expression::class.java))
            }
            is CallAutomatonConstructor -> {
                addProperty("kind", "callAutomatonConstructor")
                addProperty("automatonName", src.automaton.name)
                addProperty("state", src.state.name)
                add("args", JsonArray().apply {
                    src.args.forEach { arg ->
                        add(JsonObject().apply {
                            addProperty("name", arg.variable.name)
                            add("value", context.serialize(arg.init, Expression::class.java))
                        })
                    }
                })
            }
            is BoolLiteral -> {
                addProperty("kind", "bool")
                addProperty("value", src.value)
            }
            is QualifiedAccess -> {
                addProperty("kind", "qualifiedAccess")
                add("access", context.serialize(src, QualifiedAccess::class.java))
            }
        }
    }
}

val qualifiedAccessSerializer = JsonSerializer<QualifiedAccess> { src, _, context ->
    JsonObject().apply {
        when (src) {
            is AccessAlias -> {
                addProperty("kind", "accessAlias")
            }
            is ArrayAccess -> {
                addProperty("kind", "arrayAccess")
                add("index", context.serialize(src.index, Expression::class.java))
            }
            is RealTypeAccess -> {
                addProperty("kind", "realTypeAccess")
                addProperty("name", src.type.name)
            }
            is VariableAccess -> {
                addProperty("kind", "variableAccess")
                addProperty("name", src.fieldName)
                if (src.variable != null) {
                    add("variableInfo", context.serialize(src.variable, Variable::class.java))
                }
            }
            is AutomatonGetter -> {
                addProperty("kind", "automatonGetter")
                addProperty("automaton", src.automaton.name)
            }
        }

        addProperty("type", src.type.fullName)
        if (src.childAccess != null) {
            add("child", context.serialize(src.childAccess, QualifiedAccess::class.java))
        }
    }
}

val statementSerializer = JsonSerializer<Statement> { src, _, context ->
    when (src) {
        is Assignment -> JsonObject().apply {
            addProperty("kind", "assignment")
            addProperty("variable", src.left.toString())
            if (src.left is ArrayAccess) {
                add("arrayIndex", context.serialize(src.left.index, Expression::class.java))
            }
            add("value", context.serialize(src.value, Expression::class.java))
        }

        is Action -> JsonObject().apply {
            addProperty("kind", "action")
            addProperty("actionName", src.name)
            add("args", JsonArray().apply {
                src.arguments.forEach { arg -> add(context.serialize(arg, Expression::class.java)) }
            })
        }
    }
}

val functionArgumentsSerializer = JsonSerializer<FunctionArgument> { src, _, context ->
    JsonObject().apply {
        addProperty("name", src.name)
        addProperty("type", src.type.fullName)
        val anno = src.annotation
        if (anno != null) {
            add("annotation", context.serialize(anno, Annotation::class.java))
        }
    }
}