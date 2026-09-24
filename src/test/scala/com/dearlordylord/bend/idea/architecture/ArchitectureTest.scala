package com.dearlordylord.bend.idea.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.{Files, Path}

final class ArchitectureTest:
  @Test def productionRespectsBoundaries(): Unit =
    val paths = System
      .getProperty("architecture.classes")
      .split(File.pathSeparator)
      .map(value => Path.of(value))
      .filter(path => Files.isDirectory(path))
    val classes = new ClassFileImporter().importPaths(paths*)
    assertTrue(
      "The actual Scala production output must be inspected",
      classes.contain("com.dearlordylord.bend.idea.syntax.BendFileType")
    )
    assertTrue(
      "Scala companion bytecode must also be inspected",
      classes.contain("com.dearlordylord.bend.idea.syntax.BendLanguage$")
    )
    assertTrue(
      "The shared lexer boundary must be inspected",
      classes.contain("com.dearlordylord.bend.idea.syntax.lexer.BendLexer")
    )
    assertTrue(
      "The editing feature boundary must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.editing.BendCommenter"
      )
    )
    assertTrue(
      "The completion feature boundary must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.completion.BendCompletionContributor"
      )
    )
    assertTrue(
      "Usage search must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.references.BendUsageSearch"
      )
    )
    assertTrue(
      "Find Usages provider must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.navigation.BendFindUsagesProvider"
      )
    )
    assertTrue(
      "Documentation provider must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.documentation.BendDocumentationProvider"
      )
    )
    assertTrue(
      "Source documentation projection must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.api.BendSourceDocumentation$"
      )
    )
    assertTrue(
      "The shared template API boundary must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.templates.api.BendSnippets$"
      )
    )
    assertTrue(
      "Source identity must be inspected",
      classes.contain("com.dearlordylord.bend.idea.model.FileId")
    )
    assertTrue(
      "The tolerant parser must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.syntax.parser.BendSurfaceParser"
      )
    )
    assertTrue(
      "Native declaration PSI must be inspected",
      classes.contain("com.dearlordylord.bend.idea.syntax.psi.BendDeclaration")
    )
    assertTrue(
      "The symbols API must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols$"
      )
    )
    assertTrue(
      "The shared scope policy must be inspected",
      classes.contain("com.dearlordylord.bend.idea.symbols.scope.BendScope$")
    )
    assertTrue(
      "Structured case/do scope must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.scope.BendStructuredScope$"
      )
    )
    assertTrue(
      "Proof scope must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.scope.BendProofScope$"
      )
    )
    assertTrue(
      "Law relationships must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.declarations.BendLawDeclarations$"
      )
    )
    assertTrue(
      "Proof surface forms must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.syntax.psi.BendProofSurface$"
      )
    )
    assertTrue(
      "Toolchain path policy must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.toolchain.api.BendToolchainPaths$"
      )
    )
    assertTrue(
      "Base source contract must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.workspace.api.BendBaseSource"
      )
    )
    assertTrue(
      "Base import eligibility must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.workspace.api.BendImportLines$"
      )
    )
    assertTrue(
      "Shared import graph loader must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.workspace.loading.BendGraphLoader$"
      )
    )
    assertTrue(
      "Loaded graph model must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.workspace.model.BendLoadedGraph"
      )
    )
    assertTrue(
      "Source catalog port must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.workspace.ports.BendSourceCatalog"
      )
    )
    assertTrue(
      "Imported symbol catalog must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.api.BendImportedSymbolCatalog"
      )
    )
    assertTrue(
      "Native references must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.references.BendNameReference"
      )
    )
    assertTrue(
      "Module file references must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.references.BendModulePathReference"
      )
    )
    assertTrue(
      "Physical reference targets must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.references.BendPhysicalTargets$"
      )
    )
    assertTrue(
      "Reference PSI must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.syntax.psi.BendReferenceElement"
      )
    )
    assertTrue(
      "Loading configuration boundary must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration"
      )
    )
    assertTrue(
      "Workspace graph API must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.workspace.api.BendWorkspaceGraph"
      )
    )
    assertTrue(
      "Workspace graph adapter must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.adapters.intellij.BendWorkspaceGraphService"
      )
    )
    assertTrue(
      "Import path policy must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.workspace.api.BendImportPaths$"
      )
    )
    assertTrue(
      "Import path completion must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.completion.BendImportPathCompletion$"
      )
    )
    assertTrue(
      "Workspace path catalog API must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.workspace.api.BendWorkspacePaths"
      )
    )
    assertTrue(
      "Base source adapter must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.adapters.intellij.BendLibrarySourceService"
      )
    )
    assertTrue(
      "Settings storage must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.adapters.intellij.BendSettingsStorage"
      )
    )
    assertTrue(
      "Settings UI must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.settings.BendSettingsConfigurable"
      )
    )
    assertTrue(
      "Root result model must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.model.BendCheckResult"
      )
    )
    assertTrue(
      "Graph snapshot policy must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.api.BendGraphSnapshot$"
      )
    )
    assertTrue(
      "Source mapping must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.model.BendSourceMapping"
      )
    )
    assertTrue(
      "Rewrite range must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.model.BendRewrittenRange"
      )
    )
    assertTrue(
      "Check policy must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.api.BendCheckPolicy$"
      )
    )
    assertTrue(
      "Check service contract must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.api.BendCheckService"
      )
    )
    assertTrue(
      "CLI backend must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.adapters.cli.BendCliCheckBackend"
      )
    )
    assertTrue(
      "External input observation must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.adapters.cli.BendExternalInputs$"
      )
    )
    assertTrue(
      "Bounded process adapter must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.adapters.process.BendBoundedProcess$"
      )
    )
    assertTrue(
      "Check session must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.adapters.intellij.BendCheckSession"
      )
    )
    assertTrue(
      "Automatic checker must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.adapters.intellij.BendBackgroundChecking"
      )
    )
    assertTrue(
      "Checking status policy must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.model.BendCheckingStatus$"
      )
    )
    assertTrue(
      "Publication policy must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.checking.BendPublicationPolicy$"
      )
    )
    assertTrue(
      "Check status UI must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.checking.BendCheckStatusAction"
      )
    )
    assertTrue(
      "Explicit action must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.checking.BendCheckCurrentFileAction"
      )
    )
    assertTrue(
      "Problem projection must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.checking.BendCheckAnnotator"
      )
    )
    ArchitectureRules.check(classes, "com.dearlordylord.bend.idea")

  @Test def rejectsForbiddenCompiledScalaDependencies(): Unit =
    val classes = new ClassFileImporter().importPackages("architecturefixture")
    val failures = ArchitectureRules.violations(classes, "architecturefixture")
    assertTrue(
      "Detect a policy-to-syntax dependency",
      failures.exists(s => s.contains("A2") && s.contains("BadSyntax"))
    )
    assertTrue(
      "Detect file I/O in a policy",
      failures.exists(s => s.contains("A4") && s.contains("BadIo"))
    )
    assertTrue(
      "Detect platform types in a policy",
      failures.exists(s => s.contains("A4") && s.contains("BadPlatform"))
    )
    assertTrue(
      "Detect one feature reaching into another",
      failures.exists(s => s.contains("A2") && s.contains("BadRename"))
    )
    val _ = assertThrows(
      classOf[AssertionError],
      () => ArchitectureRules.check(classes, "architecturefixture")
    )
