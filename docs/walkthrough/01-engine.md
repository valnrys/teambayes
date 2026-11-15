# Walkthrough 1: the engine

This is a guided tour of the `:engine` module, one function at a time, written for
someone who knows engineering but not Kotlin. Skim section 1 to get the shape of it,
dive into section 2 when you want to know what a particular line is doing, and use
section 4 when something seems odd.

It describes the code **as it is** at the end of milestone 1. Where the code and the
docs disagree, this follows the code. Every `File.kt:line` link jumps to the line.

---

## 1. The engine in one page

**What it does.** It works out how good each player probably is from who played whom
and who won, then uses that to split tonight's players into the most even two teams.
It's plain Kotlin with no Android and no runtime libraries beyond Kotlin's standard
library and the JDK
([`build.gradle.kts:26-31`](../../engine/build.gradle.kts#L26-L31)).

**How a game flows through it.**

```
  organiser drags players best → worst
                 │
                 ▼
        Prior.fromRankOrder            one starting mu per player
                 │                     (sigma always starts wide: 25/3)
                 ▼
        starting ratings ─────┐        Map<playerId, Rating>
                              │
        every game ever ──────┤        List<Game>, in any order
                              ▼
                          replay()     check every game, sort, rate them one by one
                              │          └─ WengLin.rate → ThurstoneMostellerFull
                              ▼                               └─ NormalDist
        current ratings + history      history is for charts
                              │
        tonight's players ────┤        plus optional locks ("p03 plays for A")
                              ▼
                     Balancer.balance  scores each split with predictDraw
                              │
                              ▼
        the k most even splits, best first
```

Three ideas carry the whole thing:

- **A rating is a bell curve.** `mu` is our best guess at a player's skill and
  `sigma` is how unsure we are. Games move `mu` and usually shrink `sigma`.
- **Ratings are never stored.** They are rebuilt from scratch by replaying every
  game, so editing an old result just means replaying again.
- **"Most even" means "most likely to be a draw".** The balancer ranks splits by the
  model's predicted draw chance.

**How we know it's right.** The maths is a line-by-line port of the Python package
openskill 6.2.0. A Python script, [`tools/fixtures/generate.py`](../../tools/fixtures/generate.py),
runs openskill and writes thousands of expected answers into JSON "fixtures". The Kotlin
tests must match every one to within **1e-9**, absolute or relative. Tiny tail values are
judged only by relative error, so returning `0.0` can't fake a match
([`FixtureSupport.kt:23-27`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/FixtureSupport.kt#L23-L27)). The commit that landed the port reports agreement to
about 2e-13. The generator also throws away fixture points so fragile that a one-bit
wobble would flip them, so a correct port can't fail by bad luck. On top of that, the
project uses **mutation checks** (break a line on purpose and confirm a test goes red;
see [learning.md](../learning.md)), and **every change is reviewed adversarially by two
independent AI reviewers before it's pushed** ([decisions.md](../decisions.md)). Each is
asked to find what's wrong, not to approve.

---

## 2. File by file

Reading order: the constants, the data types, the bell-curve maths, the model, the
friendly wrapper, then the three features (prior, replay, balancer).

### `Config.kt`: the fixed numbers

**What it's for:** one place for every tuning constant, so nothing is a magic number
somewhere else.

**`EngineConfig`** ([`Config.kt:12`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Config.kt#L12)) is an `object`: a single ready-made instance, so
you write `EngineConfig.MU0`. *Kotlin idea here: `object` and `const val`, see
[learning.md](../learning.md#object).*

The six constants ([`Config.kt:15-30`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Config.kt#L15-L30)) are openskill's defaults. In football terms:

| Constant | Value | Means |
|---|---|---|
| `MU0` | 25 | A new player is assumed average. |
| `SIGMA0` | 25/3 ≈ 8.33 | ...and we know almost nothing about them. |
| `BETA` | 25/6 ≈ 4.17 | How far one night's form strays from true skill. |
| `TAU` | 25/300 ≈ 0.083 | Doubt added before every game, so ratings can follow someone getting fitter or rustier. |
| `KAPPA` | 1e-4 | Safety floor on how much one game can shrink sigma. Normal games never reach it. |
| `EPSILON` | 0.1 | Draw margin: how close two performances must be to count as level. |

Only the ratios matter. Doubling every number except kappa (a pure safety floor with no
units) would describe the same players.

**`betaFor(nPlayers)`** ([`Config.kt:39-42`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Config.kt#L39-L42)) gives the match-day randomness for a game
with `nPlayers` on the pitch: `BETA * sqrt(nPlayers / 2)`. A 10-player game has more
room for luck than a one-on-one, so it gets a bigger beta (about 9.3 for 5-a-side).
This is TeamBayes's own choice, not openskill's default. It refuses fewer than two
players with `require`. *Kotlin idea: `require`, see [learning.md](../learning.md#require).*

### `Rating.kt`: the data types

**What it's for:** the small value types every other file passes around.

**`Rating(mu, sigma)`** ([`Rating.kt:11`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Rating.kt#L11)) is one player's skill estimate. It's a
`data class`, so equality, printing and copying come for free. *Kotlin idea: see
[learning.md](../learning.md#data-class).*

- The `init` block ([`Rating.kt:13-16`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Rating.kt#L13-L16)) runs on every construction and rejects a
  non-finite `mu` or a negative or non-finite `sigma`. So a NaN can never sneak into
  the engine inside a `Rating`, and that includes ratings the model itself produces.
  `sigma = 0` is allowed. *Kotlin idea: `init` is the constructor body.*
- **`ordinal`** ([`Rating.kt:23-24`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Rating.kt#L23-L24)) is `mu - 3 * sigma`: a cautious single number,
  "almost certainly at least this good". It's handy for leaderboards, because a new
  player with one lucky win doesn't leap to the top. **Surprise:** the model uses it
  too, but only to decide the order in which it adds up a team (see `teamSum` below).

**`Outcome`** ([`Rating.kt:33-42`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Rating.kt#L33-L42)) is the result of a game: `A_WINS`, `B_WINS` or
`DRAW`. Each carries openskill's "ranks" (lower is better, equal is a draw), because
that's the language the model's maths is written in. *Kotlin idea: `enum class`, see
[learning.md](../learning.md#enum-class).*

**`RatedTeams`** ([`Rating.kt:48`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Rating.kt#L48)) and **`WinProbabilities`** ([`Rating.kt:54`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Rating.kt#L54)) are
two-field result types, so callers read `result.teamA` instead of guessing what
`result.first` means. Both destructure, meaning you can unpack the fields into separate
names in one line: `val (newA, newB) = WengLin.rate(...)`.
**Note:** win probabilities add to 1. Draws don't get a share there.

### `NormalDist.kt`: the bell-curve maths

**What it's for:** the three bell-curve functions the model needs, computed the same
way Python does, so our answers land within a few rounding steps of openskill's.

**Why write our own?** The model picks between formulas by comparing these values
with tiny thresholds (below 2.2e-16 in one case). A sloppy implementation would take
the wrong branch near a threshold and give a wildly different answer. So each function
follows Python 3.14's `statistics.NormalDist` formula for formula. `exp` and `log` come
from Java's `StrictMath`, which gives the same bits on every JVM and Android phone
([`NormalDist.kt:41-42`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L41-L42)). The licence header ([`NormalDist.kt:1-21`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L1-L21)) credits fdlibm
and CPython, whose code this translates.

**`NormalDist`** ([`NormalDist.kt:44`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L44)) is `internal`: usable inside the engine module
but invisible to the app. *Kotlin idea: see [learning.md](../learning.md#internal).*

**`SQRT_2`, `SQRT_TWO_PI`** ([`NormalDist.kt:46-53`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L46-L53)) are √2 and √(2π), worked out
once. The KDoc (the `/** ... */` doc comment above the code; see
[learning.md](../learning.md#kdoc)) points out that Python calls 2π "tau", which has nothing to do with the
model's `TAU`.

**`cdf(x)`** ([`NormalDist.kt:63`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L63)): the chance a standard normal value is at most `x`,
the area under the curve to the left. It's written as `0.5 * erfc(-x / √2)` rather
than the textbook `0.5 * (1 + erf(...))`. That's how Python 3.14 does it, and it keeps
its accuracy deep in the left tail, where `cdf(-10)` is about 7.6e-24 rather than
rounding to zero.

**`pdf(x)`** ([`NormalDist.kt:71`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L71)): the height of the curve at `x`. **Surprise:** it
uses `x * x` deliberately, so `pdf(x) == pdf(-x)` holds to the last bit. One of
openskill's draw helpers computes `pdf(-t) - pdf(t)` and needs an exact `0.0`, and the
fixtures check this.

**`invCdf(p)`** ([`NormalDist.kt:80-148`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L80-L148)): the reverse of `cdf`. Given an area `p`, it
finds the `x` that has that much area to its left. Only `predictDraw` uses it, to turn
"draws happen 1 time in n" into a draw margin. It's a port of CPython's version of
Wichura's AS241 algorithm, which uses three hand-tuned fractions of polynomials: one
for the middle of the range ([`NormalDist.kt:84-103`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L84-L103)) and two for the tails
([`NormalDist.kt:105-147`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L105-L147)). It rejects `p` outside (0, 1) ([`NormalDist.kt:81`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L81)).

**`erfc(x)`** ([`NormalDist.kt:160-225`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L160-L225)): the complementary error function,
`1 - erf(x)`, which is what `cdf` is built on. It's a faithful translation of fdlibm,
the 1993 Sun maths library most C maths libraries descend from. **The idea:** no
single formula is accurate everywhere, so it splits the number line into ranges and
uses a formula tuned for each:

- **Not a number or infinite** ([`NormalDist.kt:165-168`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L165-L168)): NaN stays NaN, `erfc(+∞) = 0`,
  `erfc(-∞) = 2`.
- **Near zero, |x| < 0.84375** ([`NormalDist.kt:170-183`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L170-L183)): 1 minus a tuned
  approximation of `erf`. For very tiny |x| it just returns `1 - x` ([`NormalDist.kt:171`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L171)).
- **|x| from 0.84375 up to 1.25** ([`NormalDist.kt:185-196`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L185-L196)): it starts from a stored value
  of `erf(1)` (`ERX`) and corrects for the distance from 1.
- **|x| from 1.25 up to 28** ([`NormalDist.kt:198-221`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L198-L221)): here `erfc` behaves like
  `exp(-x²)/x` times a slowly changing correction, with two sets of coefficients
  either side of |x| ≈ 2.86. For x at or below -6 the answer is simply 2 to double
  precision ([`NormalDist.kt:209`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L209)). One trick is worth knowing: `exp(-x²)` loses digits
  when |x| is large, so |x| is split into a "round" part `z` and a small remainder, and the
  two exponentials are computed separately ([`NormalDist.kt:215-219`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L215-L219)).
- **|x| of 28 or more** ([`NormalDist.kt:224`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L224)): the answer underflows to 0 (or rounds to 2).

**Surprise:** the range checks don't compare `x` with 0.84375. They compare the top
32 bits of the number's binary form (`hx`, `ix`) with hex constants, exactly as the C
code does ([`NormalDist.kt:161-163`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L161-L163)). That makes the boundaries fall on the same bit
patterns. *Kotlin idea: `toRawBits()` gives a Double's 64 bits as a Long; `ushr`,
`shl` and `and` are bit operators, spelled as words.*

**The constants** ([`NormalDist.kt:228-296`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L228-L296)) are fdlibm's coefficients, copied with
Sun's hex bit patterns in comments so a reviewer can check each one. `HIGH_WORD_MASK`
([`NormalDist.kt:228`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt#L228)) clears the low 32 bits for the `z` trick. `TINY` is used to
produce a clean 0 or 2 at the extremes.

### `ThurstoneMostellerFull.kt`: the rating model

**What it's for:** given two teams and a result, it nudges every player's rating; given
two teams before a game, it predicts the result. Everything else in the engine is
plumbing around this file.

**What the name means.** "Weng-Lin" is the 2011 paper that turned Bayesian rating
into simple update formulas. "Thurstone-Mosteller" means each team's performance on
the night is a bell curve. "Full" means every team is compared with every other team,
which with two teams just means "A against B".

**The class** ([`ThurstoneMostellerFull.kt:35-42`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L35-L42)) holds the six settings, each
defaulting to an `EngineConfig` value. *Kotlin idea: default arguments
(`val beta: Double = EngineConfig.BETA`) mean you only pass what differs, by name:
`ThurstoneMostellerFull(beta = 9.3)`.* Most callers should use `WengLin` instead,
which picks beta for you.

**The `init` checks** ([`ThurstoneMostellerFull.kt:44-52`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L44-L52)) reject impossible settings
(zero beta, negative tau, NaN anything). **Surprise:** they're written as positive
checks (`beta > 0.0 && beta.isFinite()`), because any comparison with NaN is false, so
NaN fails a positive check automatically.

**`newRating()`** ([`ThurstoneMostellerFull.kt:55`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L55)): a fresh player's rating, `mu ± sigma`
from the model's settings.

**`rate(teamA, teamB, outcome)`** ([`ThurstoneMostellerFull.kt:75-101`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L75-L101)): the heart of
the engine. It returns new ratings in the same player order and never changes its
inputs. In three steps, numbered as in the code's comments:

1. **Add doubt first** ([`ThurstoneMostellerFull.kt:80-82`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L80-L82)). Every sigma becomes
   `sqrt(sigma² + tau²)`: "they might have changed since last time". That's openskill's
   order, and the fixtures depend on it. Then it **refuses a team with no uncertainty**
   ([`ThurstoneMostellerFull.kt:86-89`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L86-L89)): if a whole team has sigma 0 and tau is 0, the
   maths divides by zero. openskill crashes there; we throw a clear error instead of
   returning NaN. With the default tau this can't happen.
2. **Update each team against the other** ([`ThurstoneMostellerFull.kt:93-94`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L93-L94)) with
   `updateTeam`.
3. **Skip the tie-averaging, on purpose** ([`ThurstoneMostellerFull.kt:96-99`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L96-L99)). This is
   the most famous quirk. openskill 6.2.0 tries to give tied teams the same average
   change, but it reads the "before" values from objects it has already overwritten, so
   every adjustment it computes is zero. We copy the behaviour (do nothing) rather than
   the intent, because the fixtures came from the real behaviour.

**Also surprising:** the model has no idea who anyone is. If the same player is listed
twice, it rates them as two people. The KDoc ([`ThurstoneMostellerFull.kt:63-66`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L63-L66)) puts
the burden on callers, and `replay` is the caller that enforces it.

**`predictWin(teamA, teamB)`** ([`ThurstoneMostellerFull.kt:112-120`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L112-L120)): the chance each
team wins. It's `cdf(gap in team mu / spread)`, where the spread combines two betas
(one per team's form on the night) and both teams' uncertainty. Equal teams give
0.5. **Note:** no tau here, and no share for draws: team B's chance is just `1 - pA`.

**`predictDraw(teamA, teamB)`** ([`ThurstoneMostellerFull.kt:131-146`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L131-L146)): the chance of a
draw, which the balancer maximises. It sets a draw band around "level" and measures
how much of the predicted gap's bell curve lands in it. **Surprises**
([`ThurstoneMostellerFull.kt:133-139`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L133-L139)):

- It assumes draws happen 1 time in `n` (with `n` players), which isn't configurable and
  isn't learned from your group's real draw rate. (openskill's choice.)
- It ignores `epsilon` entirely and builds its own margin with `invCdf`. (Also
  openskill's.)
- **The player count enters twice, and that's partly ours.** openskill's `predict_draw`
  multiplies beta by `sqrt(n)`, assuming an unscaled beta. Our `WengLin` has already
  scaled beta by `sqrt(n / 2)`, so through `WengLin` the player count scales beta a
  second time. Harmless for ranking splits, since every split of one roster has the
  same `n`.

**`updateTeam(...)`** ([`ThurstoneMostellerFull.kt:152-195`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L152-L195), private): new ratings for
one team's players. Here's the intuition without the derivation:

- `c` ([`ThurstoneMostellerFull.kt:160`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L160)) is the total spread of the contest: both teams'
  uncertainty plus two betas of match-day luck.
- `deltaMu` ([`ThurstoneMostellerFull.kt:161`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L161)) is how much better this team was expected
  to be, measured in units of `c`. Big and positive means "we were clear favourites".
- `t` ([`ThurstoneMostellerFull.kt:166`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L166)) is the draw margin in the same units. **Quirk:**
  openskill uses it for wins and losses too, not only draws.
- `omega` is how far the team's mu moves; `delta` is how much of its variance (sigma², the
  spread squared) we remove.
  The `when` ([`ThurstoneMostellerFull.kt:171-184`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L171-L184)) picks the formulas for a win, a
  loss or a draw. *Kotlin idea: `when` is a tidier `if / else if / else`.*
- Each player then takes a slice of the team's change in proportion to their own
  variance ([`ThurstoneMostellerFull.kt:188-194`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L188-L194)). The player we know least about moves
  most. Their sigma, already widened by tau in step 1, is multiplied by
  `sqrt(1 - share * delta)`, and never falls below `sqrt(kappa)` times that value ([`ThurstoneMostellerFull.kt:192`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L192)). That floor only bites with absurd
  inputs, like a sigma of 1000.

**`requireTeams`** ([`ThurstoneMostellerFull.kt:197-199`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L197-L199)): both teams must have someone in them.

**`TeamSum`** ([`ThurstoneMostellerFull.kt:202`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L202)) and **`teamSum(team)`**
([`ThurstoneMostellerFull.kt:209-217`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L209-L217)): a team's strength is the sum of its players' mu, and
its uncertainty is the sum of their sigma². **Surprise:** players are added in order of
descending `ordinal`, because that's what openskill does, and floating-point addition
gives slightly different bits in a different order. A test proves the order matters
([`EngineApiTest.kt:117-132`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/EngineApiTest.kt#L117-L132)). *Kotlin idea: see "Why floating-point order matters" in
[learning.md](../learning.md#why-floating-point-order-matters).*

**The four helper functions** live in an `internal companion object`
([`ThurstoneMostellerFull.kt:219`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L219)) so the tests can call them directly. *Kotlin idea: a
companion object holds a class's "static" members.* `MACHINE_EPSILON`
([`ThurstoneMostellerFull.kt:221`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L221)) is 2^-52, the gap between 1.0 and the next double.
In all four, `x` is `deltaMu` (how much this team was favoured) and `t` is the margin.

- **`v(x, t)`** ([`ThurstoneMostellerFull.kt:228-232`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L228-L232)): **how much a win surprises us**,
  and so how far mu moves. If you were heavy favourites (`x` large), winning is no news
  and `v` is near 0. If you were big underdogs (`x` very negative), `v` is large: an
  upset moves ratings a lot. When the maths gets too small to divide safely, it uses the
  straight-line limit `-(x - t)`.
- **`w(x, t)`** ([`ThurstoneMostellerFull.kt:238-246`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L238-L246)): **how much a win teaches us**, as
  the fraction of variance to remove, between 0 and 1. An expected result teaches little;
  a shock teaches a lot. **Quirk:** its fallback tests the sign of `x`, not `x - t`.
- **`vt(x, t)`** ([`ThurstoneMostellerFull.kt:253-261`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L253-L261)): the draw version of `v`. A draw
  pulls both teams towards each other: the favourite moves down, the underdog up. Even
  teams barely move. When the draw band holds less than 1e-5 of the probability, it uses
  a straight line in `x`.
- **`wt(x, t)`** ([`ThurstoneMostellerFull.kt:267-276`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L267-L276)): the draw version of `w`, falling
  back to 1 (the biggest possible shrink) in the extreme case.

Those thresholds (`MACHINE_EPSILON`, `1e-5`) are exactly why `NormalDist` must be so
precise.

### `WengLin.kt`: the front door

**What it's for:** the easy, safe way to rate and predict. It works out beta from the
team sizes, so nobody can pass a player count that doesn't match the teams.

**`WengLin`** ([`WengLin.kt:15`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/WengLin.kt#L15)) is an `object` with four public one-line functions and a private helper:

- **`rate`**, **`predictWin`**, **`predictDraw`** ([`WengLin.kt:21-30`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/WengLin.kt#L21-L30)) each build the
  right model for this game and call the method of the same name on it. *Kotlin idea:
  `fun f(...) = expression` is a function whose body is one expression.*
- **`forGame(nPlayers)`** ([`WengLin.kt:43-44`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/WengLin.kt#L43-L44)) builds the model for an `n`-player game.
  It exists for the balancer, which scores thousands of splits of one roster and
  shouldn't build a model each time. **The catch:** nothing checks that later calls
  really have `n` players. Prefer the three before it.
- **`modelFor`** ([`WengLin.kt:47-50`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/WengLin.kt#L47-L50), private) checks for an empty team *before*
  counting players. Otherwise 1-against-0 would fail with the less helpful "a game needs
  at least two players" from `betaFor`.

### `Prior.kt`: starting skill from a ranking

**What it's for:** turning the organiser's rough best-to-worst order into starting
ratings, so the first few team splits aren't a blind guess.

**`Prior`** ([`Prior.kt:10`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Prior.kt#L10)) only sets `mu`. Sigma always starts at `SIGMA0`, which is
wide, so a handful of real results outweighs the ranking.

**`SPREAD_IN_BETAS`** ([`Prior.kt:22`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Prior.kt#L22)) is 2: the best and worst players start two betas
(about 8.3 points) either side of 25. That's exactly one starting sigma, which is the
reasoning in the KDoc: enough to steer early splits, not enough to be stubborn.

**`fromRankOrder(count)`** ([`Prior.kt:36-43`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Prior.kt#L36-L43)) returns `count` evenly spaced mus from
about 33.3 (best, first) down to about 16.7 (worst, last). One player gets 25
([`Prior.kt:38`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Prior.kt#L38)); zero players get an empty list; a negative count throws. It uses the
base `BETA`, not `betaFor`, because there's no game yet. *Kotlin idea:
`List(count) { rank -> ... }` builds a list by calling the block once per index.*

**`insertBetween(above, below)`** ([`Prior.kt:66-78`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Prior.kt#L66-L78)) picks a starting mu for someone
who joins later, placed between two existing players:

- both neighbours: the midpoint;
- only a better neighbour (new worst player): one beta below it;
- only a worse neighbour (new best player): one beta above it;
- nobody: 25.

**Surprises:** stepping past either end always takes a full beta, so a new player can
start outside the 16.7 to 33.3 range. And it throws if `above` is lower than `below`,
which could happen if the app took neighbours from the original ranking after a few
games had reshuffled people. Callers should take neighbours from a list sorted by
current mu. *Kotlin idea: `Double?` means "a Double or null", and `when { ... }` with
no subject is a chain of conditions.*

### `Replay.kt`: from game history to current ratings

**What it's for:** rebuilding everyone's current rating by playing every game again,
in order, from the starting ratings.

**`Game`** ([`Replay.kt:14-21`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Replay.kt#L14-L21)): one played game, with an id, when it was played and
entered (epoch milliseconds), each team's player ids, and the outcome.

**`RatingPoint`** ([`Replay.kt:24`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Replay.kt#L24)): one player's rating just after one game, for charts.

**`ReplayResult`** ([`Replay.kt:33-36`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Replay.kt#L33-L36)): everyone's current rating, plus each
player's history oldest first. Players who never played keep their starting rating and
an empty history.

**`replay(starting, games)`** ([`Replay.kt:55-75`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Replay.kt#L55-L75)) is a top-level function, not inside
any class. *Kotlin idea: Kotlin lets functions live directly in a file.* Step by step:

1. Check every game before rating any of them (`validate`, below). A bad game anywhere
   means nothing is rated.
2. Sort the games by when they were played, then when they were entered, then by id
   ([`Replay.kt:61`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Replay.kt#L61)). So the input order never matters, and two games played at the
   same moment always replay in the same order. **Note:** ids compare as text, so
   `"g10"` comes before `"g2"`. That only matters as a last tie-break.
3. Rate each game with `WengLin.rate` ([`Replay.kt:63-67`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Replay.kt#L63-L67)), then write the new ratings
   back and append a history point for each player ([`Replay.kt:68-72`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Replay.kt#L68-L72)).

*Kotlin idea: `compareBy({ it.playedAt }, { it.createdAt }, { it.id })` builds a
"sort by this, then this, then this" rule from small lambdas (`{ it.x }` is a one-line
function of one argument).*

**`validate`** ([`Replay.kt:77-94`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Replay.kt#L77-L94), private to this file) rejects, in order: a
repeated game id, an empty team, a player listed twice in one game (same team or both),
and a player with no starting rating. This is where the model's "no player listed
twice" rule is enforced, because the model can't see ids. *Kotlin idea: `require` with
a message, see [learning.md](../learning.md#require).*

### `Balancer.kt`: picking even teams

**What it's for:** given tonight's players and any locks, it finds the `k` splits with
the highest chance of a draw.

#### The types

- **`BalancerPlayer(id, rating)`** ([`Balancer.kt:7`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L7)): someone playing tonight.
- **`Side`** ([`Balancer.kt:10`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L10)): `A` or `B`, for locks.
- **`Split`** ([`Balancer.kt:18-23`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L18-L23)): the two teams' player ids, the draw chance (the
  score) and team A's win chance.
- **`BalanceResult`** ([`Balancer.kt:26-56`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L26-L56)) is either `Splits` (the answer, plus
  `sampled`, which says whether every split was tried) or `Impossible` with a `Reason`:
  too few players (under 4), too many (over 64), locks that can't be met, a lock on
  someone who isn't playing, or a duplicate id. **Why not throw?** These are normal
  user mistakes, so the app should show a message rather than crash. *Kotlin idea:
  `sealed interface`, see [learning.md](../learning.md#sealed-interface).*

#### The limits

[`Balancer.kt:86-98`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L86-L98): `EXHAUSTIVE_LIMIT = 100_000` (more splits than this and it
samples) and `MAX_PLAYERS = 64` (each split fits in one 64-bit number while sampling).
`SAMPLE_ATTEMPTS` caps the random draws at 400,000, and `DEFAULT_SEED` fixes the dice so
results repeat.

**Surprise:** `EXHAUSTIVE_MAX = 20` isn't used by any code. It records a promise that
follows from the limit: with no locks, 20 players is 92,378 splits, which is under
100,000, so 20 or fewer is always tried in full. A test holds the code to that promise
([`BalancerTest.kt:316-320`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/BalancerTest.kt#L316-L320)).

#### `balance(players, locks, k, random)`

[`Balancer.kt:125-146`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L125-L146). The steps:

1. `k` below 1 is a programming error, so it throws ([`Balancer.kt:131`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L131)). Every other
   problem comes back as `Impossible` ([`Balancer.kt:133-141`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L133-L141)).
2. **Players are sorted by id** ([`Balancer.kt:136`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L136)) and from then on referred to by
   position in that list. That makes the result independent of the order the app
   passed them in.
3. Build a `Problem` and count the splits the locks allow. Up to 100,000 it tries them
   all (`enumerate`), and above that it samples (`sample`) ([`Balancer.kt:143-145`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L143-L145)).

**Determinism:** same players, locks, `k` and seed give the same splits, in the same
order, with the same team labels. Ties in score keep the order they were found in.
*Kotlin idea: `random: Random = Random(DEFAULT_SEED)` is a default argument, evaluated
fresh on every call, so the default really is the same dice every time.*

**`impossible(reason)`** ([`Balancer.kt:148`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L148)) is a one-line shorthand. **`Candidate`**
([`Balancer.kt:151`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L151)) is a split that made the top `k`, with its score, a find-order
number for tie-breaks, and team A's positions.

#### `Problem`: the working state for one call

[`Balancer.kt:154-295`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L154-L295) is a private class that lives for one `balance` call.

- **`model`** ([`Balancer.kt:162`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L162)): built once with `WengLin.forGame(n)`, since every
  split has the same `n` players.
- **`lockedA`, `lockedB`** ([`Balancer.kt:164-165`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L164-L165)): positions pinned to each side.
- **`forcedA`** ([`Balancer.kt:172`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L172)): **the mirror trick.** Without locks, A-vs-B and
  B-vs-A are the same split, so player 0 (lowest id) is always put on team A and each
  split appears once. With locks, `forcedA` is just `lockedA`: the locks already fix
  which team is which.
- **`free`** ([`Balancer.kt:175`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L175)): everyone who may go either way.
- **`needs`** ([`Balancer.kt:183-185`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L183-L185)): how many free players must join team A. Even `n`
  has one answer. Odd `n` has two (the extra player on either side). If the locks make
  both impossible, the list is empty and `balance` reports `LOCKS_UNSATISFIABLE`.
- **`counts`, `totalSplits`** ([`Balancer.kt:188-191`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L188-L191)): how many splits each choice gives,
  and the total. They're doubles so a 64-player count can't overflow.
- **`best`** ([`Balancer.kt:194-196`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L194-L196)): a priority queue holding the best `k` so far, with
  the *worst* of them on top so it can be kicked out cheaply. Among equal scores, the
  latest find sits on top, so later finds lose ties. *Kotlin idea: `PriorityQueue` is
  Java's; Kotlin uses Java libraries directly.*
- **`onA`, `ratingsA`, `ratingsB`, `order`** ([`Balancer.kt:199-204`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L199-L204)): scratch space
  reused for every split, so the balancer doesn't build two new team lists for every
  split. (The model's `teamSum` still makes a sorted copy of each team.)

**`enumerate()`** ([`Balancer.kt:207-218`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L207-L218)) tries every split. For each team size, it
walks through every way to choose the free players for team A in dictionary order,
marks them in `onA`, scores the split, and unmarks them. *Kotlin idea: `do { } while`
runs the body before checking the condition.*

**`sample(random)`** ([`Balancer.kt:226-244`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L226-L244)) draws random splits until it has 100,000
distinct ones or has made 400,000 attempts. Each draw picks a team size in proportion to
how many splits it has (`chooseIndex`), then picks the free players for A with a partial
Fisher-Yates shuffle (shuffle just the first few positions), which makes every pick
equally likely ([`Balancer.kt:235-239`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L235-L239)). A `HashSet` of 64-bit masks skips repeats
([`Balancer.kt:241`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L241)). **The upshot:** every allowed split is equally likely to be tried.

**`chooseIndex(random)`** ([`Balancer.kt:247-255`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L247-L255)): picks a team-size option, weighted by
its split count. It's like throwing a dart at a line divided into those lengths.

**`teamMask()`** ([`Balancer.kt:258-262`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L258-L262)): team A as a 64-bit number, one bit per
player, so "have we seen this split?" is a cheap lookup.

**`offer()`** ([`Balancer.kt:265-278`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L265-L278)): scores the split marked in `onA` with
`predictDraw` and keeps it if it beats the worst of the current top `k`. **Note:** an
equal score doesn't get in ([`Balancer.kt:274`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L274)), which is what makes earlier finds win
ties.

**`finish()`** ([`Balancer.kt:281-294`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L281-L294)): sorts the survivors best first (ties by find
order), turns positions back into ids (each team in id order), and works out team A's
win chance for each.

#### The two helpers

- **`choose(n, r)`** ([`Balancer.kt:298-302`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L298-L302)): "n choose r" with doubles, rounded to the
  nearest whole number. It's exact for the sizes that decide exhaustive versus sampled.
- **`nextCombination(pick, size)`** ([`Balancer.kt:308-315`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L308-L315)): steps a sorted list of
  positions to the next combination in dictionary order, like an odometer where each
  digit must stay bigger than the one before. It returns `false` after the last one.

---

## 3. The tests

Run them with `./gradlew :engine:test`. There are two kinds: **fixture tests**, which
compare against openskill, and **behaviour tests**, which cover what the fixtures can't
(bad input, ordering, determinism).

**The harness, `FixtureSupport.kt`.** `close(actual, expected)`
([`FixtureSupport.kt:23-27`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/FixtureSupport.kt#L23-L27)) is the one tolerance rule, copied from the generator:
within 1e-9 absolute or relative, but relative only when the expected value is tiny.
`FixtureChecker` ([`FixtureSupport.kt:43-96`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/FixtureSupport.kt#L43-L96)) collects every comparison in a file
rather than stopping at the first failure. It reports the largest errors seen, and it
**fails if nothing was checked**, so an empty or misread fixture can't pass quietly
([`FixtureSupport.kt:85`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/FixtureSupport.kt#L85)). NaN counts as a failure and is kept out of the error stats
([`FixtureSupport.kt:53-57`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/FixtureSupport.kt#L53-L57)). The rest of the file is small JSON helpers
([`FixtureSupport.kt:100-135`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/FixtureSupport.kt#L100-L135)). *Kotlin idea: `val JsonElement.asDouble` adds a property
to someone else's type; these are called extension properties.*

**The count checks.** Every fixture test asserts how many cases it expects (87 points,
117 rate cases, 72 predictions, 70 rosters, 5 chains of 10 games), using `checkCount`
([`FixtureSupport.kt:70-72`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/FixtureSupport.kt#L70-L72)). If a fixture is regenerated with fewer cases, or a
section is renamed, the test fails instead of happily checking nothing.

**`FixtureSupportTest.kt`** tests the tester. It proves `close` rejects `0.0` for a tiny
tail value, and that the checker fails on a bad value, a NaN, a wrong count, a recorded
failure and an empty run ([`FixtureSupportTest.kt:11-68`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/FixtureSupportTest.kt#L11-L68)). A checker that couldn't fail
would make every other fixture test meaningless.

**`NormalFixtureTest.kt`** checks `erfc`, `cdf` and `pdf` at 87 points, including the
exact symmetry of `pdf` ([`NormalFixtureTest.kt:24-26`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/NormalFixtureTest.kt#L24-L26)), `invCdf` at 34 probabilities,
and `v`, `w`, `vt`, `wt` on over 2,200 rows. It also checks that every fallback branch
has at least one row ([`NormalFixtureTest.kt:75-76`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/NormalFixtureTest.kt#L75-L76)), so the risky code paths are
definitely exercised.

**`RateFixtureTest.kt`** checks 117 single games: every team shape from 2v2 to 8v8,
uneven teams, each outcome, plus extreme mismatches, tiny sigmas and the kappa floor.
It also checks our `betaFor` against the beta the fixture used.

**`ChainsFixtureTest.kt`** plays 5 chains of 10 games over a pool of 14 players,
carrying our own ratings forward and checking all 14 after every game. Errors would
compound here, so it catches slow drift that one game would hide.

**`PredictFixtureTest.kt`** checks win and draw chances for 72 rosters.

**`BalancerFixtureTest.kt`** runs the balancer on 70 rosters with `k` set huge, so every
split comes back, then checks the split count, the top five distinct scores, no
duplicates or mirrors, and that every lock holds. It compares **scores, not which split
won**, because near-ties can legitimately break differently.

**`EngineApiTest.kt`** covers the edges: bad `Rating` values, bad model settings, empty
teams on every entry point, the zero-variance refusal, zero-sigma predictions, the
summing-order quirk, `WengLin` inferring the player count, and fdlibm's special values
(NaN, ±∞, ±0).

**`PriorTest.kt`** pins down the endpoints, the even spacing, the one-player and
zero-player cases, and every branch and error of `insertBetween`.

**`ReplayTest.kt`** replays the chains fixture through `replay()` itself
([`ReplayTest.kt:23-49`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/ReplayTest.kt#L23-L49)), then checks that shuffled input gives identical results, the
tie-break order, history contents, and each validation error.

**`BalancerTest.kt`** brute-forces small rosters to prove the best split really has the
smallest mu gap ([`BalancerTest.kt:34-53`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/BalancerTest.kt#L34-L53)). It checks no mirrors or duplicates, locks
with odd `n`, determinism (same seed, shuffled input, repeat calls), the exhaustive and
sampled boundary (by split count, not roster size), that sampling picks team sizes in the
right proportions ([`BalancerTest.kt:291-301`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/BalancerTest.kt#L291-L301)), 64 and 65 players, and a timing smoke
test for 18 and 20 players that prints how long it took.

---

## 4. Questions you might ask

**Why not just use a rating library?** openskill is Python, so an Android app can't
call it, and TrueSkill, the best-known option, is patented and trademarked. The engine
also has a rule of zero runtime dependencies: less to audit, nothing to clash with
Android, and it can be reused elsewhere ([decisions.md](../decisions.md)). The model
itself is about 700 lines across five files, and the fixtures make every line checkable.

**Why copy a bug?** The fixtures come from what openskill *does*, so "fixing" the
tie-averaging step ([`ThurstoneMostellerFull.kt:96-99`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L96-L99)) would make every draw case
disagree. Then you couldn't tell a real porting mistake from a deliberate change. If we
ever want different behaviour, it should be a separate, documented change with its own
tests.

**Why are ratings never stored?** Because they can be derived. Store players and games,
and replay. Editing or deleting a game from three weeks ago then just means replaying,
with no stored number that could drift out of step with the history. Each game is a
few dozen sums, so replaying a group's whole history is cheap.

**Why does the balancer sort players by id?** Determinism. The same roster should give
the same answer whatever order the app passes it in. It also gives the mirror trick a
fixed "player 0" and makes ties break the same way every time.

**What happens with 25 players?** No locks gives 5,200,300 possible splits, well over
100,000, so it samples: 100,000 distinct splits chosen uniformly (about 2%), keeps the
best `k` and sets `sampled = true`. With so many near-identical splits, the best of a 2%
sample is very likely to be as good as makes no difference. Locks shrink the count, and
if they bring it under 100,000 it goes back to trying everything
([`BalancerTest.kt:269-288`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/BalancerTest.kt#L269-L288)).

**Does a player who's been away for months get less certain?** No. Tau is added once
per game *played* ([`ThurstoneMostellerFull.kt:80-82`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L80-L82)), not per week away, and `Game`
has no notion of gaps. Someone back after six months keeps their old sigma until their
next game.

**Why are some limits checked with `require` and others returned as `Impossible`?**
`require` is for programming mistakes, like `k = 0` or a game naming an unknown player,
which the app should never produce. `Impossible` is for things a user can do with a
normal tap: too few players, or a lock that can't be met.

**Why is a 6-v-5 game so lopsided in the predictions?** Team strength is a *sum*, so the
extra player counts as a full extra share. With equal new players the six get about 79%.
That's a known limitation in [model.md](../model.md). With 11 players the balancer
compensates by putting weaker players in the team of six, possibly more than real games
would justify.

**How would an iPhone version reuse this?** The engine is plain Kotlin with no Android
code, so the natural route is Kotlin Multiplatform: compile the same source for iOS as
well as Android. A handful of Java calls would need small replacements:
`StrictMath.exp`/`log` in `NormalDist`, `Math.ulp` ([`ThurstoneMostellerFull.kt:221`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L221)),
`Math.rint` ([`Balancer.kt:301`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L301)) and `java.util.PriorityQueue` ([`Balancer.kt:3`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt#L3)). The
`StrictMath` swap is the delicate one, since it's what guarantees identical bits. The
fixtures would come too, so an iOS build would be held to the same 1e-9 standard.
