import org.gradle.api.tasks.scala.ScalaCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.ByteArrayOutputStream

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
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        testFramework(TestFrameworkType.Platform)
        pluginVerifier("1.410")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.tngtech.archunit:archunit:1.4.1")
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

tasks.test {
    useJUnit()
    exclude("**/architecture/**")
    systemProperty("java.awt.headless", "true")
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
    dependsOn(policyMutationNegativeCheck, compileDiscardedResultNegativeCheck)
}

tasks.check {
    dependsOn(architectureTest, policyMutationCheck, qualityGateNegativeChecks)
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
