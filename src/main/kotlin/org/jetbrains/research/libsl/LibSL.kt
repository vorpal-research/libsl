package org.jetbrains.research.libsl

import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.jetbrains.research.libsl.asg.Library
import org.jetbrains.research.libsl.asg.LslContext
import org.jetbrains.research.libsl.errors.ErrorManager
import org.jetbrains.research.libsl.visitors.ASGBuilder
import org.jetbrains.research.libsl.visitors.Resolver
import java.io.File
import java.nio.file.Path

class LibSL(
    private val basePath: String
) {
    val context = LslContext()
    val errorManager = ErrorManager()
    lateinit var library: Library
    private var isParsed = false

    var errorListener: ParseTreeListener? = null
    
    fun loadFromString(string: String): Library {
        val stream = CharStreams.fromString(string)
        val lexer = LibSLLexer(stream)
        val tokenStream = CommonTokenStream(lexer)
        context.init()
        val parser = LibSLParser(tokenStream)
        if (errorListener != null) {
            parser.addParseListener(errorListener)
        }

        val file = parser.file()
        Resolver(context,basePath, errorManager).visitFile(file)
        return ASGBuilder(context, errorManager).visitFile(file)
    }

    fun loadFromFile(file: File): Library {
        return loadFromString(file.readText())
    }

    fun loadByPath(path: Path): Library {
        return loadFromFile(path.toFile())
    }

    fun loadFromPath(path: String): Library {
        return loadFromFile(File(path))
    }
}