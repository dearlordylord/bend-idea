package com.dearlordylord.bend.idea.syntax

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Instantiated by IntelliJ through plugin.xml; no compiler or runtime startup.
  */
final class BendFileType extends LanguageFileType(BendLanguage.instance):
  override def getName: String = "Bend"
  override def getDescription: String = "Bend 2 source file"
  override def getDefaultExtension: String = "bend"
  override def getIcon: Icon =
    IconLoader.getIcon("/icons/bend.svg", classOf[BendFileType])
