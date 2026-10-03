package com.dearlordylord.bend.idea.adapters.cli

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.jar.{JarEntry, JarFile, JarOutputStream, Attributes}
import scala.jdk.CollectionConverters.*

final class BendFormatVersionTest:
  private val jar = Path.of(System.getProperty("bend.format.tool.jar"))
  private val version = System.getProperty("bend.format.tool.version")
  private val javaExecutable =
    Path.of(System.getProperty("java.home"), "bin", "java").toString

  private def run(tool: Path, args: String*): (Int, String, String) =
    runCommand((Seq(javaExecutable, "-jar", tool.toString) ++ args)*)

  private def runCommand(command: String*): (Int, String, String) =
    val process = new ProcessBuilder(command*).start()
    if !process.waitFor(20, TimeUnit.SECONDS) then
      val _ = process.destroyForcibly()
      fail("Formatter subprocess timed out")
    (
      process.exitValue(),
      new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8),
      new String(process.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
    )

  @Test def unpackagedClassesCannotClaimARelease(): Unit =
    val classpath = System.getProperty("bend.format.tool.classes") +
      java.io.File.pathSeparator + jar.toString
    val result = runCommand(
      javaExecutable,
      "-cp",
      classpath,
      "com.dearlordylord.bend.idea.adapters.cli.BendFormatCli",
      "--version"
    )
    assertEquals(2, result._1)
    assertEquals("", result._2)
    assertTrue(result._3.contains("build version metadata is unavailable"))

  @Test def packagedVersionMatchesBuildAndSurvivesRenaming(): Unit =
    assertEquals(s"bend-format-tool-$version.jar", jar.getFileName.toString)
    val archive = new JarFile(jar.toFile)
    try
      assertEquals(
        version,
        archive.getManifest.getMainAttributes.getValue("Implementation-Version")
      )
    finally archive.close()
    assertEquals((0, s"bend-format-tool $version\n", ""), run(jar, "--version"))
    val renamed = Files.createTempFile("renamed-formatter-", ".jar")
    try
      val _ = Files.copy(
        jar,
        renamed,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
      assertEquals(
        (0, s"bend-format-tool $version\n", ""),
        run(renamed, "--version")
      )
    finally
      val _ = Files.deleteIfExists(renamed)

  @Test def missingAndBlankMetadataFailExplicitlyWithoutDisablingFormatting()
      : Unit =
    for replacement <- List(None, Some("   ")) do
      val rewritten = Files.createTempFile("formatter-no-version-", ".jar")
      val source = Files.createTempFile("formatter-source-", ".bend")
      try
        val archive = new JarFile(jar.toFile)
        try
          val manifest = archive.getManifest
          val _ = manifest.getMainAttributes.remove(
            Attributes.Name.IMPLEMENTATION_VERSION
          )
          replacement.foreach(value => {
            val _ = manifest.getMainAttributes
              .putValue("Implementation-Version", value)
          })
          val output =
            new JarOutputStream(Files.newOutputStream(rewritten), manifest)
          try
            for
              entry <- archive.entries().asScala
              if entry.getName != "META-INF/MANIFEST.MF"
            do
              output.putNextEntry(new JarEntry(entry.getName))
              val input = archive.getInputStream(entry)
              try
                val _ = input.transferTo(output)
              finally input.close()
              output.closeEntry()
          finally output.close()
        finally archive.close()
        val result = run(rewritten, "--version")
        assertEquals(2, result._1)
        assertEquals("", result._2)
        assertTrue(result._3.contains("build version metadata is unavailable"))
        val _ = Files.writeString(source, "def main():\n  1\n")
        assertEquals(0, run(rewritten, "check", source.toString)._1)
        assertEquals(0, run(rewritten, "fix", source.toString)._1)
      finally
        val _ = Files.deleteIfExists(rewritten)
        val _ = Files.deleteIfExists(source)
