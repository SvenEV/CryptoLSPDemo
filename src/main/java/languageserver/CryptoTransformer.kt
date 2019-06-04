package languageserver

import boomerang.callgraph.ObservableStaticICFG
import boomerang.preanalysis.BoomerangPretransformer
import crypto.HeadlessCryptoScanner.CG
import crypto.Utils
import crypto.analysis.CrySLResultsReporter
import crypto.analysis.CryptoScanner
import crypto.rules.CryptSLRule
import crypto.rules.CryptSLRuleReader
import soot.*
import soot.jimple.toolkits.ide.icfg.AbstractJimpleBasedICFG
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG
import soot.options.Options
import java.io.File

class CryptoTransformer(
    private val applicationClassPath: String?,
    private val libraryPaths: List<String>,
    ruleDir: String) : SceneTransformer() {

    private val errorReporter: CryptoErrorReporter

    // When whole program mode is disabled, the classpath misses jce.jar
    private val pathToJCE = System.getProperty("java.home") + File.separator + "lib" + File.separator + "jce.jar"

    private val rules: List<CryptSLRule>
    private val excludeList: List<String>
    private val includeList: List<String>

    private val callGraphAlgorithm = CG.CHA

    val diagnostics get() = errorReporter.diagnostics
    var icfg: AbstractJimpleBasedICFG? = null

    init {
        this.errorReporter = CryptoErrorReporter()

        rules = File(ruleDir).listFiles()
            .filter { it != null && it.name.endsWith(".cryptslbin") }
            .map { CryptSLRuleReader.readFromFile(it) }
            .toList()

        if (rules.isEmpty()) {
            println("CogniCrypt did not find any rules to start the analysis for. It checked for rules in $ruleDir")
        }

        excludeList = rules.map { Utils.getFullyQualifiedName(it) }.toList()
        includeList = emptyList()
        initilizeSootOptions()
    }

    override fun internalTransform(phaseName: String, options: Map<String, String>) {
        BoomerangPretransformer.v().reset()
        BoomerangPretransformer.v().apply()
        icfg = JimpleBasedInterproceduralCFG(false)
        val observableIcfg = ObservableStaticICFG(icfg)
        val rules = rules

        val reporter = CrySLResultsReporter().apply {
            addReportListener(errorReporter)
        }

        val scanner = object : CryptoScanner() {
            override fun icfg() = observableIcfg
            override fun getAnalysisListener() = reporter
        }

        scanner.scan(rules)
    }

    private fun initilizeSootOptions() {
        G.reset()
        Options.v().set_whole_program(true)
        when (callGraphAlgorithm) {
            CG.CHA -> {
                Options.v().setPhaseOption("cg.cha", "on")
                Options.v().setPhaseOption("cg", "all-reachable:true")
            }
            CG.SPARK_LIBRARY -> {
                Options.v().setPhaseOption("cg.spark", "on")
                Options.v().setPhaseOption("cg", "all-reachable:true,library:any-subtype")
            }
            CG.SPARK -> {
                Options.v().setPhaseOption("cg.spark", "on")
                Options.v().setPhaseOption("cg", "all-reachable:true")
            }
            else -> throw RuntimeException("No call graph option selected!")
        }
        Options.v().set_output_format(Options.output_format_none)
        Options.v().set_no_bodies_for_excluded(true)
        Options.v().set_allow_phantom_refs(true)
        Options.v().set_keep_line_number(true)
        Options.v().set_prepend_classpath(true)// append rt.jar to soot class path
        Options.v().set_soot_classpath(File.pathSeparator + (libraryPaths + pathToJCE).joinToString(File.pathSeparator))
        if (applicationClassPath != null)
            Options.v().set_process_dir(applicationClassPath.split(File.pathSeparatorChar))
        Options.v().set_include(includeList)
        Options.v().set_exclude(excludeList)
        Options.v().set_full_resolver(true)
        Scene.v().loadNecessaryClasses()
    }
}