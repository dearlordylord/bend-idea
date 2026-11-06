import Base

def rebuild(size: Nat) -> Nat:
  match size:
    case 0n:
      0n
    case 1n+rest:
      1n+rebuild(rest)

def roundtrip_0() -> {rebuild(150n) == 150n : Nat}:
  {==}

def roundtrip_1() -> {rebuild(151n) == 151n : Nat}:
  {==}

def roundtrip_2() -> {rebuild(152n) == 152n : Nat}:
  {==}

def roundtrip_3() -> {rebuild(153n) == 153n : Nat}:
  {==}

def roundtrip_4() -> {rebuild(154n) == 154n : Nat}:
  {==}

def roundtrip_5() -> {rebuild(155n) == 155n : Nat}:
  {==}

def roundtrip_6() -> {rebuild(156n) == 156n : Nat}:
  {==}

def roundtrip_7() -> {rebuild(157n) == 157n : Nat}:
  {==}

def roundtrip_8() -> {rebuild(158n) == 158n : Nat}:
  {==}

def roundtrip_9() -> {rebuild(159n) == 159n : Nat}:
  {==}

def roundtrip_10() -> {rebuild(160n) == 160n : Nat}:
  {==}

def roundtrip_11() -> {rebuild(161n) == 161n : Nat}:
  {==}

def roundtrip_12() -> {rebuild(162n) == 162n : Nat}:
  {==}

def roundtrip_13() -> {rebuild(163n) == 163n : Nat}:
  {==}

def roundtrip_14() -> {rebuild(164n) == 164n : Nat}:
  {==}

def roundtrip_15() -> {rebuild(165n) == 165n : Nat}:
  {==}

def roundtrip_16() -> {rebuild(166n) == 166n : Nat}:
  {==}

def roundtrip_17() -> {rebuild(167n) == 167n : Nat}:
  {==}

def roundtrip_18() -> {rebuild(168n) == 168n : Nat}:
  {==}

def roundtrip_19() -> {rebuild(169n) == 169n : Nat}:
  {==}

def roundtrip_20() -> {rebuild(170n) == 170n : Nat}:
  {==}

def roundtrip_21() -> {rebuild(171n) == 171n : Nat}:
  {==}

def roundtrip_22() -> {rebuild(172n) == 172n : Nat}:
  {==}

def roundtrip_23() -> {rebuild(173n) == 173n : Nat}:
  {==}

def roundtrip_24() -> {rebuild(174n) == 174n : Nat}:
  {==}

def roundtrip_25() -> {rebuild(175n) == 175n : Nat}:
  {==}

def roundtrip_26() -> {rebuild(176n) == 176n : Nat}:
  {==}

def roundtrip_27() -> {rebuild(177n) == 177n : Nat}:
  {==}

def roundtrip_28() -> {rebuild(178n) == 178n : Nat}:
  {==}

def roundtrip_29() -> {rebuild(179n) == 179n : Nat}:
  {==}

def roundtrip_30() -> {rebuild(180n) == 180n : Nat}:
  {==}

def roundtrip_31() -> {rebuild(181n) == 181n : Nat}:
  {==}
