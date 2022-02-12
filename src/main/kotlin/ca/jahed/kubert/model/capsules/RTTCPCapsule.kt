package ca.jahed.kubert.model.capsules

import ca.jahed.kubert.KubertConfiguration
import ca.jahed.kubert.model.classes.RTExtMessage
import ca.jahed.kubert.model.protocols.RTRelayProtocol
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

class RTTCPCapsule(isServer: Boolean,
                   slotIndex: Int,
                   hostEnvVarPrefix: String,
                   portEnvVarPrefix: String,
                   config: KubertConfiguration
)
    : RTCapsule(NameUtils.randomize(RTTCPCapsule::class.java.simpleName)) {

    init {
        attributes.add(RTAttribute.builder("index", RTInteger).build())
        attributes.add(RTAttribute.builder("remoteHost", RTString).build())
        attributes.add(RTAttribute.builder("port", RTInteger).build())
        attributes.add(RTAttribute.builder("isServer", RTBoolean).build())

        ports.add(RTPort.builder("relay", RTRelayProtocol).sap().conjugate().notification().build())
        ports.add(RTPort.builder("tcp", RTTCPProtocol).internal().build())
        ports.add(RTPort.builder("tcpServer", RTTCPProtocol).internal().build())
        ports.add(RTPort.builder("timer", RTTimingProtocol).internal().build())
        ports.add(RTPort.builder("log", RTLogProtocol).internal().build())

        operations.add(RTOperation.builder("getPort")
            .ret(RTParameter.builder(RTInteger))
            .action(RTAction.builder("""
                int hostIndex = this->isServer ? ${slotIndex} : this->index;
                int hostIndexLength = hostIndex > 0 ? floor(log10(abs(hostIndex))) + 1 : 1;

                int portIndex = this->isServer ? this->index : ${slotIndex};
                int portIndexLength = portIndex > 0 ? floor(log10(abs(portIndex))) + 1 : 1;

                const char * hostEnvVarPrefix = "${hostEnvVarPrefix}";
                const char * portEnvVarPrefix = "${portEnvVarPrefix}";
                char * portEnvVar = (char*) malloc(sizeof(char) * (strlen(hostEnvVarPrefix) + hostIndexLength
                        + strlen(portEnvVarPrefix) + portIndexLength + 1));
                sprintf(portEnvVar, "%s%d%s%d", hostEnvVarPrefix, hostIndex, portEnvVarPrefix, portIndex);

                int port = atoi(getenv(portEnvVar));
                free(portEnvVar);
                return port;
            """.trimIndent()))
            .build()
        )

        operations.add(RTOperation.builder("getHost")
            .ret(RTParameter.builder(RTString))
            .action(RTAction.builder("""
                int hostIndex = this->index;
                int hostIndexLength = hostIndex > 0 ? floor(log10(abs(hostIndex))) + 1 : 1;

                const char * hostEnvVarPrefix = "${hostEnvVarPrefix}";
                char * hostEnvVar = (char*) malloc(sizeof(char) * (strlen(hostEnvVarPrefix) + hostIndexLength + 14));
                sprintf(hostEnvVar, "%s%d_SERVICE_HOST", hostEnvVarPrefix, hostIndex);

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
                    if(${config.debug}) log.log("[%s] listening %d", this->getSlot()->name, this->port);
                    tcpServer.listen(this->port);
                    tcpServer.accept();
                }
                else {
                    this->remoteHost = this->getHost();
                    if(${config.debug}) log.log("[%s] connecting to service %s:%d", this->getSlot()->name, this->remoteHost, this->port);
                    tcp.connect(this->remoteHost, this->port);   
                }
            """.trimIndent()))
            .build()
        )

        properties = RTCapsuleProperties.builder().implementationPreface("""
            #include <math.h>
        """.trimIndent()).build()

        stateMachine = RTStateMachine.builder()
            .state(RTPseudoState.initial("init"))
            .state(RTState.builder("waitForControllerBind"))
            .state(RTState.builder("connecting"))
            .state(RTState.builder("connected"))
            .state(RTState.builder("error"))

            .transition(RTTransition.builder("init", "waitForControllerBind")
                .action("""
                    this->index = *((int*) rtdata);
                """.trimIndent())
            )

            .transition(RTTransition.builder("waitForControllerBind", "connecting")
                .trigger("relay", "rtBound")
                .action("""
                    this->onInit();
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "connected")
                .trigger("(tcp|tcpServer)", "connected")
                .action("""
                    if(${config.debug}) log.log("[%s] connected", this->getSlot()->name);
                    if($isServer) tcp.attach(sockfd);
                    relay.recall();
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "error")
                .trigger("tcp", "error")
                .action("""
                    if(${config.debug} && errno != 111)
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
                    if(${config.debug}) log.log("[%s] received message %s", this->getSlot()->name, payload);
                    ${RTExtMessage.name} rtMessage = { strdup(payload) };
                    relay.relay(rtMessage).send();
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connecting")
                .trigger("tcp", "disconnected")
                .action("""
                    if(${config.debug}) log.log("[%s] tcp disconnected", this->getSlot()->name);
                    this->onInit();
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connected")
                .trigger("relay", "relay")
                .action("""
                    if(${config.debug}) log.log("[%s] sending message @%s", this->getSlot()->name, rtMessage.payload);
                    if(!tcp.send(rtMessage.payload))
                        log.log("[%s] error sending message", this->getSlot()->name);
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "connecting")
                .trigger("relay", "relay")
                .action("""
                    msg->defer();
                """.trimIndent())
            )

            .transition(RTTransition.builder("error", "error")
                .trigger("relay", "relay")
                .action("""
                    msg->defer();
                """.trimIndent())
            )

            .build(this)
    }
}