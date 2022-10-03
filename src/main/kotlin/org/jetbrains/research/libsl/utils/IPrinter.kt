package org.jetbrains.research.libsl.utils

interface IPrinter {
    fun dumpToString(): String

    fun formatListEmptyLineAtEndIfNeeded(printers: Collection<IPrinter>): String = buildString {
        for (printer in printers) {
            appendLine(printer.dumpToString())
        }
        if (printers.isNotEmpty()) {
            appendLine()
        }
    }

    fun simpleCollectionFormatter(
        prefix: String,
        suffix: String,
        collection: Collection<String>
    ): String = buildString {
        for (element in collection) {
            appendLine("$prefix$element$suffix")
        }
        if (collection.isNotEmpty()) {
            appendLine()
        }
    }

    fun withIndent(text: String) = buildString {
        currentIndentLevel++

        val lines = text.lines()
        for (line in lines.dropLast(1)) {
            appendLine(getLineWithIndent(line))
        }
        lines.lastOrNull()?.let { line -> append(getLineWithIndent(line)) }

        currentIndentLevel--
    }

    private fun getLineWithIndent(line: String): String {
        return SPACE.repeat(currentIndent) + line
    }

    companion object {
        const val SPACE = " "

        var currentIndentLevel = 0
        const val indentLevelSize = 4

        val currentIndent: Int
            get() = currentIndentLevel * indentLevelSize
    }
}