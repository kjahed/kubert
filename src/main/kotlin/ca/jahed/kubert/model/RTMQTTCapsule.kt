package ca.jahed.kubert.model

import ca.jahed.kubert.Kubert
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.papyrusrt.rts.protocols.RTMQTTProtocol
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.cppproperties.RTCapsuleProperties
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTLogProtocol
import ca.jahed.rtpoet.rtmodel.rts.protocols.RTTimingProtocol
import ca.jahed.rtpoet.rtmodel.sm.RTPseudoState
import ca.jahed.rtpoet.rtmodel.sm.RTState
import ca.jahed.rtpoet.rtmodel.sm.RTStateMachine
import ca.jahed.rtpoet.rtmodel.sm.RTTransition
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTInt
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTInteger
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTString

class RTMQTTCapsule(pubTopic: String, subTopic: String)
    : RTCapsule(NameUtils.randomize(RTMQTTCapsule::class.java.simpleName)) {

    init {
        attributes.add(RTAttribute.builder("index", RTInteger).build())
        attributes.add(RTAttribute.builder("host", RTString).build())
        attributes.add(RTAttribute.builder("port", RTInteger).build())
        attributes.add(RTAttribute.builder("pubTopic", RTString).build())
        attributes.add(RTAttribute.builder("subTopic", RTString).build())

        ports.add(RTPort.builder("relay", RTRelayProtocol).external().conjugate().build())
        ports.add(RTPort.builder("mqtt", RTMQTTProtocol).internal().build())
        ports.add(RTPort.builder("timer", RTTimingProtocol).internal().build())
        ports.add(RTPort.builder("log", RTLogProtocol).internal().build())

        operations.add(RTOperation.builder("getSubTopic")
            .ret(RTParameter.builder(RTString))
            .action(RTAction.builder("""
                int index = this->index;
                int indexLength = index > 0 ? floor(log10(abs(index))) + 1 : 1;

                const char * subTopicTemplate = "${subTopic}";
                char * topic = (char*) malloc(sizeof(char) * (strlen(subTopicTemplate) + indexLength + 1));
                sprintf(topic, "${subTopic}", index);
                return topic;
            """.trimIndent()))
            .build()
        )


        operations.add(RTOperation.builder("getPubTopic")
            .ret(RTParameter.builder(RTString))
            .action(RTAction.builder("""
                int index = this->index;
                int indexLength = index > 0 ? floor(log10(abs(index))) + 1 : 1;

                const char * pubTopicTemplate = "${pubTopic}";
                char * topic = (char*) malloc(sizeof(char) * (strlen(pubTopicTemplate) + indexLength + 1));
                sprintf(topic, "${pubTopic}", index);
                return topic;
            """.trimIndent()))
            .build()
        )


        properties = RTCapsuleProperties.builder().implementationPreface("""
            #include <math.h>
        """.trimIndent()).build()

        stateMachine = RTStateMachine.builder()
            .state(RTPseudoState.initial("init"))
            .state(RTState.builder("connecting"))
            .state(RTState.builder("handshaking"))
            .state(RTState.builder("acknowledging"))
            .state(RTState.builder("connected"))
            .state(RTState.builder("error"))

            .transition(RTTransition.builder("init", "connecting")
                .action("""
                    this->index = *((int*) rtdata);
                    this->host = "mqtt.${Kubert.namespace}.svc.cluster.local";
                    this->port = 1883;
                    this->pubTopic = this->getPubTopic();
                    this->subTopic = this->getSubTopic();
                    if(${Kubert.debug}) log.log("[%s] pubtopic: %s", this->getSlot()->name, this->pubTopic);
                    if(${Kubert.debug}) log.log("[%s] subtopic: %s", this->getSlot()->name, this->subTopic);
                    mqtt.connect(this->host, this->port);
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "handshaking")
                .trigger("mqtt", "connected")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] connected to broker", this->getSlot()->name);
                    mqtt.subscribe(this->subTopic);
                    mqtt.publish(this->pubTopic, "syn");
                """.trimIndent())
            )

            .transition(RTTransition.builder("handshaking", "acknowledging")
                .trigger("mqtt", "received")
                .guard("""
                    return strcmp(payload, "syn") == 0;
                """.trimIndent())
                .action("""
                    mqtt.publish(this->pubTopic, "ack");
                    if(${Kubert.debug}) log.log("[%s] got syn", this->getSlot()->name);
                """.trimIndent())
            )

            .transition(RTTransition.builder("acknowledging", "connected")
                .trigger("mqtt", "received")
                .guard("""
                    return strcmp(payload, "ack") == 0;
                """.trimIndent())
                .action("""
                    relay.recall();
                    if(${Kubert.debug}) log.log("[%s] handshake complete", this->getSlot()->name);
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "error")
                .trigger("mqtt", "error")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] connection error: %d", this->getSlot()->name, errno);
                    timer.informIn(UMLRTTimespec(1,0));
                """.trimIndent())
            )

            .transition(RTTransition.builder("error", "connecting")
                .trigger("timer", "timeout")
                .action("""
                    mqtt.connect(this->host, this->port);
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connected")
                .trigger("mqtt", "received")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] got message %s", this->getSlot()->name, payload);
                    ${RTExtMessage.name} rtMessage = { strdup(payload) };
                    relay.relay(rtMessage).send();
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connecting")
                .trigger("mqtt", "disconnected")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] disconnected", this->getSlot()->name);
                    mqtt.connect(this->host, this->port);
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connected")
                .trigger("^(?!mqtt|timer|log).*$", "*")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] sending message @%s", this->getSlot()->name, rtMessage.payload);
                    mqtt.publish(this->pubTopic, rtMessage.payload);
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "connecting")
                .trigger("^(?!mqtt|timer|log).*$", "*")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] deferring signal %s on port %s", this->getSlot()->name, msg->signal.getName(), msg->signal.getSrcPort()->getName());
                    msg->defer();
                """.trimIndent())
            )

            .transition(RTTransition.builder("handshaking", "handshaking")
                .trigger("^(?!mqtt|timer|log).*$", "*")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] deferring signal %s on port %s", this->getSlot()->name, msg->signal.getName(), msg->signal.getSrcPort()->getName());
                    msg->defer();
                """.trimIndent())
            )

            .transition(RTTransition.builder("acknowledging", "acknowledging")
                .trigger("^(?!mqtt|timer|log).*$", "*")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] deferring signal %s on port %s", this->getSlot()->name, msg->signal.getName(), msg->signal.getSrcPort()->getName());
                    msg->defer();
                """.trimIndent())
            )

            .transition(RTTransition.builder("error", "error")
                .trigger("^(?!mqtt|timer|log).*$", "*")
                .action("""
                    if(${Kubert.debug}) log.log("[%s] deferring signal %s on port %s", this->getSlot()->name, msg->signal.getName(), msg->signal.getSrcPort()->getName());
                    msg->defer();
                """.trimIndent())
            )

            .build(this)
    }
}