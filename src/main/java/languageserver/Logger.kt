package languageserver

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalTime

class Logger {
    private lateinit var file: File
    private lateinit var writer: BufferedWriter

    init {
        try {
            file = File.createTempFile("logger", ".txt")
            writer = BufferedWriter(FileWriter(file))
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun log(isServer: Boolean, msg: String) {
        val sb = StringBuilder().apply {
            append("LOG-" + LocalTime.now())
            append(
                if (isServer)
                    ": Server sends"
                else
                    ": Client sends"
            )
            append("\n")
            append(msg)
        }
        try {
            writer.write(sb.toString())
            writer.newLine()
            writer.newLine()
            writer.flush()
        } catch (e: IOException) {
            assert(false) { e }
        }
    }

    fun logServerMsg(msg: String) = log(true, msg)

    fun logClientMsg(msg: String) = log(false, msg)

    fun logVerbose(msg: String) {
        try {
            writer.write("VERBOSE-:$msg")
            writer.newLine()
            writer.newLine()
            writer.flush()
        } catch (e: IOException) {
            assert(false) { e }
        }
    }
}