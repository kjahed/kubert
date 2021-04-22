// umlrtjsoncoder.hh

#ifndef UMLRTJSONCODER_HH
#define UMLRTJSONCODER_HH

#include "document.h"
#include "writer.h"
#include "stringbuffer.h"
using namespace rapidjson;

#include <map>
#include <string>
using namespace std;

struct UMLRTSignal;
struct UMLRTMessage;
struct UMLRTCommsPort;
struct UMLRTObject_class;

class UMLRTJSONCoder
{
public:

	class Event {



	public:
		typedef enum {
		Signal, Method, ActionCode, Transition, State, Capsule, Attribute, Timer, UnknownSource
		} EventSource; 

		typedef enum {
		SendSignal, ReceiveSignal, DeferSignal, RecallSignal, CancelSignal, // Signal events
		MethodCall, MethodCallReceive, MethodStartExecute, MethodReturn, MethodFailed, MethodReturnReceived, // Method events
		ActionStart, ActionEnd, // Action code events
		TransitionStart, TransitionEnd, // Transition events
		StateEntryStart, StateEntryEnd, StateExitStart, StateExitEnd, StateIdleStart, StateIdleEnd, // State events
		CapsuleInstantiate, CapsuleFree, // Capsule events
		AttributeInstantiate, AttributeFree, AttributeChange, // Attribute events
		TimerStart, TimerSet, TimerCancel, TimerTimeout, // Timer events
		UnknownKind
		} EventKind;

		private:
		string capsuleInstance;
		string sourceName;
		string eventId;
		long seconds;
		long nanoseconds;
		EventSource eventSource;
		EventKind eventKind;
		map<string, string> params;

		public:
		Event(string capsuleInstance = "",
		string sourceName = "",
		EventSource eventSource = UnknownSource,
		EventKind eventKind = UnknownKind,
		long seconds = 0,
		long nanoseconds = 0);
		const string getCapsuleInstance() const;
		void setCapsuleInstance(const string capsuleInstance);
		const string getSourceName() const;
		void setSourceName(const string sourceName);
		const EventSource getEventSource() const;
		void setEventSource(const Event::EventSource source);
		const EventKind getEventKind() const;
		void setEventKind(const Event::EventKind kind);
		const long getSeconds() const;
		const long getNanoseconds() const;
		void setTimestamp();
		void setTimestamp(const long seconds, const long nanoseconds = 0);
		void setSeconds(const long seconds);
		void setNanoseconds(const long nanoseconds = 0);
		void setEventId(const string eventId);
		const string getEventId() const;
		void generateEventId();
		const map<string, string> getParams() const;
		const string getParam(string key) const;
		void setParams(const map<string, string> params);
		void setParam(const  string key, const string value);
		void setParam(const  string key, const int value);
		void clearParams();
		static const UMLRTObject_field fields[];
	};

    UMLRTJSONCoder ( );
    UMLRTJSONCoder ( const char * json );

	static char * fromJSON( const char * json, UMLRTSignal & signal, UMLRTSlot * slot, int * destPortIdx );
	static char * toJSON( const UMLRTMessage * msg, char ** buffer );

    void encode( const UMLRTObject_class * desc, uint8_t * data );
    void decode( const UMLRTObject_class * desc, uint8_t * data );
    void commit( char ** buffer );


private:
    Document document;
    int documentIndex;

	static char * generateMsgID ( );

	static void encodeParam( Document & document, Value & paramObj, const UMLRTObject_class * desc, void * data );
	static bool decodeParam( const Value & param, const UMLRTObject_class * desc, uint8_t * buffer );
};

#endif // UMLRTJSONCODER_HH
