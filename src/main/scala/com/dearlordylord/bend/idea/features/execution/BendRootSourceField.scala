package com.dearlordylord.bend.idea.features.execution

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.{
  TextBrowseFolderListener,
  TextFieldWithBrowseButton
}

/** Selects a Bend source root through the platform file chooser. */
private[execution] object BendRootSourceField:
  def create(project: Project): TextFieldWithBrowseButton =
    val field = new TextFieldWithBrowseButton()
    val descriptor = FileChooserDescriptorFactory
      .createSingleFileDescriptor("bend")
      .withTitle("Select Bend Root Source")
      .withDescription("Choose a .bend source file to run or build")
    field.addBrowseFolderListener(
      new TextBrowseFolderListener(descriptor, project)
    )
    field.setEditable(false)
    field
