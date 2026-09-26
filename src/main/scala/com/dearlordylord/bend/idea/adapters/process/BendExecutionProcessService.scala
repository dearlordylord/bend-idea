package com.dearlordylord.bend.idea.adapters.process

import com.dearlordylord.bend.idea.features.execution.api.{
  BendExecutionProcessFactory,
  BendExecutionRequest
}
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.{OSProcessHandler, ProcessHandler}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Streaming OS process launcher for explicit Run/Build. */
final class BendExecutionProcessService extends BendExecutionProcessFactory:
  override def start(request: BendExecutionRequest): ProcessHandler =
    val command = new GeneralCommandLine(request.executable)
    command.addParameter(request.root)
    // Bend's CLI treats leading-dash values as options until this separator.
    command.addParameter("--")
    command.addParameters(request.arguments.asJava)
    command.setWorkDirectory(request.workingDirectory)
    command.getEnvironment.putAll(request.environment.asJava)
    command.setCharset(StandardCharsets.UTF_8)
    val handler = new OSProcessHandler(command)
    handler.setShouldDestroyProcessRecursively(true)
    handler
