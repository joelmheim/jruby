/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core.array;

import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.truffle.nodes.core.CoreMethodArrayArgumentsNode;
import org.jruby.truffle.runtime.RubyContext;

@ImportStatic(ArrayGuards.class)
public abstract class ArrayCoreMethodNode extends CoreMethodArrayArgumentsNode {

    public ArrayCoreMethodNode(RubyContext context, SourceSection sourceSection) {
        super(context, sourceSection);
    }

}
