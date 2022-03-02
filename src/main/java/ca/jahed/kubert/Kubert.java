package ca.jahed.kubert;

import ca.jahed.rtpoet.dsl.RtStandaloneSetup;
import ca.jahed.rtpoet.dsl.generator.RTModelGenerator;
import ca.jahed.rtpoet.dsl.rt.Model;
import ca.jahed.rtpoet.papyrusrt.PapyrusRTReader;
import ca.jahed.rtpoet.rtmodel.RTModel;
import com.google.inject.Injector;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextResourceSet;
import picocli.CommandLine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "ca.jahed.kubert.kubert", mixinStandardHelpOptions = true, version = "1.0",
        description = "Deploy UML-RT models to Kubernetes clusters.")
public class Kubert implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "The UML-RT model to deploy.")
    protected static File inputModel;

    @CommandLine.Parameters(index = "1..*", description = "Program arguments.")
    protected static List<String> programArgs = new ArrayList<>();

    @CommandLine.Option(names = {"-o", "--output-dir"}, description = "Output directory for deployment files.")
    protected static File outputDir = new File("output");

    @CommandLine.Option(names = {"-r", "--docker-repo"}, description = "Docker repository for generated containers")
    protected static String dockerRepo = "";

    @CommandLine.Option(names = {"-i", "--image"}, description = "Base Docker image for all generated containers")
    protected static String dockerBaseImage = "kjahed/umlrt-rts:1.0";

    @CommandLine.Option(names = {"-n", "--namespace"}, description = "Namespace for Kubernetes resources.")
    protected static String namespace = "kubert";

    @CommandLine.Option(names = {"-a", "--app-name"}, description = "Name of the Kubernetes app")
    protected static String appName = "umlrt";

    @CommandLine.Option(names = {"-g", "--codegen"}, description = "Path to UML-RT code generator.")
    protected static String codeGenPath = new File(Objects.requireNonNull(Kubert.class.getClassLoader()
            .getResource("codegen")).getPath()).getAbsolutePath();

    @CommandLine.Option(names = {"-p", "--base-port"}, description = "Base TCP port for proxy capsules")
    protected static int baseTcpPort = 8000;

    @CommandLine.Option(names = {"-d", "--debug"}, description = "Generate debug statements")
    protected static boolean debug = true;

    @CommandLine.Option(names = {"-t", "--rt"}, description = "Input is an RTModel in textual notation")
    protected static boolean rt = false;

    protected static String umlrtArgs = "";

    @Override
    public Integer call() {
        generate(inputModel, new KubertConfiguration());
        return 0;
    }

    public void generate(File umlModel, KubertConfiguration config) {
        generate(umlModel.getAbsolutePath(), config);
    }

    public void generate(String umlModel, KubertConfiguration config) {
        RTModel rtModel;
        if(config.getTextual()) {
            Injector injector = new RtStandaloneSetup().createInjectorAndDoEMFRegistration();
            XtextResourceSet resourceSet = injector.getInstance(XtextResourceSet.class);
            resourceSet.addLoadOption(XtextResource.OPTION_RESOLVE_ALL, Boolean.TRUE);
            Resource resource = resourceSet.getResource(URI.createURI(inputModel.getAbsolutePath()), true);
            rtModel = new RTModelGenerator().doGenerate(resource);
        } else {
            rtModel = PapyrusRTReader.read(umlModel);
        }
        generate(rtModel, config);
    }

    public void generate(RTModel model, KubertConfiguration config) {
        ModelTransformer.INSTANCE.transform(model, config);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Kubert()).execute(args);
        System.exit(exitCode);
    }
}
