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

import org.ballerinalang.jvm.BallerinaErrors;
import org.ballerinalang.jvm.StringUtils;
import org.ballerinalang.jvm.types.AttachedFunction;
import org.ballerinalang.jvm.types.BObjectType;
import org.ballerinalang.jvm.types.BType;
import org.ballerinalang.jvm.util.exceptions.BallerinaException;
import org.ballerinalang.jvm.values.ArrayValue;
import org.ballerinalang.jvm.values.ErrorValue;
import org.ballerinalang.jvm.values.ObjectValue;
import org.ballerinalang.jvm.values.TypedescValue;

/**
 * Generic mock object.
 */
public class Mock {

    public static ObjectValue mock(TypedescValue typedescValue, ObjectValue objectValue) {
        if (!objectValue.getType().getName().contains("$anonType$")) {
            if (objectValue.getType().getAttachedFunctions().length == 0 &&
                    objectValue.getType().getFields().size() == 0) {
                throw new BallerinaException("Mock object type " + objectValue.getType().getName()
                        + " should have at least one member function or field declared.");
            } else {
                for (AttachedFunction attachedFunction : objectValue.getType().getAttachedFunctions()) {
                    if(!isFunctionAvailable(attachedFunction,
                            ((BObjectType)typedescValue.getDescribingType()).getAttachedFunctions())) {
                        throw new BallerinaException("Mock object type " + objectValue.getType().getName()
                                + " is not compatible with original");
                    }
                }
            }
        }
        BObjectType bObjectType = (BObjectType) typedescValue.getDescribingType();
        return new GenericMockObjectValue(bObjectType, objectValue);
    }

    public static void thenReturn(ObjectValue caseObj) {
        GenericMockObjectValue genericMock = (GenericMockObjectValue) caseObj.get("prepareObj");
        ObjectValue mockObj = genericMock.getMockObj();
        String functionName = caseObj.getStringValue("functionName");
        ArrayValue args = caseObj.getArrayValue("args");
        //TODO: check
//        Object returnVal = caseObj.get(StringUtils.fromString("returnVal"));
        Object returnVal = caseObj.get("returnVal");
        MockRegistry.getInstance().addNewCase(mockObj, functionName, args, returnVal);
    }

    public static ErrorValue validatePrepareObj(ObjectValue caseObj) {
        GenericMockObjectValue genericMock = (GenericMockObjectValue) caseObj;
        ObjectValue mockObj = genericMock.getMockObj();
        String objectType = mockObj.getType().getName();
        if (!objectType.contains("$anonType$")) {
            String detail = "Cases cannot be registered to user-defined object type " + genericMock.getType().getName();
            return BallerinaErrors.createError(StringUtils.fromString(MockConstants.INVALID_MOCK_OBJECT_ERROR), detail);
        }
        return null;
    }

    public static ErrorValue validateFunctionName(ObjectValue caseObj) throws BallerinaException {
        GenericMockObjectValue genericMock = (GenericMockObjectValue) caseObj.getObjectValue("prepareObj");
        String functionName = caseObj.getStringValue("functionName");
        if (!isFunctionAvailable(functionName, genericMock.getType().getAttachedFunctions())) {
            String detail = functionName + ": no such function is available in " + genericMock.getType().getName();
            return BallerinaErrors.createError(StringUtils.fromString(MockConstants.FUNCTION_NOT_FOUND_ERROR), detail);
        }
        return null;
    }

    private static boolean isFunctionAvailable(String functionName, AttachedFunction[] attachedFunctions) {
        for ( AttachedFunction attachedFunction : attachedFunctions) {
            if (attachedFunction.getName().equals(functionName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFunctionAvailable(AttachedFunction func, AttachedFunction[] attachedFunctions) {
        String functionName = func.getName();
        BType[] paramTypes = func.getParameterType();
        BType returnType = func.type.getReturnParameterType();

        for ( AttachedFunction attachedFunction : attachedFunctions) {
            if (attachedFunction.getName().equals(functionName)) {
                if (paramTypes.length != attachedFunction.getParameterType().length) {
                    return false;
                } else {
                    for (int i = 0; i < paramTypes.length; i++) {
                        boolean isParamTypeMatching = paramTypes[i].equals(attachedFunction.getParameterType()[i]);
                        if (!isParamTypeMatching) {
                            return false;
                        }
                    }
                }

                return returnType.equals(attachedFunction.type.getReturnParameterType());

            }
        }
        return false;
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
