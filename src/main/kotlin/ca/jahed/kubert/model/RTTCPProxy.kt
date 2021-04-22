package ca.jahed.kubert.model

import ca.jahed.kubert.Kubert
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.papyrusrt.rts.protocols.RTTCPProtocol
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.cppproperties.RTCapsuleProperties
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTLogProtocol
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTTimingProtocol
import ca.jahed.rtpoet.rtmodel.sm.RTPseudoState
import ca.jahed.rtpoet.rtmodel.sm.RTState
import ca.jahed.rtpoet.rtmodel.sm.RTStateMachine
import ca.jahed.rtpoet.rtmodel.sm.RTTransition
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTBoolean
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTInteger
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTString

class RTTCPProxy(isServer: Boolean,
                 slotIndex: Int,
                 hostEnvVarPrefix: String,
                 portEnvVarPrefix: String,
                 proxyPorts: List<RTPort>)
    : RTCapsule(NameUtils.randomize(RTTCPProxy::class.java.simpleName)) {

    var numBorderPorts = 0
    var numInternalPorts = 0

    init {
        attributes.add(RTAttribute.builder("remoteHost", RTString).build())
        attributes.add(RTAttribute.builder("port", RTInteger).build())
        attributes.add(RTAttribute.builder("isServer", RTBoolean).build())

        ports.add(RTPort.builder("tcp", RTTCPProtocol).internal().build())
        ports.add(RTPort.builder("tcpServer", RTTCPProtocol).internal().build())
        ports.add(RTPort.builder("timer", RTTimingProtocol).internal().build())
        ports.add(RTPort.builder("log", RTLogProtocol).internal().build())
        numInternalPorts += 4

        proxyPorts.forEach {
            if (it.wired && it.service) numBorderPorts++ else numInternalPorts++
            ports.add(it)
        }

        operations.add(RTOperation.builder("getPort")
            .ret(RTParameter.builder(RTInteger))
            .action(RTAction.builder("""
                int hostIndex = this->isServer ? ${slotIndex} : this->getIndex();
                int hostIndexLength = hostIndex > 0 ? floor(log10(abs(hostIndex))) + 1 : 1;

                int portIndex = this->isServer ? this->getIndex() : ${slotIndex};
                int portIndexLength = portIndex > 0 ? floor(log10(abs(portIndex))) + 1 : 1;

                const char * hostEnvVarPrefix = "${hostEnvVarPrefix}";
                const char * portEnvVarPrefix = "${portEnvVarPrefix}";
                char * portEnvVar = (char*) malloc(sizeof(char) * (strlen(hostEnvVarPrefix) + hostIndexLength
                        + strlen(portEnvVarPrefix) + portIndexLength + 1));
                sprintf(portEnvVar, "%s%d%s%d", hostEnvVarPrefix, hostIndex, portEnvVarPrefix, portIndex);
                if(${Kubert.debug}) log.log("[%s] service port: %s", this->getSlot()->name, portEnvVar);

                int port = atoi(getenv(portEnvVar));
                free(portEnvVar);
                return port;
            """.trimIndent()))
            .build()
        )

        operations.add(RTOperation.builder("getHost")
            .ret(RTParameter.builder(RTString))
            .action(RTAction.builder("""
                int hostIndex = this->getIndex();
                int hostIndexLength = hostIndex > 0 ? floor(log10(abs(hostIndex))) + 1 : 1;

                const char * hostEnvVarPrefix = "${hostEnvVarPrefix}";
                char * hostEnvVar = (char*) malloc(sizeof(char) * (strlen(hostEnvVarPrefix) + hostIndexLength + 14));
                sprintf(hostEnvVar, "%s%d_SERVICE_HOST", hostEnvVarPrefix, hostIndex);
                if(${Kubert.debug}) log.log("[%s] service name: %s", this->getSlot()->name, hostEnvVar);

                const char * host = getenv(hostEnvVar);
                free(hostEnvVar);
                return strdup(host);
            """.trimIndent()))
            .build()
        )

        operations.add(RTOperation.builder("onInit")
            .action(RTAction.builder("""
                this->isServer = ${isServer};
                this->port = this->getPort();
            
                if(this->isServer) {
                    if(${Kubert.debug}) log.log("[%s] listening %d", this->getSlot()->name, this->port);
                    tcpServer.listen(this->port);
                    tcpServer.accept();
                }
                else {
                    this->remoteHost = this->getHost();
                    if(${Kubert.debug}) log.log("[%s] connecting to service %s:%d", this->getSlot()->name, this->remoteHost, this->port);
                    tcp.connect(this->remoteHost, this->port);   
                }
            """.trimIndent()))
            .build()
        )

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
            #include <math.h>
            #include "umlrtjsoncoder.hh"
        """.trimIndent()).build()

        stateMachine = RTStateMachine.builder()
            .state(RTPseudoState.initial("init"))
            .state(RTState.builder("connecting"))
            .state(RTState.builder("connected"))
            .state(RTState.builder("error"))

            .transition(RTTransition.builder("init", "connecting")
                .action("""
                    this->onInit();
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "connected")
                .trigger("(tcp|tcpServer)", "connected")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] connected", this->getSlot()->name);
                    if($isServer) tcp.attach(sockfd);
                    this->recallAll();
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "error")
                .trigger("tcp", "error")
                .action("""
                    if(${Kubert.debug} && errno != 111)
                        log.log("[%s] connection error: %d", this->getSlot()->name, errno);
                    timer.informIn(UMLRTTimespec(1,0));
                """.trimIndent())
            )

            .transition(RTTransition.builder("error", "connecting")
                .trigger("timer", "timeout")
                .action("""
                    tcp.connect(this->remoteHost, this->port);
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connected")
                .trigger("tcp", "received")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] got message %s", this->getSlot()->name, payload);
                    UMLRTOutSignal signal;
                    int destPortIdx;
                    UMLRTJSONCoder::fromJSON(payload, signal, getSlot(), &destPortIdx);
                    if(${Kubert.debug}) log.log("[%s] decoded signal %s", this->getSlot()->name, signal.getName());
                    signal.send();  
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connecting")
                .trigger("tcp", "disconnected")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] tcp disconnected", this->getSlot()->name);
                    this->onInit();
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connected")
                .trigger("^(?!tcp|tcpServer|timer|log).*$", "*")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] got signal %s on port %s", this->getSlot()->name, msg->signal.getName(), msg->signal.getSrcPort()->getName());
                    char* json = NULL;
                    UMLRTJSONCoder::toJSON(msg, &json);
                    if(${Kubert.debug}) log.log("[%s] sending message @%s", this->getSlot()->name, json);
                    
                    if(json == NULL)
                        log.log("[%s] error encoding signal", this->getSlot()->name);
                    else if(!tcp.send(json))
                        log.log("[%s] error sending message", this->getSlot()->name);
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "connecting")
                .trigger("^(?!tcp|tcpServer|timer|log).*$", "*")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] deferring signal %s on port %s", this->getSlot()->name, msg->signal.getName(), msg->signal.getSrcPort()->getName());
                    msg->defer();
                """.trimIndent())
            )

            .transition(RTTransition.builder("error", "error")
                .trigger("^(?!tcp|tcpServer|timer|log).*$", "*")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] deferring signal %s on port %s", this->getSlot()->name, msg->signal.getName(), msg->signal.getSrcPort()->getName());
                    msg->defer();
                """.trimIndent())
            )

            .build(this)
    }
}