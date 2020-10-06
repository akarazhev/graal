/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.truffle.espresso.analysis;

import static com.oracle.truffle.espresso.bytecode.Bytecodes.JSR;
import static com.oracle.truffle.espresso.bytecode.Bytecodes.JSR_W;
import static com.oracle.truffle.espresso.bytecode.Bytecodes.RET;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import com.oracle.truffle.espresso.analysis.graph.Block;
import com.oracle.truffle.espresso.analysis.graph.EspressoBlock;
import com.oracle.truffle.espresso.analysis.graph.EspressoBlockWithHandlers;
import com.oracle.truffle.espresso.analysis.graph.EspressoExecutionGraph;
import com.oracle.truffle.espresso.analysis.graph.Graph;
import com.oracle.truffle.espresso.analysis.graph.LinkedBlock;
import com.oracle.truffle.espresso.bytecode.BytecodeStream;
import com.oracle.truffle.espresso.bytecode.BytecodeSwitch;
import com.oracle.truffle.espresso.bytecode.Bytecodes;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.ExceptionHandler;

public final class GraphBuilder {
    public static Graph<? extends LinkedBlock> build(Method method) {
        return new GraphBuilder(method).build();
    }

    private static final long BLOCK_START = 1L << 63;
    private static final long ONLY_TARGET = 1L << 62;
    private static final long SWITCH = 1L << 61;
    private static final long HAS_TARGET = 1L << 60;
    private static final long HAS_BLOCK = 1L << 59;
    private static final long IS_CONTROL_SINK = 1L << 58;
    private static final long IS_JSR = 1L << 57;
    private static final long IS_RET = 1L << 56;

    private static final int BLOCK_ID_SHIFT = 16;
    private static final long BLOCK_ID_MASK = 0xFFFFL << BLOCK_ID_SHIFT;
    private static final long TARGET_MASK = 0xFFFFL;

    private final long[] status;

    private static final int[] EMPTY_SUCCESSORS = new int[0];

    private final Method method;

    private final BytecodeStream bs;
    private final ExceptionHandler[] handlers;

    private final List<int[]> switchTable = new ArrayList<>();
    private final List<JsrMarker> jsrTable = new ArrayList<>();
    private final List<Integer> returnTable = new ArrayList<>();

    private int[] handlerToBlock;
    private TemporaryBlock[] temporaryBlocks;

    private int nBlocks;

    private GraphBuilder(Method method) {
        this.method = method;
        this.bs = new BytecodeStream(method.getCode());
        this.status = new long[bs.endBCI()];
        this.handlers = method.getExceptionHandlers();
    }

    private EspressoExecutionGraph build() {
        // Delimit blocks.
        identifyBlock();
        // Count and assign block ids to blocks.
        assignIds();
        // Create block objects.
        spawnBlocks();
        // Register handlers.
        registerHandlers();
        // Register all blocks that can succeed a RET.
        registerJsrRet();
        // Register predecessors.
        registerPredecessors();
        // spawn the espresso graph.
        return promote();
    }

    /**
     * Analyses control flow of the byte code to identify every start of a basic block.
     */
    private void identifyBlock() {
        int bci = 0;
        status[bci] = BLOCK_START;
        while (bci < bs.endBCI()) {
            int curOpcode = bs.currentBC(bci);
            if (Bytecodes.isBlockEnd(curOpcode)) {
                markBlock(bci, curOpcode);
            }
            bci = bs.nextBCI(bci);
        }
        for (ExceptionHandler handler : handlers) {
            markHandler(handler);
        }
    }

    /**
     * Now that we know exactly where the block starts are, we assign a unique id for each of these
     * blocks, in order of start BCI.
     */
    private void assignIds() {
        int id = 0;
        for (int bci = 0; bci < status.length; bci++) {
            if (isStatus(bci, BLOCK_START)) {
                setBlockID(bci, id++);
            }
        }
        nBlocks = id;
    }

