/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.langlib.xml;

import io.ballerina.runtime.api.async.StrandMetadata;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BXml;
import io.ballerina.runtime.internal.scheduling.AsyncUtils;
import io.ballerina.runtime.internal.scheduling.Scheduler;

import java.util.concurrent.atomic.AtomicInteger;

import static io.ballerina.runtime.api.constants.RuntimeConstants.BALLERINA_BUILTIN_PKG_PREFIX;
import static io.ballerina.runtime.api.constants.RuntimeConstants.XML_LANG_LIB;
import static org.ballerinalang.langlib.xml.utils.Constants.XML_VERSION;

/**
 * Native implementation of lang.xml:forEach(map&lt;Type&gt;, function).
 *
 * @since 1.0
 */
public final class ForEach {

    private static final StrandMetadata METADATA = new StrandMetadata(BALLERINA_BUILTIN_PKG_PREFIX, XML_LANG_LIB,
                                                                      XML_VERSION, "forEach");

    private ForEach() {
    }

    public static void forEach(BXml x, BFunctionPointer<Object, Object> func) {
        if (x.isSingleton()) {
            func.asyncCall(new Object[]{x, true}, METADATA);
            return;
        }
        AtomicInteger index = new AtomicInteger(-1);
        AsyncUtils.invokeFunctionPointerAsyncIteratively(func, null, METADATA, x.size(),
                () -> new Object[]{x.getItem(index.incrementAndGet()), true}, result -> {
                }, () -> null, Scheduler.getStrand().scheduler);
    }
}
