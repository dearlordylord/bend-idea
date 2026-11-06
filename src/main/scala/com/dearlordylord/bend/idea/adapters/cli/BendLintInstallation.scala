package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.adapters.process.{
  BendBoundedProcess,
  BendProcessOutcome
}
import com.dearlordylord.bend.idea.toolchain.api.BendLintInstallationResult
import com.dearlordylord.bend.idea.toolchain.api.BendLintInstallationResult.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.{Properties, UUID}
import java.util.zip.{GZIPInputStream, ZipInputStream}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Explicit installation owns network access and preparation; checks only
  * consume ready tools.
  */
final class BendLintInstallation(private val home: Path):
  import BendLintInstallation.*

  def current(): BendLintInstallationResult = guarded {
    val state = readProperties(home.resolve("selection.properties"))
    installed(state.getProperty("active", ""))
  }

  def install(
      bunOverride: String,
      canceled: () => Boolean
  ): BendLintInstallationResult =
    guarded {
      val _ = Files.createDirectories(home)
      val channel = FileChannel.open(
        home.resolve("install.lock"),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE
      )
      try
        val lock = channel.tryLock()
        if lock == null then
          Unavailable("Another bend-lint installation is running.")
        else
          try installLocked(bunOverride, canceled)
          finally lock.release()
      finally channel.close()
    }

  def rollback(): BendLintInstallationResult = guarded {
    val _ = Files.createDirectories(home)
    val channel = FileChannel.open(
      home.resolve("install.lock"),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE
    )
    try
      val lock = channel.tryLock()
      if lock == null then
        Unavailable("Another bend-lint installation is running.")
      else
        try
          val state = readProperties(home.resolve("selection.properties"))
          val previous = state.getProperty("previous", "")
          installed(previous) match
            case result @ Installed(_, _, _) =>
              writeSelection(previous, state.getProperty("active", ""))
              result
            case _ =>
              Unavailable(
                "No intact previous bend-lint installation is available."
              )
        finally lock.release()
    finally channel.close()
  }

  private def installed(name: String): BendLintInstallationResult =
    if !name.matches("lint-[a-zA-Z0-9-]+") then
      return Unavailable("Install the pinned bend-lint tools in Bend settings.")
    val directory = home.resolve(name)
    validate(directory) match
      case Some(message) => Unavailable(message)
      case None          =>
        val manifest = readProperties(directory.resolve("ready.properties"))
        Installed(
          manifest.getProperty("bun"),
          directory.toString,
          s"bend-lint ${pin("lint.version")}; its own Bend 2.0.39; prepared JavaScript rules."
        )

  private def installLocked(
      bunOverride: String,
      canceled: () => Boolean
  ): BendLintInstallationResult =
    checkCanceled(canceled)
    val name = "lint-" + UUID.randomUUID().toString
    val directory = home.resolve(name)
    val _ = Files.createDirectory(directory)
    var published = false
    try
      val archive = download(pin("lint.url"), 5 * 1024 * 1024, canceled)
      verify(archive, "SHA-512", pin("lint.sha512"))
      unpackTar(archive, directory, canceled)
      val bun = if bunOverride.trim.nonEmpty then
        Path.of(bunOverride.trim).toRealPath()
      else downloadBun(directory, canceled)
      probeBun(bun, directory, canceled)
      List("compiler", "bun").foreach { tool =>
        val notice = download(pin(tool + ".license.url"), 64 * 1024, canceled)
        verify(notice, "SHA-256", pin(tool + ".license.sha256"))
        val _ = Files.write(directory.resolve(tool + "-LICENSE.txt"), notice)
      }
      val _ =
        Files.writeString(directory.resolve("bend-lint.json"), config, UTF_8)
      val _ = Files.createDirectories(directory.resolve("packages"))
      val _ = Files.createDirectories(directory.resolve("cache"))
      val names = directory.resolve("packages/names")
      val _ = Files.createDirectories(names)
      hubPackages.foreach { case (name, hash) =>
        val _ = Files.writeString(names.resolve(name), hash + "\n", UTF_8)
      }
      val downloader = directory.resolve("download-compiler.mjs")
      val _ = Files.writeString(
        downloader,
        "import { tryGetBend2SourceUrl } from './src/linter/seam.ts';\nawait tryGetBend2SourceUrl();\n",
        UTF_8
      )
      val fetched = BendBoundedProcess.run(
        List(bun.toString, downloader.toString),
        directory,
        environment(directory),
        timeoutMillis = 120000L,
        maxOutputBytes = 262144,
        canceled = canceled
      )
      fetched match
        case BendProcessOutcome.Exited(0, _) => ()
        case BendProcessOutcome.Canceled(_)  => throw new InstallationCanceled
        case other                           =>
          throw new IllegalArgumentException(
            "Lint compiler download did not complete: " + other.toString.take(
              4096
            )
          )
      verifyCompiler(directory, canceled)
      val process = BendBoundedProcess.run(
        List(
          bun.toString,
          directory.resolve("src/linter/seam.ts").toString,
          "prepare"
        ),
        directory,
        environment(directory),
        timeoutMillis = 240000L,
        maxOutputBytes = 1024 * 1024,
        canceled = canceled
      )
      process match
        case BendProcessOutcome.Exited(0, _) => ()
        case BendProcessOutcome.Canceled(_)  => throw new InstallationCanceled
        case other                           =>
          throw new IllegalArgumentException(
            "bend-lint preparation did not complete: " + other.toString.take(
              4096
            )
          )
      checkCanceled(canceled)
      require(
        Files.isRegularFile(directory.resolve("LICENSE")),
        "The downloaded package has no license."
      )
      verifyPrepared(directory, canceled)
      val manifest = new Properties()
      val _ = manifest.setProperty("bun", bun.toString)
      val _ = manifest.setProperty(
        "bun.sha256",
        digestPath(bun, 256 * 1024 * 1024, canceled)
      )
      val _ = manifest.setProperty("lint.version", pin("lint.version"))
      val immutable = immutableFiles(directory)
      require(
        immutable.nonEmpty && immutable.size <= 4096,
        "Excessive prepared tool inventory."
      )
      var total = 0L
      immutable.foreach { path =>
        checkCanceled(canceled)
        require(
          Files.size(path) <= 32 * 1024 * 1024,
          "Prepared tool input is too large."
        )
        total += Files.size(path)
        require(
          total <= 64 * 1024 * 1024,
          "Prepared tool inventory is too large."
        )
        val _ = manifest.setProperty(
          "file." + directory.relativize(path).toString.replace('\\', '/'),
          digest(Files.readAllBytes(path), "SHA-256")
        )
      }
      writeProperties(directory.resolve("ready.properties"), manifest)
      require(
        validate(directory).isEmpty,
        "Prepared bend-lint tools failed verification."
      )
      checkCanceled(canceled)
      val state = readProperties(home.resolve("selection.properties"))
      val previous = state.getProperty("active", "")
      writeSelection(name, previous)
      published = true
      installed(name)
    finally if !published then removeTree(directory)

  private def writeSelection(active: String, previous: String): Unit =
    val next = new Properties()
    val _ = next.setProperty("active", active)
    val _ = next.setProperty("previous", previous)
    writeProperties(home.resolve("selection.properties"), next)

