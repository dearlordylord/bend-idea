package com.dearlordylord.bend.idea.adapters.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.Files
import java.util.concurrent.{Callable, FutureTask, TimeUnit}

final class BendExplicitCheckRunnerServiceTest extends BasePlatformTestCase:
  def testDocumentLookupAcquiresReadAccessOnPooledThread(): Unit =
    val path = Files.createTempFile("bend-explicit-root-", ".bend")
    try
      Files.writeString(path, "def main():\n  0\n")
      val virtualFile = LocalFileSystem
        .getInstance()
        .refreshAndFindFileByNioFile(path)
      assertNotNull(
        "Fixture file should be visible in the local VFS",
        virtualFile
      )
      val runner = new BendExplicitCheckRunnerService(getProject)
      val task = new FutureTask[Boolean](new Callable[Boolean]:
        override def call(): Boolean = runner.documentAt(path).nonEmpty)

      ApplicationManager.getApplication.executeOnPooledThread(task)

      assertTrue(task.get(10, TimeUnit.SECONDS))
    finally
      val _ = Files.deleteIfExists(path)
