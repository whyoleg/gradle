/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.buildinit.plugins

import org.gradle.api.logging.configuration.WarningMode
import org.gradle.buildinit.plugins.fixtures.ScriptDslFixture
import org.gradle.buildinit.plugins.internal.Protocol
import org.gradle.buildinit.plugins.internal.modifiers.BuildInitDsl
import org.gradle.buildinit.plugins.internal.modifiers.InsecureProtocolOption
import org.gradle.integtests.fixtures.DefaultTestExecutionResult
import org.gradle.integtests.fixtures.TestResources
import org.gradle.integtests.fixtures.executer.ExecutionResult
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.server.http.HttpServer
import org.gradle.test.fixtures.server.http.MavenHttpModule
import org.gradle.test.fixtures.server.http.MavenHttpRepository
import org.gradle.test.fixtures.server.http.PomHttpArtifact
import org.gradle.util.SetSystemProperties
import org.gradle.util.internal.TextUtil
import org.junit.Rule
import spock.lang.Issue

abstract class MavenConversionIntegrationTest extends AbstractInitIntegrationSpec {

    @Rule
    public final TestResources resources = new TestResources(temporaryFolder)

    @Rule
    public final SetSystemProperties systemProperties = new SetSystemProperties()

    @Rule
    public final HttpServer server = new HttpServer()

    @Override
    String subprojectName() { null }

    abstract BuildInitDsl getScriptDsl()

    def setup() {
        /**
         * We need to configure the local maven repository explicitly as
         * RepositorySystem.defaultUserLocalRepository is statically initialised and used when
         * creating multiple ProjectBuildingRequest.
         * */
        m2.generateUserSettingsFile(m2.mavenRepo())
        using m2
    }

    def "multiModule"() {
        def dsl = dslFixtureFor(scriptDsl)
        def warSubprojectBuildFile = targetDir.file("webinar-war/" + dsl.buildFileName)
        def implSubprojectBuildFile = targetDir.file("webinar-impl/" + dsl.buildFileName)
        def conventionPluginScript = targetDir.file("buildSrc/src/main/${scriptDsl.name().toLowerCase()}/${scriptDsl.fileNameFor("com.example.webinar.java-conventions")}")

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        targetDir.file(dsl.settingsFileName).exists()
        !targetDir.file(dsl.buildFileName).exists() // no root build file
        warSubprojectBuildFile.exists()

        warSubprojectBuildFile.text.contains("id 'com.example.webinar.java-conventions'") || warSubprojectBuildFile.text.contains('id("com.example.webinar.java-conventions")')
        !warSubprojectBuildFile.text.contains("options.encoding")

        assertContainsPublishingConfig(conventionPluginScript, scriptDsl)
        conventionPluginScript.text.contains("options.encoding = 'UTF-8'") || conventionPluginScript.text.contains('options.encoding = "UTF-8"')
        conventionPluginScript.text.contains(TextUtil.toPlatformLineSeparators('''
java {
    withSourcesJar()
}'''))

        implSubprojectBuildFile.text.contains("publishing.publications.maven.artifact(testsJar)") || implSubprojectBuildFile.text.contains('(publishing.publications["maven"] as MavenPublication).artifact(testsJar)')
        implSubprojectBuildFile.text.contains(TextUtil.toPlatformLineSeparators('''
java {
    withJavadocJar()
}'''))
        when:
        run 'clean', 'build'

        then: //smoke test the build artifacts
        targetDir.file("webinar-api/build/libs/webinar-api-1.0-SNAPSHOT.jar").exists()
        targetDir.file("webinar-impl/build/libs/webinar-impl-1.0-SNAPSHOT.jar").exists()
        targetDir.file("webinar-war/build/libs/webinar-war-1.0-SNAPSHOT.war").exists()

        new DefaultTestExecutionResult(targetDir.file("webinar-impl")).assertTestClassesExecuted('webinar.WebinarTest')

        when:
        run 'projects'

        then:
        output.contains """
Root project 'webinar-parent'
+--- Project ':webinar-api' - Webinar APIs
+--- Project ':webinar-impl' - Webinar implementation
\\--- Project ':webinar-war' - Webinar web application
"""
    }

