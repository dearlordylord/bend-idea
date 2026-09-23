plugins {
    id("org.jetbrains.intellij.platform.grammarkit") version "2.13.1"
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
        grammarKit("2023.3.1")
    }
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask>("generateParser") {
    sourceFile.set(file("BendSurface.bnf"))
    targetRootOutputDir.set(file("build/generated"))
    pathToParser.set("experiment/BendGeneratedParser.java")
    pathToPsiRoot.set("experiment/psi")
}
