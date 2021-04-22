// umlrtmqttprotocol.hh

#ifndef UMLRTMQTTPROTOCOL_HH
#define UMLRTMQTTPROTOCOL_HH

#include "umlrtprotocol.hh"
#include "umlrtsignal.hh"
#include "umlrtobjectclass.hh"
#include "umlrtmqttservice.hh"

struct UMLRTCommsPort;

class UMLRTMQTTProtocol
{
public:
    enum SignalId { signal_received = UMLRTSignal::FIRST_PROTOCOL_SIGNAL_ID,
    	signal_connected,
		signal_disconnected,
		signal_error };

    struct params_received
    {
        char * topic;
        char * payload;
        int length;
    };

    static UMLRTObject_field fields_received[];
    static UMLRTObject payload_received;

    struct params_error
    {
        int errno;
    };

    static UMLRTObject_field fields_error[];
    static UMLRTObject payload_error;

    static UMLRTObject_field fields_connected[];
    static UMLRTObject payload_connected;

    static UMLRTObject_field fields_disconnected[];
    static UMLRTObject payload_disconnected;

    class InSignals {  };
    class OutSignals {
    public:
        
        const void connect( const char * host, int port ) const;
        const void connect( const char * host, int port, const char * username, const char * password, const char * id ) const;
        const void disconnect( ) const;
        const void subscribe( const char * topic );
        const void publish ( const char * topic, char * payload, int length );
        const void publish ( const char * topic, const char * msg );

    };

    typedef OutSignals Base;
    typedef InSignals Conjugate;
};

class UMLRTMQTTProtocol_baserole : protected UMLRTProtocol, private UMLRTMQTTProtocol::Base
{
public:
	UMLRTMQTTProtocol_baserole( const UMLRTCommsPort *& srcPort ) : UMLRTProtocol( srcPort ) { }

    const void connect( const char * host, int port ) const
    {
    	UMLRTMQTTService::connect(srcPort, host, port);
    }

    const void connect( const char * host, int port, const char * username, const char * password, const char * id ) const
    {
    	UMLRTMQTTService::connect(srcPort, host, port, username, password, id);
    }

    const void disconnect( ) const
    {
    	UMLRTMQTTService::disconnect(srcPort);
    }

    const void subscribe( const char * topic )
    {
    	UMLRTMQTTService::subscribe(srcPort, topic);
    }

    const void publish ( const char * topic, char * payload, int length )
    {
    	UMLRTMQTTService::publish(srcPort, topic, payload, length);
    }

    const void publish ( const char * topic, const char * msg )
    {
    	UMLRTMQTTService::publish(srcPort, topic, msg);
    }
};

#endif // UMLRTMQTTPROTOCOL_HH
