package ca.jahed.kubert.model

import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.RTCapsulePart
import ca.jahed.rtpoet.rtmodel.RTElement
import ca.jahed.rtpoet.rtmodel.RTPort

class RTSlot(val part: RTCapsulePart, val index: Int, val parent: RTSlot? = null):
    RTElement(
        if(parent != null) parent.name + "." + part.name + "[" + index + "]"
        else part.name + "[" + index + "]"
    ) {

    val children = mutableListOf<RTSlot>()
    val siblings = mutableListOf<RTSlot>()
    val neighbors = mutableListOf<RTSlot>()
    val connections = mutableMapOf<RTPort, MutableSet<Pair<RTPort, RTSlot>>>()

    val k8sName = NameUtils.toLegalK8SName(name)
    val servicePorts = mutableListOf<Pair<String, Int>>()
    var position = 0

    fun addConnection(srcPort: RTPort, destPort: RTPort, destSlot: RTSlot) {
        connections.getOrPut(srcPort, { mutableSetOf() }).add(Pair(destPort, destSlot))
    }

    fun getConnectionsWith(destSlot: RTSlot): Set<Pair<RTPort, RTSlot>> {
        val found = mutableSetOf<Pair<RTPort, RTSlot>>()
        for (connections in connections.values)
            for (connection in connections)
                if (connection.second === destSlot) found.add(connection)
        return found
    }
}