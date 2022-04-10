import com.google.gson.GsonBuilder
import org.antlr.v4.runtime.*
import org.jetbrains.research.libsl.LibSLLexer
import org.jetbrains.research.libsl.LibSLParser
import org.jetbrains.research.libsl.asg.*
import org.jetbrains.research.libsl.asg.Annotation
import org.jetbrains.research.libsl.asg.Function
import org.jetbrains.research.libsl.errors.ErrorManager
import org.jetbrains.research.libsl.visitors.ASGBuilder
import org.jetbrains.research.libsl.visitors.Resolver
import org.junit.jupiter.api.Assertions
import java.io.File
import java.io.FileNotFoundException

const val baseRoot = "./src/test/"
const val testdataPath = "$baseRoot/testdata/"

fun testRunner(name: String) {
    val fileContent = getLslFileAsStream(name)
    val stream = CharStreams.fromString(fileContent)
    val lexer = LibSLLexer(stream)
    val tokenStream = CommonTokenStream(lexer)
    val context = LslContext()
    val errorManager = ErrorManager()
    context.init()
    val parser = LibSLParser(tokenStream)
    parser.addErrorListener(object : BaseErrorListener() {
        override fun syntaxError(
            recognizer: Recognizer<*, *>?,
            offendingSymbol: Any?,
            line: Int,
            charPositionInLine: Int,
            msg: String?,
            e: RecognitionException?
        ) {
            Assertions.fail<RecognitionException>("$line:$charPositionInLine: $msg", e)
        }
    })

    val file = parser.file()
    Resolver(context,"$testdataPath/lsl/", errorManager).visitFile(file)
    val library = ASGBuilder(context, errorManager).visitFile(file)

    val gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Library::class.java, librarySerializer)
        .registerTypeAdapter(Automaton::class.java, automatonSerializer)
        .registerTypeAdapter(Type::class.java, typeSerializer)
        .registerTypeAdapter(FunctionArgument::class.java, functionArgumentsSerializer)
        .registerTypeAdapter(Variable::class.java, variableSerializer)
        .registerTypeAdapter(Annotation::class.java, annotationSerializer)
        .registerTypeAdapter(Function::class.java, functionSerializer)
        .registerTypeAdapter(Expression::class.java, expressionSerializer)
        .registerTypeAdapter(QualifiedAccess::class.java, qualifiedAccessSerializer)
        .registerTypeAdapter(Statement::class.java, statementSerializer)
        .create()
    val prettyContent = gson.toJson(library)

    val expectedFile = File("$testdataPath/expected/$name.json")
    if (!expectedFile.exists()) {
        expectedFile.writeText(prettyContent)
        Assertions.fail<FileNotFoundException>("new file was created: $name")
    }

    Assertions.assertEquals(expectedFile.readText(), prettyContent)
}

private fun getLslFileAsStream(name: String): String {
    val file = File("$testdataPath/lsl/$name.lsl")
    if (!file.exists()) {
        error("can't find file $name")
    }

    return file.readText()
}
