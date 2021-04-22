package ca.jahed.kubert.model

import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.RTAttribute
import ca.jahed.rtpoet.rtmodel.RTClass
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTString

object RTExtMessage: RTClass(NameUtils.randomize(RTExtMessage::class.java.simpleName)) {
    init {
        attributes.add(RTAttribute.builder("payload", RTString).publicVisibility().build())
    }
}