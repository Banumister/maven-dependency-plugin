/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.dependency.analyze;

import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.StrictPatternExcludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.StrictPatternIncludesArtifactFilter;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzer;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzerException;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.xml.PrettyPrintXMLWriter;

/**
 * Analyzes the dependencies of this project and determines which are: used and declared; used and undeclared; unused
 * and declared; compile scoped but only used in tests.
 *
 * @author <a href="mailto:markhobson@gmail.com">Mark Hobson</a>
 * @since 2.0-alpha-5
 */
public abstract class AbstractAnalyzeMojo extends AbstractMojo {
    // fields -----------------------------------------------------------------

    /**
     * The plexusContainer to look-up the right {@link ProjectDependencyAnalyzer} implementation depending on the mojo
     * configuration.
     */
    @Component
    private PlexusContainer plexusContainer;

    /**
     * The Maven project to analyze.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Specify the project dependency analyzer to use (plexus component role-hint). By default,
     * <a href="/shared/maven-dependency-analyzer/">maven-dependency-analyzer</a> is used. To use this, you must declare
     * a dependency for this plugin that contains the code for the analyzer. The analyzer must have a declared Plexus
     * role name, and you specify the role name here.
     *
     * @since 2.2
     */
    @Parameter(property = "analyzer", defaultValue = "default")
    private String analyzer;

    /**
     * Whether to fail the build if a dependency warning is found.
     */
    @Parameter(property = "failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    /**
     * Output used dependencies.
     */
    @Parameter(property = "verbose", defaultValue = "false")
    private boolean verbose;

    /**
     * Ignore Runtime/Provided/Test/System scopes for unused dependency analysis.
     *
     * <code><b>Non-test scoped</b></code> list will be not affected.
     */
    @Parameter(property = "ignoreNonCompile", defaultValue = "false")
    private boolean ignoreNonCompile;

    /**
     * Ignore Runtime scope for unused dependency analysis.
     *
     * @since 3.2.0
     */
    @Parameter(property = "ignoreUnusedRuntime", defaultValue = "false")
    private boolean ignoreUnusedRuntime;

    /**
     * Ignore all dependencies that are used only in test but not test-scoped. Setting
     * this flag has the same effect as adding all dependencies that have been flagged with
     * the <i>Non-test scoped test only dependencies found</i> warning to the
     * <code>&lt;ignoredNonTestScopedDependencies&gt;</code> configuration.
     *
     * @since 3.3.1-SNAPSHOT
     */
    @Parameter(property = "ignoreAllNonTestScoped", defaultValue = "false")
    private boolean ignoreAllNonTestScoped;

    /**
     * Output the xml for the missing dependencies (used but not declared).
     *
     * @since 2.0-alpha-5
     */
    @Parameter(property = "outputXML", defaultValue = "false")
    private boolean outputXML;

    /**
     * Output scriptable values for the missing dependencies (used but not declared).
     *
     * @since 2.0-alpha-5
     */
    @Parameter(property = "scriptableOutput", defaultValue = "false")
    private boolean scriptableOutput;

    /**
     * Flag to use for scriptable output.
     *
     * @since 2.0-alpha-5
     */
    @Parameter(property = "scriptableFlag", defaultValue = "$$$%%%")
    private String scriptableFlag;

    /**
     * Flag to use for scriptable output
     *
     * @since 2.0-alpha-5
     */
    @Parameter(defaultValue = "${basedir}", readonly = true)
    private File baseDir;

    /**
     * Target folder
     *
     * @since 2.0-alpha-5
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDirectory;

    /**
     * Force dependencies as used, to override incomplete result caused by bytecode-level analysis. Dependency format is
     * <code>groupId:artifactId</code>.
     *
     * @since 2.6
     */
    @Parameter
    private String[] usedDependencies;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "mdep.analyze.skip", defaultValue = "false")
    private boolean skip;

    /**
     * List of dependencies that will be ignored. Any dependency on this list will be excluded from the "declared but
     * unused", the "used but undeclared", and the "non-test scoped" list. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> matches all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> matches all snapshot artifacts.
     * </p>
     *
     * @since 2.10
     */
    @Parameter
    private final String[] ignoredDependencies = new String[0];

    /**
     * List of dependencies that will be ignored if they are used but undeclared. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> matches all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> matches all snapshot artifacts.
     * </p>
     *
     * @since 2.10
     */
    @Parameter
    private final String[] ignoredUsedUndeclaredDependencies = new String[0];

    /**
     * List of dependencies that will be ignored if they are declared but unused. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> matches all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> matches all snapshot artifacts.
     * </p>
     *
     * @since 2.10
     */
    @Parameter
    private final String[] ignoredUnusedDeclaredDependencies = new String[0];

