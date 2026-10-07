package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceDeclarationFact,
  BendSourceHandle,
  BendSymbolCategory
}
import com.dearlordylord.bend.idea.symbols.declarations.BendDeclarationSite
import com.dearlordylord.bend.idea.workspace.model.{
  BendLoadedFile,
  BendLoadedGraph,
  BendSourceRecord
}
import junit.framework.TestCase
import org.junit.Assert.*

final class BendProofLinkCacheTest extends TestCase:
  private val revision = BendProofLinkRevision(1, 1, 1)
  private def inventory(
      path: String,
      count: Int = 0,
      nameLength: Int = 0,
      sourceLength: Int = 0
  ): BendProofLinkInventory =
    val id = new FileId(path, true)
    val source = BendSourceRecord(id, path, "x" * sourceLength, 1, Nil)
    val declarations = (0 until count).map { offset =>
      val handle = BendSourceHandle(id, BendSymbolCategory.Law, offset)
      val name = "n" * nameLength
      handle -> BendSourceDeclarationFact(
        handle,
        name,
        BendSymbolCategory.Law,
        BendDeclarationSite(name, offset, true, false, false, false)
      )
    }.toMap
    BendProofLinkInventory(
      BendLoadedGraph(id, List(BendLoadedFile(source, "")), Nil, Nil),
      id,
      declarations,
      Map.empty,
      Map.empty,
      Set.empty,
      false
    )

  def testAggregateDeclarationLimitAcceptsBoundaryAndEvictsLeastRecentRoot()
      : Unit =
    val cache = new BendProofLinkCache
    (0 until 4).foreach(i =>
      cache.put(s"root$i", revision, inventory(s"root$i", count = 4096))
    )
    assertTrue(cache.get("root0", revision).nonEmpty)
    cache.put("extra", revision, inventory("extra", count = 1))
    assertTrue(cache.get("root0", revision).nonEmpty)
    assertTrue(cache.get("root1", revision).isEmpty)
    assertTrue(cache.get("extra", revision).nonEmpty)

  def testAggregateNameLimitAndReplacementAccounting(): Unit =
    val cache = new BendProofLinkCache
    cache.put("a", revision, inventory("a", count = 1, nameLength = 1000000))
    cache.put("b", revision, inventory("b", count = 1, nameLength = 1000000))
    assertTrue(cache.get("a", revision).nonEmpty)
    cache.put("a", revision, inventory("a", count = 1, nameLength = 1000000))
    assertTrue(
      "Replacing a root must remove its previous weight",
      cache.get("b", revision).nonEmpty
    )
    assertTrue(cache.get("a", revision).nonEmpty)
    cache.put("extra", revision, inventory("extra", count = 1, nameLength = 1))
    assertTrue(cache.get("b", revision).isEmpty)
    assertTrue(cache.get("a", revision).nonEmpty)

  def testAggregateSourceLimitAndOversizedEntryPreserveAdmissibleRoots(): Unit =
    val cache = new BendProofLinkCache
    cache.put("a", revision, inventory("a", sourceLength = 2000000))
    cache.put("b", revision, inventory("b", sourceLength = 2000000))
    assertTrue(cache.get("a", revision).nonEmpty)
    cache.put("a", revision, inventory("a", sourceLength = 2000000))
    assertTrue(cache.get("b", revision).nonEmpty)
    assertTrue(cache.get("a", revision).nonEmpty)
    cache.put("extra", revision, inventory("extra", sourceLength = 1))
    assertTrue(cache.get("b", revision).isEmpty)
    assertTrue(cache.get("a", revision).nonEmpty)
    val roots = new BendProofLinkCache
    (0 until 32).foreach(i =>
      roots.put(s"root$i", revision, inventory(s"root$i"))
    )
    roots.put("oversized", revision, inventory("oversized", count = 16385))
    (0 until 32).foreach(i =>
      assertTrue(roots.get(s"root$i", revision).nonEmpty)
    )
    assertTrue(roots.get("oversized", revision).isEmpty)
    roots.put(
      "longName",
      revision,
      inventory("longName", count = 1, nameLength = 2000001)
    )
    assertTrue(roots.get("longName", revision).isEmpty)

  def testDisposalClearsCacheAndRejectsLatePublication(): Unit =
    val cache = new BendProofLinkCache
    cache.put("a", revision, inventory("a"))
    cache.dispose()
    cache.put("late", revision, inventory("late"))
    assertTrue(cache.get("a", revision).isEmpty)
    assertTrue(cache.get("late", revision).isEmpty)
