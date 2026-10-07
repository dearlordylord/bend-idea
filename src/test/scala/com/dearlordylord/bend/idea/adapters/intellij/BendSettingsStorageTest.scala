package com.dearlordylord.bend.idea.adapters.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendSettingsStorageTest extends BasePlatformTestCase:
  def testPersistenceStateIsCopiedOnLoadAndExport(): Unit =
    val storage = new BendSettingsStorage
    val incoming = new BendSettingsState
    incoming.executable = "/tools/bend"
    incoming.lintEnabled = true
    incoming.lintBun = "/tools/bun"
    incoming.lintDirectory = "/tools/bend-lint"
    incoming.lintSemanticObservations = true

    storage.loadState(incoming)
    incoming.executable = "/tools/changed-after-load"
    incoming.lintDirectory = "/changed"
    assertEquals("/tools/bend-lint", storage.choices.lint.directory)
    assertEquals("/tools/bun", storage.selection.lint.bun)
    assertTrue(storage.selection.lint.enabled)
    assertTrue(storage.selection.lint.semanticObservations)
    assertEquals("/tools/bend", storage.choices.executable)

    val exported = storage.getState
    exported.executable = "/tools/changed-after-export"
    exported.lintEnabled = false
    assertTrue(storage.choices.lint.enabled)
    assertEquals("/tools/bend", storage.choices.executable)
