package com.dearlordylord.bend.idea.syntax

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendFileTypeTest extends BasePlatformTestCase:
  def testOpeningBendFileUsesRegisteredLanguage(): Unit =
    val source = "# unsaved Bend source\nlaw example : Type\n"
    val file = myFixture.configureByText("example.bend", source)
    val fileType = file.getVirtualFile.getFileType
    assertTrue(fileType.isInstanceOf[BendFileType])
    assertSame(
      BendLanguage.instance,
      fileType.asInstanceOf[BendFileType].getLanguage
    )
    assertSame(
      fileType,
      FileTypeManager.getInstance.getFileTypeByExtension("bend")
    )
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testBothThemeIconsLoad(): Unit =
    IconLoader.activate()
    for path <- Seq("/icons/bend.svg", "/icons/bend_dark.svg") do
      val icon = IconLoader.getIcon(path, classOf[BendFileType])
      assertEquals(16, icon.getIconWidth)
      assertEquals(16, icon.getIconHeight)