    /**
     * List of dependencies that will be ignored if they are in not test scope but are only used in test classes.
     * The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> matches all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> matches all snapshot artifacts.
     * </p>
     *
     * @since 3.3.0
     */
    @Parameter
    private final String[] ignoredNonTestScopedDependencies = new String[0];

    /**
     * List of project packaging that will be ignored.
     * <br/>
     * <b>Default value is<b>: <code>pom, ear</code>
     *
     * @since 3.2.1
     */
    // defaultValue value on @Parameter - not work with Maven 3.2.5
    // When is set defaultValue always win, and there is no possibility to override by plugin configuration.
    @Parameter
    private final List<String> ignoredPackagings = Arrays.asList( "pom", "ear" );


    /**
     * List of dependencies to be included. When not set includes all dependencies (default).
     *
     * <p>The ignore parameters are then applied to the result of the include parameter.</p>
     *
     * <p>If an artifact matches on both include then ignore, that artifact won't result
     * in a dependency problem.</p>
     *
     * The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> matches all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> matches all snapshot artifacts.
     * </p>
     *
     * @since 3.6.1-SNAPSHOT
     */
    @Parameter
    private final String[] includeDependencies = new String[0];

    // Mojo methods -----------------------------------------------------------

    /*
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (isSkip()) {
            getLog().info("Skipping plugin execution");
            return;
        }

        if (ignoredPackagings.contains(project.getPackaging())) {
            getLog().info("Skipping " + project.getPackaging() + " project");
            return;
        }

        if (outputDirectory == null || !outputDirectory.exists()) {
            getLog().info("Skipping project with no build directory");
            return;
        }

        boolean warning = checkDependencies();

        if (warning && failOnWarning) {
            throw new MojoExecutionException("Dependency problems found");
        }
    }

    /**
     * @return {@link ProjectDependencyAnalyzer}
     * @throws MojoExecutionException in case of an error.
     */
    protected ProjectDependencyAnalyzer createProjectDependencyAnalyzer() throws MojoExecutionException {

        try {
            return plexusContainer.lookup(ProjectDependencyAnalyzer.class, analyzer);
        } catch (ComponentLookupException exception) {
            throw new MojoExecutionException(
                    "Failed to instantiate ProjectDependencyAnalyser" + " / role-hint " + analyzer, exception);
        }
    }

    /**
     * @return {@link #skip}
     */
    protected final boolean isSkip() {
        return skip;
    }

    // private methods --------------------------------------------------------