    def "multiModuleWithNestedParent"() {
        def dsl = dslFixtureFor(scriptDsl)

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        targetDir.file(dsl.settingsFileName).exists()
        targetDir.file("webinar-war/" + dsl.buildFileName).exists()

        when:
        run 'clean', 'build'

        then: //smoke test the build artifacts
        targetDir.file("webinar-api/build/libs/webinar-api-1.0-SNAPSHOT.jar").exists()
        targetDir.file("webinar-impl/build/libs/webinar-impl-1.0-SNAPSHOT.jar").exists()
        targetDir.file("webinar-war/build/libs/webinar-war-1.0-SNAPSHOT.war").exists()

        new DefaultTestExecutionResult(targetDir.file("webinar-impl")).assertTestClassesExecuted('webinar.WebinarTest')
    }

    def "flatmultimodule"() {
        def dsl = dslFixtureFor(scriptDsl)
        executer.beforeExecute {
            executer.inDirectory(targetDir.file("webinar-parent")).withWarningMode(WarningMode.None) // FIXME we cannot assert warnings in this test as withWarningMode is ignored for the Kotlin DSL
        }

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        !targetDir.file(dsl.buildFileName).exists()
        !targetDir.file("webinar-parent/" + dsl.buildFileName).exists()
        targetDir.file("webinar-parent/" + dsl.settingsFileName).exists()
        targetDir.file("webinar-api/" + dsl.buildFileName).exists()
        targetDir.file("webinar-impl/" + dsl.buildFileName).exists()
        targetDir.file("webinar-war/" + dsl.buildFileName).exists()

        when:
        run 'clean', 'build'

        then: //smoke test the build artifacts
        targetDir.file("webinar-api/build/libs/webinar-api-1.0-SNAPSHOT.jar").exists()
        targetDir.file("webinar-impl/build/libs/webinar-impl-1.0-SNAPSHOT.jar").exists()
        targetDir.file("webinar-war/build/libs/webinar-war-1.0-SNAPSHOT.war").exists()

        new DefaultTestExecutionResult(targetDir.file("webinar-impl")).assertTestClassesExecuted('webinar.WebinarTest')

        when:
        run 'projects'

        then:
        output.contains """
Root project 'webinar-parent'
+--- Project ':webinar-api' - Webinar APIs
+--- Project ':webinar-impl' - Webinar implementation
\\--- Project ':webinar-war' - Webinar web application
"""
    }

    def "singleModule"() {
        def dsl = dslFixtureFor(scriptDsl)

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        dsl.assertGradleFilesGenerated()
        dsl.getSettingsFile().text.contains("rootProject.name = 'util'") || dsl.getSettingsFile().text.contains('rootProject.name = "util"')
        assertContainsPublishingConfig(dsl.getBuildFile(), scriptDsl)

        when:
        fails 'clean', 'build'

        then:
        targetDir.file("build/libs/util-2.5.jar").exists()
        failure.assertHasDescription("Execution failed for task ':test'.")
        failure.assertHasCause("There were failing tests.")
    }

    private static void assertContainsPublishingConfig(TestFile buildScript, BuildInitDsl dsl, String indent = "", List<String> additionalArchiveTasks = []) {
        def text = buildScript.text
        if (dsl == BuildInitDsl.GROOVY) {
            assert text.contains("id 'maven-publish'")
            def configLines = ["from(components.java)"]
            configLines += additionalArchiveTasks.collect { "artifact($it)" }
            def publishingBlock = TextUtil.toPlatformLineSeparators(TextUtil.indent("""
            publishing {
                publications {
                    maven(MavenPublication) {
${TextUtil.indent(configLines.join("\n"), "                        ")}
                    }
                }
            }
            """.stripIndent().trim(), indent))
            assert text.contains(publishingBlock)
        } else {
            assert text.contains("`maven-publish`")
            def configLines = ['from(components["java"])']
            configLines += additionalArchiveTasks.collect { "artifact($it)" }
            def publishingBlock = TextUtil.toPlatformLineSeparators(TextUtil.indent("""
            publishing {
                publications.create<MavenPublication>("maven") {
${TextUtil.indent(configLines.join("\n"), "                    ")}
                }
            }
            """.stripIndent().trim(), indent))
            assert text.contains(publishingBlock)
        }

    }

