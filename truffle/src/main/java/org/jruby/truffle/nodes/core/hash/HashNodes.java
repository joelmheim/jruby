/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core.hash;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.utilities.BranchProfile;
import com.oracle.truffle.api.utilities.ConditionProfile;
import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.nodes.RubyNode;
import org.jruby.truffle.nodes.core.*;
import org.jruby.truffle.nodes.core.array.ArrayBuilderNode;
import org.jruby.truffle.nodes.dispatch.CallDispatchHeadNode;
import org.jruby.truffle.nodes.dispatch.DispatchHeadNodeFactory;
import org.jruby.truffle.nodes.objects.AllocateObjectNode;
import org.jruby.truffle.nodes.objects.AllocateObjectNodeGen;
import org.jruby.truffle.nodes.yield.YieldDispatchHeadNode;
import org.jruby.truffle.runtime.NotProvided;
import org.jruby.truffle.runtime.RubyArguments;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.hash.*;
import org.jruby.truffle.runtime.layouts.Layouts;
import org.jruby.truffle.runtime.methods.InternalMethod;

import java.util.Arrays;
import java.util.Map;

@CoreClass(name = "Hash")
public abstract class HashNodes {

    @CoreMethod(names = "allocate", constructor = true)
    public abstract static class AllocateNode extends CoreMethodArrayArgumentsNode {

        @Child private AllocateObjectNode allocateObjectNode;

