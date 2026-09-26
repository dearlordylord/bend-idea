package com.dearlordylord.bend.idea.features.execution

object UnsafeCastProbe:
  def force(value: AnyRef): String = value.asInstanceOf[String]
