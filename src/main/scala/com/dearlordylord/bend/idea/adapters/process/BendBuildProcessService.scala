package com.dearlordylord.bend.idea.adapters.process

import com.dearlordylord.bend.idea.features.execution.api.{
  BendBuildProcessFactory,
  BendBuildRequest
}
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.{OSProcessHandler, ProcessHandler}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Streaming compiler process for explicit native builds and source emission.
  */
final class BendBuildProcessService extends BendBuildProcessFactory:
  override def start(request: BendBuildRequest): ProcessHandler =
    val command = new GeneralCommandLine(request.executable)
    command.addParameter(request.root)
    command.addParameter("-o")
    command.addParameter(request.output)
    command.setWorkDirectory(request.workingDirectory)
    command.getEnvironment.putAll(request.environment.asJava)
    command.setCharset(StandardCharsets.UTF_8)
    val handler = new OSProcessHandler(command)
    handler.setShouldDestroyProcessRecursively(true)
    handler
