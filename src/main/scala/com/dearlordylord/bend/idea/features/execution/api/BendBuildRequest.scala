package com.dearlordylord.bend.idea.features.execution.api

import com.intellij.execution.process.ProcessHandler

enum BendBuildOutputKind:
  case Native, CSource, JavaScriptSource

/** Compiler build/emission request. Output identity is checked against the
  * loaded Bend graph before the command reaches the process adapter.
  */
final case class BendBuildRequest(
    executable: String,
    root: String,
    output: String,
    outputKind: BendBuildOutputKind,
    workingDirectory: String,
    environment: Map[String, String]
)

trait BendBuildProcessFactory:
  def start(request: BendBuildRequest): ProcessHandler

/** Runtime options accepted by generated native executables, not by the Bend
  * compiler build command.
  */
final case class BendNativeRunRequest(
    executable: String,
    workingDirectory: String,
    environment: Map[String, String],
    threads: Option[Int],
    gpu: Option[String],
    arguments: List[String]
)

trait BendNativeProcessFactory:
  def start(request: BendNativeRunRequest): ProcessHandler
