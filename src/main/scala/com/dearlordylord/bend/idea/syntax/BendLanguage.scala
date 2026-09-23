package com.dearlordylord.bend.idea.syntax

import com.intellij.lang.Language

final class BendLanguage private () extends Language("Bend")

object BendLanguage:
  val instance: BendLanguage = new BendLanguage()
