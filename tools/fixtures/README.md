# Reference fixtures

The Kotlin rating engine is a port of the Weng-Lin Thurstone-Mosteller "full
pairing" model from the Python package `openskill`, pinned to **6.2.0**. This
folder generates the JSON files the Kotlin tests check against.

**The fixtures are the source of truth. If Kotlin and a fixture disagree, the
Kotlin is wrong.** Don't loosen a tolerance or edit a fixture to make a test pass.

## Regenerate

Two hard requirements, both checked by `generate.py` (it refuses to run otherwise):

- **Python 3.14 or newer.** On 3.14, `NormalDist.cdf(x)` is `0.5 * erfc(-x / sqrt(2))`.
  Python 3.13 and older compute `0.5 * (1 + erf(x / sqrt(2)))`, which gives different
  values and loses the lower tail. **That 3.14 form is the one the Kotlin
  equivalent of `phi_major`, `NormalDist.cdf`, must copy.**
- **Pure-Python openskill.** PyPI's cp310-cp313 wheels are compiled with mypyc,
  which bypasses the generator's branch counters and ulp checks. Install from the
  sdist with `--no-binary`.

From the repo root:

```sh
python3.14 -m venv tools/fixtures/.venv         # once; git-ignored
tools/fixtures/.venv/bin/pip install --require-hashes --no-binary openskill -r tools/fixtures/requirements.txt
tools/fixtures/.venv/bin/python tools/fixtures/generate.py
```

(Python 3.14 has no compiled openskill wheel, so plain `--require-hashes` also gives
the pure-Python build there. `--no-binary` makes it explicit. It works with the
hashes because the sdist's hash is in `requirements.txt`.)

`requirements.txt` pins openskill 6.2.0 by sha256 for every wheel and the sdist
on PyPI (openskill has no dependencies). Output goes to
`engine/src/test/resources/fixtures/`. The generator is deterministic (fixed
seeds, `random.Random(seed)` only, no timestamps, no Python version in the
output), so a re-run gives byte-identical files. If `git diff` shows changes
after a re-run, openskill or Python's `statistics`/`math` changed underneath you;
investigate before committing. The generator also asserts that every fallback
branch of `v`/`w`/`vt`/`wt` is covered, and that `rate.json` hits the kappa floor,
and prints the counts. Coverage is counted on the cases that survive filtering.

## Tolerance

Floats are written with Python's default `json` serialisation (round-trip
`repr`), so they parse back to the exact doubles. Compare them with this one rule
(`close(a, e)` in `generate.py`; `a` = actual, `e` = expected):

```
d = |a - e|
if |e| < 1e-9:   pass iff d <= 1e-9 * |e|            (relative only)
else:            pass iff d <= 1e-9  or  d <= 1e-9 * |e|
```

The generator also rejects fixture points that would fail a correct port. Every
`normal.json` `v`/`w`/`vt`/`wt` row, every `rate.json` and `predict.json` case, every
`chains.json` chain and every `balancer.json` roster is recomputed under 26
perturbations of `phi_major`, `phi_minor` and `phi_major_inverse` by one ulp
(`math.nextafter`), and is dropped (or, for chains and rosters, re-drawn) if the
result moves outside `close()`:

- 10 uniform ones (every combination of -1/0/+1 on major and minor, plus inverse
  only -1 and +1), and
- 16 seeded random ones, where each distinct `(function, |argument|)` gets its own
  sign from {-1, 0, +1}. This breaks cancellation in differences like
  `cdf(u) - cdf(v)`; calls with the same `|argument|` stay equal.
- Saturated values are never nudged: an exact `0.0`, or a `1.0` where the upper
  tail `erfc(x/sqrt(2)) < 2**-53`. Any other `1.0` may only move down. A cdf value
  is never pushed above 1.0 or below 0.0.

Also dropped: subnormal expected values (0 < |e| < 2.2250738585072014e-308) and
values that underflow to exactly 0 (these would demand bit-exact underflow). Not
dropped: the constant results of the `w`/`wt` fallbacks (0 or 1) and `vt(0, t)`,
which is an exact symmetric zero. Drop and re-draw counts are in each file's
`meta.dropped`.

A plain absolute 1e-9 would let you return `0.0` for any tail value (such as
`pdf(8)`, about 5e-15), so tiny expected values need a relative match. If
`e == 0.0` the actual must be exactly `0.0`.

## Engine configuration

- `ThurstoneMostellerFull`, package defaults: mu 25, sigma 25/3, tau 25/300.
- For a game with `n` players in total, `beta = (25/6) * sqrt(n/2)`. Each case
  records `n` and `beta` so the Kotlin side can check its own formula.
