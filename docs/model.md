# The rating model

TeamBayes rates players with the Weng-Lin **Thurstone-Mosteller "full pairing"**
model, ported line by line from the Python package
[openskill](https://github.com/vivekjoshy/openskill.py) 6.2.0. The Kotlin lives in
`engine/src/main/kotlin/io/github/valnrys/teambayes/engine/`. Its results match
openskill's to within 1e-9, checked against the reference fixtures in
[`tools/fixtures/`](../tools/fixtures/README.md).

## How it works, in one paragraph

Each player has a rating `mu ± sigma`: our best guess at their skill, and how unsure
we are. A team's strength is the sum of its players' ratings. On the day, each team
plays at its strength plus some random form. After a game, every player's mu moves
towards what the result suggests and their sigma shrinks, because we've learned
something. Players we're unsure about move further. Before a game, the same model
gives the chance of each result. The balancer picks the split with the highest
chance of a draw, which is the most even game.

## The constants

All are in `EngineConfig` (`Config.kt`). Ratings use openskill's scale, where only
differences between players mean anything.

| Constant | Value | In football terms |
|---|---|---|
| `MU0` | 25 | A new player's starting skill: we assume they're average. |
| `SIGMA0` | 25/3 ≈ 8.33 | A new player's starting uncertainty: we know almost nothing yet. |
| `BETA` | 25/6 ≈ 4.17 | Match-day randomness: how far one game's form can stray from true skill. Scaled per game (below). |
| `TAU` | 25/300 ≈ 0.083 | Uncertainty added before every game, so ratings can follow a player who gets fitter or rustier. |
| `KAPPA` | 1e-4 | A floor on how much one game can shrink sigma. A numerical safety net; normal games never reach it. |
| `EPSILON` | 0.1 | Draw margin: how close two teams' performances must be to count as level. |

### Beta grows with the size of the game

`betaFor(n) = BETA * sqrt(n / 2)`, where `n` counts the players on both teams. A team's
spread grows with the square root of its size, so a 10-player game carries more
match-day noise than a one-on-one. The `WengLin` functions work out `n` from the two
teams, so callers can't pass the wrong number.

## openskill quirks we copy on purpose

The fixtures come from openskill, so we copy what it *does*, not what it might have
meant. Each of these is marked in `ThurstoneMostellerFull.kt`.

- **Tau comes first.** Every sigma becomes `sqrt(sigma² + tau²)` before the update.
- **Epsilon is used for wins too**, not only draws.
- **Gamma and the kappa floor:** `gamma = sqrt(teamVariance) / c`, and a player's new
  sigma is `sigma * sqrt(max(1 - share * delta, kappa))`.
- **Players are summed in descending ordinal order** (`mu - 3 * sigma`).
  Floating-point addition depends on order, so we sum the same way to get the same
  bits.
- **Tied teams don't get averaged.** openskill 6.2.0 tries to give tied teams the same
  average mu change, but it reads the "before" values from objects it has already
  updated, so the step changes nothing. We copy that.
- **Draw prediction assumes a draw rate of `1 / n`** and ignores epsilon.
- **Defaults:** no sigma limiting, no outlier balancing, no player weights, no score
  margin.

We also make a few deliberate changes. None of them changes the maths: for any
input both accept, the engine runs openskill's formulas in openskill's order. They
only narrow what the engine accepts and fix how it is configured.

- **Per-game beta is our configuration, not openskill's default.** openskill's model
  defaults to a fixed `beta = 25/6`. We construct the model with
  `beta = betaFor(n)` for each game and pass it in as the model's beta, exactly as
  the fixtures do. The model code itself is unchanged.
- **Only two teams,** with results given as ranks (`Outcome`).
- **No per-call tau override.** The model's tau is always used.
- **Inputs are validated up front.** Non-finite ratings, impossible settings and
  zero-variance teams in `rate` throw `IllegalArgumentException` instead of
  producing NaN. openskill raises `ZeroDivisionError` in that last case.

One rule the engine can't enforce: **a player must not appear twice in one game.**
The engine has no player ids, so it treats every list entry as a separate player.
Callers must prevent repeats.

## Numerical approach

The model compares normal-distribution values against small thresholds to choose
between formulas, so those values must be accurate even far into the tails.

- **`erfc`** is a faithful port of netlib fdlibm's `s_erf.c`, accurate to within a few
  units in the last place (ulp). We chose it because Python's `math.erfc` uses the
  platform maths library, which is also accurate to a few ulp.
- **`cdf` and `pdf`** use the same formulas as Python 3.14's `NormalDist`:
  `cdf(x) = 0.5 * erfc(-x / sqrt(2))`, which stays accurate in the lower tail, and a
  `pdf` built from `x * x`, so `pdf(x) == pdf(-x)` exactly.
- **`invCdf`** ports CPython's `_normal_dist_inv_cdf` (Wichura's algorithm AS241).
- **`StrictMath`** supplies `exp` and `log`, so the engine gives the same bits on every
  JVM and Android device.

We aren't bit-identical to Python on macOS, which uses Apple's libm and CPython's C
inverse CDF. The difference is a few ulp, far inside the 1e-9 tolerance.

## Known limitations

- **No player identity.** The engine sees ratings, not people. If the same player is
  listed twice, it rates them as two people. Callers must prevent repeats.
- **Two teams only.** That's all 5-a-side needs, so the multi-team parts of openskill
  aren't ported.
- **Draw chances use a fixed prior.** `predictDraw` assumes draws happen 1 time in
  `n` (with `n` players in the game). It doesn't learn how often your group actually
  draws.
- **An extra player is over-favoured.** Team skill is a sum, so a sixth player counts
  as a full extra share. With six equal players against five, the six-a-side team is
  given about a 79% chance of winning. Real games with a spare player are rarely that
  lopsided.
- **Beta is probably too small for casual groups.** In our engine bake-off, a beta 3
  to 5 times larger fitted 46 real games better (in-sample, so treat it as a hint,
  not a result). That's why beta is one named constant, `EngineConfig.BETA`, easy to
  change once there's more data.
- **Close games are the goal, so judge it on closeness.** When the balancer does its
  job, results are close to a coin flip, and the model will rarely "predict the
  winner". That's success, not failure. The right measure is whether games are close.
