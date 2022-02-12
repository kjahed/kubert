package ca.jahed.kubert

import ca.jahed.kubert.model.RTPartialModel
import ca.jahed.kubert.model.RTSlot
import ca.jahed.kubert.utils.RTHierarchyUtils
import ca.jahed.rtpoet.papyrusrt.PapyrusRTWriter
import ca.jahed.rtpoet.papyrusrt.rts.PapyrusRTLibrary
import ca.jahed.rtpoet.rtmodel.RTModel
import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.xmi.XMIResource
import java.io.File
import java.io.PrintWriter

object ModelTransformer {
    fun transform(model: RTModel, config: KubertConfiguration) {
        val topSlot = RTHierarchyUtils.buildHierarchy(model)
        val slots = processSlot(topSlot, config)

//        val mqttDir = File(config.outputDir, "mqtt")
//        mqttDir.mkdirs()
//        writeToFile(mqttConfigMap(config), File(mqttDir, "configmap.yaml"))
//        writeToFile(mqttDeployment(config), File(mqttDir, "deployment.yaml"))
//        writeToFile(mqttService(config), File(mqttDir, "service.yaml"))
//        writeToFile(mqttGradleScript(config), File(mqttDir, "build.gradle"))

        writeToFile(gradleSettingsFile(slots, config), File(config.outputDir, "settings.gradle"))
        writeToFile(gradlePropertiesFile(config), File(config.outputDir, "gradle.properties"))
        writeToFile(gradleRootScript(config), File(config.outputDir, "build.gradle"))
        writeToFile(nameSpaceFile(config), File(config.outputDir, "namespace.yaml"))
        writeToFile(volumeFile(config), File(config.outputDir, "volume.yaml"))
        writeToFile(rolesFile(config), File(config.outputDir, "roles.yaml"))
    }

    private fun processSlot(slot: RTSlot, config: KubertConfiguration): List<RTSlot> {
        val slots = mutableListOf<RTSlot>()
        for (child in slot.children)
            slots.addAll(processSlot(child, config))

        if (slot.part.capsule.stateMachine == null) return slots

        val outputDir = File(config.outputDir, slot.name)
        outputDir.mkdirs()

        val partialModel = RTPartialModel(slot, config)
        val resource = PapyrusRTLibrary.createResourceSet()
            .createResource(URI.createFileURI(File(outputDir, "model.uml").absolutePath))
        PapyrusRTWriter.write(resource, partialModel)
        (resource as XMIResource).eObjectToIDMap.clear()
        resource.save(null)

        writeToFile(dockerFile(partialModel, config), File(outputDir, "Dockerfile"))
        writeToFile(deploymentFile(slot, config), File(outputDir, "deployment.yaml"))
        writeToFile(gradleScript(slot, config), File(outputDir, "build.gradle"))
        if (slot.servicePorts.isNotEmpty()) writeToFile(serviceFile(slot, config), File(outputDir, "service.yaml"))

        slots.add(slot)
        return slots
    }