    def "singleModule with explicit project dir"() {
        given:
        resources.maybeCopy('MavenConversionIntegrationTest/singleModule')
        def workingDir = temporaryFolder.createDir("workingDir")

        when:
        executer.beforeExecute {
            executer.inDirectory(workingDir).usingProjectDirectory(targetDir)
        }
        run 'init', '--dsl', scriptDsl.id as String

        then:
        dslFixtureFor(scriptDsl).assertGradleFilesGenerated()

        when:
        fails 'clean', 'build'

        then:
        targetDir.file("build/libs/util-2.5.jar").exists()
        failure.assertHasDescription("Execution failed for task ':test'.")
        failure.assertHasCause("There were failing tests.")
    }

    def 'sourcesJar'() {
        def rootBuildFile = dslFixtureFor(scriptDsl).getBuildFile()

        when: 'build is initialized'
        run 'init', '--dsl', scriptDsl.id as String

        then: 'sourcesJar task configuration is generated'
        rootBuildFile.text.contains(TextUtil.toPlatformLineSeparators('''
            java {
                withSourcesJar()
            }
            '''.stripIndent().trim()))
        assertContainsPublishingConfig(rootBuildFile, scriptDsl)

        when: 'the generated task is executed'
        run 'clean', 'build', 'sourcesJar'

        then: 'the sources jar is generated'
        targetDir.file('build/libs/util-2.5.jar').exists()
        targetDir.file('build/libs/util-2.5-sources.jar').exists()
    }

    def 'testsJar'() {
        def rootBuildFile = dslFixtureFor(scriptDsl).getBuildFile()

        when: 'build is initialized'
        run 'init', '--dsl', scriptDsl.id as String

        then: 'testsJar task configuration is generated'
        rootBuildFile.text.contains(TextUtil.toPlatformLineSeparators('''
            tasks.register('testsJar', Jar) {
                archiveClassifier = 'tests'
                from(sourceSets.test.output)
            }
            '''.stripIndent().trim())) || rootBuildFile.text.contains(TextUtil.toPlatformLineSeparators('''
            val testsJar by tasks.registering(Jar::class) {
                archiveClassifier.set("tests")
                from(sourceSets["test"].output)
            }
            '''.stripIndent().trim()))
        assertContainsPublishingConfig(rootBuildFile, scriptDsl, '', ['testsJar'])

        when: 'the generated task is executed'
        run 'clean', 'build', 'testJar'

        then: 'the tests jar is generated'
        targetDir.file('build/libs/util-2.5.jar').exists()
        targetDir.file('build/libs/util-2.5-tests.jar').exists()
    }

    def 'javadocJar'() {
        def rootBuildFile = dslFixtureFor(scriptDsl).getBuildFile()

        when: 'build is initialized'
        run 'init', '--dsl', scriptDsl.id as String

        then: 'javadocJar task configuration is generated'
        rootBuildFile.text.contains(TextUtil.toPlatformLineSeparators('''
            java {
                withJavadocJar()
            }
            '''.stripIndent().trim()))
        assertContainsPublishingConfig(rootBuildFile, scriptDsl)

        when: 'the generated task is executed'
        run 'clean', 'build', 'javadocJar'

        then: 'the javadoc jar is generated'
        targetDir.file('build/libs/util-2.5.jar').exists()
        targetDir.file('build/libs/util-2.5-javadoc.jar').exists()
    }

