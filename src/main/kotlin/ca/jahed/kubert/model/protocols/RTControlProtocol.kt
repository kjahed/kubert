package ca.jahed.kubert.model.protocols

import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.RTCapsule
import ca.jahed.rtpoet.rtmodel.RTParameter
import ca.jahed.rtpoet.rtmodel.RTProtocol
import ca.jahed.rtpoet.rtmodel.RTSignal
import ca.jahed.rtpoet.rtmodel.rts.classes.RTSystemClass
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTString

class RTControlProtocol(mainCapsule: RTCapsule): RTProtocol(NameUtils.randomize(RTControlProtocol::class.java.simpleName)) {
    val smStateParameterName = NameUtils.randomize("_currentState")

    init {
        val restoreStateSignalBuilder = RTSignal.builder("restoreState")
            .parameter(RTParameter.builder(smStateParameterName, RTString))

        val saveStateSignalBuilder = RTSignal.builder("saveState")
            .parameter(RTParameter.builder(smStateParameterName, RTString))

        mainCapsule.attributes.filter { it.type !is RTSystemClass }.forEach {
            restoreStateSignalBuilder.parameter(RTParameter.builder("_${it.name}", it.type))
            saveStateSignalBuilder.parameter(RTParameter.builder("_${it.name}", it.type))
        }

        outputSignals.add(restoreStateSignalBuilder.build())
        inputSignals.add(saveStateSignalBuilder.build())

        outputSignals.add(RTSignal.builder("initialState").build())
    }
}