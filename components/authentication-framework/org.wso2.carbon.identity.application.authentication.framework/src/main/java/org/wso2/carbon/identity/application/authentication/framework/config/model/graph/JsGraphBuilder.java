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

import jdk.nashorn.api.scripting.JSObject;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import jdk.nashorn.api.scripting.ScriptUtils;
import jdk.nashorn.internal.runtime.ScriptFunction;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticationDecisionEvaluator;
import org.wso2.carbon.identity.application.authentication.framework.JsFunctionRegistry;
import org.wso2.carbon.identity.application.authentication.framework.config.model.StepConfig;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.internal.FrameworkServiceDataHolder;
import org.wso2.carbon.identity.application.authentication.framework.store.JavascriptCache;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/**
 * Translate the authentication graph config to runtime model.
 * This is not thread safe. Should be discarded after each build.
 */
public class JsGraphBuilder {

    private static final Log log = LogFactory.getLog(JsGraphBuilder.class);
    private Map<String, StepConfig> stepNamedMap;
    private AuthenticationGraph result = new AuthenticationGraph();
    private AuthGraphNode currentNode = null;
    private AuthenticationContext authenticationContext;
    private JsFunctionRegistryImpl jsFunctionRegistrar;
    private ScriptEngine engine;
    private JavascriptCache javascriptCache;
    private static final String PROP_CURRENT_NODE = "Adaptive.Auth.Current.Graph.Node"; //TODO: same constant
    private static ThreadLocal<AuthenticationContext> contextForJs = new ThreadLocal<>();
    private static ThreadLocal<AuthGraphNode> dynamicallyBuiltBaseNode = new ThreadLocal<>();

    /**
     * Constructs the builder with the given authentication context.
     *
     * @param authenticationContext current authentication context.
     * @param stepConfigMap         The Step map from the service provider configuration.
     */
    public JsGraphBuilder(AuthenticationContext authenticationContext, Map<Integer, StepConfig> stepConfigMap,
                          ScriptEngine scriptEngine) {

        this.engine = scriptEngine;
        this.authenticationContext = authenticationContext;
        stepNamedMap = stepConfigMap.entrySet().stream()
                .collect(Collectors.toMap(e -> String.valueOf(e.getKey()), e -> e.getValue()));
    }

    /**
     * Returns the built graph.
     * @return
     */
    public AuthenticationGraph build() {
        if (currentNode != null && !(currentNode instanceof EndStep)) {
            attachToLeaf(currentNode, new EndStep());
        }
        return result;
    }

    /**
     * Creates the graph with the given Script and step map.
     *
     * @param script the Dynamic authentication script.
     */
    public JsGraphBuilder createWith(String script) {

        CompiledScript compiledScript = null;
        if (javascriptCache != null) {
            compiledScript = javascriptCache.getScript(authenticationContext.getServiceProviderName());
        }
        try {
            if (compiledScript == null) {
                Compilable compilable = (Compilable) engine;
                //TODO: Think about keeping a cached compiled scripts. May be the last updated timestamp.
                compiledScript = compilable.compile(script);
            }
            Bindings bindings = engine.createBindings();
            bindings.put("executeStep", (Consumer<Map>) this::executeStep);
            bindings.put("sendError", (Consumer<Map>) this::sendError);
            if (jsFunctionRegistrar != null) {
                jsFunctionRegistrar.stream(JsFunctionRegistry.Subsystem.SEQUENCE_HANDLER, entry -> {
                    bindings.put(entry.getKey(), entry.getValue());
                });
            }
            javascriptCache.putBindings(authenticationContext.getServiceProviderName(), bindings);

            JSObject builderFunction = (JSObject) compiledScript.eval(bindings);
            builderFunction.call(null, authenticationContext);

            //Now re-assign the executeStep function to dynamic evaluation
            bindings.put("executeStep", (Consumer<Map>) this::executeStepInAsyncEvent);
        } catch (ScriptException e) {
            //TODO: Find out how to handle script engine errors
            log.error("Error in executing the Javascript.", e);
        }
        return this;
    }

