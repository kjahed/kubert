package ca.jahed.kubert.model.classes

import ca.jahed.kubert.model.RTSlot
import ca.jahed.kubert.utils.NameUtils
import ca.jahed.rtpoet.rtmodel.*
import ca.jahed.rtpoet.rtmodel.cppproperties.RTAttributeProperties
import ca.jahed.rtpoet.rtmodel.cppproperties.RTClassProperties
import ca.jahed.rtpoet.rtmodel.cppproperties.RTParameterProperties
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTBoolean
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTInteger
import ca.jahed.rtpoet.rtmodel.types.primitivetype.RTString

class RTFramePortWrapper(slot: RTSlot): RTClass(NameUtils.randomize(RTFramePortWrapper::class.java.simpleName)) {
    private val k8sScaleTemplate = "kubectl scale deployments/%s --replicas=%d"

    init {
        attributes.add(RTAttribute.builder("k8sName", RTString).build())

        attributes.add(RTAttribute.builder("frame", RTInteger)
            .properties(RTAttributeProperties.builder().type("UMLRTFrameProtocol_baserole *"))
            .build()
        )

        operations.add(RTOperation.builder("setPort")
            .parameter(RTParameter.builder("port", RTInteger)
                .properties(RTParameterProperties.builder().type("UMLRTFrameProtocol_baserole *"))
            )
            .action(RTAction.builder("""
                this->frame = port;
            """.trimIndent()))
            .build()
        )

        operations.add(RTOperation.builder("incarnate")
            .parameter(RTParameter.builder("part", RTInteger)
                .properties(RTParameterProperties.builder().type("const UMLRTCapsulePart *"))
                .build()
            )
            .ret(RTParameter.builder(RTInteger)
                .properties(RTParameterProperties.builder().type("const UMLRTCapsuleId"))
            )
            .action(RTAction.builder("""
                UMLRTCapsuleId capsuleId = frame->incarnate(part);
                int index = capsuleId.getCapsule()->getIndex();
                int indexLength = index > 0 ? floor(log10(abs(index))) + 1 : 1;
                
                const char * ownerK8sName = "${slot.k8sName}";
                const char * partName = part->role()->name;
                char * k8sName = (char*) malloc(sizeof(char) * (strlen(ownerK8sName) + strlen(partName) + indexLength + 4));
                sprintf(k8sName, "%s--%s-%d", ownerK8sName, partName, index);
                
                const char * k8sScaleTemplate = "$k8sScaleTemplate";                
                char * kubeCmd = (char*) malloc(sizeof(char) * (strlen(k8sScaleTemplate) + strlen(k8sName) + 2));
                sprintf(kubeCmd, k8sScaleTemplate, k8sName, 1);
                
                system(kubeCmd);
                free(kubeCmd);
                
                capsuleId.setId(k8sName);
                return capsuleId;
            """.trimIndent()))
            .build()
        )

        operations.add(RTOperation.builder("destroy")
            .parameter(RTParameter.builder("capsuleId", RTInteger)
                .properties(RTParameterProperties.builder().type("const UMLRTCapsuleId"))
            )
            .ret(RTParameter.builder(RTBoolean))
            .action(RTAction.builder("""
                const char * k8sScaleTemplate = "$k8sScaleTemplate";  
                const char * id = capsuleId.getId();
                
                char * kubeCmd = (char*) malloc(sizeof(char) * (strlen(k8sScaleTemplate) + strlen(id) + 2));
                sprintf(kubeCmd, k8sScaleTemplate, id, 0);
                
                system(kubeCmd);
                free(kubeCmd);
                return frame->destroy(capsuleId);
            """.trimIndent()))
            .build()
        )

        properties = RTClassProperties.builder()
            .headerPreface("""
                #include "umlrtcapsuleid.hh"
                #include "umlrtcapsulepart.hh"
                #include "umlrtframeprotocol.hh"
            """.trimIndent())
            .implementationPreface("""
                #include "umlrtcapsule.hh"
                #include <math.h>
                #include <stdlib.h>
            """.trimIndent())
            .dontGenerateExtractionOperator()
            .dontGenerateInsertionOperator()
            .build()
    }
}