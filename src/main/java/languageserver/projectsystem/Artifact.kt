package languageserver.projectsystem

/**
 * @author George Fraser
 * @see https://github.com/georgewfraser/java-language-server.git
 *
 * Modified by Linghui Luo and Sven Vinkemeier
 */
data class Artifact(val groupId: String, val artifactId: String, val version: String) {
    override fun toString() = "$groupId:$artifactId:$version"

    companion object {
        fun parse(id: String): Artifact {
            val parts = id.split(':').dropLastWhile { it.isEmpty() }
            return when {
                parts.size == 3 -> Artifact(parts[0], parts[1], parts[2])
                parts.size == 5 -> Artifact(parts[0], parts[1], parts[3]) // groupId:artifactId:jar:version:compile
                parts.size == 6 -> Artifact(parts[0], parts[1], parts[4]) // groupId:artifactId:jar:classifier:version:compile
                else -> throw IllegalArgumentException("$id is not properly formatted artifact")
            }
        }
    }
}
