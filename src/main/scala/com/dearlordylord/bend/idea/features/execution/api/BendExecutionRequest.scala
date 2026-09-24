package com.dearlordylord.bend.idea.features.execution.api

import com.intellij.execution.process.ProcessHandler

/** Immutable inputs for an explicit Bend process. Program arguments are already
  * tokenized; the process adapter passes them without shell joining.
  */
final case class BendExecutionRequest(
    executable: String,
    root: String,
    workingDirectory: String,
    environment: Map[String, String],
    arguments: List[String]
)

/** Platform process boundary used only by explicit Run/Build configurations.
  * Compiler checks keep their bounded, check-only backend.
  */
trait BendExecutionProcessFactory:
  def start(request: BendExecutionRequest): ProcessHandler
