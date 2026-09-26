package com.dearlordylord.bend.idea.features

import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

final class BendIntentionMetadataTest:
  @Test def everyRegisteredIntentionHasPackagedDescription(): Unit =
    val descriptor =
      Option(getClass.getResourceAsStream("/META-INF/plugin.xml"))
        .getOrElse(throw new AssertionError("Plugin descriptor is missing"))
    val xml = try new String(descriptor.readAllBytes(), StandardCharsets.UTF_8)
    finally descriptor.close()
    val intentionClass =
      "(?s)<intentionAction>\\s*<className>([^<]+)</className>".r
    val registered = intentionClass
      .findAllMatchIn(xml)
      .map(_.group(1).trim)
      .toList
    assertTrue("No registered intentions found", registered.nonEmpty)
    registered.foreach { className =>
      val simpleName = className.split('.').last
      val resource = s"/intentionDescriptions/$simpleName/description.html"
      val description = Option(getClass.getResourceAsStream(resource))
        .getOrElse(
          throw new AssertionError(s"Missing $resource for $className")
        )
      try
        val content =
          new String(description.readAllBytes(), StandardCharsets.UTF_8)
        assertTrue(s"Empty description for $className", content.trim.nonEmpty)
      finally description.close()
    }
