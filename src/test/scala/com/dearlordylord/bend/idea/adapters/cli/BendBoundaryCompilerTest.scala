package com.dearlordylord.bend.idea.adapters.cli

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.Assert.*

/** The actual pinned parser supplies grouping judgments, independently of our
  * tolerant editor selection policy. This never evaluates a Bend program.
  */
final class BendBoundaryCompilerTest:
  @Test def gluedAnglesAndComparisonsUseCompilerGrouping(): Unit =
    val compiler = RealBendCompilerFixture.inputs
    val directory = Files.createTempDirectory("bend-boundary-parser-")
    val script = directory.resolve("probe.ts")
    val output = directory.resolve("output.txt")
    try
      Files.writeString(
        script,
        """
const B = await import(process.argv[2]);
function parse(str) {
  const p = {book: B.book_nil(), dir: '', str, pos: 0,
    sc: {stk: [], frs: 0}, stk: [], frs: 0, ns: '', al: {}};
  return B.parse_term(p);
}
function shape(t) {
  if (t.$ === 'App' && t.f.$ === 'App' && t.f.f.$ === 'Ref')
    return [t.f.f.k, shape(t.f.x), shape(t.x)];
  if (t.$ === 'ADT') return ['ADT', t.k, ...t.x.map(shape)];
  if (t.$ === 'Var') return t.k;
  if (t.$ === 'Ctr' || t.$ === 'Lit') return 'number';
  throw new Error('Unexpected compiler node ' + t.$);
}
for (const str of ['4 + 5<6', '4<5<6', '4 + (5<6)',
  '4 + 5 < 6', '4 < 5 < 6', 'Envelope<(Left & Right)>'])
  console.log(JSON.stringify(shape(parse(str))));
// A malformed first argument must never be treated as a valid family application.
try { console.log(parse('Envelope<Left & Right>').$ === 'ADT' ? 'ADT' : 'not ADT'); }
catch { console.log('not ADT'); }
"""
      )
      val process = new ProcessBuilder(
        compiler.bunExecutable.toString,
        script.toString,
        compiler.main.resolveSibling("bend.ts").toString
      ).redirectErrorStream(true).redirectOutput(output.toFile).start()
      try
        assertTrue(
          "Pinned parser timed out",
          process.waitFor(15, TimeUnit.SECONDS)
        )
        val result = Files.readString(output)
        assertEquals(result, 0, process.exitValue())
        assertEquals(
          List(
            "[\".add\",\"number\",[\".is_lt\",\"number\",\"number\"]]",
            "[\".is_lt\",\"number\",[\".is_lt\",\"number\",\"number\"]]",
            "[\".add\",\"number\",[\".is_lt\",\"number\",\"number\"]]",
            "[\".is_lt\",[\".add\",\"number\",\"number\"],\"number\"]",
            "[\".is_lt\",[\".is_lt\",\"number\",\"number\"],\"number\"]",
            "[\"ADT\",\"Envelope\",[\"Pair\",\"Left\",\"Right\"]]",
            "not ADT"
          ),
          result.linesIterator.toList
        )
      finally
        if process.isAlive then
          process.destroyForcibly()
          val _ = process.waitFor(5, TimeUnit.SECONDS)
    finally
      val _ = Files.deleteIfExists(script)
      val _ = Files.deleteIfExists(output)
      val _ = Files.deleteIfExists(directory)

  @Test def typedLambdaLetAndMultilineParallelLetCheckWithoutExecution(): Unit =
    val compiler = RealBendCompilerFixture.inputs
    val directory = Files.createTempDirectory("bend-boundary-check-")
    val source = directory.resolve("main.bend")
    val output = directory.resolve("output.txt")
    try
      for program <- List(
          "import Base\ndef localIdentity() -> U32 -> U32:\n  input => local: U32 = input; local\n",
          "import Base\ndef multilineValues() -> U32:\n  north south east west = {7 : U32} {8 : U32}\n    {9 : U32} {10 : U32}\n  (north + south + east + west : U32)\n"
        )
      do
        Files.writeString(source, program)
        val process = new ProcessBuilder(
          compiler.bunExecutable.toString,
          compiler.main.toString,
          source.toString,
          "--check-only"
        ).redirectErrorStream(true).redirectOutput(output.toFile).start()
        try
          assertTrue(
            "Pinned check timed out",
            process.waitFor(15, TimeUnit.SECONDS)
          )
          assertEquals(Files.readString(output), 0, process.exitValue())
          assertEquals(program, Files.readString(source))
        finally
          if process.isAlive then
            process.destroyForcibly()
            val _ = process.waitFor(5, TimeUnit.SECONDS)
    finally
      val _ = Files.deleteIfExists(source)
      val _ = Files.deleteIfExists(output)
      val _ = Files.deleteIfExists(directory)
