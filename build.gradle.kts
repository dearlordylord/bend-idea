import org.gradle.api.tasks.scala.ScalaCompile
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    scala
    id("com.diffplug.spotless") version "8.10.2"
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

group = "com.dearlordylord.bend.idea"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation("org.scala-lang:scala3-library_3:${providers.gradleProperty("scalaVersion").get()}")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.standardout.org.editorconfig:editorconfig-core:0.12.1.Final")
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        bundledPlugin("org.editorconfig.editorconfigjetbrains")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier("1.410")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.tngtech.archunit:archunit:1.4.1")
}

val bendTestToolchainPins = Properties().apply {
    file("ci/bend-test-toolchain.properties").inputStream().use(::load)
}
fun bendTestPin(name: String): String = requireNotNull(bendTestToolchainPins.getProperty(name)) {
    "Missing '$name' in ci/bend-test-toolchain.properties."
}.trim().also { require(it.isNotEmpty()) { "Empty '$name' in ci/bend-test-toolchain.properties." } }

val expectedBendCommit = bendTestPin("bend.commit")
val expectedBunVersion = bendTestPin("bun.version")
val expectedBunRevision = bendTestPin("bun.revision")
val bendReleaseSmoke = providers.gradleProperty("bendReleaseSmoke")
    .map(String::toBooleanStrict)
    .getOrElse(false)

val verifyBendTestInputs by tasks.registering(Exec::class) {
    description = "Verifies the pinned real Bend compiler and Bun test inputs."
    group = "verification"
    workingDir = rootDir
    commandLine("bash", file("ci/verify-bend-test-inputs.sh").absolutePath)
    outputs.upToDateWhen { false }
}

val scalafixCli by configurations.creating
val scalafixVersion = "0.14.6"
dependencies.add(
    scalafixCli.name,
    "ch.epfl.scala:scalafix-cli_3.3.7:$scalafixVersion"
)