    /**
     * Add authentication fail node to the authentication graph.
     * @param parameterMap
     * TODO: This method works in conditional mode and need to implement separate method for dynamic mode
     */
    public void sendError(Map<String, Object> parameterMap) {

        FailNode newNode = new FailNode();

        if (parameterMap.get("showErrorPage") != null) {
            newNode.setShowErrorPage((boolean)parameterMap.get("showErrorPage"));
        }
        if (parameterMap.get("pageUri") != null) {
            newNode.setErrorPageUri((String) parameterMap.get("pageUri"));
        }

        if (currentNode == null) {
            result.setStartNode(newNode);
        } else {
            attachToLeaf(currentNode, newNode);
        }
    }

    /**
     * Adds the step given by step ID tp the authentication graph.
     *
     * @param parameterMap parameterMap
     */
    public void executeStep(Map<String, Object> parameterMap) {
        //TODO: Use Step Name instead of Step ID (integer)
        StepConfig stepConfig = stepNamedMap.get(parameterMap.get("id"));
        if (stepConfig == null) {
            log.error("Given Authentication Step :" + parameterMap.get("id") + " is not in Environment");
            return;
        }
        StepConfigGraphNode newNode = wrap(stepConfig);
        if (currentNode == null) {
            result.setStartNode(newNode);
        } else {
            attachToLeaf(currentNode, newNode);
        }
        currentNode = newNode;
        attachEventListeners((Map<String, Object>) parameterMap.get("on"));
    }

    /**
     * Adds the step given by step ID tp the authentication graph.
     *
     * @param parameterMap parameterMap
     */
    public void executeStepInAsyncEvent(Map<String, Object> parameterMap) {
        //TODO: Use Step Name instead of Step ID (integer)
        //TODO: can get the context from ThreadLocal. so that javascript does not have context as a parameter.
        AuthenticationContext context = contextForJs.get();
        AuthGraphNode currentNode = dynamicallyBuiltBaseNode.get();

        Object idObj = parameterMap.get("id");
        Integer id = idObj instanceof Integer ? (Integer) idObj : Integer.parseInt(String.valueOf(idObj));
        if (log.isDebugEnabled()) {
            log.debug("Execute Step on async event. Step ID : " + id);
        }
        AuthenticationGraph graph = context.getSequenceConfig().getAuthenticationGraph();
        if (graph == null) {
            log.error("The graph happens to be null on the sequence config. Can not execute step : " + id);
            return;
        }

        StepConfig stepConfig = graph.getStepMap().get(id);
        if (log.isDebugEnabled()) {
            log.debug("Found step for the Step ID : " + id + ", Step Config " + stepConfig);
        }
        StepConfigGraphNode newNode = wrap(stepConfig);
        if (currentNode == null) {
            log.debug("Setting a new node at the first time. Node : " + newNode.getName());
            dynamicallyBuiltBaseNode.set(newNode);
        } else {
            attachToLeaf(currentNode, newNode);
        }

        attachEventListeners((Map<String, Object>) parameterMap.get("on"), newNode);
    }

    private void attachEventListeners(Map<String, Object> eventsMap, AuthGraphNode currentNode) {
        if (eventsMap == null) {
            return;
        }
        DynamicDecisionNode decisionNode = new DynamicDecisionNode();
        eventsMap.entrySet().stream().forEach(e -> {
            decisionNode.addFunction(e.getKey(), generateFunction(e.getValue()));
        });
        if (!decisionNode.getFunctionMap().isEmpty()) {
            attachToLeaf(currentNode, decisionNode);
        }
    }

