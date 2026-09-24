package com.dearlordylord.bend.idea.adapters.process

import com.dearlordylord.bend.idea.features.execution.api.{
  BendNativeProcessFactory,
  BendNativeRunRequest
}
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.{OSProcessHandler, ProcessHandler}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Streaming launcher for an explicitly selected generated native artifact. */
final class BendNativeProcessService extends BendNativeProcessFactory:
  override def start(request: BendNativeRunRequest): ProcessHandler =
    val command = new GeneralCommandLine(request.executable)
    request.threads.foreach { count =>
      command.addParameter("--threads")
      command.addParameter(count.toString)
    }
    request.gpu.foreach { value =>
      command.addParameter("--gpu")
      command.addParameter(value)
    }
    command.addParameter("--")
    command.addParameters(request.arguments.asJava)
    command.setWorkDirectory(request.workingDirectory)
    command.getEnvironment.putAll(request.environment.asJava)
    command.setCharset(StandardCharsets.UTF_8)
    val handler = new OSProcessHandler(command)
    handler.setShouldDestroyProcessRecursively(true)
    handler
