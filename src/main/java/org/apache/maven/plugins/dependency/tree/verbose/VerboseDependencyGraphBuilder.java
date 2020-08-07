package org.apache.maven.plugins.dependency.tree.verbose;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector;
import org.eclipse.aether.util.graph.transformer.ChainedDependencyGraphTransformer;
import org.eclipse.aether.util.graph.transformer.JavaDependencyContextRefiner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Builds the VerboseDependencyGraph
 */
public class VerboseDependencyGraphBuilder
{
    private static final String PRE_MANAGED_SCOPE = "preManagedScope", PRE_MANAGED_VERSION = "preManagedVersion",
            MANAGED_SCOPE = "managedScope";

    public DependencyNode buildVerboseGraph( MavenProject project, ProjectDependenciesResolver resolver,
                                                         RepositorySystemSession repositorySystemSession )
            throws DependencyResolutionException
    {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager( repositorySystemSession.getLocalRepositoryManager() );

        DependencySelector dependencySelector = new AndDependencySelector(
                // ScopeDependencySelector takes exclusions. 'Provided' scope is not here to avoid
                // false positive in LinkageChecker.
                new ScopeDependencySelector(),
                new ExclusionDependencySelector() );

        session.setDependencySelector( dependencySelector );
        session.setDependencyGraphTransformer( new ChainedDependencyGraphTransformer(
                new CycleBreakerGraphTransformer(), // Avoids StackOverflowError
                new JavaDependencyContextRefiner() ) );
        session.setDependencyManager( null );

        DependencyResolutionRequest request = new DefaultDependencyResolutionRequest();
        request.setMavenProject( project );
        request.setRepositorySession( session );
        request.setResolutionFilter( null );

        DependencyNode rootNode = resolver.resolve( request ).getDependencyGraph();
        // Don't want transitive test dependencies included in analysis
        DependencyNode prunedRoot = pruneTransitiveTestDependencies( rootNode, project );
        applyDependencyManagement( project, prunedRoot );
        return prunedRoot;
    }

    private void applyDependencyManagement( MavenProject project, DependencyNode root )
    {
        Map<String, org.apache.maven.model.Dependency> dependencyManagementMap = createDependencyManagementMap(
                project.getDependencyManagement() );

        for ( DependencyNode child : root.getChildren() )
        {
            for ( DependencyNode nonTransitiveDependencyNode : child.getChildren() )
            {
                applyDependencyManagementDfs( dependencyManagementMap, nonTransitiveDependencyNode );
            }
        }
    }

    private void applyDependencyManagementDfs( Map<String, org.apache.maven.model.Dependency> dependencyManagementMap,
                                               DependencyNode node )
    {
        if ( dependencyManagementMap.containsKey( getDependencyManagementCoordinate( node.getArtifact() ) ) )
        {
            org.apache.maven.model.Dependency manager = dependencyManagementMap.get(
                    getDependencyManagementCoordinate( node.getArtifact() ) );
            Map<String, String> artifactProperties = new HashMap<>();
            for ( Map.Entry<String, String> entry : node.getArtifact().getProperties().entrySet() )
            {
                artifactProperties.put( entry.getKey(), entry.getValue() );
            }

            if ( !manager.getVersion().equals( node.getArtifact().getVersion() ) )
            {
                artifactProperties.put( PRE_MANAGED_VERSION, node.getArtifact().getVersion() );
                node.setArtifact( node.getArtifact().setVersion( manager.getVersion() ) );
            }

            if ( !manager.getScope().equals( node.getDependency().getScope() ) )
            {
                artifactProperties.put( PRE_MANAGED_SCOPE, node.getDependency().getScope() );
                // be aware this does not actually change the node's scope, it may need to be fixed in the future
                artifactProperties.put( MANAGED_SCOPE, manager.getScope() );
            }
            node.setArtifact( node.getArtifact().setProperties( artifactProperties ) );
            node.getDependency().setArtifact( node.getDependency().getArtifact().setProperties( artifactProperties ) );
        }
        for ( DependencyNode child : node.getChildren() )
        {
            applyDependencyManagementDfs( dependencyManagementMap, child );
        }
    }

    private static Map<String, org.apache.maven.model.Dependency> createDependencyManagementMap(
            DependencyManagement dependencyManagement )
    {
        Map<String, org.apache.maven.model.Dependency> dependencyManagementMap = new HashMap<>();
        if ( dependencyManagement == null )
        {
            return dependencyManagementMap;
        }
        for ( org.apache.maven.model.Dependency dependency : dependencyManagement.getDependencies() )
        {
            dependencyManagementMap.put( getDependencyManagementCoordinate( dependency ), dependency );
        }
        return dependencyManagementMap;
    }

    private static String getDependencyManagementCoordinate( org.apache.maven.model.Dependency dependency )
    {
        StringBuilder string = new StringBuilder();
        string.append( dependency.getGroupId() ).append( ":" ).append( dependency.getArtifactId() )
                .append( ":" ).append( dependency.getType() );
        if ( dependency.getClassifier() != null && !dependency.getClassifier().equals( "" ) )
        {
            string.append( ":" ).append( dependency.getClassifier() );
        }
        return string.toString();
    }

    private static String getDependencyManagementCoordinate( Artifact artifact )
    {
        StringBuilder string = new StringBuilder();
        string.append( artifact.getGroupId() ).append( ":" ).append( artifact.getArtifactId() ).append( ":" )
                .append( artifact.getExtension() );
        if ( artifact.getClassifier() != null && !artifact.getClassifier().equals( "" ) )
        {
            string.append( ":" ).append( artifact.getClassifier() );
        }
        return string.toString();
    }

    private Dependency getProjectDependency( MavenProject project )
    {
        Model model = project.getModel();

        return new Dependency( new DefaultArtifact( model.getGroupId(), model.getArtifactId(), model.getPackaging(),
                model.getVersion() ), "" );
    }

    private DependencyNode pruneTransitiveTestDependencies( DependencyNode rootNode, MavenProject project )
    {
        Set<DependencyNode> visitedNodes = new HashSet<>();
        DependencyNode newRoot = new DefaultDependencyNode( getProjectDependency( project ) );
        newRoot.setChildren( new ArrayList<DependencyNode>() );

        for ( int i = 0; i < rootNode.getChildren().size(); i++ )
        {
            DependencyNode childNode = rootNode.getChildren().get( i );
            newRoot.getChildren().add( childNode );

            pruneTransitiveTestDependenciesDfs( childNode, visitedNodes );
        }

        return newRoot;
    }

    private void pruneTransitiveTestDependenciesDfs( DependencyNode node , Set<DependencyNode> visitedNodes )
    {
        if ( !visitedNodes.contains( node ) )
        {
            visitedNodes.add( node );
            // iterator needed to avoid concurrentModificationException
            Iterator<DependencyNode> iterator = node.getChildren().iterator();
            while ( iterator.hasNext() )
            {
                DependencyNode child = iterator.next();
                if ( child.getDependency().getScope().equals( "test" ) )
                {
                    iterator.remove();
                }
                else
                {
                    pruneTransitiveTestDependenciesDfs( child, visitedNodes );
                }
            }
        }
    }
}