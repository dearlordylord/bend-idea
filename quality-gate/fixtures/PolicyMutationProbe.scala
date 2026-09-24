package com.dearlordylord.bend.idea.analysis.checking

object PolicyMutationProbe:
  def increment(): Int =
    var counter = 0
    counter += 1
    counter
