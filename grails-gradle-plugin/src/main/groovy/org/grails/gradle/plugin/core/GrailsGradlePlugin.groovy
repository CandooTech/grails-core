package org.grails.gradle.plugin.core

import grails.util.BuildSettings
import grails.util.Environment
import grails.util.GrailsNameUtils
import grails.util.Metadata
import groovy.transform.CompileStatic
import nebula.plugin.extraconfigurations.ProvidedBasePlugin
import org.apache.tools.ant.filters.EscapeUnicode
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.CopySpec
import org.gradle.api.plugins.GroovyPlugin
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.process.JavaForkOptions
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.grails.build.parsing.CommandLineParser
import org.grails.gradle.plugin.agent.AgentTasksEnhancer
import org.grails.gradle.plugin.commands.ApplicationContextCommandTask
import org.grails.gradle.plugin.model.GrailsClasspathToolingModelBuilder
import org.grails.gradle.plugin.run.FindMainClassTask
import org.grails.io.support.FactoriesLoaderSupport

import javax.inject.Inject

class GrailsGradlePlugin extends GroovyPlugin {
    public static final String APPLICATION_CONTEXT_COMMAND_CLASS = "grails.dev.commands.ApplicationCommand"

    List<Class<Plugin>> basePluginClasses = [ProvidedBasePlugin, IntegrationTestGradlePlugin]
    private final ToolingModelBuilderRegistry registry

    @Inject
    GrailsGradlePlugin(ToolingModelBuilderRegistry registry) {
        this.registry = registry
    }

    void apply(Project project) {
        super.apply(project)
        registerToolingModelBuilder(project, registry)

        registerGrailsExtension(project)

        applyBasePlugins(project)

        registerFindMainClassTask(project)

        configureGrailsBuildSettings(project)

        configureFileWatch(project)

        def grailsVersion = resolveGrailsVersion(project)

        enableNative2Ascii(project, grailsVersion)

        configureAssetCompilation(project)

        configureConsoleTask(project)

        configureForkSettings(project, grailsVersion)

        configureGrailsSourceDirs(project)

        configureApplicationCommands(project)
    }

    protected void registerToolingModelBuilder(Project project, ToolingModelBuilderRegistry registry) {
        registry.register(new GrailsClasspathToolingModelBuilder())
    }

    @CompileStatic
    protected void applyBasePlugins(Project project) {
        basePluginClasses.each { Class<Plugin> cls -> project.plugins.apply(cls) }
    }

    protected GrailsExtension registerGrailsExtension(Project project) {
        project.extensions.create("grails", GrailsExtension)
    }

    protected void configureFileWatch(Project project) {
        def environment = Environment.current
        enableFileWatch(environment, project)
    }

    protected String configureGrailsBuildSettings(Project project) {
        System.setProperty(BuildSettings.APP_BASE_DIR, project.projectDir.absolutePath)
    }

    protected void configureApplicationCommands(Project project) {
        def applicationContextCommands = FactoriesLoaderSupport.loadFactoryNames(APPLICATION_CONTEXT_COMMAND_CLASS)
        for (ctxCommand in applicationContextCommands) {
            def taskName = GrailsNameUtils.getLogicalPropertyName(ctxCommand, "Command")
            def commandName = GrailsNameUtils.getScriptName(GrailsNameUtils.getLogicalName(ctxCommand, "Command"))
            project.tasks.create(taskName, ApplicationContextCommandTask) {
                classpath = project.sourceSets.main.runtimeClasspath + project.configurations.console
                command = commandName
                if (project.hasProperty('args')) {
                    args(CommandLineParser.translateCommandline(project.args))
                }
            }
        }
    }

    protected void configureGrailsSourceDirs(Project project) {
        def grailsSourceDirs = []
        def excludedDirs = ['views', 'migrations', 'assets', 'i18n', 'conf']
        project.file("grails-app").eachDir { File subdir ->
            def dirName = subdir.name
            if (!subdir.hidden && !dirName.startsWith(".") && !excludedDirs.contains(dirName)) {
                grailsSourceDirs << subdir.absolutePath
            }
        }

        grailsSourceDirs << project.file("src/main/groovy")

        project.sourceSets {
            main {
                groovy {
                    srcDirs = grailsSourceDirs
                    resources {
                        srcDirs = [
                            project.file("src/main/resources"),
                            project.file("grails-app/conf"),
                            project.file("grails-app/views"),
                            project.file("grails-app/i18n")
                        ]
                    }
                }
            }
        }
    }

    protected String resolveGrailsVersion(Project project) {
        def grailsVersion = project.property('grailsVersion')

        if (!grailsVersion) {
            def grailsCoreDep = project.configurations.getByName('compile').dependencies.find { Dependency d -> d.name == 'grails-core' }
            grailsVersion = grailsCoreDep.version
        }
        grailsVersion
    }