- Always two teams. `ranks` is `[0,1]` (team A won), `[1,0]` (B won) or `[0,0]`
  (draw). Scores are never used.
- Predictions use `predict_win(teams)` and `predict_draw(teams)` on the model for
  that `n`. `predict_draw` uses a built-in draw probability of `1/n` (not
  configurable), so its draw margin uses `inv_cdf((1 + 1/n)/2)`.

Every file starts with a `meta` block: `openskill`, `seed`, `generator`, `file`,
`description`.

Player ids are `p01`, `p02`, ... and are not real people.
A *rating* is `{"id": "p01", "mu": 25.0, "sigma": 8.33...}`.

## The files

### `normal.json`
The normal-distribution primitives openskill relies on. Checked against
`openskill/models/weng_lin/common.py` in 6.2.0: `phi_major` is
`NormalDist().cdf`, `phi_minor` is `NormalDist().pdf`, and `phi_major_inverse`
is `NormalDist().inv_cdf`. `erfc` is `math.erfc` (not used by openskill itself;
included for the Kotlin implementation of the primitives).

- `points`: `{x, erfc, cdf, pdf}` for x from -10 to 10 in steps of 0.25, plus
  1e-8, 0.5e-3 and 37.5 and their negatives (87 points). Extreme tails
  underflow: `erfc(37.5)` is `0.0`, `erfc(-37.5)` is `2.0`, `cdf(37.5)` is `1.0`.
- `pdf` must be bit-symmetric (`pdf(x) == pdf(-x)` exactly): compute it from `x*x`,
  not from `x` with a sign, because `vt(0, t)` expects exactly `0.0` (it is
  `pdf(-t) - pdf(t)`).
- `inv_cdf`: `{p, value}` for 17 probabilities from 1e-12 to 1-1e-12.
- `inv_cdf_draw`: `{n, p, value}` for n = 4..20 with `p = (1 + 1/n) / 2`
  (computed in that order in doubles). This is what `predict_draw` evaluates.
- `v`, `w`, `vt`, `wt`: openskill's own helper functions, each a list of
  `{x, t, value, branch}`. Signature is `f(x, t)` for all four.
  - `v(x,t)`: `xt = x-t`; `cdf(xt) < eps` (eps = 2.220446049250313e-16, i.e.
    `sys.float_info.epsilon`) gives `-xt`, else `pdf(xt)/cdf(xt)`. Branches: `main`, `fallback`.
  - `w(x,t)`: same test; fallback returns `1` if `x < 0` else `0`, else
    `v*(v+xt)`. Branches: `main`, `fallback_x_neg`, `fallback_x_nonneg`.
  - `vt(x,t)`: `xx = |x|`, `b = cdf(t-xx) - cdf(-t-xx)`; `b < 1e-5` gives `-x-t`
    if `x < 0` else `-x+t`. Branches: `main`, `fallback_x_neg`, `fallback_x_nonneg`.
  - `wt(x,t)`: same `b`; `b < eps` gives `1.0`. Branches: `main`, `fallback`.
  - Grids: x in -40..40 step 1 and -10..10 step 0.25. t is 0, 0.5, 2, 10 for
    `v`/`w` and 0.01, 0.1, 0.5, 2 for `vt`/`wt` (t below about 1e-6 is dropped:
    `b` is then a difference of two numbers near 0.5 and is float noise). Points
    that fail the ulp or underflow checks above are dropped (see `meta.dropped`; 12
    rows, all `v`/`w` at large positive x where the pdf is subnormal or zero).
    The generator asserts every branch still appears after filtering. `w:fallback_x_nonneg` needs t > 8.3, which
    the rating code never produces (its t is about 0.01), so only this file covers it.
  - Kotlin must reproduce the branch conditions exactly, since a point near a
    threshold would otherwise flip branch and differ wildly.

### `rate.json`
`cases`: single updates, each with `name`, `rating_set`, `outcome`, `n`, `beta`,
`ranks`, `teams` (input, `[teamA, teamB]` of ratings) and `result` (output, same
shape and order).

- Shapes: 2v2, 3v3 to 8v8; A smaller: 2v3, 3v4, 4v5, 5v6, 6v7, 7v8; A larger:
  3v2, 5v4, 6v5. Each with outcomes `a`, `b`, `draw` and two rating sets:
  `default` (all 25 / 8.33) and `random` (mu in [10,40], sigma in [1, 25/3]).
- `extreme`: 5v5 mu 5 vs 45 (sigma 1), 5v5 mu -100 vs 150, 3v3 and 8v8 at 5 vs 45.
  These hit the `v`/`w`/`vt`/`wt` fallback branches.
