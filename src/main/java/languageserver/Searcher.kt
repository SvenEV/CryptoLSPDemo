package languageserver

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.ast.type.Type
import crypto.pathconditions.ofType
import crypto.rules.CryptSLRuleReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

data class MethodScanResult(
    val type: TypeDeclaration<*>,
    val cryptoFields: List<FieldDeclaration>,
    val method: MethodDeclaration,
    val cryptoParameters: List<Parameter>,
    val hasCryptoResult: Boolean,
    val cryptoDeclarations: List<VariableDeclarationExpr>,
    val usesBranching: Boolean
) {
    val usesCrypto get() =
        /*cryptoFields.any() ||*/ cryptoParameters.any() || hasCryptoResult || cryptoDeclarations.any()
    val isRelevant get() =
        usesBranching && usesCrypto
}

data class FileScanResult(
    val file: Path,
    val isParsable: Boolean,
    val relevantImports: List<ImportDeclaration>,
    val methods: List<MethodScanResult>
)

data class RootDirectoryScanResult(
    val path: Path,
    val javaFiles: List<Path>,
    val files: List<FileScanResult>
)

data class CrySLFile(
    val file: Path,
    val className: String,
    val shortClassName: String
)

object Searcher {

    private val directory = Paths.get("E:\\User\\Desktop\\StudyProjects\\EVALUATION")
    private val rulesDirectory = Paths.get("E:\\Projects\\Masterarbeit\\CogniCryptVSCode\\resources\\JCA_rules")
    private val parser = JavaParser()

    private val branchStmts = listOf(
        IfStmt::class.java,
        SwitchStmt::class.java,
        WhileStmt::class.java,
        DoStmt::class.java,
        ForStmt::class.java,
        ForEachStmt::class.java
    )

    private fun linkTo(path: Path) = "[${directory.relativize(path)}]($path)  "

    @JvmStatic
    fun main(args: Array<String>) {
        val rules = Files.list(rulesDirectory)
            .map { CrySLFile(it, CryptSLRuleReader.readFromFile(it.toFile()).className, it.toFile().nameWithoutExtension) }
            .toList()

        Files.newDirectoryStream(directory) { e -> Files.isDirectory(e) }
            .asSequence()
            .map { scanRootDirectory(it, rules) }
            .forEach { result ->
                val report = generateReport(result)
                Files.write(result.path.resolve("report.md"), report.toByteArray())
            }
    }

    private fun generateReport(result: RootDirectoryScanResult): String {
        val sb = StringBuilder()

        sb.appendln("# ${result.path.fileName}")
        sb.appendln("## Files with Crypto Usage and Branching")
        result.files
            .filter { it.methods.any { m -> m.isRelevant } }
            .forEach { fileResult ->
                sb.appendln("### ${fileResult.file.fileName}")
                sb.appendln(linkTo(fileResult.file))
                fileResult.methods
                    .filter { m -> m.isRelevant }
                    .forEach { m ->
                        sb.appendln("```java")
                        sb.appendln(m.method)
                        sb.appendln("```")
                    }
            }

        sb.appendln("## Unparsable Files")
        val unparsableFiles = result.files.filter { !it.isParsable }
        unparsableFiles.forEach { sb.appendln(linkTo(it.file)) }

        sb.appendln("## Files with Crypto Imports but no Relevant Methods")
        result.files.filter { it.relevantImports.any() && it.methods.none { m -> m.isRelevant } }.forEach { sb.appendln(linkTo(it.file)) }

        sb.appendln("## Files with Relevant Methods but no Crypto Imports")
        result.files.filter { it.relevantImports.none() && it.methods.any { m -> m.isRelevant } }.forEach { sb.appendln(linkTo(it.file)) }

        sb.appendln("## Statistics")
        val allMethods = result.files.flatMap { it.methods }
        sb.appendln("${result.javaFiles.size} Java files (including ${unparsableFiles.size} unparsable files)  ")
        sb.appendln("${allMethods.size} methods  ")
        val noBranchingButCrypto = allMethods.count { !it.usesBranching && it.usesCrypto }
        val noCryptoButBranching = allMethods.count { it.usesBranching && !it.usesCrypto }
        val noBranchingNoCrypto = allMethods.count { !it.usesBranching && !it.usesCrypto }
        val branchingAndCrypto = allMethods.count { it.usesBranching && it.usesCrypto }
        sb.appendln()
        sb.appendln("|   | No Crypto | Crypto |")
        sb.appendln("|---|-----------|--------|")
        sb.appendln("|No Branching|$noBranchingNoCrypto|$noBranchingButCrypto|")
        sb.appendln("| Branching  |$noCryptoButBranching|$branchingAndCrypto|")

        return sb.toString()
    }

    private fun scanRootDirectory(directory: Path, rules: List<CrySLFile>): RootDirectoryScanResult {
        print("Scanning directory '$directory'... ")
        val javaFiles = scanDirectory(directory).toList()
        println("Found ${javaFiles.size} java files")

        val results = javaFiles.map { scanJavaFile(it, rules) }.toList()
        return RootDirectoryScanResult(directory, javaFiles, results)
    }

    private fun scanDirectory(dir: Path): Sequence<Path> =
        Files.newDirectoryStream(dir)
            .asSequence()
            .flatMap {
                when {
                    Files.isDirectory(it) -> scanDirectory(it)
                    it.toFile().extension == "java" -> sequenceOf(it)
                    else -> emptySequence()
                }
            }

    private fun scanJavaFile(file: Path, rules: List<CrySLFile>): FileScanResult {
        val parseResult = parser.parse(file)

        fun importMatchesAnyRule(import: ImportDeclaration) =
            (import.isAsterisk && rules.any { it.className.startsWith(import.nameAsString.trimEnd('*')) }) ||
                rules.any { it.className == import.nameAsString }

        return if (parseResult.isSuccessful) {
            val relevantImports = parseResult.result.get().imports.filter(::importMatchesAnyRule)

            val methods =
                parseResult.result.get().types.flatMap { type ->
                    type.methods.map { scanMethod(it, type, rules) }
                }

            FileScanResult(file, true, relevantImports, methods)
        } else {
            FileScanResult(file, false, emptyList(), emptyList())
        }
    }

    private fun scanMethod(method: MethodDeclaration, type: TypeDeclaration<*>, rules: List<CrySLFile>): MethodScanResult {
        fun typeMatchesAnyRule(type: Type) =
            rules.any {
                it.className == type.elementType.asString() ||
                    it.shortClassName == type.elementType.asString()
            }

        val cryptoFields = type.fields.filter { typeMatchesAnyRule(it.elementType) }
        val cryptoParameters = method.parameters.filter { typeMatchesAnyRule(it.type) }
        val hasCryptoResult = typeMatchesAnyRule(method.type)

        val cryptoDeclarations =
            if (method.body.isPresent)
                method.body.get().findAll(ExpressionStmt::class.java)
                    .map { it.expression }
                    .ofType<VariableDeclarationExpr>()
                    .filter { typeMatchesAnyRule(it.elementType) }
            else
                emptyList()

        val usesBranching =
            if (method.body.isPresent)
                method.body.get().findAll(Statement::class.java)
                    .any { it.javaClass in branchStmts }
            else
                false

        return MethodScanResult(type, cryptoFields, method, cryptoParameters, hasCryptoResult, cryptoDeclarations, usesBranching)
    }
}