    protected void configureAssetCompilation(Project project) {
        if (project.extensions.findByName('assets')) {
            project.assets {
                assetsPath = 'grails-app/assets'
                compileDir = 'build/assetCompile/assets'
            }
        }

        def tasks = project.tasks
        project.afterEvaluate {
            if (tasks.findByName("war")) {
                project.war {
                    from "${project.buildDir}/assetCompile"
                }
            } else {
                project.processResources {
                    from("${project.buildDir}/assetCompile") {
                        into "META-INF"
                    }
                }
            }
        }
    }

    protected void configureForkSettings(project, grailsVersion) {
        boolean isJava8Compatible = JavaVersion.current().isJava8Compatible()

        def systemPropertyConfigurer = { JavaForkOptions task ->
            def map = System.properties.findAll { entry ->
                entry.key.startsWith("grails.")
            }
            for (key in map.keySet()) {
                def value = map.get(key)
                if (value) {
                    def sysPropName = key.toString().substring(7)
                    task.systemProperty(sysPropName, value.toString())
                }
            }
            task.systemProperty Metadata.APPLICATION_NAME, project.name
            task.systemProperty Metadata.APPLICATION_VERSION, project.version
            task.systemProperty Metadata.APPLICATION_GRAILS_VERSION, grailsVersion
            task.systemProperty Environment.KEY, Environment.current.name ?: Environment.DEVELOPMENT.name
            task.systemProperty Environment.FULL_STACKTRACE, System.getProperty(Environment.FULL_STACKTRACE) ?: ""
            task.minHeapSize = "768m"
            task.maxHeapSize = "768m"
            if (!isJava8Compatible) {
                task.jvmArgs "-XX:PermSize=96m", "-XX:MaxPermSize=256m"
            }
            task.jvmArgs "-XX:+TieredCompilation", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=3"
        }

        def tasks = project.tasks
        tasks.withType(Test).each systemPropertyConfigurer
        tasks.withType(JavaExec).each systemPropertyConfigurer
    }


    @CompileStatic
    protected void configureConsoleTask(Project project) {
        def tasks = project.tasks
        def consoleConfiguration = project.configurations.create("console")
        def findMainClass = tasks.findByName('findMainClass')
        def consoleTask = createConsoleTask(project, tasks, consoleConfiguration)
        def shellTask = createShellTask(project, tasks, consoleConfiguration)

        findMainClass.doLast {
            def mainClassName = project.property("mainClassName")
            consoleTask.args mainClassName
            shellTask.args mainClassName
            project.tasks.withType(ApplicationContextCommandTask) { ApplicationContextCommandTask task ->
                task.args mainClassName
            }
        }

        consoleTask.dependsOn(tasks.findByName('classes'), findMainClass)
        shellTask.dependsOn(tasks.findByName('classes'), findMainClass)
    }

    protected JavaExec createConsoleTask(Project project, TaskContainer tasks, Configuration configuration) {
        tasks.create("console", JavaExec) {
            classpath = project.sourceSets.main.runtimeClasspath + configuration
            main = "grails.ui.console.GrailsSwingConsole"
        }
    }

    protected JavaExec createShellTask(Project project, TaskContainer tasks, Configuration configuration) {
        tasks.create("shell", JavaExec) {
            classpath = project.sourceSets.main.runtimeClasspath + configuration
            main = "grails.ui.shell.GrailsShell"
            standardInput = System.in
        }
    }

    protected void enableFileWatch(Environment environment, Project project) {
        if (environment.isReloadEnabled()) {
//            configureWatchPlugin(project)

            project.configurations {
                agent
            }
            project.dependencies {
                agent "org.springframework:springloaded:1.2.1.RELEASE"
            }
            project.afterEvaluate(new AgentTasksEnhancer())
        }
    }

    @CompileStatic
    protected void registerFindMainClassTask(Project project) {
        def findMainClassTask = project.tasks.create(name: "findMainClass", type: FindMainClassTask, overwrite: true)
        findMainClassTask.mustRunAfter project.tasks.withType(GroovyCompile)
        def bootRepackageTask = project.tasks.findByName("bootRepackage")
        if(bootRepackageTask) {
            bootRepackageTask.dependsOn findMainClassTask
        }
    }

    /**
     * Enables native2ascii processing of resource bundles
     **/
    protected void enableNative2Ascii(Project project, grailsVersion) {
        project.afterEvaluate {
            for (SourceSet sourceSet in project.sourceSets) {

                project.tasks.getByName(sourceSet.processResourcesTaskName) { CopySpec task ->
                    def grailsExt = project.extensions.getByType(GrailsExtension)
                    task.filter( ReplaceTokens, tokens: [
                            'info.app.name': project.name,
                            'info.app.version': project.version,
                            'info.app.grailsVersion': grailsVersion
                    ]
                    )
                    task.from(sourceSet.resources) {
                        include '**/*.properties'
                        if(grailsExt.native2ascii) {
                            filter(EscapeUnicode)
                        }
                    }
                    task.from(sourceSet.resources) {
                        exclude '**/*.properties'
                    }
                    task.from(sourceSet.resources) {
                        include '**/*.groovy'
                    }
                }
            }
        }

    }

}
