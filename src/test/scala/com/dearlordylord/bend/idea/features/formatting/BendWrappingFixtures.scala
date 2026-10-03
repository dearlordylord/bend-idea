package com.dearlordylord.bend.idea.features.formatting

/** Exact approved issue #83 excerpts shared by the editor and packaged CLI. */
object BendWrappingFixtures:
  val actorBefore =
    """def actor_available_creature(+actor: T.Creature) -> Bool:
       |  match actor:
       |    case T.Cr{T.Alive{state,temp,kind},T.Ec{True{},bonus,grant},ac,features,positive_ac,valid_grant,valid_features}:
       |      True{}
       |    case _: False{}
       |""".stripMargin
  val actorAfter =
    """def actor_available_creature(+actor: T.Creature) -> Bool:
       |  match actor:
       |    case T.Cr{
       |      T.Alive{state, temp, kind},
       |      T.Ec{True{}, bonus, grant},
       |      ac,
       |      features,
       |      positive_ac,
       |      valid_grant,
       |      valid_features
       |    }:
       |      True{}
       |    case _: False{}
       |""".stripMargin
  val healBefore =
    """def heal.alive(state: T.AliveState, temp: Nat, kind: T.CreatureKind, +amount: Nat) -> T.Vitals:
       |  match state:
       |    case T.AliveState{+hp,+maximum,+positive,+bound}:
       |      T.Alive{T.AliveState{alive_hp_after(hp,maximum,amount),maximum,
       |        T.min_positive(maximum,(hp + amount : Nat),T.bound_positive(hp,maximum,positive,bound),T.add_positive(hp,amount,positive)),
       |        T.min_bound_left(maximum,(hp + amount : Nat))},temp,kind}
       |""".stripMargin
  val healAfter =
    """def heal.alive(state: T.AliveState, temp: Nat, kind: T.CreatureKind, +amount: Nat) -> T.Vitals:
       |  match state:
       |    case T.AliveState{+hp, +maximum, +positive, +bound}:
       |      T.Alive{
       |        T.AliveState{
       |          alive_hp_after(hp, maximum, amount),
       |          maximum,
       |          T.min_positive(
       |            maximum,
       |            (hp + amount : Nat),
       |            T.bound_positive(hp, maximum, positive, bound),
       |            T.add_positive(hp, amount, positive)
       |          ),
       |          T.min_bound_left(maximum, (hp + amount : Nat))
       |        },
       |        temp,
       |        kind
       |      }
       |""".stripMargin
  val signatureAfter =
    """def heal.with_evidence(
       |  state: T.AliveState,
       |  temp: Nat,
       |  kind: T.CreatureKind,
       |  +amount: Nat,
       |  evidence: {amount == amount : Nat}
       |) -> T.Vitals:
       |  ?TODO
       |""".stripMargin

  val callBefore = "def main() -> U32:\n  combine(alpha,beta,gamma)\n"
  val callCompact = "def main() -> U32:\n  combine(alpha, beta, gamma)\n"
  val callWrapped =
    "def main() -> U32:\n  combine(\n    alpha,\n    beta,\n    gamma\n  )\n"
  val widthCases = List(
    "28" -> callWrapped,
    "29" -> callCompact,
    "30" -> callCompact,
    "off" -> callCompact,
    "unset" -> callCompact
  )

  val signatureBefore =
    "def heal.with_evidence(state: T.AliveState, temp: Nat, kind: T.CreatureKind, +amount: Nat, evidence: {amount == amount : Nat}) -> T.Vitals:\n  ?TODO\n"
  val tabBefore = "def main() -> U32:\n\tcombine(alpha,beta,gamma)"
  val tabAfter =
    "def main() -> U32:\n\tcombine(\n\t\talpha,\n\t\tbeta,\n\t\tgamma\n\t)"

  val exactCases = List(
    actorBefore -> actorAfter,
    healBefore -> healAfter,
    signatureBefore -> signatureAfter
  )
  val multilineSignatureBefore =
    "def dependent(\n  first: U32, second: U32,\n  third: U32\n) -> U32:\n  first\n"
  val multilineSignatureAfter =
    "def dependent(\n  first: U32,\n  second: U32,\n  third: U32\n) -> U32:\n  first\n"
  val siblingsBefore =
    "def main():\n  Pair{Inner{alpha,beta},Inner{gamma,delta}}\n"
  val siblingsAfter =
    "def main():\n  Pair{\n    Inner{alpha, beta},\n    Inner{gamma, delta}\n  }\n"
  // Newlines before grouping/index openers are significant in Bend application
  // parsing. Reflow must refuse the whole file rather than joining these terms.
  val separatedApplicationCases = List(
    "import Base\ndef combine_three_arguments(a: U32,b: U32,c: U32) -> U32:\n  a\ndef main() -> U32:\n  combine_three_arguments(1\n    (2), 3)\n",
    "def main():\n  combine_three_arguments(first\n    [second],third)\n"
  )
  val unsafeCases = List(
    "def unfinished(alpha,beta\n",
    "def main():\n  combine(alpha *,beta,gamma)\n",
    "def main():\n  combine('ab',alpha,beta)\n",
    ("def main():\n  combine('" + 92.toChar + "u{110000}',alpha,beta)\n"),
    "def dangling(first: U32, second: U32, third: U32) -> U32:\n",
    "def main():\n  (alpha,beta)\n",
    "def main():\n  combine(alpha,beta, # keep attachment\n    gamma)\n",
    "def main():\n  combine(\"unterminated,beta)\n"
  ) ++ separatedApplicationCases

  val boundedCases = List(
    (
      25,
      "def main():\n  (combine(alpha,beta,gamma))\n",
      "def main():\n  (combine(\n    alpha,\n    beta,\n    gamma\n  ))\n"
    ),
    (
      25,
      "def main():\n  pair(\n    a,b\n  ) + combine(alpha,beta,gamma)\n",
      "def main():\n  pair(\n    a, b\n  ) + combine(\n    alpha,\n    beta,\n    gamma\n  )\n"
    )
  )

  val editorConfigIndentCases = List(
    ("tab_width = 8\n", "  ", "    "),
    (
      "indent_style = invalid\nindent_size = invalid\ntab_width = invalid\n",
      "  ",
      "    "
    ),
    ("indent_style = space\ntab_width = 8\n", "  ", "    "),
    ("indent_style = tab\nindent_size = 4\ntab_width = 3\n", "\t ", "\t\t  "),
    ("indent_style = tab\nindent_size = 4\ntab_width = 8\n", "    ", "\t")
  )

  val multilineLiteralBefore =
    "import Base\ndef text(\n  x: U32\n) -> String:\n  \"hello\ndef fake():\n  contents\n\n\"\ndef other(x: U32,y: U32) -> U32:\n  x\n"
  val multilineLiteralAfterFourSpaces =
    "import Base\ndef text(\n  x: U32\n) -> String:\n  \"hello\ndef fake():\n  contents\n\n\"\ndef other(x: U32, y: U32) -> U32:\n    x\n"
  val comparisonCases = List(("upper", "A", "B"), ("lower", "a", "b")).map {
    (name, left, right) =>
      val header = s"import Base\ndef less($left: Nat,$right: Nat) -> Bool:\n"
      val body = s"($left< $right : Nat)\n"
      (
        name,
        header + "    " + body,
        header.replace(",", ", ") + "  " + body,
        header.replace(",", ", ") + "    " + body
      )
  }
