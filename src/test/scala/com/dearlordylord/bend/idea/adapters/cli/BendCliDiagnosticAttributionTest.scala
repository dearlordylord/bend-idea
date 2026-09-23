package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import org.junit.Assert.*
import org.junit.Test

final class BendCliDiagnosticAttributionTest:
  private val original =
    "import ./module.bend as M\ndef broken() -> Type:\n  unknown\n"
  private val copied =
    "import ../copies/module.bend as M\ndef broken() -> Type:\n  unknown\n"
  private val source = new FileId("/tmp/imported.bend", false)
  private val pathStart = original.indexOf("./module.bend")
  private val pathEnd = pathStart + "./module.bend".length
  private val copiedPathStart = copied.indexOf("../copies/module.bend")
  private val copiedPathEnd = copiedPathStart + "../copies/module.bend".length
  private val mapping = BendSourceMapping(source, 23L,
    "/tmp/imported.bend", "/tmp/copied/imported.bend", "M",
    original, copied, Set(0),
    List(BendRewrittenRange(pathStart, pathEnd, copiedPathStart, copiedPathEnd)),
    "\ndef broken() -> Type:\n  unknown\n",
    List(BendBlankedRange(0, 0, copied.indexOf('\n') + 1, 0, 1)))

  @Test def uniqueUnchangedCompilerExcerptIdentifiesItsSourceLine(): Unit =
    assertEquals(BendLocation.SourceLine(source, 2),
      BendCliDiagnosticAttribution.graphLocation("3>| unknown", List(mapping)))

  @Test def duplicateExcerptStaysOnRoot(): Unit =
    val duplicate = mapping.copy(source = new FileId("/tmp/other.bend", false))
    assertEquals(BendLocation.RootOnly,
      BendCliDiagnosticAttribution.graphLocation("3>| unknown", List(mapping, duplicate)))

  @Test def blankedAndRewrittenLinesStayOnRoot(): Unit =
    assertEquals(BendLocation.RootOnly,
      BendCliDiagnosticAttribution.graphLocation("1>|", List(mapping)))
    val unblanked = mapping.copy(compilerText = copied, blankedImportRanges = Nil)
    assertEquals(BendLocation.RootOnly,
      BendCliDiagnosticAttribution.graphLocation("1>| import ../copies/module.bend as M",
        List(unblanked)))
