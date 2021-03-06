/*
 * Copyright (c) 2020, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ballerinalang.debugadapter.evaluation.engine;

import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Value;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.FunctionTypeSymbol;
import io.ballerina.compiler.api.symbols.MethodSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.syntax.tree.MethodCallExpressionNode;
import org.ballerinalang.debugadapter.SuspendedContext;
import org.ballerinalang.debugadapter.evaluation.BExpressionValue;
import org.ballerinalang.debugadapter.evaluation.EvaluationException;
import org.ballerinalang.debugadapter.evaluation.EvaluationExceptionKind;
import org.ballerinalang.debugadapter.variable.BVariable;
import org.ballerinalang.debugadapter.variable.BVariableType;
import org.ballerinalang.debugadapter.variable.VariableFactory;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.ballerinalang.debugadapter.evaluation.engine.FunctionInvocationExpressionEvaluator.modifyName;
import static org.ballerinalang.debugadapter.evaluation.engine.InvocationArgProcessor.generateNamedArgs;
import static org.ballerinalang.debugadapter.evaluation.utils.LangLibUtils.LANG_LIB_PACKAGE_PREFIX;
import static org.ballerinalang.debugadapter.evaluation.utils.LangLibUtils.LANG_LIB_VALUE;
import static org.ballerinalang.debugadapter.evaluation.utils.LangLibUtils.getAssociatedLangLibName;
import static org.ballerinalang.debugadapter.evaluation.utils.LangLibUtils.getLangLibDefinition;
import static org.ballerinalang.debugadapter.evaluation.utils.LangLibUtils.getLangLibFunctionDefinition;
import static org.ballerinalang.debugadapter.evaluation.utils.LangLibUtils.getQualifiedLangLibClassName;
import static org.ballerinalang.debugadapter.evaluation.utils.LangLibUtils.loadLangLibMethod;

/**
 * Evaluator implementation for method call invocation expressions.
 *
 * @since 2.0.0
 */
public class MethodCallExpressionEvaluator extends Evaluator {

    private final MethodCallExpressionNode syntaxNode;
    private final String methodName;
    private final Evaluator objectExpressionEvaluator;
    private final List<Map.Entry<String, Evaluator>> argEvaluators;

    public MethodCallExpressionEvaluator(SuspendedContext context, MethodCallExpressionNode methodCallExpressionNode,
                                         Evaluator expression, List<Map.Entry<String, Evaluator>> argEvaluators) {
        super(context);
        this.syntaxNode = methodCallExpressionNode;
        this.objectExpressionEvaluator = expression;
        this.argEvaluators = argEvaluators;
        this.methodName = syntaxNode.methodName().toSourceCode().trim();
    }

    @Override
    public BExpressionValue evaluate() throws EvaluationException {
        try {
            // If the static type of expression is a subtype of object, and the object type includes a method named
            // method-name, then the method-call-expr is executed by calling that method on v.
            //
            // Otherwise, the method-call-expr will be turned into a call to a function in the lang library M,
            // where M is selected as follows.
            //  - If the static type of expression is a subtype of some basic type with identifier B, and the module
            //      lang.B contains a function method-name then M is B. The identifier for a basic type is the reserved
            //      identifier used in type descriptors for subtypes of that basic type, as listed in the Lang library
            //      section.
            //  - Otherwise, if the static type of expression is xml:Text and the module lang.string contains a
            //      function method-name, then M is string, and the result of evaluating expression is implicitly
            //      converted to a string before the function is called.
            //  - Otherwise, M is value.

            Value invocationResult = null;
            BExpressionValue result = objectExpressionEvaluator.evaluate();
            BVariable resultVar = VariableFactory.getVariable(context, result.getJdiValue());

            // If the expression result is an object, try invoking as an object method invocation.
            if (result.getType() == BVariableType.OBJECT) {
                invocationResult = invokeObjectMethod(resultVar);
            }

            // Otherwise, try matching lang-lib methods.
            if (invocationResult == null) {
                invocationResult = invokeLangLibMethod(result);
            }

            return new BExpressionValue(context, invocationResult);
        } catch (EvaluationException e) {
            throw e;
        } catch (Exception e) {
            throw new EvaluationException(String.format(EvaluationExceptionKind.INTERNAL_ERROR.getString(),
                    syntaxNode.toSourceCode().trim()));
        }
    }