    def "enforcerplugin"() {
        def dsl = dslFixtureFor(scriptDsl)

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        dsl.assertGradleFilesGenerated()

        and:
        dsl.getBuildFile().text.contains(TextUtil.toPlatformLineSeparators("""configurations.all {
    exclude(group: 'org.apache.maven')
    exclude(group: 'org.apache.maven', module: 'badArtifact')
    exclude(group: '*', module: 'badArtifact')
    exclude(group: 'broken')
}""")) || dsl.getBuildFile().text.contains(TextUtil.toPlatformLineSeparators("""configurations.all {
    exclude(mapOf("group" to "org.apache.maven"))
    exclude(mapOf("group" to "org.apache.maven", "module" to "badArtifact"))
    exclude(mapOf("group" to "*", "module" to "badArtifact"))
    exclude(mapOf("group" to "broken"))
}"""))
        !dsl.getBuildFile().text.contains("http://repo.maven.apache.org/maven2")
        when:
        run 'clean', 'build'

        then:
        targetDir.file("build/libs/enforcerExample-1.0.jar").exists()
    }

    def "providedNotWar"() {
        def dsl = dslFixtureFor(scriptDsl)

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        dsl.assertGradleFilesGenerated()

        when:
        run 'clean', 'build'

        then:
        dsl.getBuildFile().text.contains("compileOnly 'junit:junit:4.10'") || dsl.getBuildFile().text.contains('compileOnly("junit:junit:4.10")')
        targetDir.file("build/libs/myThing-0.0.1-SNAPSHOT.jar").exists()
    }

    def "provides decent error message when POM is invalid"() {
        setup:
        def pom = targetDir.file("pom.xml")
        pom << "<project>someInvalid pom content</project>"

        when:
        fails 'init'

        then:
        failure.assertHasCause("Could not convert Maven POM $pom to a Gradle build.")
    }

    def "mavenExtensions"() {
        def dsl = dslFixtureFor(scriptDsl)

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        dsl.assertGradleFilesGenerated()

        when:
        run 'clean', 'build'

        then:
        targetDir.file("build/libs/testApp-1.0.jar").exists()
    }

    @Issue("GRADLE-2820")
    def "remoteparent"() {
        def dsl = dslFixtureFor(scriptDsl)

        setup:
        withSharedResources()
        def repo = mavenHttpServer()
        //update pom with test repo url
        targetDir.file("pom.xml").text = targetDir.file("pom.xml").text.replaceAll('LOCAL_MAVEN_REPO_URL', repo.getUri().toString())

        expectParentPomRequest(repo)

        when:
        run 'init', '--dsl', scriptDsl.id as String, '--insecure-protocol', InsecureProtocolOption.ALLOW.id

        then:
        dsl.assertGradleFilesGenerated()

        when:
        libRequest(repo, "commons-lang", "commons-lang", "2.6")
        run 'clean', 'build'

        then:
        targetDir.file("build/libs/util-2.5.jar").exists()
    }

    @Issue("GRADLE-2872")
    def "expandProperties"() {
        def dsl = dslFixtureFor(scriptDsl)

        setup:
        withSharedResources()
        executer.withArgument("-DCOMMONS_LANG_VERSION=2.6")

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        dsl.assertGradleFilesGenerated()

        when:
        run('clean', 'build')

        then:
        targetDir.file("build/libs/util-3.2.2.jar").exists()
    }

