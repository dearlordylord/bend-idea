package com.dearlordylord.bend.idea.adapters.process

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean

final class BendBoundedProcessTest:
  @Test def timeoutAndOutputLimitsTerminateWorkers(): Unit =
    val directory = Files.createTempDirectory("bend-worker-test-")
    try
      val timed = BendBoundedProcess.run(
        List("sh", "-c", "sleep 10"),
        directory,
        Map.empty,
        timeoutMillis = 100L
      )
      assertTrue(timed.isInstanceOf[BendProcessOutcome.TimedOut])
      val output = BendBoundedProcess.run(
        List("sh", "-c", "yes output"),
        directory,
        Map.empty,
        maxOutputBytes = 128
      )
      assertTrue(output.isInstanceOf[BendProcessOutcome.OutputLimit])
    finally Files.deleteIfExists(directory)

  @Test def cancellationTerminatesWorker(): Unit =
    val directory = Files.createTempDirectory("bend-worker-cancel-")
    try
      val canceled = new AtomicBoolean(false)
      val thread = new Thread(() => {
        Thread.sleep(100)
        canceled.set(true)
      })
      thread.start()
      val result = BendBoundedProcess.run(
        List("sh", "-c", "sleep 10"),
        directory,
        Map.empty,
        canceled = () => canceled.get()
      )
      assertTrue(result.isInstanceOf[BendProcessOutcome.Canceled])
      thread.join()
    finally Files.deleteIfExists(directory)
