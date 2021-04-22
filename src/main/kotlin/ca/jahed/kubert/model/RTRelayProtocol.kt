package ca.jahed.kubert.model

import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.papyrusrt.rts.protocols.RTBaseCommProtocol
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