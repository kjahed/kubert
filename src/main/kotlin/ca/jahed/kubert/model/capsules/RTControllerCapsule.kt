package ca.jahed.kubert.model.capsules

import ca.jahed.kubert.KubertConfiguration
import ca.jahed.kubert.model.classes.RTExtMessage
import ca.jahed.kubert.model.protocols.RTRelayProtocol
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.cppproperties.RTAttributeProperties
import ca.jahed.rtpoet.rtmodel.cppproperties.RTCapsuleProperties
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTLogProtocol
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTTimingProtocol
import ca.jahed.rtpoet.rtmodel.sm.RTPseudoState
import ca.jahed.rtpoet.rtmodel.sm.RTState
import ca.jahed.rtpoet.rtmodel.sm.RTStateMachine
import ca.jahed.rtpoet.rtmodel.sm.RTTransition
import ca.jahed.rtpoet.rtmodel.types.RTType
import ca.jahed.rtpoet.rtmodel.types.primitivetype.*

class RTControllerCapsule(numNeighbors: Int, controlProtocol: RTProtocol, controlPortName: String, config: KubertConfiguration) :
    RTCapsule(NameUtils.randomize(RTControllerCapsule::class.java.simpleName)) {
    private val stateFileName = NameUtils.randomize("state");
    private val outQFileName = NameUtils.randomize("outQ");

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
        ports.add(RTPort.builder("timer", RTTimingProtocol).internal().build())

        ports.add(RTPort.builder(controlPortName, controlProtocol).spp().notification().build())

        ports.add(RTPort.builder("coderPort", RTRelayProtocol).spp()
            .registrationOverride(NameUtils.randomString(8)).replication(numNeighbors).build())

        ports.add(RTPort.builder("commPort", RTRelayProtocol).spp()
            .registrationOverride(NameUtils.randomString(8)).replication(numNeighbors).build())

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

        operations.add(RTOperation.builder("dumpOutQ")
            .action(RTAction.builder("""
                UMLRTJSONCoder jsonCoder = UMLRTJSONCoder();

                int qSize = outQ.size();
                jsonCoder.encode(&${getTypeDescriptor(RTInteger)}, (uint8_t*) &qSize);
                
                for(int i=0; i<qSize; i++) {
                    ${RTExtMessage.name} rtMessage = outQ.front();
                    jsonCoder.encode(&${getTypeDescriptor(RTExtMessage)}, (uint8_t*) &rtMessage);
                    
                    outQ.pop();
                    outQ.push(rtMessage);
                }
                
                char * json;
                jsonCoder.commit(&json);
                                
                std::ofstream outQFile;
                outQFile.open("/data/${outQFileName}");
                outQFile << json;
                outQFile.close();
            """.trimIndent()))
            .build()
        )

        operations.add(RTOperation.builder("loadOutQ")
            .action(RTAction.builder("""
                string json;
                std::ifstream outQFile("/data/${outQFileName}");
                if(outQFile.is_open()) {
                    std::getline(outQFile, json);
                    outQFile.close();
                }
                
                if(!json.empty()) {
                    UMLRTJSONCoder jsonCoder = UMLRTJSONCoder(json.c_str());
                    int qSize;
                    jsonCoder.decode(&${getTypeDescriptor(RTInteger)}, (uint8_t*) &qSize);
                    
                    for(int i=0; i<qSize; i++) {
                        ${RTExtMessage.name} rtMessage;
                        jsonCoder.decode(&${getTypeDescriptor(RTExtMessage)}, (uint8_t*) &rtMessage);
                        outQ.push(rtMessage);
                    }
                }
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
                if(${config.debug}) log.log("[%s] controller %p starting", this->getSlot()->name, this);
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
                        
                        if(${config.debug}) log.log("[%s] decoded state %s", this->getSlot()->name, stateJson);
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
                    if(${config.debug}) log.log("[%s] state json @%s", this->getSlot()->name, stateJson);
                    if(stateJson == NULL)
                        log.log("[%s] error encoding state", this->getSlot()->name);
                    else
                        this->saveState(stateJson);

                    this->processing = false;
                    timer.informIn(UMLRTTimespec(0,0));
                """.trimIndent())
            )

            .transition(RTTransition.builder("collecting", "collecting")
                .trigger("timer", "timeout")
                .guard("""
                    return !this->processing;
                """.trimIndent())
                .action("""
                    //this->dumpOutQ();
                    
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
                    inQ.push(rtMessageCpy);
                    timer.informIn(UMLRTTimespec(0,0));
                """.trimIndent())
            )

            .transition(RTTransition.builder("collecting", "collecting")
                .trigger("coderPort", "relay")
                .action("""
                    ${RTExtMessage.name} rtMessageCpy = { strdup(rtMessage.payload), msg->sapIndex0() };
                    outQ.push(rtMessageCpy);
                    timer.informIn(UMLRTTimespec(0,0));
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