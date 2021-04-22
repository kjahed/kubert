// umlrtmqttservice.cc

#include "basedebug.hh"
#include "basefatal.hh"
#include "umlrtoutsignal.hh"
#include "umlrtmqttservice.hh"
#include "umlrtmqttprotocol.hh"
#include "umlrtcontroller.hh"

#include <math.h>
#include <unistd.h>

#include "MQTTClient.h"

UMLRTHashMap * UMLRTMQTTService::portClientMap = NULL;
UMLRTHashMap * UMLRTMQTTService::portIdMap = NULL;
UMLRTHashMap * UMLRTMQTTService::slotIdMap = NULL;

UMLRTQueue UMLRTMQTTService::inQueue;

int UMLRTMQTTService::messageReceived ( void * context, char * topicName, int topicLen, MQTTClient_message * message )
{
	const UMLRTCommsPort* port = (const UMLRTCommsPort*)context;
	UMLRTMQTTService::Message* msg = new UMLRTMQTTService::Message;

    msg->port = port;
    msg->payload = strndup((const char*)message->payload, message->payloadlen);
    msg->length = message->payloadlen;
    msg->topic = strdup(topicName);

    inQueue.enqueue(msg);
	MQTTClient_free(topicName);
	MQTTClient_freeMessage(&message);
    return 1;
}

char * UMLRTMQTTService::generateClientID ( const int len ) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    srand((time_t)ts.tv_nsec);

	char* s = (char*) malloc(sizeof(char) * len);
    if(s == NULL)
        return NULL;

    static const char alphanum[] =
        "0123456789"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        "abcdefghijklmnopqrstuvwxyz";

    for (int i = 0; i < len; ++i) {
        s[i] = alphanum[rand() % (sizeof(alphanum) - 1)];
    }

    s[len] = 0;
	return s;
}

UMLRTHashMap * UMLRTMQTTService::getPortClientMap ( )
{
    if (portClientMap == NULL)
    {
    	portClientMap = new UMLRTHashMap("portClientMap", UMLRTHashMap::compareString, false/*objectIsString*/);
    }
    return portClientMap;
}

UMLRTHashMap * UMLRTMQTTService::getPortIdMap ( )
{
    if (portIdMap == NULL)
    {
    	portIdMap = new UMLRTHashMap("portIdMap", UMLRTHashMap::compareString, false/*objectIsString*/);
    }
    return portIdMap;
}

UMLRTHashMap * UMLRTMQTTService::getSlotIdMap ( )
{
    if (slotIdMap == NULL)
    {
    	slotIdMap = new UMLRTHashMap("slotIdMap", UMLRTHashMap::compareString, false/*objectIsString*/);
    }
    return slotIdMap;
}

MQTTClient * UMLRTMQTTService::getClient ( const UMLRTCommsPort * port )
{
	char* id = new char[strlen(port->slotName()) + strlen(port->getName()) + 2];
	strcpy(id, port->slotName());
	strcat(id, ":");
	strcat(id, port->getName());

	MQTTClient* client = (MQTTClient*) getPortClientMap()->getObject(id);
	delete id;
    return client;
}

void UMLRTMQTTService::putClient ( const UMLRTCommsPort * port, MQTTClient * client )
{
	char * id = new char[strlen(port->slotName()) + strlen(port->getName()) + 2];
	strcpy(id, port->slotName());
	strcat(id, ":");
	strcat(id, port->getName());

	getPortClientMap()->insert(id, (void *)client);
	getPortIdMap()->insert(id, (void *)port);
	getSlotIdMap()->insert(id, (void *)port->slot);
}

void UMLRTMQTTService::removeClient ( const UMLRTCommsPort * port )
{
	char * id = new char[strlen(port->slotName()) + strlen(port->getName()) + 2];
	strcpy(id, port->slotName());
	strcat(id, ":");
	strcat(id, port->getName());

	getPortClientMap()->remove(id);
	getPortIdMap()->remove(id);
	getSlotIdMap()->remove(id);
	delete id;
}


