package ca.jahed.kubert.model

import ca.jahed.kubert.Kubert
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.rts.RTSystemSignal
import ca.jahed.rtpoet.rtmodel.rts.classes.RTSystemClass
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTSystemProtocol
import ca.jahed.rtpoet.rtmodel.sm.*
import ca.jahed.rtpoet.rtmodel.visitors.RTVisitorListener
import ca.jahed.rtpoet.utils.RTDeepCopier

class RTPartialModel(private val mainSlot: RTSlot):
    RTModel(mainSlot.name, RTCapsulePart(NameUtils.randomize("top"), RTCapsule(NameUtils.randomize("Top")))) {
    private val copier = RTDeepCopier(listOf(RTCapsulePart::class.java, RTConnector::class.java))

    private val controlPortRegistration = NameUtils.randomString(8)
    private val controllerCapsule = RTControllerCapsule(mainSlot.neighbors.size, controlPortRegistration)

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

        classes.add(RTExtMessage)
        protocols.add(RTRelayProtocol)
        protocols.add(RTControlProtocol)
        capsules.add(top.capsule)
        capsules.add(controllerCapsule)

        top.capsule.parts.add(
            RTCapsulePart.builder("controller", controllerCapsule).build()
        )

        // create parent proxy if it exists
        var container = top.capsule
        if(mainSlot.parent != null &&
            mainSlot.neighbors.contains(mainSlot.parent)) {
            val proxyPart = createProxyPart(mainSlot.parent)

            capsules.add(proxyPart.capsule)
            proxyPart.capsule.parts.forEach { capsules.add(it.capsule) }

            container.parts.add(proxyPart)
            container = proxyPart.capsule
        }

        // create main part
        val mainPart = createMainPart(mainSlot)
        container.parts.add(mainPart)
        capsules.add(mainPart.capsule)

        // create proxy parts
        mainSlot.neighbors.forEach {
            if(Pair(it.parent, it.part) !in proxyParts.keys) {
                val proxyPart = createProxyPart(it)
                capsules.add(proxyPart.capsule)
                proxyPart.capsule.parts.forEach { capsules.add(it.capsule) }

                if(it in mainSlot.children) mainPart.capsule.parts.add(proxyPart)
                else container.parts.add(proxyPart)
            }

            val isServer = mainSlot.k8sName > it.k8sName
            if(isServer) mainSlot.servicePorts.add(Pair(it.k8sName, Kubert.baseTcpPort + it.position))
        }

        // create proxy connectors
        val connected = mutableSetOf<Pair<RTPort, RTCapsulePart>>()
        mainSlot.connections.forEach { (port, connections) ->
            connections.filter { it in proxyPorts }.forEach {
                val destPort = proxyPorts[it]!!
                val destSlot = it.second
                val destPart = proxyParts[Pair(destSlot.parent, destSlot.part)]!!
                val srcPort = copier.copy(port) as RTPort

                if(srcPort.wired && Pair(destPort, destPart) !in connected) {
                    val end1 =
                        if(destSlot in mainSlot.children)
                            RTConnectorEnd(srcPort)
                        else RTConnectorEnd(srcPort, mainPart)

                    val end2 =
                        if(destSlot === mainSlot.parent)
                            RTConnectorEnd(destPort)
                        else RTConnectorEnd(destPort, destPart)

                    val connector = RTConnector.builder(end1, end2).build()
                    connected.add(Pair(destPort, destPart))

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

        val controlPort = RTPort.builder(NameUtils.randomize("controlPort"), RTControlProtocol).sap()
            .conjugate().notification().registrationOverride(controlPortRegistration).build()
        capsuleCopy.ports.add(controlPort)

        capsuleCopy.stateMachine!!.states().filterIsInstance<RTState>().forEach {
            val entryAction = it.entryAction ?: RTAction()
            entryAction.body = """
                ${controlPort.name}.messageProcessed("dummy").send();
                ${entryAction.body}
            """.trimIndent()
            it.entryAction = entryAction
        }

        val initState = capsuleCopy.stateMachine!!.states()
            .filterIsInstance<RTPseudoState>().first { it.kind == RTPseudoState.Kind.INITIAL }
        val initTransition = capsuleCopy.stateMachine!!.transitions().first { it.source == initState }

        val bindingState = RTState.builder(NameUtils.randomize("binding")).build()
        capsuleCopy.stateMachine!!.states().add(bindingState)
        initTransition.source = bindingState
        initTransition.triggers.add(RTTrigger(RTSystemSignal.rtBound(), controlPort))

        val newInitTransition = RTTransition.builder(initState, bindingState).build()
        capsuleCopy.stateMachine!!.transitions().add(newInitTransition)
        return RTCapsulePart(slot.part.name, capsuleCopy)
    }

    private fun createProxyPart(slot: RTSlot): RTCapsulePart {
        val coderPart = createCoderPart(slot)
        val commPart = createCommPart(slot)
        coderPart.capsule.parts.add(commPart)

        val controllerCoderPort = controllerCapsule.ports.first { it.name == "coderPort" }
        val controllerCommPort = controllerCapsule.ports.first { it.name == "commPort" }

        val coderRelayPort = coderPart.capsule.ports.first { it.name == "relay" }
        val commRelayPort = commPart.capsule.ports.first { it.name == "relay" }

        coderRelayPort.registrationOverride = controllerCoderPort.registrationOverride
        commRelayPort.registrationOverride = controllerCommPort.registrationOverride

        proxyParts[Pair(slot.parent, slot.part)] = coderPart
        return coderPart
    }

    private fun createCoderPart(slot: RTSlot): RTCapsulePart {
        val ports = mutableListOf<RTPort>()
        mainSlot.getConnectionsWith(slot).forEach {
            val proxyPort = copyPort(it.first)
            proxyPort.notification = false
            ports.add(proxyPort)
            proxyPorts[it] = proxyPort
        }

        val partName = if (slot in mainSlot.children) slot.part.name else NameUtils.randomize(slot.part.name)
        val replication = if (slot === mainSlot.parent) 1 else slot.part.replication

        return RTCapsulePart.builder(partName, RTCoderCapsule(ports))
            .replication(replication)
            .build()
    }

    private fun createCommPart(slot: RTSlot): RTCapsulePart {
        val isServer = mainSlot.k8sName > slot.k8sName
        val portNameFull = if (isServer) slot.k8sName else mainSlot.k8sName
        val portName = portNameFull.substring(0, portNameFull.lastIndexOf('-') + 1)

        val hostName = if (isServer) mainSlot.k8sName else slot.k8sName
        val hostEnvVarPrefix = NameUtils.toLegalEnvVarName(hostName.substring(0, hostName.lastIndexOf('-') + 1))
        val portEnvVarPrefix = NameUtils.toLegalEnvVarName("_SERVICE_PORT_$portName")

        return RTCapsulePart.builder("communicator",
            RTTCPCapsule(isServer, mainSlot.index, hostEnvVarPrefix, portEnvVarPrefix))
            .optional()
            .build()
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