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

import org.ballerinalang.jvm.scheduling.Strand;
import org.ballerinalang.jvm.types.BObjectType;
import org.ballerinalang.jvm.util.exceptions.BallerinaException;
import org.ballerinalang.jvm.values.AbstractObjectValue;
import org.ballerinalang.jvm.values.FutureValue;
import org.ballerinalang.jvm.values.ObjectValue;
import org.ballerinalang.jvm.values.StringValue;
import org.ballerinalang.jvm.values.api.BString;

public class GenericMockObjectValue extends AbstractObjectValue {

    private ObjectValue mockObj;

    public GenericMockObjectValue(BObjectType type, ObjectValue mockObj) {
        super(type);
        this.mockObj = mockObj;
    }

    @Override
    public Object call(Strand strand, String funcName, Object... args) {
        if (MockRegistry.getInstance().getRegisterInProgress().contains(this.mockObj)) {
            return MockRegistry.getInstance().addNewCase(this.mockObj, funcName, args);
        }

        String caseId = MockRegistry.constructCaseId(this.mockObj, funcName, args);
        if(MockRegistry.getInstance().getCase(caseId) == null) {
            throw new BallerinaException("mock object is not registered");
        }
        return MockRegistry.getInstance().getCase(caseId).getReturnVal();
    }

    @Override
    public Object get(String fieldName) {
        return null;
    }

    @Override
    public Object get(StringValue fieldName) {
        return null;
    }

    @Override
    public Object get(BString fieldName) {
        return null;
    }

    @Override
    public void set(String fieldName, Object value) {

    }

    @Override
    public void set(StringValue fieldName, Object value) {

    }

    @Override
    public FutureValue start(Strand strand, String funcName, Object... args) {
        return null;
    }

    @Override
    public BString bStringValue() {
        return null;
    }

    public ObjectValue getMockObj() {
        return this.mockObj;
    }
}
