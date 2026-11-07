package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.toolchain.api.{
  BendLintOptions,
  BendToolchainChoices
}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.components.State
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*

final class BendSettingsStorageTest extends BasePlatformTestCase:
  def testPersistenceMetadataNamesTheSettingsFile(): Unit =
    val state = classOf[BendSettingsStorage].getAnnotation(classOf[State])
    assertEquals("BendSettings.xml", state.storages().head.value())

  def testXmlPersistenceRestoresEveryConfiguredSetting(): Unit =
    val configured = BendToolchainChoices(
      "/tools/bend",
      "/libraries/base.bend",
      "/libraries/cache",
      diagnosticsEnabled = false,
      lint = BendLintOptions(
        enabled = true,
        bun = "/tools/bun",
        directory = "/tools/bend-lint",
        background = true
      )
    )
    val storage = new BendSettingsStorage
    storage.update(configured)
    val serialized = XmlSerializer.serialize(storage.getState)
    val restored = new BendSettingsStorage
    restored.loadState(
      XmlSerializer.deserialize(serialized, classOf[BendSettingsState])
    )
    assertEquals(
      "IDE XML persistence must retain compiler, library and lint settings",
      configured,
      restored.choices
    )

  def testPersistenceStateIsCopiedOnLoadAndExport(): Unit =
    val storage = new BendSettingsStorage
    val incoming = new BendSettingsState
    incoming.executable = "/tools/bend"
    incoming.lintEnabled = true
    incoming.lintBun = "/tools/bun"
    incoming.lintDirectory = "/tools/bend-lint"
    incoming.lintBackground = true

    storage.loadState(incoming)
    incoming.executable = "/tools/changed-after-load"
    incoming.lintDirectory = "/changed"
    assertEquals("/tools/bend-lint", storage.choices.lint.directory)
    assertEquals("/tools/bun", storage.selection.lint.bun)
    assertTrue(storage.selection.lint.enabled)
    assertTrue(storage.selection.lint.background)
    assertEquals("/tools/bend", storage.choices.executable)

    val exported = storage.getState
    exported.executable = "/tools/changed-after-export"
    exported.lintEnabled = false
    assertTrue(storage.choices.lint.enabled)
    assertEquals("/tools/bend", storage.choices.executable)
