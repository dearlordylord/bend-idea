package com.dearlordylord.bend.idea.symbols.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.{AdditionalLibraryRootsProvider, SyntheticLibrary}
import com.intellij.openapi.vfs.VirtualFile
import scala.jdk.CollectionConverters.*

/** Registers the current bounded Bend library inventory as project library roots. */
final class BendSymbolLibraryRootsProvider extends AdditionalLibraryRootsProvider:
  override def getAdditionalProjectLibraries(project: Project): java.util.Collection[SyntheticLibrary] =
    val roots = BendConfiguredSymbolRoots.roots(project)
    if roots.isEmpty then java.util.List.of()
    else java.util.List.of(SyntheticLibrary.newImmutableLibrary(roots.asJava))

  override def getRootsToWatch(project: Project): java.util.Collection[VirtualFile] =
    BendConfiguredSymbolRoots.watchRoots(project).asJava
