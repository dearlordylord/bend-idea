package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.toolchain.api.BendLintInstallationResult.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream
import scala.jdk.CollectionConverters.*

final class BendLintInstallationTest:
  @Test def archivesRejectTraversalLinksAndChecksumMismatch(): Unit =
    temporary { home =>
      List("../outside", "/absolute", "C:/drive", "a\\b").foreach { name =>
        val _ = assertThrows(
          classOf[IllegalArgumentException],
          () => {
            val _ = BendLintInstallation.safePath(home, name)
          }
        )
      }
      val _ = assertThrows(
        classOf[IllegalArgumentException],
        () =>
          BendLintInstallation.unpackTar(
            archive("package/../../outside", '0', "bad"),
            home,
            () => false
          )
      )
      val _ = assertThrows(
        classOf[IllegalArgumentException],
        () =>
          BendLintInstallation.unpackTar(
            archive("package/link", '2', ""),
            home,
            () => false
          )
      )
      assertFalse(Files.exists(home.getParent.resolve("outside")))
      val _ = assertThrows(
        classOf[IllegalArgumentException],
        () =>
          BendLintInstallation.verify(
            "tampered".getBytes(UTF_8),
            "SHA-256",
            "0" * 64
          )
      )
    }

  @Test def cancellationPreservesSelectedToolsAndRollbackRejectsMissingPrevious()
      : Unit =
    temporary { home =>
      val selected = "active=lint-kept\nprevious=lint-old\n"
      val _ = Files.writeString(home.resolve("selection.properties"), selected)
      val installation = new BendLintInstallation(home)
      assertEquals(Canceled, installation.install("", () => true))
      assertEquals(
        selected,
        Files.readString(home.resolve("selection.properties"))
      )
      assertTrue(installation.rollback().isInstanceOf[Unavailable])
      assertEquals(
        selected,
        Files.readString(home.resolve("selection.properties"))
      )
      val files = Files.list(home)
      try
        assertEquals(
          Set("selection.properties", "install.lock"),
          files.iterator().asScala.map(_.getFileName.toString).toSet
        )
      finally files.close()
    }

  @Test def extractingPinnedFilePreservesItsContents(): Unit =
    temporary { home =>
      BendLintInstallation.unpackTar(
        archive("package/src/entry.ts", '0', "export {};\n"),
        home,
        () => false
      )
      assertEquals(
        "export {};\n",
        Files.readString(home.resolve("src/entry.ts"))
      )
      val _ = assertThrows(
        classOf[java.nio.file.FileAlreadyExistsException],
        () =>
          BendLintInstallation.unpackTar(
            archive("package/src/entry.ts", '0', "changed"),
            home,
            () => false
          )
      )
    }

  @Test def realPinnedInstallationPreparesToolsAndSurvivesCanceledReplacement()
      : Unit =
    val bun = Option(System.getenv("BEND_TEST_INSTALL_BUN"))
    org.junit.Assume.assumeTrue(
      "Set BEND_TEST_INSTALL_BUN for the explicit network installation test.",
      bun.nonEmpty
    )
    temporary { home =>
      val installation = new BendLintInstallation(home)
      val result = installation.install(bun.get, () => false)
      result match
        case Installed(actualBun, directory, _) =>
          assertEquals(Path.of(bun.get).toRealPath().toString, actualBun)
          assertEquals(None, BendLintInstallation.validate(Path.of(directory)))
          assertTrue(Files.isRegularFile(Path.of(directory).resolve("LICENSE")))
          val injected = Path
            .of(directory)
            .resolve("cache/bend-lint/server/programs/injected-native")
          val _ = Files.writeString(injected, "untracked native artifact")
          assertTrue(
            BendLintInstallation
              .validate(Path.of(directory))
              .exists(_.contains("inventory changed"))
          )
          val _ = Files.delete(injected)
          assertEquals(None, BendLintInstallation.validate(Path.of(directory)))
          assertEquals(Canceled, installation.install(bun.get, () => true))
          assertEquals(result, installation.current())
          val replacement = installation.install(bun.get, () => false)
          assertTrue(replacement.toString, replacement.isInstanceOf[Installed])
          assertEquals(result, installation.rollback())
          replacement match
            case Installed(_, newer, _) =>
              val _ = Files.writeString(
                Path.of(newer).resolve("src/linter/seam.ts"),
                "changed"
              )
            case _ => ()
          assertTrue(installation.rollback().isInstanceOf[Unavailable])
          assertEquals(result, installation.current())
        case other => fail(other.toString)
    }

  private def archive(name: String, kind: Char, body: String): Array[Byte] =
    val header = new Array[Byte](512)
    val file = name.getBytes(UTF_8)
    System.arraycopy(file, 0, header, 0, file.length)
    val bytes = body.getBytes(UTF_8)
    val size = f"${bytes.length}%011o".getBytes(UTF_8)
    System.arraycopy(size, 0, header, 124, size.length)
    header(156) = kind.toByte
    val output = new ByteArrayOutputStream()
    val gzip = new GZIPOutputStream(output)
    gzip.write(header)
    gzip.write(bytes)
    gzip.write(new Array[Byte]((512 - bytes.length % 512) % 512))
    gzip.write(new Array[Byte](1024))
    gzip.close()
    output.toByteArray

  private def temporary(body: Path => Unit): Unit =
    val home = Files.createTempDirectory("bend-lint-install-")
    try body(home)
    finally
      val stream = Files.walk(home)
      try
        stream
          .iterator()
          .asScala
          .toList
          .reverse
          .foreach(path => { val _ = Files.deleteIfExists(path) })
      finally stream.close()
