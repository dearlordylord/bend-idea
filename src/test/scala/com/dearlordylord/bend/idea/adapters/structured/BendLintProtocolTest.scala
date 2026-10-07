package com.dearlordylord.bend.idea.adapters.structured

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import org.junit.Assert.*
import org.junit.Test

final class BendLintProtocolTest:
  private val source = "# 😀  \r\ndef main() -> Type:\r\n  Type\r\n"
  private val id = new FileId("/source.bend", true)
  private val mapping = BendSourceMapping(
    id,
    1L,
    id.value,
    "/snapshot.bend",
    "",
    source,
    source,
    Set.empty,
    Nil,
    source,
    Nil
  )
  private val reply =
    """{"ok":true,"findings":[{"code":"style/space","severity":"warning","message":"Trailing whitespace","path":"/snapshot.bend","range":{"start":{"line":0,"character":4},"end":{"line":0,"character":6}},"fixes":[]}]}"""

  @Test def utf16CrlfAndMissingLocationsRemainExplicit(): Unit =
    val result = BendLintProtocol.decode(reply, 0, List(mapping)).get
    assertEquals(BendLintOutcome.Completed, result.outcome)
    assertEquals(BendTextRange(4, 6), result.findings.head.range)
    assertEquals("  ", source.substring(4, 6))
    val missing = BendLintProtocol
      .decode(reply.replace("/snapshot.bend", "/other.bend"), 0, List(mapping))
      .get
    assertTrue(missing.findings.isEmpty)
    assertEquals(1, missing.unmapped)

  @Test def malformedRangesAndContradictoryExitAreNotCleanRuns(): Unit =
    assertTrue(BendLintProtocol.decode(reply, 1, List(mapping)).isEmpty)
    assertTrue(
      BendLintProtocol
        .decode(
          reply.replace("\"character\":4", "\"character\":4.5"),
          0,
          List(mapping)
        )
        .isEmpty
    )
    val invalid = BendLintProtocol
      .decode(
        reply.replace("\"character\":6", "\"character\":9000"),
        0,
        List(mapping)
      )
      .get
    assertEquals(1, invalid.unmapped)
    assertTrue(invalid.findings.isEmpty)
    assertTrue(
      BendLintProtocol
        .decode(reply.replace("warning", "success"), 0, List(mapping))
        .isEmpty
    )

  @Test def failedCheckIsDistinctFromNoFindings(): Unit =
    val failed = BendLintProtocol
      .decode("""{"ok":false,"findings":[]}""", 1, List(mapping))
      .get
    assertEquals(BendLintOutcome.CheckRejected, failed.outcome)
    val clean = BendLintProtocol
      .decode("""{"ok":true,"findings":[]}""", 0, List(mapping))
      .get
    assertEquals(BendLintOutcome.Completed, clean.outcome)