    private Value invokeObjectMethod(BVariable resultVar) throws EvaluationException {
        boolean isFoundObjectMethod = false;
        try {
            String className = resultVar.getDapVariable().getValue();
            Optional<ClassSymbol> classDef = findClassDefWithinModule(className);
            if (classDef.isEmpty()) {
                throw new EvaluationException(String.format(EvaluationExceptionKind.CLASS_NOT_FOUND.getString(),
                        className));
            }

            Optional<MethodSymbol> objectMethodDef = findObjectMethodInClass(classDef.get(), methodName);
            if (objectMethodDef.isEmpty()) {
                throw new EvaluationException(
                        String.format(EvaluationExceptionKind.OBJECT_METHOD_NOT_FOUND.getString(),
                                syntaxNode.methodName().toString().trim(), className));
            }

            isFoundObjectMethod = true;
            GeneratedInstanceMethod objectMethod = getObjectMethodByName(resultVar, methodName);
            objectMethod.setNamedArgValues(generateNamedArgs(context, methodName, objectMethodDef.get().
                    typeDescriptor(), argEvaluators));
            return objectMethod.invoke();
        } catch (EvaluationException e) {
            // If the object method is not found, we have to ignore the Evaluation Exception and try find any
            // matching lang library functions.
            if (isFoundObjectMethod) {
                throw e;
            }
        }
        return null;
    }

    private Value invokeLangLibMethod(BExpressionValue resultVar) throws EvaluationException {

        FunctionSymbol langLibFunctionDef = null;
        GeneratedStaticMethod langLibMethod = null;

        // Tries to use the dedicated lang library functions.
        String langLibName = getAssociatedLangLibName(resultVar.getType());
        Optional<ModuleSymbol> langLibDef = getLangLibDefinition(context, langLibName);
        if (langLibDef.isPresent()) {
            Optional<FunctionSymbol> functionDef = getLangLibFunctionDefinition(context, langLibDef.get(), methodName);
            if (functionDef.isPresent()) {
                String langLibCls = getQualifiedLangLibClassName(langLibDef.get(), langLibName);
                langLibFunctionDef = functionDef.get();
                langLibMethod = loadLangLibMethod(context, resultVar, langLibCls, methodName);
            }
        }

        // Tries to use "value" lang library functions.
        if (langLibMethod == null) {
            Optional<ModuleSymbol> valueLibDef = getLangLibDefinition(context, LANG_LIB_VALUE);
            if (valueLibDef.isEmpty()) {
                throw new EvaluationException(String.format(EvaluationExceptionKind.LANG_LIB_NOT_FOUND.getString(),
                        LANG_LIB_PACKAGE_PREFIX + langLibName + ", " + LANG_LIB_PACKAGE_PREFIX + LANG_LIB_VALUE));
            }

            Optional<FunctionSymbol> functionDef = getLangLibFunctionDefinition(context, valueLibDef.get(), methodName);
            if (functionDef.isEmpty()) {
                throw new EvaluationException(String.format(EvaluationExceptionKind.LANG_LIB_METHOD_NOT_FOUND.
                        getString(), methodName, langLibName));
            }

            String langLibCls = getQualifiedLangLibClassName(valueLibDef.get(), LANG_LIB_VALUE);
            langLibFunctionDef = functionDef.get();
            langLibMethod = loadLangLibMethod(context, resultVar, langLibCls, methodName);
        }

        argEvaluators.add(0, new AbstractMap.SimpleEntry<>("", objectExpressionEvaluator));
        FunctionTypeSymbol functionTypeDesc = langLibFunctionDef.typeDescriptor();
        Map<String, Value> argValueMap = generateNamedArgs(context, methodName, functionTypeDesc, argEvaluators);
        langLibMethod.setNamedArgValues(argValueMap);
        return langLibMethod.invoke();
    }

    private Optional<ClassSymbol> findClassDefWithinModule(String className) {
        SemanticModel semanticContext = context.getDebugCompiler().getSemanticInfo();
        return semanticContext.moduleSymbols()
                .stream()
                .filter(symbol -> symbol.kind() == SymbolKind.CLASS && modifyName(symbol.name()).equals(className))
                .findFirst()
                .map(symbol -> (ClassSymbol) symbol);
    }

    private Optional<MethodSymbol> findObjectMethodInClass(ClassSymbol classDef, String methodName) {
        return classDef.methods().values()
                .stream()
                .filter(methodSymbol -> modifyName(methodSymbol.name()).equals(methodName))
                .findFirst();
    }

    private GeneratedInstanceMethod getObjectMethodByName(BVariable objectVar, String methodName)
            throws EvaluationException {

        ReferenceType objectRef = ((ObjectReference) objectVar.getJvmValue()).referenceType();
        List<Method> methods = objectRef.methodsByName(methodName);
        if (methods == null || methods.size() != 1) {
            throw new EvaluationException(String.format(EvaluationExceptionKind.OBJECT_METHOD_NOT_FOUND.getString(),
                    syntaxNode.methodName().toString().trim(), objectVar.computeValue()));
        }
        return new GeneratedInstanceMethod(context, objectVar.getJvmValue(), methods.get(0));
    }
}