    private void attachEventListeners(Map<String, Object> eventsMap) {
        if (eventsMap == null) {
            return;
        }
        DynamicDecisionNode decisionNode = new DynamicDecisionNode();
        eventsMap.entrySet().stream().forEach(e -> {
            decisionNode.addFunction(e.getKey(), generateFunction(e.getValue()));
        });
        if (!decisionNode.getFunctionMap().isEmpty()) {
            attachToLeaf(currentNode, decisionNode);
            currentNode = decisionNode;
        }
    }

    private Object generateFunction(Object value) {
        String source = null;
        boolean isFunction = false;
        if (value instanceof ScriptObjectMirror) {
            ScriptObjectMirror scriptObjectMirror = (ScriptObjectMirror) value;
            ScriptFunction scriptFunction = (ScriptFunction) ScriptUtils.unwrap(scriptObjectMirror);
            isFunction = scriptObjectMirror.isFunction();
            source = scriptFunction.toSource();
        } else {
            source = String.valueOf(value);
        }

        JsBasedEvaluator evaluator2 = new JsBasedEvaluator(source, isFunction);
        return evaluator2;
    }

    /**
     * Attach the new node to the destination node.
     * Any immediate branches available in the destination will be re-attached to the new node.
     * New node may be cloned if needed to attach on multiple branches.
     *
     * @param destination
     * @param newNode
     */
    private static void infuse(AuthGraphNode destination, AuthGraphNode newNode) {

        if (destination instanceof StepConfigGraphNode) {
            StepConfigGraphNode stepConfigGraphNode = ((StepConfigGraphNode) destination);
            attachToLeaf(newNode, stepConfigGraphNode.getNext());
            stepConfigGraphNode.setNext(newNode);
        } else if (destination instanceof AuthDecisionPointNode) {
            AuthDecisionPointNode decisionPointNode = (AuthDecisionPointNode) destination;
            decisionPointNode.getOutcomes().stream().forEach(o -> {
                AuthGraphNode clonedNode = clone(newNode);
                attachToLeaf(clonedNode, o.getDestination());
                o.setDestination(clonedNode);
            });
        } else if (destination instanceof DynamicDecisionNode) {
            DynamicDecisionNode dynamicDecisionNode = (DynamicDecisionNode) destination;
            attachToLeaf(newNode, dynamicDecisionNode.getDefaultEdge());
            dynamicDecisionNode.setDefaultEdge(newNode);
        } else {
            log.error("Can not infuse nodes in node type : " + destination);
        }
    }

    private static AuthGraphNode clone(AuthGraphNode node) {
        if (node instanceof StepConfigGraphNode) {
            StepConfigGraphNode stepConfigGraphNode = ((StepConfigGraphNode) node);
            return wrap(stepConfigGraphNode.getStepConfig());
        } else {
            log.error("Clone not implemented for the node type: " + node);
        }
        return null;
    }

    /**
     * Attach the new node to end of the base node.
     * The new node is added to each leaf node of the Tree structure given in the destination node.
     * Effectively this will join all the leaf nodes to new node, converting the tree into a graph.
     *
     * @param baseNode
     * @param nodeToAttach
     */
    private static void attachToLeaf(AuthGraphNode baseNode, AuthGraphNode nodeToAttach) {

        if (baseNode instanceof StepConfigGraphNode) {
            StepConfigGraphNode stepConfigGraphNode = ((StepConfigGraphNode) baseNode);
            if (stepConfigGraphNode.getNext() == null) {
                stepConfigGraphNode.setNext(nodeToAttach);
            } else {
                attachToLeaf(stepConfigGraphNode.getNext(), nodeToAttach);
            }
        } else if (baseNode instanceof AuthDecisionPointNode) {
            AuthDecisionPointNode decisionPointNode = (AuthDecisionPointNode) baseNode;
            if (decisionPointNode.getDefaultEdge() == null) {
                decisionPointNode.setDefaultEdge(nodeToAttach);
            } else {
                attachToLeaf(decisionPointNode.getDefaultEdge(), nodeToAttach);
            }
            decisionPointNode.getOutcomes().stream().forEach(o -> attachToLeaf(o.getDestination(), nodeToAttach));
        } else if (baseNode instanceof DynamicDecisionNode) {
            DynamicDecisionNode dynamicDecisionNode = (DynamicDecisionNode) baseNode;
            dynamicDecisionNode.setDefaultEdge(nodeToAttach);
        } else if (baseNode instanceof EndStep) {
            if (log.isDebugEnabled()) {
                log.debug("The destination is an End Step. Unable to attach the node : " + nodeToAttach);
            }
        } else if (baseNode instanceof FailNode) {
            if (log.isDebugEnabled()) {
                log.debug("The destination is an Fail Step. Unable to attach the node : " + nodeToAttach);
            }
        } else {
            log.error("Unknown graph node found : " + baseNode);
        }
    }

