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
      "Foreign paths must share syntax, navigation and completion owners",
      classes.contain(
        "com.dearlordylord.bend.idea.syntax.psi.BendForeignPaths$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.symbols.references.BendForeignPathReference"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.completion.BendForeignPathCompletion$"
      )
    )
    assertTrue(
      "File path refactoring must use the workspace path policy",
      classes.contain(
        "com.dearlordylord.bend.idea.features.rename.BendMoveFileHandler"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.rename.BendSourceFileRenameProcessor"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.workspace.api.BendSourcePathEdits$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.workspace.api.BendImportPaths$"
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
      "Parameter information handler must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.signatures.BendParameterInfoHandler"
      )
    )
    assertTrue(
      "Parameter name hints provider must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.signatures.BendParameterNameHintsProvider"
      )
    )
    assertTrue(
      "Selected proof roots must use the shared explicit check boundary",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.api.BendExplicitCheckRunner"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.adapters.intellij.BendExplicitCheckRunnerService"
      )
    )
    assertTrue(
      "Explicit Run must use a separate request and streaming process adapter",
      classes.contain(
        "com.dearlordylord.bend.idea.features.execution.BendRunConfiguration"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.execution.api.BendExecutionRequest"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.adapters.process.BendExecutionProcessService"
      )
    )
    assertTrue(
      "Build and native Run must retain separate process requests and adapters",
      classes.contain(
        "com.dearlordylord.bend.idea.features.execution.BendBuildConfiguration"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.execution.BendNativeRunConfiguration"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.execution.api.BendBuildRequest"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.execution.api.BendNativeRunRequest"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.adapters.process.BendBuildProcessService"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.adapters.process.BendNativeProcessService"
      )
    )
    assertTrue(
      "Proof-root selection must remain in its feature owner",
      classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendCheckProofRootAction"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendProofRootStore"
      )
    )
    assertTrue(
      "Law/fill links and hole navigation must use the proof feature owner",
      classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendProofLineMarkerProvider"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendProofNavigation$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendHoleNavigation$"
      )
    )
    assertTrue(
      "Proof progress must remain a root-owned feature that reads shared results",
      classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendProofProgressToolWindowFactory"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendProofProgressReader"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendProofProgressModel$"
      )
    )
    assertTrue(
      "Law-fill skeleton planning must use the proof owner and shared template renderer",
      classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendGenerateLawFillAction"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.proofs.BendProofFillGenerator$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.templates.api.BendSnippets$"
      )
    )
    assertTrue(
      "Semantic color annotator must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.editing.BendSemanticAnnotator"
      )
    )
    assertTrue(
      "Match skeleton planning must stay in editing and use the shared template renderer",
      classes.contain(
        "com.dearlordylord.bend.idea.features.editing.BendGenerateMatchCasesAction"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.editing.BendMatchSkeletonGenerator$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.templates.api.BendSnippets$"
      )
    )
    assertTrue(
      "Breadcrumb provider must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.editing.BendBreadcrumbsProvider"
      )
    )
    assertTrue(
      "Selection handler must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.features.editing.BendSelectionHandler"
      )
    )
    assertTrue(
      "Source documentation projection must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.api.BendSourceDocumentation$"
      )
    )
    assertTrue(
      "Source call application model must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.api.BendSourceApplications$"
      )
    )
    assertTrue(
      "Shared call signature alignment must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.api.BendSourceCallSignatures$"
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
        "com.dearlordylord.bend.idea.workspace.api.BendSourceCatalog"
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
        "com.dearlordylord.bend.idea.symbols.api.BendPhysicalTargets$"
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
      "Blanked import range must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.model.BendBlankedRange"
      )
    )
    assertTrue(
      "Mapped source range must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.model.BendTextRange"
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
      "Check reservation identity must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.model.BendCheckReservation"
      )
    )
    assertTrue(
      "Check origin must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.model.BendCheckOrigin$"
      )
    )
    assertTrue(
      "Background scheduler control API must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.api.BendBackgroundCheckControl"
      )
    )
    assertTrue(
      "Background timer token must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.api.BendBackgroundCheckTicket"
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
      "Check transition policy must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.checking.BendCheckTransitionPolicy$"
      )
    )
    assertTrue(
      "Immutable check state must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.checking.BendCheckState"
      )
    )
    assertTrue(
      "Check transition result must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.checking.BendCheckTransition"
      )
    )
    assertTrue(
      "Check events must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.checking.BendCheckEvent"
      )
    )
    assertTrue(
      "Background scheduling intent must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.checking.BendBackgroundIntent"
      )
    )
    assertTrue(
      "Background scheduling phases must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.checking.BendBackgroundPhase$"
      )
    )
    assertTrue(
      "Check adapter actions must be inspected",
      classes.contain(
        "com.dearlordylord.bend.idea.analysis.checking.BendCheckAction"
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
    assertTrue(
      "Explicit imports must use the public symbol search boundary",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.api.BendWorkspaceSymbolSearch$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.symbols.index.BendWorkspaceSymbolIndexSearch$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.completion.BendExplicitImportPlanner$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.completion.BendExplicitImportAction"
      )
    )
    assertTrue(
      "Static dependency inspection must consume shared lexical applications",
      classes.contain(
        "com.dearlordylord.bend.idea.symbols.api.BendSourceApplications$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.navigation.BendStaticDependencies$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.navigation.BendInspectDependenciesAction"
      )
    )
    assertTrue(
      "File creation must use the shared editable template owner",
      classes.contain(
        "com.dearlordylord.bend.idea.features.templates.BendFileTemplates$"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.templates.BendCreateModuleAction"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.features.templates.BendCreateLawProofPairAction"
      )
    )
    assertTrue(
      "Spellchecking must use the token-aware spelling feature",
      classes.contain(
        "com.dearlordylord.bend.idea.features.spelling.BendSpellcheckingStrategy"
      ) && classes.contain(
        "com.dearlordylord.bend.idea.syntax.lexer.BendTokens$"
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
