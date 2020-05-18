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


import org.ballerinalang.jvm.values.ObjectValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MockRegistry {
    private static MockRegistry instance = new MockRegistry();

    public static MockRegistry getInstance() {
        return instance;
    }
    private Map<String, Case> casesMap = new HashMap<String, Case>();
    private List<ObjectValue> registerInProgress = new ArrayList();

    public static class Case {
        private ObjectValue mockObject;
        private String functionName;
        private Object[] argsList;
        private boolean isArgAvailable = true;
        private Object returnVal = null;

        public Case(ObjectValue mockObject, String functionName, Object[] argsList) {
            this.mockObject = mockObject;

            this.functionName = functionName;
            this.argsList = argsList;
        }

        public void setReturnVal(Object returnVal) {
            this.returnVal = returnVal;
        }

        public Object getReturnVal() {
            return returnVal;
        }

        public boolean isArgAvailable() {
            return isArgAvailable;
        }

        public void setArgAvailable(boolean argAvailable) {
            isArgAvailable = argAvailable;
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

    public String addNewCase(ObjectValue mockObject, String functionName, Object[] argsList) {
        Case mockCase = new Case(mockObject, functionName, argsList);
        String caseId = constructCaseId(mockObject, functionName, argsList);
        casesMap.put(caseId, mockCase);
        return caseId;
    }

    public Case getCase(String caseId) {
        return casesMap.get(caseId);
    }

    public static String constructCaseId(ObjectValue mockObject, String functionName, Object[] argsList) {
        return mockObject.hashCode() + "-" +functionName + "-" + Arrays.toString(argsList);
    }

}


