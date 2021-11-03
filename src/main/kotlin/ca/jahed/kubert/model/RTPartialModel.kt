package ca.jahed.kubert.model

import ca.jahed.kubert.Kubert
import ca.jahed.kubert.model.capsules.*
import ca.jahed.kubert.model.classes.RTExtMessage
import ca.jahed.kubert.model.classes.RTFramePortWrapper
import ca.jahed.kubert.model.protocols.RTControlProtocol
import ca.jahed.kubert.model.protocols.RTTimingProtocol
import ca.jahed.kubert.model.protocols.RTRelayProtocol
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.rts.RTSystemSignal
import ca.jahed.rtpoet.rtmodel.rts.classes.RTSystemClass
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTFrameProtocol
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTSystemProtocol
import ca.jahed.rtpoet.rtmodel.sm.*
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTBoolean
import ca.jahed.rtpoet.rtmodel.visitors.RTVisitorListener
import ca.jahed.rtpoet.utils.RTDeepCopier

class RTPartialModel(private val mainSlot: RTSlot):
    RTModel(mainSlot.name, RTCapsulePart(NameUtils.randomize("top"), RTCapsule(NameUtils.randomize("Top")))) {

    private val copier = RTDeepCopier(listOf(RTCapsulePart::class.java, RTConnector::class.java))
    private val proxyParts = mutableMapOf<Pair<RTSlot?, RTCapsulePart>, RTCapsulePart>()
    private val proxyPorts = mutableMapOf<Pair<RTPort, RTSlot>, RTPort>()

    init {
        classes.add(RTExtMessage)
        protocols.add(RTRelayProtocol)
        capsules.add(RTDummyCapsule)
        capsules.add(top!!.capsule)

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

        // create main capsule
        val mainCapsule = createMainCapsule()
        val mainCapsuleControlPort = mainCapsule.ports.first { it.protocol is RTControlProtocol }

        // create controller capsule
        val controlProtocol = mainCapsuleControlPort.protocol
        val controllerCapsule = RTControllerCapsule(mainSlot.neighbors.size,
            controlProtocol, mainCapsuleControlPort.name)

        capsules.add(mainCapsule)
        capsules.add(controllerCapsule)
        protocols.add(controlProtocol)

        // create controller part
        top!!.capsule.parts.add(
            RTCapsulePart.builder("controller", controllerCapsule).build()
        )

        // create parent proxy if it exists
        var container = top!!.capsule
        if(mainSlot.parent != null &&
            mainSlot.neighbors.contains(mainSlot.parent)) {
            val proxyPart = createProxyPart(mainSlot.parent, controllerCapsule)

            capsules.add(proxyPart.capsule)
            proxyPart.capsule.parts.forEach { capsules.add(it.capsule) }

            container.parts.add(proxyPart)
            container = proxyPart.capsule
        }

        // create main part
        val mainPart = RTCapsulePart.builder(mainSlot.part.name, mainCapsule).build()
        container.parts.add(mainPart)

        // create proxy parts
        mainSlot.neighbors.forEach {
            if(Pair(it.parent, it.part) !in proxyParts.keys) {
                val proxyPart = createProxyPart(it, controllerCapsule)
                capsules.add(proxyPart.capsule)
                proxyPart.capsule.parts.forEach { capsules.add(it.capsule) }

                if(it in mainSlot.children) mainCapsule.parts.add(proxyPart)
                else container.parts.add(proxyPart)
            }

            val isServer = mainSlot.k8sName > it.k8sName
            if(isServer) mainSlot.servicePorts.add(Pair(it.k8sName, Kubert.baseTcpPort + it.position))
        }

        // create unconnected children parts
        mainSlot.children.forEach {
            if(Pair(it.parent, it.part) !in proxyParts.keys) {
                val proxyPart = createDummyProxyPart(it)
                mainCapsule.parts.add(proxyPart)
            }
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

    private fun createMainCapsule(): RTCapsule {
        val capsuleCopy = copier.copy(mainSlot.part.capsule) as RTCapsule
        capsuleCopy.parts.clear()
        capsuleCopy.connectors.clear()
        val attributes = capsuleCopy.attributes.map { it }
        val stateMachine = capsuleCopy.stateMachine!!
        val states = stateMachine.states().map { it }

        val controlProtocol = RTControlProtocol(capsuleCopy)
        val controlPort = RTPort.builder(NameUtils.randomize("controlPort"), controlProtocol)
            .sap().conjugate().notification().build()
        capsuleCopy.ports.add(controlPort)

        val saveStateParams = mutableListOf("(char*)this->getCurrentStateString()")
        attributes.filter { it.type !is RTSystemClass }.forEach { saveStateParams.add("this->${it.name}") }

        val frameWrapper = RTFramePortWrapper(mainSlot)
        classes.add(frameWrapper)

        val framePortWrapperMap = mutableMapOf<RTPort, RTAttribute>() // port -> frame wrapper
        capsuleCopy.ports.filter { it.protocol is RTFrameProtocol }.forEach {
            val wrapper = RTAttribute.builder(it.name, frameWrapper).build()
            capsuleCopy.attributes.add(wrapper)
            it.name = NameUtils.randomize(it.name)
            framePortWrapperMap[it] = wrapper
        }

        val disableEntryCodeAttribute =
            RTAttribute.builder(NameUtils.randomize("disableEntryCodes"), RTBoolean).build()
        capsuleCopy.attributes.add(disableEntryCodeAttribute)

        states.filterIsInstance<RTState>().forEach {
            val entryAction = it.entryAction ?: RTAction()
            entryAction.body = """                
                if(!this->${disableEntryCodeAttribute.name}) {
                    ${entryAction.body}
                }
                
                this->${disableEntryCodeAttribute.name} = false;
                ${controlPort.name}.saveState(${saveStateParams.joinToString(",")}).send();
            """.trimIndent()

            it.entryAction = entryAction
        }

        val bindingState = RTState.builder(NameUtils.randomize("binding")).build()
        val waitingForStateState = RTState.builder(NameUtils.randomize("waitingForState")).build()
        val restoringStateChoice = RTPseudoState.choice(NameUtils.randomize("stateSelector")).build()

        stateMachine.states().add(bindingState)
        stateMachine.states().add(waitingForStateState)
        stateMachine.states().add(restoringStateChoice)

        val initState = stateMachine.states()
            .filterIsInstance<RTPseudoState>().first { it.kind == RTPseudoState.Kind.INITIAL }
        val originalInitTrans = stateMachine.transitions().first { it.source == initState }
        originalInitTrans.source = waitingForStateState
        originalInitTrans.triggers.add(
            RTTrigger(controlPort.inputs().first { it.name == "initialState" }, controlPort)
        )

        stateMachine.transitions().add(
            RTTransition.builder(initState, bindingState).action("""
                ${framePortWrapperMap.keys.joinToString("\n") { """
                    ${framePortWrapperMap[it]!!.name}.setPort(&${it.name});
                """.trimIndent() }}
            """.trimIndent()).build()
        )

        stateMachine.transitions().add(
            RTTransition.builder(bindingState, waitingForStateState)
                .trigger(RTTrigger(RTSystemSignal.rtBound(), controlPort)).build()
        )

        stateMachine.transitions().add(
            RTTransition.builder(waitingForStateState, restoringStateChoice)
                .trigger(
                    RTTrigger.builder(controlPort.inputs().first { it.name == "restoreState" }, controlPort).build()
                )
                .action("""
                    this->${disableEntryCodeAttribute.name} = true;
                    ${attributes.filter { it.type !is RTSystemClass }.joinToString("\n") {"""
                        this->${it.name} = _${it.name};
                    """.trimIndent()}}
                """.trimIndent())
                .build()
        )

        states.filterIsInstance<RTState>().forEach {
            stateMachine.transitions().add(
                RTTransition.builder(restoringStateChoice, it)
                    .guard("""
                        return strcmp(${controlProtocol.smStateParameterName}, "${it.name}") == 0;
                    """.trimIndent())
                    .action("""
                        if(${Kubert.debug}) printf("[%s] restoring state to ${it.name}", this->getSlot()->name);
                    """.trimIndent())
                    .build()
            )
        }

        return capsuleCopy
    }

    private fun createDummyProxyPart(slot: RTSlot): RTCapsulePart {
        val dummyPartBuilder = RTCapsulePart.builder(slot.part.name, RTDummyCapsule).replication(slot.part.replication)
        if(slot.part.optional) dummyPartBuilder.optional()
        val dummyPart = dummyPartBuilder.build()
        proxyParts[Pair(slot.parent, slot.part)] = dummyPart
        return dummyPart
    }

    private fun createProxyPart(slot: RTSlot, controller: RTCapsule): RTCapsulePart {
        val coderPart = createCoderPart(slot)
        val commPart = createTCPPart(slot)
        coderPart.capsule.parts.add(commPart)

        val controllerCoderPort = controller.ports.first { it.name == "coderPort" }
        val controllerCommPort = controller.ports.first { it.name == "commPort" }

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

        val partBuilder = RTCapsulePart.builder(partName, RTCoderCapsule(ports)).replication(replication)
        return partBuilder.build()
    }

    private fun createTCPPart(slot: RTSlot): RTCapsulePart {
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

    private fun createMQTTPart(slot: RTSlot): RTCapsulePart {
        val slotNameTemplate = "${slot.name.substring(0, slot.name.lastIndexOf('[')+1)}%d${slot.name.substring(slot.name.lastIndexOf(']'))}"
        val subTopic = "${mainSlot.name}->${slotNameTemplate}"
        val pubTopic = "${slotNameTemplate}->${mainSlot.name}"

        return RTCapsulePart.builder("communicator",
            RTMQTTCapsule(pubTopic, subTopic))
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