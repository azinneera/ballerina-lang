/*
 * Copyright (c) 2019, WSO2 Inc. (http://wso2.com) All Rights Reserved.
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

package org.ballerinalang.debugadapter;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import org.ballerinalang.debugadapter.jdi.JdiProxyException;
import org.ballerinalang.debugadapter.jdi.ThreadReferenceProxyImpl;
import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.ContinuedEventArguments;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.StoppedEventArgumentsReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.ballerinalang.debugadapter.utils.PackageUtils.getQualifiedClassName;

/**
 * JDI Event processor implementation.
 */
public class JDIEventProcessor {

    private final ExecutionContext context;
    private final Map<String, Breakpoint[]> breakpointsList = new HashMap<>();
    private final List<EventRequest> stepEventRequests = new ArrayList<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(JBallerinaDebugServer.class);
    private static final String JBAL_STRAND_PREFIX = "jbal-strand-exec";

    JDIEventProcessor(ExecutionContext context) {
        this.context = context;
    }

    /**
     * Asynchronously listens and processes the incoming JDI events.
     */
    void startListening() {
        CompletableFuture.runAsync(() -> {
            boolean vmAttached = true;
            while (vmAttached) {
                try {
                    EventSet eventSet = context.getDebuggee().eventQueue().remove();
                    EventIterator eventIterator = eventSet.eventIterator();
                    while (eventIterator.hasNext()) {
                        vmAttached = processEvent(eventSet, eventIterator.next());
                    }
                } catch (Exception e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            // It is not required to terminate the debuggee (remote VM) in here, since it must be disconnected or dead
            // by now.
            context.getAdapter().exit(false);
        });
    }

    private boolean processEvent(EventSet eventSet, Event event) {
        if (event instanceof ClassPrepareEvent) {
            ClassPrepareEvent evt = (ClassPrepareEvent) event;
            configureUserBreakPoints(evt.referenceType());
            eventSet.resume();
        } else if (event instanceof BreakpointEvent) {
            StoppedEventArguments stoppedEventArguments = new StoppedEventArguments();
            stoppedEventArguments.setReason(StoppedEventArgumentsReason.BREAKPOINT);
            stoppedEventArguments.setThreadId(((BreakpointEvent) event).thread().uniqueID());
            stoppedEventArguments.setAllThreadsStopped(true);
            context.getClient().stopped(stoppedEventArguments);
            context.getEventManager().deleteEventRequests(stepEventRequests);
        } else if (event instanceof StepEvent) {
            if (((StepEvent) event).location().lineNumber() > 0) {
                context.getEventManager().deleteEventRequests(stepEventRequests);
                StoppedEventArguments stoppedEventArguments = new StoppedEventArguments();
                stoppedEventArguments.setReason(StoppedEventArgumentsReason.STEP);
                stoppedEventArguments.setThreadId(((StepEvent) event).thread().uniqueID());
                stoppedEventArguments.setAllThreadsStopped(true);
                context.getClient().stopped(stoppedEventArguments);
            } else {
                long threadId = ((StepEvent) event).thread().uniqueID();
                int stepType = ((StepRequest) event.request()).depth();
                sendStepRequest(threadId, stepType);
            }
        } else if (event instanceof VMDisconnectEvent
                || event instanceof VMDeathEvent
                || event instanceof VMDisconnectedException) {
            ExitedEventArguments exitedEventArguments = new ExitedEventArguments();
            exitedEventArguments.setExitCode(0L);
            context.getClient().exited(exitedEventArguments);
            return false;
        } else {
            eventSet.resume();
        }
        return true;
    }

    void setBreakpointsList(String path, Breakpoint[] breakpointsList) {
        Breakpoint[] breakpoints = breakpointsList.clone();
        this.breakpointsList.put(getQualifiedClassName(path), breakpoints);
        if (context.getDebuggee() != null) {
            // Setting breakpoints to a already running debug session.
            context.getEventManager().deleteAllBreakpoints();
            context.getDebuggee().allClasses().forEach(this::configureUserBreakPoints);
        }
    }

    void sendStepRequest(long threadId, int stepType) {
        if (stepType == StepRequest.STEP_OVER) {
            configureDynamicBreakPoints(threadId);
        } else if (stepType == StepRequest.STEP_INTO || stepType == StepRequest.STEP_OUT) {
            createStepRequest(threadId, stepType);
        }
        context.getDebuggee().resume();
        // Notifies the debug client that the execution is resumed.
        ContinuedEventArguments continuedEventArguments = new ContinuedEventArguments();
        continuedEventArguments.setAllThreadsContinued(true);
        context.getClient().continued(continuedEventArguments);
    }

    void restoreBreakpoints(DebugInstruction instruction) {
        if (context.getDebuggee() == null || instruction == DebugInstruction.STEP_OVER) {
            return;
        }

        context.getEventManager().deleteAllBreakpoints();
        if (instruction == DebugInstruction.CONTINUE) {
            context.getDebuggee().allClasses().forEach(this::configureUserBreakPoints);
        }
    }

    private void configureUserBreakPoints(ReferenceType referenceType) {
        try {
            String qualifiedClassName = getQualifiedClassName(context, referenceType);
            if (!breakpointsList.containsKey(qualifiedClassName)) {
                return;
            }
            Breakpoint[] breakpoints = breakpointsList.get(qualifiedClassName);
            for (Breakpoint bp : breakpoints) {
                List<Location> locations = referenceType.locationsOfLine(bp.getLine().intValue());
                if (!locations.isEmpty()) {
                    Location loc = locations.get(0);
                    BreakpointRequest bpReq = context.getEventManager().createBreakpointRequest(loc);
                    bpReq.enable();
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }
    }

    private void configureDynamicBreakPoints(long threadId) {
        ThreadReferenceProxyImpl threadReference = context.getAdapter().getThreadsMap().get(threadId);
        try {
            Location currentLocation = threadReference.frames().get(0).location();
            ReferenceType referenceType = currentLocation.declaringType();
            List<Location> allLocations = currentLocation.method().allLineLocations();
            Optional<Location> firstLocation = allLocations.stream().min(Comparator.comparingInt(Location::lineNumber));
            Optional<Location> lastLocation = allLocations.stream().max(Comparator.comparingInt(Location::lineNumber));
            if (firstLocation.isEmpty()) {
                return;
            }
            // If the debug flow is in the last line of the method and the user wants to step over, the expected
            // behavior would be stepping out to the parent/caller function.
            if (currentLocation.lineNumber() == lastLocation.get().lineNumber()) {
                createStepRequest(threadId, StepRequest.STEP_OUT);
                return;
            }

            int nextStepPoint = firstLocation.get().lineNumber();
            context.getEventManager().deleteAllBreakpoints();
            do {
                List<Location> locations = referenceType.locationsOfLine(nextStepPoint);
                if (!locations.isEmpty() && (locations.get(0).lineNumber() > firstLocation.get().lineNumber())) {
                    BreakpointRequest bpReq = context.getEventManager().createBreakpointRequest(locations.get(0));
                    bpReq.enable();
                }
                nextStepPoint++;
            } while (nextStepPoint <= lastLocation.get().lineNumber());
        } catch (AbsentInformationException | JdiProxyException e) {
            LOGGER.error(e.getMessage());
            int stepType = ((StepRequest) this.stepEventRequests.get(0)).depth();
            sendStepRequest(threadId, stepType);
        }
    }

    void createStepRequest(long threadId, int stepType) {
        context.getEventManager().deleteEventRequests(stepEventRequests);
        ThreadReferenceProxyImpl threadReference = context.getAdapter().getThreadsMap().get(threadId);
        if (threadReference == null || threadReference.getThreadReference() == null) {
            return;
        }

        StepRequest request = context.getEventManager().createStepRequest(threadReference.getThreadReference(),
                StepRequest.STEP_LINE, stepType);
        request.setSuspendPolicy(StepRequest.SUSPEND_ALL);
        // Todo - Replace with a class inclusive filter.
        request.addClassExclusionFilter("io.*");
        request.addClassExclusionFilter("com.*");
        request.addClassExclusionFilter("org.*");
        request.addClassExclusionFilter("ballerina.*");
        request.addClassExclusionFilter("java.*");
        request.addClassExclusionFilter("$lambda$main$");
        stepEventRequests.add(request);
        request.addCountFilter(1);
        request.enable();
    }
}
