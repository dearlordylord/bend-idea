package com.dearlordylord.bend.idea.adapters.structured

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

final class BendStructuredCheckTest:
  private val convention = "compiler-source-namespace-sha256-utf16-v1"
  private val source = "# 😀\r\ndef broken() -> Type:\r\n  missing\r\n"
  private val start = source.indexOf("missing")
  private def mapping(namespace: String): BendSourceMapping =
    BendSourceMapping(
      new FileId(s"/$namespace.bend", false),
      1L,
      s"/$namespace.bend",
      s"/copy/$namespace.bend",
      namespace,
      source,
      source,
      Set.empty,
      Nil,
      source,
      Nil
    )

  private def response(
      namespace: Option[String],
      digest: String,
      locations: Option[String],
      rootSource: Option[Boolean] = None
  ): String =
    val span = new JsonObject
    span.addProperty("source", source)
    namespace.foreach(span.addProperty("namespace", _))
    rootSource.foreach(span.addProperty("rootSource", _))
    span.addProperty("sha256", digest)
    span.addProperty("start", start)
    span.addProperty("end", start + 7)
    val item = new JsonObject
    item.addProperty("message", "Missing reference")
    item.add("span", span)
    val entries = new com.google.gson.JsonArray
    entries.add(item)
    val value = new JsonObject
    value.addProperty("checkProtocol", 1)
    locations.foreach(value.addProperty("locations", _))
    value.addProperty("outcome", "failed")
    value.addProperty("completeness", "unknown")
    value.addProperty("reliance", "unknown")
    value.addProperty("details", "Missing reference")
    value.add("diagnostics", entries)
    value.toString

  private val digest = MessageDigest
    .getInstance("SHA-256")
    .digest(source.getBytes(StandardCharsets.UTF_8))
    .map(byte => f"${byte & 0xff}%02x")
    .mkString

  @Test def namespaceAndDigestDisambiguateIdenticalUnicodeCrLfSources(): Unit =
    val b = mapping("b")
    val result = BendStructuredCheck
      .decode(
        response(Some("b"), digest, Some(convention)),
        List(mapping("a"), b),
        Some(convention)
      )
      .get
    assertEquals(
      BendLocation.SourceRange(b.source, BendTextRange(start, start + 7)),
      result.diagnostics.head.location
    )

  @Test def missingOrIncorrectIdentityNeverGuesses(): Unit =
    List((Some("b"), "0" * 64), (Some("absent"), digest), (None, digest))
      .foreach { case (namespace, sha) =>
        val result = BendStructuredCheck
          .decode(
            response(namespace, sha, Some(convention)),
            List(mapping("a"), mapping("b")),
            Some(convention)
          )
          .get
        assertEquals(BendLocation.RootOnly, result.diagnostics.head.location)
      }
    assertTrue(
      BendStructuredCheck
        .decode(
          response(Some("b"), digest, None),
          List(mapping("b")),
          Some(convention)
        )
        .isEmpty
    )

  @Test def legacyTextCoordinatesRemainConservative(): Unit =
    val output = response(None, digest, None)
    val a = mapping("a")
    assertEquals(
      BendLocation.SourceRange(a.source, BendTextRange(start, start + 7)),
      BendStructuredCheck.decode(output, List(a)).get.diagnostics.head.location
    )
    assertEquals(
      BendLocation.RootOnly,
      BendStructuredCheck
        .decode(output, List(a, mapping("b")))
        .get
        .diagnostics
        .head
        .location
    )

  @Test def pinnedCompilerIdentifiesOneOfTwoIdenticalImportedFiles(): Unit =
    val compiler =
      com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture.inputs
    val directory =
      java.nio.file.Files.createTempDirectory("bend-span-identity-")
    try
      val executable = directory.resolve("bend")
      val _ =
        compiler.writeStructuredLauncher(executable, structuredChecks = true)
      val root = directory.resolve("main.bend")
      val _ = java.nio.file.Files.writeString(
        root,
        "import ./a.bend as A\nimport ./b.bend as B\ndef main() -> Type:\n  Type\n"
      )
      List("a", "b").foreach { name =>
        val _ = java.nio.file.Files
          .writeString(directory.resolve(name + ".bend"), source)
      }
      val negotiation = BendStructuredCheck.negotiate(
        executable.toString,
        directory,
        Map.empty,
        () => false
      )
      assertTrue(
        negotiation.toString,
        negotiation.isInstanceOf[BendStructuredCheck.Negotiation.Supported]
      )
      val result = BendStructuredCheck.check(
        executable.toString,
        directory,
        root,
        Map.empty,
        List("a", "b").map(name =>
          mapping(name).copy(
            copiedPath = directory.resolve(name + ".bend").toString
          )
        ),
        () => false,
        Some(convention)
      )
      assertEquals(result.details, BendCheckOutcome.Failed, result.outcome)
      assertEquals(
        result.details,
        BendLocation
          .SourceRange(mapping("a").source, BendTextRange(start, start + 7)),
        result.diagnostics.head.location
      )
    finally
      val paths = java.nio.file.Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = java.nio.file.Files.deleteIfExists(path)
          })
      finally paths.close()

  @Test def identicalRootAndBaseTextRequiresExplicitRootProvenance(): Unit =
    val root = mapping("")
    List(None, Some(false)).foreach { marker =>
      val result = BendStructuredCheck
        .decode(
          response(Some(""), digest, Some(convention), marker),
          List(root),
          Some(convention)
        )
        .get
      assertEquals(BendLocation.RootOnly, result.diagnostics.head.location)
    }
    val result = BendStructuredCheck
      .decode(
        response(Some(""), digest, Some(convention), Some(true)),
        List(root),
        Some(convention)
      )
      .get
    assertEquals(
      BendLocation.SourceRange(root.source, BendTextRange(start, start + 7)),
      result.diagnostics.head.location
    )