    @Issue("GRADLE-2819")
    def "multiModuleWithRemoteParent"() {
        def dsl = dslFixtureFor(scriptDsl)

        given:
        withSharedResources()
        def repo = mavenHttpServer()
        //update pom with test repo url
        targetDir.file("pom.xml").text = targetDir.file("pom.xml").text.replaceAll('LOCAL_MAVEN_REPO_URL', repo.getUri().toString())

        expectParentPomRequest(repo)

        when:
        run 'init', '--dsl', scriptDsl.id as String, '--insecure-protocol', InsecureProtocolOption.UPGRADE.id

        then:
        targetDir.file(dsl.settingsFileName).exists()

        when:
        libRequest(repo, "commons-lang", "commons-lang", 2.6)
        // Required for the 'webinar-impl' project's POM
        libRequest(repo, "junit", "junit", 4.10)
        // Required for the 'webinar-war' project's POM
        libRequest(repo, "junit", "junit", "3.8.1")
        libRequest(repo, "org.hamcrest", "hamcrest-core", 1.1)

        run 'clean', 'build'

        then: //smoke test the build artifacts
        targetDir.file("webinar-api/build/libs/webinar-api-1.0-SNAPSHOT.jar").exists()
        targetDir.file("webinar-impl/build/libs/webinar-impl-1.0-SNAPSHOT.jar").exists()
        targetDir.file("webinar-war/build/libs/webinar-war-1.0-SNAPSHOT.war").exists()

        new DefaultTestExecutionResult(targetDir.file("webinar-impl")).assertTestClassesExecuted('webinar.WebinarTest')

        when:
        run 'projects'

        then:
        output.contains """
Root project 'webinar-parent'
+--- Project ':util-parent'
+--- Project ':webinar-api' - Webinar APIs
+--- Project ':webinar-impl' - Webinar implementation
\\--- Project ':webinar-war' - Webinar web application
"""
    }

    @Issue("https://github.com/gradle/gradle/issues/15827")
    def "compilePluginWithoutConfiguration"() {
        def dsl = dslFixtureFor(scriptDsl)

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        dsl.assertGradleFilesGenerated()
        succeeds 'build'
    }

    @Issue("https://github.com/gradle/gradle/issues/17328")
    def "insecureProtocolDefaultHandling"() {
        def dsl = dslFixtureFor(scriptDsl)

        setup:
        def repo = mavenHttpServer()
        // update pom with test repo url
        def localRepoUrl = repo.getUri().toString()
        targetDir.file("pom.xml").text = targetDir.file("pom.xml").text.replaceAll('LOCAL_MAVEN_REPO_URL', localRepoUrl)

        when:
        run 'init', '--dsl', scriptDsl.id as String

        then:
        assertWarnOptionSucceeds(result, dsl, localRepoUrl)
    }

    @Issue("https://github.com/gradle/gradle/issues/17328")
    def "insecureProtocolWarn"() {
        def dsl = dslFixtureFor(scriptDsl)

        setup:
        def repo = mavenHttpServer()
        // update pom with test repo url
        def localRepoUrl = repo.getUri().toString()
        targetDir.file("pom.xml").text = targetDir.file("pom.xml").text.replaceAll('LOCAL_MAVEN_REPO_URL', localRepoUrl)

        when:
        run 'init', '--dsl', scriptDsl.id as String, '--insecure-protocol', InsecureProtocolOption.WARN.id

        then:
        assertWarnOptionSucceeds(result, dsl, localRepoUrl)
    }

    private static void assertWarnOptionSucceeds(ExecutionResult result, ScriptDslFixture dsl, String localRepoUrl) {
        dsl.assertGradleFilesGenerated()

        def isGroovy = dsl.scriptDsl.id.equalsIgnoreCase("Groovy")
        def allowPrefix = isGroovy ? "a" : "isA"
        def fileExtension = isGroovy ? "gradle" : "gradle.kts"

        def initTask = result.groupedOutput.task(':init')
        initTask.output.contains("Repository URL: '$localRepoUrl' uses an insecure protocol.")
        initTask.output.contains("If you wish to use this repository, you will have to uncomment the line '${allowPrefix}llowInsecureProtocol=true' in the generated build.${fileExtension} file.")
    }

