package com.dearlordylord.bend.idea.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.{Files, Path}

final class ArchitectureTest:
  @Test def productionRespectsBoundaries(): Unit =
    val paths = System.getProperty("architecture.classes").split(File.pathSeparator)
      .map(value => Path.of(value)).filter(path => Files.isDirectory(path))
    val classes = new ClassFileImporter().importPaths(paths*)
    assertTrue("The actual Scala production output must be inspected", classes.contain("com.dearlordylord.bend.idea.syntax.BendFileType"))
    assertTrue("Scala companion bytecode must also be inspected", classes.contain("com.dearlordylord.bend.idea.syntax.BendLanguage$"))
    assertTrue("The shared lexer boundary must be inspected", classes.contain("com.dearlordylord.bend.idea.syntax.lexer.BendLexer"))
    assertTrue("The editing feature boundary must be inspected", classes.contain("com.dearlordylord.bend.idea.features.editing.BendCommenter"))
    assertTrue("The completion feature boundary must be inspected", classes.contain("com.dearlordylord.bend.idea.features.completion.BendCompletionContributor"))
    assertTrue("The shared template API boundary must be inspected", classes.contain("com.dearlordylord.bend.idea.features.templates.api.BendSnippets$"))
    ArchitectureRules.check(classes, "com.dearlordylord.bend.idea")

  @Test def rejectsForbiddenCompiledScalaDependencies(): Unit =
    val classes = new ClassFileImporter().importPackages("architecturefixture")
    val failures = ArchitectureRules.violations(classes, "architecturefixture")
    assertTrue("Detect a policy-to-syntax dependency", failures.exists(s => s.contains("A2") && s.contains("BadSyntax")))
    assertTrue("Detect file I/O in a policy", failures.exists(s => s.contains("A4") && s.contains("BadIo")))
    assertTrue("Detect platform types in a policy", failures.exists(s => s.contains("A4") && s.contains("BadPlatform")))
    assertTrue("Detect one feature reaching into another", failures.exists(s => s.contains("A2") && s.contains("BadRename")))
    assertThrows(classOf[AssertionError], () => ArchitectureRules.check(classes, "architecturefixture"))
