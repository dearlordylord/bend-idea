package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.workspace.api.{BendSourceCatalog}
import com.dearlordylord.bend.idea.workspace.model.{BendSourceRecord}
import com.intellij.testFramework.ServiceContainerUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendProofCacheIdentityTest extends BasePlatformTestCase:
  def testWarmCacheRejectsRetargetedCanonicalSourceWithIdenticalText(): Unit =
    val first =
      myFixture.addFileToProject("identity/first.bend", "law claim:\n  Type\n")
    val second =
      myFixture.addFileToProject("identity/second.bend", first.getText)
    val proof = myFixture.addFileToProject(
      "identity/PROOF.bend",
      "import ./link.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val catalog = getProject.getService(classOf[BendSourceCatalog])
    var retargeted = false
    val link = proof.getVirtualFile.getParent.getPath + "/link.bend"
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendSourceCatalog],
      new BendSourceCatalog:
        override def source(path: String): Option[BendSourceRecord] =
          if path == link then
            catalog
              .source((if retargeted then second
                       else first).getVirtualFile.getPath)
              .map(_.copy(path = path))
          else catalog.source(path)
        override def canonicalPath(path: String): String =
          catalog.canonicalPath(path)
        override def cachedPackageHash(
            cache: String,
            name: String
        ): Option[String] = catalog.cachedPackageHash(cache, name)
      ,
      getTestRootDisposable
    )
    val firstLaw = BendSourceSymbols.declarations(first).head
    val paths = List(proof.getVirtualFile.getPath)
    assertEquals(
      1,
      BendProofNavigation.destinations(first, firstLaw, paths).size
    )
    assertEquals(
      1,
      BendProofNavigation.destinations(first, firstLaw, paths).size
    )
    val before = com.intellij.openapi.vfs.VirtualFileManager
      .getInstance()
      .getModificationCount
    retargeted = true
    assertTrue(BendProofNavigation.destinations(first, firstLaw, paths).isEmpty)
    assertEquals(
      "Identity change is detected without a VFS invalidation event",
      before,
      com.intellij.openapi.vfs.VirtualFileManager
        .getInstance()
        .getModificationCount
    )
    assertEquals(
      1,
      BendProofNavigation
        .destinations(
          second,
          BendSourceSymbols.declarations(second).head,
          paths
        )
        .size
    )

  def testWarmCacheRejectsNamedPackageRemappingWithoutSourceEdits(): Unit =
    val first = myFixture.addFileToProject(
      "cache/0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/LAWS.bend",
      "law claim:\n  Type\n"
    )
    val second =
      myFixture.addFileToProject(
        "cache/0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/LAWS.bend",
        first.getText
      )
    val proof = myFixture.addFileToProject(
      "named/PROOF.bend",
      "import laws@1.0.0.0/LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val catalog = getProject.getService(classOf[BendSourceCatalog])
    var hash = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendSourceCatalog],
      new BendSourceCatalog:
        override def source(path: String): Option[BendSourceRecord] =
          catalog.source(path)
        override def canonicalPath(path: String): String =
          catalog.canonicalPath(path)
        override def cachedPackageHash(
            cache: String,
            name: String
        ): Option[String] =
          if name == "laws@1.0.0.0" then Some(hash)
          else catalog.cachedPackageHash(cache, name)
      ,
      getTestRootDisposable
    )
    val settings =
      com.intellij.openapi.application.ApplicationManager.getApplication
        .getService(
          classOf[
            com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
          ]
        )
    val original = settings.choices
    try
      settings.update(
        original.copy(packageCache =
          first.getVirtualFile.getParent.getParent.getPath
        )
      )
      val firstLaw = BendSourceSymbols.declarations(first).head
      val paths = List(proof.getVirtualFile.getPath)
      assertEquals(
        1,
        BendProofNavigation.destinations(first, firstLaw, paths).size
      )
      assertEquals(
        1,
        BendProofNavigation.destinations(first, firstLaw, paths).size
      )
      val before = com.intellij.openapi.vfs.VirtualFileManager
        .getInstance()
        .getModificationCount
      hash = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      assertTrue(
        BendProofNavigation.destinations(first, firstLaw, paths).isEmpty
      )
      assertEquals(
        before,
        com.intellij.openapi.vfs.VirtualFileManager
          .getInstance()
          .getModificationCount
      )
      assertEquals(
        1,
        BendProofNavigation
          .destinations(
            second,
            BendSourceSymbols.declarations(second).head,
            paths
          )
          .size
      )
    finally settings.update(original)
