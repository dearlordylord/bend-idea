package com.dearlordylord.bend.idea.adapters.process

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

enum BendProcessOutcome:
  case Exited(code: Int, output: String)
  case TimedOut(output: String)
  case OutputLimit(output: String)
  case StartFailed(message: String)
  case Canceled(output: String)

/** One bounded process operation; termination includes descendants and both
  * streams.
  */
object BendBoundedProcess:
  def run(
      command: List[String],
      directory: Path,
      environment: Map[String, String],
      timeoutMillis: Long = 15000L,
      maxOutputBytes: Int = 2 * 1024 * 1024,
      canceled: () => Boolean = () => false
  ): BendProcessOutcome =
    var process: Process = null
    val executor = Executors.newFixedThreadPool(
      2,
      (r: Runnable) => {
        val t = new Thread(r, "bend-check-output")
        t.setDaemon(true)
        t
      }
    )
    val bytes = new ByteArrayOutputStream()
    val tooLarge = new AtomicBoolean(false)
    def output: String = bytes.synchronized {
      bytes.toString(StandardCharsets.UTF_8)
    }
    def terminate(): Unit =
      if process != null then
        process.descendants().iterator().asScala.foreach(_.destroyForcibly())
        process.destroyForcibly()
        try process.waitFor(2, TimeUnit.SECONDS)
        catch case _: InterruptedException => ()
    try
      if canceled() then return BendProcessOutcome.Canceled("")
      val builder =
        new ProcessBuilder(command.asJava).directory(directory.toFile)
      builder.environment().putAll(environment.asJava)
      process = builder.start()
      val started = System.nanoTime()
      def drain(stream: java.io.InputStream): Unit =
        val buffer = new Array[Byte](8192)
        try
          var size = stream.read(buffer)
          while size >= 0 && !tooLarge.get() do
            bytes.synchronized {
              val room = maxOutputBytes - bytes.size()
              if size > room then
                if room > 0 then bytes.write(buffer, 0, room)
                tooLarge.set(true)
              else bytes.write(buffer, 0, size)
            }
            if !tooLarge.get() then size = stream.read(buffer)
        catch case _: java.io.IOException => ()
      val stdout = executor.submit(new Runnable {
        override def run(): Unit = drain(process.getInputStream)
      })
      val stderr = executor.submit(new Runnable {
        override def run(): Unit = drain(process.getErrorStream)
      })
      var done = false
      while !done && !tooLarge.get() && !canceled() &&
        TimeUnit.NANOSECONDS.toMillis(
          System.nanoTime() - started
        ) < timeoutMillis
      do
        if Thread.currentThread().isInterrupted then
          throw new InterruptedException()
        done = process.waitFor(50, TimeUnit.MILLISECONDS)
      if !done || tooLarge.get() then terminate()
      stdout.get(2, TimeUnit.SECONDS)
      stderr.get(2, TimeUnit.SECONDS)
      if canceled() then BendProcessOutcome.Canceled(output)
      else if tooLarge.get() then BendProcessOutcome.OutputLimit(output)
      else if !done then BendProcessOutcome.TimedOut(output)
      else BendProcessOutcome.Exited(process.exitValue(), output)
    catch
      case e: InterruptedException =>
        terminate()
        Thread.currentThread().interrupt()
        BendProcessOutcome.StartFailed("Check canceled")
      case e: java.io.IOException =>
        BendProcessOutcome.StartFailed(
          Option(e.getMessage).getOrElse("Could not start Bend")
        )
      case e: java.util.concurrent.TimeoutException =>
        terminate()
        BendProcessOutcome.TimedOut(output)
    finally
      terminate()
      executor.shutdownNow()
