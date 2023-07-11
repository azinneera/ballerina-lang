/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
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

package org.wso2.ballerinalang.compiler.bir.codegen.optimizer;

import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.model.symbols.SymbolOrigin;
import org.wso2.ballerinalang.compiler.bir.BIRGenUtils;
import org.wso2.ballerinalang.compiler.bir.codegen.interop.JMethodCallInstruction;
import org.wso2.ballerinalang.compiler.bir.emit.BIREmitter;
import org.wso2.ballerinalang.compiler.bir.model.BIRAbstractInstruction;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRBasicBlock;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRErrorEntry;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRFunction;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRFunctionParameter;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRPackage;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRParameter;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRTypeDefinition;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRVariableDcl;
import org.wso2.ballerinalang.compiler.bir.model.BIRNonTerminator;
import org.wso2.ballerinalang.compiler.bir.model.BIROperand;
import org.wso2.ballerinalang.compiler.bir.model.BIRTerminator;
import org.wso2.ballerinalang.compiler.bir.model.BirScope;
import org.wso2.ballerinalang.compiler.bir.model.InstructionKind;
import org.wso2.ballerinalang.compiler.bir.model.VarKind;
import org.wso2.ballerinalang.compiler.bir.model.VarScope;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolTable;
import org.wso2.ballerinalang.compiler.semantics.model.types.BInvokableType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BUnionType;
import org.wso2.ballerinalang.compiler.util.Name;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.wso2.ballerinalang.compiler.bir.BIRGenUtils.rearrangeBasicBlocks;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.RUNTIME_UTILS;

/**
 * Split large BIR functions into smaller methods.
 *
 * @since 2.0
 */
public class LargeMethodOptimizer {

    private static final Name DEFAULT_WORKER_NAME = new Name("function");
    private final SymbolTable symbolTable;
    // splits are done only if the original function has more instructions than the below number
    private static final int FUNCTION_INSTRUCTION_COUNT_THRESHOLD = 2;
    // splits are done only if the newly created method will contain more instructions than the below number
    private static final int SPLIT_INSTRUCTION_COUNT_THRESHOLD = 2;
    // splits are done only if the newly created method will have less function arguments than the below number
    private static final int MAX_SPLIT_FUNCTION_ARG_COUNT = 250;
    // total least no. of terminators and non-terminators that should be there to make the split
    private static final int INS_COUNT_PERIODIC_SPLIT_THRESHOLD = 3; // later can be 1000
    // current BIR package id
    private PackageID currentPackageId;
    // newly created split BIR function number
    private int splitFuncNum;
    // newly created temporary variable number to handle split function return value
    private int splitTempVarNum;

