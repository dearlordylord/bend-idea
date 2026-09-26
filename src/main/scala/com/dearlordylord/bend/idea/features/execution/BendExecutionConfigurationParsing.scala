package com.dearlordylord.bend.idea.features.execution

import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Shared parsing for run/build environment and argument fields. */
private[execution] object BendExecutionConfigurationParsing:
  def projectBase(project: Project): Path =
    Option(project.getBasePath)
      .filter(_.nonEmpty)
      .map(Path.of(_).toAbsolutePath.normalize())
      .getOrElse(
        Path.of(System.getProperty("user.dir")).toAbsolutePath.normalize()
      )

  def absolute(value: String, base: Path): Either[String, Path] =
    try
      val path = Path.of(value.trim)
      Right((if path.isAbsolute then path else base.resolve(path)).normalize())
    catch case _: Exception => Left(s"Invalid path: $value")

  def environment(value: String): Either[String, Map[String, String]] =
    val lines = value.linesIterator.filter(_.trim.nonEmpty).toList
    val entries = lines.map { line =>
      val content = line.dropWhile(_.isWhitespace)
      val split = content.indexOf('=')
      val name = if split < 0 then "" else content.take(split).trim
      if split <= 0 || !name.matches("[A-Za-z_][A-Za-z0-9_]*") then
        Left(s"Environment entries must be KEY=VALUE: $line")
      else Right(name -> content.substring(split + 1))
    }
    entries.collectFirst { case Left(error) => error } match
      case Some(error) => Left(error)
      case None => Right(entries.collect { case Right(entry) => entry }.toMap)

  def arguments(value: String): Either[String, List[String]] =
    try Right(ParametersListUtil.parse(value).asScala.toList)
    catch case _: Exception => Left("Could not parse program arguments.")
