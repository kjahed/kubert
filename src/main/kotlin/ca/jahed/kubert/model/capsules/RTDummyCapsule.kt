package ca.jahed.kubert.model.capsules

import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.*

object RTDummyCapsule : RTCapsule(NameUtils.randomize(RTDummyCapsule::class.java.simpleName))