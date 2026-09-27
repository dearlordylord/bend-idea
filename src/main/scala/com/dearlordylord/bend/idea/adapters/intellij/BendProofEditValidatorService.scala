package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.adapters.cli.BendCliCheckBackend
import com.dearlordylord.bend.idea.adapters.cli.BendExternalInputs
import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendGraphSnapshot,
  BendProofEditPreview,
  BendProofEditPreviewOutcome,
  BendProofEditValidator,
  BendResourceBinding,
  BendResourceCandidateStatus,
  BendResourceReport,
  BendResourceReportOutcome
}
import com.dearlordylord.bend.idea.analysis.model.{
  BendAnalysisKey,
  BendCheckOutcome,
  BendCheckResult,
  BendCheckSnapshot,
  BendCompleteness,
  BendGoal,
  BendIncompleteKind
}
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendSourceCatalog,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.project.Project
import scala.util.control.NonFatal

/** Validates reflexivity against a derived, isolated complete-root snapshot. No
  * source document is modified during preview.
  */
final class BendProofEditValidatorService(project: Project)
    extends BendProofEditValidator:
  override def previewReflexivity(
      check: BendCheckResult,
      goal: BendGoal
  )(completed: BendProofEditPreviewOutcome => Unit): Unit =
    def finish(outcome: BendProofEditPreviewOutcome): Unit =
      ApplicationManager.getApplication.invokeLater(() =>
        if !project.isDisposed then completed(outcome)
      )
    ProgressManager
      .getInstance()
      .run(
        new Task.Backgroundable(project, "Validating Bend reflexivity", true):
          override def run(indicator: ProgressIndicator): Unit =
            try
              val outcome = validate(
                check,
                goal,
                () => indicator.isCanceled || project.isDisposed
              )
              if !indicator.isCanceled then finish(outcome)
            catch
              case NonFatal(error) if !indicator.isCanceled =>
                finish(
                  BendProofEditPreviewOutcome.Rejected(
                    "Could not validate reflexivity: " + Option(
                      error.getMessage
                    )
                      .getOrElse(error.getClass.getSimpleName)
                  )
                )
              case NonFatal(_) => ()
      )

  private final case class Prepared(
      originalCheck: BendCheckResult,
      goal: BendGoal,
      source: com.dearlordylord.bend.idea.analysis.model.BendCheckedSource,
      root: BendSourceRecord,
      selection: BendToolchainSelection,
      snapshot: BendCheckSnapshot,
      obsolete: () => Boolean
  )

  private def prepare(
      check: BendCheckResult,
      goal: BendGoal,
      canceled: () => Boolean
  ): Either[String, Prepared] =
    val checkService = project.getService(classOf[BendCheckService])
    def baseObsolete: Boolean = canceled() || !checkService.isCurrent(check)
    if baseObsolete || !check.goal.contains(goal) then
      return Left("Goal changed before candidate validation")
    val sourceOption = check.sources.find(_.id == goal.source)
    if sourceOption.isEmpty then return Left("Goal source is unavailable")
    val source = sourceOption.get
    if goal.range.start < 0 || goal.range.end > source.text.length ||
      source.text.substring(goal.range.start, goal.range.end) !=
        s"?${goal.holeName}"
    then return Left("Goal source range is no longer exact")
    val selection = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .selection
    if selection.configurationRevision != check.key.configurationRevision ||
      selection.executable != check.key.executable ||
      selection.baseSource != check.key.snapshotProvenance.baseSource ||
      BendExternalInputs.stamp(
        selection.executable,
        check.key.basePath
      ) != check.key.externalStamp
    then return Left("Bend toolchain changed before validation")
    val capture = new BendRootSnapshotCapture(project)
    val rootPathOption = check.sources.find(_.id == check.key.root).map(_.path)
    if rootPathOption.isEmpty then
      return Left("Checked root source is unavailable")
    val rootPath = rootPathOption.get
    val original = capture.capture(rootPath, selection, () => baseObsolete)
    if original.isEmpty then return Left("Goal validation canceled")
    val originalSnapshot = original.get
    if originalSnapshot.root != check.key.root then
      return Left("Selected root identity changed before validation")
    val rootOption =
      originalSnapshot.graph.flatMap(_.source(originalSnapshot.root))
    if rootOption.isEmpty then
      return Left("Checked root is no longer available")
    val root = rootOption.get
    val currentSource = originalSnapshot.graph.flatMap(_.source(source.id))
    if currentSource.isEmpty || currentSource.get.revision != source.revision ||
      currentSource.get.text != source.text
    then return Left("Goal source changed before validation")
    if originalSnapshot.inputFingerprint != check.key.snapshotProvenance.graphFingerprint ||
      BendAnalysisKey.sourceDigest(root.text) != check.key.sourceFingerprint
    then return Left("Checked root or dependencies changed before validation")
    Right(
      Prepared(
        check,
        goal,
        source,
        root,
        selection,
        originalSnapshot,
        () => baseObsolete || !capture.current(originalSnapshot)
      )
    )

  private def inputsCurrent(prepared: Prepared): Boolean =
    !prepared.obsolete() &&
      BendExternalInputs.stamp(
        prepared.selection.executable,
        prepared.originalCheck.key.basePath
      ) == prepared.originalCheck.key.externalStamp

  private def checkReplacement(
      prepared: Prepared,
      replacement: String,
      extraCanceled: () => Boolean = () => false
  ): BendCheckResult =
    def canceled: Boolean = prepared.obsolete() || extraCanceled()
    val source = prepared.source
    val goal = prepared.goal
    val proposed = source.text.take(goal.range.start) + replacement +
      source.text.drop(goal.range.end)
    val changed = BendSourceRecord(
      source.id,
      source.path,
      proposed,
      source.revision,
      BendImportLines.parse(proposed)
    )
    val frozen = prepared.snapshot.graph.toList.flatMap(_.files.map(_.source))
    val byId = frozen.map(record => record.id -> record).toMap
    val requested = prepared.snapshot.graph.toList
      .flatMap(_.edges)
      .flatMap(edge =>
        edge.target
          .flatMap(byId.get)
          .map(record => edge.requestedPath -> record)
      )
    val records = ((frozen ++ prepared.snapshot.siblingLaws.toList)
      .map(record => record.path -> record) ++ requested).toMap
    val overlay = new BendSourceCatalog:
      override def source(path: String): Option[BendSourceRecord] =
        records
          .get(path)
          .map(record => if record.id == changed.id then changed else record)
    val candidateRoot = if prepared.root.id == changed.id then changed
    else prepared.root
    val candidateGraph = BendWorkspaceGraph.load(
      candidateRoot,
      prepared.selection.baseSource,
      prepared.selection.packageCache,
      overlay,
      () => canceled
    )
    val candidateSibling = prepared.snapshot.siblingLaws.map(record =>
      if record.id == changed.id then changed else record
    )
    val candidateSnapshot = BendGraphSnapshot.attach(
      BendCheckSnapshot(
        candidateRoot.id,
        candidateRoot.path,
        candidateRoot.text,
        candidateRoot.revision,
        prepared.selection,
        selectedPath = prepared.snapshot.selectedPath
      ),
      candidateGraph,
      candidateSibling
    )
    new BendCliCheckBackend().check(candidateSnapshot, () => canceled)

  private def validate(
      check: BendCheckResult,
      goal: BendGoal,
      canceled: () => Boolean
  ): BendProofEditPreviewOutcome =
    prepare(check, goal, canceled) match
      case Left(reason)    => BendProofEditPreviewOutcome.Rejected(reason)
      case Right(prepared) =>
        val replacement = "{==}"
        val checked = checkReplacement(prepared, replacement)
        if !inputsCurrent(prepared) then
          BendProofEditPreviewOutcome.Rejected("Goal validation canceled")
        else
          val complete = checked.outcome == BendCheckOutcome.Success &&
            checked.completeness == BendCompleteness.Complete
          val locallyValid = complete ||
            (checked.outcome == BendCheckOutcome.Failed &&
              checked.completeness == BendCompleteness.Incomplete &&
              checked.incompleteKind.contains(BendIncompleteKind.TodoHoles))
          if !locallyValid then
            BendProofEditPreviewOutcome.Rejected(
              "Reflexivity was rejected by Bend: " + checked.details
            )
          else
            BendProofEditPreviewOutcome.Validated(
              BendProofEditPreview(
                check,
                prepared.source,
                goal,
                replacement,
                checked,
                complete
              )
            )

  override def probeGoalResources(
      check: BendCheckResult,
      goal: BendGoal
  )(completed: BendResourceReportOutcome => Unit): Unit =
    def finish(outcome: BendResourceReportOutcome): Unit =
      ApplicationManager.getApplication.invokeLater(() =>
        if !project.isDisposed then completed(outcome)
      )
    ProgressManager
      .getInstance()
      .run(
        new Task.Backgroundable(project, "Checking Bend resources", true):
          override def run(indicator: ProgressIndicator): Unit =
            try
              val outcome = resourceReport(
                check,
                goal,
                () => indicator.isCanceled || project.isDisposed
              )
              if !indicator.isCanceled then finish(outcome)
            catch
              case NonFatal(error) if !indicator.isCanceled =>
                finish(
                  BendResourceReportOutcome.Unavailable(
                    "Could not check resources: " +
                      Option(error.getMessage)
                        .getOrElse(error.getClass.getSimpleName)
                  )
                )
              case NonFatal(_) => ()
      )

  private def resourceReport(
      check: BendCheckResult,
      goal: BendGoal,
      canceled: () => Boolean
  ): BendResourceReportOutcome =
    val compatible = goal.compatibleBindings
    if compatible.isEmpty then
      return BendResourceReportOutcome.Unavailable(
        "This compiler pairing has no supported binder comparison at this goal"
      )
    if goal.context.map(_.name).distinct.size != goal.context.size then
      return BendResourceReportOutcome.Unavailable(
        "Shadowed compiler binders cannot be associated safely with source names"
      )
    prepare(check, goal, canceled) match
      case Left(reason)    => BendResourceReportOutcome.Unavailable(reason)
      case Right(prepared) =>
        val names = compatible.get.toSet
        val candidates = goal.context.filter(binding => names(binding.name))
        val budget = System.nanoTime() + 15_000_000_000L
        val checked = candidates
          .take(8)
          .map { binding =>
            val status =
              if prepared.obsolete() || System.nanoTime() >= budget then
                BendResourceCandidateStatus.NotChecked
              else
                val result = checkReplacement(
                  prepared,
                  binding.name,
                  () => System.nanoTime() >= budget
                )
                if result.outcome == BendCheckOutcome.Success &&
                  result.completeness == BendCompleteness.Complete
                then
                  BendResourceCandidateStatus.AcceptedComplete(result.reliance)
                else if result.outcome == BendCheckOutcome.Failed &&
                  result.completeness == BendCompleteness.Incomplete &&
                  result.incompleteKind.contains(BendIncompleteKind.TodoHoles)
                then BendResourceCandidateStatus.AcceptedUntilTodo
                else if result.outcome == BendCheckOutcome.Failed &&
                  result.completeness != BendCompleteness.Incomplete
                then
                  BendResourceCandidateStatus.Rejected(result.details.take(512))
                else BendResourceCandidateStatus.NotChecked
            binding.name -> status
          }
          .toMap
        if !inputsCurrent(prepared) then
          BendResourceReportOutcome.Unavailable("Resource query became stale")
        else
          val bindings = goal.context.map { binding =>
            val status = if !names(binding.name) then
              BendResourceCandidateStatus.NotTypeCompatible
            else
              checked.getOrElse(
                binding.name,
                BendResourceCandidateStatus.NotChecked
              )
            BendResourceBinding(binding.name, binding.quantity, status)
          }
          BendResourceReportOutcome.Available(
            BendResourceReport(check, goal, bindings, candidates.size > 8)
          )