    private fun mqttConfigMap(config: KubertConfiguration): String {
        return """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: mosquitto-config
              namespace: ${config.namespace}
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

    private fun mqttDeployment(config: KubertConfiguration): String {
        return """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: mosquitto
              namespace: ${config.namespace}
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

    private fun mqttService(config: KubertConfiguration): String {
        return """
            apiVersion: v1
            kind: Service
            metadata:
              name: mqtt
              namespace: ${config.namespace}
            spec:
              selector:
                app: mosquitto
              ports:
              - port: 1883
                targetPort: 1883
        """.trimIndent()
    }

    private fun mqttGradleScript(config: KubertConfiguration): String {
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

    private fun dockerFile(model: RTModel, config: KubertConfiguration): String {
        return """
            FROM ${config.dockerRepo}umlrt-rts:1.0
            COPY ./cpp/src /app
            WORKDIR /app
            RUN make
            ENTRYPOINT flock -n /var/lock/app.lock /app/${model.top!!.capsule.name}Main ${config.umlrtArgs} 2>&1 | tee logfile
        """.trimIndent()
    }

    private fun deploymentFile(slot: RTSlot, config: KubertConfiguration): String {
        val parent = findParentBehavior(slot)
        return """
            ---
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: ${slot.k8sName}
              namespace: ${config.namespace}
              labels:
                app: ${config.appName}
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
                    app: ${config.appName}
                spec:
                  volumes:
                    - name: ${config.namespace}
                      persistentVolumeClaim:
                        claimName: ${config.namespace}
                  containers:
                    - name: ${slot.k8sName}
                      image: ${config.dockerRepo}${slot.k8sName}
                      volumeMounts:
                        - mountPath: "/data"
                          name: ${config.namespace}
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

    private fun serviceFile(slot: RTSlot, config: KubertConfiguration): String {
        return """
            ---
            apiVersion: v1
            kind: Service
            metadata:
              name: ${slot.k8sName}
              namespace: ${config.namespace}
              labels:
                app: ${config.appName}
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

    private fun gradleScript(slot: RTSlot, config: KubertConfiguration): String {
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
                classpath = files("${config.codeGenPath}/bin/umlrtgen.jar")
                args '-l', 'SEVERE', '-p', '${config.codeGenPath}/plugins', '-o', './cpp', './model.uml'
            }
        
            task containerize(type:Exec) {
                dependsOn 'generate'
                inputs.dir 'cpp'
                inputs.files 'Dockerfile'
                outputs.files '.image'
                commandLine 'docker', 'build', '--label', 'namespace=${config.namespace}', '-q', '-t', '${config.dockerRepo}${slot.k8sName}', '.'
                standardOutput new ByteArrayOutputStream()
        
                doLast {
                    file('.image').text = standardOutput.toString().substring(7, 7 + 4)
                }
            }
        
            task publish(type:Exec) {
                dependsOn 'containerize'
                inputs.files '.image'
                outputs.files '.push'
                commandLine 'docker', 'push', '-q', '${config.dockerRepo}${slot.k8sName}'
        
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
                commandLine 'kubectl', 'logs', '--namespace', '${config.namespace}', '--tail', '-1', '-lname=${slot.k8sName}', '-f'
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

    private fun gradleSettingsFile(slots: List<RTSlot>, config: KubertConfiguration): String {
        return """
            rootProject.name = '${config.namespace}'
            include 'mqtt',${slots.joinToString(separator = ",") { "'" + it.name + "'" }}
        """.trimIndent()
    }

    private fun gradlePropertiesFile(config: KubertConfiguration): String {
        return """
            org.gradle.parallel=true
            org.gradle.workers.max=4
        """.trimIndent()
    }

    private fun gradleRootScript(config: KubertConfiguration): String {
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
            
                    file('.namespace').text = '${config.namespace}'
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
            
                    file('.roles').text = '${config.namespace}'
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
            
                    file('.volume').text = '${config.namespace}'
                }
            }
            
            task deployServices {
            
            }
            
            task run(type:Exec) {
                dependsOn 'deploy'
                commandLine 'kubectl', '--namespace', '${config.namespace}', 'scale', 'deployment', '-lparent=', '--replicas=1'
            }
            
            task stop(type:Exec) {
                commandLine 'kubectl', '--namespace', '${config.namespace}', 'scale', 'deployment', '-lapp=${config.appName}', '--replicas=0'
                ignoreExitValue true
            }
            
            task monitorAll(type:Exec) {
                dependsOn 'deploy'
                commandLine 'kubectl', 'logs', '--namespace', '${config.namespace}', '--tail', '-1', '--max-log-requests', '1000', '-lapp=${config.appName}', '-f'
            }
            
            task logs(type:Exec) {
                dependsOn 'deploy'
                commandLine 'kubectl', 'logs', '--namespace', '${config.namespace}', '--tail', '-1', '--max-log-requests', '1000', '-lapp=${config.appName}'
            }
                   
            task deleteNamespace(type:Exec) {
                commandLine 'kubectl', 'delete', 'namespaces', '${config.namespace}'
                ignoreExitValue true
            
                doLast {
                    delete '.namespace', '.roles'
                }
            }
            
            task deleteVolume(type:Exec) {
                dependsOn 'deleteNamespace'
            
                commandLine 'kubectl', 'delete', 'pv', '${config.namespace}'
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
                        commandLine 'docker', 'image', 'prune', '-a', '-f', '--filter', 'label=namespace=${config.namespace}'
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

    private fun nameSpaceFile(config: KubertConfiguration): String {
        return """
            ---
            apiVersion: v1
            kind: Namespace
            metadata:
              name: ${config.namespace}
        """.trimIndent()
    }

    private fun volumeFile(config: KubertConfiguration): String {
        return """
            apiVersion: storage.k8s.io/v1
            kind: StorageClass
            metadata:
              name: ${config.namespace}
            provisioner: docker.io/hostpath
            reclaimPolicy: Delete
            ---
            apiVersion: v1
            kind: PersistentVolume
            metadata:
              name: ${config.namespace}
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
              name: ${config.namespace}
              namespace: ${config.namespace}
            spec:
              storageClassName: manual
              accessModes:
                - ReadWriteMany
              resources:
                requests:
                  storage: 1Gi
              storageClassName: ${config.namespace}
        """.trimIndent()
    }

    private fun rolesFile(config: KubertConfiguration): String {
        return """
            apiVersion: rbac.authorization.k8s.io/v1
            kind: Role
            metadata:
              namespace: ${config.namespace}
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
              namespace: ${config.namespace}
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
              namespace: ${config.namespace}
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
              namespace: ${config.namespace}
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