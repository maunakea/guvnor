/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.guvnor.common.services.builder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.guvnor.common.services.project.builder.service.BuildService;
import org.guvnor.common.services.project.model.Package;
import org.guvnor.common.services.project.model.Project;
import org.guvnor.common.services.project.service.ProjectService;
import org.guvnor.common.services.shared.config.AppConfigService;
import org.kie.commons.validation.PortablePreconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.uberfire.backend.server.util.Paths;
import org.uberfire.backend.vfs.Path;
import org.uberfire.workbench.events.ResourceAddedEvent;
import org.uberfire.workbench.events.ResourceBatchChangesEvent;
import org.uberfire.workbench.events.ResourceChange;
import org.uberfire.workbench.events.ResourceDeletedEvent;
import org.uberfire.workbench.events.ResourceUpdatedEvent;

/**
 * Listener for changes to project resources to handle incremental builds
 */
@ApplicationScoped
public class BuildChangeListener {

    private static final String INCREMENTAL_BUILD_PROPERTY_NAME = "build.enable-incremental";

    private static final Logger log = LoggerFactory.getLogger( BuildChangeListener.class );

    @Inject
    private Paths paths;

    @Inject
    private ProjectService projectService;

    @Inject
    private BuildService buildService;

    @Inject
    private AppConfigService appConfigService;

    @Inject
    private BuildExecutorServiceFactory executorServiceProducer;
    private ExecutorService executor;

    private boolean isIncrementalEnabled = false;

    @PostConstruct
    private void setup() {
        executor = executorServiceProducer.getExecutorService();
        isIncrementalEnabled = isIncrementalBuildEnabled();
    }

    private boolean isIncrementalBuildEnabled() {
        final String value = appConfigService.loadPreferences().get( INCREMENTAL_BUILD_PROPERTY_NAME );
        return Boolean.parseBoolean( value );
    }

