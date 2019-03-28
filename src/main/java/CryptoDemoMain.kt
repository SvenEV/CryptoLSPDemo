import org.eclipse.lsp4j.launch.websockets.LSPWebSocketServer
import java.io.File
import javax.websocket.OnError
import javax.websocket.Session
import javax.websocket.server.ServerEndpoint

val ruleDirPath =
    System.getProperty("user.project")?.let { userProject ->
        System.setProperty("log4j.configurationFile", "$userProject/src/test/resources/template-log4j2.xml")
        File("$userProject/JCA_rules").absolutePath
    } ?: throw IllegalStateException("Please specify your the project path of crypto-lsp-demo as JVM argument:\r\n" + "-Duser.project=<PATH_TO_crypto-lsp-demo>")

object CryptoDemoMain {
    @JvmStatic
    fun main(args: Array<String>) {
        CryptoLanguageServer(ruleDirPath).launchOnStdio()
    }
}

//you need to configure JVM option for tomcat at first
//for windows, add this line 'set JAVA_OPTS="-Duser.project=PATH\TO\crypto-lsp-demo"' to tomcat\bin\catalina.bat
//for linux, add this line 'JAVA_OPTS="-Duser.project=PATH/TO/crypto-lsp-demo"' to tomcat\bin\catalina.sh
@ServerEndpoint("/websocket")
class CryptoWebSocketServer : LSPWebSocketServer<CryptoLanguageServer>(
    { CryptoLanguageServer(ruleDirPath) },
    CryptoLanguageServer::class.java) {

    @OnError
    override fun onError(e: Throwable, session: Session?) {
        e.printStackTrace()
    }
}
