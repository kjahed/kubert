// umlrtjsoncoder.cc

#include "basedebug.hh"
#include "basefatal.hh"

#include "umlrtcapsule.hh"
#include "umlrtmessage.hh"
#include "umlrtjsoncoder.hh"
#include "umlrtsignalregistry.hh"

UMLRTJSONCoder::UMLRTJSONCoder ( ) {
    document.SetArray();
    documentIndex = 0;
}

UMLRTJSONCoder::UMLRTJSONCoder ( const char * json ) {
    document.Parse(json);
    documentIndex = 0;

    if(!document.IsArray()) {
        fprintf(stderr, "Error parsing JSON message: '%s'\n", json);
        return;
    }
}

char * UMLRTJSONCoder::generateMsgID ( ) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    srand((time_t)ts.tv_nsec);
    const int len  = 16;

	char* s = (char*) malloc(sizeof(char) * (len+1));
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

char * UMLRTJSONCoder::fromJSON( const char * json, UMLRTSignal & signal, UMLRTSlot * slot, int * destPortIdx )
{
	Document document;
	document.Parse(json);

	if(!document.IsObject()) {
		fprintf(stderr, "Error parsing JSON message: '%s'\n", json);
		return NULL;
	}

	if(!document.HasMember("sourcePort")) {
		fprintf(stderr, "Error parsing JSON message: '%s'\n", json);
		return NULL;
	}

    const Value& sourceSlotVal = document["sourceSlot"];
    if(!sourceSlotVal.IsString()) {
        fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
        return NULL;
    }

	const Value& sourcePortVal = document["sourcePort"];
	if(!sourcePortVal.IsString()) {
		fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
		return NULL;
	}

    const char* sourceSlotStr = sourceSlotVal.GetString();
	const char* sourcePortStr = sourcePortVal.GetString();

	const UMLRTCommsPort * sourcePort = NULL;
	for(int i=0; i<slot->numPorts; i++) {
		if(strcmp(slot->ports[i].getName(), sourcePortStr) == 0) {
			sourcePort = &(slot->ports[i]);
			break;
		}
	}

	if(sourcePort == NULL) {
		for(int i=0; i<slot->capsuleClass->numPortRolesInternal; i++) {
			if(strcmp(slot->capsule->getInternalPorts()[i]->getName(), sourcePortStr) == 0) {
				sourcePort = slot->capsule->getInternalPorts()[i];
				break;
			}
		}
	}

	if(sourcePort == NULL) {
		fprintf(stderr, "Source port '%s' not found\n", sourcePortStr);
		return NULL;
	}

	if(!document.HasMember("destinationPortIndex")) {
		fprintf(stderr, "Error parsing JSON message: '%s'\n", json);
		return NULL;
	}

	const Value& destPortIdxVal = document["destinationPortIndex"];
	if(!destPortIdxVal.IsInt()) {
		fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
		return NULL;
	}

	*destPortIdx = destPortIdxVal.GetInt();

	if(!document.HasMember("signal")) {
		fprintf(stderr, "Error parsing JSON message: '%s'\n", json);
		return NULL;
	}

	const Value& sigVal = document["signal"];
	if(!sigVal.IsString()) {
		fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
		return NULL;
	}

	const Value& protVal = document["protocol"];
	if(!protVal.IsString()) {
		fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
		return NULL;
	}

	char* msgID = "";
	if(document.HasMember("id")) {
		const Value& idVal = document["id"];
		if(idVal.IsString()) {
			const char* id = idVal.GetString();
			msgID = strdup(id);
		}
	}

	const char* signalName = sigVal.GetString();
	const char* protocolName = protVal.GetString();

	int signalID = UMLRTSignalRegistry::getRegistry().getInSignalID(protocolName, signalName);
	if(signalID == -1)
		return NULL;

	UMLRTObject * payloadObj = UMLRTSignalRegistry::getRegistry().getInSignalPayloadObject(protocolName, signalName);
	if(payloadObj->sizeOf == 0)
		payloadObj = NULL;

	if(payloadObj != NULL && !document.HasMember("params")) {
		fprintf(stderr, "Mismatched signal parameters in JSON message '%s'\n", json);
		return NULL;
	}

	if(payloadObj == NULL && document.HasMember("params")) {
		const Value& paramsArr = document["params"];
		if(!paramsArr.IsArray()) {
			fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
			return NULL;
		}

		if(paramsArr.Size() > 0) {
			fprintf(stderr, "Mismatched signal parameters in JSON message '%s'\n", json);
			return NULL;
		}
	}

	if(payloadObj != NULL) {
		const Value& paramsArr = document["params"];
		if(!paramsArr.IsArray()) {
			fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
			return NULL;
		}

		if(payloadObj->numFields != paramsArr.Size()) {
			fprintf(stderr, "Mismatched signal parameters in JSON message '%s'\n", json);
			return NULL;
		}

		uint8_t* data = (uint8_t*) malloc(sizeof(uint8_t) * payloadObj->sizeOf);
		if(data == NULL)
			FATAL("memory allocation failed for payload data");

		for (size_t i=0; i<payloadObj->numFields; i++)
	    {
	    	const Value& paramObj = paramsArr[i];
	    	if(!paramObj.IsObject()) {
	    		fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
	    		return NULL;
	    	}

	    	if(!paramObj.HasMember("value")) {

	    		fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
	    		return NULL;
	    	}

	    	if(paramObj.HasMember("type")
	    		&& !paramObj["type"].IsString()) {

	    		fprintf(stderr, "Erroneous JSON message: '%s'\n", json);
	    		return NULL;
	    	}

	    	// decode
	    	if(!decodeParam(paramObj, payloadObj->fields[i].desc, data + payloadObj->fields[i].offset)) {
	    		fprintf(stderr, "Mismatched signal parameters in JSON message '%s'\n", json);
	    		return NULL;
	    	}
	    }

		signal.initialize(signalName, signalID, sourcePort, payloadObj, data);
		memcpy(signal.getPayload(), data, payloadObj->sizeOf);
		free(data);
	}

	else {
		signal.initialize(signalName, signalID, sourcePort);
	}

	return msgID;
}