    @PreDestroy
    private void destroyExecutorService() {
        try {
            executor.shutdown();
            if ( !executor.awaitTermination( 10,
                                             TimeUnit.SECONDS ) ) {
                executor.shutdownNow();
                if ( !executor.awaitTermination( 10,
                                                 TimeUnit.SECONDS ) ) {
                    log.error( "executor did not terminate" );
                }
            }
        } catch ( InterruptedException e ) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void addResource( @Observes final ResourceAddedEvent resourceAddedEvent ) {
        //Do nothing if incremental builds are disabled
        if ( !isIncrementalEnabled ) {
            return;
        }

        //Perform incremental build
        PortablePreconditions.checkNotNull( "resourceAddedEvent",
                                            resourceAddedEvent );
        final Path resource = resourceAddedEvent.getPath();
        log.info( "Incremental build request received for: " + resource.toURI() + " (added)." );

        //If resource is not within a Package it cannot be used for an incremental build
        final Package pkg = projectService.resolvePackage( resource );
        if ( pkg == null ) {
            return;
        }

        //Schedule an incremental build
        executor.execute( new Runnable() {

            @Override
            public void run() {
                try {
                    log.info( "Incremental build request being processed: " + resource.toURI() + " (added)." );
                    buildService.addPackageResource( resource );
                } catch ( Exception e ) {
                    log.error( e.getMessage(),
                               e );
                }
            }
        } );
    }

    public void deleteResource( @Observes final ResourceDeletedEvent resourceDeletedEvent ) {
        //Do nothing if incremental builds are disabled
        if ( !isIncrementalEnabled ) {
            return;
        }

        //Perform incremental build
        PortablePreconditions.checkNotNull( "resourceDeletedEvent",
                                            resourceDeletedEvent );
        final Path resource = resourceDeletedEvent.getPath();
        log.info( "Incremental build request received for: " + resource.toURI() + " (deleted)." );

        //If resource is not within a Package it cannot be used for an incremental build
        final Package pkg = projectService.resolvePackage( resource );
        if ( pkg == null ) {
            return;
        }

        //Schedule an incremental build
        executor.execute( new Runnable() {

            @Override
            public void run() {
                try {
                    log.info( "Incremental build request being processed: " + resource.toURI() + " (deleted)." );
                    buildService.deletePackageResource( resource );
                } catch ( Exception e ) {
                    log.error( e.getMessage(),
                               e );
                }
            }
        } );
    }

    public void updateResource( @Observes final ResourceUpdatedEvent resourceUpdatedEvent ) {
        //Do nothing if incremental builds are disabled
        if ( !isIncrementalEnabled ) {
            return;
        }

        //Perform incremental build
        PortablePreconditions.checkNotNull( "resourceUpdatedEvent",
                                            resourceUpdatedEvent );
        final Path resource = resourceUpdatedEvent.getPath();
        log.info( "Incremental build request received for: " + resource.toURI() + " (updated)." );

        //If resource is not within a Project it cannot be used for an incremental build
        final Project project = projectService.resolveProject( resource );
        if ( project == null ) {
            return;
        }

        //The pom.xml or kmodule.xml cannot be processed incrementally
        final boolean isPomFile = projectService.isPom( resource );
        final boolean isKModuleFile = projectService.isKModule( resource );
        if ( isPomFile || isKModuleFile ) {
            scheduleProjectResourceUpdate( resource );
        } else {
            schedulePackageResourceUpdate( resource );
        }
    }

    //Schedule an incremental build for a project resource
    private void scheduleProjectResourceUpdate( final Path resource ) {
        executor.execute( new Runnable() {

            @Override
            public void run() {
                try {
                    log.info( "Incremental build request being processed: " + resource.toURI() + " (updated)." );
                    buildService.updateProjectResource( resource );
                } catch ( Exception e ) {
                    log.error( e.getMessage(),
                               e );
                }
            }
        } );
    }

    //Schedule an incremental build for a package resource
    private void schedulePackageResourceUpdate( final Path resource ) {
        executor.execute( new Runnable() {

            @Override
            public void run() {
                try {
                    log.info( "Incremental build request being processed: " + resource.toURI() + " (updated)." );
                    buildService.updatePackageResource( resource );
                } catch ( Exception e ) {
                    log.error( e.getMessage(),
                               e );
                }
            }
        } );
    }

    public void batchResourceChanges( @Observes final ResourceBatchChangesEvent resourceBatchChangesEvent ) {
        //Do nothing if incremental builds are disabled
        if ( !isIncrementalEnabled ) {
            return;
        }

        //Perform incremental build
        PortablePreconditions.checkNotNull( "resourceBatchChangesEvent",
                                            resourceBatchChangesEvent );
        log.info( "Batch incremental build request received." );

        //Block changes together with their respective project as Builder operates at the Project level
        final Set<ResourceChange> batch = resourceBatchChangesEvent.getBatch();
        final Map<Project, Set<ResourceChange>> projectBatchChanges = new HashMap<Project, Set<ResourceChange>>();
        for ( ResourceChange change : batch ) {
            PortablePreconditions.checkNotNull( "path",
                                                change.getPath() );
            final Path resource = change.getPath();

            //If resource is not within a Package it cannot be used for an incremental build
            final Project project = projectService.resolveProject( resource );
            final Package pkg = projectService.resolvePackage( resource );
            if ( project != null && pkg != null ) {
                if ( !projectBatchChanges.containsKey( project ) ) {
                    projectBatchChanges.put( project,
                                             new HashSet<ResourceChange>() );
                }
                final Set<ResourceChange> projectChanges = projectBatchChanges.get( project );
                projectChanges.add( change );
                log.info( "- Batch content: " + change.getPath().toURI() + " (" + change.getType().toString() + ")." );
            }
        }

        //Schedule an incremental build for each Project
        for ( final Map.Entry<Project, Set<ResourceChange>> e : projectBatchChanges.entrySet() ) {
            executor.execute( new Runnable() {

                @Override
                public void run() {
                    try {
                        log.info( "Batch incremental build request being processed." );
                        buildService.applyBatchResourceChanges( e.getKey(),
                                                                e.getValue() );
                    } catch ( Exception e ) {
                        log.error( e.getMessage(),
                                   e );
                    }
                }
            } );
        }
    }

}
