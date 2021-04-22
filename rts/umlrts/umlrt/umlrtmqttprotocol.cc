// umlrtmqttprotocol.cc

#include "umlrtmqttprotocol.hh"

UMLRTObject_field UMLRTMQTTProtocol::fields_received[] =
{
	{
		"topic",
		&UMLRTType_charptr,
		offsetof( params_received, topic ),
		1,
		0
	},
    {
        "payload",
        &UMLRTType_charptr,
        offsetof( params_received, payload ),
        1,
        0
    },
    {
        "length",
        &UMLRTType_int,
        offsetof( params_received, length ),
        1,
        0
    }
};

UMLRTObject UMLRTMQTTProtocol::payload_received =
{
    sizeof( params_received ),
    3,
    fields_received
};

UMLRTObject_field UMLRTMQTTProtocol::fields_error[] =
{
    {
        "errno",
        &UMLRTType_int,
        offsetof( params_error, errno ),
        1,
        0
    }
};

UMLRTObject UMLRTMQTTProtocol::payload_error =
{
    sizeof( params_error ),
    1,
	fields_error
};

UMLRTObject_field UMLRTMQTTProtocol::fields_connected[] =
{
    #ifdef NEED_NON_FLEXIBLE_ARRAY
    {
        0,
        0,
        0,
        0,
        0
    }
    #endif
};

UMLRTObject UMLRTMQTTProtocol::payload_connected =
{
    0,
    #ifdef NEED_NON_FLEXIBLE_ARRAY
    1
    #else
    0
    #endif
    ,
    fields_connected
};

UMLRTObject_field UMLRTMQTTProtocol::fields_disconnected[] =
{
    #ifdef NEED_NON_FLEXIBLE_ARRAY
    {
        0,
        0,
        0,
        0,
        0
    }
    #endif
};

UMLRTObject UMLRTMQTTProtocol::payload_disconnected =
{
    0,
    #ifdef NEED_NON_FLEXIBLE_ARRAY
    1
    #else
    0
    #endif
    ,
    fields_disconnected
};