spotless {
    scala {
        target("src/main/scala/**/*.scala", "src/test/scala/**/*.scala")
        scalafmt("3.11.5").configFile(".scalafmt.conf")
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<ScalaCompile>().configureEach {
    scalaCompileOptions.additionalParameters = listOf(
        "-feature",
        "-Werror",
        "-Wvalue-discard",
        "-Wnonunit-statement",
        "-Wunused:imports,privates,locals"
    )
}

val buildFormatTool by tasks.registering(Jar::class) {
    description = "Builds the pinned offline Bend format command as a standalone JVM jar."
    group = "build"
    dependsOn(tasks.classes)
    archiveBaseName.set("bend-format-tool")
    archiveVersion.set(project.version.toString())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = "com.dearlordylord.bend.idea.adapters.cli.BendFormatCli"
    manifest.attributes["Implementation-Version"] = project.version.toString()
    from(sourceSets.main.get().output) {
        include("com/dearlordylord/bend/idea/syntax/parser/BendLayoutPolicy*.class")
        include("com/dearlordylord/bend/idea/adapters/cli/BendFormatCli*.class")
    }
    from(provider {
        configurations.runtimeClasspath.get().filter { it.extension == "jar" }.map(::zipTree)
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

tasks.test {
    dependsOn(buildFormatTool)
    systemProperty("bend.format.tool.jar", buildFormatTool.get().archiveFile.get().asFile.absolutePath)
    systemProperty("bend.format.tool.version", project.version.toString())
    systemProperty("bend.format.tool.classes", sourceSets.main.get().output.classesDirs.asPath)
}

tasks.test {
    useJUnit()
    exclude("**/architecture/**")
    systemProperty("java.awt.headless", "true")
    if (bendReleaseSmoke) {
        outputs.upToDateWhen { false }
        filter {
            includeTestsMatching("com.dearlordylord.bend.idea.features.checking.BendReleaseEditorSmokeTest")
            isFailOnNoMatchingTests = true
        }
        doFirst {
            val compiler = providers.environmentVariable("BEND_TEST_CURRENT_COMPILER").orNull
            val base = providers.environmentVariable("BEND_TEST_CURRENT_BASE").orNull
            require(!compiler.isNullOrBlank() && file(compiler).isFile && file(compiler).canExecute()) {
                "BEND_TEST_CURRENT_COMPILER must name an executable published Bend binary."
            }
            require(!base.isNullOrBlank() && file(base).isFile) {
                "BEND_TEST_CURRENT_BASE must name that release's Base source."
            }
        }
    } else {
        exclude("**/BendReleaseEditorSmokeTest.class")
        systemProperty("bend.test.expectedBendCommit", expectedBendCommit)
        systemProperty("bend.test.expectedBunVersion", expectedBunVersion)
        systemProperty("bend.test.expectedBunRevision", expectedBunRevision)
        dependsOn(verifyBendTestInputs)
    }
}

val architectureTest by tasks.registering(Test::class) {
    description = "Checks compiled Scala package boundaries and tests the checker with forbidden dependencies."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/architecture/*Test.class")
    useJUnit()
    systemProperty("architecture.classes", sourceSets.main.get().output.classesDirs.asPath)
}

val mutationPolicyRoots = listOf(
    "src/main/scala/com/dearlordylord/bend/idea/model",
    "src/main/scala/com/dearlordylord/bend/idea/workspace/api",
    "src/main/scala/com/dearlordylord/bend/idea/workspace/model",
    "src/main/scala/com/dearlordylord/bend/idea/workspace/loading",
    "src/main/scala/com/dearlordylord/bend/idea/analysis/api",
    "src/main/scala/com/dearlordylord/bend/idea/analysis/model",
    "src/main/scala/com/dearlordylord/bend/idea/analysis/checking"
)
val mutationPolicySources = files(
    mutationPolicyRoots.map { root ->
        fileTree(root) { include("**/*.scala") }
    }
)
val policyLintArguments: (List<File>) -> List<String> = { sources ->
    listOf(
        "--config",
        file(".scalafix.conf").absolutePath,
        "--scala-version",
        providers.gradleProperty("scalaVersion").get(),
        "--syntactic",
        "--check"
    ) + sources.flatMap { source -> listOf("--files", source.absolutePath) }
}
val scopedPolicySources: (List<File>) -> List<File> = { additionalSources ->
    val missingRoots = mutationPolicyRoots.filterNot { file(it).isDirectory }
    check(missingRoots.isEmpty()) {
        "Mutation policy source roots are missing: ${missingRoots.joinToString()}"
    }
    val productionSources = mutationPolicySources.files.sortedBy {
        it.relativeTo(projectDir).invariantSeparatorsPath
    }
    check(productionSources.isNotEmpty()) {
        "Mutation policy lint has no Scala sources in its configured roots."
    }
    (productionSources + additionalSources).distinct()
}
val policyMutationCheck by tasks.registering(JavaExec::class) {
    description = "Checks for mutable vars in the explicitly scoped pure policy packages."
    group = "verification"
    classpath = scalafixCli
    mainClass.set("scalafix.cli.Cli")
    inputs.files(mutationPolicySources)
    inputs.file(".scalafix.conf")
    inputs.property("scalafixVersion", scalafixVersion)
    doFirst {
        setArgs(policyLintArguments(scopedPolicySources(emptyList())))
    }
}

val productionScalaSources = fileTree("src/main/scala") { include("**/*.scala") }
val unsafeCastProbe = file("quality-gate/fixtures/UnsafeCastProbe.scala")
val unsafeCastLintArguments: (List<File>) -> List<String> = { sources ->
    listOf(
        "--config",
        file(".scalafix-unsafe-casts.conf").absolutePath,
        "--scala-version",
        providers.gradleProperty("scalaVersion").get(),
        "--syntactic",
        "--check"
    ) + sources.flatMap { source -> listOf("--files", source.absolutePath) }
}
val unsafeCastCheck by tasks.registering(JavaExec::class) {
    description = "Rejects unchecked asInstanceOf casts in production Scala."
    group = "verification"
    classpath = scalafixCli
    mainClass.set("scalafix.cli.Cli")
    inputs.files(productionScalaSources)
    inputs.file(".scalafix-unsafe-casts.conf")
    inputs.property("scalafixVersion", scalafixVersion)
    doFirst {
        val sources = productionScalaSources.files.sortedBy {
            it.relativeTo(projectDir).invariantSeparatorsPath
        }
        check(sources.isNotEmpty()) { "Unsafe cast lint has no production Scala sources." }
        setArgs(unsafeCastLintArguments(sources))
    }
}
val unsafeCastProbeStdout = ByteArrayOutputStream()
val unsafeCastProbeStderr = ByteArrayOutputStream()
val unsafeCastNegativeCheck by tasks.registering(JavaExec::class) {
    description = "Proves that the production cast lint rejects asInstanceOf."
    group = "verification"
    classpath = scalafixCli
    mainClass.set("scalafix.cli.Cli")
    isIgnoreExitValue = true
    inputs.files(unsafeCastProbe)
    inputs.file(".scalafix-unsafe-casts.conf")
    inputs.property("scalafixVersion", scalafixVersion)
    doFirst {
        check(unsafeCastProbe.isFile && unsafeCastProbe.readText().contains("asInstanceOf")) {
            "The unsafe cast probe must contain an asInstanceOf cast."
        }
        unsafeCastProbeStdout.reset()
        unsafeCastProbeStderr.reset()
        standardOutput = unsafeCastProbeStdout
        errorOutput = unsafeCastProbeStderr
        setArgs(unsafeCastLintArguments(listOf(unsafeCastProbe)))
    }
    doLast {
        val output = String(unsafeCastProbeStdout.toByteArray(), Charsets.UTF_8) +
            String(unsafeCastProbeStderr.toByteArray(), Charsets.UTF_8)
        check(executionResult.get().exitValue != 0 && output.contains("DisableSyntax.asInstanceOf")) {
            "The unsafe cast probe was not rejected with the expected diagnostic:\n$output"
        }
        logger.lifecycle("Verified production cast lint rejects asInstanceOf.")
    }
}

val policyMutationProbe = file("quality-gate/fixtures/PolicyMutationProbe.scala")
val discardedResultProbe = file("quality-gate/fixtures/DiscardedResultProbe.scala")
val mutationProbeStdout = ByteArrayOutputStream()
val mutationProbeStderr = ByteArrayOutputStream()
val compilerProbeStdout = ByteArrayOutputStream()
val compilerProbeStderr = ByteArrayOutputStream()
val policyMutationNegativeCheck by tasks.registering(JavaExec::class) {
    description = "Proves that the scoped mutation lint rejects a policy-package var."
    group = "verification"
    classpath = scalafixCli
    mainClass.set("scalafix.cli.Cli")
    isIgnoreExitValue = true
    inputs.files(mutationPolicySources, policyMutationProbe)
    inputs.file(".scalafix.conf")
    inputs.property("scalafixVersion", scalafixVersion)
    doFirst {
        check(policyMutationProbe.isFile &&
            policyMutationProbe.readText().contains("package com.dearlordylord.bend.idea.analysis.") &&
            policyMutationProbe.readText().contains("var ")) {
            "The mutation negative probe must contain a var in an analysis policy package."
        }
        mutationProbeStdout.reset()
        mutationProbeStderr.reset()
        standardOutput = mutationProbeStdout
        errorOutput = mutationProbeStderr
        setArgs(policyLintArguments(scopedPolicySources(listOf(policyMutationProbe))))
    }
    doLast {
        val output = String(mutationProbeStdout.toByteArray(), Charsets.UTF_8) +
            String(mutationProbeStderr.toByteArray(), Charsets.UTF_8)
        check(executionResult.get().exitValue != 0) {
            "The policy mutation probe unexpectedly passed the scoped Scalafix check."
        }
        check(output.contains("DisableSyntax", ignoreCase = true) ||
            output.contains("noVars", ignoreCase = true)) {
            "The policy mutation probe failed without a DisableSyntax diagnostic:\n$output"
        }
        logger.lifecycle("Verified scoped mutation lint rejects the policy-package var probe.")
    }
}

val discardedProbeOutput = layout.buildDirectory.dir("quality-gate/discarded-result")
val compileDiscardedResultNegativeCheck by tasks.registering(JavaExec::class) {
    description = "Proves the configured Scala warning flags reject a discarded non-Unit result."
    group = "verification"
    val scalaCompile = tasks.named<ScalaCompile>("compileScala").get()
    classpath = scalaCompile.scalaClasspath
    mainClass.set("dotty.tools.dotc.Main")
    isIgnoreExitValue = true
    inputs.file(discardedResultProbe)
    inputs.property("scalaWarningParameters", scalaCompile.scalaCompileOptions.additionalParameters)
    doFirst {
        val warningParameters = scalaCompile.scalaCompileOptions.additionalParameters
        check(warningParameters.contains("-Werror") &&
            warningParameters.contains("-Wvalue-discard")) {
            "The discarded-result probe must use the configured fatal value-discard warning."
        }
        check(discardedResultProbe.isFile) { "The discarded-result probe source is missing." }
        val outputDirectory = discardedProbeOutput.get().asFile
        project.delete(outputDirectory)
        check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Could not create compiler probe output directory: $outputDirectory"
        }
        compilerProbeStdout.reset()
        compilerProbeStderr.reset()
        standardOutput = compilerProbeStdout
        errorOutput = compilerProbeStderr
        setArgs(warningParameters + listOf(
            "-classpath",
            sourceSets.main.get().compileClasspath.asPath,
            "-d",
            outputDirectory.absolutePath,
            discardedResultProbe.absolutePath
        ))
    }
    doLast {
        val output = String(compilerProbeStdout.toByteArray(), Charsets.UTF_8) +
            String(compilerProbeStderr.toByteArray(), Charsets.UTF_8)
        check(executionResult.get().exitValue != 0) {
            "The discarded-result probe unexpectedly compiled with the configured warning flags."
        }
        check(output.contains("E175") ||
            output.contains("discarded non-Unit", ignoreCase = true)) {
            "The discarded-result probe failed without a value-discard diagnostic:\n$output"
        }
        logger.lifecycle("Verified Scala warning flags reject the discarded non-Unit result probe.")
    }
}

val qualityGateNegativeChecks by tasks.registering {
    description = "Runs negative probes for the configured compiler and policy lint gates."
    group = "verification"
    dependsOn(policyMutationNegativeCheck, unsafeCastNegativeCheck, compileDiscardedResultNegativeCheck)
}

tasks.check {
    dependsOn(architectureTest, policyMutationCheck, unsafeCastCheck, qualityGateNegativeChecks)
}

val requireSigning by tasks.registering {
    doLast {
        val directory = providers.environmentVariable("BEND_IDEA_SIGNING_DIR").orNull
        require(!directory.isNullOrBlank() &&
            file("$directory/private.pem").isFile &&
            file("$directory/chain.crt").isFile) {
            "Set BEND_IDEA_SIGNING_DIR to a directory containing private.pem and chain.crt."
        }
    }
}
tasks.named("signPlugin") { dependsOn(requireSigning) }

tasks.named("verifyPluginSignature") { dependsOn("signPlugin") }
tasks.named("publishPlugin") {
    dependsOn("verifyPluginSignature")
    doFirst {
        require(providers.environmentVariable("BEND_IDEA_SIGNING_DIR").isPresent) {
            "Set BEND_IDEA_SIGNING_DIR before publishing; unsigned updates are not released."
        }
    }
}

intellijPlatform {
    buildSearchableOptions = false
    providers.environmentVariable("BEND_IDEA_SIGNING_DIR").orNull?.let { signingDirectory ->
        signing {
            privateKeyFile = file("$signingDirectory/private.pem")
            certificateChainFile = file("$signingDirectory/chain.crt")
        }
    }
    pluginConfiguration {
        id = "com.dearlordylord.bend.idea"
        name = "Bend2"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "251"
        }
    }
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
