package com.dearlordylord.bend.idea.workspace.api

import scala.collection.mutable

/** Pure Bend import path mapping shared by loading and path completion. */
object BendImportPaths:
  def isHashRootPrefix(fragment: String): Boolean =
    normalize(fragment).matches("^0x[0-9a-f]*$")

  /** A completed slash after this fragment would select the package cache. */
  def isHashRootFragment(fragment: String): Boolean =
    normalize(fragment).matches("^0x[0-9a-f]+$")

  def target(
      sourcePath: String,
      packageCache: String,
      spelling: String
  ): String =
    val rel = normalize(spelling)
    if rel.matches("^0x[0-9a-f]+/.*") then normalize(packageCache + "/" + rel)
    else if rel.startsWith("/") then rel
    else normalize(parent(sourcePath) + "/" + rel)

  /** Namespace assigned by Bend's loader to one written import edge. */
  def namespace(sourceNamespace: String, spelling: String): String =
    if spelling == "Base" then ""
    else
      val rel = normalize(spelling)
      val sub =
        if rel.matches("^0x[0-9a-f]+/.*") || rel.startsWith("/") then rel
        else if parent(sourceNamespace).isEmpty then normalize(rel)
        else normalize(parent(sourceNamespace) + "/" + rel)
      sub.stripSuffix(".bend")

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
