package ca.jahed.kubert

class KubertConfiguration {
    var outputDir = Kubert.outputDir
    var dockerRepo = Kubert.dockerRepo
    var dockerBaseImage = Kubert.dockerBaseImage
    var namespace = Kubert.namespace
    var appName = Kubert.appName
    var codeGenPath = Kubert.codeGenPath
    var baseTcpPort = Kubert.baseTcpPort
    var debug = Kubert.debug
    var umlrtArgs = Kubert.programArgs.joinToString(" ")
}