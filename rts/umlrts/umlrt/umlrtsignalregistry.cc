// umlrtsignalregistry.cc

#include "basedebug.hh"
#include "basefatal.hh"
#include "umlrtsignalregistry.hh"

UMLRTSignalRegistry::~UMLRTSignalRegistry ( ) 
{
	getOutSignalMap()->lock();

	UMLRTHashMap::Iterator iter1 = getOutSignalMap()->getIterator();
	while (iter1 != iter1.end())
    {
    	UMLRTHashMap* signalsMap = (UMLRTHashMap*) iter1.getObject();

    	signalsMap->lock();

    	UMLRTHashMap::Iterator iter2 = signalsMap->getIterator();
		while (iter2 != iter2.end())
	    {
	    	SignalEntry* entry = (SignalEntry*) iter2.getObject();
	    	delete entry;

	    	iter2 = iter2.next();
	    }


	    signalsMap->unlock();

    	iter1 = iter1.next();
    }

    getOutSignalMap()->unlock();

	getInSignalMap()->lock();

	iter1 = getInSignalMap()->getIterator();
	while (iter1 != iter1.end())
    {
    	UMLRTHashMap* signalsMap = (UMLRTHashMap*) iter1.getObject();

    	signalsMap->lock();

    	UMLRTHashMap::Iterator iter2 = signalsMap->getIterator();
		while (iter2 != iter2.end())
	    {
	    	SignalEntry* entry = (SignalEntry*) iter2.getObject();
	    	delete entry;

	    	iter2 = iter2.next();
	    }


	    signalsMap->unlock();

    	iter1 = iter1.next();
    }

    getInSignalMap()->unlock();
}

UMLRTHashMap * UMLRTSignalRegistry::getOutSignalMap()
{
    if (outSignalMap == NULL)
    {
        outSignalMap = new UMLRTHashMap("outSignalMap", UMLRTHashMap::compareStringIgnoreCase, false/*objectIsString*/);
    }

    return outSignalMap;
}

UMLRTHashMap * UMLRTSignalRegistry::getInSignalMap()
{
    if (inSignalMap == NULL)
    {
        inSignalMap = new UMLRTHashMap("inSignalMap", UMLRTHashMap::compareStringIgnoreCase, false/*objectIsString*/);
    }

    return inSignalMap;
}

bool UMLRTSignalRegistry::registerOutSignal ( const char* protocol, const char* signalName, int signalID, UMLRTObject* payloadObj ) 
{
	UMLRTHashMap* signalsMap = (UMLRTHashMap*) getOutSignalMap()->getObject(protocol);
	if(signalsMap == NULL) {
		signalsMap = new UMLRTHashMap(protocol, UMLRTHashMap::compareStringIgnoreCase, false/*objectIsString*/);
		getOutSignalMap()->insert(protocol, signalsMap);
	}

	if(signalsMap->getObject(signalName) != NULL)
		return false; // already registered

	SignalEntry* entry = new SignalEntry;
	entry->protocol = protocol;
	entry->name = signalName;
	entry->id = signalID;
	entry->payloadObj = payloadObj;

	signalsMap->insert(signalName, entry);

	return true;
}

bool UMLRTSignalRegistry::registerInSignal ( const char* protocol, const char* signalName, int signalID, UMLRTObject* payloadObj ) 
{
	UMLRTHashMap* signalsMap = (UMLRTHashMap*) getInSignalMap()->getObject(protocol);
	if(signalsMap == NULL) {
		signalsMap = new UMLRTHashMap(protocol, UMLRTHashMap::compareStringIgnoreCase, false/*objectIsString*/);
		getInSignalMap()->insert(protocol, signalsMap);
	}

	if(signalsMap->getObject(signalName) != NULL)
		return false; // already registered

	SignalEntry* entry = new SignalEntry;
	entry->protocol = protocol;
	entry->name = signalName;
	entry->id = signalID;
	entry->payloadObj = payloadObj;

	signalsMap->insert(signalName, entry);

	return true;
}
    
int UMLRTSignalRegistry::getOutSignalID ( const char* protocol, const char* signalName )
{
	UMLRTHashMap* signalsMap = (UMLRTHashMap*) getOutSignalMap()->getObject(protocol);
	if(signalsMap != NULL) {
		SignalEntry* entry = (SignalEntry*) signalsMap->getObject(signalName);
		if(entry != NULL)
			return entry->id;
	}

	return -1;
}


int UMLRTSignalRegistry::getInSignalID ( const char* protocol, const char* signalName )
{
	UMLRTHashMap* signalsMap = (UMLRTHashMap*) getInSignalMap()->getObject(protocol);
	if(signalsMap != NULL) {
		SignalEntry* entry = (SignalEntry*) signalsMap->getObject(signalName);
		if(entry != NULL)
			return entry->id;
	}

	return -1;
}

UMLRTObject* UMLRTSignalRegistry::getOutSignalPayloadObject ( const char* protocol, const char* signalName )
{
	UMLRTHashMap* signalsMap = (UMLRTHashMap*) getOutSignalMap()->getObject(protocol);
	if(signalsMap != NULL) {
		SignalEntry* entry = (SignalEntry*) signalsMap->getObject(signalName);
		if(entry != NULL)
			return entry->payloadObj;
	}

	return NULL;
}

UMLRTObject* UMLRTSignalRegistry::getInSignalPayloadObject ( const char* protocol, const char* signalName )
{
	UMLRTHashMap* signalsMap = (UMLRTHashMap*) getInSignalMap()->getObject(protocol);
	if(signalsMap != NULL) {
		SignalEntry* entry = (SignalEntry*) signalsMap->getObject(signalName);
		if(entry != NULL)
			return entry->payloadObj;
	}

	return NULL;
}