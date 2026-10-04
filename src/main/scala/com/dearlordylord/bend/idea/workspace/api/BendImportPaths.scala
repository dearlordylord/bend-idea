package com.dearlordylord.bend.idea.workspace.api

import scala.collection.mutable

/** Pure Bend import path mapping shared by loading and path completion. */
object BendImportPaths:
  def isHashRootPrefix(fragment: String): Boolean =
    normalize(fragment).matches("^0x[0-9a-f]*$")

  /** A completed slash after this fragment would select the package cache. */
  def isHashRootFragment(fragment: String): Boolean =
    normalize(fragment).matches("^0x[0-9a-f]+$")

  def isNamedPackage(name: String): Boolean =
    name.matches(
      "[a-z][a-z0-9-]{0,63}@(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*)){3}"
    )

  def isPackageHash(hash: String): Boolean = hash.matches("0x[0-9a-f]{32}")

  def isPackageImport(spelling: String): Boolean =
    spelling.startsWith("0x") || namedPackage(spelling).nonEmpty

  def namedPackage(spelling: String): Option[String] =
    Option
      .when(spelling.contains('/'))(spelling.takeWhile(_ != '/'))
      .filter(isNamedPackage)

  def withPackageHash(spelling: String, hash: String): String =
    hash + spelling.substring(spelling.indexOf('/'))

  def target(
      sourcePath: String,
      packageCache: String,
      spelling: String
  ): String =
    val rel = normalize(spelling)
    if rel.matches("^0x[0-9a-f]+/.*") then normalize(packageCache + "/" + rel)
    else if rel.startsWith("/") then rel
    else normalize(parent(sourcePath) + "/" + rel)

  /** Namespace of a canonical target relative to the canonical root directory,
    * or the package cache. Written import paths remain separate for editing.
    */
  def canonicalNamespace(
      rootPath: String,
      targetPath: String,
      packageCache: String
  ): String =
    val target = normalize(targetPath)
    val cache = normalize(packageCache).stripSuffix("/")
    val namespace = if cache.nonEmpty && target.startsWith(cache + "/") then
      target.substring(cache.length + 1)
    else
      val from =
        parent(normalize(rootPath)).split('/').filter(_.nonEmpty).toList
      val to = target.split('/').filter(_.nonEmpty).toList
      val common = from.zip(to).takeWhile((a, b) => a == b).size
      (List.fill(from.size - common)("..") ++ to.drop(common)).mkString("/")
    namespace.stripSuffix(".bend")

  def directory(
      sourcePath: String,
      packageCache: String,
      fragment: String
  ): String =
    val slash = fragment.lastIndexOf('/')
    val parentPath = if slash < 0 then "." else fragment.take(slash + 1)
    val normalized = normalize(parentPath)
    if parentPath.endsWith("/") && normalized.matches("^0x[0-9a-f]+(?:/.*)?$")
    then normalize(packageCache + "/" + normalized)
    else if normalized.isEmpty then normalize(parent(sourcePath))
    else target(sourcePath, packageCache, normalized)

  private def parent(path: String): String =
    val slash = path.lastIndexOf('/')
    if slash < 0 then "" else path.substring(0, slash)

  private def normalize(path: String): String =
    val absolute = path.startsWith("/")
    val stack = mutable.ArrayBuffer.empty[String]
    path.split('/').foreach {
      case "" | "."                                     => ()
      case ".." if stack.nonEmpty && stack.last != ".." =>
        stack.remove(stack.size - 1)
      case ".." if !absolute => stack += ".."
      case ".."              => ()
      case segment           => stack += segment
    }
    (if absolute then "/" else "") + stack.mkString("/")
