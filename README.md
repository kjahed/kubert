# Kubert

### Usage
```
Kubert [-dhV] [-a=<appName>] [-g=<codeGenPath>]
                              [-n=<namespace>] [-o=<outputDir>]
                              [-p=<baseTcpPort>] [-r=<dockerRepo>] <inputModel>
                              [<programArgs>...]
Deploy UML-RT models to Kubernetes clusters.
      <inputModel>           The UML-RT model to deploy.
      [<programArgs>...]     Program arguments.
  -a, --app-name=<appName>   Name of the Kubernetes app
  -d, --debug                Generate debug statements
  -g, --codegen=<codeGenPath>
                             Path to UML-RT code generator.
  -h, --help                 Show this help message and exit.
  -n, --namespace=<namespace>
                             Namespace for Kubernetes resources.
  -o, --output-dir=<outputDir>
                             Output directory for deployment files.
  -p, --base-port=<baseTcpPort>
                             Base TCP port for proxy capsules
  -r, --docker-repo=<dockerRepo>
                             Docker container repository
  -V, --version              Print version information and exit.
```

### Example
```
bin/Kubert -g /full/path/to/codegen -r kjahed/ -o output ParcelRouter.uml -- -u 100 1 1 1 1

cd output
gradle generate  # generate code
gradle publish   # containerize
gradle deploy    # deploy
gradle run       # execute
```
### 
