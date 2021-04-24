package ca.jahed.kubert.model

import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.RTParameter
import ca.jahed.rtpoet.rtmodel.RTProtocol
import ca.jahed.rtpoet.rtmodel.RTSignal
import ca.jahed.rtpoet.rtmodel.rts.classes.RTCapsuleId
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTString

object RTControlProtocol: RTProtocol(NameUtils.randomize(RTControlProtocol::class.java.simpleName)) {
    init {
        outputSignals.add(RTSignal.builder("setState")
            .parameter(RTParameter.builder("state", RTString))
            .build())

        inputSignals.add(RTSignal.builder("messageProcessed")
            .parameter(RTParameter.builder("state", RTString))
            .build())
    }
}