    public LargeMethodOptimizer(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    public void splitLargeBIRFunctions(BIRPackage birPkg) {
        currentPackageId = birPkg.packageID;
        splitFuncNum = 0;
        splitTempVarNum = 0;

        List<BIRFunction> newlyAddedBIRFunctions = new ArrayList<>();
        for (BIRFunction function : birPkg.functions) {
            if (hasLessInstructionCount(function)) {
                continue;
            }
            List<BIRFunction> newBIRFunctions = splitBIRFunction(function, false, false, false);
            newlyAddedBIRFunctions.addAll(newBIRFunctions);
        }
        for (BIRTypeDefinition birTypeDef : birPkg.typeDefs) {
            for (BIRFunction function : birTypeDef.attachedFuncs) {
                if (hasLessInstructionCount(function)) {
                    continue;
                }
                List<BIRFunction> newBIRFunctions = splitBIRFunction(function, true, false, false);
                newlyAddedBIRFunctions.addAll(newBIRFunctions);
            }
        }
        birPkg.functions.addAll(newlyAddedBIRFunctions);
    }

    private boolean hasLessInstructionCount(BIRFunction birFunction) {
        int instructionCount = 0;
        for (BIRBasicBlock basicBlock : birFunction.basicBlocks) {
            instructionCount += basicBlock.instructions.size();
        }
        return instructionCount < FUNCTION_INSTRUCTION_COUNT_THRESHOLD;
    }

    private List<BIRFunction> splitBIRFunction(BIRFunction birFunction, boolean fromAttachedFunction,
                                               boolean fromSplitFunction, boolean splitTypeArray) {
        final List<BIRFunction> newlyAddingFunctions = new ArrayList<>();
        List<Split> possibleSplits = getPossibleSplits(birFunction.basicBlocks, birFunction.errorTable,
                birFunction.name.toString());
        if (!possibleSplits.isEmpty()) {
            generateSplits(birFunction, possibleSplits, newlyAddingFunctions, fromAttachedFunction);
        } else if (fromSplitFunction) {
            periodicSplitFunction(birFunction, newlyAddingFunctions, fromAttachedFunction, splitTypeArray);
        }
        return newlyAddingFunctions;
    }

    private void periodicSplitFunction(BIRFunction parentFunc, List<BIRFunction> newlyAddingFunctions,
                                       boolean fromAttachedFunction, boolean splitTypeArray) {
        if (!splitTypeArray) {
            return;
        }

        if (BIREmitter.dumbBIR) {
            System.out.println("----split_func_start----");
            System.out.println(BIREmitter.emitFunction(parentFunc, 0));
            System.out.println("----split_func_end----");
        }

        List<BIRBasicBlock> bbs = parentFunc.basicBlocks;
        int newArrayInsBBNum = bbs.size() - 2;
        int newArrayInsNumInRelevantBB = bbs.get(newArrayInsBBNum).instructions.size() - 1;
        BIRNonTerminator.NewArray arrayIns = (BIRNonTerminator.NewArray) bbs.get(newArrayInsBBNum).instructions
                .get(newArrayInsNumInRelevantBB);

        // creating necessary temp variables
        TempVarsForArraySplit parentFuncTempVars = getTempVarsForArraySplit();
        ParentFuncEnv parentFuncEnv = new ParentFuncEnv(parentFuncTempVars, bbs.get(newArrayInsBBNum + 1));
        BIRBasicBlock parentFuncStartBB = parentFuncEnv.parentFuncNewBB;
        SplitFuncEnv splitFuncEnv = new SplitFuncEnv(getTempVarsForArraySplit(), fromAttachedFunction);

        parentFuncEnv.returnOperand = arrayIns.lhsOp;
        parentFuncEnv.returnVarDcl = arrayIns.lhsOp.variableDcl;

        BIRVariableDcl handleArray = new BIRVariableDcl(null, symbolTable.handleType, new Name("%splitArray"),
                VarScope.FUNCTION, VarKind.TEMP, null);
        BIROperand handleArrayOperand = new BIROperand(handleArray);

        createAndAddNewHandleArrayForLargeArrayIns(parentFuncEnv, arrayIns, handleArray, handleArrayOperand);

        BIRNonTerminator.NewLargeArray newLargeArrayIns = new BIRNonTerminator.NewLargeArray(arrayIns.pos,
                arrayIns.type, arrayIns.lhsOp, arrayIns.typedescOp, arrayIns.sizeOp, handleArrayOperand);

        // populating ListConstructorEntry array elements at runtime using jMethodCalls
        // creating method calls
        Map<BIROperand, BIRListConstructorEntryWithIndex> birOperands =  new HashMap<>();
        Set<BIROperand> arrayValuesOperands = new HashSet<>();
        List<BIRNonTerminator> globalAndArgVarIns = getGlobalAndArgVarIns(parentFuncTempVars, arrayIns,
                handleArrayOperand, birOperands, arrayValuesOperands);

        // add the constant load array size operand instruction
        parentFuncEnv.parentFuncNewInsList.add(bbs.get(0).instructions.get(0));
        parentFuncEnv.parentFuncLocalVarList.add(bbs.get(0).instructions.get(0).lhsOp.variableDcl);

        splitParentFuncForPeriodicSplits(parentFunc, newlyAddingFunctions, fromAttachedFunction,
                bbs, newArrayInsBBNum, newArrayInsNumInRelevantBB,
                handleArray, handleArrayOperand, birOperands, splitFuncEnv, parentFuncEnv, arrayValuesOperands);

        parentFuncEnv.parentFuncNewInsList.addAll(globalAndArgVarIns);
        parentFuncEnv.parentFuncNewInsList.add(newLargeArrayIns);
        parentFuncEnv.parentFuncNewBB.instructions = parentFuncEnv.parentFuncNewInsList;
        parentFuncEnv.parentFuncNewBB.terminator = bbs.get(newArrayInsBBNum).terminator;
        parentFuncEnv.parentFuncNewBBList.add(parentFuncEnv.parentFuncNewBB);
        BIRBasicBlock parentFuncExitBB = bbs.get(newArrayInsBBNum + 1);
        BIRGenUtils.renumberBasicBlock(parentFuncEnv.parentFuncBBId++, parentFuncExitBB);
        parentFuncEnv.parentFuncNewBBList.add(parentFuncExitBB);
        parentFunc.basicBlocks = parentFuncEnv.parentFuncNewBBList;
        parentFunc.errorTable = new ArrayList<>();

        parentFunc.localVars = new ArrayList<>();
        parentFunc.localVars.add(parentFunc.returnVariable);
        parentFunc.localVars.addAll(parentFunc.parameters);
        parentFunc.localVars.addAll(parentFuncEnv.parentFuncLocalVarList);
        if (parentFuncTempVars.tempVarsUsed) {
            parentFunc.localVars.add(parentFuncTempVars.arrayIndexVarDcl);
            parentFunc.localVars.add(parentFuncTempVars.typeCastVarDcl);
        }
        for (BIRVariableDcl localVar : parentFunc.localVars) {
            if (localVar.kind == VarKind.LOCAL) {
                if (localVar.startBB != null) {
                    localVar.startBB = parentFuncStartBB;
                }
                if (localVar.endBB != null) {
                    localVar.endBB = parentFuncExitBB;
                }
            }
        }
    }

    private List<BIRNonTerminator> getGlobalAndArgVarIns(TempVarsForArraySplit parentFuncTempVars,
                                                         BIRNonTerminator.NewArray arrayIns,
                                                         BIROperand handleArrayOperand,
                                                         Map<BIROperand, BIRListConstructorEntryWithIndex>
                                                                 birOperands, Set<BIROperand> arrayValuesOperands) {
        List<BIRNonTerminator> globalAndArgVarIns = new ArrayList<>();
        int arrIndex = 0;
        for (BIRNode.BIRListConstructorEntry value : arrayIns.values) {
            BIRVariableDcl arrElementVarDcl = value.exprOp.variableDcl;
            if (isTempOrSyntheticVar(arrElementVarDcl)) {
                birOperands.put(value.exprOp, new BIRListConstructorEntryWithIndex(value, arrIndex));
                arrayValuesOperands.add(value.exprOp);
            } else {
                setArrayElement(globalAndArgVarIns, handleArrayOperand, value.exprOp, value, arrIndex,
                        parentFuncTempVars);
            }
            arrIndex++;
        }
        return globalAndArgVarIns;
    }

    private void createAndAddNewHandleArrayForLargeArrayIns(ParentFuncEnv parentFuncEnv,
                                                            BIRNonTerminator.NewArray arrayIns,
                                                            BIRVariableDcl handleArray, BIROperand handleArrayOperand) {
        BIRVariableDcl arraySizeVarDcl = new BIRVariableDcl(null, symbolTable.intType, new Name("%splitArraySize"),
                VarScope.FUNCTION, VarKind.TEMP, null);
        parentFuncEnv.parentFuncLocalVarList.add(arraySizeVarDcl);
        BIROperand arraySizeOperand = new BIROperand(arraySizeVarDcl);

        parentFuncEnv.parentFuncLocalVarList.add(handleArray);

        JMethodCallInstruction callHandleArray = new JMethodCallInstruction(null);
        callHandleArray.lhsOp = handleArrayOperand;
        callHandleArray.invocationType = INVOKESTATIC;
        callHandleArray.jClassName = RUNTIME_UTILS;
        callHandleArray.jMethodVMSig = "(J)Lio/ballerina/runtime/internal/values/HandleValue;";
        callHandleArray.name = "getListInitialValueEntryArray";
        callHandleArray.args = new ArrayList<>(Arrays.asList(arraySizeOperand));

        BIRNonTerminator.ConstantLoad loadArraySize = new BIRNonTerminator.ConstantLoad(null,
                (long) arrayIns.values.size(), symbolTable.intType, arraySizeOperand);
        parentFuncEnv.parentFuncNewInsList.add(loadArraySize);
        parentFuncEnv.parentFuncNewInsList.add(callHandleArray);
    }

    private Map<BIRAbstractInstruction, List<BIROperand>> getSplitPoints(List<BIRBasicBlock> bbs,
                                                                   Set<BIROperand> arrayValuesOperands) {
        Map<BIRAbstractInstruction, List<BIROperand>> insSplitPoints = new HashMap<>();
        for (int bbIndex = bbs.size() - 2; bbIndex >= 0; bbIndex--) {
            BIRBasicBlock bb = bbs.get(bbIndex);
            List<BIRNonTerminator> bbInstructions = bb.instructions;
            int lastInsNum;
            if (bbIndex == bbs.size() - 2) {
                lastInsNum = bbInstructions.size() - 2;
            } else {
                lastInsNum = bbInstructions.size() - 1;
                populateInsSplitPoints(arrayValuesOperands, insSplitPoints, bb.terminator);
            }
            for (int insIndex = lastInsNum; insIndex >= 0; insIndex--) {
                populateInsSplitPoints(arrayValuesOperands, insSplitPoints, bbInstructions.get(insIndex));
            }
        }
        return insSplitPoints;
    }

    private void populateInsSplitPoints(Set<BIROperand> arrayValuesOperands,
                                        Map<BIRAbstractInstruction, List<BIROperand>> insSplitPoints,
                                        BIRAbstractInstruction ins) {
        BIROperand insLhsOp = ins.lhsOp;
        if (insLhsOp != null && arrayValuesOperands.contains(insLhsOp)) {
            addOperandToInsSplitPoints(arrayValuesOperands, insSplitPoints, ins, insLhsOp);
        }
        for (BIROperand rhsOperand : ins.getRhsOperands()) {
            if (arrayValuesOperands.contains(rhsOperand)) {
                addOperandToInsSplitPoints(arrayValuesOperands, insSplitPoints, ins, rhsOperand);
            }
        }
    }

    private void addOperandToInsSplitPoints(Set<BIROperand> arrayValuesOperands,
                                            Map<BIRAbstractInstruction, List<BIROperand>> insSplitPoints,
                                            BIRAbstractInstruction ins, BIROperand insLhsOp) {
        arrayValuesOperands.remove(insLhsOp);
        if (insSplitPoints.containsKey(ins)) {
            List<BIROperand> operandList = insSplitPoints.get(ins);
            operandList.add(insLhsOp);
        } else {
            List<BIROperand> operandList = new ArrayList<>();
            operandList.add(insLhsOp);
            insSplitPoints.put(ins, operandList);
        }
    }

    private void splitParentFuncForPeriodicSplits(BIRFunction parentFunc, List<BIRFunction> newlyAddingFunctions,
                                                  boolean fromAttachedFunction,
                                                  List<BIRBasicBlock> bbs,
                                                  int newArrayInsBBNum, int newArrayInsNumInRelevantBB,
                                                  BIRVariableDcl handleArray, BIROperand handleArrayOperand,
                                                  Map<BIROperand, BIRListConstructorEntryWithIndex> birOperands,
                                                  SplitFuncEnv splitFuncEnv,
                                                  ParentFuncEnv parentFuncEnv, Set<BIROperand> arrayValuesOperands) {
        Map<BIRAbstractInstruction, List<BIROperand>> insSplitPoints = getSplitPoints(bbs, arrayValuesOperands);
        boolean splitNow;
        for (int bbIndex = 0; bbIndex < bbs.size() - 1; bbIndex++) {
            BIRBasicBlock bb = bbs.get(bbIndex);
            if (!splitFuncEnv.splitOkay && splitFuncEnv.doNotSplitTillThisBBNum == bb.number) {
                splitFuncEnv.splitOkay = true;
                splitFuncEnv.periodicSplitInsCount -= 1;
            }
            splitFuncEnv.splitFuncChangedBBs.put(bb.number, splitFuncEnv.splitFuncBB);
            for (int insIndex = 0; insIndex < bb.instructions.size(); insIndex++) {
                if (bbIndex == 0 && insIndex == 0) { // array size ins
                    continue;
                } else if ((bbIndex == newArrayInsBBNum) && (insIndex == newArrayInsNumInRelevantBB)) {
                    createNewFuncForPeriodicSplit(parentFunc, newlyAddingFunctions, fromAttachedFunction,
                            handleArray, splitFuncEnv, parentFuncEnv, bb,
                            bb.instructions.get(newArrayInsNumInRelevantBB));
                    return;
                }

                BIRNonTerminator bbIns = bb.instructions.get(insIndex);
                if (!splitFuncEnv.returnValAssigned && bbIns.lhsOp != null
                        && bbIns.lhsOp.variableDcl.kind == VarKind.RETURN) {
                    splitFuncEnv.returnValAssigned = true;
                    // when the return val is assigned, there are no more ins in the BB
                    // and the terminator is of kind GOTO to the return BB
                    bb.terminator = new BIRTerminator.GOTO(bb.terminator.pos, splitFuncEnv.returnBB,
                            bb.terminator.scope);
                    splitFuncEnv.splitFuncCorrectTerminatorBBs.add(splitFuncEnv.splitFuncBB);
                }
                splitFuncEnv.splitFuncNewInsList.add(bbIns);
                splitFuncEnv.periodicSplitInsCount++;
                populateSplitFuncArgsAndLocalVarList(splitFuncEnv, bbIns);
                splitNow = setArrayElementGivenOperand(birOperands, splitFuncEnv.splitFuncNewInsList,
                        handleArrayOperand, splitFuncEnv.splitFuncTempVars, insSplitPoints, bbIns);

                // create split func if needed
                if (splitFuncEnv.periodicSplitInsCount > INS_COUNT_PERIODIC_SPLIT_THRESHOLD && splitFuncEnv.splitOkay
                        && splitNow) {
                    createNewFuncForPeriodicSplit(parentFunc, newlyAddingFunctions, fromAttachedFunction,
                            handleArray, splitFuncEnv, parentFuncEnv, bb, bbIns);
                }
            }

            splitFuncEnv.splitFuncBB.instructions = splitFuncEnv.splitFuncNewInsList;
            splitFuncEnv.splitFuncBB.terminator = bb.terminator;
            splitFuncEnv.splitFuncNewBBList.add(splitFuncEnv.splitFuncBB);
            splitFuncEnv.splitFuncNewInsList =  new ArrayList<>();

//            bb.instructions = parentFuncNewInsList;  // not needed these two
//            parentFuncBBId = renumberBasicBlock(parentFuncBBId, bb);
//            parentFuncNewBBList.add(bb);
//            parentFuncNewInsList = new ArrayList<>();

            // next consider lhs op in BIR terminator, branch terms won't have array element as lhs op
            // if we found a lhs op, we need to create a new BB and change the terminator

            // now these will be in the split funcs

            populateSplitFuncArgsAndLocalVarList(splitFuncEnv, bb.terminator);

            splitFuncEnv.periodicSplitInsCount++;

            List<BIRNonTerminator> newBBInsList = new ArrayList<>();
            splitNow = setArrayElementGivenOperand(birOperands, newBBInsList, handleArrayOperand,
                    splitFuncEnv.splitFuncTempVars, insSplitPoints, bb.terminator);
            if (splitNow) {
                splitFuncEnv.splitFuncCorrectTerminatorBBs.add(splitFuncEnv.splitFuncBB);
                BIRBasicBlock newBB = new BIRBasicBlock(splitFuncEnv.splitFuncBBId++);
                splitFuncEnv.splitFuncBB = new BIRBasicBlock(splitFuncEnv.splitFuncBBId++);
                newBB.instructions.addAll(newBBInsList);
                newBB.terminator = new BIRTerminator.GOTO(null, splitFuncEnv.splitFuncBB, bb.terminator.scope);
                splitFuncEnv.splitFuncCorrectTerminatorBBs.add(newBB);
                bb.terminator.thenBB = newBB; // change the current BB's terminator to new BB where ins are put
                splitFuncEnv.splitFuncNewBBList.add(newBB);
            } else {
                splitFuncEnv.splitFuncBB = new BIRBasicBlock(splitFuncEnv.splitFuncBBId++);
            }

            if (bb.terminator.kind == InstructionKind.BRANCH) {
                splitFuncEnv.splitOkay = false;
                BIRTerminator.Branch branch = (BIRTerminator.Branch) bb.terminator;
                splitFuncEnv.doNotSplitTillThisBBNum = Math.max(branch.trueBB.number, branch.falseBB.number);
            }

            if (splitFuncEnv.periodicSplitInsCount > INS_COUNT_PERIODIC_SPLIT_THRESHOLD && splitFuncEnv.splitOkay
                    && splitNow) {
                createNewFuncForPeriodicSplit(parentFunc, newlyAddingFunctions, fromAttachedFunction,
                        handleArray, splitFuncEnv, parentFuncEnv, bb, bb.terminator);
            }
        }
    }

    private void createNewFuncForPeriodicSplit(BIRFunction parentFunc, List<BIRFunction> newlyAddingFunctions,
                                               boolean fromAttachedFunction, BIRVariableDcl handleArray,
                                               SplitFuncEnv splitFuncEnv, ParentFuncEnv parentFuncEnv,
                                               BIRBasicBlock bb, BIRAbstractInstruction bbIns) {
        splitFuncEnv.periodicSplitInsCount = 0;

        if (splitFuncEnv.splitFuncTempVars.tempVarsUsed) {
            splitFuncEnv.splitFuncArgs.add(handleArray);
        }

        //create new spilt BIRFunction
        List<BType> paramTypes = new ArrayList<>();
        for (BIRVariableDcl funcArg : splitFuncEnv.splitFuncArgs) {
            paramTypes.add(funcArg.type);
        }
        BType funcRetType = splitFuncEnv.returnValAssigned ? symbolTable.errorOrNilType : symbolTable.nilType;
        BInvokableType type =  new BInvokableType(paramTypes, funcRetType, null);
        splitFuncNum += 1;
        String splitFuncName = "$split$method$_" + splitFuncNum;
        Name newFuncName = new Name(splitFuncName);
        BIRFunction splitBirFunc = new BIRFunction(parentFunc.pos, newFuncName, newFuncName, 0, type,
                DEFAULT_WORKER_NAME, 0, SymbolOrigin.VIRTUAL);

        //---copied below
        List<BIRFunctionParameter> functionParams = new ArrayList<>();
        for (BIRVariableDcl funcArg : splitFuncEnv.splitFuncArgs) {
            Name argName = funcArg.name;
            splitBirFunc.requiredParams.add(new BIRParameter(bbIns.pos, argName, 0));
            BIRFunctionParameter funcParameter = new BIRFunctionParameter(bbIns.pos, funcArg.type, argName,
                    VarScope.FUNCTION, VarKind.ARG, argName.getValue(), false, false);
            functionParams.add(funcParameter);
            splitBirFunc.parameters.add(funcParameter);
        }
        splitBirFunc.argsCount = splitFuncEnv.splitFuncArgs.size();
        splitBirFunc.returnVariable = splitFuncEnv.returnVarDcl;
        splitFuncEnv.returnVarDcl.type = funcRetType;
        BIROperand returnValOperand = splitFuncEnv.returnOperand;
        splitBirFunc.localVars.add(0, splitBirFunc.returnVariable);
        splitBirFunc.localVars.addAll(functionParams);
        splitBirFunc.localVars.addAll(splitFuncEnv.splitFuncLocalVarList);

        if (splitFuncEnv.splitFuncTempVars.tempVarsUsed) {
            splitBirFunc.localVars.add(splitFuncEnv.splitFuncTempVars.arrayIndexVarDcl);
            splitBirFunc.localVars.add(splitFuncEnv.splitFuncTempVars.typeCastVarDcl);
        }

        while (parentFuncEnv.errorTableIndex < parentFunc.errorTable.size()
                && parentFunc.errorTable.get(parentFuncEnv.errorTableIndex).trapBB.number <= bb.number) {
            splitFuncEnv.splitFuncErrorTable.add(parentFunc.errorTable.get(parentFuncEnv.errorTableIndex));
            parentFuncEnv.errorTableIndex++;
        }
        splitBirFunc.errorTable = splitFuncEnv.splitFuncErrorTable;
        // --copied above

        // need to add BBs and () return var assignment and return BB
        BIRNonTerminator.ConstantLoad constantLoad = new BIRNonTerminator.ConstantLoad(bbIns.pos,
                new Name("()"), symbolTable.nilType, returnValOperand);
        splitFuncEnv.splitFuncNewInsList.add(constantLoad);
        splitFuncEnv.splitFuncBB.instructions.addAll(splitFuncEnv.splitFuncNewInsList);

        BIRBasicBlock exitBB = splitFuncEnv.returnBB;
        splitFuncEnv.splitFuncBBId = BIRGenUtils.renumberBasicBlock(splitFuncEnv.splitFuncBBId, exitBB);
        exitBB.terminator = new BIRTerminator.Return(null);
        splitFuncEnv.splitFuncBB.terminator = new BIRTerminator.GOTO(null, exitBB, bbIns.scope);
        splitFuncEnv.splitFuncCorrectTerminatorBBs.add(splitFuncEnv.splitFuncBB);
        splitFuncEnv.splitFuncNewBBList.add(splitFuncEnv.splitFuncBB);
        splitFuncEnv.splitFuncNewBBList.add(exitBB);
        splitBirFunc.basicBlocks = splitFuncEnv.splitFuncNewBBList;
        newlyAddingFunctions.add(splitBirFunc);

        fixTerminatorBBsInPeriodicSplitFunc(splitFuncEnv, splitBirFunc);
        fixErrorTableInPeriodicSplitFunc(splitFuncEnv, splitBirFunc);
        fixLocalVarStartAndEndBBsInPeriodicSplitFunc(splitFuncEnv, splitBirFunc);


        // now we need to call this function from parent func
        List<BIROperand> args = new ArrayList<>();
        for (BIRVariableDcl funcArg : splitFuncEnv.splitFuncArgs) {
            args.add(new BIROperand(funcArg));
        }

        BIRBasicBlock parentFuncNextBB = new BIRBasicBlock(parentFuncEnv.parentFuncBBId++);
        parentFuncEnv.parentFuncNewBB.instructions = parentFuncEnv.parentFuncNewInsList;

        BIROperand splitFuncCallResultOp = null;
        if (splitFuncEnv.returnValAssigned) {
            splitFuncCallResultOp = generateTempLocalVariable(symbolTable.errorOrNilType,
                    parentFuncEnv.parentFuncLocalVarList);
        }
        parentFuncEnv.parentFuncNewBB.terminator = new BIRTerminator.Call(bbIns.pos, InstructionKind.CALL,
                false, currentPackageId, newFuncName, args, splitFuncCallResultOp, parentFuncNextBB,
                Collections.emptyList(), Collections.emptySet(), bbIns.scope);

        parentFuncEnv.parentFuncNewInsList = new ArrayList<>();
        parentFuncEnv.parentFuncNewBBList.add(parentFuncEnv.parentFuncNewBB);
        parentFuncEnv.parentFuncNewBB = parentFuncNextBB;

        if (splitFuncEnv.returnValAssigned) {
            BIROperand isErrorResultOp = generateTempLocalVariable(symbolTable.booleanType,
                    parentFuncEnv.parentFuncLocalVarList);
            BIRNonTerminator errorTestIns = new BIRNonTerminator.TypeTest(null, symbolTable.errorType,
                    isErrorResultOp, splitFuncCallResultOp);
            parentFuncEnv.parentFuncNewInsList.add(errorTestIns);
            parentFuncEnv.parentFuncNewBB.instructions = parentFuncEnv.parentFuncNewInsList;
            BIRBasicBlock trueBB = new BIRBasicBlock(parentFuncEnv.parentFuncBBId++);
            BIRBasicBlock falseBB = new BIRBasicBlock(parentFuncEnv.parentFuncBBId++);
            parentFuncEnv.parentFuncNewBB.terminator = new BIRTerminator.Branch(null, isErrorResultOp, trueBB,
                    falseBB, bbIns.scope);
            parentFuncEnv.parentFuncNewBBList.add(parentFuncEnv.parentFuncNewBB);
            BIROperand castedErrorOp = generateTempLocalVariable(symbolTable.errorType,
                    parentFuncEnv.parentFuncLocalVarList);
            BIRNonTerminator typeCastErrIns = new BIRNonTerminator.TypeCast(null, castedErrorOp,
                    splitFuncCallResultOp, symbolTable.errorType, false);
            trueBB.instructions.add(typeCastErrIns);
            BIRNonTerminator moveIns = new BIRNonTerminator.Move(null, castedErrorOp,
                    parentFuncEnv.returnOperand);
            trueBB.instructions.add(moveIns);
            trueBB.terminator = new BIRTerminator.GOTO(null, parentFuncEnv.returnBB, bbIns.scope);
            parentFuncEnv.parentFuncNewBBList.add(trueBB);
            parentFuncEnv.parentFuncNewBB = falseBB;
            parentFuncEnv.parentFuncNewInsList = new ArrayList<>();
        }

        splitFuncEnv.reset(getTempVarsForArraySplit(), fromAttachedFunction);
    }

    private void fixLocalVarStartAndEndBBsInPeriodicSplitFunc(SplitFuncEnv splitFuncEnv, BIRFunction splitBirFunc) {
        Map<Integer, BIRBasicBlock> changedBBs = splitFuncEnv.splitFuncChangedBBs;
        for (BIRVariableDcl localVar : splitBirFunc.localVars) {
            if (localVar.kind == VarKind.LOCAL) {
                if (localVar.startBB != null && changedBBs.containsKey(localVar.startBB.number)) {
                    localVar.startBB = changedBBs.get(localVar.startBB.number);
                }
                if (localVar.endBB != null && changedBBs.containsKey(localVar.endBB.number)) {
                    localVar.endBB = changedBBs.get(localVar.endBB.number);
                }
            }
        }
    }

    private void fixErrorTableInPeriodicSplitFunc(SplitFuncEnv splitFuncEnv, BIRFunction splitBirFunc) {
        Map<Integer, BIRBasicBlock> changedBBs = splitFuncEnv.splitFuncChangedBBs;
        for (BIRErrorEntry birErrorEntry : splitBirFunc.errorTable) {
            if (changedBBs.containsKey(birErrorEntry.trapBB.number)) {
                birErrorEntry.trapBB = changedBBs.get(birErrorEntry.trapBB.number);
            }
            if (changedBBs.containsKey(birErrorEntry.endBB.number)) {
                birErrorEntry.endBB = changedBBs.get(birErrorEntry.endBB.number);
            }
            if (changedBBs.containsKey(birErrorEntry.targetBB.number)) {
                birErrorEntry.targetBB = changedBBs.get(birErrorEntry.targetBB.number);
            }
        }
    }

    private void fixTerminatorBBsInPeriodicSplitFunc(SplitFuncEnv splitFuncEnv, BIRFunction splitBirFunc) {
        for (BIRBasicBlock basicBlock : splitBirFunc.basicBlocks) {
            if (!splitFuncEnv.splitFuncCorrectTerminatorBBs.contains(basicBlock)) {
                BIRTerminator terminator = basicBlock.terminator;
                Map<Integer, BIRBasicBlock> changedBBs = splitFuncEnv.splitFuncChangedBBs;
                if (terminator.thenBB != null
                        && changedBBs.containsKey(terminator.thenBB.number)) {
                    terminator.thenBB = changedBBs.get(terminator.thenBB.number);
                }

                switch (terminator.getKind()) {
                    case GOTO:
                        if (changedBBs.containsKey(
                                ((BIRTerminator.GOTO) terminator).targetBB.number)) {
                            ((BIRTerminator.GOTO) terminator).targetBB = changedBBs.get(
                                    ((BIRTerminator.GOTO) terminator).targetBB.number);
                        }
                        break;
                    case BRANCH:
                        BIRTerminator.Branch branchTerminator = (BIRTerminator.Branch) terminator;
                        if (changedBBs.containsKey(branchTerminator.trueBB.number)) {
                            branchTerminator.trueBB = changedBBs.get(
                                    branchTerminator.trueBB.number);
                        }
                        if (changedBBs.containsKey(branchTerminator.falseBB.number)) {
                            branchTerminator.falseBB = changedBBs.get(
                                    branchTerminator.falseBB.number);
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void populateSplitFuncArgsAndLocalVarList(SplitFuncEnv splitFuncEnv, BIRAbstractInstruction bbIns) {
        for (BIROperand rhsOperand : bbIns.getRhsOperands()) {
            if (!splitFuncEnv.splitAvailableOperands.contains(rhsOperand)
                    && needToPassRhsVarDclAsArg(rhsOperand)) {
                splitFuncEnv.splitFuncArgs.add(rhsOperand.variableDcl);
            }
        }
        if (bbIns.lhsOp != null) {
            splitFuncEnv.splitAvailableOperands.add(bbIns.lhsOp);
            if (needToPassLhsVarDclAsArg(bbIns.lhsOp)) {
                splitFuncEnv.splitFuncArgs.add(bbIns.lhsOp.variableDcl);
            }
            if (isTempOrSyntheticVar(bbIns.lhsOp.variableDcl)) {
                splitFuncEnv.splitFuncLocalVarList.add(bbIns.lhsOp.variableDcl);
            }
        }
    }

    private TempVarsForArraySplit getTempVarsForArraySplit() {
        BIRVariableDcl arrayIndexVarDcl = new BIRVariableDcl(null, symbolTable.intType,
                new Name("%arrIndex"), VarScope.FUNCTION, VarKind.TEMP, null);
        BIRVariableDcl typeCastVarDcl = new BIRVariableDcl(null, symbolTable.anyOrErrorType,
                new Name("%typeCast"), VarScope.FUNCTION, VarKind.TEMP, null);
        return new TempVarsForArraySplit(arrayIndexVarDcl, typeCastVarDcl);
    }

    private boolean setArrayElementGivenOperand(Map<BIROperand, BIRListConstructorEntryWithIndex> birOperands,
                                                List<BIRNonTerminator> newInsList, BIROperand handleArrayOperand,
                                                TempVarsForArraySplit tempVars,
                                                Map<BIRAbstractInstruction, List<BIROperand>> insSplitPoints,
                                                BIRAbstractInstruction currIns) {
        if (insSplitPoints.containsKey(currIns)) {
            List<BIROperand> currOperands = insSplitPoints.get(currIns);
            for (BIROperand currOperand : currOperands) {
                BIRListConstructorEntryWithIndex listConstructorEntryWithIndex = birOperands.get(currOperand);
                BIRNode.BIRListConstructorEntry listConstructorEntry = listConstructorEntryWithIndex
                        .listConstructorEntry;
                int arrayIndex = listConstructorEntryWithIndex.index;
                setArrayElement(newInsList, handleArrayOperand, currOperand, listConstructorEntry, arrayIndex,
                        tempVars);
            }
            return true;
        }
        return false;
    }

    private void setArrayElement(List<BIRNonTerminator> newInsList,
                                 BIROperand handleArrayOperand, BIROperand insLhsOp,
                                 BIRNode.BIRListConstructorEntry listConstructorEntry, int arrayIndex,
                                 TempVarsForArraySplit tempVars) {
        JMethodCallInstruction callSetEntry = new JMethodCallInstruction(null);
        callSetEntry.invocationType = INVOKESTATIC;
        callSetEntry.jClassName = RUNTIME_UTILS;
        callSetEntry.jMethodVMSig =
                "(Lio/ballerina/runtime/internal/values/HandleValue;Ljava/lang/Object;J)V";
        tempVars.tempVarsUsed = true;
        BIRNonTerminator.ConstantLoad loadArrayIndex = new BIRNonTerminator.ConstantLoad(null,
                (long) arrayIndex, symbolTable.intType, tempVars.arrayIndexOperand);
        newInsList.add(loadArrayIndex);

        BIRNonTerminator.TypeCast typeCastInstruction = new BIRNonTerminator.TypeCast(null,
                tempVars.typeCastOperand, insLhsOp, symbolTable.anyOrErrorType, true);
        newInsList.add(typeCastInstruction);
        callSetEntry.args = new ArrayList<>(Arrays.asList(handleArrayOperand, tempVars.typeCastOperand,
                tempVars.arrayIndexOperand));

        if (listConstructorEntry instanceof BIRNode.BIRListConstructorExprEntry) {
            // use jcall
            callSetEntry.name = "setExpressionEntry";
        } else {
            // BIRListConstructorSpreadMemberEntry
            callSetEntry.name = "setSpreadEntry";
        }
        newInsList.add(callSetEntry);
    }

    /**
     * Obtain a list of possible splits that can be done inside a function due to large no. of instructions.
     *
     * @param basicBlocks available basic block list of the function
     * @param errorTableEntries available error table entries of the function
     * @param parentFuncName
     * @return a list of possible splits
     */
    private List<Split> getPossibleSplits(List<BIRBasicBlock> basicBlocks, List<BIRErrorEntry> errorTableEntries,
                                          String parentFuncName) {
        List<Split> possibleSplits = new ArrayList<>();
        List<BIRVariableDcl> newFuncArgs;
        int splitEndBBIndex = basicBlocks.size() - 1; // goes from end to beginning
        int splitEndInsIndex = basicBlocks.get(splitEndBBIndex).instructions.size() - 1;
        int errTableEntryIndex = errorTableEntries.size() - 1;
        boolean splitStarted = false;
        boolean returnValAssigned = false;
        boolean splitTypeArray = true; // will be used to mark the split caused from either NewArray or NewStructure
        Set<BIRVariableDcl> neededOperandsVarDcl = new HashSet<>(); // that will need to be passed as function args
        Set<BIRVariableDcl> lhsOperandList = new HashSet<>(); // that will need as localVars in the new function
        BIROperand splitStartOperand = null;
        int splitInsCount = 0; // including terminator instructions

        for (int bbNum = basicBlocks.size() - 1; bbNum >= 0; bbNum--) {
            BIRBasicBlock basicBlock = basicBlocks.get(bbNum);
            BIRTerminator bbTerminator = basicBlock.terminator;
            if (splitStarted) {
                if (bbTerminator.lhsOp != null) {
                    if (bbTerminator.lhsOp.variableDcl.kind == VarKind.LOCAL) {
                        // if a local var is assigned value in a BB terminator, no split is done
                        splitStarted = false;
                    } else if (needToPassLhsVarDclAsArg(bbTerminator.lhsOp)) {
                        neededOperandsVarDcl.add(bbTerminator.lhsOp.variableDcl);
                    } else {
                        neededOperandsVarDcl.remove(bbTerminator.lhsOp.variableDcl);
                        if (isTempOrSyntheticVar(bbTerminator.lhsOp.variableDcl)) {
                            lhsOperandList.add(bbTerminator.lhsOp.variableDcl);
                        }
                    }
                }
                splitInsCount++;
                // RHS operands varDcl will be needed to pass as function args if they won't be available inside split
                BIROperand[] rhsOperands = bbTerminator.getRhsOperands();
                for (BIROperand rhsOperand : rhsOperands) {
                    if (needToPassRhsVarDclAsArg(rhsOperand)) {
                        neededOperandsVarDcl.add(rhsOperand.variableDcl);
                    }
                }
            }
            List<BIRNonTerminator> instructions = basicBlock.instructions;
            for (int insNum = instructions.size() - 1; insNum >= 0; insNum--) {
                BIRNonTerminator currIns = instructions.get(insNum);
                if (currIns.lhsOp.variableDcl.kind == VarKind.LOCAL) {
                    // if the local var is assigned value inside the split, no split is done
                    splitStarted = false;
                }
                if (splitStarted) {
                    if (currIns.lhsOp.variableDcl.kind == VarKind.RETURN) {
                        returnValAssigned = true;
                    } else if (needToPassLhsVarDclAsArg(currIns.lhsOp)) {
                        neededOperandsVarDcl.add(currIns.lhsOp.variableDcl);
                    } else {
                        neededOperandsVarDcl.remove(currIns.lhsOp.variableDcl);
                        if (isTempOrSyntheticVar(currIns.lhsOp.variableDcl)) {
                            lhsOperandList.add(currIns.lhsOp.variableDcl);
                        }
                    }
                    BIROperand[] rhsOperands = currIns.getRhsOperands();
                    for (BIROperand rhsOperand : rhsOperands) {
                        if (needToPassRhsVarDclAsArg(rhsOperand)) {
                            neededOperandsVarDcl.add(rhsOperand.variableDcl);
                        }
                    }
                    splitInsCount++;
                    // now check for the termination of split, i.e. split start
                    if (currIns.lhsOp == splitStartOperand) {
                        if ((neededOperandsVarDcl.size() > MAX_SPLIT_FUNCTION_ARG_COUNT) ||
                                (splitInsCount < SPLIT_INSTRUCTION_COUNT_THRESHOLD)) {
                            splitStarted = false;
                            continue;
                        }
                        newFuncArgs = new ArrayList<>(neededOperandsVarDcl);
                        // create necessary error table entries
                        List<BIRErrorEntry> splitErrorTableEntries = new ArrayList<>();
                        int trapBBNum;
                        while (errTableEntryIndex >= 0) {
                            trapBBNum = errorTableEntries.get(errTableEntryIndex).trapBB.number;
                            if (trapBBNum <= getBbIdNum(basicBlocks, bbNum)) {
                                break;
                            } else if (trapBBNum < getBbIdNum(basicBlocks, splitEndBBIndex)) {
                                splitErrorTableEntries.add(errorTableEntries.get(errTableEntryIndex));
                            }
                            errTableEntryIndex--;
                        }
                        Collections.reverse(splitErrorTableEntries);

                        // check where the new method needs to be further split
                        boolean splitAgain = splitInsCount >= FUNCTION_INSTRUCTION_COUNT_THRESHOLD;

                        // splitTypeArray variable can be used here if that information is needed later
                        possibleSplits.add(new Split(insNum, splitEndInsIndex, bbNum, splitEndBBIndex, lhsOperandList,
                                newFuncArgs, splitErrorTableEntries, splitAgain, splitTypeArray, returnValAssigned));
                        splitStarted = false;
                    }
                } else {
                    if ((currIns.kind == InstructionKind.NEW_ARRAY) ||
                            (currIns.kind == InstructionKind.NEW_STRUCTURE)) {
                        // new split can be created from here
                        if (currIns.kind == InstructionKind.NEW_ARRAY) {
                            BIRNonTerminator.NewArray arrayIns = (BIRNonTerminator.NewArray) currIns;
                            splitStartOperand = arrayIns.sizeOp;
                            splitTypeArray = true;
                        } else {
                            BIRNonTerminator.NewStructure structureIns = (BIRNonTerminator.NewStructure) currIns;
                            splitStartOperand = structureIns.rhsOp;
                            splitTypeArray = false;
                        }

                        // if the split will have all the available instructions already in the function -
                        // no need to make that split, avoids doing the same split repeatedly
                        if ((bbNum == basicBlocks.size() - 2) && (!basicBlocks.get(0).instructions.isEmpty()) &&
                                (basicBlocks.get(0).instructions.get(0).lhsOp == splitStartOperand)
                                && parentFuncName.startsWith("$split$")) {
                            continue;
                        }

                        splitStarted = true;
                        returnValAssigned = false;
                        neededOperandsVarDcl = new HashSet<>();
                        BIROperand[] initialRhsOperands = currIns.getRhsOperands();
                        for (BIROperand rhsOperand : initialRhsOperands) {
                            if (needToPassRhsVarDclAsArg(rhsOperand)) {
                                neededOperandsVarDcl.add(rhsOperand.variableDcl);
                            }
                        }
                        lhsOperandList = new HashSet<>();
                        splitInsCount = 1;
                        splitEndInsIndex = insNum;
                        splitEndBBIndex = bbNum;
                    }
                }
            }
        }
        Collections.reverse(possibleSplits);
        return possibleSplits;
    }

    private int getBbIdNum(List<BIRBasicBlock> bbList, int bbIndex) {
        return bbList.get(bbIndex).number;
    }

    private boolean needToPassRhsVarDclAsArg(BIROperand rhsOp) {
        return (!rhsOp.variableDcl.ignoreVariable) && (rhsOp.variableDcl.kind != VarKind.GLOBAL) &&
                (rhsOp.variableDcl.kind != VarKind.CONSTANT);
    }

    private boolean needToPassLhsVarDclAsArg(BIROperand lhsOp) {
        return (!lhsOp.variableDcl.ignoreVariable) && (lhsOp.variableDcl.kind == VarKind.SELF);
    }

    /**
     * Generate the given possible splits.
     * @param function original BIR function
     * @param possibleSplits list of possible splits
     * @param newlyAddedFunctions list to add the newly created functions
     * @param fromAttachedFunction flag which indicates an original attached function is being split
     */
    private void generateSplits(BIRFunction function, List<Split> possibleSplits,
                                List<BIRFunction> newlyAddedFunctions, boolean fromAttachedFunction) {
        int splitNum = 0; // next split to be done or the ongoing split index
        List<BIRBasicBlock> basicBlocks = function.basicBlocks;
        List<BIRBasicBlock> newBBList = new ArrayList<>();
        int startInsNum = 0; // start instruction index
        int bbNum = 0; // ongoing BB index
        int newBBNum = getBbIdNum(basicBlocks, basicBlocks.size() - 1); // used for newly created BB's id
        BIRBasicBlock currentBB  = possibleSplits.get(splitNum).startBBNum == 0
                ? replaceExistingBBAndGetCurrentBB(basicBlocks, bbNum) : null;
        Map<String, String> changedLocalVarStartBB = new HashMap<>(); // key: oldBBId, value: newBBId
        Map<String, String> changedLocalVarEndBB = new HashMap<>(); // key: oldBBId, value: newBBId
        Map<Integer, BIRBasicBlock> changedErrorTableEndBB = new HashMap<>(); // key: oldBB number, value: new BB

        while (bbNum < basicBlocks.size()) {
            // it is either the start of a new BB or after split across BBs (say jump) now in the middle of a BB
            // here variable currentBB is the BB where next instructions are going to be added
            if (startInsNum == 0) {
                if (splitNum >= possibleSplits.size()) {
                    // if there are no more splits just copy the remaining BBs and break
                    for (; bbNum < basicBlocks.size(); bbNum++) {
                        newBBList.add(basicBlocks.get(bbNum));
                    }
                    break;
                } else if (bbNum < possibleSplits.get(splitNum).startBBNum) {
                    // can just copy the BBs till the BB where a split starts
                    for (; bbNum < possibleSplits.get(splitNum).startBBNum; bbNum++) {
                        newBBList.add(basicBlocks.get(bbNum));
                    }
                    currentBB = replaceExistingBBAndGetCurrentBB(basicBlocks, bbNum);
                    continue;
                }
            } else if (splitNum >= possibleSplits.size()) {
                // came from a jump, so if there are no more splits can put the remaining instructions in the bb
                // and continue to next bb
                if (startInsNum < basicBlocks.get(bbNum).instructions.size()) {
                    currentBB.instructions.addAll(basicBlocks.get(bbNum).instructions.subList(startInsNum,
                            basicBlocks.get(bbNum).instructions.size()));
                }
                currentBB.terminator = basicBlocks.get(bbNum).terminator;
                newBBList.add(currentBB);
                changedLocalVarStartBB.put(basicBlocks.get(bbNum).id.value, currentBB.id.value);
                changedLocalVarEndBB.put(basicBlocks.get(bbNum).id.value, currentBB.id.value);
                startInsNum = 0;
                bbNum += 1;
                continue;
                // do not need to create a new BB because hereafter BBs will be just copied
            }

            // now there is definitely a split starts form this BB, first will handle the splits inside the same BB
            // here a list of splits starting from this BB and ends in this BB is obtained
            // so after creating splits have to handle the last instructions in the BB or a jump to another BB
            List<Split> splitsInSameBBList = new ArrayList<>();
            while (splitNum < possibleSplits.size()) {
                if (possibleSplits.get(splitNum).endBBNum == bbNum) {
                    splitsInSameBBList.add(possibleSplits.get(splitNum));
                    splitNum++;
                } else {
                    break;
                }
            }
            // so the next splitNum contains a split starts from this BB or another BB, but do not end in this BB
            changedLocalVarStartBB.put(basicBlocks.get(bbNum).id.value, currentBB.id.value);
            if (!splitsInSameBBList.isEmpty()) {
                // current unfinished BB is passed to the function and a new one is returned from it
                currentBB = generateSplitsInSameBB(function, bbNum, splitsInSameBBList, newlyAddedFunctions,
                        newBBNum, newBBList, startInsNum, currentBB, fromAttachedFunction);
                startInsNum = possibleSplits.get(splitNum - 1).lastIns + 1;
                newBBNum += splitsInSameBBList.size();
            }

            // handle if splits are over or if the next spilt is in another BB
            if ((splitNum >= possibleSplits.size()) || (possibleSplits.get(splitNum).startBBNum > bbNum)) {
                if (startInsNum < basicBlocks.get(bbNum).instructions.size()) {
                    currentBB.instructions.addAll(basicBlocks.get(bbNum).instructions.subList(startInsNum,
                            basicBlocks.get(bbNum).instructions.size()));
                }
                currentBB.terminator = basicBlocks.get(bbNum).terminator;
                newBBList.add(currentBB);
                changedLocalVarEndBB.put(basicBlocks.get(bbNum).id.value, currentBB.id.value);
                startInsNum = 0;
                bbNum += 1;
                if (splitNum < possibleSplits.size() && (possibleSplits.get(splitNum).startBBNum == bbNum)) {
                    currentBB = replaceExistingBBAndGetCurrentBB(basicBlocks, bbNum);
                } else {
                    currentBB = null;
                }
                continue;
            }

            // now we have a split that go to another BB from this BB
            // need to add remaining instructions then create a split function
            if (startInsNum < possibleSplits.get(splitNum).firstIns) {
                currentBB.instructions.addAll(basicBlocks.get(bbNum).instructions.subList(startInsNum,
                        possibleSplits.get(splitNum).firstIns));
            }
            splitFuncNum += 1;
            String newFunctionName = "$split$method$_" + splitFuncNum;
            Name newFuncName = new Name(newFunctionName);
            Split currSplit = possibleSplits.get(splitNum);
            splitNum += 1;
            BIRNonTerminator lastInstruction = basicBlocks.get(currSplit.endBBNum).instructions.get(currSplit.lastIns);
            BType newFuncReturnType = lastInstruction.lhsOp.variableDcl.type;
            BIROperand splitLastInsLhsOp = new BIROperand(lastInstruction.lhsOp.variableDcl);
            BIROperand splitFuncCallResultOp;
            if (currSplit.returnValAssigned) {
                splitFuncCallResultOp = generateTempLocalVariable(newFuncReturnType, function.localVars);
                newFuncReturnType = createErrorUnionReturnType(newFuncReturnType);
            } else {
                splitFuncCallResultOp = splitLastInsLhsOp;
            }
            // extra +1 for BB incremented in createNewBIRFunctionAcrossBB function, hence BB number is newBBNum + 2
            BIRBasicBlock parentFuncNewBB = new BIRBasicBlock(newBBNum + 2);
            BIRFunction newBIRFunc = createNewBIRFunctionAcrossBB(function, newFuncName, newFuncReturnType, currSplit,
                    newBBNum, fromAttachedFunction, changedErrorTableEndBB, parentFuncNewBB);
            newBBNum += 2;
            newlyAddedFunctions.add(newBIRFunc);
            if (currSplit.splitFurther) {
                newlyAddedFunctions.addAll(splitBIRFunction(newBIRFunc, fromAttachedFunction, true,
                        currSplit.splitTypeArray));
            }
            function.errorTable.removeAll(currSplit.errorTableEntries);
            startInsNum = currSplit.lastIns + 1;

            List<BIROperand> args = new ArrayList<>();
            for (BIRVariableDcl funcArg : currSplit.funcArgs) {
                args.add(new BIROperand(funcArg));
            }
            currentBB.terminator = new BIRTerminator.Call(lastInstruction.pos, InstructionKind.CALL, false,
                    currentPackageId, newFuncName, args, splitFuncCallResultOp, parentFuncNewBB,
                    Collections.emptyList(), Collections.emptySet(), lastInstruction.scope);
            newBBList.add(currentBB);
            changedLocalVarEndBB.put(basicBlocks.get(bbNum).id.value, currentBB.id.value);
            currentBB = parentFuncNewBB;
            if (currSplit.returnValAssigned) {
                currentBB = handleNewFuncReturnVal(function, splitFuncCallResultOp, lastInstruction.scope, currentBB,
                        newBBNum, newBBList, splitLastInsLhsOp);
                newBBNum += 2;
            }
            bbNum = currSplit.endBBNum;
        }

        // set startBB and endBB for local vars in the function
        setLocalVarStartEndBB(function, newBBList, changedLocalVarStartBB, changedLocalVarEndBB);
        // newBBList is used as the original function's BB list
        function.basicBlocks = newBBList;
        // unused temp and synthetic vars in the original function are removed
        // and onlyUsedInSingleBB flag in BIRVariableDcl is changed in needed places
        removeUnusedVarsAndSetVarUsage(function);
        // renumbering BBs is not necessary here as it will be done later and fixing error table does not require order
        // set error table changed BBs
        fixErrorTableEndBBs(function.errorTable, changedErrorTableEndBB);
    }

    private void fixErrorTableEndBBs(List<BIRErrorEntry> errorTable,
                                     Map<Integer, BIRBasicBlock> changedErrorTableEndBB) {
        for (BIRErrorEntry birErrorEntry : errorTable) {
            if (changedErrorTableEndBB.containsKey(birErrorEntry.endBB.number)) {
                birErrorEntry.endBB = changedErrorTableEndBB.get(birErrorEntry.endBB.number);
            }
        }
    }

    private BIRBasicBlock replaceExistingBBAndGetCurrentBB(List<BIRBasicBlock> basicBlocks, int bbNum) {
        BIRBasicBlock currentBB = basicBlocks.get(bbNum);
        BIRBasicBlock newCurrentBB = new BIRBasicBlock(getBbIdNum(basicBlocks, bbNum));
        newCurrentBB.instructions = currentBB.instructions;
        newCurrentBB.terminator = currentBB.terminator;
        basicBlocks.set(bbNum, newCurrentBB);
        currentBB.terminator = null;
        currentBB.instructions = new ArrayList<>();
        return currentBB;
    }

    private BType createErrorUnionReturnType(BType newFuncReturnType) {
        LinkedHashSet<BType> memberTypes = new LinkedHashSet<>(2);
        memberTypes.add(newFuncReturnType);
        memberTypes.add(symbolTable.errorType);
        return new BUnionType(null, memberTypes, false, false);
    }

    private BIRBasicBlock handleNewFuncReturnVal(BIRFunction function, BIROperand splitFuncCallResultOp,
                                                 BirScope lastInsScope, BIRBasicBlock currentBB, int newBBNum,
                                                 List<BIRBasicBlock> newBBList, BIROperand splitLastInsLhsOp) {
        BIROperand isErrorResultOp = generateTempLocalVariable(symbolTable.booleanType, function.localVars);
        BIRNonTerminator errorTestIns = new BIRNonTerminator.TypeTest(null, symbolTable.errorType,
                isErrorResultOp, splitFuncCallResultOp);
        currentBB.instructions.add(errorTestIns);
        BIRBasicBlock trueBB = new BIRBasicBlock(++newBBNum);
        BIRBasicBlock falseBB = new BIRBasicBlock(++newBBNum);
        currentBB.terminator = new BIRTerminator.Branch(null, isErrorResultOp, trueBB, falseBB, lastInsScope);
        newBBList.add(currentBB);
        BIROperand castedErrorOp = generateTempLocalVariable(symbolTable.errorType, function.localVars);
        BIRNonTerminator typeCastErrIns = new BIRNonTerminator.TypeCast(null, castedErrorOp,
                splitFuncCallResultOp, symbolTable.errorType, false);
        trueBB.instructions.add(typeCastErrIns);
        BIRNonTerminator moveIns = new BIRNonTerminator.Move(null, castedErrorOp,
                new BIROperand(function.returnVariable));
        trueBB.instructions.add(moveIns);
        BIRBasicBlock returnBB = function.basicBlocks.get(function.basicBlocks.size() - 1);
        trueBB.terminator = new BIRTerminator.GOTO(null, returnBB, lastInsScope);
        newBBList.add(trueBB);
        BIRNonTerminator typeCastLhsOpIns = new BIRNonTerminator.TypeCast(null, splitLastInsLhsOp,
                splitFuncCallResultOp, splitLastInsLhsOp.variableDcl.type, false);
        falseBB.instructions.add(typeCastLhsOpIns);
        return falseBB;
    }

    private boolean isTempOrSyntheticVar(BIRVariableDcl variableDcl) {
        return (variableDcl.kind == VarKind.TEMP) || (variableDcl.kind == VarKind.SYNTHETIC);
    }

    private Set<BIRVariableDcl> findUsedVars(BIRBasicBlock basicBlock, Set<BIRVariableDcl> usedTempAndSyntheticVars,
                                             Set<BIRVariableDcl> allUsedVars, Set<BIRVariableDcl> multipleBBUsedVars) {
        Set<BIRVariableDcl> thisBBUsedAllVars = new HashSet<>();
        for (BIRNonTerminator instruction : basicBlock.instructions) {
            thisBBUsedAllVars.add(instruction.lhsOp.variableDcl);
            if (allUsedVars.contains(instruction.lhsOp.variableDcl)) {
                multipleBBUsedVars.add(instruction.lhsOp.variableDcl);
            }
            if (isTempOrSyntheticVar(instruction.lhsOp.variableDcl)) {
                usedTempAndSyntheticVars.add(instruction.lhsOp.variableDcl);
            }
            BIROperand[] rhsOperands = instruction.getRhsOperands();
            for (BIROperand rhsOperand : rhsOperands) {
                thisBBUsedAllVars.add(rhsOperand.variableDcl);
                if (allUsedVars.contains(rhsOperand.variableDcl)) {
                    multipleBBUsedVars.add(rhsOperand.variableDcl);
                }
                if (isTempOrSyntheticVar(rhsOperand.variableDcl)) {
                    usedTempAndSyntheticVars.add(rhsOperand.variableDcl);
                }
            }
        }
        if (basicBlock.terminator.lhsOp != null) {
            thisBBUsedAllVars.add(basicBlock.terminator.lhsOp.variableDcl);
            if (allUsedVars.contains(basicBlock.terminator.lhsOp.variableDcl)) {
                multipleBBUsedVars.add(basicBlock.terminator.lhsOp.variableDcl);
            }
            if (isTempOrSyntheticVar(basicBlock.terminator.lhsOp.variableDcl)) {
                usedTempAndSyntheticVars.add(basicBlock.terminator.lhsOp.variableDcl);
            }
        }
        BIROperand[] rhsOperands = basicBlock.terminator.getRhsOperands();
        for (BIROperand rhsOperand : rhsOperands) {
            thisBBUsedAllVars.add(rhsOperand.variableDcl);
            if (allUsedVars.contains(rhsOperand.variableDcl)) {
                multipleBBUsedVars.add(rhsOperand.variableDcl);
            }
            if (isTempOrSyntheticVar(rhsOperand.variableDcl)) {
                usedTempAndSyntheticVars.add(rhsOperand.variableDcl);
            }
        }
        return thisBBUsedAllVars;
    }

    private void removeUnusedVarsAndSetVarUsage(BIRFunction birFunction) {
        Set<BIRVariableDcl> usedTempAndSyntheticVars = new HashSet<>();
        Set<BIRVariableDcl> allUsedVars = new HashSet<>();
        Set<BIRVariableDcl> multipleBBUsedVars = new HashSet<>();
        for (BIRBasicBlock basicBlock : birFunction.basicBlocks) {
            allUsedVars.addAll(findUsedVars(basicBlock, usedTempAndSyntheticVars, allUsedVars, multipleBBUsedVars));
        }

        List<BIRVariableDcl> newLocalVars = new ArrayList<>();
        for (BIRVariableDcl localVar : birFunction.localVars) {
            if ((localVar.onlyUsedInSingleBB) && multipleBBUsedVars.contains(localVar)) {
                localVar.onlyUsedInSingleBB = false;
            }
            if (isTempOrSyntheticVar(localVar)) {
                if (usedTempAndSyntheticVars.contains(localVar)) {
                    newLocalVars.add(localVar);
                }
            } else {
                newLocalVars.add(localVar);
            }
        }
        birFunction.localVars = newLocalVars;
    }

    private void setLocalVarStartEndBB(BIRFunction birFunction, List<BIRBasicBlock> newBBList, Map<String, String>
            changedLocalVarStartBB, Map<String, String> changedLocalVarEndBB) {

        Map<String, BIRBasicBlock> newBBs = new HashMap<>();
        for (BIRBasicBlock newBB : newBBList) {
            newBBs.put(newBB.id.value, newBB);
        }
        for (BIRVariableDcl localVar : birFunction.localVars) {
            if (localVar.kind == VarKind.LOCAL) {
                if (localVar.startBB != null) {
                    if (changedLocalVarStartBB.containsKey(localVar.startBB.id.value)) {
                        localVar.startBB = newBBs.get(changedLocalVarStartBB.get(localVar.startBB.id.value));
                    } else {
                        localVar.startBB = newBBs.get(localVar.startBB.id.value);
                    }
                }
                if (localVar.endBB != null) {
                    if (changedLocalVarEndBB.containsKey(localVar.endBB.id.value)) {
                        localVar.endBB = newBBs.get(changedLocalVarEndBB.get(localVar.endBB.id.value));
                    } else {
                        localVar.endBB = newBBs.get(localVar.endBB.id.value);
                    }
                }
            }
        }
    }

    /**
     * Create a new BIR function which spans across available multiple BBs.
     *
     * @param parentFunc parent BIR function
     * @param funcName Name of the function to be created
     * @param currSplit ongoing split details
     * @param newBBNum last BB id num of the parent function
     * @param fromAttachedFunction flag which indicates an original attached function is being split
     * @param changedErrorTableEndBB
     * @param parentFuncNewBB
     * @return newly created BIR function
     */
    private BIRFunction createNewBIRFunctionAcrossBB(BIRFunction parentFunc, Name funcName, BType retType,
                                                     Split currSplit, int newBBNum, boolean fromAttachedFunction,
                                                     Map<Integer, BIRBasicBlock> changedErrorTableEndBB,
                                                     BIRBasicBlock parentFuncNewBB) {
        List<BIRBasicBlock> parentFuncBBs = parentFunc.basicBlocks;
        BIRNonTerminator lastIns = parentFuncBBs.get(currSplit.endBBNum).instructions.get(currSplit.lastIns);
        List<BType> paramTypes = new ArrayList<>();
        for (BIRVariableDcl funcArg : currSplit.funcArgs) {
            paramTypes.add(funcArg.type);
        }
        BInvokableType type = new BInvokableType(paramTypes, retType, null);

        BIRFunction birFunc = new BIRFunction(parentFunc.pos, funcName, funcName, 0, type,
                DEFAULT_WORKER_NAME, 0, SymbolOrigin.VIRTUAL);

        List<BIRFunctionParameter> functionParams = new ArrayList<>();
        BIRVariableDcl selfVarDcl = null;
        for (BIRVariableDcl funcArg : currSplit.funcArgs) {
            Name argName = funcArg.name;
            birFunc.requiredParams.add(new BIRParameter(lastIns.pos, argName, 0));
            BIRFunctionParameter funcParameter = new BIRFunctionParameter(lastIns.pos, funcArg.type, argName,
                    VarScope.FUNCTION, VarKind.ARG, argName.getValue(), false, false);
            functionParams.add(funcParameter);
            birFunc.parameters.add(funcParameter);
            if (funcArg.kind == VarKind.SELF) {
                selfVarDcl = new BIRVariableDcl(funcArg.pos, funcArg.type, funcArg.name, funcArg.originalName,
                        VarScope.FUNCTION, VarKind.ARG, funcArg.metaVarName);
            }
        }

        birFunc.argsCount = currSplit.funcArgs.size();
        if (fromAttachedFunction) {
            birFunc.returnVariable = new BIRVariableDcl(lastIns.pos, retType, new Name("%1"),
                    VarScope.FUNCTION, VarKind.RETURN, null);
        } else {
            birFunc.returnVariable = new BIRVariableDcl(lastIns.pos, retType, new Name("%0"),
                    VarScope.FUNCTION, VarKind.RETURN, null);
        }
        birFunc.localVars.add(0, birFunc.returnVariable);
        birFunc.localVars.addAll(functionParams);
        birFunc.localVars.addAll(currSplit.lhsVars);
        birFunc.errorTable.addAll(currSplit.errorTableEntries);

        // creates BBs
        BIRBasicBlock entryBB = new BIRBasicBlock(0);
        if (currSplit.firstIns < parentFuncBBs.get(currSplit.startBBNum).instructions.size()) {
            entryBB.instructions.addAll(parentFuncBBs.get(currSplit.startBBNum).instructions.subList(
                    currSplit.firstIns, parentFuncBBs.get(currSplit.startBBNum).instructions.size()));
        }
        entryBB.terminator = parentFuncBBs.get(currSplit.startBBNum).terminator;
        List<BIRBasicBlock> basicBlocks = birFunc.basicBlocks;
        basicBlocks.add(entryBB);

        // copying BBs between first and last bb
        for (int bbNum = currSplit.startBBNum + 1; bbNum < currSplit.endBBNum; bbNum++) {
            BIRBasicBlock bb = parentFuncBBs.get(bbNum);
            basicBlocks.add(bb);
            changedErrorTableEndBB.put(bb.number, parentFuncNewBB);
        }

        // create last BB and the return BB (exit BB)
        int lastBBIdNum = getBbIdNum(parentFuncBBs, currSplit.endBBNum);
        BIRBasicBlock lastBB = new BIRBasicBlock(lastBBIdNum);
        changedErrorTableEndBB.put(lastBBIdNum, parentFuncNewBB);
        if (0 <= currSplit.lastIns) {
            lastBB.instructions.addAll(parentFuncBBs.get(currSplit.endBBNum).instructions.subList(
                    0, currSplit.lastIns + 1));
        }
        lastBB.instructions.get(currSplit.lastIns).lhsOp = new BIROperand(birFunc.returnVariable);

        // a new BB with id using newBBNum is used to include the return statement
        newBBNum += 1;
        BIRBasicBlock exitBB = new BIRBasicBlock(newBBNum);
        exitBB.terminator = new BIRTerminator.Return(null);
        lastBB.terminator = new BIRTerminator.GOTO(null, exitBB, lastIns.scope);
        for (BIRBasicBlock basicBlock : basicBlocks) {
            fixTerminatorBBs(lastBBIdNum, lastBB, basicBlock.terminator);
        }
        basicBlocks.add(lastBB);
        basicBlocks.add(exitBB);
        rectifyVarKindsAndTerminators(birFunc, selfVarDcl, exitBB);
        rearrangeBasicBlocks(birFunc);
        return birFunc;
    }

    private void fixTerminatorBBs(int lastBBIdNum, BIRBasicBlock lastBB, BIRTerminator terminator) {
        if (terminator.thenBB != null && terminator.thenBB.number == lastBBIdNum) {
            terminator.thenBB = lastBB;
        }

        switch (terminator.getKind()) {
            case GOTO:
                if (((BIRTerminator.GOTO) terminator).targetBB.number == lastBBIdNum) {
                    ((BIRTerminator.GOTO) terminator).targetBB = lastBB;
                }
                break;
            case BRANCH:
                BIRTerminator.Branch branchTerminator = (BIRTerminator.Branch) terminator;
                if (branchTerminator.trueBB.number == lastBBIdNum) {
                    branchTerminator.trueBB = lastBB;
                }
                if (branchTerminator.falseBB.number == lastBBIdNum) {
                    branchTerminator.falseBB = lastBB;
                }
                break;
            default:
                break;
        }
    }

    /**
     * Generate the given splits inside the same BB.
     *
     * @param function parent function
     * @param bbNum index of the ongoing BB in the BB list in the ongoing function in birPkg
     * @param possibleSplits list of possible splits
     * @param newlyAddedFunctions list to add the newly created functions
     * @param newBBNum last BB id num of the parent function
     * @param newBBList new BB list creating for the already available parent function
     * @param startInsNum index of the start instruction in the BB instruction list
     * @param currentBB BB at the start of the split
     * @param fromAttachedFunction flag which indicates an original attached function is being split
     * @return the next BB that will be in the parent function
     */
    private BIRBasicBlock generateSplitsInSameBB(BIRFunction function, int bbNum, List<Split> possibleSplits,
                                                 List<BIRFunction> newlyAddedFunctions, int newBBNum,
                                                 List<BIRBasicBlock> newBBList, int startInsNum,
                                                 BIRBasicBlock currentBB, boolean fromAttachedFunction) {
        List<BIRNonTerminator> instructionList = function.basicBlocks.get(bbNum).instructions;

        for (int splitNum = 0; splitNum < possibleSplits.size(); splitNum++) {
            splitFuncNum += 1;
            String newFunctionName = "$split$method$_" + splitFuncNum;
            Name newFuncName = new Name(newFunctionName);
            BIROperand currentBBTerminatorLhsOp =
                    new BIROperand(instructionList.get(possibleSplits.get(splitNum).lastIns).lhsOp.variableDcl);
            BIRFunction newBIRFunc = createNewBIRFuncForSplitInBB(function, newFuncName,
                    instructionList.get(possibleSplits.get(splitNum).lastIns),
                    instructionList.subList(possibleSplits.get(splitNum).firstIns,
                            possibleSplits.get(splitNum).lastIns),
                    possibleSplits.get(splitNum).lhsVars, possibleSplits.get(splitNum).funcArgs, fromAttachedFunction);
            newlyAddedFunctions.add(newBIRFunc);
            if (possibleSplits.get(splitNum).splitFurther) {
                newlyAddedFunctions.addAll(splitBIRFunction(newBIRFunc, fromAttachedFunction, true,
                        possibleSplits.get(splitNum).splitTypeArray));
            }
            currentBB.instructions.addAll(instructionList.subList(startInsNum, possibleSplits.get(splitNum).firstIns));
            startInsNum = possibleSplits.get(splitNum).lastIns + 1;
            newBBNum += 1;
            BIRBasicBlock newBB = new BIRBasicBlock(newBBNum);
            List<BIROperand> args = new ArrayList<>();
            for (BIRVariableDcl funcArg : possibleSplits.get(splitNum).funcArgs) {
                args.add(new BIROperand(funcArg));
            }
            currentBB.terminator = new BIRTerminator.Call(instructionList.get(possibleSplits.get(splitNum).lastIns).pos,
                    InstructionKind.CALL, false, currentPackageId, newFuncName, args, currentBBTerminatorLhsOp,
                    newBB, Collections.emptyList(), Collections.emptySet(),
                    instructionList.get(possibleSplits.get(splitNum).lastIns).scope);
            newBBList.add(currentBB);
            currentBB = newBB;
        }
        // need to handle the last created BB, this is done outside the function
        return currentBB;
    }

    /**
     * Generate a temporary function scope variable.
     *
     * @param variableType The type of the variable
     * @param funcLocalVarList The BIR function local var list to which the variable should be added
     * @return The generated operand for the variable declaration
     */
    private BIROperand generateTempLocalVariable(BType variableType, List<BIRVariableDcl> funcLocalVarList) {
        Name variableName = new Name("$split$tempVar$_" + splitTempVarNum++);
        BIRVariableDcl variableDcl = new BIRVariableDcl(variableType, variableName, VarScope.FUNCTION, VarKind.TEMP);
        funcLocalVarList.add(variableDcl);
        return new BIROperand(variableDcl);
    }

    /**
     * Generate a temporary function scope variable.
     *
     * @param variableType The type of the variable
     * @param funcLocalVarList The BIR function local var set to which the variable should be added
     * @return The generated operand for the variable declaration
     */
    private BIROperand generateTempLocalVariable(BType variableType, Set<BIRVariableDcl> funcLocalVarList) {
        Name variableName = new Name("$split$tempVar$_" + splitTempVarNum++);
        BIRVariableDcl variableDcl = new BIRVariableDcl(variableType, variableName, VarScope.FUNCTION, VarKind.TEMP);
        funcLocalVarList.add(variableDcl);
        return new BIROperand(variableDcl);
    }

    /**
     * Create a new BIR function for a split which starts and ends in the same BB.
     *
     * @param parentFunc parent BIR function
     * @param funcName Name of the function to be created
     * @param currentIns last instruction in the split
     * @param collectedIns other instructions in the split
     * @param lhsOperandList set of varDcl of the LHS operands
     * @param funcArgs list of varDcl for the new function arguments
     * @param fromAttachedFunction flag which indicates an original attached function is being split
     * @return newly created BIR function
     */
    private BIRFunction createNewBIRFuncForSplitInBB(BIRFunction parentFunc, Name funcName, BIRNonTerminator currentIns,
                                                     List<BIRNonTerminator> collectedIns,
                                                     Set<BIRVariableDcl> lhsOperandList, List<BIRVariableDcl> funcArgs,
                                                     boolean fromAttachedFunction) {
        BType retType = currentIns.lhsOp.variableDcl.type;
        List<BType> paramTypes = new ArrayList<>();
        for (BIRVariableDcl funcArg : funcArgs) {
            paramTypes.add(funcArg.type);
        }
        BInvokableType type = new BInvokableType(paramTypes, retType, null);

        BIRFunction birFunc = new BIRFunction(parentFunc.pos, funcName, funcName, 0, type,
                DEFAULT_WORKER_NAME, 0, SymbolOrigin.VIRTUAL);

        List<BIRFunctionParameter> functionParams = new ArrayList<>();
        BIRVariableDcl selfVarDcl = null;
        for (BIRVariableDcl funcArg : funcArgs) {
            Name argName = funcArg.name;
            birFunc.requiredParams.add(new BIRParameter(currentIns.pos, argName, 0));
            BIRFunctionParameter funcParameter = new BIRFunctionParameter(currentIns.pos, funcArg.type, argName,
                    VarScope.FUNCTION, VarKind.ARG, argName.getValue(), false, false);
            functionParams.add(funcParameter);
            birFunc.parameters.add(funcParameter);
            if (funcArg.kind == VarKind.SELF) {
                selfVarDcl = new BIRVariableDcl(funcArg.pos, funcArg.type, funcArg.name, funcArg.originalName,
                        VarScope.FUNCTION, VarKind.ARG, funcArg.metaVarName);
            }
        }

        birFunc.argsCount = funcArgs.size();
        if (fromAttachedFunction) {
            birFunc.returnVariable = new BIRVariableDcl(currentIns.pos, retType, new Name("%1"),
                    VarScope.FUNCTION, VarKind.RETURN, null);
        } else {
            birFunc.returnVariable = new BIRVariableDcl(currentIns.pos, retType, new Name("%0"),
                    VarScope.FUNCTION, VarKind.RETURN, null);
        }
        birFunc.localVars.add(0, birFunc.returnVariable);
        birFunc.localVars.addAll(functionParams);
        birFunc.localVars.addAll(lhsOperandList);

        // creates 2 bbs
        BIRBasicBlock entryBB = new BIRBasicBlock(0);
        entryBB.instructions.addAll(collectedIns);
        currentIns.lhsOp = new BIROperand(birFunc.returnVariable);
        entryBB.instructions.add(currentIns);
        BIRBasicBlock exitBB = new BIRBasicBlock(1);
        exitBB.terminator = new BIRTerminator.Return(null);
        entryBB.terminator = new BIRTerminator.GOTO(null, exitBB, currentIns.scope);
        birFunc.basicBlocks.add(entryBB);
        birFunc.basicBlocks.add(exitBB);
        rectifyVarKindsAndTerminators(birFunc, selfVarDcl, exitBB);
        return birFunc;
    }

    private BIRVariableDcl getArgVarKindVarDcl(BIRVariableDcl variableDcl) {
        return new BIRVariableDcl(variableDcl.pos, variableDcl.type, variableDcl.name, variableDcl.originalName,
                VarScope.FUNCTION, VarKind.ARG, variableDcl.metaVarName);
    }

    private void rectifyVarKindsAndTerminators(BIRFunction birFunction, BIRVariableDcl selfVarDcl,
                                               BIRBasicBlock returnBB) {
        for (BIRBasicBlock basicBlock : birFunction.basicBlocks) {
            for (BIRNonTerminator instruction : basicBlock.instructions) {
                if (instruction.lhsOp.variableDcl.kind == VarKind.SELF) {
                    instruction.lhsOp.variableDcl = selfVarDcl;
                } else if (instruction.lhsOp.variableDcl.kind == VarKind.RETURN) {
                    instruction.lhsOp.variableDcl = birFunction.returnVariable;
                    basicBlock.terminator = new BIRTerminator.GOTO(null, returnBB, basicBlock.terminator.scope);
                }
                BIROperand[] rhsOperands = instruction.getRhsOperands();
                for (BIROperand rhsOperand : rhsOperands) {
                    if (rhsOperand.variableDcl.kind == VarKind.SELF) {
                        rhsOperand.variableDcl = selfVarDcl;
                    } else if (rhsOperand.variableDcl.kind == VarKind.LOCAL) {
                        rhsOperand.variableDcl = getArgVarKindVarDcl(rhsOperand.variableDcl);
                    }
                }
            }
            if ((basicBlock.terminator.lhsOp != null) &&
                    (basicBlock.terminator.lhsOp.variableDcl.kind == VarKind.SELF)) {
                basicBlock.terminator.lhsOp.variableDcl = selfVarDcl;
            }
            BIROperand[] rhsOperands = basicBlock.terminator.getRhsOperands();
            for (BIROperand rhsOperand : rhsOperands) {
                if (rhsOperand.variableDcl.kind == VarKind.SELF) {
                    rhsOperand.variableDcl = selfVarDcl;
                } else if (rhsOperand.variableDcl.kind == VarKind.LOCAL) {
                    rhsOperand.variableDcl = getArgVarKindVarDcl(rhsOperand.variableDcl);
                }
            }
        }
    }

    static class Split {
        int firstIns;
        int lastIns;
        int startBBNum;
        int endBBNum;
        boolean splitFurther;
        boolean splitTypeArray;
        boolean returnValAssigned;
        Set<BIRVariableDcl> lhsVars;
        List<BIRVariableDcl> funcArgs;
        List<BIRErrorEntry> errorTableEntries;

        private Split(int firstIns, int lastIns, int startBBNum, int endBBNum, Set<BIRVariableDcl> lhsVars,
                      List<BIRVariableDcl> funcArgs, List<BIRErrorEntry> errorTableEntries, boolean splitFurther,
                      boolean splitTypeArray, boolean returnValAssigned) {
            this.firstIns = firstIns;
            this.lastIns = lastIns;
            this.startBBNum = startBBNum;
            this.endBBNum = endBBNum;
            this.splitFurther = splitFurther;
            this.splitTypeArray = splitTypeArray;
            this.returnValAssigned = returnValAssigned;
            this.lhsVars = lhsVars;
            this.funcArgs = funcArgs;
            this.errorTableEntries = errorTableEntries;
        }
    }

    static class BIRListConstructorEntryWithIndex {
        BIRNode.BIRListConstructorEntry listConstructorEntry;
        int index;

        private BIRListConstructorEntryWithIndex(BIRNode.BIRListConstructorEntry listConstructorEntry, int index) {
            this.listConstructorEntry = listConstructorEntry;
            this.index = index;
        }
    }

    static class TempVarsForArraySplit {
        BIRVariableDcl arrayIndexVarDcl;
        BIRVariableDcl typeCastVarDcl;
        BIROperand arrayIndexOperand;
        BIROperand typeCastOperand;
        boolean tempVarsUsed = false;

        private TempVarsForArraySplit(BIRVariableDcl arrayIndexVarDcl, BIRVariableDcl typeCastVarDcl) {
            this.arrayIndexVarDcl = arrayIndexVarDcl;
            this.typeCastVarDcl = typeCastVarDcl;
            this.arrayIndexOperand = new BIROperand(arrayIndexVarDcl);
            this.typeCastOperand = new BIROperand(typeCastVarDcl);
        }
    }

    static class SplitFuncEnv {
        List<BIRBasicBlock> splitFuncNewBBList = new ArrayList<>();
        List<BIRNonTerminator> splitFuncNewInsList =  new ArrayList<>();
        Set<BIRVariableDcl> splitFuncLocalVarList = new HashSet<>();
        Set<BIROperand> splitAvailableOperands = new HashSet<>();
        Set<BIRVariableDcl> splitFuncArgs = new LinkedHashSet<>(); // see hashset is possible
        List<BIRErrorEntry> splitFuncErrorTable = new ArrayList<>();
        //for split funcs we need to always create BBs and change the terminators BB, local var end BBs, and error table
        Map<Integer, BIRBasicBlock> splitFuncChangedBBs = new HashMap<>();
        Set<BIRBasicBlock> splitFuncCorrectTerminatorBBs = new HashSet<>();
        int periodicSplitInsCount = 0;
        boolean splitOkay = true; // this is made false when there is branch terminator
        int doNotSplitTillThisBBNum = 0;
        boolean returnValAssigned = false;
        int splitFuncBBId = 0;
        BIRBasicBlock splitFuncBB = new BIRBasicBlock(splitFuncBBId++);
        TempVarsForArraySplit splitFuncTempVars;
        BIRBasicBlock returnBB = new BIRBasicBlock(-1);
        boolean fromAttachedFunction;
        BIRVariableDcl returnVarDcl;
        BIROperand returnOperand;

        private SplitFuncEnv(TempVarsForArraySplit splitFuncTempVars, boolean fromAttachedFunction) {
            this.splitFuncTempVars = splitFuncTempVars;
            this.fromAttachedFunction = fromAttachedFunction;
            if (fromAttachedFunction) {
                this.returnVarDcl = new BIRVariableDcl(null, null, new Name("%1"),
                        VarScope.FUNCTION, VarKind.RETURN, null);
            } else {
                this.returnVarDcl = new BIRVariableDcl(null, null, new Name("%0"),
                        VarScope.FUNCTION, VarKind.RETURN, null);
            }
            this.returnOperand = new BIROperand(this.returnVarDcl);
        }

        void reset(TempVarsForArraySplit tempVars, boolean fromAttachedFunc) {
            this.splitFuncNewBBList = new ArrayList<>();
            this.splitFuncNewInsList =  new ArrayList<>();
            this.splitFuncLocalVarList = new HashSet<>();
            this.splitAvailableOperands = new HashSet<>();
            this.splitFuncArgs = new LinkedHashSet<>(); // see hashset is possible
            this.splitFuncErrorTable = new ArrayList<>();
            this.splitFuncChangedBBs = new HashMap<>();
            this.splitFuncCorrectTerminatorBBs = new HashSet<>();
            this.splitFuncTempVars = tempVars;
            this.splitFuncBBId = 0;
            this.splitFuncBB = new BIRBasicBlock(this.splitFuncBBId++);
            this.splitOkay = true;
            this.returnValAssigned = false;
            this.fromAttachedFunction = fromAttachedFunc;
            if (fromAttachedFunc) {
                this.returnVarDcl = new BIRVariableDcl(null, null, new Name("%1"),
                        VarScope.FUNCTION, VarKind.RETURN, null);
            } else {
                this.returnVarDcl = new BIRVariableDcl(null, null, new Name("%0"),
                        VarScope.FUNCTION, VarKind.RETURN, null);
            }
            this.returnOperand = new BIROperand(this.returnVarDcl);
            this.returnBB = new BIRBasicBlock(-1);
        }
    }

    static class ParentFuncEnv {
        List<BIRBasicBlock> parentFuncNewBBList = new ArrayList<>();
        List<BIRNonTerminator> parentFuncNewInsList =  new ArrayList<>();
        Set<BIRVariableDcl> parentFuncLocalVarList = new HashSet<>();
        int errorTableIndex = 0;
        int parentFuncBBId = 0;
        BIRBasicBlock parentFuncNewBB = new BIRBasicBlock(parentFuncBBId++);
        TempVarsForArraySplit parentFuncTempVars;

        BIRBasicBlock returnBB;
        BIRVariableDcl returnVarDcl;
        BIROperand returnOperand;

        private ParentFuncEnv(TempVarsForArraySplit parentFuncTempVars, BIRBasicBlock returnBB) {
            this.parentFuncTempVars = parentFuncTempVars;
            this.returnBB = returnBB;
        }
    }
}
