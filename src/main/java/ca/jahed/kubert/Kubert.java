package ca.jahed.kubert;

import ca.jahed.rtpoet.papyrusrt.PapyrusRTReader;
import ca.jahed.rtpoet.rtmodel.RTModel;
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
    public static File inputModel;

    @CommandLine.Parameters(index = "1..*", description = "Program arguments.")
    public static List<String> programArgs = new ArrayList<>();

    @CommandLine.Option(names = {"-o", "--output-dir"}, description = "Output directory for deployment files.")
    public static File outputDir = new File("output");

    @CommandLine.Option(names = {"-r", "--docker-repo"}, description = "Docker container repository")
    public static String dockerRepo = "";

    @CommandLine.Option(names = {"-n", "--namespace"}, description = "Namespace for Kubernetes resources.")
    public static String namespace = "kubert";

    @CommandLine.Option(names = {"-a", "--app-name"}, description = "Name of the Kubernetes app")
    public static String appName = "umlrt";

    @CommandLine.Option(names = {"-g", "--codegen"}, description = "Path to UML-RT code generator.")
    public static String codeGenPath = new File(Objects.requireNonNull(Kubert.class.getClassLoader()
            .getResource("codegen")).getPath()).getAbsolutePath();

    @CommandLine.Option(names = {"-p", "--base-port"}, description = "Base TCP port for proxy capsules")
    public static int baseTcpPort = 8000;

    @CommandLine.Option(names = {"-d", "--debug"}, description = "Generate debug statements")
    public static boolean debug = true;

    public static String umlrtArgs = "";

    @Override
    public Integer call() throws Exception {
        umlrtArgs = String.join(" ", programArgs);
        RTModel rtModel = PapyrusRTReader.read(inputModel.getAbsolutePath());
        ModelTransformer.INSTANCE.transform(rtModel);
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Kubert()).execute(args);
        System.exit(exitCode);
    }
}
