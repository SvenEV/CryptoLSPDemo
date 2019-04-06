package languageserver.projectsystem

import com.github.javaparser.JavaParser
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Logger
import java.util.stream.Stream

/**
 * Infer the source path from a given project root path. Instead using the Parser from Java JDK
 * tool.jar from the original version, we use com.github.javaparser.JavaParser here.
 * Modified by Linghui Luo and Sven Vinkemeier
 *
 * @author George Fraser
 * @see https://github.com/georgewfraser/java-language-server.git
 */
object InferSourcePath {
    private const val certaintyThreshold = 10
    private val LOG = Logger.getLogger("main")

    internal fun allJavaFiles(dir: Path): Stream<Path> {
        val match = FileSystems.getDefault().getPathMatcher("glob:*.java")

        try {
            return Files.walk(dir).filter { java -> match.matches(java.fileName) }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    fun sourcePath(workspaceRoot: Path): Set<Path> {
        LOG.info("Searching for source roots in $workspaceRoot")
        val sourceRoots = mutableMapOf<Path, Int>()

        fun alreadyKnown(java: Path) = sourceRoots.keys
            .any { root -> java.startsWith(root) && sourceRoots[root]!! > certaintyThreshold }

        fun infer(java: Path): Path? {
            val javaParser = JavaParser()

            val result =
                try {
                    javaParser.parse(java).result.orElse(null)
                } catch (e: IOException) {
                    e.printStackTrace()
                    null
                }

            val packageName = result?.packageDeclaration?.orElse(null)?.nameAsString ?: ""
            val packagePath = packageName.replace('.', File.separatorChar)
            val dir = java.parent

            return when {
                packagePath.isEmpty() -> dir

                !dir.endsWith(packagePath) -> {
                    LOG.warning("Java source file $java is not in $packagePath")
                    null
                }

                else -> {
                    val up = Paths.get(packagePath).nameCount
                    var truncate = dir
                    for (i in 0 until up) {
                        truncate = truncate.parent
                    }
                    truncate
                }
            }
        }

        allJavaFiles(workspaceRoot).forEach { java ->
            if (java.fileName.toString() != "module-info.java" && !alreadyKnown(java)) {
                val root = infer(java)
                if (root != null) {
                    val count = sourceRoots[root] ?: 0
                    sourceRoots[root] = count + 1
                }
            }
        }

        return sourceRoots.keys
    }
}
