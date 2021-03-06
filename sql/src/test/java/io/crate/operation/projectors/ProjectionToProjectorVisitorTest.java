/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.projectors;

import com.google.common.collect.ImmutableList;
import io.crate.breaker.RamAccountingContext;
import io.crate.executor.transport.TransportActionProvider;
import io.crate.metadata.*;
import io.crate.operation.ImplementationSymbolVisitor;
import io.crate.operation.aggregation.impl.AggregationImplModule;
import io.crate.operation.aggregation.impl.AverageAggregation;
import io.crate.operation.aggregation.impl.CountAggregation;
import io.crate.operation.operator.EqOperator;
import io.crate.operation.operator.OperatorModule;
import io.crate.planner.RowGranularity;
import io.crate.planner.projection.AggregationProjection;
import io.crate.planner.projection.FilterProjection;
import io.crate.planner.projection.GroupProjection;
import io.crate.planner.projection.TopNProjection;
import io.crate.planner.symbol.*;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.number.OrderingComparison.lessThanOrEqualTo;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;


public class ProjectionToProjectorVisitorTest {

    protected static final RamAccountingContext RAM_ACCOUNTING_CONTEXT =
            new RamAccountingContext("dummy", new NoopCircuitBreaker(CircuitBreaker.Name.FIELDDATA));
    private ProjectionToProjectorVisitor visitor;
    private FunctionInfo countInfo;
    private FunctionInfo avgInfo;
    private Functions functions;

