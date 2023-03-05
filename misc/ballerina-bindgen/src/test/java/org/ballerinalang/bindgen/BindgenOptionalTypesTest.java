/*
 *  Copyright (c) 2023, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.ballerinalang.bindgen;

import org.ballerinalang.bindgen.exceptions.BindgenException;
import org.ballerinalang.formatter.core.FormatterException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class BindgenOptionalTypesTest extends BindgenUnitTestBase {

    public BindgenOptionalTypesTest() {
        this.withOptionalParamTypes = true;
        this.withOptionalReturnTypes = true;
        this.resourceDirectory = Paths.get("src")
                .resolve("test")
                .resolve("resources")
                .resolve("unit-test-resources")
                .resolve("withOptionalTypes")
                .toAbsolutePath();
    }

    @Override
    @Test
    public void constructorMapping() throws FormatterException, ClassNotFoundException, BindgenException, IOException {
        super.constructorMapping();
    }

    @Override
    @Test
    public void methodMapping() throws FormatterException, ClassNotFoundException, BindgenException, IOException {
        super.methodMapping();
    }

    @Override
    @Test
    public void fieldMapping() throws FormatterException, ClassNotFoundException, BindgenException, IOException {
        super.fieldMapping();
    }

    @Override
    @Test
    public void errorMapping() throws FormatterException, ClassNotFoundException, BindgenException, IOException {
        super.errorMapping();
    }

    @Override
    @Test
    public void innerClassMapping() throws FormatterException, ClassNotFoundException, BindgenException, IOException {
        super.innerClassMapping();
    }

    @Override
    @Test
    public void dependentClassMapping() throws FormatterException, ClassNotFoundException, BindgenException, IOException {
        super.dependentClassMapping();
    }

    @Override
    @Test
    public void directThrowableMapping() throws FormatterException, ClassNotFoundException, BindgenException, IOException {
        super.directThrowableMapping();
    }

    @Override
    @Test
    public void dependentInnerClassMapping() throws FormatterException, ClassNotFoundException, BindgenException, IOException {
        super.dependentInnerClassMapping();
    }

    @Override
    @Test
    public void interfaceMapping() throws FormatterException, ClassNotFoundException, BindgenException, IOException {
        super.interfaceMapping();
    }
}
