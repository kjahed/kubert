// umlrtzmqservice.cc

#include "basedebug.hh"
#include "basefatal.hh"
#include "umlrtoutsignal.hh"
#include "umlrtzmqservice.hh"
#include "umlrttcpprotocol.hh"
#include "umlrtcontroller.hh"

#include <zmqpp/zmqpp.hpp>
#include <string>
#include <iostream>
#include <unistd.h>

using namespace std;

UMLRTHashMap * UMLRTZMQService::portSocketMap = NULL;
UMLRTHashMap * UMLRTZMQService::portIdMap = NULL;
UMLRTHashMap * UMLRTZMQService::slotIdMap = NULL;
zmqpp::context UMLRTZMQService::context;

UMLRTHashMap * UMLRTZMQService::getPortSocketMap ( )
{
    if (portSocketMap == NULL)
    {
    	portSocketMap = new UMLRTHashMap("portSocketMap", UMLRTHashMap::compareString, false/*objectIsString*/);
    }
    return portSocketMap;
}

UMLRTHashMap * UMLRTZMQService::getPortIdMap ( )
{
    if (portIdMap == NULL)
    {
    	portIdMap = new UMLRTHashMap("portIdMap", UMLRTHashMap::compareString, false/*objectIsString*/);
    }
    return portIdMap;
}

UMLRTHashMap * UMLRTZMQService::getSlotIdMap ( )
{
    if (slotIdMap == NULL)
    {
    	slotIdMap = new UMLRTHashMap("slotIdMap", UMLRTHashMap::compareString, false/*objectIsString*/);
    }
    return slotIdMap;
}

zmqpp::socket * UMLRTZMQService::getSocket ( const UMLRTCommsPort * port )
{
	char * id = new char[strlen(port->slotName()) + strlen(port->getName()) + 2];
	strcpy(id, port->slotName());
	strcat(id, ":");
	strcat(id, port->getName());

	UMLRTHashMap * portSocketMap = getPortSocketMap();
	zmqpp::socket * socket = (zmqpp::socket *) portSocketMap->getObject(id);
	delete id;

	return socket;
}

void UMLRTZMQService::putSocket ( const UMLRTCommsPort * port, zmqpp::socket * socket )
{
	char * id = new char[strlen(port->slotName()) + strlen(port->getName()) + 2];
	strcpy(id, port->slotName());
	strcat(id, ":");
	strcat(id, port->getName());

	getPortSocketMap()->insert(id, (void *)socket);
	getPortIdMap()->insert(id, (void *)port);
	getSlotIdMap()->insert(id, (void *)port->slot);
}

void UMLRTZMQService::removeSocket ( const UMLRTCommsPort * port )
{
	char * id = new char[strlen(port->slotName()) + strlen(port->getName()) + 2];
	strcpy(id, port->slotName());
	strcat(id, ":");
	strcat(id, port->getName());

	getPortSocketMap()->remove(id);
	getPortIdMap()->remove(id);
	getSlotIdMap()->remove(id);
	delete id;
}


bool UMLRTZMQService::connekt ( const UMLRTCommsPort * port, const char* host, int tcpPort )
{
    zmqpp::socket * sock = getSocket(port);
	if(sock != NULL)
		return false;

    string address("tcp://");
    address.append(host);
    address.append(":");
    address.append(to_string(tcpPort));

	sock = new zmqpp::socket(context, zmqpp::socket_type::pair);
    sock->connect(address);
	putSocket(port, sock);

    zmqpp::message message;
    message << MSG_TYPE_CONNECT;
    sock->send(message);
	return true;
}

bool UMLRTZMQService::listn ( const UMLRTCommsPort * port, int tcpPort )
{
    zmqpp::socket * sock = getSocket(port);
	if(sock != NULL)
		return false;

    string address("tcp://*:");
    address.append(to_string(tcpPort));

	sock = new zmqpp::socket(context, zmqpp::socket_type::pair);
    sock->bind(address);
    putSocket(port, sock);
}

bool UMLRTZMQService::send ( const UMLRTCommsPort * port, const char* msg )
{
    zmqpp::socket * sock = getSocket(port);
    if(sock == NULL)
        return false;

    zmqpp::message message;
    message << MSG_TYPE_UMLRT_SIGNAL;
    message << msg;
    sock->send(message);
    return true;
}

void UMLRTZMQService::handleControlMessage( const UMLRTCommsPort * port, zmqpp::socket * sock, int type)
{
    switch(type) {
        case MSG_TYPE_CONNECT: {
            zmqpp::message reply;
            reply << MSG_TYPE_CONNECT_ACK;
            sock->send(reply);
            // let it fall to the next case
        }
        case MSG_TYPE_CONNECT_ACK: {
            UMLRTOutSignal signal;
            signal.initialize( "connected", UMLRTTCPProtocol::signal_connected, port,
                &UMLRTTCPProtocol::payload_connected, (void *)new int(0));
            if(port->slot->capsule != NULL)
                port->slot->controller->deliver( port, signal, port->roleIndex );
            break;
        }
        default:
            FATAL("Unknown control message: %d", type);
    }
}

void UMLRTZMQService::spawn ( )
{
    start(NULL);
}

// Main loop
void * UMLRTZMQService::run ( void * args )
{
    while(1)
    {
        getSlotIdMap()->lock();
        UMLRTHashMap::Iterator clIter = getSlotIdMap()->getIterator();

        while (clIter != clIter.end())
        {
            char * id  = (char *) clIter.getKey();
            UMLRTSlot * slot = (UMLRTSlot *) clIter.getObject();
            if(slot->capsule == NULL) {
                getPortSocketMap()->remove(id);
                getPortIdMap()->remove(id);
                //getSlotIdMap()->remove(id);
            }

            const UMLRTCommsPort * port = (const UMLRTCommsPort *) getPortIdMap()->getObject(id);

            if(port != NULL) {
                zmqpp::socket * socket = getSocket(port);
                if(socket == NULL)
                    FATAL("TCP socket not found for port %s", port->getName());

                zmqpp::message message;
                if(socket->receive(message, true)) {
                    int msgType;
                    message >> msgType;
                    if(msgType == MSG_TYPE_UMLRT_SIGNAL) {
                        string text;
                        message >> text;

                        char * payload = strdup(text.c_str());
                        int length = strlen(payload);

                        UMLRTOutSignal signal;
                        signal.initialize( "received", UMLRTTCPProtocol::signal_received, port,
                            &UMLRTTCPProtocol::payload_received, &payload, &length);
                        delete payload;

                        if(port->slot->capsule != NULL)
                            port->slot->controller->deliver( port, signal, port->roleIndex );
                    } else {
                        handleControlMessage(port, socket, msgType);
                    }
                }
            }

            clIter = clIter.next();
        }

        getSlotIdMap()->unlock();

        usleep(100000);
    }
}