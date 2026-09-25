package com.dearlordylord.bend.idea.test

import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import java.nio.file.{Files, Path}

object VfsTestRoots:
  def allowSystemTemporaryDirectory(
      disposable: Disposable,
      additionalRoots: Path*
  ): Unit =
    val temporaryRoot = Path
      .of(System.getProperty("java.io.tmpdir"))
      .toAbsolutePath
      .normalize()
    val realRoot =
      if Files.exists(temporaryRoot) then
        List(temporaryRoot.toRealPath().toString)
      else Nil
    val extraRoots = additionalRoots.toList.flatMap { path =>
      val absolute = path.toAbsolutePath.normalize()
      absolute.toString ::
        (if Files.exists(absolute) then List(absolute.toRealPath().toString)
         else Nil)
    }
    val roots = ((temporaryRoot.toString :: realRoot) ++ extraRoots).distinct
    VfsRootAccess.allowRootAccess(disposable, roots*)
