package ca.jahed.kubert.model.capsules

import ca.jahed.kubert.Kubert
import ca.jahed.kubert.model.classes.RTExtMessage
import ca.jahed.kubert.model.protocols.RTRelayProtocol
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.cppproperties.RTCapsuleProperties
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTFrameProtocol
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTLogProtocol
import ca.jahed.rtpoet.rtmodel.sm.RTPseudoState
import ca.jahed.rtpoet.rtmodel.sm.RTState
import ca.jahed.rtpoet.rtmodel.sm.RTStateMachine
import ca.jahed.rtpoet.rtmodel.sm.RTTransition

class RTCoderCapsule(proxyPorts: List<RTPort>)
    : RTCapsule(NameUtils.randomize(RTCoderCapsule::class.java.simpleName)) {

    private var numBorderPorts = 0
    private var numInternalPorts = 0

    init {
        ports.add(RTPort.builder("relay", RTRelayProtocol).sap().notification().conjugate().build())
        ports.add(RTPort.builder("log", RTLogProtocol).internal().build())
        ports.add(RTPort.builder("frame", RTFrameProtocol).internal().build())
        numInternalPorts += 3

        proxyPorts.forEach {
            if (it.wired && it.service) numBorderPorts++ else numInternalPorts++
            ports.add(it)
        }

        operations.add(RTOperation.builder("recallAll")
            .action(RTAction.builder("""
                for(int i=0; i<${numBorderPorts}; i++) {
                    borderPorts[i]->recall();
                }

                for(int i=0; i<${numInternalPorts}; i++) {
                    if(internalPorts[i]->sap && !internalPorts[i]->automatic)
                        UMLRTProtocol::registerSapPort(internalPorts[i], internalPorts[i]->registrationOverride);
                    internalPorts[i]->recall();
                }
            """.trimIndent()))
            .build()
        )

        properties = RTCapsuleProperties.builder().implementationPreface("""
            #include <stdlib.h>
            #include "umlrtjsoncoder.hh"
        """.trimIndent()).build()

        stateMachine = RTStateMachine.builder()
            .state(RTPseudoState.initial("init"))
            .state(RTState.builder("waitingForControllerBind"))
            .state(RTState.builder("relaying"))

            .transition(RTTransition.builder("init", "waitingForControllerBind").action("""
                    frame.incarnate(communicator, new int(this->getIndex()));
                """.trimIndent())
            )

            .transition(RTTransition.builder("waitingForControllerBind", "relaying")
                .trigger("relay", "rtBound")
                .action("""
                    this->recallAll();
                """.trimIndent())
            )

            .transition(RTTransition.builder("relaying", "relaying") // proxy -> main
                .trigger("relay", "relay")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] got message %s", this->getSlot()->name, rtMessage.payload);
                    UMLRTOutSignal signal;
                    int destPortIdx;
                    UMLRTJSONCoder::fromJSON(rtMessage.payload, signal, getSlot(), &destPortIdx);
                    if(${Kubert.debug}) log.log("[%s] decoded signal %s", this->getSlot()->name, signal.getName());
                    signal.send();  
                """.trimIndent())
            )

            .transition(RTTransition.builder("relaying", "relaying") // main -> proxy
                .trigger("^(?!relay|log|frame).*$", "*")
                .action("""
                    char* json = NULL;
                    UMLRTJSONCoder::toJSON(msg, &json);
                    if(${Kubert.debug}) log.log("[%s] encoded message @%s", this->getSlot()->name, json);
                    
                    if(json == NULL) {
                        log.log("[%s] error encoding signal", this->getSlot()->name);
                    } else {
                        ${RTExtMessage.name} rtMessage = { json };
                        relay.relay(rtMessage).send();
                    }
                """.trimIndent())
            )

            .transition(RTTransition.builder("waitingForControllerBind", "waitingForControllerBind") // main -> proxy
                .trigger("^(?!relay|log|frame).*$", "*")
                .action("""
                    msg->defer();
                """.trimIndent())
            )

            .build(this)
    }
}