    private boolean checkDependencies() throws MojoExecutionException {
        ProjectDependencyAnalysis analysis;
        try {
            analysis = createProjectDependencyAnalyzer().analyze(project);

            if (usedDependencies != null) {
                analysis = analysis.forceDeclaredDependenciesUsage(usedDependencies);
            }
        } catch (ProjectDependencyAnalyzerException exception) {
            throw new MojoExecutionException("Cannot analyze dependencies", exception);
        }

        if (ignoreNonCompile) {
            analysis = analysis.ignoreNonCompile();
        }

        Set<Artifact> usedDeclared = new LinkedHashSet<>(analysis.getUsedDeclaredArtifacts());
        Map<Artifact, Set<String>> usedUndeclaredWithClasses =
                new LinkedHashMap<>(analysis.getUsedUndeclaredArtifactsWithClasses());
        Set<Artifact> unusedDeclared = new LinkedHashSet<>(analysis.getUnusedDeclaredArtifacts());
        Set<Artifact> nonTestScope = new LinkedHashSet<>(analysis.getTestArtifactsWithNonTestScope());

        Set<Artifact> notIncludedUsedDeclared = new LinkedHashSet<>();
        Set<Artifact> notIncludedUsedUndeclared = new LinkedHashSet<>();
        Set<Artifact> notIncludedUnusedDeclared = new LinkedHashSet<>();
        Set<Artifact> notIncludedNonTestScope = new LinkedHashSet<>();

        Set<Artifact> ignoredUsedUndeclared = new LinkedHashSet<>();
        Set<Artifact> ignoredUnusedDeclared = new LinkedHashSet<>();
        Set<Artifact> ignoredNonTestScope = new LinkedHashSet<>();

        // Used declared handling
        notIncludedUsedDeclared.addAll( filterDependencies( usedDeclared,
          includeDependencies ) );

        // Used undeclared handling
        notIncludedUsedUndeclared.addAll( filterDependencies( usedUndeclaredWithClasses.keySet(),
          includeDependencies ) );

        ignoredUsedUndeclared.addAll( filterDependenciesNotMatching( usedUndeclaredWithClasses.keySet(),
          ignoredDependencies ) );
        ignoredUsedUndeclared.addAll( filterDependenciesNotMatching( usedUndeclaredWithClasses.keySet(),
          ignoredUsedUndeclaredDependencies ) );

        // Unused declared handling
        if ( ignoreUnusedRuntime )
        {
          filterArtifactsByScope( unusedDeclared, Artifact.SCOPE_RUNTIME );
        }

        notIncludedUnusedDeclared.addAll( filterDependencies( unusedDeclared, includeDependencies ) );
        ignoredUnusedDeclared.addAll( filterDependenciesNotMatching( unusedDeclared,
          ignoredDependencies ) );
        ignoredUnusedDeclared.addAll( filterDependenciesNotMatching( unusedDeclared,
          ignoredUnusedDeclaredDependencies ) );

        // Non-test scope handling
        notIncludedNonTestScope.addAll( filterDependencies ( nonTestScope, includeDependencies ) );
        if ( ignoreAllNonTestScoped )
        {
            ignoredNonTestScope.addAll( filterDependenciesNotMatching ( nonTestScope, new String [] { "*" } ) );
        }
        else
        {
            ignoredNonTestScope.addAll( filterDependenciesNotMatching( nonTestScope, ignoredDependencies ) );
            ignoredNonTestScope.addAll( filterDependenciesNotMatching( nonTestScope,
              ignoredNonTestScopedDependencies ) );
        }

        boolean reported = false;
        boolean warning = false;

        if (verbose && !usedDeclared.isEmpty()) {
            getLog().info("Used declared dependencies found:");

            logArtifacts(analysis.getUsedDeclaredArtifacts(), false);
            reported = true;
        }

        if (!usedUndeclaredWithClasses.isEmpty()) {
            logDependencyWarning("Used undeclared dependencies found:");

            if (verbose) {
                logArtifacts(usedUndeclaredWithClasses, true);
            } else {
                logArtifacts(usedUndeclaredWithClasses.keySet(), true);
            }
            reported = true;
            warning = true;
        }

        if (!unusedDeclared.isEmpty()) {
            logDependencyWarning("Unused declared dependencies found:");

            logArtifacts(unusedDeclared, true);
            reported = true;
            warning = true;
        }

        if (!nonTestScope.isEmpty()) {
            logDependencyWarning("Non-test scoped test only dependencies found:");

            logArtifacts(nonTestScope, true);
            reported = true;
            warning = true;
        }

        // log dependencies that weren't included
        if ( verbose && !notIncludedUsedDeclared.isEmpty() )
        {
            getLog().info( "Not included used declared dependencies:" );

            logArtifacts( notIncludedUsedDeclared, false );
            reported = true;
        }

        if ( verbose && !notIncludedUsedUndeclared.isEmpty() )
        {
            getLog().info( "Not included used undeclared dependencies:" );

            logArtifacts( notIncludedUsedUndeclared, false );
            reported = true;
        }

        if ( verbose && !notIncludedUnusedDeclared.isEmpty() )
        {
            getLog().info( "Not included unused declared dependencies:" );

            logArtifacts( notIncludedUnusedDeclared, false );
            reported = true;
        }

        if ( verbose && !notIncludedNonTestScope.isEmpty() )
        {
            getLog().info( "Not included non-test scoped test only dependencies:" );

            logArtifacts( notIncludedNonTestScope, false );
            reported = true;
        }

        // log ignored dependencies
        if ( verbose && !ignoredUsedUndeclared.isEmpty() )
        {
            getLog().info( "Ignored used undeclared dependencies:" );

            logArtifacts(ignoredUsedUndeclared, false);
            reported = true;
        }

        if (verbose && !ignoredUnusedDeclared.isEmpty()) {
            getLog().info("Ignored unused declared dependencies:");

            logArtifacts(ignoredUnusedDeclared, false);
            reported = true;
        }

        if (verbose && !ignoredNonTestScope.isEmpty()) {
            getLog().info("Ignored non-test scoped test only dependencies:");

            logArtifacts(ignoredNonTestScope, false);
            reported = true;
        }

        if (outputXML) {
            writeDependencyXML(usedUndeclaredWithClasses.keySet());
        }

        if (scriptableOutput) {
            writeScriptableOutput(usedUndeclaredWithClasses.keySet());
        }

        if (!reported) {
            getLog().info("No dependency problems found");
        }

        return warning;
    }

    private void filterArtifactsByScope(Set<Artifact> artifacts, String scope) {
        artifacts.removeIf(artifact -> artifact.getScope().equals(scope));
    }

    private void logArtifacts(Set<Artifact> artifacts, boolean warn) {
        if (artifacts.isEmpty()) {
            getLog().info("   None");
        } else {
            for (Artifact artifact : artifacts) {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                artifact.isSnapshot();

                if (warn) {
                    logDependencyWarning("   " + artifact);
                } else {
                    getLog().info("   " + artifact);
                }
            }
        }
    }