        public AllocateNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            allocateObjectNode = AllocateObjectNodeGen.create(context, sourceSection, null, null);
        }

        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            return allocateObjectNode.allocate(rubyClass, null, null, null, 0, null, null, false);
        }

    }

    @CoreMethod(names = "[]", constructor = true, rest = true)
    @ImportStatic(HashGuards.class)
    public abstract static class ConstructNode extends CoreMethodArrayArgumentsNode {

        @Child private HashNode hashNode;
        @Child private AllocateObjectNode allocateObjectNode;

        public ConstructNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            hashNode = new HashNode(context, sourceSection);
            allocateObjectNode = AllocateObjectNodeGen.create(context, sourceSection, null, null);
        }

        @ExplodeLoop
        @Specialization(guards = "isSmallArrayOfPairs(args)")
        public Object construct(VirtualFrame frame, DynamicObject hashClass, Object[] args) {
            final DynamicObject array = (DynamicObject) args[0];

            final Object[] store = (Object[]) Layouts.ARRAY.getStore(array);

            final int size = Layouts.ARRAY.getSize(array);
            final Object[] newStore = PackedArrayStrategy.createStore(getContext());

            for (int n = 0; n < getContext().getOptions().HASH_PACKED_ARRAY_MAX; n++) {
                if (n < size) {
                    final Object pair = store[n];

                    if (!RubyGuards.isRubyArray(pair)) {
                        return constructFallback(frame, hashClass, args);
                    }

                    final DynamicObject pairArray = (DynamicObject) pair;

                    if (!(Layouts.ARRAY.getStore(pairArray) instanceof Object[])) {
                        return constructFallback(frame, hashClass, args);
                    }

                    if (Layouts.ARRAY.getSize(pairArray) != 2) {
                        return constructFallback(frame, hashClass, args);
                    }

                    final Object[] pairStore = (Object[]) Layouts.ARRAY.getStore(pairArray);

                    final Object key = pairStore[0];
                    final Object value = pairStore[1];

                    final int hashed = hashNode.hash(frame, key);

                    PackedArrayStrategy.setHashedKeyValue(newStore, n, hashed, key, value);
                }
            }

            return allocateObjectNode.allocate(hashClass, null, null, newStore, size, null, null, false);
        }

        @Specialization(guards = "!isSmallArrayOfPairs(args)")
        public Object constructFallback(VirtualFrame frame, DynamicObject hashClass, Object[] args) {
            return ruby(frame, "_constructor_fallback(*args)", "args", Layouts.ARRAY.createArray(getContext().getCoreLibrary().getArrayFactory(), args, args.length));
        }

        public boolean isSmallArrayOfPairs(Object[] args) {
            if (args.length != 1) {
                return false;
            }

            final Object arg = args[0];

            if (!RubyGuards.isRubyArray(arg)) {
                return false;
            }

            final DynamicObject array = (DynamicObject) arg;

            if (!(Layouts.ARRAY.getStore(array) instanceof Object[])) {
                return false;
            }

            final Object[] store = (Object[]) Layouts.ARRAY.getStore(array);

            if (store.length > getContext().getOptions().HASH_PACKED_ARRAY_MAX) {
                return false;
            }

            return true;
        }

    }

    @CoreMethod(names = "[]", required = 1)
    @ImportStatic(HashGuards.class)
    public abstract static class GetIndexNode extends CoreMethodArrayArgumentsNode {

        @Child private HashNode hashNode;
        @Child private CallDispatchHeadNode eqlNode;
        @Child private BasicObjectNodes.ReferenceEqualNode equalNode;
        @Child private CallDispatchHeadNode callDefaultNode;
        @Child private LookupEntryNode lookupEntryNode;

        private final ConditionProfile byIdentityProfile = ConditionProfile.createBinaryProfile();
        private final BranchProfile notInHashProfile = BranchProfile.create();
        private final BranchProfile useDefaultProfile = BranchProfile.create();
        
        @CompilationFinal private Object undefinedValue;

        public GetIndexNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            hashNode = new HashNode(context, sourceSection);
            eqlNode = DispatchHeadNodeFactory.createMethodCall(context);
            equalNode = BasicObjectNodesFactory.ReferenceEqualNodeFactory.create(context, sourceSection, null, null);
            callDefaultNode = DispatchHeadNodeFactory.createMethodCall(context);
            lookupEntryNode = new LookupEntryNode(context, sourceSection);
        }

        public abstract Object executeGet(VirtualFrame frame, DynamicObject hash, Object key);

        @Specialization(guards = "isNullHash(hash)")
        public Object getNull(VirtualFrame frame, DynamicObject hash, Object key) {
            hashNode.hash(frame, key);

            if (undefinedValue != null) {
                return undefinedValue;
            } else {
                return callDefaultNode.call(frame, hash, "default", null, key);
            }
        }

        @ExplodeLoop
        @Specialization(guards = "isPackedHash(hash)")
        public Object getPackedArray(VirtualFrame frame, DynamicObject hash, Object key) {
            final int hashed = hashNode.hash(frame, key);

            final Object[] store = (Object[]) Layouts.HASH.getStore(hash);
            final int size = Layouts.HASH.getSize(hash);

            for (int n = 0; n < getContext().getOptions().HASH_PACKED_ARRAY_MAX; n++) {
                if (n < size) {
                    if (hashed == PackedArrayStrategy.getHashed(store, n)) {
                        final boolean equal;

                        if (byIdentityProfile.profile(Layouts.HASH.getCompareByIdentity(hash))) {
                            equal = equalNode.executeReferenceEqual(frame, key, PackedArrayStrategy.getKey(store, n));
                        } else {
                            equal = eqlNode.callBoolean(frame, key, "eql?", null, PackedArrayStrategy.getKey(store, n));
                        }

                        if (equal) {
                            return PackedArrayStrategy.getValue(store, n);
                        }
                    }
                }
            }

            notInHashProfile.enter();
            
            if (undefinedValue != null) {
                return undefinedValue;
            }

            useDefaultProfile.enter();
            return callDefaultNode.call(frame, hash, "default", null, key);

        }

        @Specialization(guards = "isBucketHash(hash)")
        public Object getBuckets(VirtualFrame frame, DynamicObject hash, Object key) {
            final HashLookupResult hashLookupResult = lookupEntryNode.lookup(frame, hash, key);

            if (hashLookupResult.getEntry() != null) {
                return hashLookupResult.getEntry().getValue();
            }

            notInHashProfile.enter();

            if (undefinedValue != null) {
                return undefinedValue;
            }

            useDefaultProfile.enter();
            return callDefaultNode.call(frame, hash, "default", null, key);
        }
        
        public void setUndefinedValue(Object undefinedValue) {
            this.undefinedValue = undefinedValue;
        }

    }
    
    @CoreMethod(names = "_get_or_undefined", required = 1)
    public abstract static class GetOrUndefinedNode extends CoreMethodArrayArgumentsNode {

        @Child private GetIndexNode getIndexNode;
        
        public GetOrUndefinedNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            getIndexNode = HashNodesFactory.GetIndexNodeFactory.create(context, sourceSection, new RubyNode[]{null, null});
            getIndexNode.setUndefinedValue(context.getCoreLibrary().getRubiniusUndefined());
        }

        @Specialization
        public Object getOrUndefined(VirtualFrame frame, DynamicObject hash, Object key) {
            return getIndexNode.executeGet(frame, hash, key);
        }

    }

    @CoreMethod(names = "[]=", required = 2, raiseIfFrozenSelf = true)
    @ImportStatic(HashGuards.class)
    public abstract static class SetIndexNode extends CoreMethodArrayArgumentsNode {

        @Child private SetNode setNode;

        public SetIndexNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object setNull(VirtualFrame frame, DynamicObject hash, Object key, Object value) {
            if (setNode == null) {
                CompilerDirectives.transferToInterpreter();
                setNode = insert(SetNodeGen.create(getContext(), getEncapsulatingSourceSection(), null, null, null, null));
            }

            return setNode.executeSet(frame, hash, key, value, Layouts.HASH.getCompareByIdentity(hash));
        }

    }

    @CoreMethod(names = "clear", raiseIfFrozenSelf = true)
    @ImportStatic(HashGuards.class)
    public abstract static class ClearNode extends CoreMethodArrayArgumentsNode {

        public ClearNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization(guards = "isNullHash(hash)")
        public DynamicObject emptyNull(DynamicObject hash) {
            return hash;
        }

        @Specialization(guards = "!isNullHash(hash)")
        public DynamicObject empty(DynamicObject hash) {
            assert HashOperations.verifyStore(getContext(), hash);
            assert HashOperations.verifyStore(getContext(), null, 0, null, null);
            Layouts.HASH.setStore(hash, null);
            Layouts.HASH.setSize(hash, 0);
            Layouts.HASH.setFirstInSequence(hash, null);
            Layouts.HASH.setLastInSequence(hash, null);

            assert HashOperations.verifyStore(getContext(), hash);
            return hash;
        }

    }

    @CoreMethod(names = "compare_by_identity", raiseIfFrozenSelf = true)
    public abstract static class CompareByIdentityNode extends CoreMethodArrayArgumentsNode {

        public CompareByIdentityNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public DynamicObject compareByIdentity(DynamicObject hash) {
            Layouts.HASH.setCompareByIdentity(hash, true);
            return hash;
        }

    }

    @CoreMethod(names = "compare_by_identity?")
    public abstract static class IsCompareByIdentityNode extends CoreMethodArrayArgumentsNode {

        private final ConditionProfile profile = ConditionProfile.createBinaryProfile();
        
        public IsCompareByIdentityNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public boolean compareByIdentity(DynamicObject hash) {
            return profile.profile(Layouts.HASH.getCompareByIdentity(hash));
        }

    }

    @CoreMethod(names = "default_proc")
    public abstract static class DefaultProcNode extends CoreMethodArrayArgumentsNode {

        public DefaultProcNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object defaultProc(DynamicObject hash) {
            if (Layouts.HASH.getDefaultBlock(hash) == null) {
                return nil();
            } else {
                return Layouts.HASH.getDefaultBlock(hash);
            }
        }

    }

    @CoreMethod(names = "delete", required = 1, needsBlock = true, raiseIfFrozenSelf = true)
    @ImportStatic(HashGuards.class)
    public abstract static class DeleteNode extends CoreMethodArrayArgumentsNode {

        @Child private HashNode hashNode;
        @Child private CallDispatchHeadNode eqlNode;
        @Child private LookupEntryNode lookupEntryNode;
        @Child private YieldDispatchHeadNode yieldNode;

        public DeleteNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            hashNode = new HashNode(context, sourceSection);
            eqlNode = DispatchHeadNodeFactory.createMethodCall(context);
            lookupEntryNode = new LookupEntryNode(context, sourceSection);
            yieldNode = new YieldDispatchHeadNode(context);
        }

        @Specialization(guards = "isNullHash(hash)")
        public Object deleteNull(VirtualFrame frame, DynamicObject hash, Object key, NotProvided block) {
            assert HashOperations.verifyStore(getContext(), hash);

            return nil();
        }

        @Specialization(guards = { "isNullHash(hash)", "isRubyProc(block)" })
        public Object deleteNull(VirtualFrame frame, DynamicObject hash, Object key, DynamicObject block) {
            assert HashOperations.verifyStore(getContext(), hash);

            return yieldNode.dispatch(frame, block, key);
        }

        @Specialization(guards = {"isPackedHash(hash)", "!isCompareByIdentity(hash)"})
        public Object deletePackedArray(VirtualFrame frame, DynamicObject hash, Object key, Object maybeBlock) {
            assert HashOperations.verifyStore(getContext(), hash);

            final int hashed = hashNode.hash(frame, key);

            final Object[] store = (Object[]) Layouts.HASH.getStore(hash);
            final int size = Layouts.HASH.getSize(hash);

            for (int n = 0; n < getContext().getOptions().HASH_PACKED_ARRAY_MAX; n++) {
                if (n < size) {
                    if (hashed == PackedArrayStrategy.getHashed(store, n)) {
                        if (eqlNode.callBoolean(frame, PackedArrayStrategy.getKey(store, n), "eql?", null, key)) {
                            final Object value = PackedArrayStrategy.getValue(store, n);
                            PackedArrayStrategy.removeEntry(getContext(), store, n);
                            Layouts.HASH.setSize(hash, size - 1);
                            assert HashOperations.verifyStore(getContext(), hash);
                            return value;
                        }
                    }
                }
            }

            assert HashOperations.verifyStore(getContext(), hash);

            if (maybeBlock == NotProvided.INSTANCE) {
                return nil();
            } else {
                return yieldNode.dispatch(frame, (DynamicObject) maybeBlock, key);
            }
        }

        @Specialization(guards = "isBucketHash(hash)")
        public Object delete(VirtualFrame frame, DynamicObject hash, Object key, Object maybeBlock) {
            assert HashOperations.verifyStore(getContext(), hash);

            final HashLookupResult hashLookupResult = lookupEntryNode.lookup(frame, hash, key);

            if (hashLookupResult.getEntry() == null) {
                if (maybeBlock == NotProvided.INSTANCE) {
                    return nil();
                } else {
                    return yieldNode.dispatch(frame, (DynamicObject) maybeBlock, key);
                }
            }

            final Entry entry = hashLookupResult.getEntry();

            // Remove from the sequence chain

            if (entry.getPreviousInSequence() == null) {
                assert Layouts.HASH.getFirstInSequence(hash) == entry;
                Layouts.HASH.setFirstInSequence(hash, entry.getNextInSequence());
            } else {
                assert Layouts.HASH.getFirstInSequence(hash) != entry;
                entry.getPreviousInSequence().setNextInSequence(entry.getNextInSequence());
            }

            if (entry.getNextInSequence() == null) {
                Layouts.HASH.setLastInSequence(hash, entry.getPreviousInSequence());
            } else {
                entry.getNextInSequence().setPreviousInSequence(entry.getPreviousInSequence());
            }

            // Remove from the lookup chain

            if (hashLookupResult.getPreviousEntry() == null) {
                ((Entry[]) Layouts.HASH.getStore(hash))[hashLookupResult.getIndex()] = entry.getNextInLookup();
            } else {
                hashLookupResult.getPreviousEntry().setNextInLookup(entry.getNextInLookup());
            }

            Layouts.HASH.setSize(hash, Layouts.HASH.getSize(hash) - 1);

            assert HashOperations.verifyStore(getContext(), hash);

            return entry.getValue();
        }

    }

    @CoreMethod(names = { "each", "each_pair" }, needsBlock = true)
    @ImportStatic(HashGuards.class)
    public abstract static class EachNode extends YieldingCoreMethodNode {

        @Child private CallDispatchHeadNode toEnumNode;
        
        public EachNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization(guards = {"isNullHash(hash)", "isRubyProc(block)"})
        public DynamicObject eachNull(DynamicObject hash, DynamicObject block) {
            return hash;
        }

        @ExplodeLoop
        @Specialization(guards = {"isPackedHash(hash)", "isRubyProc(block)"})
        public DynamicObject eachPackedArray(VirtualFrame frame, DynamicObject hash, DynamicObject block) {
            assert HashOperations.verifyStore(getContext(), hash);

            final Object[] store = (Object[]) Layouts.HASH.getStore(hash);

            int count = 0;

            try {
                for (int n = 0; n < getContext().getOptions().HASH_PACKED_ARRAY_MAX; n++) {
                    if (CompilerDirectives.inInterpreter()) {
                        count++;
                    }

                    if (n < Layouts.HASH.getSize(hash)) {
                        yield(frame, block, Layouts.ARRAY.createArray(getContext().getCoreLibrary().getArrayFactory(), new Object[]{PackedArrayStrategy.getKey(store, n), PackedArrayStrategy.getValue(store, n)}, 2));
                    }
                }
            } finally {
                if (CompilerDirectives.inInterpreter()) {
                    getRootNode().reportLoopCount(count);
                }
            }

            return hash;
        }

        @Specialization(guards = {"isBucketHash(hash)", "isRubyProc(block)"})
        public DynamicObject eachBuckets(VirtualFrame frame, DynamicObject hash, DynamicObject block) {
            assert HashOperations.verifyStore(getContext(), hash);

            for (Map.Entry<Object, Object> keyValue : BucketsStrategy.iterableKeyValues(Layouts.HASH.getFirstInSequence(hash))) {
                yield(frame, block, Layouts.ARRAY.createArray(getContext().getCoreLibrary().getArrayFactory(), new Object[]{keyValue.getKey(), keyValue.getValue()}, 2));
            }

            return hash;
        }

        @Specialization
        public Object each(VirtualFrame frame, DynamicObject hash, NotProvided block) {
            if (toEnumNode == null) {
                CompilerDirectives.transferToInterpreter();
                toEnumNode = insert(DispatchHeadNodeFactory.createMethodCallOnSelf(getContext()));
            }

            InternalMethod method = RubyArguments.getMethod(frame.getArguments());
            return toEnumNode.call(frame, hash, "to_enum", null, getSymbol(method.getName()));
        }

    }

    @CoreMethod(names = "empty?")
    @ImportStatic(HashGuards.class)
    public abstract static class EmptyNode extends CoreMethodArrayArgumentsNode {

        public EmptyNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization(guards = "isNullHash(hash)")
        public boolean emptyNull(DynamicObject hash) {
            return true;
        }

        @Specialization(guards = "!isNullHash(hash)")
        public boolean emptyPackedArray(DynamicObject hash) {
            return Layouts.HASH.getSize(hash) == 0;
        }

    }

    @CoreMethod(names = "initialize", needsBlock = true, optional = 1, raiseIfFrozenSelf = true)
    @ImportStatic(HashGuards.class)
    public abstract static class InitializeNode extends CoreMethodArrayArgumentsNode {

        public InitializeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public DynamicObject initialize(DynamicObject hash, NotProvided defaultValue, NotProvided block) {
            assert HashOperations.verifyStore(getContext(), null, 0, null, null);
            Layouts.HASH.setStore(hash, null);
            Layouts.HASH.setSize(hash, 0);
            Layouts.HASH.setFirstInSequence(hash, null);
            Layouts.HASH.setLastInSequence(hash, null);

            Layouts.HASH.setDefaultValue(hash, null);
            Layouts.HASH.setDefaultBlock(hash, null);
            return hash;
        }

        @Specialization(guards = "isRubyProc(block)")
        public DynamicObject initialize(DynamicObject hash, NotProvided defaultValue, DynamicObject block) {
            assert HashOperations.verifyStore(getContext(), null, 0, null, null);
            Layouts.HASH.setStore(hash, null);
            Layouts.HASH.setSize(hash, 0);
            Layouts.HASH.setFirstInSequence(hash, null);
            Layouts.HASH.setLastInSequence(hash, null);

            Layouts.HASH.setDefaultValue(hash, null);
            Layouts.HASH.setDefaultBlock(hash, block);
            return hash;
        }

        @Specialization(guards = "wasProvided(defaultValue)")
        public DynamicObject initialize(DynamicObject hash, Object defaultValue, NotProvided block) {
            assert HashOperations.verifyStore(getContext(), null, 0, null, null);
            Layouts.HASH.setStore(hash, null);
            Layouts.HASH.setSize(hash, 0);
            Layouts.HASH.setFirstInSequence(hash, null);
            Layouts.HASH.setLastInSequence(hash, null);

            Layouts.HASH.setDefaultValue(hash, defaultValue);
            Layouts.HASH.setDefaultBlock(hash, null);
            return hash;
        }

        @Specialization(guards = {"wasProvided(defaultValue)", "isRubyProc(block)"})
        public Object initialize(DynamicObject hash, Object defaultValue, DynamicObject block) {
            CompilerDirectives.transferToInterpreter();
            throw new RaiseException(getContext().getCoreLibrary().argumentError("wrong number of arguments (1 for 0)", this));
        }

    }

    @CoreMethod(names = {"initialize_copy", "replace"}, required = 1, raiseIfFrozenSelf = true)
    @ImportStatic(HashGuards.class)
    public abstract static class InitializeCopyNode extends CoreMethodArrayArgumentsNode {

        public InitializeCopyNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization(guards = {"isRubyHash(from)", "isNullHash(from)"})
        public DynamicObject replaceNull(DynamicObject self, DynamicObject from) {
            if (self == from) {
                return self;
            }

            assert HashOperations.verifyStore(getContext(), null, 0, null, null);
            Layouts.HASH.setStore(self, null);
            Layouts.HASH.setSize(self, 0);
            Layouts.HASH.setFirstInSequence(self, null);
            Layouts.HASH.setLastInSequence(self, null);

            copyOtherFields(self, from);

            return self;
        }

        @Specialization(guards = {"isRubyHash(from)", "isPackedHash(from)"})
        public DynamicObject replacePackedArray(DynamicObject self, DynamicObject from) {
            if (self == from) {
                return self;
            }

            final Object[] store = (Object[]) Layouts.HASH.getStore(from);
            Object store1 = PackedArrayStrategy.copyStore(getContext(), store);
            int size = Layouts.HASH.getSize(from);
            assert HashOperations.verifyStore(getContext(), store1, size, null, null);
            Layouts.HASH.setStore(self, store1);
            Layouts.HASH.setSize(self, size);
            Layouts.HASH.setFirstInSequence(self, null);
            Layouts.HASH.setLastInSequence(self, null);

            copyOtherFields(self, from);

            assert HashOperations.verifyStore(getContext(), self);

            return self;
        }

        @TruffleBoundary
        @Specialization(guards = {"isRubyHash(from)", "isBucketHash(from)"})
        public DynamicObject replaceBuckets(DynamicObject self, DynamicObject from) {
            if (self == from) {
                return self;
            }

            BucketsStrategy.copyInto(getContext(), from, self);
            copyOtherFields(self, from);

            assert HashOperations.verifyStore(getContext(), self);

            return self;
        }

        @Specialization(guards = "!isRubyHash(other)")
        public Object replaceBuckets(VirtualFrame frame, DynamicObject self, Object other) {
            return ruby(frame, "replace(Rubinius::Type.coerce_to other, Hash, :to_hash)", "other", other);
        }
        
        private void copyOtherFields(DynamicObject self, DynamicObject from) {
            Layouts.HASH.setDefaultBlock(self, Layouts.HASH.getDefaultBlock(from));
            Layouts.HASH.setDefaultValue(self, Layouts.HASH.getDefaultValue(from));
            Layouts.HASH.setCompareByIdentity(self, Layouts.HASH.getCompareByIdentity(from));
        }

    }

    @CoreMethod(names = {"map", "collect"}, needsBlock = true)
    @ImportStatic(HashGuards.class)
    public abstract static class MapNode extends YieldingCoreMethodNode {

        @Child ArrayBuilderNode arrayBuilderNode;

        public MapNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization(guards = {"isNullHash(hash)", "isRubyProc(block)"})
        public DynamicObject mapNull(VirtualFrame frame, DynamicObject hash, DynamicObject block) {
            assert HashOperations.verifyStore(getContext(), hash);

            return Layouts.ARRAY.createArray(getContext().getCoreLibrary().getArrayFactory(), null, 0);
        }

        @ExplodeLoop
        @Specialization(guards = {"isPackedHash(hash)", "isRubyProc(block)"})
        public DynamicObject mapPackedArray(VirtualFrame frame, DynamicObject hash, DynamicObject block) {
            assert HashOperations.verifyStore(getContext(), hash);

            if (arrayBuilderNode == null) {
                CompilerDirectives.transferToInterpreter();
                arrayBuilderNode = insert(new ArrayBuilderNode.UninitializedArrayBuilderNode(getContext()));
            }

            final Object[] store = (Object[]) Layouts.HASH.getStore(hash);

            final int length = Layouts.HASH.getSize(hash);
            Object resultStore = arrayBuilderNode.start(length);

            try {
                for (int n = 0; n < getContext().getOptions().HASH_PACKED_ARRAY_MAX; n++) {
                    if (n < length) {
                        final Object key = PackedArrayStrategy.getKey(store, n);
                        final Object value = PackedArrayStrategy.getValue(store, n);
                        resultStore = arrayBuilderNode.appendValue(resultStore, n, yield(frame, block, key, value));
                    }
                }
            } finally {
                if (CompilerDirectives.inInterpreter()) {
                    getRootNode().reportLoopCount(length);
                }
            }

            return Layouts.ARRAY.createArray(getContext().getCoreLibrary().getArrayFactory(), arrayBuilderNode.finish(resultStore, length), length);
        }

        @Specialization(guards = {"isBucketHash(hash)", "isRubyProc(block)"})
        public DynamicObject mapBuckets(VirtualFrame frame, DynamicObject hash, DynamicObject block) {
            assert HashOperations.verifyStore(getContext(), hash);

            if (arrayBuilderNode == null) {
                CompilerDirectives.transferToInterpreter();
                arrayBuilderNode = insert(new ArrayBuilderNode.UninitializedArrayBuilderNode(getContext()));
            }

            final int length = Layouts.HASH.getSize(hash);
            Object store = arrayBuilderNode.start(length);

            int index = 0;

            try {
                for (Map.Entry<Object, Object> keyValue : BucketsStrategy.iterableKeyValues(Layouts.HASH.getFirstInSequence(hash))) {
                    arrayBuilderNode.appendValue(store, index, yield(frame, block, keyValue.getKey(), keyValue.getValue()));
                    index++;
                }
            } finally {
                if (CompilerDirectives.inInterpreter()) {
                    getRootNode().reportLoopCount(length);
                }
            }

            return Layouts.ARRAY.createArray(getContext().getCoreLibrary().getArrayFactory(), arrayBuilderNode.finish(store, length), length);
        }

    }

    @ImportStatic(HashGuards.class)
    @CoreMethod(names = "merge", required = 1, needsBlock = true)
    public abstract static class MergeNode extends YieldingCoreMethodNode {

        @Child private CallDispatchHeadNode eqlNode;
        @Child private CallDispatchHeadNode fallbackCallNode;
        @Child private LookupEntryNode lookupEntryNode;
        @Child private SetNode setNode;
        @Child private AllocateObjectNode allocateObjectNode;

        private final BranchProfile nothingFromFirstProfile = BranchProfile.create();
        private final BranchProfile considerResultIsSmallProfile = BranchProfile.create();
        private final BranchProfile resultIsSmallProfile = BranchProfile.create();
        private final BranchProfile promoteProfile = BranchProfile.create();

        public MergeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            eqlNode = DispatchHeadNodeFactory.createMethodCall(context);
            setNode = SetNodeGen.create(context, sourceSection, null, null, null, null);
            allocateObjectNode = AllocateObjectNodeGen.create(context, sourceSection, null, null);
        }

        // Merge with an empty hash, without a block

        @Specialization(guards = {
                "isNullHash(hash)",
                "isRubyHash(other)",
                "isNullHash(other)"
        })
        public DynamicObject mergeEmptyEmpty(DynamicObject hash, DynamicObject other, NotProvided block) {
            return allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), Layouts.HASH.getDefaultBlock(hash), Layouts.HASH.getDefaultValue(hash), null, 0, null, null, false);
        }

        @Specialization(guards = {
                "isEmptyHash(hash)",
                "isRubyHash(other)",
                "isPackedHash(other)"
        })
        public DynamicObject mergeEmptyPacked(DynamicObject hash, DynamicObject other, NotProvided block) {
            final Object[] store = (Object[]) Layouts.HASH.getStore(other);
            final Object[] copy = PackedArrayStrategy.copyStore(getContext(), store);
            return allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), Layouts.HASH.getDefaultBlock(hash), Layouts.HASH.getDefaultValue(hash), copy, Layouts.HASH.getSize(other), null, null, false);
        }

        @Specialization(guards = {
                "isPackedHash(hash)",
                "isRubyHash(other)",
                "isEmptyHash(other)"
        })
        public DynamicObject mergePackedEmpty(DynamicObject hash, DynamicObject other, NotProvided block) {
            final Object[] store = (Object[]) Layouts.HASH.getStore(hash);
            final Object[] copy = PackedArrayStrategy.copyStore(getContext(), store);
            return allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), Layouts.HASH.getDefaultBlock(hash), Layouts.HASH.getDefaultValue(hash), copy, Layouts.HASH.getSize(hash), null, null, false);
        }

        @Specialization(guards = {
                "isEmptyHash(hash)",
                "isRubyHash(other)",
                "isBucketHash(other)"
        })
        public DynamicObject mergeEmptyBuckets(DynamicObject hash, DynamicObject other, NotProvided block) {
            final DynamicObject merged = allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), Layouts.HASH.getDefaultBlock(hash), Layouts.HASH.getDefaultValue(hash), null, 0, null, null, false);
            BucketsStrategy.copyInto(getContext(), other, merged);
            return merged;
        }

        @Specialization(guards = {
                "isBucketHash(hash)",
                "isRubyHash(other)",
                "isEmptyHash(other)"
        })
        public DynamicObject mergeBucketsEmpty(DynamicObject hash, DynamicObject other, NotProvided block) {
            final DynamicObject merged = allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), Layouts.HASH.getDefaultBlock(hash), Layouts.HASH.getDefaultValue(hash), null, 0, null, null, false);
            BucketsStrategy.copyInto(getContext(), hash, merged);
            return merged;
        }

        // Merge non-empty packed with non-empty packed, without a block

        @ExplodeLoop
        @Specialization(guards = {
                "isPackedHash(hash)",
                "!isEmptyHash(hash)",
                "isRubyHash(other)",
                "isPackedHash(other)",
                "!isEmptyHash(other)",
                "!isCompareByIdentity(hash)"})
        public DynamicObject mergePackedPacked(VirtualFrame frame, DynamicObject hash, DynamicObject other, NotProvided block) {
            assert HashOperations.verifyStore(getContext(), hash);
            assert HashOperations.verifyStore(getContext(), other);

            final Object[] storeA = (Object[]) Layouts.HASH.getStore(hash);
            final int storeASize = Layouts.HASH.getSize(hash);

            final Object[] storeB = (Object[]) Layouts.HASH.getStore(other);
            final int storeBSize = Layouts.HASH.getSize(other);

            // Go through and figure out what gets merged from each hash

            final boolean[] mergeFromA = new boolean[storeASize];
            int mergeFromACount = 0;

            for (int a = 0; a < getContext().getOptions().HASH_PACKED_ARRAY_MAX; a++) {
                if (a < storeASize) {
                    boolean merge = true;

                    for (int b = 0; b < getContext().getOptions().HASH_PACKED_ARRAY_MAX; b++) {
                        if (b < storeBSize) {
                            if (eqlNode.callBoolean(frame, PackedArrayStrategy.getKey(storeA, a), "eql?", null, PackedArrayStrategy.getKey(storeB, b))) {
                                merge = false;
                                break;
                            }
                        }
                    }

                    if (merge) {
                        mergeFromACount++;
                    }

                    mergeFromA[a] = merge;
                }
            }

            // If nothing comes from A, it's easy

            if (mergeFromACount == 0) {
                nothingFromFirstProfile.enter();
                return allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), Layouts.HASH.getDefaultBlock(hash), Layouts.HASH.getDefaultValue(hash), PackedArrayStrategy.copyStore(getContext(), storeB), storeBSize, null, null, false);
            }

            // Cut off here

            considerResultIsSmallProfile.enter();

            // More complicated case where some things from each hash, but it still fits in a packed array

            final int mergedSize = storeBSize + mergeFromACount;

            if (storeBSize + mergeFromACount <= getContext().getOptions().HASH_PACKED_ARRAY_MAX) {
                resultIsSmallProfile.enter();

                final Object[] merged = PackedArrayStrategy.createStore(getContext());

                int index = 0;

                for (int n = 0; n < storeASize; n++) {
                    if (mergeFromA[n]) {
                        PackedArrayStrategy.setHashedKeyValue(merged, index,
                                PackedArrayStrategy.getHashed(storeA, n),
                                PackedArrayStrategy.getKey(storeA, n),
                                PackedArrayStrategy.getValue(storeA, n));
                        index++;
                    }
                }

                for (int n = 0; n < storeBSize; n++) {
                    PackedArrayStrategy.setHashedKeyValue(merged, index,
                            PackedArrayStrategy.getHashed(storeB, n),
                            PackedArrayStrategy.getKey(storeB, n),
                            PackedArrayStrategy.getValue(storeB, n));
                    index++;
                }

                return allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), Layouts.HASH.getDefaultBlock(hash), Layouts.HASH.getDefaultValue(hash), merged, mergedSize, null, null, false);
            }

            // Most complicated cases where things from both hashes, and it also needs to be promoted to buckets

            promoteProfile.enter();

            final DynamicObject merged = allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), null, null, new Entry[BucketsStrategy.capacityGreaterThan(mergedSize)], 0, null, null, false);

            for (int n = 0; n < storeASize; n++) {
                if (mergeFromA[n]) {
                    setNode.executeSet(frame, merged, PackedArrayStrategy.getKey(storeA, n),  PackedArrayStrategy.getValue(storeA, n), false);
                }
            }

            for (int n = 0; n < storeBSize; n++) {
                setNode.executeSet(frame, merged, PackedArrayStrategy.getKey(storeB, n),  PackedArrayStrategy.getValue(storeB, n), false);
            }

            assert HashOperations.verifyStore(getContext(), hash);

            return merged;
        }

        // Merge non-empty buckets with non-empty buckets, without a block

        @Specialization(guards = {
                "isBucketHash(hash)",
                "!isEmptyHash(hash)",
                "isRubyHash(other)",
                "isBucketHash(other)",
                "!isEmptyHash(other)"
        })
        public DynamicObject mergeBucketsBuckets(VirtualFrame frame, DynamicObject hash, DynamicObject other, NotProvided block) {
            final boolean isCompareByIdentity = Layouts.HASH.getCompareByIdentity(hash);

            final DynamicObject merged = allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), null, null, new Entry[BucketsStrategy.capacityGreaterThan(Layouts.HASH.getSize(hash) + Layouts.HASH.getSize(other))], 0, null, null, false);

            for (Map.Entry<Object, Object> keyValue : BucketsStrategy.iterableKeyValues(Layouts.HASH.getFirstInSequence(hash))) {
                setNode.executeSet(frame, merged, keyValue.getKey(), keyValue.getValue(), isCompareByIdentity);
            }

            for (Map.Entry<Object, Object> keyValue : BucketsStrategy.iterableKeyValues(Layouts.HASH.getFirstInSequence(other))) {
                setNode.executeSet(frame, merged, keyValue.getKey(), keyValue.getValue(), isCompareByIdentity);
            }

            assert HashOperations.verifyStore(getContext(), hash);

            return merged;
        }

        // Merge combinations of packed and buckets, without a block

        @Specialization(guards = {
                "isPackedHash(hash)",
                "!isEmptyHash(hash)",
                "isRubyHash(other)",
                "isBucketHash(other)",
                "!isEmptyHash(other)"
        })
        public DynamicObject mergePackedBuckets(VirtualFrame frame, DynamicObject hash, DynamicObject other, NotProvided block) {
            final boolean isCompareByIdentity = Layouts.HASH.getCompareByIdentity(hash);

            final DynamicObject merged = allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), null, null, new Entry[BucketsStrategy.capacityGreaterThan(Layouts.HASH.getSize(hash) + Layouts.HASH.getSize(other))], 0, null, null, false);

            final Object[] hashStore = (Object[]) Layouts.HASH.getStore(hash);
            final int hashSize = Layouts.HASH.getSize(hash);

            for (int n = 0; n < getContext().getOptions().HASH_PACKED_ARRAY_MAX; n++) {
                if (n < hashSize) {
                    setNode.executeSet(frame, merged, PackedArrayStrategy.getKey(hashStore, n), PackedArrayStrategy.getValue(hashStore, n), isCompareByIdentity);
                }
            }

            for (Map.Entry<Object, Object> keyValue : BucketsStrategy.iterableKeyValues(Layouts.HASH.getFirstInSequence(other))) {
                setNode.executeSet(frame, merged, keyValue.getKey(), keyValue.getValue(), isCompareByIdentity);
            }

            assert HashOperations.verifyStore(getContext(), hash);

            return merged;
        }

        @Specialization(guards = {
                "isBucketHash(hash)",
                "!isEmptyHash(hash)",
                "isRubyHash(other)",
                "isPackedHash(other)",
                "!isEmptyHash(other)"
        })
        public DynamicObject mergeBucketsPacked(VirtualFrame frame, DynamicObject hash, DynamicObject other, NotProvided block) {
            final boolean isCompareByIdentity = Layouts.HASH.getCompareByIdentity(hash);

            final DynamicObject merged = allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), null, null, new Entry[BucketsStrategy.capacityGreaterThan(Layouts.HASH.getSize(hash) + Layouts.HASH.getSize(other))], 0, null, null, false);

            for (Map.Entry<Object, Object> keyValue : BucketsStrategy.iterableKeyValues(Layouts.HASH.getFirstInSequence(hash))) {
                setNode.executeSet(frame, merged, keyValue.getKey(), keyValue.getValue(), isCompareByIdentity);
            }

            final Object[] otherStore = (Object[]) Layouts.HASH.getStore(other);
            final int otherSize = Layouts.HASH.getSize(other);

            for (int n = 0; n < getContext().getOptions().HASH_PACKED_ARRAY_MAX; n++) {
                if (n < otherSize) {
                    setNode.executeSet(frame, merged, PackedArrayStrategy.getKey(otherStore, n), PackedArrayStrategy.getValue(otherStore, n), isCompareByIdentity);
                }
            }

            assert HashOperations.verifyStore(getContext(), hash);

            return merged;
        }

        // Merge using a block

        @Specialization(guards = {"isRubyHash(other)", "!isCompareByIdentity(hash)", "isRubyProc(block)"})
        public DynamicObject merge(VirtualFrame frame, DynamicObject hash, DynamicObject other, DynamicObject block) {
            CompilerDirectives.bailout("Hash#merge with a block cannot be compiled at the moment");

            final DynamicObject merged = allocateObjectNode.allocate(Layouts.BASIC_OBJECT.getLogicalClass(hash), null, null, new Entry[BucketsStrategy.capacityGreaterThan(Layouts.HASH.getSize(hash) + Layouts.HASH.getSize(other))], 0, null, null, false);

            int size = 0;

            for (Map.Entry<Object, Object> keyValue : HashOperations.iterableKeyValues(hash)) {
                setNode.executeSet(frame, merged, keyValue.getKey(), keyValue.getValue(), false);
                size++;
            }

            if (lookupEntryNode == null) {
                CompilerDirectives.transferToInterpreter();
                lookupEntryNode = insert(new LookupEntryNode(getContext(), getSourceSection()));
            }

            for (Map.Entry<Object, Object> keyValue : HashOperations.iterableKeyValues(other)) {
                final HashLookupResult searchResult = lookupEntryNode.lookup(frame, merged, keyValue.getKey());

                if (searchResult.getEntry() == null) {
                    setNode.executeSet(frame, merged, keyValue.getKey(), keyValue.getValue(), false);
                    size++;
                } else {
                    final Object oldValue = searchResult.getEntry().getValue();
                    final Object newValue = keyValue.getValue();
                    final Object mergedValue = yield(frame, block, keyValue.getKey(), oldValue, newValue);

                    setNode.executeSet(frame, merged, keyValue.getKey(), mergedValue, false);
                }
            }

            Layouts.HASH.setSize(merged, size);

            assert HashOperations.verifyStore(getContext(), hash);

            return merged;
        }

        // Merge with something that wasn't a hash

        @Specialization(guards = "!isRubyHash(other)")
        public Object merge(VirtualFrame frame, DynamicObject hash, Object other, Object maybeBlock) {
            if (fallbackCallNode == null) {
                CompilerDirectives.transferToInterpreter();
                fallbackCallNode = insert(DispatchHeadNodeFactory.createMethodCallOnSelf(getContext()));
            }
            
            final DynamicObject block;
            if (maybeBlock == NotProvided.INSTANCE) {
                block = null;
            } else {
                block = (DynamicObject) maybeBlock;
            }

            return fallbackCallNode.call(frame, hash, "merge_fallback", block, other);
        }

    }

    @CoreMethod(names = "default=", required = 1, raiseIfFrozenSelf = true)
    public abstract static class SetDefaultNode extends CoreMethodArrayArgumentsNode {

        public SetDefaultNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object setDefault(DynamicObject hash, Object defaultValue) {
            Layouts.HASH.setDefaultValue(hash, defaultValue);
            Layouts.HASH.setDefaultBlock(hash, null);
            return defaultValue;
        }
    }

    @CoreMethod(names = "shift", raiseIfFrozenSelf = true)
    @ImportStatic(HashGuards.class)
    public abstract static class ShiftNode extends CoreMethodArrayArgumentsNode {

        public ShiftNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization(guards = {"isEmptyHash(hash)", "!hasDefaultValue(hash)", "!hasDefaultBlock(hash)"})
        public DynamicObject shiftEmpty(DynamicObject hash) {
            return nil();
        }

        @Specialization(guards = {"isEmptyHash(hash)", "hasDefaultValue(hash)", "!hasDefaultBlock(hash)"})
        public Object shiftEmpyDefaultValue(DynamicObject hash) {
            return Layouts.HASH.getDefaultValue(hash);
        }

        @Specialization(guards = {"isEmptyHash(hash)", "!hasDefaultValue(hash)", "hasDefaultBlock(hash)"})
        public Object shiftEmptyDefaultProc(DynamicObject hash) {
            return ProcNodes.rootCall(Layouts.HASH.getDefaultBlock(hash), hash, nil());
        }

        @Specialization(guards = {"!isEmptyHash(hash)", "isPackedHash(hash)"})
        public DynamicObject shiftPackedArray(DynamicObject hash) {
            assert HashOperations.verifyStore(getContext(), hash);

            final Object[] store = (Object[]) Layouts.HASH.getStore(hash);
            
            final Object key = PackedArrayStrategy.getKey(store, 0);
            final Object value = PackedArrayStrategy.getValue(store, 0);
            
            PackedArrayStrategy.removeEntry(getContext(), store, 0);

            Layouts.HASH.setSize(hash, Layouts.HASH.getSize(hash) - 1);

            assert HashOperations.verifyStore(getContext(), hash);

            Object[] objects = new Object[]{key, value};
            return Layouts.ARRAY.createArray(getContext().getCoreLibrary().getArrayFactory(), objects, objects.length);
        }

        @Specialization(guards = {"!isEmptyHash(hash)", "isBucketHash(hash)"})
        public DynamicObject shiftBuckets(DynamicObject hash) {
            assert HashOperations.verifyStore(getContext(), hash);

            final Entry first = Layouts.HASH.getFirstInSequence(hash);
            assert first.getPreviousInSequence() == null;

            final Object key = first.getKey();
            final Object value = first.getValue();

            Layouts.HASH.setFirstInSequence(hash, first.getNextInSequence());

            if (first.getNextInSequence() != null) {
                first.getNextInSequence().setPreviousInSequence(null);
                Layouts.HASH.setFirstInSequence(hash, first.getNextInSequence());
            }

            if (Layouts.HASH.getLastInSequence(hash) == first) {
                Layouts.HASH.setLastInSequence(hash, null);
            }
            
            /*
             * TODO CS 7-Mar-15 this isn't great - we need to remove from the
             * lookup sequence for which we need the previous entry in the
             * bucket. However we normally get that from the search result, and
             * we haven't done a search here - we've just taken the first
             * result. For the moment we'll just do a manual search.
             */

            final Entry[] store = (Entry[]) Layouts.HASH.getStore(hash);
            
            bucketLoop: for (int n = 0; n < store.length; n++) {
                Entry previous = null;
                Entry entry = store[n];
                
                while (entry != null) {
                    if (entry == first) {
                        if (previous == null) {
                            store[n] = first.getNextInLookup();
                        } else {
                            previous.setNextInLookup(first.getNextInLookup());
                        }
                        
                        break bucketLoop;
                    }
                    
                    previous = entry;
                    entry = entry.getNextInLookup();
                }
            }


            Layouts.HASH.setSize(hash, Layouts.HASH.getSize(hash) - 1);

            assert HashOperations.verifyStore(getContext(), hash);

            Object[] objects = new Object[]{key, value};
            return Layouts.ARRAY.createArray(getContext().getCoreLibrary().getArrayFactory(), objects, objects.length);
        }

    }
    
    @CoreMethod(names = {"size", "length"})
    @ImportStatic(HashGuards.class)
    public abstract static class SizeNode extends CoreMethodArrayArgumentsNode {

        public SizeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization(guards = "isNullHash(hash)")
        public int sizeNull(DynamicObject hash) {
            return 0;
        }

        @Specialization(guards = "!isNullHash(hash)")
        public int sizePackedArray(DynamicObject hash) {
            return Layouts.HASH.getSize(hash);
        }

    }

    @CoreMethod(names = "rehash", raiseIfFrozenSelf = true)
    @ImportStatic(HashGuards.class)
    public abstract static class RehashNode extends CoreMethodArrayArgumentsNode {

        @Child private HashNode hashNode;

        public RehashNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            hashNode = new HashNode(context, sourceSection);
        }

        @Specialization(guards = "isNullHash(hash)")
        public DynamicObject rehashNull(DynamicObject hash) {
            return hash;
        }

        @Specialization(guards = "isPackedHash(hash)")
        public DynamicObject rehashPackedArray(VirtualFrame frame, DynamicObject hash) {
            assert HashOperations.verifyStore(getContext(), hash);

            final Object[] store = (Object[]) Layouts.HASH.getStore(hash);
            final int size = Layouts.HASH.getSize(hash);

            for (int n = 0; n < getContext().getOptions().HASH_PACKED_ARRAY_MAX; n++) {
                if (n < size) {
                    PackedArrayStrategy.setHashed(store, n, hashNode.hash(frame, PackedArrayStrategy.getKey(store, n)));
                }
            }

            assert HashOperations.verifyStore(getContext(), hash);

            return hash;
        }

        @TruffleBoundary
        @Specialization(guards = "isBucketHash(hash)")
        public DynamicObject rehashBuckets(DynamicObject hash) {
            assert HashOperations.verifyStore(getContext(), hash);

            final Entry[] entries = (Entry[]) Layouts.HASH.getStore(hash);
            Arrays.fill(entries, null);

            Entry entry = Layouts.HASH.getFirstInSequence(hash);

            while (entry != null) {
                final int index = BucketsStrategy.getBucketIndex(entry.getHashed(), entries.length);
                Entry bucketEntry = entries[index];

                if (bucketEntry == null) {
                    entries[index] = entry;
                } else {
                    while (bucketEntry.getNextInLookup() != null) {
                        bucketEntry = bucketEntry.getNextInLookup();
                    }

                    bucketEntry.setNextInLookup(entry);
                }

                entry = entry.getNextInSequence();
            }

            assert HashOperations.verifyStore(getContext(), hash);
            
            return hash;
        }

    }

    @RubiniusOnly
    @NodeChild(type = RubyNode.class, value = "self")
    public abstract static class DefaultValueNode extends CoreMethodNode {

        public DefaultValueNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object defaultValue(DynamicObject hash) {
            final Object value = Layouts.HASH.getDefaultValue(hash);
            
            if (value == null) {
                return nil();
            } else {
                return value;
            }
        }
    }

    @RubiniusOnly
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "self"),
            @NodeChild(type = RubyNode.class, value = "defaultValue")
    })
    public abstract static class SetDefaultValueNode extends CoreMethodNode {

        public SetDefaultValueNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object setDefaultValue(DynamicObject hash, Object defaultValue) {
            Layouts.HASH.setDefaultValue(hash, defaultValue);
            return defaultValue;
        }
        
    }

    @RubiniusOnly
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "self"),
            @NodeChild(type = RubyNode.class, value = "defaultProc")
    })
    public abstract static class SetDefaultProcNode extends CoreMethodNode {

        public SetDefaultProcNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization(guards = "isRubyProc(defaultProc)")
        public DynamicObject setDefaultProc(DynamicObject hash, DynamicObject defaultProc) {
            Layouts.HASH.setDefaultValue(hash, null);
            Layouts.HASH.setDefaultBlock(hash, defaultProc);
            return defaultProc;
        }

        @Specialization(guards = "isNil(nil)")
        public DynamicObject setDefaultProc(DynamicObject hash, Object nil) {
            Layouts.HASH.setDefaultValue(hash, null);
            Layouts.HASH.setDefaultBlock(hash, null);
            return nil();
        }

    }

}