object BendLintInstallation:
  private class InstallationCanceled extends RuntimeException
  private lazy val pins: Properties =
    val input = getClass.getResourceAsStream("/lint/tool-pins.properties")
    val values = new Properties()
    try values.load(input)
    finally input.close()
    values

  private def pin(name: String): String =
    Option(pins.getProperty(name))
      .getOrElse(throw new IllegalArgumentException("No pinned " + name))

  val config: String =
    """{"packages":{"bend-lint-rules":"0.3.0.0"},"imports":true,"rules":{"bend-lint-rules/layout/case":"warning","bend-lint-rules/no_unused/imports":"information"}}"""

  private val hubPackages = List(
    "bend-lint-api@0.3.0.0" -> "0x94bdd21968f0b8ad8c1be0e7e2b606a0",
    "bend-lint-rules@0.3.0.0" -> "0xee9c895588f324c3d6d34fd0d503daaf"
  )

  def environment(directory: Path): Map[String, String] = Map(
    "XDG_CACHE_HOME" -> directory.resolve("cache").toString,
    "BEND_LIB" -> directory.resolve("packages").toString,
    "BEND_NO_TELEMETRY" -> "1",
    "BEND_NO_UPDATE_CHECK" -> "1",
    "PATH" -> directory.resolve("no-tools").toString,
    "CC" -> directory.resolve("no-tools/clang-unavailable").toString,
    "ProgramFiles" -> directory.resolve("no-tools").toString,
    "BUN_RUNTIME_TRANSPILER_CACHE_PATH" -> "0",
    "BUN_OPTIONS" -> "",
    "NODE_OPTIONS" -> ""
  )

  /** Validates the exact prepared source/build inputs without consulting the
    * network.
    */
  def validate(
      directory: Path,
      canceled: () => Boolean = () => false
  ): Option[String] =
    try
      val marker = directory.resolve("ready.properties")
      require(
        Files.isRegularFile(marker) && Files.size(marker) <= 1024 * 1024,
        "Install the pinned bend-lint tools first."
      )
      val values = readProperties(marker)
      require(
        values.getProperty("lint.version") == pin("lint.version"),
        "Unsupported bend-lint installation version."
      )
      val bun = Path.of(values.getProperty("bun"))
      require(
        Files.isRegularFile(bun) && Files.isExecutable(bun) && Files.size(
          bun
        ) <= 256 * 1024 * 1024,
        "Prepared Bun is unavailable."
      )
      require(
        digestPath(bun, 256 * 1024 * 1024, canceled) == values.getProperty(
          "bun.sha256"
        ),
        "Prepared Bun changed; reinstall the lint tools."
      )
      val files = values
        .stringPropertyNames()
        .asScala
        .filter(_.startsWith("file."))
        .toList
      require(
        files.nonEmpty && files.size <= 4096,
        "Invalid bend-lint installation inventory."
      )
      val actual = immutableFiles(directory)
        .map(path => directory.relativize(path).toString.replace('\\', '/'))
        .toSet
      require(
        actual == files.map(_.stripPrefix("file.")).toSet,
        "Prepared bend-lint inventory changed; reinstall the tools."
      )
      var total = 0L
      files.foreach { key =>
        checkCanceled(canceled)
        val relative = key.stripPrefix("file.")
        val path = safePath(directory, relative)
        require(
          Files.isRegularFile(path) && !Files.isSymbolicLink(path) && Files
            .size(path) <= 32 * 1024 * 1024,
          "Prepared bend-lint input is missing or too large: " + relative
        )
        total += Files.size(path)
        require(
          total <= 64 * 1024 * 1024,
          "Prepared bend-lint inventory is too large."
        )
        require(
          digest(Files.readAllBytes(path), "SHA-256") == values.getProperty(
            key
          ),
          "Prepared bend-lint input changed: " + relative
        )
      }
      verifyPrepared(directory, canceled)
      None
    catch
      case NonFatal(error) =>
        Some(
          Option(error.getMessage).getOrElse("Invalid bend-lint installation.")
        )

  private def verifyPrepared(directory: Path, canceled: () => Boolean): Unit =
    require(
      treeDigest(
        directory,
        relative =>
          Set("LICENSE", "README.md", "package.json").contains(
            relative
          ) || (relative.startsWith("src/") && !relative.startsWith(
            "src/linter/build/"
          )),
        canceled
      ) == pin("lint.source.tree.sha256"),
      "Prepared lint source does not match the pinned npm package."
    )
    val packages = directory.resolve("packages")
    hubPackages.foreach { case (name, hash) =>
      require(
        Files
          .readString(packages.resolve("names").resolve(name), UTF_8)
          .trim == hash,
        "Unexpected BendHub package identity: " + name
      )
      require(
        Files.isDirectory(packages.resolve(hash)),
        "Prepared BendHub package is missing: " + name
      )
      val source = packages.resolve(hash)
      val stream = Files.walk(source)
      val paths = try
        stream
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .take(4097)
          .toList
          .sortBy(path => source.relativize(path).toString.replace('\\', '/'))
      finally stream.close()
      require(
        paths.nonEmpty && paths.size <= 4096,
        "Invalid prepared BendHub package inventory."
      )
      var total = 0L
      val body = paths.map { path =>
        checkCanceled(canceled)
        require(
          !Files.isSymbolicLink(path) && Files.size(path) <= 5 * 1024 * 1024,
          "Invalid prepared BendHub input."
        )
        total += Files.size(path)
        require(
          total <= 16 * 1024 * 1024,
          "Prepared BendHub package is too large."
        )
        digest(Files.readAllBytes(path), "SHA-256") + " " + source
          .relativize(path)
          .toString
          .replace('\\', '/') + "\n"
      }.mkString
      require(
        "0x" + digest(body.getBytes(UTF_8), "SHA-256").take(32) == hash,
        "Prepared BendHub package contents do not match their pinned identity: " + name
      )
    }
    verifyCompiler(directory, canceled)
    val programs = directory.resolve("cache/bend-lint/server/programs")
    require(Files.isDirectory(programs), "Prepared lint rules are missing.")
    val stream = Files.list(programs)
    try
      val files = stream.iterator().asScala.take(257).toList
      require(files.size <= 256, "Excessive prepared rules inventory.")
      require(
        files.forall(path =>
          path.getFileName.toString == ".pruned" || (Files.isRegularFile(
            path
          ) && !Files.isSymbolicLink(path) && path.getFileName.toString
            .endsWith(".js"))
        ),
        "Unexpected native or unprepared rules artifact; reinstall the tools."
      )
      require(
        files.exists(p =>
          p.getFileName.toString.endsWith(".js") && Files.size(p) > 0
        ),
        "Prepared JavaScript rules are missing."
      )
    finally stream.close()

  private def verifyCompiler(directory: Path, canceled: () => Boolean): Unit =
    val windows = System
      .getProperty("os.name")
      .toLowerCase(java.util.Locale.ROOT)
      .contains("win")
    val compiler = directory
      .resolve("cache/bend-lint")
      .resolve(if windows then
        "MattCozendey/bend/d38038df4c456f5c8421f3630787f6de91d6c724/bend2"
      else "bendlang/bend/v2.0.39/bend2")
    require(
      treeDigest(compiler, _ => true, canceled) == pin(if windows then
        "compiler.windows.tree.sha256"
      else "compiler.official.tree.sha256"),
      "Downloaded lint compiler does not match the tested source pairing."
    )

  def inventoryStamp(directory: Path): String =
    digest(
      immutableFiles(directory)
        .map(path => directory.relativize(path).toString.replace('\\', '/'))
        .sorted
        .mkString("\n")
        .getBytes(UTF_8),
      "SHA-256"
    )

  private def immutableFiles(directory: Path): List[Path] =
    val found = scala.collection.mutable.ListBuffer.empty[Path]
    var visited = 0
    def visit(): Unit =
      visited += 1
      require(
        visited <= 8192,
        "Prepared lint inventory exceeds its visit bound."
      )
    val _ = Files.walkFileTree(
      directory,
      new java.nio.file.SimpleFileVisitor[Path]:
        override def preVisitDirectory(
            path: Path,
            attributes: java.nio.file.attribute.BasicFileAttributes
        ): java.nio.file.FileVisitResult =
          visit()
          if directory
              .relativize(path)
              .toString
              .replace('\\', '/') == "cache/bend-lint/server/checks"
          then java.nio.file.FileVisitResult.SKIP_SUBTREE
          else java.nio.file.FileVisitResult.CONTINUE
        override def visitFile(
            path: Path,
            attributes: java.nio.file.attribute.BasicFileAttributes
        ): java.nio.file.FileVisitResult =
          visit()
          val relative = directory.relativize(path).toString.replace('\\', '/')
          if relative != "ready.properties" && relative != "bun" && relative != "bun.exe" && !relative
              .endsWith(".building") && !relative.endsWith(".pruned")
          then
            found += path
            require(
              found.size <= 4096,
              "Prepared lint inventory exceeds 4096 files."
            )
          java.nio.file.FileVisitResult.CONTINUE
    )
    found.toList

  private def downloadBun(directory: Path, canceled: () => Boolean): Path =
    val osName =
      System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
    val os = if osName.contains("win") then "windows"
    else if osName.contains("mac") then "darwin"
    else if osName.contains("linux") then "linux"
    else
      throw new IllegalArgumentException(
        "No managed Bun package for this operating system; configure Bun explicitly."
      )
    val archName = System.getProperty("os.arch")
    val arch = archName match
      case "aarch64" | "arm64" => "aarch64"
      case "amd64" | "x86_64"  => "x64"
      case _                   =>
        throw new IllegalArgumentException(
          "No managed Bun package for this architecture; configure Bun explicitly."
        )
    val asset = s"bun-$os-$arch"
    val bytes = download(
      s"https://github.com/oven-sh/bun/releases/download/bun-v${pin("bun.version")}/$asset.zip",
      128 * 1024 * 1024,
      canceled
    )
    verify(bytes, "SHA-256", pin(s"bun.$os-$arch.sha256"))
    val target = directory.resolve(if os == "windows" then "bun.exe" else "bun")
    val expected = asset + (if os == "windows" then "/bun.exe" else "/bun")
    val zip = new ZipInputStream(new ByteArrayInputStream(bytes))
    try
      var entry = zip.getNextEntry()
      var found = false
      var entries = 0
      var total = 0L
      while entry != null do
        checkCanceled(canceled)
        entries += 1
        require(entries <= 64, "Too many Bun archive entries.")
        val _ = safePath(directory, entry.getName)
        val body = readBounded(zip, 256 * 1024 * 1024, canceled)
        total += body.length
        require(
          total <= 256 * 1024 * 1024,
          "Bun archive contents are too large."
        )
        if entry.getName == expected then
          require(!found && !entry.isDirectory, "Invalid Bun archive.")
          val _ = Files.write(target, body)
          found = true
        zip.closeEntry()
        entry = zip.getNextEntry()
      require(found, "The pinned Bun archive has no runtime.")
    finally zip.close()
    require(
      target.toFile.setExecutable(true, true) || Files.isExecutable(target),
      "Could not make Bun executable."
    )
    target

  private def probeBun(
      bun: Path,
      directory: Path,
      canceled: () => Boolean
  ): Unit =
    require(Files.isExecutable(bun), "Bun executable is unavailable.")
    BendBoundedProcess.run(
      List(bun.toString, "--revision"),
      directory,
      Map.empty,
      timeoutMillis = 10000L,
      maxOutputBytes = 1024,
      canceled = canceled
    ) match
      case BendProcessOutcome.Exited(0, output) =>
        require(
          output.trim == pin("bun.revision"),
          "bend-lint requires Bun " + pin("bun.revision")
        )
      case BendProcessOutcome.Canceled(_) => throw new InstallationCanceled
      case _                              =>
        throw new IllegalArgumentException(
          "Could not verify Bun " + pin("bun.version")
        )

  private[cli] def verify(
      bytes: Array[Byte],
      algorithm: String,
      expected: String
  ): Unit =
    require(
      digest(bytes, algorithm) == expected,
      "Downloaded tool checksum does not match the pinned release."
    )

  private def digest(bytes: Array[Byte], algorithm: String): String =
    MessageDigest
      .getInstance(algorithm)
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def digestPath(
      path: Path,
      maximum: Long,
      canceled: () => Boolean
  ): String =
    require(Files.size(path) <= maximum, "Prepared tool input is too large.")
    val md = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    val buffer = new Array[Byte](8192)
    try
      var length = input.read(buffer)
      var total = 0L
      while length >= 0 do
        checkCanceled(canceled)
        total += length
        require(total <= maximum, "Prepared tool input is too large.")
        md.update(buffer, 0, length)
        length = input.read(buffer)
    finally input.close()
    md.digest().map(b => f"${b & 0xff}%02x").mkString

  private def treeDigest(
      directory: Path,
      include: String => Boolean,
      canceled: () => Boolean
  ): String =
    require(
      Files.isDirectory(directory),
      "Prepared lint compiler sources are missing."
    )
    val md = MessageDigest.getInstance("SHA-256")
    val stream = Files.walk(directory)
    val paths = try
      stream
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .filter(path =>
          include(directory.relativize(path).toString.replace('\\', '/'))
        )
        .take(4097)
        .toList
        .sortBy(path => directory.relativize(path).toString.replace('\\', '/'))
    finally stream.close()
    require(
      paths.nonEmpty && paths.size <= 4096,
      "Invalid prepared lint compiler inventory."
    )
    var total = 0L
    paths.foreach { path =>
      checkCanceled(canceled)
      require(
        !Files.isSymbolicLink(path) && Files.size(path) <= 5 * 1024 * 1024,
        "Invalid prepared lint compiler input."
      )
      total += Files.size(path)
      require(
        total <= 16 * 1024 * 1024,
        "Prepared lint compiler inventory is too large."
      )
      md.update(
        directory.relativize(path).toString.replace('\\', '/').getBytes(UTF_8)
      )
      md.update(0.toByte)
      md.update(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
      )
    }
    md.digest().map(b => f"${b & 0xff}%02x").mkString

  private[cli] def safePath(directory: Path, relative: String): Path =
    require(
      relative.nonEmpty && !relative.contains('\\') && !relative.contains(
        ':'
      ) && !relative.startsWith("/"),
      "Unsafe tool archive path."
    )
    val path = directory.resolve(relative).normalize()
    require(
      path.startsWith(directory.normalize()) && path != directory.normalize(),
      "Tool archive path escapes its directory."
    )
    path

  private[cli] def unpackTar(
      bytes: Array[Byte],
      directory: Path,
      canceled: () => Boolean
  ): Unit =
    val input = new GZIPInputStream(new ByteArrayInputStream(bytes))
    var total = 0L
    var count = 0
    try
      var ended = false
      while !ended do
        checkCanceled(canceled)
        val header = input.readNBytes(512)
        require(header.length == 512, "Truncated tool archive.")
        ended = header.forall(_ == 0)
        if !ended then
          count += 1
          require(count <= 4096, "Too many tool archive entries.")
          def field(start: Int, size: Int): String =
            new String(header, start, size, UTF_8).takeWhile(_ != 0).trim
          val name = field(0, 100)
          require(name.startsWith("package/"), "Unexpected tool archive root.")
          val size = java.lang.Long.parseLong(field(124, 12), 8)
          total += size
          require(
            size >= 0 && size <= 5 * 1024 * 1024 && total <= 16 * 1024 * 1024,
            "Excessive tool archive contents."
          )
          val kind = header(156).toChar
          require(
            kind == '0' || kind == 0.toChar || kind == '5',
            "Tool archives may contain only files and directories."
          )
          require(
            kind != '5' || size == 0,
            "Tool archive directories cannot contain file data."
          )
          val relative = name.stripPrefix("package/")
          if relative.nonEmpty then
            val path = safePath(directory, relative)
            if kind == '5' then
              val _ = Files.createDirectories(path)
            else
              val _ = Files.createDirectories(path.getParent)
              val body = input.readNBytes(size.toInt)
              require(body.length == size, "Truncated tool archive entry.")
              val _ = Files.write(path, body, StandardOpenOption.CREATE_NEW)
          else require(size == 0, "Invalid tool archive root.")
          val padding = ((512 - size % 512) % 512).toInt
          require(
            input.readNBytes(padding).length == padding,
            "Truncated tool archive padding."
          )
    finally input.close()

  private def download(
      location: String,
      maximum: Int,
      canceled: () => Boolean
  ): Array[Byte] =
    val started = System.nanoTime()
    var address = URI.create(location)
    var redirects = 0
    while redirects <= 5 do
      checkCanceled(canceled)
      require(address.getScheme == "https", "Tool downloads require HTTPS.")
      require(
        (System.nanoTime() - started) / 1000000 < 120000,
        "Tool download timed out."
      )
      val connection = address.toURL.openConnection() match
        case value: HttpURLConnection => value
        case _                        =>
          throw new IllegalArgumentException("Unsupported download transport.")
      connection.setInstanceFollowRedirects(false)
      connection.setConnectTimeout(10000)
      connection.setReadTimeout(10000)
      connection.setRequestProperty("User-Agent", "Bend-IDEA-tool-installer")
      try
        connection.getResponseCode match
          case 301 | 302 | 303 | 307 | 308 =>
            address = address.resolve(
              Option(connection.getHeaderField("Location")).getOrElse(
                throw new IllegalArgumentException(
                  "Download redirect has no destination."
                )
              )
            )
            redirects += 1
          case 200 =>
            require(
              connection.getContentLengthLong <= maximum,
              "Tool download is too large."
            )
            val input = connection.getInputStream
            try
              return readBounded(
                input,
                maximum,
                () => {
                  require(
                    (System.nanoTime() - started) / 1000000 < 120000,
                    "Tool download timed out."
                  )
                  canceled()
                }
              )
            finally input.close()
          case status =>
            throw new IllegalArgumentException(
              s"Tool download failed with HTTP $status. Retry installation when online."
            )
      finally connection.disconnect()
    throw new IllegalArgumentException("Too many tool download redirects.")

  private def readBounded(
      input: InputStream,
      maximum: Int,
      canceled: () => Boolean
  ): Array[Byte] =
    val output = new ByteArrayOutputStream()
    val buffer = new Array[Byte](8192)
    var count = input.read(buffer)
    while count >= 0 do
      checkCanceled(canceled)
      require(
        output.size().toLong + count <= maximum,
        "Tool download or archive entry is too large."
      )
      output.write(buffer, 0, count)
      count = input.read(buffer)
    output.toByteArray

  private def readProperties(path: Path): Properties =
    val values = new Properties()
    if Files.isRegularFile(path) then
      require(Files.size(path) <= 1024 * 1024, "Tool metadata is too large.")
      val input = Files.newInputStream(path)
      try values.load(input)
      finally input.close()
    values

  private def writeProperties(path: Path, values: Properties): Unit =
    val temporary = path.resolveSibling(
      path.getFileName.toString + "." + UUID.randomUUID().toString
    )
    try
      val output =
        Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)
      try values.store(output, "Bend IDEA managed lint tools")
      finally output.close()
      val _ = Files.move(
        temporary,
        path,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    finally
      val _ = Files.deleteIfExists(temporary)

  private def checkCanceled(canceled: () => Boolean): Unit =
    if canceled() || Thread.currentThread().isInterrupted then
      throw new InstallationCanceled

  private def removeTree(directory: Path): Unit =
    if Files.exists(directory) then
      val stream = Files.walk(directory)
      try
        stream
          .iterator()
          .asScala
          .toList
          .reverse
          .foreach(path => { val _ = Files.deleteIfExists(path) })
      finally stream.close()

  private def guarded(
      body: => BendLintInstallationResult
  ): BendLintInstallationResult =
    try body
    catch
      case _: InstallationCanceled => Canceled
      case NonFatal(error)         =>
        Unavailable(
          Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
        )