    /**
     * Creates temporary semi-linked blocks. We can know what the direct successors are from regular
     * control flow. Once all blocks are assigned, we will have an easier time finding the blocks
     * covered by the exception handlers.
     */
    private void spawnBlocks() {
        TemporaryBlock[] temp = new TemporaryBlock[nBlocks];
        int id = 0;
        int start = 0;
        int[] successors = null;
        boolean isRet = false;
        for (int bci = 0; bci < status.length; bci++) {
            if (bci != 0 && isStatus(bci, BLOCK_START)) {
                assert temp[id] == null;
                assert id == readBlockID(start);
                temp[id] = createTempBlock(id, start, successors, isRet, bci);
                start = bci;
                id++;
                successors = null;
                isRet = false;
            }
            if (isStatus(bci, IS_RET)) {
                isRet = true;
            }
            if (isStatus(bci, HAS_TARGET)) {
                assert successors == null;
                successors = findSuccessors(bci, id);
            }
            if (isStatus(bci, IS_CONTROL_SINK)) {
                assert successors == null;
                successors = EMPTY_SUCCESSORS;
            }
        }
        temp[id] = createTempBlock(id, start, successors, isRet, status.length);

        temporaryBlocks = temp;
        int[] handlerBlocks = new int[handlers.length];
        int pos = 0;
        for (ExceptionHandler handler : handlers) {
            handlerBlocks[pos] = readBlockID(handler.getHandlerBCI());
        }
        handlerToBlock = handlerBlocks;
    }

    private TemporaryBlock createTempBlock(int id, int start, int[] successors, boolean isRet, int bci) {
        return new TemporaryBlock(id, start, bci - 1, successors, isRet);
    }

    /**
     * Blocks are assigned, register each handler to the blocks it covers.
     */
    private void registerHandlers() {
        int pos = 0;
        for (ExceptionHandler handler : handlers) {
            int currentBlock = readBlockID(handler.getStartBCI());
            while (currentBlock < nBlocks && temporaryBlocks[currentBlock].end <= handler.getEndBCI()) {
                temporaryBlocks[currentBlock].registerHandler(pos);
                currentBlock++;
            }
            pos++;
        }
    }

    /**
     * Assigns to each temporary block its predecessors.
     */
    private void registerPredecessors() {
        for (int id = 0; id < temporaryBlocks.length; id++) {
            TemporaryBlock b = temporaryBlocks[id];
            for (int successor : b.successors(this)) {
                temporaryBlocks[successor].registerPredecessor(id);
            }

        }
    }

    /**
     * Registers to the builder all possible return addresses. As a first approximation, we will
     * consider that all RET can return to all JSR.
     */
    private void registerJsrRet() {
        if (!jsrTable.isEmpty()) {
            for (JsrMarker marker : jsrTable) {
                int id = readBlockID(marker.returnAddress);
                assert !returnTable.contains(id);
                returnTable.add(id);
            }
        }
    }

    /**
     * Fully link all the blocks and freeze the graph. Also identify all the loops in the graph if
     * needed, and remember which blocks are part of the loop.
     */
    private EspressoExecutionGraph promote() {
        EspressoBlock[] blocks = new EspressoBlock[temporaryBlocks.length];
        EspressoExecutionGraph graph = new EspressoExecutionGraph(method, handlers, handlerToBlock, blocks);
        for (int i = 0; i < temporaryBlocks.length; i++) {
            blocks[i] = temporaryBlocks[i].promote(this, graph);
        }
        return graph;
    }

    private int[] findSuccessors(int bci, int id) {
        assert isStatus(bci, HAS_TARGET);
        if (isStatus(bci, SWITCH)) {
            int switchHandler = readTarget(bci);
            int[] targets = switchTable.get(switchHandler);
            int[] result = new int[targets.length];
            int pos = 0;
            for (int target : targets) {
                result[pos++] = readBlockID(target);
            }
            return result;
        } else {
            int branchID = readBlockID(readTarget(bci));
            if (isStatus(bci, ONLY_TARGET) || id + 1 >= nBlocks) {
                return new int[]{branchID};
            } else {
                int nextId = id + 1;
                return new int[]{nextId, branchID};
            }
        }
    }

