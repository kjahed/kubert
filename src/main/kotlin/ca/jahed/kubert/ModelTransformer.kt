package ca.jahed.kubert

import ca.jahed.kubert.utils.RTHierarchyUtils
import ca.jahed.kubert.model.RTPartialModel
import ca.jahed.kubert.model.RTSlot
import ca.jahed.rtpoet.papyrusrt.PapyrusRTWriter
import ca.jahed.rtpoet.papyrusrt.rts.PapyrusRTLibrary
import ca.jahed.rtpoet.rtmodel.*
import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.xmi.XMIResource
import java.io.File
import java.io.PrintWriter

object ModelTransformer {
    fun transform(model: RTModel) {
        val topSlot = RTHierarchyUtils.buildHierarchy(model)
        val slots = processSlot(topSlot)

        val mqttDir = File(Kubert.outputDir, "mqtt")
        mqttDir.mkdirs()

        writeToFile(mqttConfigMap(), File(mqttDir, "configmap.yaml"))
        writeToFile(mqttDeployment(), File(mqttDir, "deployment.yaml"))
        writeToFile(mqttService(), File(mqttDir, "service.yaml"))
        writeToFile(mqttGradleScript(), File(mqttDir, "build.gradle"))

        writeToFile(gradleSettingsFile(slots), File(Kubert.outputDir, "settings.gradle"))
        writeToFile(gradlePropertiesFile(), File(Kubert.outputDir, "gradle.properties"))
        writeToFile(gradleRootScript(), File(Kubert.outputDir, "build.gradle"))
        writeToFile(nameSpaceFile(), File(Kubert.outputDir, "namespace.yaml"))
        writeToFile(volumeFile(), File(Kubert.outputDir, "volume.yaml"))
        writeToFile(rolesFile(), File(Kubert.outputDir, "roles.yaml"))
    }

    private fun processSlot(slot: RTSlot): List<RTSlot> {
        val slots = mutableListOf<RTSlot>()
        for (child in slot.children)
            slots.addAll(processSlot(child))

        if (slot.part.capsule.stateMachine == null) return slots

        val outputDir = File(Kubert.outputDir, slot.name)
        outputDir.mkdirs()

        val partialModel = RTPartialModel(slot)
        val resource = PapyrusRTLibrary.createResourceSet()
            .createResource(URI.createFileURI(File(outputDir, "model.uml").absolutePath))
        PapyrusRTWriter.write(resource, partialModel)
        (resource as XMIResource).eObjectToIDMap.clear()
        resource.save(null)

        writeToFile(dockerFile(partialModel), File(outputDir, "Dockerfile"))
        writeToFile(deploymentFile(slot), File(outputDir, "deployment.yaml"))
        writeToFile(gradleScript(slot), File(outputDir, "build.gradle"))
        if (slot.servicePorts.isNotEmpty()) writeToFile(serviceFile(slot), File(outputDir, "service.yaml"))

        slots.add(slot)
        return slots
    }

    private fun mqttConfigMap(): String {
        return """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: mosquitto-config
              namespace: ${Kubert.namespace}
            data:
              mosquitto.conf: |-
                # Ip/hostname to listen to.
                # If not given, will listen on all interfaces
                #bind_address
            
                # Port to use for the default listener.
                port 1883
            
                # Allow anonymous users to connect?
                # If not, the password file should be created
                allow_anonymous true
            
                # The password file.
                # Use the `mosquitto_passwd` utility.
                # If TLS is not compiled, plaintext "username:password" lines bay be used
                # password_file /mosquitto/config/passwd
        """.trimIndent()
    }

    private fun mqttDeployment(): String {
        return """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: mosquitto
              namespace: ${Kubert.namespace}
            spec:
              selector:
                matchLabels:
                  app: mosquitto
              template:
                metadata:
                  labels:
                    app: mosquitto
                spec:
                  containers:
                  - name: mosquitto
                    image: eclipse-mosquitto:2.0
                    ports:
                    - containerPort: 1883
                    volumeMounts:
                        - name: mosquitto-config
                          mountPath: /mosquitto/config/mosquitto.conf
                          subPath: mosquitto.conf
                  volumes:
                    - name: mosquitto-config
                      configMap:
                        name: mosquitto-config
        """.trimIndent()
    }

    private fun mqttService(): String {
        return """
            apiVersion: v1
            kind: Service
            metadata:
              name: mqtt
              namespace: ${Kubert.namespace}
            spec:
              selector:
                app: mosquitto
              ports:
              - port: 1883
                targetPort: 1883
        """.trimIndent()
    }

