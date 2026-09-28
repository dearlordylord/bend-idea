package com.dearlordylord.bend.idea.adapters.cli

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
      Files.copy(jar, toolDirectory.resolve("bend-format-tool-0.1.8.jar"))
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
      val _ = Files.delete(toolDirectory.resolve("bend-format-tool-0.1.8.jar"))
      assertEquals(2, run(dir, "bash", hook)._1)
    }