bool UMLRTJSONCoder::decodeParam( const Value & param, const UMLRTObject_class * desc, uint8_t * buffer) {
	const Value& paramVal = param["value"];

	if(param.HasMember("type")
		&& strcmp(param["type"].GetString(), desc->name) != 0)
		return false;

	if(paramVal.IsArray()) {
		if(paramVal.Size() != desc->object.numFields)
			return false;

		for (size_t i=0; i<desc->object.numFields; i++) {
	    	const Value& paramObj = paramVal[i];

	    	if(!paramObj.HasMember("value"))
	    		return false;

	    	if(paramObj.HasMember("type")
	    		&& !paramObj["type"].IsString())
	    		return false;

	    	if(!decodeParam(paramObj, desc->object.fields[i].desc, buffer + desc->object.fields[i].offset))
	    		return false;
	    }

	}

	else if(strcmp(desc->name, "bool") == 0) {
		if(!paramVal.IsBool())
			return false;

		bool b = paramVal.GetBool();
		desc->copy(desc, &b, buffer);
	}

	else if(strcmp(desc->name, "char") == 0) {
		if(!paramVal.IsString())
			return false;

		const char* str = paramVal.GetString();
		if(strlen(str) != 1)
			return false;

		char c = str[0];
		desc->copy(desc, &c, buffer);
	}

	else if(strcmp(desc->name, "double") == 0) {
		if(!paramVal.IsDouble())
			return false;

		double d = paramVal.GetDouble();
		desc->copy(desc, &d, buffer);
	}

	else if(strcmp(desc->name, "float") == 0) {
		if(!paramVal.IsFloat())
			return false;

		float f = paramVal.GetFloat();
		desc->copy(desc, &f, buffer);
	}

	else if(strcmp(desc->name, "int") == 0) {
		if(!paramVal.IsInt())
			return false;

		int i = paramVal.GetInt();
		desc->copy(desc, &i, buffer);
	}

	else if(strcmp(desc->name, "charptr") == 0) {
		if(!paramVal.IsString())
			return false;

		const char* str = paramVal.GetString();
		desc->copy(desc, &str, buffer);
	}
	
	else if(strcmp(desc->name, "UMLRTCapsuleId") == 0) {
		if(!paramVal.IsString())
			return false;

		const char* str = paramVal.GetString();
		
		UMLRTCapsuleId capsuleId;
		capsuleId.setId(strndup(str, paramVal.GetStringLength()));
		desc->copy(desc, &capsuleId, buffer);
	}

	else
		return false;

	return true;
}