    @Issue("https://github.com/gradle/gradle/issues/17328")
    def "insecureProtocolAllow"() {
        def dsl = dslFixtureFor(scriptDsl)

        setup:
        def repo = mavenHttpServer()
        // update pom with test repo url
        def localRepoUrl = repo.getUri().toString()
        targetDir.file("pom.xml").text = targetDir.file("pom.xml").text.replaceAll('LOCAL_MAVEN_REPO_URL', localRepoUrl)

        when:
        run 'init', '--dsl', scriptDsl.id as String, '--insecure-protocol', InsecureProtocolOption.ALLOW.id

        then:
        dsl.assertGradleFilesGenerated()

        def taskOutput = result.groupedOutput.task(':init').output
        taskOutput.contains("Repository URL: '$localRepoUrl' uses an insecure protocol.")
        taskOutput.contains("Setting allowInsecureProtocol=true.")

        def isGroovy = scriptDsl.id.equalsIgnoreCase("Groovy")
        def stringDelimiter = isGroovy ? "'" : '"'
        def allowPropertyPrefix = isGroovy ? 'a' : 'isA'
        def mavenLocalRepoBlock = """
    maven {
        url = uri($stringDelimiter$localRepoUrl$stringDelimiter)
        ${allowPropertyPrefix}llowInsecureProtocol = true
    }""" // Indentation is important here, it has to exactly match what is generated, so don't trim or stripIndent, need leading spaces

        dsl.getBuildFile().text.contains(TextUtil.toPlatformLineSeparators(mavenLocalRepoBlock))
    }

    @Issue("https://github.com/gradle/gradle/issues/17328")
    def "insecureProtocolUpgrade"() {
        def dsl = dslFixtureFor(scriptDsl)

        setup:
        def repo = mavenHttpServer()
        // update pom with test repo url
        def localRepoUrl = repo.getUri().toString()
        targetDir.file("pom.xml").text = targetDir.file("pom.xml").text.replaceAll('LOCAL_MAVEN_REPO_URL', localRepoUrl)

        when:
        run 'init', '--dsl', scriptDsl.id as String, '--insecure-protocol', InsecureProtocolOption.UPGRADE.id

        then:
        dsl.assertGradleFilesGenerated()

        def taskOutput = result.groupedOutput.task(':init').output
        taskOutput.contains("Repository URL: '$localRepoUrl' uses an insecure protocol.")
        taskOutput.contains("Upgrading protocol")

        def isGroovy = scriptDsl.id.equalsIgnoreCase("Groovy")
        def upgradedRepoUrl = localRepoUrl.replaceFirst(Protocol.HTTP.getPrefix(), Protocol.HTTPS.getPrefix())
        def stringDelimiter = isGroovy ? "'" : '"'
        def mavenLocalRepoBlock = """
    maven {
        url = uri($stringDelimiter$upgradedRepoUrl$stringDelimiter)
    }""" // Indentation is important here, it has to exactly match what is generated, so don't trim or stripIndent, need leading spaces

        dsl.getBuildFile().text.contains(TextUtil.toPlatformLineSeparators(mavenLocalRepoBlock))
    }

    static libRequest(MavenHttpRepository repo, String group, String name, Object version) {
        MavenHttpModule module = repo.module(group, name, version as String)
        module.allowAll()
    }

    def withSharedResources() {
        resources.maybeCopy('MavenConversionIntegrationTest/sharedResources')
    }

    static PomHttpArtifact expectParentPomRequest(MavenHttpRepository repo) {
        MavenHttpModule module = repo.module('util.util.parent', 'util-parent', '3')
        module.pom.expectGet()
        module.pom.sha1.expectGet()
        module.pom
    }

    MavenHttpRepository mavenHttpServer() {
        server.start()
        new MavenHttpRepository(server, '/maven', maven(file("maven_repo")))
    }
}

class KotlinDslMavenConversionIntegrationTest extends MavenConversionIntegrationTest {
    BuildInitDsl scriptDsl = BuildInitDsl.KOTLIN
}

class GroovyDslMavenConversionIntegrationTest extends MavenConversionIntegrationTest {
    BuildInitDsl scriptDsl = BuildInitDsl.GROOVY
}
