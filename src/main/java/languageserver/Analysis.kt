package languageserver

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.expr.AssignExpr
import crypto.pathconditions.MethodParameterInfo
import de.upb.soot.frontends.java.JimpleConverter
import de.upb.soot.frontends.java.WalaClassLoader
import languageserver.workspace.ProjectPaths
import org.apache.commons.lang3.exception.ExceptionUtils
import soot.*
import soot.jimple.AssignStmt

fun analyze(client: CryptoLanguageClient?, rulesDir: String, projectPaths: ProjectPaths): CryptoTransformer? =
    try {
        val transformer = CryptoTransformer(rulesDir)
        client?.setStatusBarMessage(StatusMessage("Processing sources..."))
        loadSourceCode(projectPaths)
        client?.setStatusBarMessage(StatusMessage("CogniCrypt analysis..."))
        runSootPacks(transformer)
        transformer
    } catch (e: Throwable) {
        val trace = ExceptionUtils.getStackTrace(e)
        client?.setStatusBarMessage(StatusMessage("Analysis failed, click for details", "# Exception\n```\n$trace\n```"))
        null
    }

private fun loadSourceCode(projectPaths: ProjectPaths) {
    val sourceDirs = projectPaths.sourcePath.map { it.toString() }.toSet()
    val libDirs = projectPaths.libraryPath.map { it.toString() }.toSet()
    val loader = WalaClassLoader(sourceDirs, libDirs, null)
    val sootClasses = loader.sootClasses
    val jimpleConverter = JimpleConverter(sootClasses)
    jimpleConverter.convertAllClasses()
    fixLocalVariableNamesInAllClasses()
}

private fun runSootPacks(t: Transformer) {
    PackManager.v().getPack("wjtp").add(Transform("wjtp.cognicrypt", t))
    PackManager.v().getPack("cg").apply()
    PackManager.v().getPack("wjtp").apply()
}

private fun SootMethod.tryRetrieveActiveBody() =
    try {
        retrieveActiveBody()
    } catch (ex: RuntimeException) {
        null // failed to retrieve body (missing sources?)
    }

/**
 * Tries to extract local variable names from Java source code in order to fix the names of [Local] instances.
 * For method parameters, a tag of type [MethodParameterInfo] is added to the method.
 */
fun fixLocalVariableNamesInAllClasses() {
    val parser = JavaParser()

    Scene.v().classes
        .flatMap { it.methods }
        .mapNotNull { it.tryRetrieveActiveBody() }
        .forEach { methodBody ->
            // Parse method declaration, extract parameter names and attach them to the SootMethod as a tag
            readRangeFromFile(methodBody.method.sourceLocation)
                ?.run { parser.parseBodyDeclaration(this) }
                ?.result
                ?.ifPresent { decl ->
                    val paramNames = decl.asMethodDeclaration().parameters.map { it.name.asString() }
                    methodBody.method.addTag(MethodParameterInfo(paramNames))
                }

            // For each AssignStmts with a local as left operand, extract the variable name from source code
            methodBody.units.forEach { stmt ->
                val targetLocal = (stmt as? AssignStmt)?.leftOp as? Local
                if (targetLocal != null) {
                    // Note: Parsing succeeds even if it's not an AssignExpr,
                    // but 'expr.result' throws in that case (╯°□°）╯︵ ┻━┻
                    try {
                        readRangeFromFile(stmt.sourceLocation)
                            ?.run { parser.parseExpression<AssignExpr>(this) }
                            ?.result
                            ?.ifPresent { assignment ->
                                if (assignment.target.isNameExpr)
                                    targetLocal.name = assignment.target.asNameExpr().nameAsString
                            }
                    } catch (_: ClassCastException) {
                        // Not an AssignExpr
                    }
                }
            }
        }
}
