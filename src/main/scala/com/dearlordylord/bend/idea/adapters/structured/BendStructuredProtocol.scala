package com.dearlordylord.bend.idea.adapters.structured

import com.dearlordylord.bend.idea.adapters.process.{
  BendBoundedProcess,
  BendProcessOutcome
}
import com.google.gson.{JsonObject, JsonParser}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Negotiates independent operations by protocol, not compiler release. */
private[structured] object BendStructuredProtocol:
  /** The current CLI adds a verdict banner around the same compiler error
    * returned by semantic operations and the structured check transport.
    */
  def matchesDiagnostic(message: Option[String], checked: String): Boolean =
    message.exists(value =>
      value.stripPrefix("SOME PROOFS FAIL\n") ==
        checked.stripPrefix("SOME PROOFS FAIL\n")
    )

  def string(value: JsonObject, name: String): Option[String] =
    Option(value.get(name))
      .filter(node => node.isJsonPrimitive && node.getAsJsonPrimitive.isString)
      .flatMap(node => Try(node.getAsString).toOption)

  def integer(value: JsonObject, name: String): Option[Int] =
    Option(value.get(name))
      .filter(_.isJsonPrimitive)
      .flatMap(node => Try(node.getAsInt).toOption)

  def objectField(value: JsonObject, name: String): Option[JsonObject] =
    Try(value.getAsJsonObject(name)).toOption.flatMap(Option(_))

  private def json(outcome: BendProcessOutcome): Option[JsonObject] =
    outcome match
      case BendProcessOutcome.Exited(0, output) if output.length <= 262144 =>
        Try(JsonParser.parseString(output.trim).getAsJsonObject).toOption
      case _ => None

  private def valid(value: JsonObject): Boolean =
    Option(value.get("protocol")).exists(node =>
      node.isJsonPrimitive && node.getAsJsonPrimitive.isNumber &&
        node.toString == "1"
    )

  def request(
      executable: String,
      directory: Path,
      environment: Map[String, String],
      operation: String,
      root: Option[Path],
      timeoutMillis: Long,
      canceled: () => Boolean,
      extraArguments: List[String] = Nil
  ): Option[JsonObject] =
    val arguments = root.toList.map(_.toString)
    json(
      BendBoundedProcess.run(
        List(executable, operation) ++ arguments ++ extraArguments,
        directory,
        environment,
        timeoutMillis = timeoutMillis,
        maxOutputBytes = 262144,
        canceled = canceled
      )
    ).filter(valid)

  def supports(
      executable: String,
      directory: Path,
      environment: Map[String, String],
      operation: String,
      canceled: () => Boolean
  ): Boolean =
    request(
      executable,
      directory,
      environment,
      "--idea-structured-capabilities",
      None,
      3000L,
      canceled
    ).exists(value =>
      string(value, "compiler").exists(_.trim.nonEmpty) &&
        Try(value.getAsJsonArray("operations")).toOption
          .flatMap(Option(_))
          .exists(array =>
            array
              .iterator()
              .asScala
              .exists(item =>
                item.isJsonPrimitive && item.getAsJsonPrimitive.isString &&
                  item.getAsString == operation
              )
          )
    )
