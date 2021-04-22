package ca.jahed.kubert.model

import ca.jahed.kubert.Kubert
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.rts.classes.RTSystemClass
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTSystemProtocol
import ca.jahed.rtpoet.rtmodel.visitors.RTVisitorListener
import ca.jahed.rtpoet.utils.RTDeepCopier

class RTPartialModel(private val mainSlot: RTSlot):
    RTModel(mainSlot.name, RTCapsulePart(NameUtils.randomize("top"), RTCapsule(NameUtils.randomize("Top")))) {
    private val copier = RTDeepCopier(listOf(RTCapsulePart::class.java, RTConnector::class.java))
    private var container = top.capsule

    private val proxyParts = mutableMapOf<Pair<RTSlot?, RTCapsulePart>, RTCapsulePart>()
    private val proxyPorts = mutableMapOf<Pair<RTPort, RTSlot>, RTPort>()

    init {
        copier.addListener(object : RTVisitorListener {
            override fun onVisit(element: RTElement, result: Any) {
                when(result) {
                    is RTSystemProtocol -> {}
                    is RTSystemClass -> {}
                    is RTCapsule -> {}
                    is RTArtifact -> artifacts.add(result)
                    is RTClass -> classes.add(result)
                    is RTProtocol -> protocols.add(result)
                }
            }
        })

        capsules.add(container)
        if(mainSlot.parent != null &&
            mainSlot.neighbors.contains(mainSlot.parent)) {
            val proxyPart = createProxyPart(mainSlot.parent)
            container.parts.add(proxyPart)
            container = proxyPart.capsule
            capsules.add(container)
        }

        val mainPart = createMainPart(mainSlot)
        container.parts.add(mainPart)
        capsules.add(mainPart.capsule)

        mainSlot.neighbors.forEach {
            if(Pair(it.parent, it.part) !in proxyParts.keys) {
                val proxyPart = createProxyPart(it)
                capsules.add(proxyPart.capsule)

                if(it in mainSlot.children) mainPart.capsule.parts.add(proxyPart)
                else container.parts.add(proxyPart)
            }

            val isServer = mainSlot.k8sName > it.k8sName
            if(isServer) mainSlot.servicePorts.add(Pair(it.k8sName, Kubert.baseTcpPort + it.position))
        }

        val connectors = mutableSetOf<Pair<RTPort, RTCapsulePart>>()
        mainSlot.connections.forEach { (port, connections) ->
            connections.filter { it in proxyPorts }.forEach {
                val destPort = proxyPorts[it]!!
                val destSlot = it.second
                val destPart = proxyParts[Pair(destSlot.parent, destSlot.part)]!!
                val srcPort = copier.copy(port) as RTPort

                if(srcPort.wired && Pair(destPort, destPart) !in connectors) {
                    val end1 =
                        if(destSlot in mainSlot.children)
                            RTConnectorEnd(srcPort)
                        else RTConnectorEnd(srcPort, mainPart)

                    val end2 =
                        if(destSlot === mainSlot.parent)
                            RTConnectorEnd(destPort)
                        else RTConnectorEnd(destPort, destPart)

                    val connector = RTConnector.builder(end1, end2).build()
                    connectors.add(Pair(destPort, destPart))

                    if(destSlot in mainSlot.children) mainPart.capsule.connectors.add(connector)
                    else container.connectors.add(connector)
                }
                else if(srcPort.registrationOverride.isEmpty()) {
                    srcPort.registrationOverride = NameUtils.randomString(8)
                    destPort.registrationOverride = srcPort.registrationOverride
                }
            }
        }
    }

    private fun createMainPart(slot: RTSlot): RTCapsulePart {
        val capsuleCopy = copier.copy(slot.part.capsule) as RTCapsule
        capsuleCopy.parts.clear()
        capsuleCopy.connectors.clear()
        return RTCapsulePart(slot.part.name, capsuleCopy)
    }

    private fun createProxyPart(slot: RTSlot): RTCapsulePart {
        val isServer = mainSlot.k8sName > slot.k8sName
        val portNameFull = if (isServer) slot.k8sName else mainSlot.k8sName
        val portName = portNameFull.substring(0, portNameFull.lastIndexOf('-') + 1)

        val hostName = if (isServer) mainSlot.k8sName else slot.k8sName
        val hostEnvVarPrefix = NameUtils.toLegalEnvVarName(hostName.substring(0, hostName.lastIndexOf('-') + 1))
        val portEnvVarPrefix = NameUtils.toLegalEnvVarName("_SERVICE_PORT_$portName")

//        val slotNameTemplate = "${slot.name.substring(0, slot.name.lastIndexOf('[')+1)}%d${slot.name.substring(slot.name.lastIndexOf(']'))}"
//        val subTopic = "${mainSlot.name}->${slotNameTemplate}"
//        val pubTopic = "${slotNameTemplate}->${mainSlot.name}"

        val ports = mutableListOf<RTPort>()
        mainSlot.getConnectionsWith(slot).forEach {
            val proxyPort = copyPort(it.first)
            proxyPort.notification = false
            ports.add(proxyPort)
            proxyPorts[it] = proxyPort
        }

        val partName = if(slot in mainSlot.children) slot.part.name else NameUtils.randomize(slot.part.name)
        val proxyPart = RTCapsulePart.builder(partName,
            RTTCPProxy(isServer, mainSlot.index, hostEnvVarPrefix, portEnvVarPrefix, ports))
//            RTMQTTProxy(pubTopic, subTopic, ports))
            .replication(if(slot === mainSlot.parent) 1 else slot.part.replication)
            .build()

        proxyParts[Pair(slot.parent, slot.part)] = proxyPart
        return proxyPart
    }

    private fun copyPort(port: RTPort): RTPort {
        val copy = RTPort(port.name, copier.copy(port.protocol) as RTProtocol)
        copy.registrationOverride = port.registrationOverride
        copy.registrationType = port.registrationType
        copy.publish = port.publish
        copy.notification = port.notification
        copy.conjugated = port.conjugated
        copy.service = port.service
        copy.wired = port.wired
        copy.behaviour = port.behaviour
        copy.replication = port.replication
        copy.visibility = port.visibility
        return copy
    }
 }