void UMLRTMQTTService::connect ( const UMLRTCommsPort * port, const char * mqttHost,
		int mqttPort, const char * username, const char * password, const char * id )
{
	MQTTClient * client = getClient(port);
	if(client == NULL) {
		int addressSize = strlen(mqttHost) + floor(log10(abs(mqttPort))) + 9;
	    char* address = new char[addressSize]; // TODO: free address
	    sprintf(address, "tcp://%s:%d", mqttHost, mqttPort);

		if(id == NULL)
			id = generateClientID(16);
		
	    client = new MQTTClient;
	    MQTTClient_create(client, address, id, MQTTCLIENT_PERSISTENCE_DEFAULT, NULL);
		MQTTClient_setCallbacks(*client, (void*)port, NULL, messageReceived, NULL);
	    putClient(port, client);
	}

	MQTTClient_connectOptions connOpts = MQTTClient_connectOptions_initializer;
	connOpts.keepAliveInterval = 20;
	connOpts.cleansession = 1;

	if(username != NULL && password != NULL) {
		connOpts.username = username;
		connOpts.password = password;
	}

    UMLRTOutSignal signal;
	int ret = MQTTClient_connect(*client, &connOpts);
	if( ret != MQTTCLIENT_SUCCESS )
		signal.initialize( "error", UMLRTMQTTProtocol::signal_error, port, &UMLRTMQTTProtocol::payload_error, &ret);
	else
		signal.initialize( "connected", UMLRTMQTTProtocol::signal_connected, port, &UMLRTMQTTProtocol::payload_connected);
	port->slot->controller->deliver( port, signal, port->roleIndex );
}

void UMLRTMQTTService::disconnect ( const UMLRTCommsPort * port )
{
	MQTTClient* client = getClient(port);
	if( client != NULL ) {
		UMLRTOutSignal signal;
		int ret = MQTTClient_disconnect(*client, 1000);
		if( ret != MQTTCLIENT_SUCCESS ) {
			signal.initialize( "error", UMLRTMQTTProtocol::signal_error, port, &UMLRTMQTTProtocol::payload_error, &ret);
		} else {
			MQTTClient_destroy(client);
			removeClient(port);
			signal.initialize( "disconnected", UMLRTMQTTProtocol::signal_disconnected, port, &UMLRTMQTTProtocol::payload_disconnected);
		}
		port->slot->controller->deliver( port, signal, port->roleIndex );
	}
}

void UMLRTMQTTService::subscribe ( const UMLRTCommsPort * port, const char * topic )
{
	MQTTClient* client = getClient(port);
	if(client != NULL ) {
		int ret = MQTTClient_subscribe(*client, topic, 0);
		if( ret != MQTTCLIENT_SUCCESS ) {
			UMLRTOutSignal signal;
			signal.initialize( "error", UMLRTMQTTProtocol::signal_error, port, &UMLRTMQTTProtocol::payload_error, &ret);
			port->slot->controller->deliver( port, signal, port->roleIndex );
		}
	} else {
		FATAL("MQTT client not found for port %s", port->getName());
	}
}

void UMLRTMQTTService::publish ( const UMLRTCommsPort * port, const char * topic , char * payload, int length )
{
	MQTTClient* client = getClient(port);
	if( client != NULL ) {
		int ret = MQTTClient_publish(*client, topic, length, payload, 2, 1, NULL);
		if( ret != MQTTCLIENT_SUCCESS ) {
			UMLRTOutSignal signal;
			signal.initialize( "error", UMLRTMQTTProtocol::signal_error, port, &UMLRTMQTTProtocol::payload_error, &ret);
			port->slot->controller->deliver( port, signal, port->roleIndex );
		}
	} else {
		FATAL("MQTT client not found for port %s", port->getName());
	}
}

void UMLRTMQTTService::publish ( const UMLRTCommsPort * port, const char * topic , const char * msg )
{
	publish(port, topic, (char*)msg, strlen(msg));
}

void UMLRTMQTTService::spawn ( )
{
    start(NULL);
}

void * UMLRTMQTTService::run ( void * args )
{
    while(1) {
    	while(inQueue.count() > 0) {
			UMLRTMQTTService::Message * msg = (UMLRTMQTTService::Message*)inQueue.dequeue();
			UMLRTOutSignal signal;
			signal.initialize( "received", UMLRTMQTTProtocol::signal_received, msg->port,
					&UMLRTMQTTProtocol::payload_received, &msg->topic, &msg->payload, &msg->length);
			msg->port->slot->controller->deliver( msg->port, signal, msg->port->roleIndex );
			free(msg->topic);
			free(msg->payload);
			delete msg;
		}

    	usleep(100000);
    }
}
