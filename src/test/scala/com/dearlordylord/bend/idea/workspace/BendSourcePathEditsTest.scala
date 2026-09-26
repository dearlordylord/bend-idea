package com.dearlordylord.bend.idea.workspace

import com.dearlordylord.bend.idea.workspace.api.BendSourcePathEdits
import org.junit.Assert.*
import org.junit.Test

final class BendSourcePathEditsTest:
  @Test def relativePathsNormalizeSegments(): Unit =
    assertEquals(
      Some("../lib/target.bend"),
      BendSourcePathEdits.relativeSpelling(
        "/work/pkg/source.bend",
        "/work/lib/target.bend"
      )
    )
    assertEquals(
      Some("target.bend"),
      BendSourcePathEdits.relativeSpelling(
        "/work/pkg/./source.bend",
        "/work/pkg/sub/../target.bend"
      )
    )

  @Test def renamedLeafPreservesItsImportDirectory(): Unit =
    assertEquals(
      Some("../lib/renamed.bend"),
      BendSourcePathEdits.withFileName("../lib/old.bend", "renamed.bend")
    )
