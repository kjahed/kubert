package ca.jahed.kubert

import java.io.File

class KubertConfiguration {
    var outputDir = File("output");
    var dockerRepo = "";
    var namespace = "kubert";
    var appName = "umlrt";
    var codeGenPath = "codegen"
    var baseTcpPort = 8000;
    var debug = true;
    var umlrtArgs = "";
}