    private fun mqttGradleScript(): String {
        return """
            plugins {
                id 'java'
            }
            
            task deploy {
                dependsOn ':deployNamespace'
                inputs.files 'deployment.yaml', 'service.yaml', 'configmap.yaml', '.mqtt'
                outputs.files '.mqtt'
        
                doLast {
                    exec {
                        commandLine 'kubectl', 'apply', '-f', '.'
                    }
        
                    file('.mqtt').text = 'mqtt'
                }
            }
            
            task tearDown {
                dependsOn ':deleteNamespace'
                delete '.mqtt'
            }
        """.trimIndent()
    }

    private fun dockerFile(model: RTModel): String {
        return """
            FROM ${Kubert.dockerRepo}umlrt-rts:latest
            COPY ./cpp/src /app
            WORKDIR /app
            RUN make
            ENTRYPOINT flock -n /var/lock/app.lock /app/${model.top.capsule.name}Main ${Kubert.umlrtArgs} 2>&1 | tee logfile
        """.trimIndent()
    }

    private fun deploymentFile(slot: RTSlot): String {
        val parent = findParentBehavior(slot)
        return """
            ---
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: ${slot.k8sName}
              namespace: ${Kubert.namespace}
              labels:
                app: ${Kubert.appName}
                parent: ${if(parent != null) """ "${parent.k8sName}" """ else """ "" """}
                optional: "${slot.part.optional}"
                
            spec:
              replicas: 0
              selector:
                matchLabels:
                  name: ${slot.k8sName}
              template:
                metadata:
                  labels:
                    name: ${slot.k8sName}
                    app: ${Kubert.appName}
                spec:
                  volumes:
                    - name: ${Kubert.namespace}
                      persistentVolumeClaim:
                        claimName: ${Kubert.namespace}
                  containers:
                    - name: ${slot.k8sName}
                      image: ${Kubert.dockerRepo}${slot.k8sName}
                      volumeMounts:
                        - mountPath: "/data"
                          name: ${Kubert.namespace}
                      ${
            if (slot.children.isNotEmpty()) """
                      lifecycle:
                        ${
                if (slot.children.isNotEmpty()) """
                          postStart:
                            exec:
                              command:
                                - "sh"
                                - "-c"
                                - >
                                  kubectl scale deployment -lparent=${slot.k8sName},optional=false --replicas=1;
                                  kubectl wait --for=condition=available deployment -lparent=${slot.k8sName}
                        """ else ""
            }
                        ${
                if (slot.children.isNotEmpty()) """
                          preStop:
                            exec:
                              command:
                                - "sh"
                                - "-c"
                                - >
                                  kubectl scale deployment -lparent=${slot.k8sName} --replicas=0;
                        """ else ""
            }
                      """ else ""
        }
                  terminationGracePeriodSeconds: 3
            """.trimIndent()
    }

    private fun serviceFile(slot: RTSlot): String {
        return """
            ---
            apiVersion: v1
            kind: Service
            metadata:
              name: ${slot.k8sName}
              namespace: ${Kubert.namespace}
              labels:
                app: ${Kubert.appName}
            spec:
              selector:
                name: ${slot.k8sName}
              ports:
                ${
            slot.servicePorts.joinToString(" ") { entry ->
                """
                - name: ${entry.first}
                  protocol: TCP
                  port: ${entry.second}
                  targetPort: ${entry.second}
              """
            }
        }
            """.trimIndent()
    }

