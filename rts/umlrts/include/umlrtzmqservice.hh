// umlrtzmqservice.hh

#ifndef UMLRTZMQSERVICE_HH
#define UMLRTZMQSERVICE_HH

#include "umlrtbasicthread.hh"
#include "umlrtqueue.hh"
#include "umlrtqueueelement.hh"
#include "umlrtcommsport.hh"
#include "umlrthashmap.hh"

namespace zmqpp {
    class context;
    class socket;
}

class UMLRTZMQService : UMLRTBasicThread
{
public:
	UMLRTZMQService ( ) : UMLRTBasicThread("UMLRTZMQService")
	{
	}

    void spawn ( );

    static bool connekt ( const UMLRTCommsPort * port, const char * host, int tcpPort );
    static bool listn ( const UMLRTCommsPort * port, int tcpPort );
    static bool send ( const UMLRTCommsPort * port, const char * msg );
private:

    static const int MSG_TYPE_CONNECT = 0;
    static const int MSG_TYPE_CONNECT_ACK = 1;
    static const int MSG_TYPE_UMLRT_SIGNAL = 2;

    virtual void * run ( void * args );

    static void handleControlMessage( const UMLRTCommsPort * port, zmqpp::socket * sock, int type );

    static UMLRTHashMap * portSocketMap;
    static UMLRTHashMap * portIdMap;
    static UMLRTHashMap * slotIdMap;

    static zmqpp::socket * getSocket ( const UMLRTCommsPort * port );
    static void putSocket ( const UMLRTCommsPort * port, zmqpp::socket * socket );
    static void removeSocket ( const UMLRTCommsPort * port );

    static UMLRTHashMap * getPortSocketMap ( );
    static UMLRTHashMap * getPortIdMap ( );
	static UMLRTHashMap * getSlotIdMap ( );

    static zmqpp::context context;
};

#endif // UMLRTZMQSERVICE_HH