char * UMLRTJSONCoder::toJSON( const UMLRTMessage * msg, char ** buffer ) {
	Document document;
	document.SetObject();
	Document::AllocatorType& allocator = document.GetAllocator();

	Value paramsArray(kArrayType);

	const UMLRTObject * payloadObj = UMLRTSignalRegistry::getRegistry().getOutSignalPayloadObject(
		msg->signal.getSrcPort()->role()->protocol, msg->signal.getName());

	if(payloadObj != NULL) {
		for(size_t i=0; i<payloadObj->numFields; i++) {
			Value paramObj(kObjectType);

			Value paramName;
			paramName.SetString(payloadObj->fields[i].name, allocator);
			paramObj.AddMember("name", paramName, allocator);

			encodeParam(document, paramObj, msg->signal.getType(i), msg->signal.getParam(i));
			paramsArray.PushBack(paramObj, allocator);
		}
	}

	char* msgID = generateMsgID();

	Value id;
	id.SetString(msgID, allocator);

	Value sourceSlotName;
	sourceSlotName.SetString(msg->signal.getSrcPort()->slotName(), allocator);

	Value sourcePortName;
	sourcePortName.SetString(msg->signal.getSrcPort()->getName(), allocator);
	
	Value signalName;
	signalName.SetString(msg->signal.getName(), allocator);

	Value protocolName;
	protocolName.SetString(msg->signal.getSrcPort()->role()->protocol, allocator);

	Value destPortIdx;
	destPortIdx.SetInt(msg->srcPortIndex);

	document.AddMember("id", id, allocator);
	document.AddMember("sourceSlot", sourceSlotName, allocator);
	document.AddMember("sourcePort", sourcePortName, allocator);
	document.AddMember("destinationPortIndex", destPortIdx, allocator);
	document.AddMember("protocol", protocolName, allocator);
	document.AddMember("signal", signalName, allocator);
	document.AddMember("params", paramsArray, allocator);

	StringBuffer strbuf;
	Writer<StringBuffer> writer(strbuf);
	document.Accept(writer);

	*buffer = strdup(strbuf.GetString());

	return msgID;
}

void UMLRTJSONCoder::encodeParam( Document & document, Value & paramObj, const UMLRTObject_class * desc, void * data ) {
	Document::AllocatorType& allocator = document.GetAllocator();

	Value paramType;
	paramType.SetString(desc->name, allocator);
	paramObj.AddMember("type", paramType, allocator);

	if (desc->object.numFields != 0) {	// complex object
		Value valueArray(kArrayType);

    	for (size_t i=0; i<desc->object.numFields; i++) {
            const UMLRTObject_field* field = &desc->object.fields[i];

            Value subParamObj(kObjectType);

            Value paramName;
			paramName.SetString(field->name, allocator);
			subParamObj.AddMember("name", paramName, allocator);

            encodeParam(document, subParamObj, field->desc, (uint8_t*) data + field->offset);
			valueArray.PushBack(subParamObj, allocator);
        }

       	paramObj.AddMember("value", valueArray, allocator);
    }

	else if(strcmp(desc->name, "bool") == 0) {
	    bool b;
	    desc->copy(desc, data, &b);

	    Value paramValue;
		paramValue.SetBool(b);
		paramObj.AddMember("value", paramValue, allocator);
	}

	else if(strcmp(desc->name, "char") == 0) {
	    Value paramValue;
		paramValue.SetString((char*) data, 1, allocator);
		paramObj.AddMember("value", paramValue, allocator);
	}

	else if(strcmp(desc->name, "double") == 0) {
	    double d;
        desc->copy(desc, data, &d);


	    Value paramValue;
		paramValue.SetDouble(d);
		paramObj.AddMember("value", paramValue, allocator);
	}

	else if(strcmp(desc->name, "float") == 0) {
		float f;
        desc->copy(desc, data, &f);


	    Value paramValue;
		paramValue.SetFloat(f);
		paramObj.AddMember("value", paramValue, allocator);
	}

	else if(strcmp(desc->name, "int") == 0) {
		int iv;
        desc->copy(desc, data, &iv);


	    Value paramValue;
		paramValue.SetInt(iv);
		paramObj.AddMember("value", paramValue, allocator);
	}

	else if(strcmp(desc->name, "charptr") == 0) {
		void* ptr;
		desc->copy(desc, data, &ptr);

		Value paramValue;
		paramValue.SetString((const char*) ptr, allocator);
		paramObj.AddMember("value", paramValue, allocator);

		desc->destroy(desc, &ptr);
	}
	
	else if(strcmp(desc->name, "UMLRTCapsuleId") == 0) {
		UMLRTCapsuleId capsuleID;
		desc->copy(desc, data, &capsuleID);

		Value paramValue;
		paramValue.SetString((const char*) capsuleID.getId(), allocator);
		paramObj.AddMember("value", paramValue, allocator);
	}

	else {
		fprintf(stderr, "Unsupported data type '%s'\n", desc->name);
	}
}

void UMLRTJSONCoder::encode( const UMLRTObject_class * desc, uint8_t * data ) {
    Document::AllocatorType& allocator = document.GetAllocator();

    Value paramObj(kObjectType);
    encodeParam(document, paramObj, desc, data);
    document.PushBack(paramObj, allocator);
}

void UMLRTJSONCoder::commit( char ** buffer ) {
    StringBuffer strbuf;
    Writer<StringBuffer> writer(strbuf);
    document.Accept(writer);
    *buffer = strdup(strbuf.GetString());
}

void UMLRTJSONCoder::decode( const UMLRTObject_class * desc, uint8_t * data ) {
    if(document.Size() <= documentIndex) {
        FATAL("Reached end of content");
    }

	const Value& paramObj = document[documentIndex++];
	decodeParam(paramObj, desc, data);
}