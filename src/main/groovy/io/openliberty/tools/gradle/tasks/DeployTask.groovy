/**
 * (C) Copyright IBM Corporation 2014, 2023.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openliberty.tools.gradle.tasks

import io.openliberty.tools.gradle.utils.*
import io.openliberty.tools.ant.ServerTask
import io.openliberty.tools.common.plugins.config.ApplicationXmlDocument
import io.openliberty.tools.common.plugins.config.LooseApplication
import io.openliberty.tools.common.plugins.config.LooseConfigData
import io.openliberty.tools.common.plugins.config.ServerConfigDocument
import io.openliberty.tools.common.plugins.util.DevUtil
import io.openliberty.tools.common.plugins.util.OSUtil

import org.apache.commons.io.FilenameUtils
import org.gradle.api.artifacts.UnknownConfigurationException
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

import java.lang.NumberFormatException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.MessageFormat
import java.util.HashSet
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.io.File

class DeployTask extends AbstractServerTask {

    protected ApplicationXmlDocument applicationXml = new ApplicationXmlDocument();

    private static final String LIBS = "libs";
    private static final String BUILD_LIBS = "build/" + LIBS;

    DeployTask() {
        configure({
            description "Copy applications generated by the Gradle project to a Liberty server's dropins or apps directory."
            group 'Liberty'
            project.afterEvaluate {
                springBootVersion = findSpringBootVersion(project)
                springBootTask = findSpringBootTask(project, springBootVersion)
                springBootBuildTask = determineSpringBootBuildTask()
            }
        })
    }

    boolean containsTask(List<Task> taskList, String name) {
        return taskList.find { it.getName().equals(name) }
    }

    @TaskAction
    void deploy() {
        boolean hasSpringBootAppConfigured

        configureApps(project)
        if (server.deploy.apps != null && !server.deploy.apps.isEmpty()) {
            hasSpringBootAppConfigured = server.deploy.apps.find { it.name.equals springBootBuildTask ?. name  }
            createApplicationFolder('apps')
            Tuple appsLists = splitAppList(server.deploy.apps)
            installMultipleApps(appsLists[0], 'apps')
            installFileList(appsLists[1], 'apps')
        }
        if (server.deploy.dropins != null && !server.deploy.dropins.isEmpty()) {
            if (server.deploy.dropins.find { it.name.equals springBootBuildTask ?. name  }) {
                if (hasSpringBootAppConfigured) {
                    throw new GradleException("Spring boot applications were configured for both the dropins and app folder. Only one " +
                            "spring boot application may be configured per server.")
                }
                else {
                    hasSpringBootAppConfigured = true
                }
            }
            createApplicationFolder('dropins')
            Tuple dropinsLists = splitAppList(server.deploy.dropins)
            installMultipleApps(dropinsLists[0], 'dropins')
            installFileList(dropinsLists[1], 'dropins')
        }

        File libertyConfigDropinsAppXml = ApplicationXmlDocument.getApplicationXmlFile(getServerDir(project))

        if (applicationXml.hasChildElements()) {
            logger.warn("At least one application is not defined in the server configuration but the build file indicates it should be installed in the apps folder. Application configuration is being added to the target server configuration dropins folder by the plug-in.")
            applicationXml.writeApplicationXmlDocument(getServerDir(project))
        } else if (hasConfiguredApp(libertyConfigDropinsAppXml)) {
            logger.warn("At least one application is not defined in the server configuration but the build file indicates it should be installed in the apps folder. Liberty will use additional application configuration added to the the target server configuration dropins folder by the plug-in.")
        } else {
            if (libertyConfigDropinsAppXml.exists()){
                libertyConfigDropinsAppXml.delete()
                ServerConfigDocument.markInstanceStale()
            }
        }
    }

    private void installMultipleApps(List<Task> applications, String appsDir) {
        applications.each{ Task task ->
            installProject(task, appsDir)
        }
    }

    private void installProjectArchive(Task task, String appsDir) {
        String archiveBaseName
        String fileName
        if("springboot".equals(getPackagingType())) {
            archiveBaseName = springBootTask.baseName
            installSpringBootFeatureIfNeeded()
            String targetThinAppPath = invokeThinOperation(appsDir)
            fileName = targetThinAppPath.substring(targetThinAppPath.lastIndexOf("/") + 1)
            validateAppConfig(targetThinAppPath.substring(targetThinAppPath.lastIndexOf("/") + 1), archiveBaseName, appsDir)
        } else {
            archiveBaseName = task.baseName
            fileName = getArchiveName(task)
            Files.copy(task.archivePath.toPath(), new File(getServerDir(project), "/" + appsDir + "/" + getArchiveName(task)).toPath(), StandardCopyOption.REPLACE_EXISTING)
            validateAppConfig(getArchiveName(task), archiveBaseName, appsDir)
        }
        validateAppConfig(fileName, archiveBaseName, appsDir)
        verifyAppStarted(fileName, appsDir)
    }

    protected void validateAppConfig(String fileName, String artifactId, String dir) throws Exception {
        String appsDir = dir
        if (appsDir.equalsIgnoreCase('apps') && !isAppConfiguredInSourceServerXml(fileName)) {
            applicationXml.createApplicationElement(fileName, artifactId, springBootVersion!=null)
        }
        else if (appsDir.equalsIgnoreCase('dropins') && isAppConfiguredInSourceServerXml(fileName)) {
            throw new GradleException("The application, " + artifactId + ", is configured in the server.xml and the plug-in is configured to install the application in the dropins folder. A configured application must be installed to the apps folder.")
        }
    }

    protected void installProject(Task task, String appsDir) throws Exception {
        if(isSupportedType()) {
            if(server.looseApplication){
                installLooseApplication(task, appsDir)
            } else {
                installProjectArchive(task, appsDir)
            }
        } else {
            throw new GradleException(MessageFormat.format("Application {0} is not supported", task.archiveName))
        }
    }


    private String getArchiveOutputPath() {
        String archiveOutputPath;

        if (springBootVersion.startsWith('2.')) {
            archiveOutputPath = springBootTask.archivePath.getAbsolutePath()
        }
        else if(springBootVersion.startsWith('1.')) {
            archiveOutputPath = springBootTask.archivePath.getAbsolutePath()
            if (project.bootRepackage.classifier != null && !project.bootRepackage.classifier.isEmpty()) {
                archiveOutputPath = archiveOutputPath.substring(0, archiveOutputPath.lastIndexOf(".")) + "-" + project.bootRepackage.classifier + "." + springBootTask.extension
            }
        }

        if(archiveOutputPath != null && io.openliberty.tools.common.plugins.util.SpringBootUtil.isSpringBootUberJar(new File(archiveOutputPath))) {
            return archiveOutputPath
        } else {
            throw new GradleException(archiveOutputPath + " is not a valid Spring Boot Uber JAR")
        }
    }


    private String getTargetLibCachePath() {
        new File(getInstallDir(project), "usr/shared/resources/lib.index.cache").absolutePath
    }

    private String getTargetThinAppPath(String appsDir, String sourceArchiveName) {
        String appsFolder
        if (appsDir=="dropins") {
            appsFolder = "dropins/spring"
        }
        else {
            appsFolder = "apps"
        }
        new File(createApplicationFolder(appsFolder).absolutePath, sourceArchiveName)
    }

    private String invokeThinOperation(String appsDir) {
        Map<String, String> params = buildLibertyMap(project);

        project.ant.taskdef(name: 'invokeUtil',
                classname: 'io.openliberty.tools.ant.SpringBootUtilTask',
                classpath: project.buildscript.configurations.classpath.asPath)

        String sourceAppPath = getArchiveOutputPath()
        params.put('sourceAppPath', sourceAppPath)
        params.put('targetLibCachePath', getTargetLibCachePath())
        String targetThinAppPath = getTargetThinAppPath(appsDir, "thin-" + sourceAppPath.substring(sourceAppPath.lastIndexOf(File.separator) + 1))
        params.put('targetThinAppPath', targetThinAppPath)
        project.ant.invokeUtil(params)
        return targetThinAppPath;
    }

    private isSpringBootUtilAvailable() {
        new FileNameFinder().getFileNames(new File(getInstallDir(project), "bin").getAbsolutePath(), "springBootUtility*")
    }

    private installSpringBootFeatureIfNeeded() {
        if (isClosedLiberty() && !isSpringBootUtilAvailable()) {
            String fileSuffix = isWindows ? ".bat" : ""
            File installUtil = new File(getInstallDir(project), "bin/installUtility"+fileSuffix)

            def installCommand = installUtil.toString() + " install springBoot-1.5 springBoot-2.0 --acceptLicense"
            def sbuff = new StringBuffer()
            def proc = installCommand.execute()
            proc.consumeProcessOutput(sbuff, sbuff)
            proc.waitFor()
            logger.info(sbuff.toString())
            if (proc.exitValue()!=0) {
                throw new GradleException("Error installing required spring boot support features.")
            }
        }
    }

    private void installLooseApplication(Task task, String appsDir) throws Exception {
        String looseConfigFileName = getLooseConfigFileName(task)
        String application = looseConfigFileName.substring(0, looseConfigFileName.length()-4)
        File destDir = new File(getServerDir(project), appsDir)
        File looseConfigFile = new File(destDir, looseConfigFileName)
        
        File devcDestDir = new File(new File(project.buildDir, DevUtil.DEVC_HIDDEN_FOLDER), appsDir)
        File devcLooseConfigFile = new File(devcDestDir, looseConfigFileName)

        LooseConfigData config = new LooseConfigData()

        switch(getPackagingType()){
            case "war":
                validateAppConfig(application, task.baseName, appsDir)
                logger.info(MessageFormat.format(("Installing application into the {0} folder."), looseConfigFile.getAbsolutePath()))
                installLooseConfigWar(config, task, false)
                installAndVerify(config, looseConfigFile, application, appsDir)
                if (project.liberty.dev.container) {
                    // install another copy that is container specific
                    config = new LooseConfigData()
                    installLooseConfigWar(config, task, true)
                    config.toXmlFile(devcLooseConfigFile)
                }
                break
            case "ear":
                if ((String.valueOf(project.getGradle().getGradleVersion().charAt(0)) as int) < 4) {
                    throw new Exception(MessageFormat.format(("Loose Ear is only supported by Gradle 4.0 or higher")))
                }
                validateAppConfig(application, task.baseName, appsDir)
                logger.info(MessageFormat.format(("Installing application into the {0} folder."), looseConfigFile.getAbsolutePath()))
                installLooseConfigEar(config, task)
                installAndVerify(config, looseConfigFile, application, appsDir)
                break
            default:
                logger.info(MessageFormat.format(("Loose application configuration is not supported for packaging type {0}. The project artifact will be installed as an archive file."),
                        getPackagingType()))
                installProjectArchive(task, appsDir)
                break
        }
    }

    private void installAndVerify(LooseConfigData config, File looseConfigFile, String applicationName, String appsDir) {
        deleteApplication(new File(getServerDir(project), "apps"), looseConfigFile)
        deleteApplication(new File(getServerDir(project), "dropins"), looseConfigFile)
        config.toXmlFile(looseConfigFile)
        verifyAppStarted(applicationName, appsDir)
    }

    protected void installLooseConfigWar(LooseConfigData config, Task task, boolean container) throws Exception {
        Task compileJava = task.getProject().tasks.findByPath(':compileJava')

        File outputDir;
        if(compileJava != null){
            outputDir = compileJava.destinationDir
        }

        if (outputDir != null && !outputDir.exists() && hasJavaSourceFiles(task.classpath, outputDir)) {
            logger.warn(MessageFormat.format("Installed loose application from project {0}, but the project has not been compiled.", project.name))
        }

        if (container) {
            try {
                // Set up the config to replace the absolute path names with ${variable}/target type references
                config.setProjectRoot(project.getProjectDir().getCanonicalPath());
                config.setSourceOnDiskName('${'+DevUtil.DEVMODE_PROJECT_ROOT+'}');

                if (server.deploy.copyLibsDirectory == null) { // in container mode, copy dependencies from .m2 dir to the build dir to mount in container
                    // if buildDir is subdirectory of projectDir, use buildDir/libs.  Otherwise use projectDir/build/libs since it must be under the project root in order to make use of DEVMODE_PROJECT_ROOT
                    if (project.getBuildDir().getCanonicalFile().toPath().startsWith(project.getProjectDir().getCanonicalFile().toPath())) {
                        server.deploy.copyLibsDirectory = new File(project.getBuildDir(), LIBS);
                        logger.debug("Setting copyLibsDirectory to " + server.deploy.copyLibsDirectory);
                    } else {
                        server.deploy.copyLibsDirectory = new File(project.getProjectDir(), BUILD_LIBS);
                        // This is temporary until we add variable substitution for the build dir root.
                        logger.debug("The directory indicated by the buildDir property should be within the Gradle project directory when the container option is specified.  Setting copyLibsDirectory to " + server.deploy.copyLibsDirectory);
                    }
                } else {
                    // test the user defined copyLibsDirectory extension for use in a container
                    String projectPath = project.getProjectDir().getCanonicalPath();
                    String copyLibsPath = server.deploy.copyLibsDirectory.getCanonicalPath();
                    if (!copyLibsPath.startsWith(projectPath)) {
                        // Flag an error but allow processing to continue in case dependencies, if any, are not actually referenced by the app.
                        logger.error("The directory indicated by the copyLibsDirectory extension must be within the Gradle project directory when the container option is specified.");
                    }
                }
            } catch (IOException i) {
                // an IOException here should fail the build
                throw new GradleException("Could not resolve the canonical path of the Gradle project, build directory, or the directory specified in the copyLibsDirectory extension. Exception message:"+i.getMessage(), i);
            }
        }

        LooseWarApplication looseWar = new LooseWarApplication(task, config)
        looseWar.addSourceDir()
        looseWar.addOutputDir(looseWar.getDocumentRoot() , task.classpath.getFiles().toArray()[0], "/WEB-INF/classes/");

        //retrieves dependent library jar files
        addWarEmbeddedLib(looseWar.getDocumentRoot(), looseWar, task);

        //add Manifest file
        File manifestFile = new File(project.buildDir.getAbsolutePath() + "/tmp/war/MANIFEST.MF")
        looseWar.addManifestFile(manifestFile)
    }

    private boolean hasJavaSourceFiles(FileCollection classpath, File outputDir){
        for(File f: classpath) {
            if(f.getAbsolutePath().equals(outputDir.getCanonicalPath())) {
                return true;
            }
        }
        return false;
    }

    private void addWarEmbeddedLib(Element parent, LooseApplication looseApp, Task task) throws Exception {
        ArrayList<File> deps = new ArrayList<File>();
        task.classpath.each {
            deps.add(it)
        }
        //Removes WEB-INF/lib/main directory since it is not rquired in the xml
        if (deps != null && !deps.isEmpty()) {
            deps.remove(0)
        }
        File parentProjectDir = new File(task.getProject().getRootProject().rootDir.getAbsolutePath())
        for (File dep: deps) {
            String dependencyProjectName = getProjectPath(parentProjectDir, dep)
            String projectDependencyString
            Project siblingProject
            if (dependencyProjectName != null && !dependencyProjectName.isEmpty()) {
                projectDependencyString = "project ':" + dependencyProjectName + "'"
                siblingProject = project.getRootProject().findProject(dependencyProjectName)
            }
            boolean isCurrentProject = ((task.getProject().toString()).equals(projectDependencyString))
            if (!isCurrentProject && siblingProject != null) {
                Element archive = looseApp.addArchive(parent, "/WEB-INF/lib/" + dep.getName());
                //Add sibling project class directories to <archive/> as <dir>
                siblingProject.sourceSets.main.getOutput().getClassesDirs().each {
                    looseApp.getConfig().addDir(archive, it, "/");
                }
                Task resourceTask = siblingProject.getTasks().findByPath(":" + projectDependencyString + ":processResources");
                if (resourceTask != null && resourceTask.getDestinationDir() != null) {
                    looseApp.addOutputDir(archive, resourceTask.getDestinationDir(), "/");
                }
                File resourceDir = siblingProject.sourceSets.main.getOutput().getResourcesDir()
                File manifestFile = null
                if (resourceDir.exists() && resourceDir.listFiles().length > 0) {
                    File metaInfDir = new File(resourceDir, "META-INF")
                    if (metaInfDir.exists() && metaInfDir.listFiles().length > 0) {
                        manifestFile = new File(metaInfDir, "MANIFEST.MF")
                    }
                }
                looseApp.addManifestFileWithParent(archive, manifestFile, resourceDir.getParentFile().getCanonicalPath());
            } else if (FilenameUtils.getExtension(dep.getAbsolutePath()).equalsIgnoreCase("jar")) {
                addLibrary(parent, looseApp, "/WEB-INF/lib/", dep);
            } else {
                looseApp.addOutputDir(looseApp.getDocumentRoot(), dep , "/WEB-INF/classes/");
            }
        }
    }

    protected void installLooseConfigEar(LooseConfigData config, Task task) throws Exception{
        LooseEarApplication looseEar = new LooseEarApplication(task, config);
        looseEar.addSourceDir();
        looseEar.addApplicationXmlFile();
        
        //Checking ear plugin dependency configurations to determine loose-ear content
        processDeployDependencies(looseEar, task)
        processEarlibDependencies(looseEar, task)

        File manifestFile = new File(project.buildDir.getAbsolutePath() + "/tmp/ear/MANIFEST.MF")
        looseEar.addManifestFile(manifestFile)
    }

    private void processDeployDependencies(LooseEarApplication looseEar, Task task) {
        HashMap<File, Dependency> completeDeployDeps = new HashMap<File, Dependency>();

        File[] filesAsDeps = task.getProject().configurations.deploy.getFiles().toArray()
        Dependency[] deployDeps = task.getProject().configurations.deploy.getAllDependencies().toArray()

        if(filesAsDeps.size() == deployDeps.size()){
            for(int i = 0; i<filesAsDeps.size(); i++) {
                completeDeployDeps.put(filesAsDeps[i], deployDeps[i])
            }
        }

        logger.info(MessageFormat.format("Number of deploy dependencies for " + task.project.name + " : " + completeDeployDeps.size()))
        for (Map.Entry<File, Dependency> entry : completeDeployDeps){
            Dependency dependency = entry.getValue();
            File dependencyFile = entry.getKey();

            if (dependency instanceof ProjectDependency) {
                Project dependencyProject = dependency.getDependencyProject()
                String projectType = FilenameUtils.getExtension(dependencyFile.toString())
                switch (projectType) {
                    case "jar":
                    case "ejb":
                    case "rar":
                        looseEar.addJarModule(dependencyProject)
                        break;
                    case "war":
                        Element warElement = looseEar.addWarModule(dependencyProject)
                        Task warTask = dependencyProject.getTasks().findByName('war')
                        if (warTask != null) {
                            addWarEmbeddedLib(warElement, looseEar, warTask)
                        } else {                 
                            addEmbeddedLib(warElement, dependencyProject, looseEar, "/WEB-INF/lib/")
                        }
                        break;
                    default:
                        logger.warn('Application ' + dependencyProject.getName() + ' is expressed as ' + projectType + ' which is not a supported input type. Define applications using Task or File objects of type war, ear, or jar.')
                        break;
                }
            }
            else if (dependency instanceof ExternalModuleDependency) {
                addLibrary(looseEar.getDocumentRoot(), looseEar, "/WEB-INF/lib/", dependencyFile);
            }
            else {
                logger.warn("Dependency " + dependency.getName() + "could not be added to the looseApplication, as it is neither a ProjectDependency or ExternalModuleDependency")
            }
        }
    }

    private void processEarlibDependencies (LooseEarApplication looseEar, Task task) {
        //Add earlib dependencies, requires resolving and checking for transitive dependencies
        //Using Dependency to determine how to add the resource to the loose ear
        //Resolved dependencies are used to get the tranistive dependencies to add to the loose ear
        HashMap<Dependency, ResolvedDependency> completeEarlibDeps = new HashMap<Dependency, ResolvedDependency>()

        Dependency[] earlibDeps = task.getProject().configurations.earlib.getAllDependencies().toArray()
        ResolvedDependency[] resolvedEarlibDeps = task.getProject().configurations.earlib.getResolvedConfiguration().getFirstLevelModuleDependencies().toArray()

        for (Dependency dep : earlibDeps) {
            for (ResolvedDependency resolvedDep : resolvedEarlibDeps) {
                if (dep.getName().equals(resolvedDep.getModuleName())) {
                    completeEarlibDeps.put(dep, resolvedDep)
                    break
                }
            }
        }

        logger.info(MessageFormat.format("Number of earlib dependencies for " + task.project.name + " : " + completeEarlibDeps.size()))
        for (Map.Entry<Dependency, ResolvedDependency> entry : completeEarlibDeps){
            Dependency dependency = entry.getKey();
            ResolvedDependency resolvedDependency = entry.getValue();

            if (dependency instanceof ProjectDependency) { //Adding the project archive and it's transitve dependencies to the loose ear
                Project dependencyProject = dependency.getDependencyProject()

                ResolvedArtifact projectArtifact

                //Getting project artifact to get the file later
                for (ResolvedArtifact artifact : resolvedDependency.getModuleArtifacts()) {
                    if (dependency.getName().equals(artifact.getModuleVersion().getId().getName())) { //Only checks the artifacts belonging to this dependency, not its children
                        projectArtifact = artifact
                        break
                    }
                }

                Set<ResolvedArtifact> projectDependencyArtifacts = resolvedDependency.getAllModuleArtifacts()

                //Removing project artifact from project dependency artifacts
                if (projectDependencyArtifacts.contains(projectArtifact)) {
                    projectDependencyArtifacts.remove(projectArtifact)
                }

                String projectType = FilenameUtils.getExtension(projectArtifact.getFile().toString())
                switch (projectType) {
                    case "jar":
                    case "ejb":
                    case "rar":
                        looseEar.addJarModule(dependencyProject)
                        projectDependencyArtifacts.each { //Adding transitive dependecies from project
                            looseEar.getConfig().addFile(it.getFile(), "/WEB-INF/lib/" + it.getName())
                        }
                        break;
                    case "war":
                        Element warElement = looseEar.addWarModule(dependencyProject)
                        addEmbeddedLib(warElement, dependencyProject, looseEar, "/WEB-INF/lib/")
                        projectDependencyArtifacts.each { //Adding transitive dependecies from war project
                            looseEar.getConfig().addFile(it.getFile(), "/WEB-INF/lib/" + it.getName())
                        }
                        break;
                    default:
                        logger.warn('Application ' + dependencyProject.getName() + ' is expressed as ' + projectType + ' which is not a supported input type. Define applications using Task or File objects of type war, ear, or jar.')
                        break;
                }
            }
            else if (dependency instanceof ExternalModuleDependency) { //Adding all artifacts belonging to this dependency and its children
                resolvedDependency.getAllModuleArtifacts().each {
                    looseEar.getConfig().addFile(it.getFile(), "/WEB-INF/lib/" + it.getName())
                }
            }
            else {
                logger.warn("Dependency " + dependency.getName() + "could not be added to the looseApplication, as it is neither a ProjectDependency or ExternalModuleDependency")
            }
        }
    }

    private void addEmbeddedLib(Element parent, Project project, LooseApplication looseApp, String dir) throws Exception {
        try {
            Set<File> filesAsDeps = new HashSet<File>()
            //Get the compile and implementation dependencies that are included in the war
            if (project.configurations.findByName('compile') != null) {
                Set<File> compileDepFiles = project.configurations.compile.minus(project.configurations.providedCompile).getFiles()
                filesAsDeps.addAll(compileDepFiles)
            }
            if (project.configurations.findByName('impementation') != null) {
                Set<File> implementationDepFiles = project.configurations.implementation.minus(project.configurations.providedCompile).getFiles()
                filesAsDeps.addAll(implementationDepFiles)
            }
            for (File f : filesAsDeps){
                String extension = FilenameUtils.getExtension(f.getAbsolutePath())
                if(extension.equals("jar")){
                    addLibrary(parent, looseApp, dir, f);
                }
            }
        } catch (UnknownConfigurationException uce) {
            logger.debug("No compile configuration detected when adding embedded libs to loose ear file.")
        }
    }

    private void addLibrary(Element parent, LooseApplication looseApp, String dir, File lib) throws GradleException {
        if(server.deploy.copyLibsDirectory != null) {
            if(!server.deploy.copyLibsDirectory.exists()) {
                server.deploy.copyLibsDirectory.mkdirs()
            }
            if(!server.deploy.copyLibsDirectory.isDirectory()) {
                throw new GradleException("copyLibsDirectory must be a directory.")
            } else {
                looseApp.getConfig().addFile(parent, lib, dir + lib.getName(), server.deploy.copyLibsDirectory)
            }
        } else {
            looseApp.getConfig().addFile(parent, lib, dir + lib.getName())
        }
    }

    private String getProjectPath(File parentProjectDir, File dep) {
        String dependencyPathPortion = dep.getAbsolutePath().replace(parentProjectDir.getAbsolutePath(),"")
        String projectPath = dep.getAbsolutePath().replace(dependencyPathPortion,"")
        Pattern pattern
        if (OSUtil.isWindows()) {
            pattern = Pattern.compile("\\\\build\\\\.*")
        } else {
            pattern = Pattern.compile("/build/.*")
        }
        Matcher matcher = pattern.matcher(dependencyPathPortion)
        projectPath = matcher.replaceAll("")

        //Remove leading slash character from trimmed path
        if (projectPath.length() > 1) {
            projectPath = projectPath.substring(1, projectPath.length())
        }
        
        return projectPath
    }

    private boolean isSupportedType(){
        switch (getPackagingType()) {
            case "ear":
            case "war":
            case "springboot":
                return true;
            default:
                return false;
        }
    }

    public static boolean isSupportedLooseAppType(String type) {
        switch (type) {
            case "ear":
            case "war":
                return true;
            default:
                return false;
        }
    }

    //Cleans up the application if the install style is switched from loose application to archive and vice versa
    protected void deleteApplication(File parent, File artifactFile) throws IOException {
        deleteApplication(parent, artifactFile.getName());
        if (artifactFile.getName().endsWith(".xml")) {
            deleteApplication(parent, artifactFile.getName().substring(0, artifactFile.getName().length() - 4));
        } else {
            deleteApplication(parent, artifactFile.getName() + ".xml");
        }
    }

    protected void deleteApplication(File parent, String filename) throws IOException {
        File application = new File(parent, filename);
        if (application.isDirectory()) {
            FileUtils.deleteDirectory(application);
        } else {
            application.delete();
        }
    }

    protected void installFromFile(File file, String appsDir) {
        Files.copy(file.toPath(), new File(getServerDir(project).toString() + '/' + appsDir + '/' + file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        validateAppConfig(file.name, file.name.take(file.name.lastIndexOf('.')), appsDir)
        if (server.looseApplication) {
            logger.warn('Application ' + file.getName() + ' was installed as a file as specified. To install as a loose application, specify the plugin or task generating the archive. ')
        }
        verifyAppStarted(file.name, appsDir)
    }

    protected void installFileList(List<File> appFiles, String appsDir) {
        appFiles.each { File appFile ->
            installFromFile(appFile, appsDir)
        }
    }

    File createApplicationFolder(String appDir) {
        File applicationDirectory = new File(getServerDir(project), appDir)
        try {
            if (!applicationDirectory.exists()) {
                applicationDirectory.mkdir()
            }
        } catch (Exception e) {
            throw new GradleException("There was a problem creating ${applicationDirectory.getCanonicalPath()}.", e)
        }
        return applicationDirectory
    }

    private boolean shouldValidateAppStart() throws GradleException {
        try {
            return new File(getServerDir(project).getCanonicalPath()  + "/workarea/.sRunning").exists()
        } catch (IOException ioe) {
            throw new GradleException("Could not get the server directory to determine the state of the server.")
        }
    }

    protected void verifyAppStarted(String appFile, String appsDir) throws GradleException {
        if (shouldValidateAppStart()) {
            String appName = appFile.substring(0, appFile.lastIndexOf('.'))
            if (appsDir.equals("apps")) {
                ServerConfigDocument scd = null

                File serverXML = new File(getServerDir(project).getCanonicalPath(), "server.xml")

                try {
                    scd = ServerConfigDocument.getInstance(CommonLogger.getInstance(project), serverXML, server.configDirectory,
                            server.bootstrapPropertiesFile, combinedBootstrapProperties, server.serverEnvFile, false, getLibertyDirectoryPropertyFiles(null))

                    //appName will be set to a name derived from appFile if no name can be found.
                    appName = scd.findNameForLocation(appFile)
                } catch (Exception e) {
                    logger.warn(e.getLocalizedMessage())
                } 
            }

            long appTimeout = 30 * 1000
            try {
                if (server.timeout != null && !server.timeout.isEmpty()) {
                    appTimeout = Long.valueOf(server.timeout) * 1000
                }
            } catch (NumberFormatException nfe) {
                throw new GradleException("The server.timeout parameter " + server.timeout + " could not be parsed into a long value. Ensure the value is formatted correctly.")
            }
            
            ServerTask serverTask = createServerTask(project, null) //Using a server task without an opertation to check logs for app start
            if (serverTask.waitForStringInLog("CWWKZ0001I.*" + appName, appTimeout, new File(new File(getOutputDir(project), server.name), "logs/messages.log")) == null) {
                throw new GradleException("Failed to deploy the " + appName + " application. The application start message was not found in the log file.")
            }
        }
    }
}

