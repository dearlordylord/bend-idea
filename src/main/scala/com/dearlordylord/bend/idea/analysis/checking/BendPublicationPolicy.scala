package com.dearlordylord.bend.idea.analysis.checking

/** Immutable publication facts captured after a worker finishes. */
final case class BendPublicationFacts(requestGeneration: Long, latestGeneration: Option[Long],
    sourceCurrent: Boolean, graphCurrent: Boolean, toolchainCurrent: Boolean,
    externalInputsCurrent: Boolean, canceled: Boolean, disposed: Boolean)

object BendPublicationPolicy:
  def mayPublish(facts: BendPublicationFacts): Boolean =
    !facts.disposed && !facts.canceled && facts.sourceCurrent && facts.graphCurrent &&
      facts.toolchainCurrent && facts.externalInputsCurrent &&
      facts.latestGeneration.contains(facts.requestGeneration)
