import com.ibm.wala.classLoader.Module;

import de.upb.soot.core.SootClass;
import de.upb.soot.frontends.java.JimpleConverter;
import de.upb.soot.frontends.java.WalaClassLoader;

import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import soot.PackManager;
import soot.Transform;
import soot.Transformer;

import magpiebridge.core.AnalysisResult;
import magpiebridge.core.MagpieServer;
import magpiebridge.core.ServerAnalysis;

public class CryptoServerAnalysis implements ServerAnalysis {

  private static final Logger LOG = Logger.getLogger("main");

  private String ruleDirPath;

  public CryptoServerAnalysis(String ruleDirPath) {
    this.ruleDirPath = ruleDirPath;
  }

  @Override
  public String source() {
    return "CogniCrypt";
  }

  @Override
  public void analyze(Collection<Module> files, MagpieServer server) {

    // String srcPath = server.getSourceCodePath();
    // server.logger.logVerbose("analyze "+ srcPath);
    // if (ps != null) {
    // JavaProjectService ps = (JavaProjectService) server.getProjectService("java");
    // LOG.info("java project root path" + ps.getRootPath());
    // LOG.info("java project source path" + ps.getSourcePath());
    // LOG.info("java project class path" + ps.getClassPath());
    // }
    server.logger.logVerbose("CryptoServerAnalysis: files = " + files);

    try {
      CryptoTransformer transformer = new CryptoTransformer(ruleDirPath);
      loadSourceCode(files);
      runSootPacks(transformer);
      Collection<AnalysisResult> results = transformer.getDiagnostics().stream().map(it -> (AnalysisResult)it).collect(Collectors.toList());

      server.logger.logVerbose("CryptoServerAnalysis: " + results.size() + " results = " + results);

      for (AnalysisResult re : results) {
        System.err.println(re.toString());
      }
      server.consume(results, source());
    } catch (Exception e) {
      server.logger.logVerbose("CryptoServerAnalysis: Exception = " + e);
    }
  }

  public Collection<AnalysisResult> analyze(String srcPath) {
    CryptoTransformer transformer = new CryptoTransformer(ruleDirPath);
    loadSourceCode(srcPath);
    runSootPacks(transformer);
    Collection<AnalysisResult> results = transformer.getDiagnostics().stream().map(it -> (AnalysisResult)it).collect(Collectors.toList());
    return results;
  }

  private void loadSourceCode(Collection<? extends Module> files) {
    // use WALA source-code front end to load classes
    WalaClassLoader loader = new WalaClassLoader(files);
    List<SootClass> sootClasses = loader.getSootClasses();
    JimpleConverter jimpleConverter = new JimpleConverter(sootClasses);
    jimpleConverter.convertAllClasses();
  }

  private void loadSourceCode(String srcPath) {
    WalaClassLoader loader = new WalaClassLoader(srcPath);
    List<SootClass> sootClasses = loader.getSootClasses();
    JimpleConverter jimpleConverter = new JimpleConverter(sootClasses);
    jimpleConverter.convertAllClasses();
  }

  private void runSootPacks(Transformer t) {
    PackManager.v().getPack("wjtp").add(new Transform("wjtp.cognicrypt", t));
    PackManager.v().getPack("cg").apply();
    PackManager.v().getPack("wjtp").apply();
  }
}
