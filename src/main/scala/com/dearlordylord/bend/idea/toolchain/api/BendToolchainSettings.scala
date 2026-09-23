package com.dearlordylord.bend.idea.toolchain.api

/** User choices. Empty paths select the documented Bend installation defaults.
  */
final case class BendToolchainChoices(
    executable: String = "",
    baseSource: String = "",
    packageCache: String = "",
    diagnosticsEnabled: Boolean = true
)

/** A path alone cannot prove that an overridden Base matches a compiler. */
enum BendBaseCompatibility:
  case Unverified

final case class BendToolchainSelection(
    executable: String,
    baseSource: String,
    packageCache: String,
    diagnosticsEnabled: Boolean,
    configurationRevision: Long,
    baseCompatibility: BendBaseCompatibility = BendBaseCompatibility.Unverified
)

/** Pure path policy; the adapter supplies environment values and owns
  * persistence.
  */
object BendToolchainPaths:
  def resolve(
      choices: BendToolchainChoices,
      home: String,
      bendLib: Option[String],
      revision: Long
  ): BendToolchainSelection =
    val installation = home.stripSuffix("/") + "/.bend"
    def expand(value: String): String =
      if value == "~" then home
      else if value.startsWith("~/") then home.stripSuffix("/") + value.drop(1)
      else value
    def chosen(value: String, default: String): String = expand(
      if value.trim.isEmpty then default else value.trim
    )
    BendToolchainSelection(
      chosen(choices.executable, installation + "/bin/bend"),
      chosen(choices.baseSource, installation + "/bend2/base.bend"),
      chosen(
        choices.packageCache,
        bendLib.filter(_.nonEmpty).getOrElse(installation + "/lib")
      ),
      choices.diagnosticsEnabled,
      revision
    )

/** Obtained through IntelliJ's application service, implemented at the adapter
  * boundary.
  */
trait BendToolchainSettings:
  def choices: BendToolchainChoices
  def selection: BendToolchainSelection
  def update(value: BendToolchainChoices): Unit