    /**
     * Creates the StepConfigGraphNode with given StepConfig.
     *
     * @param stepConfig Step Config Object.
     * @return built and wrapped new StepConfigGraphNode.
     */
    private static StepConfigGraphNode wrap(StepConfig stepConfig) {

        StepConfigGraphNode stepConfigGraphNode = new StepConfigGraphNode(stepConfig);
        return stepConfigGraphNode;
    }

    /**

     * Javascript based Decision Evaluator implementation.
     * This is used to create the Authentication Graph structure dynamically on the fly while the authentication flow
     * is happening.
     * The graph is re-organized based on last execution of the decision.
     */
    public static class JsBasedEvaluator implements AuthenticationDecisionEvaluator {

        private static final long serialVersionUID = 6853505881096840344L;
        private String source;
        private boolean isFunction = false;

        public JsBasedEvaluator(String source, boolean isFunction) {
            this.source = source;
            this.isFunction = isFunction;
        }

        @Override
        public String evaluate(AuthenticationContext authenticationContext) {
            Bindings bindings = getJavascriptCache().getBindings(authenticationContext.getServiceProviderName());
            String result = null;
            if (isFunction) {
                Compilable compilable = (Compilable) getEngine();
                try {
                    JsGraphBuilder.contextForJs.set(authenticationContext);
                    CompiledScript compiledScript = compilable.compile(source);
                    JSObject builderFunction = (JSObject) compiledScript.eval(bindings);
                    Object scriptResult = builderFunction.call(null, authenticationContext);

                    //TODO: New method ...
                    AuthGraphNode executingNode = (AuthGraphNode) authenticationContext.getProperty(PROP_CURRENT_NODE);
                    if (canInfuse(executingNode)) {
                        infuse(executingNode, dynamicallyBuiltBaseNode.get());
                    }

                } catch (ScriptException e) {
                    //TODO: do proper error handling
                    log.error("Error in executing the javascript for service provider : " + authenticationContext
                            .getServiceProviderName(), e);
                } finally {
                    contextForJs.remove();
                    dynamicallyBuiltBaseNode.remove();
                }

            } else {
                result = source;
            }
            return result;
        }

        private boolean canInfuse(AuthGraphNode executingNode) {
            return executingNode instanceof DynamicDecisionNode && dynamicallyBuiltBaseNode.get() != null;
        }

        private ScriptEngine getEngine() {
            return FrameworkServiceDataHolder.getInstance().getJsGraphBuilderFactory().getEngine();
        }

        private JavascriptCache getJavascriptCache() {
            return FrameworkServiceDataHolder.getInstance().getJsGraphBuilderFactory().getJavascriptCache();
        }
    }

    public void setJsFunctionRegistry(JsFunctionRegistryImpl jsFunctionRegistrar) {
        this.jsFunctionRegistrar = jsFunctionRegistrar;
    }

    public void setJavascriptCache(JavascriptCache javascriptCache) {
        this.javascriptCache = javascriptCache;
    }
}