    private void markBlock(int bci, int opcode) {
        if (Bytecodes.isReturn((byte) opcode) || opcode == Bytecodes.ATHROW) {
            markSink(bci);
        } else if (Bytecodes.isBranch(opcode)) {
            markBranch(bci);
            if (isJSR(opcode)) {
                markJsr(bci);
            }
        } else if (isSwitch(opcode)) {
            markSwitch(bci, opcode);
        } else if (Bytecodes.isStop(opcode)) {
            markGoto(bci);
        } else if (isRet(opcode)) {
            markRet(bci);
        }
    }

    private void markSink(int bci) {
        mark(bci, IS_CONTROL_SINK);
        int next = bs.nextBCI(bci);
        if (next < bs.endBCI()) {
            mark(next, BLOCK_START);
        }
    }

    private void markBranch(int bci) {
        markTarget(bci, bs.readBranchDest(bci));
        int next = bs.nextBCI(bci);
        if (next < bs.endBCI()) {
            mark(next, BLOCK_START);
        }
    }

    private void markSwitch(int bci, int opcode) {
        BytecodeSwitch helper;
        if (opcode == Bytecodes.TABLESWITCH) {
            helper = bs.getBytecodeTableSwitch();
        } else {
            assert opcode == Bytecodes.LOOKUPSWITCH;
            helper = bs.getBytecodeLookupSwitch();
        }
        ArrayList<Integer> targets = new ArrayList<>();
        mark(bci, SWITCH);
        markTarget(bci, switchTable.size());
        for (int i = 0; i < helper.numberOfCases(bci); i++) {
            int target = helper.targetAt(bci, i);
            targets.add(target);
            mark(target, BLOCK_START);
        }
        int defaultTarget = helper.defaultTarget(bci);
        targets.add(defaultTarget);
        mark(defaultTarget, BLOCK_START);
        switchTable.add(toIntArray(targets));
    }

    private void markJsr(int bci) {
        int target = bs.readBranchDest(bci);
        mark(bci, IS_JSR);
        int returnAddress = bs.nextBCI(bci);
        jsrTable.add(new JsrMarker(target, returnAddress));
    }

    private void markRet(int bci) {
        mark(bci, IS_RET);
    }

    private void markGoto(int bci) {
        mark(bci, bs.readBranchDest(bci) & TARGET_MASK);
        mark(bs.nextBCI(bci), BLOCK_START);
        mark(bs.readBranchDest(bci), BLOCK_START);
    }

    private void markHandler(ExceptionHandler handler) {
        mark(handler.getStartBCI(), BLOCK_START);
        int afterEnd = bs.nextBCI(handler.getEndBCI());
        if (afterEnd < bs.endBCI()) {
            mark(afterEnd, BLOCK_START);
        }
        mark(handler.getHandlerBCI(), BLOCK_START);
    }

    private static int[] toIntArray(ArrayList<Integer> targets) {
        int[] result = new int[targets.size()];
        int pos = 0;
        for (int i : targets) {
            result[pos++] = i;
        }
        return result;
    }

    private void mark(int bci, long state) {
        status[bci] |= state;
    }

    private void markTarget(int bci, int targetBCI) {
        mark(targetBCI, BLOCK_START);
        mark(bci, HAS_TARGET | (targetBCI & TARGET_MASK));
    }

    private void setBlockID(int bci, int id) {
        mark(bci, ((id << 16) & BLOCK_ID_MASK) | HAS_BLOCK);
    }

    private boolean isStatus(int bci, long state) {
        return (status[bci] & state) != 0;
    }

    private int readTarget(int bci) {
        assert isStatus(bci, HAS_TARGET);
        return (int) (status[bci] & TARGET_MASK);
    }