    @Before
    public void prepare() {
        MockitoAnnotations.initMocks(this);
        ReferenceResolver referenceResolver = new GlobalReferenceResolver(new HashMap<ReferenceIdent, ReferenceImplementation>());
        Injector injector = new ModulesBuilder()
                .add(new AggregationImplModule())
                .add(new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(Client.class).toInstance(mock(Client.class));
                    }
                })
                .add(new OperatorModule())
                .createInjector();
        functions = injector.getInstance(Functions.class);
        ImplementationSymbolVisitor symbolvisitor =
                new ImplementationSymbolVisitor(referenceResolver, functions, RowGranularity.NODE);
        visitor = new ProjectionToProjectorVisitor(
                mock(ClusterService.class),
                ImmutableSettings.EMPTY,
                mock(TransportActionProvider.class, Answers.RETURNS_DEEP_STUBS.get()),
                symbolvisitor);

        countInfo = new FunctionInfo(new FunctionIdent(CountAggregation.NAME, Arrays.<DataType>asList(DataTypes.STRING)), DataTypes.LONG);
        avgInfo = new FunctionInfo(new FunctionIdent(AverageAggregation.NAME, Arrays.<DataType>asList(DataTypes.INTEGER)), DataTypes.DOUBLE);
    }

    @Test
    public void testSimpleTopNProjection() throws ExecutionException, InterruptedException {
        TopNProjection projection = new TopNProjection(10, 2);
        projection.outputs(Arrays.<Symbol>asList(Literal.newLiteral("foo"), new InputColumn(0)));

        CollectingProjector collectingProjector = new CollectingProjector();
        Projector projector = visitor.process(projection, RAM_ACCOUNTING_CONTEXT);
        projector.registerUpstream(null);
        projector.downstream(collectingProjector);
        assertThat(projector, instanceOf(SimpleTopNProjector.class));

        projector.startProjection();
        int i;
        for (i = 0; i<20; i++) {
            if (!projector.setNextRow(42)) {
                break;
            }
        }
        assertThat(i, is(11));
        projector.upstreamFinished();
        Object[][] rows = collectingProjector.result().get();
        assertThat(rows.length, is(10));
        assertThat((BytesRef) rows[0][0], is(new BytesRef("foo")));
        assertThat((Integer)rows[0][1], is(42));
    }

    @Test
    public void testSortingTopNProjection() throws ExecutionException, InterruptedException {
        TopNProjection projection = new TopNProjection(10, 0,
                Arrays.<Symbol>asList(new InputColumn(0), new InputColumn(1)),
                new boolean[]{false, false},
                new Boolean[] { null, null }
        );
        projection.outputs(Arrays.<Symbol>asList(Literal.newLiteral("foo"), new InputColumn(0), new InputColumn(1)));
        Projector projector = visitor.process(projection, RAM_ACCOUNTING_CONTEXT);
        projector.registerUpstream(null);
        assertThat(projector, instanceOf(SortingTopNProjector.class));

        projector.startProjection();
        int i;
        for (i = 20; i>0; i--) {
            if (!projector.setNextRow(i%4, i)) {
                break;
            }
        }
        assertThat(i, is(0));
        projector.upstreamFinished();
        Object[][] rows = ((ResultProvider)projector).result().get();
        assertThat(rows.length, is(10));
        assertThat(rows[0].length, is(3));

        Object[] formerRow = null;
        for (Object[] row : rows) {
            if (formerRow == null) {
                formerRow = row;
                continue;
            }
            assertThat((BytesRef)row[0], is(new BytesRef("foo")));
            assertThat((Integer)formerRow[1], lessThanOrEqualTo((Integer)row[1]));
            if (formerRow[1].equals(row[1]))  {
                assertThat((Integer)formerRow[2], lessThanOrEqualTo((Integer)row[2]));
            }
        }
    }

    @Test
    public void testAggregationProjector() throws ExecutionException, InterruptedException {
        AggregationProjection projection = new AggregationProjection();
        projection.aggregations(Arrays.asList(
                new Aggregation(avgInfo, Arrays.<Symbol>asList(new InputColumn(1)), Aggregation.Step.ITER, Aggregation.Step.FINAL),
                new Aggregation(countInfo, Arrays.<Symbol>asList(new InputColumn(0)), Aggregation.Step.ITER, Aggregation.Step.FINAL)
        ));
        Projector projector = visitor.process(projection, RAM_ACCOUNTING_CONTEXT);
        CollectingProjector collectingProjector = new CollectingProjector();
        projector.downstream(collectingProjector);
        assertThat(projector, instanceOf(AggregationProjector.class));

        projector.startProjection();
        projector.setNextRow("foo", 10);
        projector.setNextRow("bar", 20);
        projector.upstreamFinished();
        Object[][] rows = collectingProjector.result().get();
        assertThat(rows.length, is(1));
        assertThat((Double)rows[0][0], is(15.0));   // avg
        assertThat((Long)rows[0][1], is(2L));       // count
    }

    @Test
    public void testGroupProjector() throws ExecutionException, InterruptedException {
        //         in(0)  in(1)      in(0),      in(2)
        // select  race, avg(age), count(race), gender  ... group by race, gender
        GroupProjection projection = new GroupProjection();
        projection.keys(Arrays.<Symbol>asList(new InputColumn(0, DataTypes.STRING), new InputColumn(2, DataTypes.STRING)));
        projection.values(Arrays.asList(
                new Aggregation(avgInfo, Arrays.<Symbol>asList(new InputColumn(1)), Aggregation.Step.ITER, Aggregation.Step.FINAL),
                new Aggregation(countInfo, Arrays.<Symbol>asList(new InputColumn(0)), Aggregation.Step.ITER, Aggregation.Step.FINAL)
        ));

        Projector projector = visitor.process(projection, RAM_ACCOUNTING_CONTEXT);
        projector.registerUpstream(null);

        // use a topN projection in order to get sorted outputs
        TopNProjection topNProjection = new TopNProjection(10, 0,
                ImmutableList.<Symbol>of(new InputColumn(2, DataTypes.DOUBLE)),
                new boolean[]{false},
                new Boolean[] { null });
        topNProjection.outputs(Arrays.<Symbol>asList(
                new InputColumn(0, DataTypes.STRING), new InputColumn(1, DataTypes.STRING),
                new InputColumn(2, DataTypes.DOUBLE), new InputColumn(3, DataTypes.LONG)));
        SortingTopNProjector topNProjector = (SortingTopNProjector)visitor.process(topNProjection, RAM_ACCOUNTING_CONTEXT);
        projector.downstream(topNProjector);
        topNProjector.startProjection();

        assertThat(projector, instanceOf(GroupingProjector.class));

        projector.startProjection();
        BytesRef human = new BytesRef("human");
        BytesRef vogon = new BytesRef("vogon");
        BytesRef male = new BytesRef("male");
        BytesRef female = new BytesRef("female");
        projector.setNextRow(human, 34, male);
        projector.setNextRow(human, 22, female);
        projector.setNextRow(vogon, 40, male);
        projector.setNextRow(vogon, 48, male);
        projector.setNextRow(human, 34, male);
        projector.upstreamFinished();

        Object[][] rows = topNProjector.result().get();
        assertThat(rows.length, is(3));
        assertThat((BytesRef)rows[0][0], is(human));
        assertThat((BytesRef)rows[0][1], is(female));
        assertThat((Double)rows[0][2], is(22.0));
        assertThat((Long)rows[0][3], is(1L));

        assertThat((BytesRef)rows[1][0], is(human));
        assertThat((BytesRef)rows[1][1], is(male));
        assertThat((Double)rows[1][2], is(34.0));
        assertThat((Long)rows[1][3], is(2L));

        assertThat((BytesRef)rows[2][0], is(vogon));
        assertThat((BytesRef)rows[2][1], is(male));
        assertThat((Double)rows[2][2], is(44.0));
        assertThat((Long)rows[2][3], is(2L));
    }

    @Test
    public void testFilterProjection() throws Exception {
        EqOperator op = (EqOperator)functions.get(
                new FunctionIdent(EqOperator.NAME, ImmutableList.<DataType>of(DataTypes.INTEGER, DataTypes.INTEGER)));
        Function function = new Function(
                op.info(), Arrays.<Symbol>asList(Literal.newLiteral(2), new InputColumn(1)));
        FilterProjection projection = new FilterProjection(function);
        projection.outputs(Arrays.<Symbol>asList(new InputColumn(0), new InputColumn(1)));

        CollectingProjector collectingProjector = new CollectingProjector();
        Projector projector = visitor.process(projection, RAM_ACCOUNTING_CONTEXT);
        projector.registerUpstream(null);
        projector.downstream(collectingProjector);
        assertThat(projector, instanceOf(FilterProjector.class));

        projector.startProjection();
        projector.setNextRow("human", 2);
        projector.setNextRow("vogon", 1);

        projector.upstreamFinished();

        Object[][] rows = collectingProjector.result().get();
        assertThat(rows.length, is(1));
    }

}
