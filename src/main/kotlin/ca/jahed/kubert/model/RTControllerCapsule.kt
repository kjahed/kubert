package ca.jahed.kubert.model

import ca.jahed.kubert.Kubert
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.cppproperties.RTAttributeProperties
import ca.jahed.rtpoet.rtmodel.cppproperties.RTCapsuleProperties
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTLogProtocol
import ca.jahed.rtpoet.rtmodel.sm.*
import ca.jahed.rtpoet.rtmodel.types.RTType
import ca.jahed.rtpoet.rtmodel.types.primitivetype.*

class RTControllerCapsule(numNeighbors: Int, controlProtocol: RTProtocol, controlPortName: String) :
    RTCapsule(NameUtils.randomize(RTControllerCapsule::class.java.simpleName)) {
    private val stateFileName = NameUtils.randomize("state");
    private val saveStateSignal = controlProtocol.inputSignals.first {it.name == "saveState"}
    private val restoreStateSignal = controlProtocol.outputSignals.first {it.name == "restoreState"}

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

        ports.add(RTPort.builder(controlPortName, controlProtocol).spp().notification().build())

        ports.add(RTPort.builder("coderPort", RTRelayProtocol).spp()
            .registrationOverride(NameUtils.randomString(8)).replication(numNeighbors).build())

        ports.add(RTPort.builder("commPort", RTRelayProtocol).spp()
            .registrationOverride(NameUtils.randomString(8)).replication(numNeighbors).build())

        //TODO: fix hacky replace
        operations.add(RTOperation.builder("saveState")
            .parameter(RTParameter.builder("state", RTString))
            .action(RTAction.builder("""
                std::ofstream stateFile;
                stateFile.open("/data/${stateFileName}");
                stateFile << state;
                stateFile.close();
            """.trimIndent()))
            .build()
        )

        operations.add(RTOperation.builder("readState")
            .ret(RTParameter.builder(RTString))
            .action(RTAction.builder("""
                string state;
                std::ifstream stateFile("/data/${stateFileName}");
                if(stateFile.is_open()) {
                    std::getline(stateFile, state);
                    stateFile.close();
                }
                
                return !state.empty() ? strdup(state.c_str()) : NULL;
            """.trimIndent()))
            .build()
        )

        properties = RTCapsuleProperties.builder().headerPreface("""
            #include "umlrtjsoncoder.hh"
            #include <queue>
            #include <iostream>
            #include <fstream>
            #include <string>
        """.trimIndent()).build()

        stateMachine = RTStateMachine.builder()
            .state(RTPseudoState.initial("init"))
            .state(RTState.builder("waitForMainCapsuleBind"))
            .state(RTState.builder("collecting"))

            .transition(RTTransition.builder("init", "waitForMainCapsuleBind").action("""
                if(${Kubert.debug}) log.log("[%s] controller %p starting", this->getSlot()->name, this);
                this->processing = true;
            """.trimIndent()))

            .transition(RTTransition.builder("waitForMainCapsuleBind", "collecting")
                .trigger(controlPortName, "rtBound")
                .action("""
                    const char * stateJson = this->readState();
                    if(stateJson == NULL) {
                        ${controlPortName}.initialState().sendAt(msg->sapIndex0());
                    } else {
                    
                        UMLRTJSONCoder jsonCoder = UMLRTJSONCoder(stateJson);
                        ${restoreStateSignal.parameters.joinToString("\n") { """
                            ${getTypeName(it.type)} ${it.name};
                            jsonCoder.decode(&${getTypeDescriptor(it.type)}, (uint8_t*) &${it.name});
                        """.trimIndent() }}
                    
                        $controlPortName.${restoreStateSignal.name}(
                        ${restoreStateSignal.parameters.joinToString(",") { it.name }}
                        ).send();
                        
                        if(${Kubert.debug}) log.log("[%s] decoded state %s", this->getSlot()->name, stateJson);
                    }
                """.trimIndent())
            )


            .transition(RTTransition.builder("collecting", "collecting")
                .trigger(controlPortName, saveStateSignal.name)
                .action("""
                    UMLRTJSONCoder jsonCoder = UMLRTJSONCoder();
                    ${saveStateSignal.parameters.joinToString("\n") { """
                        jsonCoder.encode(&${getTypeDescriptor(it.type)}, (uint8_t*) &${it.name});
                    """.trimIndent() }}

                    char * stateJson;
                    jsonCoder.commit(&stateJson);
                    if(${Kubert.debug}) log.log("[%s] state json @%s", this->getSlot()->name, stateJson);
                    if(stateJson == NULL)
                        log.log("[%s] error encoding state", this->getSlot()->name);
                    else
                        this->saveState(stateJson);
                    
                    // send out messages
                    while (!outQ.empty()) {
                        ${RTExtMessage.name} rtMessage = outQ.front();
                        outQ.pop();
                        commPort.relay(rtMessage).sendAt(rtMessage.srcPortIndex);
                     }

                    this->processing = false;

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

    private fun getTypeDescriptor(type: RTType): String {
        var desc = "UMLRTType_${type.name}"
        when(type) {
            is RTString -> desc = "UMLRTType_charptr"
            is RTInteger -> desc = "UMLRTType_int"
            is RTBoolean -> desc = "UMLRTType_bool"
            is RTReal -> desc = "UMLRTType_double"
            is RTUnlimitedNatural -> desc = "UMLRTType_longdouble"
        }
        return desc
    }

    private fun getTypeName(type: RTType): String {
        var name = type.name
        when(type) {
            is RTString -> name = "char *"
            is RTInteger -> name = "int"
            is RTBoolean -> name = "bool"
            is RTReal -> name = "double"
            is RTUnlimitedNatural -> name = "long double"
        }
        return name;
    }
}