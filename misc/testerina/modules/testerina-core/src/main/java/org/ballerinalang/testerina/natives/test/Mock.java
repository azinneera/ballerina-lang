/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.ballerinalang.testerina.natives.test;

import org.ballerinalang.jvm.types.BObjectType;
import org.ballerinalang.jvm.util.exceptions.BallerinaException;
import org.ballerinalang.jvm.values.ObjectValue;
import org.ballerinalang.jvm.values.TypedescValue;

/**
 * Generic mock object.
 */
public class Mock {

    public static ObjectValue mockExt(TypedescValue typedescValue, ObjectValue objectValue) {
        BObjectType bObjectType = (BObjectType) typedescValue.getDescribingType();
        return new GenericMockObjectValue(bObjectType, objectValue);
    }

    public static void startMockRegister(ObjectValue... objectValues) {
        for (ObjectValue objectValue : objectValues) {
            GenericMockObjectValue genericMock = (GenericMockObjectValue) objectValue;
            MockRegistry.getInstance().addToRegisterInProgress(genericMock.getMockObj());
        }
    }
    public static void endMockRegister(ObjectValue... objectValues) {
        for (ObjectValue objectValue : objectValues) {
            GenericMockObjectValue genericMock = (GenericMockObjectValue) objectValue;
            MockRegistry.getInstance().removeFromRegisterInProgress(genericMock.getMockObj());
        }
    }

    public static void thenReturnExt(ObjectValue caseObj, Object retVal) {
        String caseId = caseObj.getStringValue("caseId");
        if(MockRegistry.getInstance().getCase(caseId) == null) {
            throw new BallerinaException("object not registered for mocking.");
        }
        MockRegistry.getInstance().getCase(caseId).setReturnVal(retVal);
        System.out.println();
    }





    

    //    public static void whenExt(ObjectValue obj, String functionName) {
//        ObjectValue mockObj = ((GenericMockObjectValue) obj).getMockObj();
//        Map<String, Map<Object[], Object>> objMap = ArgsMatcherStorage.getInstance().getObjectMap().get(mockObj);
//        if (objMap.isEmpty()) {
//            Map<String, Map<Object[], Object>> functionsMap = new HashMap<>();
//            Map<Object[], Object> argsMap = new HashMap<>();
//            functionsMap.put(functionName, argsMap);
//            ArgsMatcherStorage.getInstance().getObjectMap().put(mockObj, functionsMap);
//        }
//
//    }

//    public static void withArgumentsExt(ObjectValue objectValue) {
//        ObjectValue mockObj = ((GenericMockObjectValue) objectValue.getObjectValue("mockObj")).getMockObj();
//        Map<String, Map<Object[], Object>> functionsMap = ArgsMatcherStorage.getInstance().getObjectMap().get(mockObj);
//        String functionName = objectValue.getStringValue("method");
//
//        Map<Object[], Object> argsMap;
//
//        for (Map.Entry<String, Map<Object[], Object>> entry : functionsMap.entrySet()) {
//            if (entry.getKey().equals(functionName)) {
//                argsMap = functionsMap.entrySet().iterator().next().getValue();
//                List argsList = new ArrayList();
//                for (int i = 0; i < objectValue.getArrayValue("args").size(); i++) {
//                    argsList.add(objectValue.getArrayValue("args").getRefValue(i));
//                }
//                argsMap.put(argsList.toArray(), null);
//            }
//        }
//        System.out.println();
//    }
}
