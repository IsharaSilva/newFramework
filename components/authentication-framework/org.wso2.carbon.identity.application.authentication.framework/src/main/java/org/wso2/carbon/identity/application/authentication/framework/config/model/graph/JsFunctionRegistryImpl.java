/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.identity.application.authentication.framework.config.model.graph;

import org.wso2.carbon.identity.application.authentication.framework.JsFunctionRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Default implementation for the JsFunctionRegistry.
 */
public class JsFunctionRegistryImpl implements JsFunctionRegistry {

    private Map<Subsystem, Map<String, Object>> subsystemMap = new HashMap<>();

    @Override
    public void register(Subsystem subsystem, String functionName, Object function) {
        Map<String, Object> functionNameMap = subsystemMap.get(subsystem);
        if (functionNameMap == null) {
            functionNameMap = new HashMap<>();
            subsystemMap.put(subsystem, functionNameMap);
        }
        functionNameMap.put(functionName, function);
    }

    public void stream(Subsystem subsystem, Consumer<Map.Entry<String, Object>> consumer) {
        Map<String, Object> functionNameMap = subsystemMap.get(subsystem);

        if (functionNameMap != null) {
            functionNameMap.entrySet().stream().forEach(e -> consumer.accept(e));
        }
    }

    @Override
    public void deRegister(Subsystem subsystem, String functionName) {

    }
}
