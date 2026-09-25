package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.symbols.api.{BendSourceApplications}
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendDefinition,
  BendLaw,
  BendReferenceElement
}
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendWorkspacePaths
}
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.{
  PsiElement,
  PsiFile,
  PsiManager,
  PsiPolyVariantReference,
  PsiReference
}
import com.intellij.psi.util.PsiTreeUtil

enum BendDependencyKind:
  case Import, ImportedBy, Call, CalledBy, UnknownCall, UnknownImport

final case class BendStaticDependency(
    kind: BendDependencyKind,
    label: String,
    navigation: PsiElement,
    offset: Int
):
  override def toString: String = label

final case class BendStaticDependencyReport(
    dependencies: List[BendStaticDependency],
    unresolvedNamedCalls: Int,
    skippedLargeSources: Int
):
  val coverage: String =
    s"Static source view: explicit imports and statically resolved named calls. " +
      s"$unresolvedNamedCalls named calls are unresolved or dynamic; " +
      s"$skippedLargeSources large sources were skipped. Higher-order calls " +
      "and implicit compiler sugar may be absent. This is not a complete runtime call graph."

/** One-hop dependency inspection based on current PSI references and source
  * identities. The bounded inventory is scanned once; cycles are never
  * recursively expanded.
  */
object BendStaticDependencies:
  private val MaxFiles = 512
  private val MaxSourceLength = 1024 * 1024

  private final case class DeclarationKey(path: String, nameOffset: Int)
  private final case class CallEdge(
      from: PsiFile,
      owner: Option[BendDeclaration],
      usage: BendReferenceElement,
      target: BendDeclaration,
      targetKey: DeclarationKey,
      name: String
  )
  private final case class ImportEdge(
      from: PsiFile,
      target: Option[PsiFile],
      aliases: Set[String],
      reference: PsiReference,
      targetPath: Option[String],
      spelling: String
  )

  def inspect(
      selected: PsiElement
  ): Either[String, BendStaticDependencyReport] =
    val selectedFile = selected match
      case file: PsiFile => file
      case element       => element.getContainingFile
    if selectedFile == null || selectedFile.getLanguage != BendLanguage.instance
    then return Left("Choose a Bend source file or declaration.")

    val project = selectedFile.getProject
    val inventory = project
      .getService(classOf[BendWorkspacePaths])
      .filesWithExtension("bend", MaxFiles)
    inventory.flatMap { paths =>
      val roots = ProjectRootManager.getInstance(project).getContentRoots.toList
      val indexed = paths
        .flatMap { path =>
          ProgressManager.checkCanceled()
          virtualFile(path, roots)
        }
        .flatMap(vf => Option(PsiManager.getInstance(project).findFile(vf)))
      val sources = (List(selectedFile) ++ indexed)
        .filter(file =>
          file.isValid && file.getLanguage == BendLanguage.instance
        )
        .distinctBy(pathIdentity)
      val skipped = sources.count(_.getTextLength > MaxSourceLength)
      val bounded = sources.filter(_.getTextLength <= MaxSourceLength)
      val selectedPath = pathIdentity(selectedFile)
      val selectedDeclaration = selected match
        case declaration: BendDeclaration => Some(declaration)
        case other                        =>
          Option(
            PsiTreeUtil.getParentOfType(other, classOf[BendDeclaration], false)
          )
      val selectedKey = selectedDeclaration.map(declarationKey)

      val calls = bounded.flatMap { file =>
        ProgressManager.checkCanceled()
        callEdges(file)
      }
      val imports = bounded.flatMap { file =>
        ProgressManager.checkCanceled()
        importEdges(file)
      }
      val unresolvedCalls = calls.collect { case Left(_) => () }.size
      val resolvedCalls = calls.collect { case Right(edge) => edge }
      val outgoingCalls = resolvedCalls.filter(edge =>
        pathIdentity(edge.from) == selectedPath &&
          selectedDeclaration.forall(declaration =>
            declaration.getTextRange.contains(edge.usage.getTextOffset)
          )
      )
      val incomingCalls = selectedKey.toList.flatMap(key =>
        resolvedCalls.filter(_.targetKey == key)
      ) ++ (if selectedDeclaration.isEmpty then
              resolvedCalls.filter(_.targetKey.path == selectedPath)
            else Nil)
      val unresolvedOutgoing = bounded.flatMap(file =>
        ProgressManager.checkCanceled()
        if pathIdentity(file) != selectedPath then Nil
        else
          callEdges(file).collect {
            case Left((name, reference, owner))
                if selectedDeclaration.forall(declaration =>
                  declaration.getTextRange.contains(reference.getTextOffset)
                ) =>
              val context = owner.map(_.getName + " calls ").getOrElse("Call ")
              BendStaticDependency(
                BendDependencyKind.UnknownCall,
                context + name + " (unresolved or dynamic)",
                reference,
                reference.getTextOffset
              )
          }
      )

      val outgoingImports = imports
        .filter(edge =>
          pathIdentity(edge.from) == selectedPath && edge.target.nonEmpty
        )
        .groupBy(_.targetPath.get)
        .values
        .map { edges =>
          val first = edges.head
          val aliases = edges.flatMap(_.aliases).distinct.sorted
          val via =
            if aliases.isEmpty then "" else s" via ${aliases.mkString(", ")}"
          BendStaticDependency(
            BendDependencyKind.Import,
            s"Imports ${fileName(first.target.get)}$via",
            first.target.get,
            0
          )
        }
        .toList
      val incomingImports = imports
        .filter(edge => edge.targetPath.contains(selectedPath))
        .groupBy(edge => pathIdentity(edge.from))
        .values
        .map { edges =>
          val first = edges.head
          val aliases = edges.flatMap(_.aliases).distinct.sorted
          val via =
            if aliases.isEmpty then "" else s" via ${aliases.mkString(", ")}"
          BendStaticDependency(
            BendDependencyKind.ImportedBy,
            s"Imported by ${fileName(first.from)}$via",
            first.reference.getElement,
            first.reference.getRangeInElement.getStartOffset
          )
        }
        .toList
      val unresolvedOutgoingImports = imports
        .filter(edge =>
          pathIdentity(edge.from) == selectedPath && edge.target.isEmpty
        )
        .map(edge =>
          BendStaticDependency(
            BendDependencyKind.UnknownImport,
            s"Unresolved import ${edge.spelling}",
            edge.reference.getElement,
            edge.reference.getRangeInElement.getStartOffset
          )
        )

      val callDependencies = outgoingCalls.map { edge =>
        BendStaticDependency(
          BendDependencyKind.Call,
          s"${ownerName(edge.owner)} calls ${edge.target.getName}",
          edge.target.getNameIdentifier,
          edge.target.getNameIdentifier.getTextOffset
        )
      }
      val callerDependencies = incomingCalls.map { edge =>
        BendStaticDependency(
          BendDependencyKind.CalledBy,
          s"${ownerName(edge.owner)} calls ${edge.target.getName}",
          edge.usage,
          edge.usage.getTextOffset
        )
      }
      val dependencies =
        (outgoingImports ++ incomingImports ++ callDependencies ++
          callerDependencies ++ unresolvedOutgoing ++ unresolvedOutgoingImports)
          .distinctBy(dependency =>
            (
              dependency.kind,
              dependency.label,
              pathIdentity(dependency.navigation.getContainingFile),
              dependency.offset
            )
          )
          .sortBy(dependency => (dependency.kind.ordinal, dependency.label))
      Right(
        BendStaticDependencyReport(dependencies, unresolvedCalls, skipped)
      )
    }

  private def callEdges(
      file: PsiFile
  ): List[
    Either[(String, BendReferenceElement, Option[BendDeclaration]), CallEdge]
  ] =
    BendSourceApplications.namedCalls(file).flatMap { application =>
      ProgressManager.checkCanceled()
      val leaf = file.findElementAt(application.calleeFrom)
      val reference =
        if leaf == null then null
        else
          PsiTreeUtil.getParentOfType(
            leaf,
            classOf[BendReferenceElement],
            false
          )
      if reference == null || reference.getTextOffset != application.calleeFrom
      then Nil
      else
        val owner = Option(
          PsiTreeUtil.getParentOfType(
            reference,
            classOf[BendDeclaration],
            false
          )
        )
        val resolved = reference.getReferences.toList
          .flatMap(resolvedTargets)
          .flatMap(declaration)
          .filter(target =>
            target.isInstanceOf[BendDefinition] || target
              .isInstanceOf[BendLaw]
          )
          .distinctBy(declarationKey)
        resolved match
          case target :: Nil =>
            Right(
              CallEdge(
                file,
                owner,
                reference,
                target,
                declarationKey(target),
                application.callee
              )
            ) :: Nil
          case _ => Left((application.callee, reference, owner)) :: Nil
    }

  private def importEdges(file: PsiFile): List[ImportEdge] =
    val source = file.getText
    val lines = BendImportLines.parse(source)
    file.getReferences.toList.flatMap { reference =>
      ProgressManager.checkCanceled()
      lines
        .find(line =>
          BendImportLines.pathRange(source, line).exists { case (start, end) =>
            start == reference.getRangeInElement.getStartOffset &&
            end == reference.getRangeInElement.getEndOffset
          }
        )
        .toList
        .map { line =>
          val target = Option(reference.resolve()).collect {
            case imported: PsiFile => imported
          }
          ImportEdge(
            file,
            target,
            line.alias.toSet,
            reference,
            target.map(pathIdentity),
            line.spelling
          )
        }
    }

  private def resolvedTargets(reference: PsiReference): List[PsiElement] =
    reference match
      case poly: PsiPolyVariantReference =>
        poly
          .multiResolve(false)
          .toList
          .flatMap(result => Option(result.getElement))
      case _ => Option(reference.resolve()).toList

  private def declaration(element: PsiElement): Option[BendDeclaration] =
    element match
      case value: BendDeclaration => Some(value)
      case other                  =>
        Option(
          PsiTreeUtil.getParentOfType(other, classOf[BendDeclaration], false)
        )

  private def declarationKey(declaration: BendDeclaration): DeclarationKey =
    DeclarationKey(
      pathIdentity(declaration.getContainingFile),
      declaration.getNameIdentifier.getTextOffset
    )

  private def ownerName(owner: Option[BendDeclaration]): String =
    owner.map(_.getName).getOrElse("Top-level source")

  private def fileName(file: PsiFile): String =
    Option(file.getVirtualFile).map(_.getName).getOrElse(file.getName)

  private def pathIdentity(file: PsiFile): String =
    Option(file)
      .flatMap(value => Option(value.getVirtualFile))
      .map(virtual =>
        Option(virtual.getCanonicalPath).getOrElse(virtual.getPath)
      )
      .getOrElse(file.getName)

  private def virtualFile(
      path: String,
      roots: List[com.intellij.openapi.vfs.VirtualFile]
  ): Option[com.intellij.openapi.vfs.VirtualFile] =
    Option(LocalFileSystem.getInstance().findFileByPath(path))
      .filter(_.isValid)
      .orElse {
        roots.iterator
          .flatMap { root =>
            val base = root.getPath.stripSuffix("/")
            if path == base then Some(root)
            else if path.startsWith(base + "/") then
              Option(root.findFileByRelativePath(path.drop(base.length + 1)))
            else None
          }
          .find(_.isValid)
      }
