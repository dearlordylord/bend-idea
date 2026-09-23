package com.dearlordylord.bend.idea.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import scala.jdk.CollectionConverters.*

/** Compiled dependency rules for A2/A4; semantic contracts still need editor/process tests. */
private[architecture] object ArchitectureRules:
  private val owners = Set("model", "syntax", "workspace", "symbols", "toolchain", "analysis", "features", "adapters", "bootstrap")
  private val policyOwners = Set("model", "workspace", "toolchain", "analysis")
  private val forbiddenPolicyDependencies = Seq(
    "com.intellij.", "org.jetbrains.", "java.awt.", "javax.swing.", "java.io.", "java.nio.file.", "java.net.",
    "java.sql.", "scala.io.", "scala.sys.", "scala.concurrent.", "java.util.concurrent.",
    "java.lang.Process", "java.lang.Runtime", "java.lang.Thread"
  )

  private def parts(packageName: String, root: String): List[String] =
    packageName.stripPrefix(root + ".").split('.').toList

  private def publicArea(target: List[String], owner: String, areas: Set[String]): Boolean =
    target match
      case `owner` :: area :: _ => areas.contains(area)
      case _ => false

  private def allowed(source: List[String], target: List[String]): Boolean =
    val from = source.head
    val to = target.head
    if from == "bootstrap" then true
    else if from == to then
      // Feature handlers cannot reach another slice's implementation.
      from != "features" || source.lift(1) == target.lift(1) ||
        target.take(3) == List("features", "templates", "api")
    else if to == "model" then true
    else from match
      case "symbols" => to == "syntax" || publicArea(target, "workspace", Set("api", "model"))
      case "analysis" => publicArea(target, "workspace", Set("api", "model")) ||
        publicArea(target, "toolchain", Set("api", "model"))
      case "features" => to == "syntax" || publicArea(target, "symbols", Set("api")) ||
        publicArea(target, "workspace", Set("api", "model")) ||
        publicArea(target, "analysis", Set("api", "model")) ||
        publicArea(target, "toolchain", Set("api", "model"))
      case "adapters" => to == "syntax" ||
        Set("workspace", "analysis", "toolchain").exists(owner => publicArea(target, owner, Set("api", "model", "ports"))) ||
        target.take(3) == List("features", "execution", "ports")
      case _ => false

  def violations(classes: JavaClasses, root: String): Seq[String] =
    val imported = classes.asScala.toSeq
    require(imported.nonEmpty, "Architecture rules must inspect compiled production classes")
    imported.flatMap { cls =>
      val source = parts(cls.getPackageName, root)
      val placement = Option.when(!cls.getPackageName.startsWith(root + ".") || !owners(source.head))(
        s"A2: unowned production class ${cls.getName}"
      ).toSeq
      val dependencies = cls.getDirectDependenciesFromSelf.asScala.toSeq.flatMap { dependency =>
        val target = dependency.getTargetClass
        val boundary = Option.when(target.getPackageName.startsWith(root + ".") &&
          !allowed(source, parts(target.getPackageName, root)))(s"A2: ${dependency.getDescription}")
        // Scala case classes inherit this marker; it grants no file or stream access.
        val effect = Option.when(policyOwners(source.head) &&
          target.getName != "java.io.Serializable" &&
          forbiddenPolicyDependencies.exists(target.getName.startsWith))(s"A4: ${dependency.getDescription}")
        boundary.toSeq ++ effect.toSeq
      }
      placement ++ dependencies
    }

  def check(classes: JavaClasses, root: String): Unit =
    val failures = violations(classes, root)
    if failures.nonEmpty then throw new AssertionError(failures.mkString("\n"))
    slices().matching(root + ".(*)..").should().beFreeOfCycles().check(classes)
    // This slice may be absent until the first feature is implemented.
    slices().matching(root + ".features.(*)..").should().beFreeOfCycles().allowEmptyShould(true).check(classes)