    private int readBlockID(int bci) {
        assert isStatus(bci, HAS_BLOCK);
        return (int) ((status[bci] & BLOCK_ID_MASK) >> BLOCK_ID_SHIFT);
    }

    private static boolean isSwitch(int opcode) {
        return opcode == Bytecodes.LOOKUPSWITCH || opcode == Bytecodes.TABLESWITCH;
    }

    private static boolean isJSR(int opcode) {
        return opcode == JSR || opcode == JSR_W;
    }

    private static boolean isRet(int opcode) {
        return opcode == RET;
    }

    private static final class JsrMarker {
        private final int target;
        private final int returnAddress;

        public JsrMarker(int target, int returnAddress) {
            this.target = target;
            this.returnAddress = returnAddress;
        }
    }

    private static final class TemporaryBlock implements Block {
        private final int id;
        private final int start;
        private final int end;
        private final int[] successors;
        private final boolean isRet;

        // Additional successors
        private final ArrayList<Integer> handlers = new ArrayList<>();

        // Predecessor handling
        private final ArrayList<Integer> predecessors = new ArrayList<>();

        private int[] fullyLinkedSuccessors = null;

        TemporaryBlock(int id, int start, int end, int[] successors, boolean isRet) {
            this.id = id;
            this.start = start;
            this.end = end;
            if (successors != null) {
                this.successors = successors;
            } else {
                this.successors = new int[]{id + 1};
            }
            this.isRet = isRet;
        }

        @Override
        public int start() {
            return start;
        }

        @Override
        public int end() {
            return end;
        }

        @Override
        public int id() {
            return id;
        }

        void registerHandler(int handlerPos) {
            handlers.add(handlerPos);
        }

        void registerPredecessor(int predecessor) {
            predecessors.add(predecessor);
        }

        EspressoBlock promote(GraphBuilder builder, EspressoExecutionGraph graph) {
            if (successors.length == 0 && handlers.isEmpty() && !isRet) {
                return new EspressoBlock(graph, id, start, end, EspressoBlock.EMPTY_ID_ARRAY, toIntArray(predecessors));
            }
            if (handlers.isEmpty()) {
                return new EspressoBlock(graph, id, start, end, successors(builder), toIntArray(predecessors));
            }
            return new EspressoBlockWithHandlers(graph, id, start, end, successors(builder), toIntArray(handlers), toIntArray(predecessors));
        }

        private int[] successors(GraphBuilder builder) {
            if (fullyLinkedSuccessors == null) {
                if (successors.length == 0 && handlers.isEmpty() && !isRet) {
                    fullyLinkedSuccessors = EspressoBlock.EMPTY_ID_ARRAY;
                } else if (!isRet && handlers.isEmpty()) {
                    fullyLinkedSuccessors = successors;
                } else if (!isRet && !handlers.isEmpty()) {
                    fullyLinkedSuccessors = merge(builder.nBlocks, successors, handlers);
                } else if (isRet && handlers.isEmpty()) {
                    fullyLinkedSuccessors = merge(builder.nBlocks, successors, builder.returnTable);
                } else {
                    fullyLinkedSuccessors = merge(builder.nBlocks, successors, handlers, builder.returnTable);
                }
            }
            return fullyLinkedSuccessors;
        }

        private static int[] merge(int totalBlocks, int[] successors, List<Integer>... others) {
            int size = successors.length;
            for (List<Integer> list : others) {
                size += list.size();
            }
            int[] fullyLinkedSuccessors = new int[size];
            BitSet present = new BitSet(totalBlocks);
            for (int i = 0; i < successors.length; i++) {
                int id = successors[i];
                present.set(id);
                fullyLinkedSuccessors[i] = id;
            }
            int pos = successors.length;
            for (List<Integer> list : others) {
                for (int i : list) {
                    if (!present.get(i)) {
                        fullyLinkedSuccessors[pos++] = i;
                    }
                }
            }
            return fullyLinkedSuccessors;
        }
    }
}
