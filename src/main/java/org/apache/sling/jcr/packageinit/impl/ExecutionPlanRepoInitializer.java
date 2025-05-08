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
package org.apache.sling.jcr.packageinit.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.packaging.registry.ExecutionPlan;
import org.apache.jackrabbit.vault.packaging.registry.ExecutionPlanBuilder;
import org.apache.jackrabbit.vault.packaging.registry.PackageRegistry;
import org.apache.jackrabbit.vault.packaging.registry.PackageTask;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.api.SlingRepositoryInitializer;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.propertytypes.ServiceRanking;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = {SlingRepositoryInitializer.class})
@Designate(ocd = ExecutionPlanRepoInitializer.Config.class)
@ServiceRanking(200)
public class ExecutionPlanRepoInitializer implements SlingRepositoryInitializer {

    private static final String EXECUTEDPLANS_FILE = "executedplans.file";
    
    private File statusFile;

    @ObjectClassDefinition(
            name = "Execution plan based Repository Initializer"
    )
    @interface Config {
        
        @AttributeDefinition
        String statusfilepath() default "";

        @AttributeDefinition
        String[] executionplans() default {};
        
        @AttributeDefinition
        boolean reinstallSnapshots() default false;
    }

    /**
     * The logger.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private BundleContext context;
    private Config config;

    @Activate
    private void activate(BundleContext context, Config config) {
        this.context = context;
        this.config = config;
    }

    private static boolean isCandidateProcessed(String candidate, Set<Integer> executedHashes) {
        return executedHashes.contains(Integer.valueOf(candidate.hashCode()));
    }

    @Override
    public void processRepository(SlingRepository slingRepository) throws Exception {
        
        List<String> epCandidates = Arrays.asList(config.executionplans());
        Set<Integer> executedHashes = new HashSet<>();
        if (StringUtils.isEmpty(config.statusfilepath())) {
            // if no path is configured lookup default file in bundledata
            statusFile = context.getDataFile(EXECUTEDPLANS_FILE);
        } else {
            Path statusFilePath = Paths.get(config.statusfilepath());
            if (statusFilePath.isAbsolute()) {
                // only absolute references are considered for lookup of
                // external statusfile
                statusFile = statusFilePath.toFile();
            } else {
                throw new IllegalStateException("Only absolute paths supported");
            }
        }
        if (statusFile.exists()) {
            // in case statusFile already exists read all hashes
            try (BufferedReader br = new BufferedReader(new FileReader(statusFile))) {
                for (String line; (line = br.readLine()) != null;) {
                    executedHashes.add(Integer.parseInt(line));
                }
            }
        }
        
        if (!epCandidates.isEmpty()) {
            ServiceTracker<PackageRegistry, ?> st = new ServiceTracker<>(context, PackageRegistry.class, null);
            try {
                st.open();
                logger.info("Waiting for PackageRegistry.");
                PackageRegistry registry = (PackageRegistry) st.waitForService(0);
                logger.info("PackageRegistry found - starting execution of execution plan");
                
                ExecutionPlanBuilder builder = registry.createExecutionPlan();
                @SuppressWarnings("deprecation")
                Session session = slingRepository.loginAdministrative(null);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(statusFile))) {
                    for (String plan : epCandidates) {
                        builder.load(new ByteArrayInputStream(plan.getBytes(StandardCharsets.UTF_8)));
                        builder.with(session);
                        boolean planHasSnapshot = builder.preview().stream().anyMatch( p -> p.getVersionString().endsWith("-SNAPSHOT"));
                        // by default check if either there are no SNAPSHOT versions ( e.g. all releases ) or if the config was set to not reinstall snapshots
                        boolean checkIfCandidateWasProcessed = !planHasSnapshot || !config.reinstallSnapshots();
                        if ( checkIfCandidateWasProcessed && isCandidateProcessed(plan, executedHashes)) {
                            continue;
                        }
                        ExecutionPlan xplan = builder.execute();
                        if (xplan.getTasks().size() > 0) {
                            if (xplan.hasErrors()) {
                                IllegalStateException ex = new IllegalStateException("Execution plan contained errors - cannot complete repository initialization.");
                                for (PackageTask task : xplan.getTasks()) {
                                    if (PackageTask.State.ERROR.equals(task.getState())){
                                        ex.addSuppressed(task.getError());
                                    }
                                }
                                throw ex;
                            }
                            logger.info("Execution plan executed with {} entries", xplan.getTasks().size());
                        } else {
                            logger.info("No tasks found in execution plan - no additional packages installed.");
                        }
                        
                        // save hashes to file for crosscheck on subsequent startup to avoid double processing
                        writer.write(String.valueOf(plan.hashCode()));
                        writer.newLine();

                    }
                } finally {
                    session.logout();
                }
            } finally {
                st.close();
            }
        } else {
            logger.info("No execution plans configured - skipping init.");
        }
    }
}
