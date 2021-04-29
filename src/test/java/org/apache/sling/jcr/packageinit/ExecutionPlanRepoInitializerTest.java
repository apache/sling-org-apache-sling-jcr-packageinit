/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.jcr.packageinit;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jcr.LoginException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.apache.jackrabbit.vault.packaging.PackageId;
import org.apache.jackrabbit.vault.packaging.registry.ExecutionPlan;
import org.apache.jackrabbit.vault.packaging.registry.ExecutionPlanBuilder;
import org.apache.jackrabbit.vault.packaging.registry.PackageRegistry;
import org.apache.jackrabbit.vault.packaging.registry.PackageTask;
import org.apache.jackrabbit.vault.packaging.registry.PackageTask.State;
import org.apache.jackrabbit.vault.packaging.registry.impl.FSPackageRegistry;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.packageinit.impl.ExecutionPlanRepoInitializer;
import org.apache.sling.testing.mock.osgi.MockOsgi;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ExecutionPlanRepoInitializerTest {
    
    static String EXECUTIONPLAN_1 =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<executionPlan version=\"1.0\">\n" +
                    "    <task cmd=\"extract\" packageId=\"my_packages:test_a:1.0\"/>\n" +
                    "</executionPlan>\n";
    
    static String EXECUTIONPLAN_2 =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<executionPlan version=\"1.0\">\n" +
                    "    <task cmd=\"extract\" packageId=\"my_packages:test_b:1.0\"/>\n" +
                    "</executionPlan>\n";
    
    static String[] EXECUTIONSPLANS = {EXECUTIONPLAN_1, EXECUTIONPLAN_2};
    
    static String STATUSFILE_NAME = "executedplans.file";

    @Rule
    public final SlingContext context = new SlingContext();
    
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();


    @Mock
    SlingRepository slingRepo;

    @Mock
    Session adminSession;

    @Mock
    PackageRegistry registry;

    @Mock
    ExecutionPlanBuilder builder;
    
    @Mock
    ExecutionPlanBuilder builder2;

    @Mock
    ExecutionPlan xplan;

    private ListAppender<ILoggingEvent> listAppender;

    private File statusFile;
    
    private List<Exception> foundExceptions;
    

    @Before
    public void setup() throws IOException, PackageException, LoginException, RepositoryException {
        when(registry.createExecutionPlan()).thenReturn(builder);
        when(builder.execute()).thenReturn(xplan);
        this.statusFile = temporaryFolder.newFile(STATUSFILE_NAME + UUID.randomUUID());
        final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        foundExceptions = new ArrayList<Exception>();
        when(slingRepo.loginAdministrative(null)).thenReturn(adminSession);
    }

    @After
    public void teardown() {
        final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.detachAppender(listAppender);
    }

    @Test
    public void skipWithoutExecution() throws Exception {
        ExecutionPlanRepoInitializer initializer = registerRepoInitializer(new String[]{});

        initializer.processRepository(slingRepo);
        List<ILoggingEvent> logsList = listAppender.list;
        assertThat(logsList.get(0).getFormattedMessage(),
                is("No execution plans configured - skipping init."));
    }


    @Test
    public void waitForRegistryAndInstall() throws Exception {
        ExecutionPlanRepoInitializer initializer = registerRepoInitializer(EXECUTIONSPLANS);

        CountDownLatch cdl = new CountDownLatch(1);
        processRepository(initializer, cdl, foundExceptions);

        assertTrue("processRespository() should not be completed before FSRegistry is available", cdl.getCount() > 0);
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);

        context.bundleContext().registerService(PackageRegistry.class.getName(), registry, null);
        cdl.await(500, TimeUnit.MILLISECONDS);
        verify(builder, times(2)).load(captor.capture());

        Iterator<InputStream> isIt = captor.getAllValues().iterator();
        for (String ep : EXECUTIONSPLANS) {
            StringWriter writer = new StringWriter();
            IOUtils.copy(isIt.next(), writer, "UTF-8");
            assertEquals(writer.toString(), ep);
        }
    }

    @Test
    public void doubleExecute() throws Exception {
        ExecutionPlanRepoInitializer initializer = registerRepoInitializer(EXECUTIONSPLANS);

        CountDownLatch cdl = new CountDownLatch(1);
        processRepository(initializer, cdl, foundExceptions);

        assertTrue("processRespository() should not be completed before FSRegistry is available", cdl.getCount() > 0);
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);

        context.bundleContext().registerService(PackageRegistry.class.getName(), registry, null);
        cdl.await(500, TimeUnit.MILLISECONDS);
        verify(builder, times(2)).load(captor.capture());
        
        // use different builder to reset captor
        when(registry.createExecutionPlan()).thenReturn(builder2);
        
        MockOsgi.deactivate(initializer, context.bundleContext());
        // 2nd time register in inverted order
        initializer = registerRepoInitializer(new String[] { EXECUTIONPLAN_2, EXECUTIONPLAN_1 });
        cdl = new CountDownLatch(1);
        processRepository(initializer, cdl, foundExceptions);
        
        cdl.await(500, TimeUnit.MILLISECONDS);
        verify(builder2, never()).load(captor.capture());

    }
    

    @Test
    public void failOnError() throws Exception {
        ExecutionPlanRepoInitializer initializer = registerRepoInitializer(EXECUTIONSPLANS);

        CountDownLatch cdl = new CountDownLatch(1);

        assertTrue("processRespository() should not be completed before FSRegistry is available", cdl.getCount() > 0);
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        
        List<PackageTask> ptl = new ArrayList<>();
        PackageTask pt = mock(PackageTask.class);
        when(pt.getPackageId()).thenReturn(PackageId.fromString("test:test:1.0"));
        when(pt.getState()).thenReturn(State.ERROR);
        Throwable testEx = new Throwable("expectedException");
        when(pt.getError()).thenReturn(testEx);
        ptl.add(pt);
        when(xplan.getTasks()).thenReturn(ptl);
        when(xplan.hasErrors()).thenReturn(true);

        processRepository(initializer, cdl, foundExceptions);
        
        context.bundleContext().registerService(PackageRegistry.class.getName(), registry, null);
        cdl.await(20500, TimeUnit.MILLISECONDS);
        verify(builder, times(1)).load(captor.capture());
        assertTrue("Expected IllegalStateException.",foundExceptions.get(0) instanceof IllegalStateException);
        assertEquals(testEx.getMessage(), foundExceptions.get(0).getSuppressed()[0].getMessage());

        List<ILoggingEvent> logsList = listAppender.list;
        assertEquals("Waiting for PackageRegistry.", logsList.get(0)
                                      .getMessage());
        assertEquals("PackageRegistry found - starting execution of execution plan", logsList.get(1)
            .getMessage());
    }


    private ExecutionPlanRepoInitializer registerRepoInitializer(String[] executionPlans) {
        ExecutionPlanRepoInitializer initializer = new ExecutionPlanRepoInitializer();
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put("executionplans", executionPlans);
        props.put("statusfilepath", statusFile.getAbsolutePath());
        context.registerInjectActivateService(initializer, props);
        return initializer;
    }

    private void processRepository(ExecutionPlanRepoInitializer initializer, CountDownLatch cdl, List<Exception> foundExceptions) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    initializer.processRepository(slingRepo);
                } catch (Exception e) {
                    foundExceptions.add(e);
                }
                cdl.countDown();
            }
        }).start();
    }

}
