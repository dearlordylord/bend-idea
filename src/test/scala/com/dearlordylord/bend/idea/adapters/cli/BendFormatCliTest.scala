package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.features.formatting.BendWrappingFixtures
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

final class BendFormatCliTest:
  private val jar = Path.of(System.getProperty("bend.format.tool.jar"))
  private val javaExecutable =
    Path.of(System.getProperty("java.home"), "bin", "java").toString

  private def run(directory: Path, command: String*): (Int, String) =
    val process = new ProcessBuilder(command*)
      .directory(directory.toFile)
      .redirectErrorStream(true)
      .start()
    val finished = process.waitFor(20, TimeUnit.SECONDS)
    if !finished then
      process.destroyForcibly()
      fail(s"Timed out: ${command.mkString(" ")}")
    (
      process.exitValue(),
      new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    )

  private def tool(directory: Path, args: String*): (Int, String) =
    run(directory, (Seq(javaExecutable, "-jar", jar.toString) ++ args)*)

  private def temporary(test: Path => Unit): Unit =
    val directory = Files.createTempDirectory("bend-format-cli-")
    try test(directory)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      finally paths.close()

  @Test def fileErrorsExplainHowInputsAreUnavailable(): Unit = temporary {
    dir =>
      val missing = tool(dir, "check", "missing.bend")
      assertEquals(2, missing._1)
      assertTrue(missing._2.contains("missing.bend: file not found"))
      val file = dir.resolve("invalid.bend")
      val bytes = Array[Byte](0xff.toByte)
      val _ = Files.write(file, bytes)
      val invalid = tool(dir, "fix", file.toString)
      assertEquals(2, invalid._1)
      assertTrue(invalid._2.contains("expected valid UTF-8 source"))
      assertArrayEquals(bytes, Files.readAllBytes(file))
  }

  @Test def dashPrefixedAndSpacedPathsAreExplicitFiles(): Unit = temporary {
    dir =>
      val file = dir.resolve("-my file.bend")
      val source = "def main(x: U32,y: U32):\n  x\n"
      val _ = Files.writeString(file, source)
      assertEquals(2, tool(dir, "fix", "-my file.bend")._1)
      assertEquals(source, Files.readString(file))
      assertEquals(1, tool(dir, "check", "--", "-my file.bend")._1)
      assertEquals(0, tool(dir, "fix", "--", "-my file.bend")._1)
      assertEquals(0, tool(dir, "check", "--", "-my file.bend")._1)
      val _ = Files.writeString(dir.resolve("plain.bend"), "def main():\n  1\n")
      assertEquals(
        0,
        tool(dir, "check", "plain.bend", "--", "-my file.bend")._1
      )
  }

  @Test def checkFixAndNestedEditorConfigAreRepeatable(): Unit = temporary {
    dir =>
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*.bend]\nindent_style = space\nindent_size = 4\n"
      )
      val nested = Files.createDirectory(dir.resolve("nested"))
      Files.writeString(
        nested.resolve(".editorconfig"),
        "[*.bend]\nindent_style = tab\nindent_size = 4\ntab_width = 4\n"
      )
      val file = nested.resolve("main.bend")
      val source = "def main(x: U32,y: U32) -> U32:\n  U32.add(x,y)\n"
      Files.writeString(file, source)
      val check = tool(dir, "check", file.toString)
      assertEquals(1, check._1)
      assertTrue(check._2.contains("would-change:"))
      assertEquals(source, Files.readString(file))
      val fix = tool(dir, "fix", file.toString)
      assertEquals(0, fix._1)
      assertEquals(
        "def main(x: U32, y: U32) -> U32:\n\tU32.add(x, y)\n",
        Files.readString(file)
      )
      assertEquals(0, tool(dir, "check", file.toString)._1)
      assertEquals(0, tool(dir, "fix", file.toString)._1)
  }

  @Test def unixLiteralBackslashFilenameRetainsItsEditorConfigIdentity(): Unit =
    org.junit.Assume.assumeTrue(java.io.File.separatorChar == '/')
    temporary { dir =>
      val file = dir.resolve("literal\\backslash.bend")
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[literal\\\\backslash.bend]\nindent_size = 4\nbend_max_line_length = 20\n"
      )
      val source = "def main():\n  combine_three_arguments(1,2,3)\n"
      Files.writeString(file, source)
      assertEquals(1, tool(dir, "check", file.toString)._1)
      assertEquals(source, Files.readString(file))
      assertEquals(0, tool(dir, "fix", file.toString)._1)
      assertEquals(
        "def main():\n    combine_three_arguments(\n        1,\n        2,\n        3\n    )\n",
        Files.readString(file)
      )
      assertEquals(0, tool(dir, "check", file.toString)._1)
    }

  @Test def unsafeAndMissingInputsRemainUnavailable(): Unit = temporary { dir =>
    val bad = dir.resolve("bad.bend")
    val source = "def f(x: U32,y: U32\n  x\n"
    Files.writeString(bad, source)
    assertEquals(2, tool(dir, "check", bad.toString)._1)
    assertEquals(2, tool(dir, "fix", bad.toString)._1)
    assertEquals(source, Files.readString(bad))
    assertEquals(2, tool(dir, "check", dir.resolve("missing.bend").toString)._1)
  }

  @Test def defaultStyleAndUnsetUseTwoSpaces(): Unit = temporary { dir =>
    val file = dir.resolve("main.bend")
    Files.writeString(file, "def main():\n    1\n")
    assertEquals(1, tool(dir, "check", file.toString)._1)
    assertEquals(0, tool(dir, "fix", file.toString)._1)
    assertEquals("def main():\n  1\n", Files.readString(file))
    assertEquals(0, tool(dir, "check", file.toString)._1)
    Files.writeString(
      dir.resolve(".editorconfig"),
      "root = true\n[*.bend]\nindent_size = 4\n"
    )
    val nested = Files.createDirectory(dir.resolve("nested"))
    Files.writeString(
      nested.resolve(".editorconfig"),
      "[*.bend]\nindent_size = unset\n"
    )
    val child = nested.resolve("child.bend")
    Files.writeString(child, "def child():\n    1\n")
    assertEquals(1, tool(dir, "check", child.toString)._1)
    assertEquals(0, tool(dir, "fix", child.toString)._1)
    assertEquals("def child():\n  1\n", Files.readString(child))
  }

  @Test def stagedHookChecksIndexContentWithoutChangingIndexOrDisk(): Unit =
    temporary { dir =>
      assertEquals(0, run(dir, "git", "init", "-q")._1)
      val file = dir.resolve("main.bend")
      Files.writeString(file, "def main(x: U32,y: U32) -> U32:\n  x\n")
      assertEquals(0, run(dir, "git", "add", "main.bend")._1)
      val staged = run(dir, "git", "show", ":main.bend")._2
      val working = "def main(x: U32, y: U32) -> U32:\n  x\n"
      Files.writeString(file, working)
      val toolDirectory = Files.createDirectories(dir.resolve("build/libs"))
      Files.copy(jar, toolDirectory.resolve(jar.getFileName))
      val hook = Path.of("contrib/hooks/pre-commit").toAbsolutePath.toString
      val result = run(dir, "bash", hook)
      assertEquals(result._2, 1, result._1)
      assertTrue(result._2.contains("would-change:"))
      assertEquals(staged, run(dir, "git", "show", ":main.bend")._2)
      assertEquals(working, Files.readString(file))
      val all = Path.of("ci/bend-format-all-tracked.sh").toAbsolutePath.toString
      assertEquals(0, run(dir, "bash", all)._1)
      Files.writeString(
        dir.resolve("untracked.bend"),
        "def u(a: U32,b: U32):\n  a\n"
      )
      assertEquals(0, run(dir, "bash", all)._1)
      val nested = Files.createDirectory(dir.resolve("nested"))
      Files.writeString(
        nested.resolve("tracked.bend"),
        "def t(a: U32,b: U32):\n  a\n"
      )
      assertEquals(0, run(dir, "git", "add", "nested/tracked.bend")._1)
      val allTracked = run(dir, "bash", all)
      assertEquals(1, allTracked._1)
      assertTrue(allTracked._2.contains("nested/tracked.bend"))
      assertFalse(allTracked._2.contains("untracked.bend"))
      val fromNested = run(nested, "bash", all)
      assertEquals(1, fromNested._1)
      assertTrue(fromNested._2.contains("nested/tracked.bend"))
      assertFalse(fromNested._2.contains("untracked.bend"))
      val ambiguous = toolDirectory.resolve("bend-format-tool-other.jar")
      val _ = Files.copy(jar, ambiguous)
      assertEquals(2, run(dir, "bash", hook)._1)
      assertEquals(2, run(dir, "bash", all)._1)
      val _ = Files.delete(ambiguous)
      val _ = Files.delete(toolDirectory.resolve(jar.getFileName))
      assertEquals(2, run(dir, "bash", hook)._1)
    }

  @Test def approvedLayoutsAreIdenticalInTheActualStandaloneJar(): Unit =
    temporary { dir =>
      for (before, after) <- BendWrappingFixtures.exactCases
      do
        val file = dir.resolve("example.bend")
        Files.writeString(file, before)
        assertEquals(1, tool(dir, "check", file.toString)._1)
        assertEquals(before, Files.readString(file))
        assertEquals(0, tool(dir, "fix", file.toString)._1)
        assertEquals(after, Files.readString(file))
        assertEquals(0, tool(dir, "check", file.toString)._1)
        assertEquals(0, tool(dir, "fix", file.toString)._1)
        assertEquals(after, Files.readString(file))
    }

  @Test def batchFixKeepsUnavailableFileAndFormatsSafeFile(): Unit = temporary {
    dir =>
      val safe = dir.resolve("safe.bend")
      val unsafe = dir.resolve("unsafe.bend")
      val bad = "def unfinished(x,y\n"
      Files.writeString(safe, BendWrappingFixtures.actorBefore)
      Files.writeString(unsafe, bad)
      assertEquals(2, tool(dir, "fix", safe.toString, unsafe.toString)._1)
      assertEquals(BendWrappingFixtures.actorAfter, Files.readString(safe))
      assertEquals(bad, Files.readString(unsafe))
  }

  @Test def widthOnlyEditorConfigNestedOverridesAndThresholds(): Unit =
    temporary { dir =>
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*.bend]\nbend_max_line_length = 28\n"
      )
      val parent = dir.resolve("parent.bend")
      Files.writeString(parent, BendWrappingFixtures.callBefore)
      assertEquals(0, tool(dir, "fix", parent.toString)._1)
      assertEquals(BendWrappingFixtures.callWrapped, Files.readString(parent))
      for (width, expected) <- BendWrappingFixtures.widthCases do
        val child = Files.createDirectory(dir.resolve("width" + width))
        Files.writeString(
          child.resolve(".editorconfig"),
          s"[*.bend]\nbend_max_line_length = $width\n"
        )
        val file = child.resolve("child.bend")
        Files.writeString(file, BendWrappingFixtures.callBefore)
        assertEquals(0, tool(dir, "fix", file.toString)._1)
        assertEquals(expected, Files.readString(file))
        assertEquals(0, tool(dir, "check", file.toString)._1)
      val invalid = Files.createDirectory(dir.resolve("invalid"))
      Files.writeString(
        invalid.resolve(".editorconfig"),
        "[*.bend]\nbend_max_line_length = invalid\n"
      )
      val file = invalid.resolve("child.bend")
      Files.writeString(file, BendWrappingFixtures.callBefore)
      assertEquals(2, tool(dir, "fix", file.toString)._1)
      assertEquals(BendWrappingFixtures.callBefore, Files.readString(file))
    }

  @Test def wrappingPreservesCrLfAndNoFinalNewlineBytes(): Unit = temporary {
    dir =>
      for
        (before, after) <- BendWrappingFixtures.exactCases
        suffix <- List("\n", "")
      do
        val source = before.stripSuffix("\n") + suffix
        val expected = after.stripSuffix("\n") + suffix
        val file = dir.resolve("crlf.bend")
        Files.writeString(file, source.replace("\n", "\r\n"))
        assertEquals(0, tool(dir, "fix", file.toString)._1)
        assertEquals(expected.replace("\n", "\r\n"), Files.readString(file))
        assertEquals(0, tool(dir, "check", file.toString)._1)
  }

  @Test def tabWidthAndNoFinalNewlineMatchEditorLayout(): Unit = temporary {
    dir =>
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*.bend]\nindent_style = tab\nindent_size = 4\ntab_width = 4\nbend_max_line_length = 28\n"
      )
      val file = dir.resolve("tabs.bend")
      Files.writeString(file, BendWrappingFixtures.tabBefore)
      assertEquals(0, tool(dir, "fix", file.toString)._1)
      assertEquals(BendWrappingFixtures.tabAfter, Files.readString(file))
      assertEquals(0, tool(dir, "check", file.toString)._1)
  }

  @Test def unsafeWrappingRequestsNeverWriteAnyBytes(): Unit = temporary {
    dir =>
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*.bend]\nbend_max_line_length = 20\n"
      )
      for source <- BendWrappingFixtures.unsafeCases do
        val file = dir.resolve("unsafe.bend")
        Files.writeString(file, source)
        assertEquals(2, tool(dir, "check", file.toString)._1)
        assertEquals(source, Files.readString(file))
        assertEquals(2, tool(dir, "fix", file.toString)._1)
        assertEquals(source, Files.readString(file))
  }

  @Test def existingMultilineSignaturesAndNestedSiblingGrouping(): Unit =
    temporary { dir =>
      val file = dir.resolve("groups.bend")
      Files.writeString(file, BendWrappingFixtures.multilineSignatureBefore)
      assertEquals(0, tool(dir, "check", file.toString)._1)
      assertEquals(
        BendWrappingFixtures.multilineSignatureBefore,
        Files.readString(file)
      )
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*.bend]\nbend_max_line_length = 20\n"
      )
      assertEquals(0, tool(dir, "fix", file.toString)._1)
      assertEquals(
        BendWrappingFixtures.multilineSignatureAfter,
        Files.readString(file)
      )
      assertEquals(0, tool(dir, "check", file.toString)._1)
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*.bend]\nbend_max_line_length = 30\n"
      )
      Files.writeString(file, BendWrappingFixtures.siblingsBefore)
      assertEquals(0, tool(dir, "fix", file.toString)._1)
      assertEquals(BendWrappingFixtures.siblingsAfter, Files.readString(file))
      assertEquals(0, tool(dir, "check", file.toString)._1)
    }

  @Test def groupedCallsAndUnrelatedSiblingListsUseBoundedWidth(): Unit =
    temporary { dir =>
      for ((width, source, expected), index) <-
          BendWrappingFixtures.boundedCases.zipWithIndex
      do
        Files.writeString(
          dir.resolve(".editorconfig"),
          s"root = true\n[*.bend]\nbend_max_line_length = $width\n"
        )
        val file = dir.resolve(s"bounded-$index.bend")
        Files.writeString(file, source)
        assertEquals(0, tool(dir, "fix", file.toString)._1)
        assertEquals(expected, Files.readString(file))
        assertEquals(0, tool(dir, "check", file.toString)._1)
    }

  @Test def tabWidthOnlyAndTabRemainderIndentationMatchEditor(): Unit =
    temporary { root =>
      for ((properties, indent, continuation), index) <-
          BendWrappingFixtures.editorConfigIndentCases.zipWithIndex
      do
        val dir = Files.createDirectory(root.resolve("case" + index))
        Files.writeString(
          dir.resolve(".editorconfig"),
          "root = true\n[*.bend]\n" + properties + "bend_max_line_length = 20\n"
        )
        val file = dir.resolve("main.bend")
        Files.writeString(file, "def f():\n  1\n")
        assertEquals(0, tool(dir, "fix", file.toString)._1)
        assertEquals("def f():\n" + indent + "1\n", Files.readString(file))
        assertEquals(0, tool(dir, "check", file.toString)._1)
        Files.writeString(
          file,
          "def main():\n" + indent + "combine(alpha,beta,gamma)\n"
        )
        val expected =
          "def main():\n" + indent + "combine(\n" + continuation + "alpha,\n" + continuation + "beta,\n" + continuation + "gamma\n" + indent + ")\n"
        assertEquals(1, tool(dir, "check", file.toString)._1)
        assertEquals(0, tool(dir, "fix", file.toString)._1)
        assertEquals(expected, Files.readString(file))
        assertEquals(0, tool(dir, "check", file.toString)._1)
    }

  @Test def multilineLiteralBytesRemainExactWhileActualHeaderAndBodyChange()
      : Unit = temporary { dir =>
    Files.writeString(
      dir.resolve(".editorconfig"),
      "root = true\n[*.bend]\nindent_size = 4\n"
    )
    val file = dir.resolve("literal.bend")
    Files.writeString(file, BendWrappingFixtures.multilineLiteralBefore)
    assertEquals(1, tool(dir, "check", file.toString)._1)
    assertEquals(
      BendWrappingFixtures.multilineLiteralBefore,
      Files.readString(file)
    )
    assertEquals(0, tool(dir, "fix", file.toString)._1)
    assertEquals(
      BendWrappingFixtures.multilineLiteralAfterFourSpaces,
      Files.readString(file)
    )
    assertEquals(0, tool(dir, "check", file.toString)._1)
    assertEquals(0, tool(dir, "fix", file.toString)._1)
    assertEquals(
      BendWrappingFixtures.multilineLiteralAfterFourSpaces,
      Files.readString(file)
    )
  }

  @Test def uppercaseAndLowercaseComparisonsFormatIdenticallyUnderBothIndents()
      : Unit = temporary { root =>
    for (name, before, twoSpaces, fourSpaces) <-
        BendWrappingFixtures.comparisonCases
    do
      for (label, expected) <- List(
          "default" -> twoSpaces,
          "four" -> fourSpaces
        )
      do
        val dir = Files.createDirectory(root.resolve(name + label))
        Files.writeString(
          dir.resolve(".editorconfig"),
          "root = true\n[*.bend]\n" + (if label == "four" then
                                         "indent_size = 4\n"
                                       else "")
        )
        val file = dir.resolve("comparison.bend")
        Files.writeString(file, before)
        assertEquals(1, tool(dir, "check", file.toString)._1)
        assertEquals(before, Files.readString(file))
        assertEquals(0, tool(dir, "fix", file.toString)._1)
        assertEquals(expected, Files.readString(file))
        assertEquals(0, tool(dir, "check", file.toString)._1)
        assertEquals(0, tool(dir, "fix", file.toString)._1)
        assertEquals(expected, Files.readString(file))
  }

  @Test def tupleListAndContinuedResultMatchTheEditor(): Unit = temporary {
    dir =>
      val file = dir.resolve("coverage.bend")
      Files.writeString(file, BendWrappingFixtures.coverageBefore)
      assertEquals(1, tool(dir, "check", file.toString)._1)
      assertEquals(BendWrappingFixtures.coverageBefore, Files.readString(file))
      assertEquals(0, tool(dir, "fix", file.toString)._1)
      assertEquals(BendWrappingFixtures.coverageAfter, Files.readString(file))
      assertEquals(0, tool(dir, "fix", file.toString)._1)
      assertEquals(0, tool(dir, "check", file.toString)._1)
      assertEquals(BendWrappingFixtures.coverageAfter, Files.readString(file))
  }
