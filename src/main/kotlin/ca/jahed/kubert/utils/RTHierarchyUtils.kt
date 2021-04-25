package ca.jahed.kubert.utils

import ca.jahed.kubert.model.RTSlot
import ca.jahed.rtpoet.rtmodel.RTModel
import ca.jahed.rtpoet.rtmodel.RTPort

object RTHierarchyUtils {
    fun buildHierarchy(model: RTModel): RTSlot {
        val topSlot = RTSlot(model.top, 0)
        val slots: List<RTSlot> = buildHierarchy(topSlot)
        calculatePositions(topSlot)
        findNeighbors(slots)
        return topSlot
    }

    private fun buildHierarchy(slot: RTSlot): List<RTSlot> {
        val slots = mutableListOf(slot)
        for (childPart in slot.part.capsule.parts) {
            for (i in 0 until childPart.replication) {
                val childSlot = RTSlot(childPart, i, slot)
                slot.children.add(childSlot)
                slots.addAll(buildHierarchy(childSlot))
            }
        }

        for (i in 0 until slot.children.size) {
            for (j in i + 1 until slot.children.size) {
                slot.children[i].siblings.add(slot.children[j])
                slot.children[j].siblings.add(slot.children[i])
            }
        }
        return slots
    }

    private fun calculatePositions(slot: RTSlot, position: Int = 0) {
        slot.position = position
        for(i in 0 until slot.children.size)
            calculatePositions(slot.children[i], position * slot.children.size + position + i + 1)
    }

    private fun findNeighbors(slots: List<RTSlot>) {
        findWiredNeighbors(slots)
        findNoneWiredNeighbors(slots)

        for (slot in slots) {
            for (connections in slot.connections.values) {
                for (connection in connections) {
                    slot.neighbors.add(connection.second)
                }
            }
        }
    }

    private fun findWiredNeighbors(slots: List<RTSlot>) {
        for (slot in slots) {
            for (connector in slot.part.capsule.connectors) {

                if (connector.end1.part == null && connector.end2.part == null) {
                    slot.addConnection(connector.end1.port, connector.end2.port, slot)
                } else if (connector.end1.part == null) {
                    slot.children.filter { s -> s.part == connector.end2.part }.forEach {
                        slot.addConnection(connector.end1.port, connector.end2.port, it)
                        it.addConnection(connector.end2.port, connector.end1.port, slot)
                    }
                } else if (connector.end2.part == null) {
                    slot.children.filter { s -> s.part == connector.end1.part }.forEach {
                        slot.addConnection(connector.end2.port, connector.end1.port, it)
                        it.addConnection(connector.end1.port, connector.end2.port, slot)
                    }
                } else {
                    slot.children
                        .filter { s1 -> s1.part == connector.end1.part }
                        .forEach { s1 ->
                            slot.children
                                .filter { s2 -> s2.part == connector.end2.part }
                                .forEach { s2 ->
                                    s1.addConnection(connector.end1.port, connector.end2.port, s2)
                                    s2.addConnection(connector.end2.port, connector.end1.port, s1)
                                }
                        }
                }
            }
        }

        resolveRelays(slots)
    }

    private fun resolveRelays(slots: List<RTSlot>) {
        for (slot in slots) {
            for (port in slot.connections.keys) {
                if (port.isRelay()) continue

                val resolvedConnections = mutableSetOf<Pair<RTPort, RTSlot>>()
                for (farEnd in slot.connections[port]!!) {
                    val visited = mutableSetOf(Pair(port, slot))
                    resolvedConnections.add(resolveFarEnd(farEnd, visited))
                }

                slot.connections[port] = resolvedConnections
            }
        }
        for (slot in slots) {
            slot.connections.keys.filter(RTPort::isRelay)
                .map { port -> slot.connections.remove(port) }
        }
    }

    private fun resolveFarEnd(
        farEnd: Pair<RTPort, RTSlot>,
        visited: MutableSet<Pair<RTPort, RTSlot>>,
    ): Pair<RTPort, RTSlot> {
        visited.add(farEnd)
        val port: RTPort = farEnd.first
        val slot: RTSlot = farEnd.second
        if (!port.isRelay()) return farEnd

        val entries: Set<Pair<RTPort, RTSlot>> = slot.connections[port]!!
            .filter { pair -> !visited.contains(pair) }.toSet()
        assert(entries.size == 1)

        return resolveFarEnd(entries.iterator().next(), visited)
    }

    private fun findNoneWiredNeighbors(slots: List<RTSlot>) {
        for (i in slots.indices) {
            for (p1 in slots[i].part.capsule.ports) {
                if (p1.wired || !p1.behaviour) continue

                for (j in i + 1 until slots.size) {
                    for (p2 in slots[j].part.capsule.ports) {
                        if (p2.wired || !p2.behaviour) continue

                        if (p1.service != p2.service
                            && p1.conjugated != p2.conjugated
                            && p1.protocol == p2.protocol
                            && (p1.name == p2.name || p1.registrationOverride == p2.registrationOverride)
                        ) {
                            slots[i].addConnection(p1, p2, slots[j])
                            slots[j].addConnection(p2, p1, slots[i])
                        }
                    }
                }
            }
        }
    }
}