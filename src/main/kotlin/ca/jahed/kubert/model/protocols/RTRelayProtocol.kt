package ca.jahed.kubert.model.protocols

import ca.jahed.kubert.model.classes.RTExtMessage
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.RTParameter
import ca.jahed.rtpoet.rtmodel.RTProtocol
import ca.jahed.rtpoet.rtmodel.RTSignal

object RTRelayProtocol: RTProtocol(NameUtils.randomize(RTRelayProtocol::class.java.simpleName)) {
    init {
        inOutSignals.add(RTSignal.builder("relay")
            .parameter(RTParameter.builder("rtMessage", RTExtMessage))
            .build())
    }
}