package ca.jahed.kubert.model.capsules

import ca.jahed.kubert.KubertConfiguration
import ca.jahed.kubert.model.classes.RTExtMessage
import ca.jahed.kubert.model.protocols.RTRelayProtocol
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
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTInteger
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTString

class RTMQTTCapsule(pubTopic: String, subTopic: String, config: KubertConfiguration)
    : RTCapsule(NameUtils.randomize(RTMQTTCapsule::class.java.simpleName)) {
    private val sessionID = NameUtils.randomString(8)

    init {
        attributes.add(RTAttribute.builder("index", RTInteger).build())
        attributes.add(RTAttribute.builder("host", RTString).build())
        attributes.add(RTAttribute.builder("port", RTInteger).build())
        attributes.add(RTAttribute.builder("pubTopic", RTString).build())
        attributes.add(RTAttribute.builder("subTopic", RTString).build())

        ports.add(RTPort.builder("relay", RTRelayProtocol).sap().conjugate().notification().build())
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
            .state(RTState.builder("waitForControllerBind"))
            .state(RTState.builder("connecting"))
            .state(RTState.builder("connected"))
            .state(RTState.builder("error").entry("""
                if(${config.debug}) log.log("[%s] connection error", this->getSlot()->name);
                timer.informIn(UMLRTTimespec(1,0));
            """.trimIndent()))

            .transition(RTTransition.builder("init", "waitForControllerBind")
                .action("""
                    this->index = *((int*) rtdata);
                """.trimIndent())
            )

            .transition(RTTransition.builder("waitForControllerBind", "connecting")
                .trigger("relay", "rtBound")
                .action("""
                    this->host = "mqtt.${config.namespace}.svc.cluster.local";
                    this->port = 1883;
                    this->pubTopic = this->getPubTopic();
                    this->subTopic = this->getSubTopic();
                    if(${config.debug}) log.log("[%s] pubtopic: %s", this->getSlot()->name, this->pubTopic);
                    if(${config.debug}) log.log("[%s] subtopic: %s", this->getSlot()->name, this->subTopic);
                    mqtt.connect(this->host, this->port, NULL, NULL, "$sessionID");
                """.trimIndent())
            )

            .transition(RTTransition.builder("connecting", "connected")
                .trigger("mqtt", "connected")
                .action("""
                    mqtt.subscribe(this->subTopic);
                    relay.recallAll();
                """.trimIndent())
            )

            .transition(RTTransition.builder("error", "connecting")
                .trigger("timer", "timeout")
                .action("""
                    mqtt.connect(this->host, this->port, NULL, NULL, "$sessionID");
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connected")
                .trigger("mqtt", "received")
                .action("""
                    if(${config.debug}) log.log("[%s] got message %s", this->getSlot()->name, payload);
                    ${RTExtMessage.name} rtMessage = { strdup(payload) };
                    relay.relay(rtMessage).send();
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "connected")
                .trigger("relay", "relay")
                .action("""
                    if(${config.debug}) log.log("[%s] sending message @%s", this->getSlot()->name, rtMessage.payload);
                    mqtt.publish(this->pubTopic, rtMessage.payload);
                """.trimIndent())
            )

            .transition(RTTransition.builder("connected", "error")
                .trigger("mqtt", "disconnected")
            )

            .transition(RTTransition.builder("connected", "error")
                .trigger("mqtt", "error")
            )

            .transition(RTTransition.builder("connecting", "error")
                .trigger("mqtt", "error")
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