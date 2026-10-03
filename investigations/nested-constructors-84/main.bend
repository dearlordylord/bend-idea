import ./creature.bend as T
type Bool is Data:
  True{}
  False{}
def actor_available_creature(+actor: T.Creature) -> Bool:
  match actor:
    case T.Cr{T.Alive{state,temp,kind},T.Ec{True{},bonus,grant},ac,features,positive_ac,valid_grant,valid_features}:
      True{}
    case _: False{}
def unresolved(actor):
  match actor:
    case T.Missing{field}:
      field
