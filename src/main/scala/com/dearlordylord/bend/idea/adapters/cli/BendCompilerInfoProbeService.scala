package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.adapters.process.{BendBoundedProcess}
import com.dearlordylord.bend.idea.toolchain.api.{
  BendCompilerInfoProbe,
  BendCompilerInfoResult
}
import java.nio.file.{Files, Path}

final class BendCompilerInfoProbeService extends BendCompilerInfoProbe:
  override def inspect(
      executable: String,
      canceled: () => Boolean
  ): BendCompilerInfoResult =
    if canceled() then return BendCompilerInfoResult.Canceled
    val path = try Path.of(executable)
    catch
      case _: Exception =>
        return BendCompilerInfoResult.Unavailable(
          "Invalid Bend executable path."
        )
    if !Files.isRegularFile(path) || !Files.isExecutable(path) then
      return BendCompilerInfoResult.Unavailable(
        "Bend compiler is missing or not executable."
      )
    val parent = Option(path.getParent).getOrElse(path.toAbsolutePath.getParent)
    val process = BendBoundedProcess.run(
      List(executable, "--help"),
      parent,
      Map("BEND_NO_TELEMETRY" -> "1", "NO_COLOR" -> "1"),
      canceled = canceled
    )
    BendCliVerdictDecoder.compilerInfo(process)
