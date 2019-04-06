package languageserver.projectsystem

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.logging.Logger
import java.util.regex.Pattern
import kotlin.streams.asSequence
import kotlin.streams.toList

/**
 * @author George Fraser
 * @see https://github.com/georgewfraser/java-language-server.git
 *
 * Modified and extended by Linghui Luo and Sven Vinkemeier
 */
class InferConfig internal constructor(
    /** Root of the workspace that is currently open in VSCode  */
    private val workspaceRoot: Path,
    /** External dependencies specified manually by the user  */
    private val externalDependencies: Collection<String> = emptySet(),
    /** Location of the maven repository, usually ~/.m2  */
    private val mavenHome: Path = defaultMavenHome,
    /** Location of the gradle cache, usually ~/.gradle  */
    private val gradleHome: Path = defaultGradleHome) {

    fun classPath() = buildClassPath() + workspaceClassPath()
    fun libraryClassPath() = buildClassPath()

    /**
     * Find directories that contain java .class files in the workspace, for example files generated
     * by maven in target/classes
     */
    fun workspaceClassPath() = when {
        // externalDependencies
        externalDependencies.any() -> emptySet()

        // Maven
        Files.exists(workspaceRoot.resolve("pom.xml")) ->
            try {
                Files.walk(workspaceRoot).asSequence()
                    .flatMap { this.outputDirectory(it) }
                    .toSet()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }

        // Bazel
        Files.exists(workspaceRoot.resolve("WORKSPACE")) -> {
            val bazelBin = workspaceRoot.resolve("bazel-bin")
            if (Files.exists(bazelBin) && Files.isSymbolicLink(bazelBin))
                bazelOutputDirectories(bazelBin)
            else
                emptySet()
        }

        else -> emptySet()
    }

    /** Recognize build root files like pom.xml and return compiler output directories  */
    fun outputDirectory(file: Path) = when {
        // Maven
        file.fileName.toString() == "pom.xml" -> {
            val target = file.resolveSibling("target")
            if (Files.exists(target) && Files.isDirectory(target))
                sequenceOf(target.resolve("classes"), target.resolve("test-classes"))
            else
                emptySequence()
        }

        // TODO gradle
        else -> emptySequence()
    }

    /**
     * Find .jar files for external dependencies, for examples maven dependencies in ~/.m2 or jars in
     * bazel-genfiles
     */
    private fun buildClassPath() = when {
        // externalDependencies
        externalDependencies.any() ->
            externalDependencies
                .mapNotNull {
                    val a = Artifact.parse(it)
                    val found = findAnyJar(a, false)
                    if (found == null)
                        LOG.warning("Couldn't find jar for $a in $mavenHome or $gradleHome")
                    found
                }
                .toSet()

        // Maven
        Files.exists(workspaceRoot.resolve("pom.xml")) ->
            mvnDependencies()
                .mapNotNull {
                    val found = findMavenJar(it, false)
                    if (found == null)
                        LOG.warning("Couldn't find jar for $it in $mavenHome")
                    found
                }
                .toSet()

        // Bazel
        Files.exists(workspaceRoot.resolve("WORKSPACE")) -> {
            val bazelGenFiles = workspaceRoot.resolve("bazel-genfiles")
            if (Files.exists(bazelGenFiles) && Files.isSymbolicLink(bazelGenFiles)) {
                LOG.info("Looking for bazel generated files in $bazelGenFiles")
                val jars = bazelJars(bazelGenFiles)
                LOG.info(String.format("Found %d generated-files directories", jars.size))
                jars
            } else {
                emptySet()
            }
        }

        else -> emptySet()
    }

    private fun findBazelJavac(bazelRoot: File, workspaceRoot: File, acc: MutableSet<Path>) {
        // If _javac directory exists, search it for dirs with names like lib*_classes
        val javac = File(bazelRoot, "_javac")
        if (javac.exists()) {
            val match = FileSystems.getDefault().getPathMatcher("glob:**/lib*_classes")
            try {
                Files.walk(javac.toPath())
                    .filter(match::matches)
                    .filter { Files.isDirectory(it) }
                    .forEach { acc.add(it) }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }

        }
        // Recurse into all directories that mirror the structure of the workspace
        if (bazelRoot.isDirectory) {
            val children = bazelRoot.list { _, name -> File(workspaceRoot, name).exists() }
            for (child in children) {
                val bazelChild = File(bazelRoot, child)
                val workspaceChild = File(workspaceRoot, child)
                findBazelJavac(bazelChild, workspaceChild, acc)
            }
        }
    }

    /**
     * Search bazel-bin for per-module output directories matching the pattern:
     *
     * bazel-bin/path/to/module/_javac/rule/lib*_classes
     */
    private fun bazelOutputDirectories(bazelBin: Path): Set<Path> {
        try {
            val bazelBinTarget = Files.readSymbolicLink(bazelBin)
            LOG.info("Searching for bazel output directories in $bazelBinTarget")

            val dirs = HashSet<Path>()
            findBazelJavac(bazelBinTarget.toFile(), workspaceRoot.toFile(), dirs)
            LOG.info(String.format("Found %d bazel output directories", dirs.size))

            return dirs
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

    }

    /** Search bazel-genfiles for jars  */
    private fun bazelJars(bazelGenFiles: Path) = try {
        val target = Files.readSymbolicLink(bazelGenFiles)
        Files.walk(target)
            .filter { it.fileName.toString().endsWith(".jar") }
            .asSequence()
            .toSet()
    } catch (e: IOException) {
        throw RuntimeException(e)
    }


    /** Find source .jar files in local repository.  */
    private fun buildDocPath() = when {
        // externalDependencies
        externalDependencies.any() ->
            externalDependencies
                .mapNotNull {
                    val a = Artifact.parse(it)
                    val found = findAnyJar(Artifact.parse(it), true)
                    if (found == null)
                        LOG.warning("Couldn't find doc jar for $a in $mavenHome or $gradleHome")
                    found
                }
                .toSet()

        // Maven
        Files.exists(workspaceRoot.resolve("pom.xml")) ->
            mvnDependencies()
                .mapNotNull { findMavenJar(it, true) }
                .toSet()

        // TODO Gradle
        // TODO Bazel
        else -> emptySet()
    }

    private fun findAnyJar(artifact: Artifact, source: Boolean) =
        findMavenJar(artifact, source) ?: findGradleJar(artifact, source)

    private fun findMavenJar(artifact: Artifact, source: Boolean): Path? {
        val jar = mavenHome
            .resolve("repository")
            .resolve(artifact.groupId.replace('.', File.separatorChar))
            .resolve(artifact.artifactId)
            .resolve(artifact.version)
            .resolve(fileName(artifact, source))

        return if (Files.exists(jar)) jar else null
    }

    private fun findGradleJar(artifact: Artifact, source: Boolean): Path? {
        // Search for
        // caches/modules-*/files-*/groupId/artifactId/version/*/artifactId-version[-sources].jar
        val base = gradleHome.resolve("caches")
        val pattern = "glob:" +
            arrayOf(
                base.toString(),
                "modules-*",
                "files-*",
                artifact.groupId,
                artifact.artifactId,
                artifact.version,
                "*",
                fileName(artifact, source)
            ).joinToString(File.separator)
        val match = FileSystems.getDefault().getPathMatcher(pattern)

        try {
            return Files.walk(base, 7).filter(match::matches).asSequence().firstOrNull()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    private fun fileName(artifact: Artifact, source: Boolean) =
        artifact.artifactId + '-' + artifact.version + (if (source) "-sources" else "") + ".jar"

    private fun mvnDependencies(): Collection<Artifact> {
        val pomXml = workspaceRoot.resolve("pom.xml")
        return if (Files.exists(pomXml)) dependencyList(pomXml) else emptyList()
    }

    companion object {
        private val LOG = Logger.getLogger("main")

        private val defaultMavenHome get() = Paths.get(System.getProperty("user.home")).resolve(".m2")
        private val defaultGradleHome get() = Paths.get(System.getProperty("user.home")).resolve(".gradle")

        private fun dependencyList(pomXml: Path) = try {
            // Tell maven to output deps to a temporary file
            val outputFile = Files.createTempFile("deps", ".txt")

            // TODO consider using mvn dependency:copy-dependencies instead
            val cmd = String.format(
                "%s dependency:list -DincludeScope=test -DoutputFile=%s -o",
                mvnCommand, outputFile)
            val workingDirectory = pomXml.toAbsolutePath().parent.toFile()
            val result = Runtime.getRuntime().exec(cmd, null, workingDirectory).waitFor()

            if (result != 0)
                throw RuntimeException("`$cmd` returned $result")

            readDependencyList(outputFile)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        private fun readDependencyList(outputFile: Path): List<Artifact> {
            val artifact = Pattern.compile(".*:.*:.*:.*:.*")

            try {
                Files.newInputStream(outputFile).use { `in` ->
                    return BufferedReader(InputStreamReader(`in`))
                        .lines()
                        .map { line -> line.trim { it <= ' ' } }
                        .filter { line -> artifact.matcher(line).matches() }
                        .map { Artifact.parse(it) }
                        .toList()
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        private val mvnCommand
            get() = when (File.separatorChar) {
                '\\' -> findExecutableOnPath("mvn.cmd")
                    ?: findExecutableOnPath("mvn.bat") ?: "mvn"
                else -> "mvn"
            }

        private fun findExecutableOnPath(name: String) =
            System.getenv("PATH")
                .split(File.pathSeparator.toRegex())
                .dropLastWhile { it.isEmpty() }
                .map { File(it, name) }
                .firstOrNull { it.isFile && it.canExecute() }
                ?.absolutePath
    }
}
