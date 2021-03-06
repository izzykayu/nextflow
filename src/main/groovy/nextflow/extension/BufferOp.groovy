/*
 * Copyright (c) 2013-2018, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2018, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.extension

import static nextflow.extension.DataflowHelper.newOperator
import static nextflow.util.CheckHelper.checkParams

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowQueue
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.operator.DataflowEventAdapter
import groovyx.gpars.dataflow.operator.DataflowProcessor
import groovyx.gpars.dataflow.operator.PoisonPill
import nextflow.Global
import nextflow.Session
import org.codehaus.groovy.runtime.callsite.BooleanReturningMethodInvoker

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class BufferOp {

    static final private Map BUFFER_PARAMS = [
            skip: Integer,
            size: Integer,
            remainder: Boolean
    ]

    @Lazy
    private static Session session = Global.session as Session

    private Map params

    private DataflowReadChannel source

    private DataflowQueue target

    private Object openCondition

    private Object closeCondition

    private int skip

    private int size

    private boolean remainder

    private int skipCount = 0

    private int itemCount = 0


    BufferOp( DataflowReadChannel source ) {
        assert source
        this.source = source
    }

    BufferOp setParams( Map params ) {
        checkParams( 'buffer', params, BUFFER_PARAMS )
        this.params = params
        return this
    }

    BufferOp setStartCriteria( Object criteria ) {
        this.openCondition = criteria
        return this
    }

    BufferOp setCloseCriteria( Object criteria ) {
        this.closeCondition = criteria
        return this
    }

    DataflowQueue apply() {
        target = new DataflowQueue()

        if( params?.skip )
            this.skip = params.skip as int
        if( params?.size )
            this.size = params.size as int 
        if( params?.remainder == true )
            this.remainder = true

        if( (skip||size) && openCondition )
            throw new IllegalArgumentException()

        if( (skip||size) && closeCondition )
            throw new IllegalArgumentException()

        Closure c1=null
        Closure c2=null
        if( skip || size ) {
            c1 = createSkipCriteria()
            c2 = createSizeCriteria()
        }
        else {
            if( openCondition ) c1 = createCriteria(openCondition)
            if( closeCondition ) c2 = createCriteria(closeCondition)
        }

        buffer0(source, target, c1, c2, remainder)
        return target
    }

    private Closure createCriteria( Object condition ) {
        def invoker = new BooleanReturningMethodInvoker("isCase")
        return { Object it -> invoker.invoke(condition, it) }
    }

    private Closure createSkipCriteria( ) {
        return {
            skipCount +=1
            if( skipCount > skip ) {
                skipCount = 0
                return true
            }
            return false
        }
    }

    private Closure createSizeCriteria( ) {
        return {
            itemCount +=1
            if( itemCount-skip == size ) {
                itemCount = 0;
                return true
            }
            return false
        }
    }

    @CompileDynamic
    static private <V> void buffer0(DataflowReadChannel<V> source, DataflowQueue target, Closure startingCriteria, Closure closeCriteria, boolean remainder ) {
        assert closeCriteria

        // the list holding temporary collected elements
        def buffer = []

        // -- intercepts the PoisonPill and sent out the items remaining in the buffer when the 'remainder' flag is true
        def listener = new DataflowEventAdapter() {

            Object controlMessageArrived(final DataflowProcessor processor, final DataflowReadChannel<Object> channel, final int index, final Object message) {
                if( message instanceof PoisonPill && remainder && buffer.size() ) {
                    target.bind(buffer)
                }
                return message
            }

            @Override
            boolean onException(DataflowProcessor processor, Throwable e) {
                DataflowExtensions.log.error("@unknown", e)
                session.abort(e)
                return true
            }
        }

        // -- open frame flag
        boolean isOpen = startingCriteria == null

        // -- the operator collecting the elements
        newOperator( source, target, listener ) {
            if( isOpen ) {
                buffer << it
            }
            else if( startingCriteria.call(it) ) {
                isOpen = true
                buffer << it
            }

            if( closeCriteria.call(it) ) {
                ((DataflowProcessor) getDelegate()).bindOutput(buffer);
                buffer = []
                // when a *startingCriteria* is defined, close the open frame flag
                isOpen = (startingCriteria == null)
            }
        }
    }

}
