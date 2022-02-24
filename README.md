# Kubert

### Usage
```
Usage: ca.jahed.kubert.kubert [-dhV] [-a=<appName>] [-g=<codeGenPath>]
                              [-i=<dockerBaseImage>] [-n=<namespace>]
                              [-o=<outputDir>] [-p=<baseTcpPort>]
                              [-r=<dockerRepo>] <inputModel> [<programArgs>...]
Deploy UML-RT models to Kubernetes clusters.
      <inputModel>           The UML-RT model to deploy.
      [<programArgs>...]     Program arguments.
  -a, --app-name=<appName>   Name of the Kubernetes app
  -d, --debug                Generate debug statements
  -g, --codegen=<codeGenPath>
                             Path to UML-RT code generator.
  -h, --help                 Show this help message and exit.
  -i, --image=<dockerBaseImage>
                             Base Docker image for all generated containers
  -n, --namespace=<namespace>
                             Namespace for Kubernetes resources.
  -o, --output-dir=<outputDir>
                             Output directory for deployment files.
  -p, --base-port=<baseTcpPort>
                             Base TCP port for proxy capsules
  -r, --docker-repo=<dockerRepo>
                             Docker repository for generated containers
  -V, --version              Print version information and exit.
```

### Example
```
Kubert ParcelRouter.uml -- -u 100 1 1 1 1

cd output
gradle generate  # generate code
gradle publish   # containerize
gradle deploy    # deploy
gradle run       # execute
```
### 
