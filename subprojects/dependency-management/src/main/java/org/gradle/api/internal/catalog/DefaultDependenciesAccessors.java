/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.catalog;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.initialization.dsl.VersionCatalogBuilder;
import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.internal.FeaturePreviews;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.artifacts.DefaultProjectDependencyFactory;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectRegistry;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.ClasspathNormalizer;
import org.gradle.initialization.DependenciesAccessors;
import org.gradle.internal.Cast;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.execution.ExecutionEngine;
import org.gradle.internal.execution.ExecutionResult;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.workspace.WorkspaceProvider;
import org.gradle.internal.file.TreeType;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.logging.text.TreeFormatter;
import org.gradle.internal.management.DependencyResolutionManagementInternal;
import org.gradle.internal.management.VersionCatalogBuilderInternal;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.snapshot.ValueSnapshot;
import org.gradle.util.IncubationLogger;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DefaultDependenciesAccessors implements DependenciesAccessors {
    private final static Logger LOGGER = Logging.getLogger(DefaultDependenciesAccessors.class);

    private final static String SUPPORTED_PROJECT_NAMES = "[a-zA-Z]([A-Za-z0-9\\-_])*";
    private final static Pattern SUPPORTED_PATTERN = Pattern.compile(SUPPORTED_PROJECT_NAMES);
    private final static String ACCESSORS_PACKAGE = "org.gradle.accessors.dm";
    private final static String ACCESSORS_CLASSNAME_PREFIX = "LibrariesFor";
    private final static String ROOT_PROJECT_ACCESSOR_FQCN = ACCESSORS_PACKAGE + "." + RootProjectAccessorSourceGenerator.ROOT_PROJECT_ACCESSOR_CLASSNAME;

    private final ClassPath classPath;
    private final DependenciesAccessorsWorkspaceProvider workspace;
    private final DefaultProjectDependencyFactory projectDependencyFactory;
    private final FeaturePreviews featurePreviews;
    private final ExecutionEngine engine;
    private final FileCollectionFactory fileCollectionFactory;
    private final List<DefaultVersionCatalog> models = Lists.newArrayList();
    private final Map<String, Class<? extends ExternalModuleDependencyFactory>> factories = Maps.newHashMap();

    private ClassLoaderScope classLoaderScope;
    private Class<? extends TypeSafeProjectDependencyFactory> generatedProjectFactory;
    private ClassPath sources = DefaultClassPath.of();
    private ClassPath classes = DefaultClassPath.of();

    public DefaultDependenciesAccessors(ClassPathRegistry registry,
                                        DependenciesAccessorsWorkspaceProvider workspace,
                                        DefaultProjectDependencyFactory projectDependencyFactory,
                                        FeaturePreviews featurePreview,
                                        ExecutionEngine engine,
                                        FileCollectionFactory fileCollectionFactory) {
        this.classPath = registry.getClassPath("DEPENDENCIES-EXTENSION-COMPILER");
        this.workspace = workspace;
        this.projectDependencyFactory = projectDependencyFactory;
        this.featurePreviews = featurePreview;
        this.engine = engine;
        this.fileCollectionFactory = fileCollectionFactory;
    }

    @Override
    public void generateAccessors(List<VersionCatalogBuilder> builders, ClassLoaderScope classLoaderScope, Settings settings) {
        try {
            this.classLoaderScope = classLoaderScope;
            this.models.clear(); // this is used in tests only, shouldn't happen in real context
            for (VersionCatalogBuilder builder : builders) {
                DefaultVersionCatalog model = ((VersionCatalogBuilderInternal) builder).build();
                models.add(model);
            }
            if (models.stream().anyMatch(DefaultVersionCatalog::isNotEmpty)) {
                IncubationLogger.incubatingFeatureUsed("Type-safe dependency accessors");
                for (DefaultVersionCatalog model : models) {
                    if (model.isNotEmpty()) {
                        writeDependenciesAccessors(model);
                    }
                }
            }
            if (featurePreviews.isFeatureEnabled(FeaturePreviews.Feature.TYPESAFE_PROJECT_ACCESSORS)) {
                IncubationLogger.incubatingFeatureUsed("Type-safe project accessors");
                writeProjectAccessors(((SettingsInternal) settings).getProjectRegistry());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeDependenciesAccessors(DefaultVersionCatalog model) {
        executeWork(new DependencyAccessorUnitOfWork(model));
    }

    private void writeProjectAccessors(ProjectRegistry<? extends ProjectDescriptor> projectRegistry) {
        if (!assertCanGenerateAccessors(projectRegistry)) {
            return;
        }
        executeWork(new ProjectAccessorUnitOfWork(projectRegistry));
    }

    private void executeWork(UnitOfWork work) {
        ExecutionEngine.Result result = engine.createRequest(work).execute();
        ExecutionResult er = result.getExecutionResult().get();
        GeneratedAccessors accessors = (GeneratedAccessors) er.getOutput();
        ClassPath generatedClasses = DefaultClassPath.of(accessors.classesDir);
        sources = sources.plus(DefaultClassPath.of(accessors.sourcesDir));
        classes = classes.plus(generatedClasses);
        classLoaderScope.export(generatedClasses);
    }

    private static boolean assertCanGenerateAccessors(ProjectRegistry<? extends ProjectDescriptor> projectRegistry) {
        List<String> errors = Lists.newArrayList();
        projectRegistry.getAllProjects()
            .stream()
            .map(ProjectDescriptor::getName)
            .filter(p -> !SUPPORTED_PATTERN.matcher(p).matches())
            .map(name -> "project '" + name + "' doesn't follow the naming convention: " + SUPPORTED_PROJECT_NAMES)
            .forEach(errors::add);
        for (ProjectDescriptor project : projectRegistry.getAllProjects()) {
            project.getChildren()
                .stream()
                .map(ProjectDescriptor::getName)
                .collect(Collectors.groupingBy(AbstractSourceGenerator::toJavaName))
                .entrySet()
                .stream()
                .filter(e -> e.getValue().size() > 1)
                .forEachOrdered(e -> {
                    String javaName = e.getKey();
                    List<String> names = e.getValue();
                    errors.add("subprojects " + names + " of project " + project.getPath() + " map to the same method name get" + javaName + "()");
                });
        }
        if (!errors.isEmpty()) {
            TreeFormatter formatter = new TreeFormatter();
            formatter.node("Cannot generate project dependency accessors");
            formatter.startChildren();
            for (String error : errors) {
                formatter.node("Cannot generate project dependency accessors because " + error);
            }
            formatter.endChildren();
            throw new InvalidUserDataException(formatter.toString());
        }
        return errors.isEmpty();
    }

    @Nullable
    private <T> Class<? extends T> loadFactory(ClassLoaderScope classLoaderScope, String className) {
        Class<? extends T> clazz;
        try {
            clazz = Cast.uncheckedCast(classLoaderScope.getExportClassLoader().loadClass(className));
        } catch (ClassNotFoundException e) {
            return null;
        }
        return clazz;
    }

    @Override
    public void createExtensions(ProjectInternal project) {
        ExtensionContainer container = project.getExtensions();
        try {
            if (!models.isEmpty()) {
                ImmutableMap.Builder<String, VersionCatalog> catalogs = ImmutableMap.builderWithExpectedSize(models.size());
                for (DefaultVersionCatalog model : models) {
                    if (model.isNotEmpty()) {
                        Class<? extends ExternalModuleDependencyFactory> factory;
                        synchronized (this) {
                            factory = factories.computeIfAbsent(model.getName(), n -> loadFactory(classLoaderScope, ACCESSORS_PACKAGE + "." + ACCESSORS_CLASSNAME_PREFIX + StringUtils.capitalize(n)));
                        }
                        if (factory != null) {
                            VersionCatalog catalog = container.create(model.getName(), factory, model);
                            catalogs.put(catalog.getName(), catalog);
                        }
                    }
                }
                container.create(VersionCatalogsExtension.class, "versionCatalogs", DefaultVersionCatalogsExtension.class, catalogs.build());
            }
        } finally {
            if (featurePreviews.isFeatureEnabled(FeaturePreviews.Feature.TYPESAFE_PROJECT_ACCESSORS)) {
                ServiceRegistry services = project.getServices();
                DependencyResolutionManagementInternal drm = services.get(DependencyResolutionManagementInternal.class);
                ProjectFinder projectFinder = services.get(ProjectFinder.class);
                createProjectsExtension(container, drm, projectFinder);
            }
        }
    }

    private void createProjectsExtension(ExtensionContainer container, DependencyResolutionManagementInternal drm, ProjectFinder projectFinder) {
        if (generatedProjectFactory == null) {
            synchronized (this) {
                generatedProjectFactory = loadFactory(classLoaderScope, ROOT_PROJECT_ACCESSOR_FQCN);
            }
        }
        if (generatedProjectFactory != null) {
            Property<String> defaultProjectsExtensionName = drm.getDefaultProjectsExtensionName();
            defaultProjectsExtensionName.finalizeValue();
            container.create(defaultProjectsExtensionName.get(), generatedProjectFactory, projectDependencyFactory, projectFinder);
        }
    }

    @Override
    public ClassPath getSources() {
        return sources;
    }

    @Override
    public ClassPath getClasses() {
        return classes;
    }

    private abstract class AbstractAccessorUnitOfWork implements UnitOfWork {
        private static final String OUT_SOURCES = "sources";
        private static final String OUT_CLASSES = "classes";

        @Override
        public Identity identify(Map<String, ValueSnapshot> identityInputs, Map<String, CurrentFileCollectionFingerprint> identityFileInputs) {
            return () -> {
                Hasher hasher = Hashing.sha1().newHasher();
                identityInputs.values().forEach(s -> s.appendToHasher(hasher));
                return hasher.hash().toString();
            };
        }

        @Override
        public WorkspaceProvider getWorkspaceProvider() {
            return workspace;
        }

        protected abstract List<ClassSource> getClassSources();

        @Override
        public WorkOutput execute(ExecutionRequest executionRequest) {
            File workspace = executionRequest.getWorkspace();
            File srcDir = new File(workspace, OUT_SOURCES);
            File dstDir = new File(workspace, OUT_CLASSES);
            List<ClassSource> sources = getClassSources();
            SimpleGeneratedJavaClassCompiler.compile(srcDir, dstDir, sources, classPath);
            return new WorkOutput() {
                @Override
                public WorkResult getDidWork() {
                    return WorkResult.DID_WORK;
                }

                @Override
                public Object getOutput() {
                    return loadRestoredOutput(workspace);
                }
            };
        }

        @Override
        public Object loadRestoredOutput(File workspace) {
            File srcDir = new File(workspace, OUT_SOURCES);
            File dstDir = new File(workspace, OUT_CLASSES);
            return new GeneratedAccessors(srcDir, dstDir);
        }

        @Override
        public void visitOutputs(File workspace, OutputVisitor visitor) {
            visitOutputDir(visitor, workspace, OUT_SOURCES);
            visitOutputDir(visitor, workspace, OUT_CLASSES);
        }

        private void visitOutputDir(OutputVisitor visitor, File workspace, String propertyName) {
            File dir = new File(workspace, propertyName);
            visitor.visitOutputProperty(propertyName, TreeType.DIRECTORY, dir, fileCollectionFactory.fixed(dir));
        }
    }

    private class DependencyAccessorUnitOfWork extends AbstractAccessorUnitOfWork {
        private static final String IN_DEPENDENCY_ALIASES = "dependencyAliases";
        private static final String IN_BUNDLES = "bundles";
        private static final String IN_VERSIONS = "versions";
        private static final String IN_MODEL_NAME = "modelName";
        private static final String IN_CLASSPATH = "classpath";

        private final DefaultVersionCatalog model;

        private DependencyAccessorUnitOfWork(DefaultVersionCatalog model) {
            this.model = model;
        }

        @Override
        protected List<ClassSource> getClassSources() {
            return Collections.singletonList(new DependenciesAccessorClassSource(model.getName(), model));
        }

        @Override
        public void visitInputs(InputVisitor visitor) {
            visitor.visitInputProperty(IN_DEPENDENCY_ALIASES, IdentityKind.IDENTITY, model::getDependencyAliases);
            visitor.visitInputProperty(IN_BUNDLES, IdentityKind.IDENTITY, model::getBundleAliases);
            visitor.visitInputProperty(IN_VERSIONS, IdentityKind.IDENTITY, model::getVersionAliases);
            visitor.visitInputProperty(IN_MODEL_NAME, IdentityKind.IDENTITY, model::getName);
            visitor.visitInputFileProperty(IN_CLASSPATH, InputPropertyType.NON_INCREMENTAL, IdentityKind.IDENTITY,
                new FileValueSupplier(
                    classPath,
                    ClasspathNormalizer.class,
                    DirectorySensitivity.IGNORE_DIRECTORIES,
                    () -> fileCollectionFactory.fixed(classPath.getAsFiles())));
        }

        @Override
        public String getDisplayName() {
            return "generation of dependency accessors for " + model.getName();
        }
    }

    private class ProjectAccessorUnitOfWork extends AbstractAccessorUnitOfWork {
        private final static String IN_PROJECTS = "projects";
        private final ProjectRegistry<? extends ProjectDescriptor> projectRegistry;

        public ProjectAccessorUnitOfWork(ProjectRegistry<? extends ProjectDescriptor> projectRegistry) {
            this.projectRegistry = projectRegistry;
        }

        @Override
        protected List<ClassSource> getClassSources() {
            List<ClassSource> sources = Lists.newArrayList();
            sources.add(new RootProjectAccessorSource(projectRegistry.getRootProject()));
            for (ProjectDescriptor project : projectRegistry.getAllProjects()) {
                sources.add(new ProjectAccessorClassSource(project));
            }
            return sources;
        }

        @Override
        public void visitInputs(InputVisitor visitor) {
            visitor.visitInputProperty(IN_PROJECTS, IdentityKind.IDENTITY, this::buildProjectTree);
        }

        private String buildProjectTree() {
            Set<? extends ProjectDescriptor> allprojects = projectRegistry.getAllProjects();
            return allprojects.stream()
                .map(ProjectDescriptor::getPath)
                .sorted()
                .collect(Collectors.joining(","));
        }

        @Override
        public String getDisplayName() {
            return "generation of project accessors";
        }

    }

    private static class GeneratedAccessors {
        private final File sourcesDir;
        private final File classesDir;

        private GeneratedAccessors(File sourcesDir, File classesDir) {
            this.sourcesDir = sourcesDir;
            this.classesDir = classesDir;
        }
    }

    private static class DependenciesAccessorClassSource implements ClassSource {

        private final String name;
        private final DefaultVersionCatalog model;

        private DependenciesAccessorClassSource(String name, DefaultVersionCatalog model) {
            this.name = name;
            this.model = model;
        }

        @Override
        public String getPackageName() {
            return ACCESSORS_PACKAGE;
        }

        @Override
        public String getSimpleClassName() {
            return ACCESSORS_CLASSNAME_PREFIX + StringUtils.capitalize(name);
        }

        @Override
        public String getSource() {
            StringWriter writer = new StringWriter();
            DependenciesSourceGenerator.generateSource(writer, model, ACCESSORS_PACKAGE, getSimpleClassName());
            return writer.toString();
        }
    }

    private static class ProjectAccessorClassSource implements ClassSource {
        private final ProjectDescriptor project;
        private String className;
        private String source;

        private ProjectAccessorClassSource(ProjectDescriptor project) {
            this.project = project;
        }

        @Override
        public String getPackageName() {
            return ACCESSORS_PACKAGE;
        }

        @Override
        public String getSimpleClassName() {
            ensureInitialized();
            return className;
        }

        @Override
        public String getSource() {
            ensureInitialized();
            return source;
        }

        private void ensureInitialized() {
            if (className == null) {
                StringWriter writer = new StringWriter();
                className = ProjectAccessorsSourceGenerator.generateSource(writer, project, ACCESSORS_PACKAGE);
                source = writer.toString();
            }
        }
    }

    private static class RootProjectAccessorSource implements ClassSource {
        private final ProjectDescriptor rootProject;

        private RootProjectAccessorSource(ProjectDescriptor rootProject) {
            this.rootProject = rootProject;
        }

        @Override
        public String getPackageName() {
            return ACCESSORS_PACKAGE;
        }

        @Override
        public String getSimpleClassName() {
            return RootProjectAccessorSourceGenerator.ROOT_PROJECT_ACCESSOR_CLASSNAME;
        }

        @Override
        public String getSource() {
            StringWriter writer = new StringWriter();
            RootProjectAccessorSourceGenerator.generateSource(writer, rootProject, ACCESSORS_PACKAGE);
            return writer.toString();
        }

    }

    // public for injection
    public static class DefaultVersionCatalogsExtension implements VersionCatalogsExtension {
        private final Map<String, VersionCatalog> catalogs;

        @Inject
        public DefaultVersionCatalogsExtension(Map<String, VersionCatalog> catalogs) {
            this.catalogs = catalogs;
        }

        @Override
        public Optional<VersionCatalog> find(String name) {
            if (catalogs.containsKey(name)) {
                return Optional.of(catalogs.get(name));
            }
            return Optional.empty();
        }

        @Override
        public Iterator<VersionCatalog> iterator() {
            return catalogs.values().iterator();
        }
    }
}