- `tinysigma`: 5v5 sigma 0.5, equal mu and 20 vs 30.
- `kappa`: `[(15, sigma 1000), (30, 1)]` vs `[(15, 0.1)]`. Hits openskill's sigma
  floor (`kappa` = 0.0001: new sigma is `sigma * sqrt(max(1 - ..., kappa))`) in
  the draw case. This only occurs with a huge-sigma player in a lopsided team; a random search
  with sigma up to 1000 in 5v5 and sigma up to 200 in 2-6 a side never triggered
  it, so it can't happen in normal app use. It is a safety-net case.

### `chains.json`
- `chains`: 5 entries. Each has `name`, `initial` (14 ratings, all default) and
  `games` (10 entries).
- Game fields: `teams` (`[[ids], [ids]]`, each team 4 to 6, sizes drawn
  independently so games can be uneven), `outcome`, `ranks`, `n`, `beta`, and
  `ratings_after` (the full pool of 14 ratings after the game, in pool order).
  Players keep their ratings between games, so errors compound; this tests drift.
  Each chain passes the ulp robustness check as a whole (no chain needed re-drawing).

### `predict.json`
- `cases`: 72. 64 random rosters cycling through all rate shapes, then all-default
  (5v5, 4v5, 5v4, 3v3, 2v3), two large-gap cases (`predict-largegap-5v5-17-vs-33`,
  `predict-largegap-4v5-19-vs-31`), three extreme mismatches (5 vs 45 in 5v5 and 4v5,
  -100 vs 150; all three are dropped, see below) and tiny sigma. 75 are generated, 72 kept.
- Fields: `name`, `n`, `beta`, `teams` (ratings), `win` (`[P(A), P(B)]`), `draw`.
- Dropped for underflow: `predict-extreme-5v5-5-vs-45`, `predict-extreme-4v5-5-vs-45`
  and `predict-extreme-5v5-m100-vs-150`. Their `win[0]` is about 1e-49 or 1e-57, but
  `draw` is exactly `0.0` from cancellation (`1.0 - 1.0`), so the case would demand a
  bit-exact zero. The mismatch cases kept are `largegap-5v5-17-vs-33` and
  `largegap-4v5-19-vs-31`, the largest gaps whose `draw` survives the ulp checks
  (beyond that `draw` is cancellation noise). Remaining random cases may have small
  `win` values (down to about 4e-10 here); they need relative accuracy in the normal tail.

### `balancer.json`
Reference for the team balancer: every possible split, scored with
`predict_draw` on the model for `n`. Higher means a more even match.

- `cases`: 70. Random rosters without locks (40, n = 4 to 14 cycling), all-default
  ratings (n = 6, 7, 8, 9, 13), rosters with duplicated ratings (three distinct
  values, n = 6 to 11), random locks (15, n = 4 to 14), and `{"p01": "b"}` /
  `{"p01": "a"}` single locks at n = 6, 8, 10.
- Fields: `name`, `n`, `beta`, `players` (ratings), `locks` (`{"p03": "a"}`,
  empty if none), `splits_considered`, `top5_draw`, `best_team_a`.
- Splits are teams of floor(n/2) and ceil(n/2). For odd n the smaller team is
  chosen as a subset. Without locks a split and its mirror count once.
- Lock generation: 1 to 3 locks (never more than n-2), resampled until at least
  2 valid splits exist; every case has `splits_considered >= 2`.
- `top5_draw`: take all valid split values sorted highest first; keep a value
  only if `last_kept - value > 1e-9`; stop at five. (Greedy filter against the
  last kept value.) The generator asserts no gap it compares (accepted or rejected)
  falls in (1e-12, 1e-7), so one-ulp noise can't flip a dedupe decision; a roster
  that violates this, or whose top 5 moves under the ulp checks, is re-drawn
  (10 re-draws in total, listed in `meta.dropped`; the random stream moves on, so
  later rosters differ from any earlier version of this file). Fewer than five entries means there weren't five distinct
  values; e.g. n = 4 has only 3 splits and all-default rosters have 1.
- `best_team_a`: ids of team A in one best split.
- With locks, only splits that respect them count, and orientation matters: a
  player locked to `"a"` must be on the team you call A. For these cases every
  split is enumerated in both orientations, and for odd n team A may be either the
  smaller or the larger team.

**Compare quality values, not split identity.** Ties and near-ties can break
differently in Kotlin (different enumeration order, float noise), so check
`top5_draw[i]` with the tolerance rule above, and that your best split's quality
matches `top5_draw[0]`. Don't require `best_team_a` to match exactly.
