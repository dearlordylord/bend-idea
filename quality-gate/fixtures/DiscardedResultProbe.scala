package qualitygate

object DiscardedResultProbe:
  def discardInt(): Unit =
    List(1, 2, 3).size
