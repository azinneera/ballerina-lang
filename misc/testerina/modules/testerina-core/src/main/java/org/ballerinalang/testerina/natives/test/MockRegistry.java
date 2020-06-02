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


import org.ballerinalang.jvm.values.AbstractObjectValue;
import org.ballerinalang.jvm.values.ArrayValue;
import org.ballerinalang.jvm.values.IteratorValue;
import org.ballerinalang.jvm.values.ObjectValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MockRegistry {
    public static final String ANY = "__ANY__";
    private static MockRegistry instance = new MockRegistry();

    public static MockRegistry getInstance() {
        return instance;
    }
    private Map<String, Object> casesMap = new HashMap();
    private ArrayList registerInProgress = new ArrayList();
    private List<Object> acceptAnyArgs = new ArrayList<>();

    public List<Object> getAcceptAnyArgs() {
        return acceptAnyArgs;
    }

    public void addToAnyArgObjList(Object acceptAnyArgsObj) {
        this.acceptAnyArgs.add(acceptAnyArgsObj);
    }

    public static class Case {
        private ObjectValue mockObject;
        private String functionName;
        private ArrayValue argsList;
        private Object returnVal;

        public Case(ObjectValue mockObject, String functionName, ArrayValue argsList, Object returnVal) {
            this.mockObject = mockObject;

            this.functionName = functionName;
            this.argsList = argsList;
            this.returnVal = returnVal;
        }

        public Object getReturnVal() {
            return returnVal;
        }

    }

    public void addToRegisterInProgress(ObjectValue mockObj) {
        registerInProgress.add(mockObj);
    }

    public void removeFromRegisterInProgress(ObjectValue mockObj) {
        registerInProgress.remove(mockObj);
    }

    public List<ObjectValue> getRegisterInProgress() {
        return registerInProgress;
    }

    public void addNewCase(ObjectValue mockObject, String functionName, ArrayValue argsList, Object returnVal) {
//        Case mockCase = new Case(mockObject, functionName, argsList, returnVal);
        String caseId = constructCaseId(mockObject, functionName, argsList);
        casesMap.put(caseId, returnVal);
    }

    private String constructCaseId(ObjectValue mockObject, String functionName, ArrayValue argsList) {
        StringBuilder caseIdBuilder = new StringBuilder();
        if (mockObject != null) {
            caseIdBuilder.append(mockObject.hashCode()).append("-").append(functionName);
            if (argsList.size() > 0) {
                IteratorValue argIterator = argsList.getIterator();
                while (argIterator.hasNext()) {
                    caseIdBuilder.append("-").append(argIterator.next().toString());
                }
            }
        }
        return caseIdBuilder.toString();
    }

    public Object getCase(String caseId) {

        return casesMap.get(caseId);
    }

    public boolean hasCase(String caseId) {
        return casesMap.containsKey(caseId);
    }


    public static String constructCaseId(ObjectValue mockObject, String functionName, Object[] argsList) {
         StringBuilder caseIdSuffixBuilder = new StringBuilder();

         for (Object objArg : argsList) {
             if (objArg != null) {
                 if (objArg instanceof AbstractObjectValue) {
                     caseIdSuffixBuilder.append("-").append("anyObj");
                 } else if (objArg instanceof GenericRecord) {
                     caseIdSuffixBuilder.append("-").append("anyRecord");
                 } else if (ANY.equals(objArg.toString())) {
                     caseIdSuffixBuilder.append("-").append("anyString");
                 } else {
                     caseIdSuffixBuilder.append("-").append(objArg.toString());
                 }
             }
        }
        if (caseIdSuffixBuilder.toString().isEmpty()) {
            constructCaseId(mockObject, functionName);
        }
        return mockObject.hashCode() + "-" +functionName + caseIdSuffixBuilder.toString() ;
    }

    public static String constructCaseId(ObjectValue mockObject, String functionName) {
        return mockObject.hashCode() + "-" +functionName + "-withAny";
    }

}
