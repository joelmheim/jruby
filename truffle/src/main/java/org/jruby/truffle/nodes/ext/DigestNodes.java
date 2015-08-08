/*
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.ext;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.*;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.ext.digest.BubbleBabble;
import org.jruby.truffle.nodes.core.*;
import org.jruby.truffle.om.dsl.api.*;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.core.RubyBasicObject;
import org.jruby.util.ByteList;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;

@CoreClass(name = "Truffle::Digest")
public abstract class DigestNodes {

    @org.jruby.truffle.om.dsl.api.Layout
    public interface DigestLayout extends BasicObjectNodes.BasicObjectLayout {

        DynamicObjectFactory createDigestShape(RubyBasicObject logicalClass, RubyBasicObject metaClass);

        DynamicObject createDigest(DynamicObjectFactory factory, MessageDigest digest);

        MessageDigest getDigest(DynamicObject object);

    }

    public static final DigestLayout DIGEST_LAYOUT = DigestLayoutImpl.INSTANCE;

    private enum Algorithm {
        MD5("MD5"),
        SHA1("SHA1"),
        SHA256("SHA-256"),
        SHA384("SHA-384"),
        SHA512("SHA-512");

        private final String name;

        Algorithm(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private static RubyBasicObject createDigest(RubyContext context, Algorithm algorithm) {
        final MessageDigest digest;

        try {
            digest = MessageDigest.getInstance(algorithm.getName());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        final RubyBasicObject rubyClass = context.getCoreLibrary().getDigestClass();

        return BasicObjectNodes.createRubyBasicObject(rubyClass, DIGEST_LAYOUT.createDigest(ModuleNodes.getModel(rubyClass).factory, digest));
    }

    public static MessageDigest getDigest(RubyBasicObject digest) {
        return DIGEST_LAYOUT.getDigest(digest.dynamicObject);
    }

    @CoreMethod(names = "md5", onSingleton = true)
    public abstract static class MD5Node extends CoreMethodArrayArgumentsNode {

        public MD5Node(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public RubyBasicObject md5() {
            return createDigest(getContext(), Algorithm.MD5);
        }

    }

    @CoreMethod(names = "sha1", onSingleton = true)
    public abstract static class SHA1Node extends CoreMethodArrayArgumentsNode {

        public SHA1Node(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public RubyBasicObject sha1() {
            return createDigest(getContext(), Algorithm.SHA1);
        }

    }

    @CoreMethod(names = "sha256", onSingleton = true)
    public abstract static class SHA256Node extends CoreMethodArrayArgumentsNode {

        public SHA256Node(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public RubyBasicObject sha256() {
            return createDigest(getContext(), Algorithm.SHA256);
        }

    }

    @CoreMethod(names = "sha384", onSingleton = true)
    public abstract static class SHA384Node extends CoreMethodArrayArgumentsNode {

        public SHA384Node(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public RubyBasicObject sha384() {
            return createDigest(getContext(), Algorithm.SHA384);
        }

    }

    @CoreMethod(names = "sha512", onSingleton = true)
    public abstract static class SHA512Node extends CoreMethodArrayArgumentsNode {

        public SHA512Node(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public RubyBasicObject sha512() {
            return createDigest(getContext(), Algorithm.SHA512);
        }

    }

    @CoreMethod(names = "update", onSingleton = true, required = 2)
    public abstract static class UpdateNode extends CoreMethodArrayArgumentsNode {

        public UpdateNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization(guards = "isRubyString(message)")
        public RubyBasicObject update(RubyBasicObject digestObject, RubyBasicObject message) {
            final ByteList bytes = StringNodes.getByteList(message);
            getDigest(digestObject).update(bytes.getUnsafeBytes(), bytes.begin(), bytes.length());
            return digestObject;
        }

    }

    @CoreMethod(names = "reset", onSingleton = true, required = 1)
    public abstract static class ResetNode extends CoreMethodArrayArgumentsNode {

        public ResetNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public RubyBasicObject reset(RubyBasicObject digestObject) {
            getDigest(digestObject).reset();
            return digestObject;
        }

    }

    @CoreMethod(names = "digest", onSingleton = true, required = 1)
    public abstract static class DigestNode extends CoreMethodArrayArgumentsNode {

        public DigestNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public RubyBasicObject digest(RubyBasicObject digestObject) {
            final MessageDigest digest = getDigest(digestObject);

            // TODO CS 18-May-15 this cloning isn't ideal for the key operation

            final MessageDigest clonedDigest;

            try {
                clonedDigest = (MessageDigest) digest.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }

            return createString(clonedDigest.digest());
        }

    }

    @CoreMethod(names = "digest_length", onSingleton = true, required = 1)
    public abstract static class DigestLengthNode extends CoreMethodArrayArgumentsNode {

        public DigestLengthNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public int digestLength(RubyBasicObject digestObject) {
            return getDigest(digestObject).getDigestLength();
        }

    }

    @CoreMethod(names = "bubblebabble", onSingleton = true, required = 1)
    public abstract static class BubbleBabbleNode extends CoreMethodArrayArgumentsNode {

        public BubbleBabbleNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization(guards = "isRubyString(message)")
        public RubyBasicObject bubblebabble(RubyBasicObject message) {
            final ByteList byteList = StringNodes.getByteList(message);
            return createString(BubbleBabble.bubblebabble(byteList.unsafeBytes(), byteList.begin(), byteList.length()));
        }

    }

}