    private fun gradleScript(slot: RTSlot): String {
        return """
            plugins {
                id 'java'
            }
        
            class KeepTryingExec extends Exec {
                KeepTryingExec() {
                    super()
                    ignoreExitValue = true
                }
        
                @Override
                protected void exec() {
                    super.exec()
                    while(getExecResult().exitValue != 0) {
                        sleep 1000
                        super.exec()
                    }
                }
            }
        
            task generate(type:JavaExec) {
                inputs.files 'model.uml'
                outputs.dir 'cpp'
                classpath = files("${Kubert.codeGenPath}/bin/umlrtgen.jar")
                args '-l', 'SEVERE', '-p', '${Kubert.codeGenPath}/plugins', '-o', './cpp', './model.uml'
            }
        
            task containerize(type:Exec) {
                dependsOn 'generate'
                inputs.dir 'cpp'
                inputs.files 'Dockerfile'
                outputs.files '.image'
                commandLine 'docker', 'build', '--label', 'namespace=${Kubert.namespace}', '-q', '-t', '${Kubert.dockerRepo}${slot.k8sName}', '.'
                standardOutput new ByteArrayOutputStream()
        
                doLast {
                    file('.image').text = standardOutput.toString().substring(7, 7 + 4)
                }
            }
        
            task publish(type:Exec) {
                dependsOn 'containerize'
                inputs.files '.image'
                outputs.files '.push'
                commandLine 'docker', 'push', '-q', '${Kubert.dockerRepo}${slot.k8sName}'
        
                doLast {
                    file('.push').text = file('.image').text
                }
            }
        
            task deployService {
                dependsOn ':deployNamespace'
                inputs.files 'service.yaml'
                outputs.files '.service'
        
                onlyIf {
                    file('service.yaml')?.exists()
                }
        
                doLast {
                    exec {
                        commandLine 'kubectl', 'apply', '-f', 'service.yaml'
                    }
        
                    file('.service').text = '${slot.k8sName}'
                }
            }
                    
            task deploy {
                dependsOn 'publish', 'deployService', ':deployNamespace', ':deployVolume', ':deployServices', ':deployRoles'
                inputs.files 'deployment.yaml', '.push'
                outputs.files '.deployment'
        
                doLast {
                    exec {
                        commandLine 'kubectl', 'apply', '-f', 'deployment.yaml'
                    }
        
                    file('.deployment').text = file('.image').text
                }
            }
        
            task monitor(type:KeepTryingExec) {
                dependsOn 'deploy'
                commandLine 'kubectl', 'logs', '--namespace', '${Kubert.namespace}', '--tail', '-1', '-lname=${slot.k8sName}', '-f'
            }
        
            task deleteImage {
                dependsOn ':tearDown'
                
                doLast {
                    def imageFile = file('.image')
                    if(imageFile.exists()) {
                        exec {
                            commandLine 'docker', 'rmi', file('.image').text
                            ignoreExitValue true
                        }
                    }
                }
            }
        
            task cleanUp {
                dependsOn 'deleteImage'
                doLast {
                    delete 'cpp', '.image', '.push', '.service', '.deployment', '.namespace', '.roles'
                }
            }
        
            clean.dependsOn 'cleanUp'
            build.dependsOn 'containerize'
        
            """.trimIndent()
    }

    private fun gradleSettingsFile(slots: List<RTSlot>): String {
        return """
            rootProject.name = '${Kubert.namespace}'
            include 'mqtt',${slots.joinToString(separator = ",") { "'" + it.name + "'" }}
        """.trimIndent()
    }

    private fun gradlePropertiesFile(): String {
        return """
            org.gradle.parallel=true
            org.gradle.workers.max=4
        """.trimIndent()
    }

