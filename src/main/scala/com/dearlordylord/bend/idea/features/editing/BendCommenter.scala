package com.dearlordylord.bend.idea.features.editing

import com.intellij.lang.Commenter

/** IntelliJ's standard line-comment action owns write commands and undo. */
final class BendCommenter extends Commenter:
  override def getLineCommentPrefix: String = "#"
  override def getBlockCommentPrefix: String = null
  override def getBlockCommentSuffix: String = null
  override def getCommentedBlockCommentPrefix: String = null
  override def getCommentedBlockCommentSuffix: String = null
