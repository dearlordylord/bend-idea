package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.syntax.parser.BendLayoutPolicy
import org.editorconfig.core.EditorConfig
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.{CodingErrorAction, CharacterCodingException}
import java.nio.file.{Files, Path, NoSuchFileException, AccessDeniedException}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Optional offline style command. It never invokes the Bend compiler or IDE.
  */
object BendFormatCli:
  private val help = """Usage:
    |  bend-format check FILE ...
    |  bend-format check --stdin-path PATH
    |  bend-format fix FILE ...
    |  bend-format --help (or -h)
    |  bend-format --version
    |
    |check reports formatting changes without writing files.
    |fix applies safe formatting changes to files.
    |--stdin-path checks UTF-8 stdin using PATH for EditorConfig lookup.
    |Pass explicit .bend files; directories are not scanned recursively.
    |Quote paths containing spaces.
    |Use -- before filenames beginning with a dash.
    |
    |Examples:
    |  bend-format check src/main.bend src/types.bend
    |  bend-format fix "src/my file.bend"
    |  bend-format check -- -example.bend
    |
    |Exit codes: 0 = conforming/formatted; 1 = check would change;
    |            2 = invalid arguments or unavailable file.
    |Files in a batch are processed independently; fix may modify safe files
    |even when another file is unavailable. Reads local EditorConfig; runs offline.
    |""".stripMargin

  def main(args: Array[String]): Unit =
    System.exit(run(args.toList))

  def run(args: List[String]): Int =
    val wantsHelp = args match
      case List("--help" | "-h")                  => true
      case List("check" | "fix", "--help" | "-h") => true
      case _                                      => false
    if wantsHelp then
      print(help)
      return 0
    if args == List("--version") then
      Option(getClass.getPackage.getImplementationVersion)
        .filter(_.trim.nonEmpty) match
        case Some(version) =>
          println(s"bend-format $version")
          return 0
        case None =>
          System.err.println(
            "Bend format tool build version metadata is unavailable"
          )
          return 2
    def invalid(reason: String): Int =
      System.err.println(s"$reason. Run bend-format --help for usage.")
      System.err.print(help)
      2

    val (fix, stdinPath, paths) = args match
      case "check" :: "--stdin-path" :: path :: Nil if path.nonEmpty =>
        (false, Some(path), List(path))
      case (mode @ ("check" | "fix")) :: rest =>
        val separator = rest.indexOf("--")
        val (before, after) =
          if separator < 0 then (rest, Nil)
          else (rest.take(separator), rest.drop(separator + 1))
        before.find(_.startsWith("-")) match
          case Some("--stdin-path") =>
            return invalid("--stdin-path requires check --stdin-path PATH only")
          case Some(option) => return invalid(s"Unknown option: $option")
          case None         => ()
        val files = before ++ after
        if files.isEmpty || files.exists(_.isEmpty) then
          return invalid(s"$mode requires at least one .bend file")
        (mode == "fix", None, files)
      case Nil          => return invalid("Expected a command")
      case command :: _ => return invalid(s"Unknown command: $command")

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
        case _: NoSuchFileException =>
          println(s"unavailable: $spelling: file not found")
          exit = 2
        case _: AccessDeniedException =>
          println(s"unavailable: $spelling: permission denied")
          exit = 2
        case _: CharacterCodingException =>
          println(s"unavailable: $spelling: expected valid UTF-8 source")
          exit = 2
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
