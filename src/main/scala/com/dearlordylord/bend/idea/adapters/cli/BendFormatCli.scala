package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.syntax.parser.BendLayoutPolicy
import org.editorconfig.core.EditorConfig
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Optional offline style command. It never invokes the Bend compiler or IDE.
  */
object BendFormatCli:
  def main(args: Array[String]): Unit =
    System.exit(run(args.toList))

  def run(args: List[String]): Int =
    if args == List("--version") then
      Option(getClass.getPackage.getImplementationVersion)
        .filter(_.trim.nonEmpty) match
        case Some(version) =>
          println(s"bend-format-tool $version")
          return 0
        case None =>
          System.err.println(
            "Bend format tool build version metadata is unavailable"
          )
          return 2
    val (fix, stdinPath, paths) = args match
      case "check" :: "--stdin-path" :: path :: Nil =>
        (false, Some(path), List(path))
      case "check" :: files if files.nonEmpty =>
        (false, None, files)
      case "fix" :: files if files.nonEmpty =>
        (true, None, files)
      case _ =>
        System.err.println(
          "usage: bend-format-tool check [--stdin-path PATH | FILE ...] | fix FILE ... | --version"
        )
        return 2

    var exit = 0
    val editorConfig = new EditorConfig()
    for spelling <- paths do
      try
        val path = Path.of(spelling).toAbsolutePath.normalize()
        if !path.getFileName.toString.endsWith(".bend") then
          println(s"unavailable: $spelling: expected a .bend file")
          exit = 2
        else
          val settings = resolveSettings(editorConfig, path)
          val bytes = stdinPath match
            case Some(_) => System.in.readAllBytes()
            case None    => Files.readAllBytes(path)
          val source = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString
          BendLayoutPolicy.format(source, settings) match
            case BendLayoutPolicy.Outcome.Unchanged =>
              println(s"conforming: $spelling")
            case BendLayoutPolicy.Outcome.Unavailable(reason) =>
              println(s"unavailable: $spelling: $reason")
              exit = 2
            case BendLayoutPolicy.Outcome.Formatted(formatted) =>
              if fix then
                if !java.util.Arrays.equals(bytes, Files.readAllBytes(path))
                then
                  println(
                    s"unavailable: $spelling: file changed while formatting"
                  )
                  exit = 2
                else
                  Files.write(path, formatted.getBytes(StandardCharsets.UTF_8))
                  println(s"formatted: $spelling")
              else
                println(s"would-change: $spelling")
                if exit == 0 then exit = 1
      catch
        case NonFatal(error) =>
          println(
            s"unavailable: $spelling: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          )
          exit = 2
    exit

  private def resolveSettings(
      editorConfig: EditorConfig,
      path: Path
  ): BendLayoutPolicy.Settings =
    val properties = editorConfig
      // editorconfig-core normalizes its glob and config directory, but not
      // the target path. Convert only the native separator so Unix filenames
      // containing a literal backslash retain their identity.
      .getProperties(path.toString.replace(java.io.File.separatorChar, '/'))
      .asScala
      .map(pair =>
        pair.getKey.toLowerCase(java.util.Locale.ROOT) -> pair.getVal
      )
      .toMap
    BendLayoutPolicy
      .fromEditorConfig(properties, BendLayoutPolicy.defaultSettings)
      .fold(reason => throw new IllegalArgumentException(reason), identity)