    private void logArtifacts(Map<Artifact, Set<String>> artifacts, boolean warn) {
        if (artifacts.isEmpty()) {
            getLog().info("   None");
        } else {
            for (Map.Entry<Artifact, Set<String>> entry : artifacts.entrySet()) {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                entry.getKey().isSnapshot();

                if (warn) {
                    logDependencyWarning("   " + entry.getKey());
                    for (String clazz : entry.getValue()) {
                        logDependencyWarning("      class " + clazz);
                    }
                } else {
                    getLog().info("   " + entry.getKey());
                    for (String clazz : entry.getValue()) {
                        getLog().info("      class " + clazz);
                    }
                }
            }
        }
    }

    private void logDependencyWarning(CharSequence content) {
        if (failOnWarning) {
            getLog().error(content);
        } else {
            getLog().warn(content);
        }
    }

    private void writeDependencyXML(Set<Artifact> artifacts) {
        if (!artifacts.isEmpty()) {
            getLog().info("Add the following to your pom to correct the missing dependencies: ");

            StringWriter out = new StringWriter();
            PrettyPrintXMLWriter writer = new PrettyPrintXMLWriter(out);

            for (Artifact artifact : artifacts) {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                artifact.isSnapshot();

                writer.startElement("dependency");
                writer.startElement("groupId");
                writer.writeText(artifact.getGroupId());
                writer.endElement();
                writer.startElement("artifactId");
                writer.writeText(artifact.getArtifactId());
                writer.endElement();
                writer.startElement("version");
                writer.writeText(artifact.getBaseVersion());
                String classifier = artifact.getClassifier();
                if (StringUtils.isNotBlank(classifier)) {
                    writer.startElement("classifier");
                    writer.writeText(artifact.getClassifier());
                    writer.endElement();
                }
                writer.endElement();

                if (!Artifact.SCOPE_COMPILE.equals(artifact.getScope())) {
                    writer.startElement("scope");
                    writer.writeText(artifact.getScope());
                    writer.endElement();
                }
                writer.endElement();
            }

            getLog().info(System.lineSeparator() + out.getBuffer());
        }
    }

    private void writeScriptableOutput(Set<Artifact> artifacts) {
        if (!artifacts.isEmpty()) {
            getLog().info("Missing dependencies: ");
            String pomFile = baseDir.getAbsolutePath() + File.separatorChar + "pom.xml";
            StringBuilder buf = new StringBuilder();

            for (Artifact artifact : artifacts) {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                artifact.isSnapshot();

                buf.append(scriptableFlag)
                        .append(":")
                        .append(pomFile)
                        .append(":")
                        .append(artifact.getDependencyConflictId())
                        .append(":")
                        .append(artifact.getClassifier())
                        .append(":")
                        .append(artifact.getBaseVersion())
                        .append(":")
                        .append(artifact.getScope())
                        .append(System.lineSeparator());
            }
            getLog().info(System.lineSeparator() + buf);
        }
    }

    /**
     * Filter for artifacts that don't match the <code>excludes</code> criteria.
     *
     * @param artifacts filtered by elements that don't match the <code>excludes</code> argument
     * @param excludes the filter to be applied
     * @return the list of artifacts that matched the criteria
     */
    private List<Artifact> filterDependenciesNotMatching( Set<Artifact> artifacts, String[] excludes )
    {
        ArtifactFilter filter = new StrictPatternExcludesArtifactFilter( Arrays.asList( excludes ) );

        List<Artifact> result = new ArrayList<>();

        for (Iterator<Artifact> it = artifacts.iterator(); it.hasNext(); ) {
            Artifact artifact = it.next();
            if (!filter.include(artifact)) {
                it.remove();
                result.add(artifact);
            }
        }

        return result;
    }


    /**
     * Filter for artifacts that match the <code>includes</code> criteria.
     * No filtering is applied if the criteria is empty.
     *
     * @param artifacts filtered for elements that do match the criteria
     * @param includes the filter to be applied
     * @return the list of artifacts that didn't match the criteria
     */
    private List<Artifact> filterDependencies( Set<Artifact> artifacts, String[] includes )
    {
        List<Artifact> result = new ArrayList<>();

        if ( includes.length > 0 )
        {
          ArtifactFilter filter = new StrictPatternIncludesArtifactFilter( Arrays.asList( includes ) );

            for ( Iterator<Artifact> it = artifacts.iterator(); it.hasNext(); )
            {
                Artifact artifact = it.next();
                if ( !filter.include( artifact ) )
                {
                    it.remove();
                    result.add( artifact );
                }
            }
        }

        return result;
    }
}
