package com.dearlordylord.bend.idea.analysis.model

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.BendImportLines
import org.junit.Assert.*
import org.junit.Test

final class BendSourceMappingTest:
  private val original =
    "# 😀 heading\r\n" +
      "import ./first.bend as A\r\n" +
      "import ../nested/second-long.bend as B\r\n" +
      "def main() -> Type:\r\n" +
      "  Type\r\n"

  private def mapping(): BendSourceMapping =
    val replacements = List(
      "./first.bend" -> "../../copies/first.bend",
      "../nested/second-long.bend" -> "./b.bend")
    val edits = replacements.map { case (before, after) =>
      val start = original.indexOf(before)
      (start, start + before.length, before, after)
    }.sortBy(_._1)
    val copiedBuilder = new StringBuilder
    val rewriteRanges = List.newBuilder[BendRewrittenRange]
    var originalCursor = 0
    edits.foreach { case (start, end, _, replacement) =>
      copiedBuilder.append(original.substring(originalCursor, start))
      val copiedStart = copiedBuilder.length
      copiedBuilder.append(replacement)
      rewriteRanges += BendRewrittenRange(start, end, copiedStart, copiedBuilder.length)
      originalCursor = end
    }
    copiedBuilder.append(original.substring(originalCursor))
    val copied = copiedBuilder.result()

    val importLines = BendImportLines.parse(copied).map { imp =>
      val start = copied.lastIndexOf('\n', math.max(0, imp.offset - 1)) + 1
      val contentEnd = copied.indexOf('\n', imp.offset) match
        case -1 => copied.length
        case end => end
      val end = if contentEnd < copied.length then contentEnd + 1 else contentEnd
      (start, end, copied.take(start).count(_ == '\n'))
    }
    val compilerBuilder = new StringBuilder
    val blankedRanges = List.newBuilder[BendBlankedRange]
    var copiedCursor = 0
    importLines.foreach { case (start, end, line) =>
      compilerBuilder.append(copied.substring(copiedCursor, start))
      val compilerStart = compilerBuilder.length
      if copied.charAt(end - 1) == '\n' then compilerBuilder.append('\n')
      blankedRanges += BendBlankedRange(line, start, end, compilerStart,
        compilerBuilder.length)
      copiedCursor = end
    }
    compilerBuilder.append(copied.substring(copiedCursor))

    val rewrites = rewriteRanges.result()
    BendSourceMapping(new FileId("/tmp/source.bend", false), 17L,
      "/tmp/source.bend", "/tmp/copy/source.bend", "A",
      original, copied, rewrites.map(r => original.take(r.originalStart).count(_ == '\n')).toSet,
      rewrites, compilerBuilder.result(), blankedRanges.result())

  @Test def composedOffsetsUseUtf16AndPreserveBothSidesOfEveryRewrite(): Unit =
    val source = mapping()
    val first = source.rewrites.head
    val second = source.rewrites(1)

    assertEquals(2, "😀".length)
    val astral = source.originalText.indexOf("😀")
    assertEquals(Some(astral), source.originalOffset(source.copiedText.indexOf("😀")))
    assertEquals(Some(astral + 1), source.originalOffset(source.copiedText.indexOf("😀") + 1))
    assertEquals(Some(astral + 2), source.originalOffset(source.copiedText.indexOf("😀") + 2))

    assertEquals(Some(first.originalStart), source.originalOffset(first.copiedStart))
    assertEquals(None, source.originalOffset(first.copiedStart + 1))
    assertEquals(Some(first.originalEnd), source.originalOffset(first.copiedEnd))
    assertEquals(Some(first.copiedStart), source.originalToCopiedOffset(first.originalStart))
    assertEquals(None, source.originalToCopiedOffset(first.originalStart + 1))
    assertEquals(Some(first.copiedEnd), source.originalToCopiedOffset(first.originalEnd))
    assertEquals(Some(second.originalStart), source.originalOffset(second.copiedStart))
    assertEquals(None, source.originalOffset(second.copiedStart + 1))
    assertEquals(Some(second.originalEnd), source.originalOffset(second.copiedEnd))
    assertEquals(Some(0), source.originalOffset(0))
    assertEquals(Some(source.originalText.length), source.originalOffset(source.copiedText.length))
    assertEquals(None, source.originalOffset(-1))
    assertEquals(None, source.originalOffset(source.copiedText.length + 1))

    assertEquals(Some(BendTextRange(first.originalStart, first.originalStart)),
      source.copiedToOriginalRange(first.copiedStart, first.copiedStart))
    assertEquals(None, source.copiedToOriginalRange(first.copiedStart, first.copiedEnd))
    assertEquals(None, source.copiedToOriginalRange(first.copiedStart - 1, first.copiedEnd + 1))
    assertEquals(Some(BendTextRange(first.originalStart - 1, first.originalStart)),
      source.copiedToOriginalRange(first.copiedStart - 1, first.copiedStart))

  @Test def absoluteImportRewriteMapsItsStartBoundaryInBothDirections(): Unit =
    val originalText =
      "import /opt/bend/lib/module.bend as M\n" +
        "def main() -> Type:\n  M.item()\n"
    val originalPath = "/opt/bend/lib/module.bend"
    val copiedPath = "./module.bend"
    val originalStart = originalText.indexOf(originalPath)
    val originalEnd = originalStart + originalPath.length
    val copiedText = originalText.replace(originalPath, copiedPath)
    val copiedStart = copiedText.indexOf(copiedPath)
    val copiedEnd = copiedStart + copiedPath.length
    val blankedEnd = copiedText.indexOf('\n') + 1
    val source = BendSourceMapping(new FileId("/tmp/absolute.bend", false), 29L,
      "/tmp/absolute.bend", "/tmp/copy/absolute.bend", "M",
      originalText, copiedText, Set(0),
      List(BendRewrittenRange(originalStart, originalEnd, copiedStart, copiedEnd)),
      "\n" + copiedText.substring(blankedEnd),
      List(BendBlankedRange(0, 0, blankedEnd, 0, 1)))

    assertEquals(Some(copiedStart), source.originalToCopiedOffset(originalStart))
    assertEquals(Some(originalStart), source.originalOffset(copiedStart))
    assertEquals(Some(BendTextRange(originalStart - 1, originalStart)),
      source.copiedToOriginalRange(copiedStart - 1, copiedStart))

  @Test def blankedCrlfImportsAreSyntheticAndBodyRangesComposeExactly(): Unit =
    val source = mapping()
    val firstBlank = source.blankedImportRanges.head
    val secondBlank = source.blankedImportRanges(1)

    assertEquals(None, source.compilerToCopiedOffset(firstBlank.compilerStart))
    // This edge is also the start of the next synthetic import line, so it is ambiguous.
    assertEquals(None, source.compilerToCopiedOffset(firstBlank.compilerEnd))
    assertEquals(None, source.compilerToCopiedOffset(secondBlank.compilerStart))
    assertEquals(Some(secondBlank.copiedEnd), source.compilerToCopiedOffset(secondBlank.compilerEnd))
    assertEquals(Some(0), source.compilerToCopiedOffset(0))
    assertEquals(None, source.compilerToCopiedOffset(-1))
    assertEquals(None, source.compilerToCopiedOffset(source.compilerText.length + 1))
    assertEquals(None, source.compilerToCopiedRange(firstBlank.compilerStart, firstBlank.compilerEnd))
    assertEquals(None, source.compilerToCopiedRange(firstBlank.compilerStart,
      source.compilerText.indexOf("def main") + 1))

    val cr = source.compilerText.indexOf('\r', source.compilerText.indexOf("def main"))
    val lf = cr + 1
    val nextLine = lf + 1
    assertEquals(Some(source.copiedText.indexOf('\r', source.copiedText.indexOf("def main"))),
      source.compilerToCopiedOffset(cr))
    assertEquals(Some(source.copiedText.indexOf('\n', source.copiedText.indexOf("def main"))),
      source.compilerToCopiedOffset(lf))
    assertEquals(Some(source.copiedText.indexOf("  Type")), source.compilerToCopiedOffset(nextLine))

    val body = source.compilerText.indexOf("Type\r", source.compilerText.indexOf("def main"))
    val exact = source.compilerToOriginalRange(body, body + "Type".length).getOrElse(
      throw new AssertionError("Body span should map through both transformations"))
    assertEquals("Type", source.originalText.substring(exact.start, exact.end))
    assertEquals(None, source.compilerToOriginalRange(firstBlank.compilerStart,
      firstBlank.compilerEnd))
    assertEquals(Some(BendTextRange(source.originalText.length, source.originalText.length)),
      source.compilerToOriginalRange(source.compilerText.length, source.compilerText.length))
    assertEquals(Some(source.originalText.length),
      source.compilerToOriginalOffset(source.compilerText.length))
    assertEquals(Some(source.copiedText.length), source.copiedOffset(source.compilerText.length))

  @Test def malformedOrNonPreservingMapsAreUnavailable(): Unit =
    val source = mapping()
    val malformed = source.copy(rewrites = source.rewrites.reverse)
    assertEquals(None, malformed.originalOffset(0))
    val malformedBlanks = source.copy(blankedImportRanges = source.blankedImportRanges.reverse)
    assertEquals(None, malformedBlanks.compilerToCopiedOffset(0))
    val changedOutsideEdit = source.copy(copiedText = source.copiedText.replace("heading", "changed"))
    assertEquals(None, changedOutsideEdit.originalOffset(0))