    private fun gradleRootScript(): String {
        return """
            plugins {
                id 'java'
            }
            
            task deployNamespace {
                inputs.files 'namespace.yaml'
                outputs.files '.namespace'
            
                doLast {
                    exec {
                        commandLine 'kubectl', 'apply', '-f', 'namespace.yaml'
                    }
            
                    file('.namespace').text = '${Kubert.namespace}'
                }
            }
            
            task deployRoles {
                dependsOn 'deployNamespace'
                inputs.files 'roles.yaml'
                outputs.files '.roles'
            
                doLast {
                    exec {
                        commandLine 'kubectl', 'apply', '-f', 'roles.yaml'
                    }
            
                    file('.roles').text = '${Kubert.namespace}'
                }
            }
            
            task deploy {
            
            }
            
            task deployVolume {
                inputs.files 'volume.yaml'
                outputs.files '.volume'
            
                doLast {
                    exec {
                        commandLine 'kubectl', 'apply', '-f', 'volume.yaml'
                    }
            
                    file('.volume').text = '${Kubert.namespace}'
                }
            }
            
            task deployServices {
            
            }
            
            task run(type:Exec) {
                dependsOn 'deploy'
                commandLine 'kubectl', '--namespace', '${Kubert.namespace}', 'scale', 'deployment', '-lparent=', '--replicas=1'
            }
            
            task stop(type:Exec) {
                commandLine 'kubectl', '--namespace', '${Kubert.namespace}', 'scale', 'deployment', '-lapp=${Kubert.appName}', '--replicas=0'
                ignoreExitValue true
            }
            
            task monitorAll(type:Exec) {
                dependsOn 'deploy'
                commandLine 'kubectl', 'logs', '--namespace', '${Kubert.namespace}', '--tail', '-1', '--max-log-requests', '1000', '-lapp=${Kubert.appName}', '-f'
            }
            
            task logs(type:Exec) {
                dependsOn 'deploy'
                commandLine 'kubectl', 'logs', '--namespace', '${Kubert.namespace}', '--tail', '-1', '--max-log-requests', '1000', '-lapp=${Kubert.appName}'
            }
                   
            task deleteNamespace(type:Exec) {
                commandLine 'kubectl', 'delete', 'namespaces', '${Kubert.namespace}'
                ignoreExitValue true
            
                doLast {
                    delete '.namespace', '.roles'
                }
            }
            
            task deleteVolume(type:Exec) {
                dependsOn 'deleteNamespace'
            
                commandLine 'kubectl', 'delete', 'pv', '${Kubert.namespace}'
                ignoreExitValue true
            
                doLast {
                    delete '.volume'
                }
            }
                                    
            task tearDown {
                dependsOn 'deleteNamespace', 'deleteVolume'
            }
            
            task pruneImages() {
                doLast {
                    exec {
                        commandLine 'docker', 'image', 'prune', '-a', '-f', '--filter', 'label=namespace=${Kubert.namespace}'
                        ignoreExitValue true
                    }
                }
            }
            
            subprojects {
                rootProject.clean.dependsOn getTasks().matching {it.name == 'clean'}
                rootProject.deploy.dependsOn getTasks().matching {it.name == 'deploy'}
                rootProject.deployServices.dependsOn getTasks().matching {it.name == 'deployService'}
            }        
        """.trimIndent()
    }

    private fun nameSpaceFile(): String {
        return """
            ---
            apiVersion: v1
            kind: Namespace
            metadata:
              name: ${Kubert.namespace}
        """.trimIndent()
    }

    private fun volumeFile(): String {
        return """
            apiVersion: storage.k8s.io/v1
            kind: StorageClass
            metadata:
              name: ${Kubert.namespace}
            provisioner: docker.io/hostpath
            reclaimPolicy: Delete
            ---
            apiVersion: v1
            kind: PersistentVolume
            metadata:
              name: ${Kubert.namespace}
              labels:
                type: local
            spec:
              storageClassName: manual
              capacity:
                storage: 1Gi
              accessModes:
                - ReadWriteMany
              hostPath:
                path: "/mnt/data"
            ---
            apiVersion: v1
            kind: PersistentVolumeClaim
            metadata:
              name: ${Kubert.namespace}
              namespace: ${Kubert.namespace}
            spec:
              storageClassName: manual
              accessModes:
                - ReadWriteMany
              resources:
                requests:
                  storage: 1Gi
              storageClassName: ${Kubert.namespace}
        """.trimIndent()
    }

    private fun rolesFile(): String {
        return """
            apiVersion: rbac.authorization.k8s.io/v1
            kind: Role
            metadata:
              namespace: ${Kubert.namespace}
              name: reader
            rules:
            - apiGroups: ["", "apps", "extensions"]
              resources: ["pods", "deployments"]
              verbs: ["get", "watch", "list"]
              
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: RoleBinding
            metadata:
              name: read
              namespace: ${Kubert.namespace}
            subjects:
            - kind: ServiceAccount
              name: default
            roleRef:
              kind: Role
              name: reader
              apiGroup: rbac.authorization.k8s.io
            
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: Role
            metadata:
              namespace: ${Kubert.namespace}
              name: scaler
            rules:
              - apiGroups: ["", "apps", "extensions"]
                resources: ["deployments", "deployments/scale"]
                verbs: ["patch"]
            
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: RoleBinding
            metadata:
              name: scale
              namespace: ${Kubert.namespace}
            subjects:
              - kind: ServiceAccount
                name: default
            roleRef:
              kind: Role
              name: scaler
              apiGroup: rbac.authorization.k8s.io
        """.trimIndent()
    }

    private fun findParentBehavior(slot: RTSlot): RTSlot? {
        return if (slot.parent != null)
            if (slot.parent.part.capsule.stateMachine != null)
                slot.parent
            else findParentBehavior(slot.parent)
        else null
    }

    private fun writeToFile(str: String, file: File) {
        val out = PrintWriter(file)
        out.print(str)
        out.close()
    }
}