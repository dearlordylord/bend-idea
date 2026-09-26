package com.dearlordylord.bend.idea.adapters.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendSettingsStorageTest extends BasePlatformTestCase:
  def testPersistenceStateIsCopiedOnLoadAndExport(): Unit =
    val storage = new BendSettingsStorage
    val incoming = new BendSettingsState
    incoming.executable = "/tools/bend"

    storage.loadState(incoming)
    incoming.executable = "/tools/changed-after-load"
    assertEquals("/tools/bend", storage.choices.executable)

    val exported = storage.getState
    exported.executable = "/tools/changed-after-export"
    assertEquals("/tools/bend", storage.choices.executable)
