import com.ibm.wala.classLoader.SourceFileModule
import magpiebridge.core.JavaProjectService

object CryptoDemoMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val ruleDirPath = TestMain.ruleDirPath
        // String ruleDirPath = args[0];
        val language = "java"
        val javaProjectService = JavaProjectService()
        val server = CryptoLanguageServer("E:\\Projects\\Masterarbeit\\CryptoLSPDemo\\JCA_rules")
        server.addProjectService(language, javaProjectService)
        server.addAnalysis(language, CryptoServerAnalysis(ruleDirPath))
        server.launchOnStdio()
    }
}
