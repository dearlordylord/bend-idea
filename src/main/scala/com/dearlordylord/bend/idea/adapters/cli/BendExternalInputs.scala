package com.dearlordylord.bend.idea.adapters.cli

import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes

/** Small, repeatable observation of the installed compiler and selected Base.
  */
object BendExternalInputs:
  def stamp(executable: String, basePath: Option[String]): String =
    def file(path: String, source: Boolean): String =
      try
        val normalized = Path.of(path).toRealPath()
        val attrs =
          Files.readAttributes(normalized, classOf[BasicFileAttributes])
        val content = if source then
          val digest = java.security.MessageDigest.getInstance("SHA-256")
          val input = Files.newInputStream(normalized)
          try
            val chunk = new Array[Byte](8192)
            var length = input.read(chunk)
            while length >= 0 do
              digest.update(chunk, 0, length)
              length = input.read(chunk)
            digest.digest().map(b => f"${b & 0xff}%02x").mkString
          finally input.close()
        else ""
        s"$normalized:${attrs.fileKey()}:${attrs.size()}:${attrs.lastModifiedTime()}:$content"
      catch case _: Exception => s"missing:$path"
    val observed = file(executable, false) + basePath.fold("")(path =>
      ":" + file(path, true)
    )
    com.dearlordylord.bend.idea.analysis.model.BendAnalysisKey
      .sourceDigest(observed)
