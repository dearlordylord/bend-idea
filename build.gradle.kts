import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.util.Properties

plugins {
    scala
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

val bendTestToolchainPins = Properties().apply {
    file("ci/bend-test-toolchain.properties").inputStream().use(::load)
}
fun bendTestPin(name: String): String = requireNotNull(bendTestToolchainPins.getProperty(name)) {
    "Missing '$name' in ci/bend-test-toolchain.properties."
}.trim().also { require(it.isNotEmpty()) { "Empty '$name' in ci/bend-test-toolchain.properties." } }

val expectedBendCommit = bendTestPin("bend.commit")
val expectedBunVersion = bendTestPin("bun.version")
val expectedBunRevision = bendTestPin("bun.revision")

val verifyBendTestInputs by tasks.registering(Exec::class) {
    description = "Verifies the pinned real Bend compiler and Bun test inputs."
    group = "verification"
    workingDir = rootDir
    commandLine("bash", file("ci/verify-bend-test-inputs.sh").absolutePath)
    outputs.upToDateWhen { false }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<ScalaCompile>().configureEach {
    scalaCompileOptions.additionalParameters = listOf("-feature", "-Werror")
}

tasks.test {
    useJUnit()
    exclude("**/architecture/**")
    systemProperty("java.awt.headless", "true")
    systemProperty("bend.test.expectedBendCommit", expectedBendCommit)
    systemProperty("bend.test.expectedBunVersion", expectedBunVersion)
    systemProperty("bend.test.expectedBunRevision", expectedBunRevision)
    dependsOn(verifyBendTestInputs)
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

tasks.check { dependsOn(architectureTest) }

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
