package ca.jahed.kubert.model

import ca.jahed.kubert.Kubert
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.cppproperties.RTAttributeProperties
import ca.jahed.rtpoet.rtmodel.cppproperties.RTCapsuleProperties
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTLogProtocol
import ca.jahed.rtpoet.rtmodel.sm.*
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTBoolean
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTInteger
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTString

class RTControllerCapsule(numNeighbors: Int, controlPortRegistration: String) :
    RTCapsule(NameUtils.randomize(RTControllerCapsule::class.java.simpleName)) {

    init {
        attributes.add(RTAttribute.builder("processing", RTBoolean).build())

        attributes.add(RTAttribute.builder("inQ", RTInteger)
            .properties(RTAttributeProperties.builder().type("std::queue<${RTExtMessage.name}>"))
            .build()
        )

        attributes.add(RTAttribute.builder("outQ", RTInteger)
            .properties(RTAttributeProperties.builder().type("std::queue<${RTExtMessage.name}>"))
            .build()
        )

        ports.add(RTPort.builder("log", RTLogProtocol).internal().build())

        ports.add(RTPort.builder("controlPort", RTControlProtocol)
            .spp().registrationOverride(controlPortRegistration).build())

        ports.add(RTPort.builder("coderPort", RTRelayProtocol).spp()
            .registrationOverride(NameUtils.randomString(8)).replication(numNeighbors).build())

        ports.add(RTPort.builder("commPort", RTRelayProtocol).spp()
            .registrationOverride(NameUtils.randomString(8)).replication(numNeighbors).build())

        operations.add(RTOperation.builder("saveState")
            .parameter(RTParameter.builder("state", RTString))
            .ret(RTParameter.builder(RTBoolean))
            .action(RTAction.builder("""
                return true;
            """.trimIndent()))
            .build()
        )

        operations.add(RTOperation.builder("restoreState")
            .ret(RTParameter.builder(RTString))
            .action(RTAction.builder("""
                return "";
            """.trimIndent()))
            .build()
        )

        properties = RTCapsuleProperties.builder().headerPreface("""
            #include <queue>
        """.trimIndent()).build()

        stateMachine = RTStateMachine.builder()
            .state(RTPseudoState.initial("init"))
            .state(RTState.builder("collecting"))

            .transition(RTTransition.builder("init", "collecting").action("""
                if(${Kubert.debug}) log.log("[%s] controller %p starting", this->getSlot()->name, this);
                this->processing = true;
            """.trimIndent()))

            .transition(RTTransition.builder("collecting", "collecting")
                .trigger("controlPort", "messageProcessed")
                .action("""
                    this->saveState((char*)state);
                    this->processing = false;

                    // send out messages
                    while (!outQ.empty()) {
                        ${RTExtMessage.name} rtMessage = outQ.front();
                        outQ.pop();
                        commPort.relay(rtMessage).sendAt(rtMessage.srcPortIndex);
                     }

                    // relay next message
                    if(!inQ.empty()) {
                        ${RTExtMessage.name} rtMessage = inQ.front();
                        inQ.pop();
                        coderPort.relay(rtMessage).sendAt(rtMessage.srcPortIndex);
                        this->processing = true;
                    }
                """.trimIndent())
            )

            .transition(RTTransition.builder("collecting", "collecting")
                .trigger("commPort", "relay")
                .action("""
                    ${RTExtMessage.name} rtMessageCpy = { strdup(rtMessage.payload), msg->sapIndex0() };
                    if(!this->processing) {
                        coderPort.relay(rtMessageCpy).sendAt(rtMessageCpy.srcPortIndex);
                        this->processing = true;
                    } else {
                        inQ.push(rtMessageCpy);
                    }
                """.trimIndent())
            )

            .transition(RTTransition.builder("collecting", "collecting")
                .trigger("coderPort", "relay")
                .action("""
                    ${RTExtMessage.name} rtMessageCpy = { strdup(rtMessage.payload), msg->sapIndex0() };
                    if(!this->processing) {
                        commPort.relay(rtMessageCpy).sendAt(rtMessageCpy.srcPortIndex);
                    } else {
                        outQ.push(rtMessageCpy);
                    }
                """.trimIndent())
            )

            .build(this)
    }
}