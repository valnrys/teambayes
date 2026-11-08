#!/usr/bin/env python3
"""Generate the TeamBayes reference fixtures from openskill 6.2.0.

Usage (from anywhere):  tools/fixtures/.venv/bin/python tools/fixtures/generate.py

Deterministic: fixed seeds, random.Random(seed) only, no timestamps.
Writes JSON into engine/src/test/resources/fixtures/.
"""
import contextlib
import itertools
import json
import math
import random
import sys
from collections import Counter, defaultdict
from importlib.metadata import version
from pathlib import Path
from statistics import NormalDist

import openskill.models.weng_lin.common as wl
import openskill.models.weng_lin.thurstone_mosteller_full as tmf
from openskill.models import ThurstoneMostellerFull

# The fixtures depend on both of these; refuse to run otherwise.
#  - Python >= 3.14: NormalDist.cdf is 0.5*erfc(-x/sqrt(2)) there. Older versions use
#    0.5*(1+erf(x/sqrt(2))), which gives different values (and loses the lower tail).
#  - Pure-Python openskill: the cp3xx wheels are compiled (mypyc) and would bypass the
#    monkeypatched branch counters and ulp perturbation below.
if sys.version_info < (3, 14):
    raise SystemExit("generate.py needs Python >= 3.14 (see tools/fixtures/README.md)")
if not tmf.__file__.endswith(".py"):
    raise SystemExit("openskill is a compiled build (" + tmf.__file__ + "); install the "
                     "pure-Python one: pip install --no-binary openskill (see README)")

GENERATOR = "tools/fixtures/generate.py"
OUT = Path(__file__).resolve().parents[2] / "engine/src/test/resources/fixtures"

MU0, SIGMA0, TAU0 = 25.0, 25.0 / 3.0, 25.0 / 300.0
OUTCOMES = {"a": [0, 1], "b": [1, 0], "draw": [0, 0]}

SEEDS = {"normal": 0, "rate": 20261101, "chains": 20261102,
         "predict": 20261103, "balancer": 20261104}

_models = {}


def beta_for(n):
    return (25.0 / 6.0) * math.sqrt(n / 2)


def model(n):
    if n not in _models:
        _models[n] = ThurstoneMostellerFull(mu=MU0, sigma=SIGMA0, tau=TAU0, beta=beta_for(n))
    return _models[n]


def pid(i):
    return f"p{i:02d}"


EPS = sys.float_info.epsilon
_NORMAL = NormalDist()

# Shapes (team A size, team B size) shared by rate and predict.
SHAPES = ([(k, k) for k in range(2, 9)]
          + [(2, 3), (3, 4), (4, 5), (5, 6), (6, 7), (7, 8)]      # A smaller
          + [(3, 2), (5, 4), (6, 5)])                             # A larger


def close(a, e):
    """The one tolerance rule (copy this into the Kotlin tests).

    Pass if |a-e| <= 1e-9 or |a-e| <= 1e-9*|e|, except when |e| < 1e-9, where
    only the relative test counts (so tiny tail values can't be faked with 0.0).
    """
    d = abs(a - e)
    if abs(e) < 1e-9:
        return d <= 1e-9 * abs(e)
    return d <= 1e-9 or d <= 1e-9 * abs(e)


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


# ---- branch bookkeeping for openskill's v / w / vt / wt -----------------------
def v_branch(x, t):
    return "fallback" if _NORMAL.cdf(x - t) < EPS else "main"


def w_branch(x, t):
    if _NORMAL.cdf(x - t) < EPS:
        return "fallback_x_neg" if x < 0 else "fallback_x_nonneg"
    return "main"


def vt_branch(x, t):
    xx = abs(x)
    if _NORMAL.cdf(t - xx) - _NORMAL.cdf(-t - xx) < 1e-5:
        return "fallback_x_neg" if x < 0 else "fallback_x_nonneg"
    return "main"


def wt_branch(x, t):
    xx = abs(x)
    return "fallback" if _NORMAL.cdf(t - xx) - _NORMAL.cdf(-t - xx) < EPS else "main"


BRANCHES = {
    "v": (v_branch, ["fallback", "main"]),
    "w": (w_branch, ["fallback_x_neg", "fallback_x_nonneg", "main"]),
    "vt": (vt_branch, ["fallback_x_neg", "fallback_x_nonneg", "main"]),
    "wt": (wt_branch, ["fallback", "main"]),
}

HITS = defaultdict(Counter)   # phase -> Counter of "fn:branch"
KAPPA_HITS = Counter()        # phase -> times the kappa sigma floor applied
_phase = ["none"]


def _instrument():
    """Wrap v/w/vt/wt (as imported into the model module) and max (for kappa)."""
    for name, (branch, _) in BRANCHES.items():
        orig = getattr(tmf, name)

        def wrapper(x, t, _o=orig, _n=name, _b=branch):
            HITS[_phase[0]][f"{_n}:{_b(x, t)}"] += 1
            return _o(x, t)

        setattr(tmf, name, wrapper)

    def spy_max(*args):
        if len(args) == 2 and args[1] == 0.0001 and args[0] < args[1]:
            KAPPA_HITS[_phase[0]] += 1
        return max(*args)

    tmf.max = spy_max


@contextlib.contextmanager
def phase(name):
    old = _phase[0]
    _phase[0] = name
    try:
        yield
    finally:
        _phase[0] = old


class Perturbation:
    """One way of nudging phi_major, phi_minor and phi_major_inverse by one ulp.

    Uniform: every call of a function gets the same sign (-1, 0 or +1).
    Random: each distinct (function, |argument|) gets its own sign from a seeded rng, so
    the two cdf values in a difference like cdf(u) - cdf(v) are nudged independently,
    while symmetric calls (same |argument|) stay equal.
    Saturated values are never nudged: an exact 0.0, or a 1.0 where the upper tail
    erfc(x/sqrt(2)) < 2**-53 (1.0 is then the correctly rounded value). Any other
    1.0 may only move down. A cdf value is never pushed above 1.0 or below 0.0.
    """

    def __init__(self, uniform=None, seed=None):
        self.uniform, self.rng, self.signs = uniform, random.Random(seed), {}

    def sign(self, kind, x):
        if self.uniform is not None:
            return self.uniform[kind]
        key = (kind, abs(x))
        if key not in self.signs:
            self.signs[key] = self.rng.choice((-1, 0, 1))
        return self.signs[key]

    @staticmethod
    def nudge(val, sg):
        if sg == 0 or val == 0.0:
            return val
        return math.nextafter(val, math.inf if sg > 0 else -math.inf)

    def major(self, orig, x):
        val = orig(x)
        if val == 1.0:
            if math.erfc(x / math.sqrt(2)) < 2.0 ** -53:
                return val                      # truly saturated: 1.0 is the exact rounding
            return self.nudge(val, min(0, self.sign("major", x)))   # never above 1.0
        return min(1.0, max(0.0, self.nudge(val, self.sign("major", x))))

    def minor(self, orig, x):
        return self.nudge(orig(x), self.sign("minor", x))

    def inverse(self, orig, p):
        return self.nudge(orig(p), self.sign("inverse", p))


@contextlib.contextmanager
def perturbed(p):
    saved = (wl.phi_major, wl.phi_minor, wl.phi_major_inverse,
             tmf.phi_major, tmf.phi_major_inverse)
    o_major, o_minor, o_inv = saved[0], saved[1], saved[2]
    wl.phi_major = lambda x: p.major(o_major, x)
    wl.phi_minor = lambda x: p.minor(o_minor, x)
    wl.phi_major_inverse = lambda q: p.inverse(o_inv, q)
    tmf.phi_major = wl.phi_major
    tmf.phi_major_inverse = wl.phi_major_inverse
    try:
        yield
    finally:
        (wl.phi_major, wl.phi_minor, wl.phi_major_inverse,
         tmf.phi_major, tmf.phi_major_inverse) = saved


def _uniform(major=0, minor=0, inverse=0):
    return {"major": major, "minor": minor, "inverse": inverse}


# 8 uniform (major x minor), 2 inverse-only, 16 seeded random assignments.
UNIFORM_MODES = ([_uniform(a, b) for a in (-1, 0, 1) for b in (-1, 0, 1) if (a, b) != (0, 0)]
                 + [_uniform(inverse=-1), _uniform(inverse=1)])
RANDOM_SEEDS = list(range(1000, 1016))
SUBNORMAL = 2.2250738585072014e-308


def perturbations(n_random=len(RANDOM_SEEDS)):
    for u in UNIFORM_MODES:
        yield Perturbation(uniform=u)
    for sd in RANDOM_SEEDS[:n_random]:
        yield Perturbation(seed=sd)


def stable(compute, n_random=len(RANDOM_SEEDS)):
    """(ok, base): ok if compute() stays within close() of its unperturbed value, with
    the same length, under every perturbation. compute returns a number or a list."""
    with phase("robustness"):
        base = compute()
        for p in perturbations(n_random):
            with perturbed(p):
                got = compute()
            if isinstance(base, list):
                if len(got) != len(base) or not all(close(g, e) for g, e in zip(got, base)):
                    return False, base
            elif not close(got, base):
                return False, base
    return True, base


def underflow_reason(values):
    """'subnormal' / 'underflow_to_zero' if any value would force bit-exact underflow."""
    for v in values:
        if 0 < abs(v) < SUBNORMAL:
            return "subnormal"
    for v in values:
        if v == 0.0:
            return "underflow_to_zero"
    return None


def meta(name, seed, description, extra=None):
    m = _meta(name, seed, description)
    if extra:
        m["dropped"] = extra
    return m


def _meta(name, seed, description):
    return {
        "openskill": version("openskill"),
        "seed": seed,
        "generator": GENERATOR,
        "file": name,
        "description": description,
    }


def write(name, seed, description, body, dropped=None):
    doc = {"meta": meta(name, seed, description, dropped)}
    doc.update(body)
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / name, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")


def rand_rating(rng):
    return rng.uniform(10.0, 40.0), rng.uniform(1.0, SIGMA0)


def team_json(ids, ratings):
    return [{"id": i, "mu": r[0], "sigma": r[1]} for i, r in zip(ids, ratings)]


def make_teams(m, spec):
    """spec: list of teams, each a list of (mu, sigma) -> openskill rating objects."""
    return [[m.rating(mu=mu, sigma=sigma) for mu, sigma in t] for t in spec]


def do_rate(spec, ranks):
    n = sum(len(t) for t in spec)
    m = model(n)
    out = m.rate(make_teams(m, spec), ranks=ranks)
    return [[(r.mu, r.sigma) for r in t] for t in out]


# ---------------------------------------------------------------- normal.json
def _grid():
    return sorted(set([float(i) for i in range(-40, 41)] + [k * 0.25 for k in range(-40, 41)]))


def gen_normal():
    nd = _NORMAL
    xs = [-10 + 0.25 * k for k in range(81)]
    xs += [1e-8, -1e-8, 0.5e-3, -0.5e-3, 37.5, -37.5]
    xs = sorted(set(xs))
    ps = [1e-12, 1e-9, 1e-6, 1e-3, 0.01, 0.025, 0.1, 0.3, 0.5, 0.7, 0.9,
          0.975, 0.99, 0.999, 1 - 1e-6, 1 - 1e-9, 1 - 1e-12]
    # What predict_draw feeds to inv_cdf: (1 + 1/n) / 2, with n the total player count.
    draw_ps = [{"n": n, "p": (1 + 1 / n) / 2, "value": nd.inv_cdf((1 + 1 / n) / 2)}
               for n in range(4, 21)]

    fns = {"v": wl.v, "w": wl.w, "vt": wl.vt, "wt": wl.wt}
    ts = {"v": [0.0, 0.5, 2.0, 10.0], "w": [0.0, 0.5, 2.0, 10.0],
          "vt": [0.01, 0.1, 0.5, 2.0], "wt": [0.01, 0.1, 0.5, 2.0]}
    ext = {}
    seen = defaultdict(Counter)
    dropped = Counter()
    constant_branches = {"w:fallback_x_neg", "w:fallback_x_nonneg", "wt:fallback"}
    for name, fn in fns.items():
        rows = []
        for t in ts[name]:
            for x in _grid():
                br = BRANCHES[name][0](x, t)
                ok, val = stable(lambda: float(fn(x, t)))
                reason = underflow_reason([val])
                if reason == "underflow_to_zero" and (
                        f"{name}:{br}" in constant_branches       # branch constants 0 / 1
                        or (name == "vt" and x == 0.0)):          # exact symmetric zero
                    reason = None
                if reason:
                    dropped[f"{name}:{reason}"] += 1
                elif not ok:
                    dropped[f"{name}:ill_conditioned"] += 1
                else:
                    seen[name][br] += 1
                    rows.append({"x": x, "t": t, "value": val, "branch": br})
        ext[name] = rows
    for name, (_, required) in BRANCHES.items():
        for br in required:
            check(seen[name][br] > 0, f"normal.json never hits {name}:{br}")
    # The fallback formulas, restated, must agree with what openskill returned.
    for r in ext["v"]:
        if r["branch"] == "fallback":
            check(close(r["value"], -(r["x"] - r["t"])), "v fallback formula")
    for r in ext["vt"]:
        if r["branch"] != "main":
            check(close(r["value"], (-r["x"] - r["t"]) if r["x"] < 0 else (-r["x"] + r["t"])),
                  "vt fallback formula")
    for r in ext["wt"]:
        if r["branch"] == "fallback":
            check(r["value"] == 1.0, "wt fallback value")

    body = {
        "points": [{"x": x, "erfc": math.erfc(x), "cdf": nd.cdf(x), "pdf": nd.pdf(x)} for x in xs],
        "inv_cdf": [{"p": p, "value": nd.inv_cdf(p)} for p in ps],
        "inv_cdf_draw": draw_ps,
        "v": ext["v"], "w": ext["w"], "vt": ext["vt"], "wt": ext["wt"],
    }
    # self-checks with the stated tolerance
    for pt in body["points"]:
        check(close(pt["cdf"], 0.5 * math.erfc(-pt["x"] / math.sqrt(2))), f"cdf vs erfc at {pt['x']}")
        check(close(pt["pdf"], math.exp(-pt["x"] ** 2 / 2) / math.sqrt(2 * math.pi)), f"pdf at {pt['x']}")
    for q in body["inv_cdf"]:
        check(close(nd.cdf(q["value"]), q["p"]), f"cdf(inv_cdf({q['p']}))")
    for pt in body["points"]:
        for key in ("erfc", "cdf", "pdf"):
            check(not (0 < abs(pt[key]) < SUBNORMAL), f"subnormal {key} at {pt['x']}")
    print("normal.json dropped points:", dict(dropped), "total", sum(dropped.values()))
    write("normal.json", SEEDS["normal"],
          "Normal primitives (erfc, cdf, pdf, inv_cdf), inv_cdf at draw-margin probabilities, "
          "and openskill's v/w/vt/wt with the branch each point hits.",
          body, dict(dropped, total=sum(dropped.values())))
    return {"points": len(xs), "inv_cdf": len(ps), "inv_cdf_draw": len(draw_ps),
            "v": len(ext["v"]), "w": len(ext["w"]), "vt": len(ext["vt"]), "wt": len(ext["wt"])}


# ------------------------------------------------------------------ rate.json
def _case(name, rating_set, outcome, spec):
    na, nb = len(spec[0]), len(spec[1])
    n = na + nb
    ids_a = [pid(i + 1) for i in range(na)]
    ids_b = [pid(na + i + 1) for i in range(nb)]
    ranks = OUTCOMES[outcome]
    with phase("build"):
        out = do_rate(spec, ranks)
    return {
        "name": name,
        "rating_set": rating_set,
        "outcome": outcome,
        "n": n,
        "beta": beta_for(n),
        "ranks": ranks,
        "teams": [team_json(ids_a, spec[0]), team_json(ids_b, spec[1])],
        "result": [team_json(ids_a, out[0]), team_json(ids_b, out[1])],
    }


def gen_rate():
    seed = SEEDS["rate"]
    rng = random.Random(seed)
    cases = []
    for rating_set in ("default", "random"):
        for na, nb in SHAPES:
            for outcome in ("a", "b", "draw"):
                if rating_set == "default":
                    spec = [[(MU0, SIGMA0)] * na, [(MU0, SIGMA0)] * nb]
                else:
                    spec = [[rand_rating(rng) for _ in range(na)],
                            [rand_rating(rng) for _ in range(nb)]]
                cases.append(_case(f"{na}v{nb}-{rating_set}-{outcome}", rating_set, outcome, spec))

    def uniform(k, mu, sigma):
        return [(mu, sigma)] * k

    extreme = [
        ("extreme-5v5-5-vs-45", 5, 5, (5.0, 1.0), (45.0, 1.0)),
        ("extreme-5v5-m100-vs-150", 5, 5, (-100.0, 1.0), (150.0, 1.0)),
        ("extreme-3v3-5-vs-45", 3, 3, (5.0, 1.0), (45.0, 1.0)),
        ("extreme-8v8-5-vs-45", 8, 8, (5.0, 1.0), (45.0, 1.0)),
    ]
    for label, na, nb, ra, rb in extreme:
        for outcome in ("a", "b", "draw"):
            cases.append(_case(f"{label}-{outcome}", "extreme", outcome,
                               [uniform(na, *ra), uniform(nb, *rb)]))
    for label, ra, rb in [("tinysigma-5v5-equal", (25.0, 0.5), (25.0, 0.5)),
                          ("tinysigma-5v5-20-vs-30", (20.0, 0.5), (30.0, 0.5))]:
        for outcome in ("a", "b", "draw"):
            cases.append(_case(f"{label}-{outcome}", "tinysigma", outcome,
                               [uniform(5, *ra), uniform(5, *rb)]))
    # Kappa sigma floor: only reachable with a huge-sigma player in a lopsided team
    # (not a realistic app state; see README). Needs a draw.
    for outcome in ("a", "b", "draw"):
        cases.append(_case(f"kappa-2v1-{outcome}", "kappa", outcome,
                           [[(15.0, 1000.0), (30.0, 1.0)], [(15.0, 0.1)]]))

    # Drop ill-conditioned cases (and any with subnormal / zero outputs).
    dropped = {}
    kept = []
    for c in cases:
        spec = [[(p["mu"], p["sigma"]) for p in t] for t in c["teams"]]
        ok, vals = stable(lambda: [x for t in do_rate(spec, c["ranks"]) for r in t for x in r])
        reason = underflow_reason(vals) or (None if ok else "ill_conditioned")
        if reason:
            dropped[c["name"]] = reason
        else:
            kept.append(c)
    cases = kept
    print("rate.json dropped cases:", dropped or "none")

    # Coverage is counted on the surviving cases only.
    HITS["rate"].clear()
    KAPPA_HITS["rate"] = 0
    with phase("rate"):
        for c in cases:
            do_rate([[(p["mu"], p["sigma"]) for p in t] for t in c["teams"]], c["ranks"])
    h = HITS["rate"]
    for key in ("v:fallback", "w:fallback_x_neg", "vt:fallback_x_neg", "vt:fallback_x_nonneg", "wt:fallback"):
        check(h[key] > 0, f"rate.json never hits {key}")
    check(KAPPA_HITS["rate"] > 0, "rate.json never hits the kappa floor")
    # w:fallback_x_nonneg (x >= 0 while x - t < -8.3) needs t > 8.3; t = epsilon/c is ~0.01, so
    # the rating code can never reach it; it is covered in normal.json only.

    write("rate.json", seed,
          "Single two-team rating updates (ThurstoneMostellerFull, ranks only): shapes, extremes, tiny sigma, kappa floor.",
          {"cases": cases}, dict(dropped, total=len(dropped)))
    with phase("selfcheck"):          # recompute from the recorded inputs
        for c in cases:
            out = do_rate([[(p["mu"], p["sigma"]) for p in t] for t in c["teams"]], c["ranks"])
            for tw, to in zip(c["result"], out):
                for p, (mu, sg) in zip(tw, to):
                    check(close(mu, p["mu"]) and close(sg, p["sigma"]), c["name"])
    return len(cases)


# ---------------------------------------------------------------- chains.json
def _chain_plan(rng, pool):
    plan = []
    for _ in range(10):
        sa, sb = rng.randint(4, 6), rng.randint(4, 6)
        chosen = rng.sample(pool, sa + sb)
        plan.append((chosen[:sa], chosen[sa:], rng.choice(["a", "b", "draw"])))
    return plan


def _chain_play(plan, pool):
    """Play a plan from default ratings. Returns (games, flat list of every rating after every game)."""
    state = {p: (MU0, SIGMA0) for p in pool}
    games, flat = [], []
    for a, b, outcome in plan:
        ranks = OUTCOMES[outcome]
        out = do_rate([[state[p] for p in a], [state[p] for p in b]], ranks)
        for p, r in zip(a, out[0]):
            state[p] = r
        for p, r in zip(b, out[1]):
            state[p] = r
        n = len(a) + len(b)
        games.append({
            "teams": [a, b], "outcome": outcome, "ranks": ranks, "n": n, "beta": beta_for(n),
            "ratings_after": [{"id": p, "mu": state[p][0], "sigma": state[p][1]} for p in pool],
        })
        flat += [x for p in pool for x in state[p]]
    return games, flat


def gen_chains():
    seed = SEEDS["chains"]
    rng = random.Random(seed)
    pool = [pid(i) for i in range(1, 15)]
    initial = [{"id": p, "mu": MU0, "sigma": SIGMA0} for p in pool]
    chains, rerolled = [], 0
    for c in range(5):
        while True:
            plan = _chain_plan(rng, pool)
            with phase("build"):
                ok, flat = stable(lambda: _chain_play(plan, pool)[1])
            if ok and not underflow_reason(flat):
                break
            rerolled += 1                      # deterministic: the rng just moves on
        with phase("chains"):
            games, _ = _chain_play(plan, pool)
        chains.append({"name": f"chain-{c + 1}", "initial": initial, "games": games})
    print("chains.json chains regenerated for instability:", rerolled)
    write("chains.json", seed,
          "5 chains of 10 sequential games over a pool of 14 players from default ratings; teams of 4-6 drawn independently.",
          {"chains": chains}, {"rerolled_chains": rerolled})
    return len(chains)


# --------------------------------------------------------------- predict.json
def gen_predict():
    seed = SEEDS["predict"]
    rng = random.Random(seed)
    cases = []

    def add(name, spec):
        na, nb = len(spec[0]), len(spec[1])
        n = na + nb
        ids_a = [pid(j + 1) for j in range(na)]
        ids_b = [pid(na + j + 1) for j in range(nb)]
        m = model(n)
        teams = make_teams(m, spec)
        with phase("predict"):
            pw = m.predict_win(teams)
        cases.append({
            "name": name,
            "n": n,
            "beta": beta_for(n),
            "teams": [team_json(ids_a, spec[0]), team_json(ids_b, spec[1])],
            "win": [pw[0], pw[1]],
            "draw": m.predict_draw(teams),
        })

    for i in range(64):
        na, nb = SHAPES[i % len(SHAPES)]
        add(f"predict-{i + 1:02d}-{na}v{nb}",
            [[rand_rating(rng) for _ in range(na)], [rand_rating(rng) for _ in range(nb)]])
    for na, nb in [(5, 5), (4, 5), (5, 4), (3, 3), (2, 3)]:
        add(f"predict-default-{na}v{nb}", [[(MU0, SIGMA0)] * na, [(MU0, SIGMA0)] * nb])
    add("predict-extreme-5v5-5-vs-45", [[(5.0, 1.0)] * 5, [(45.0, 1.0)] * 5])
    add("predict-extreme-5v5-m100-vs-150", [[(-100.0, 1.0)] * 5, [(150.0, 1.0)] * 5])
    add("predict-extreme-4v5-5-vs-45", [[(5.0, 1.0)] * 4, [(45.0, 1.0)] * 5])
    add("predict-largegap-5v5-17-vs-33", [[(17.0, 1.0)] * 5, [(33.0, 1.0)] * 5])
    add("predict-largegap-4v5-19-vs-31", [[(19.0, 1.0)] * 4, [(31.0, 1.0)] * 5])
    add("predict-tinysigma-5v5", [[(20.0, 0.5)] * 5, [(30.0, 0.5)] * 5])
    dropped, kept = {}, []
    for c in cases:
        spec = [[(p["mu"], p["sigma"]) for p in t] for t in c["teams"]]

        def vals():
            m = model(c["n"])
            teams = make_teams(m, spec)
            return m.predict_win(teams) + [m.predict_draw(teams)]

        ok, base = stable(vals)
        reason = underflow_reason(base) or (None if ok else "ill_conditioned")
        if reason:
            dropped[c["name"]] = reason
        else:
            kept.append(c)
    cases = kept
    print("predict.json dropped cases:", dropped or "none")
    write("predict.json", seed,
          "predict_win (both teams) and predict_draw: random rosters, all-default, extreme mismatch, tiny sigma.",
          {"cases": cases}, dict(dropped, total=len(dropped)))
    return len(cases)


# -------------------------------------------------------------- balancer.json
def top_distinct(values, k=5):
    """Greedy distance filter: sort descending; keep v only if (last kept) - v > 1e-9.
    Returns (kept, clear): clear is False if any compared gap (accepted or rejected)
    falls in the band (1e-12, 1e-7), where one-ulp noise could flip a decision."""
    kept, clear = [], True
    for v in sorted(values, reverse=True):
        if kept:
            gap = kept[-1] - v
            if 1e-12 < gap < 1e-7:
                clear = False
        if not kept or kept[-1] - v > 1e-9:
            kept.append(v)
            if len(kept) == k:
                break
    return kept, clear


def score_splits(n, ratings, ids, locks):
    m = model(n)
    rs = [m.rating(mu=mu, sigma=s) for mu, s in ratings]
    lo, hi = n // 2, n - n // 2
    if locks:
        # Orientation matters: every subset of size lo or hi is a candidate team A.
        candidates = [c for sz in sorted({lo, hi}) for c in itertools.combinations(range(n), sz)]
    elif n % 2 == 0:
        # a split and its mirror are one split: pin player 0 to team A.
        candidates = [c for c in itertools.combinations(range(n), lo) if 0 in c]
    else:
        candidates = list(itertools.combinations(range(n), lo))   # smaller team is A
    scored = []
    for c in candidates:
        cs = set(c)
        if any(ids[i] in locks and ((locks[ids[i]] == "a") != (i in cs)) for i in range(n)):
            continue
        scored.append((m.predict_draw([[rs[i] for i in c], [rs[i] for i in range(n) if i not in cs]]), c))
    return scored


def gen_balancer():
    seed = SEEDS["balancer"]
    rng = random.Random(seed)
    cases = []
    rerolled = []

    def finish(name, n, make):
        """make() -> (ratings, locks). Re-drawn (deterministically, rng moves on) if the
        roster is ill-conditioned or a dedupe gap sits in the ambiguous band."""
        ids = [pid(i + 1) for i in range(n)]
        for attempt in range(50):
            ratings, locks = make()
            with phase("build"):
                scored = score_splits(n, ratings, ids, locks)
            check(len(scored) >= 2, f"{name}: only {len(scored)} valid splits")
            vals, clear = top_distinct([v for v, _ in scored])
            why = "band"
            if clear:
                why = "unstable"
                ok, _ = stable(lambda: top_distinct([v for v, _ in score_splits(n, ratings, ids, locks)])[0],
                               n_random=6 if n >= 12 else len(RANDOM_SEEDS))
                if ok and not underflow_reason(vals):
                    break
            rerolled.append(f"{name} (attempt {attempt + 1}: {why})")
        else:
            raise AssertionError(f"{name}: no stable roster found")
        best_val, best = None, None
        for val, c in scored:               # first split in enumeration order with the max
            if best_val is None or val > best_val:
                best_val, best = val, c
        check(vals[0] == best_val, name)
        cases.append({
            "name": name,
            "n": n,
            "beta": beta_for(n),
            "players": team_json(ids, ratings),
            "locks": locks,
            "splits_considered": len(scored),
            "top5_draw": vals,
            "best_team_a": [ids[i] for i in best],
        })

    def rand_roster(n, locks=None):
        return lambda: ([rand_rating(rng) for _ in range(n)], dict(locks or {}))

    def dupes(n):
        def make():
            base = [rand_rating(rng) for _ in range(3)]
            return [rng.choice(base) for _ in range(n)], {}
        return make

    def locked(n):
        ids = [pid(i + 1) for i in range(n)]

        def make():
            ratings = [rand_rating(rng) for _ in range(n)]
            while True:
                k = min(rng.choice([1, 2, 3]), n - 2)
                locks = {ids[i]: rng.choice(["a", "b"]) for i in rng.sample(range(n), k)}
                with phase("build"):
                    if len(score_splits(n, ratings, ids, locks)) >= 2:
                        return ratings, locks
        return make

    idx = 1
    for i in range(40):                                  # random, no locks, n = 4..14
        n = 4 + i % 11
        finish(f"balancer-{idx:02d}-n{n}", n, rand_roster(n))
        idx += 1
    for n in (6, 7, 8, 9, 13):                           # all default ratings
        finish(f"balancer-{idx:02d}-n{n}-default", n, lambda n=n: ([(MU0, SIGMA0)] * n, {}))
        idx += 1
    for n in (6, 7, 8, 9, 10, 11):                       # duplicated ratings (3 distinct values)
        finish(f"balancer-{idx:02d}-n{n}-dupes", n, dupes(n))
        idx += 1
    for n in (4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 8, 9, 12, 13):   # random locks, up to n=14
        finish(f"balancer-{idx:02d}-n{n}-locked", n, locked(n))
        idx += 1
    for n, side in ((6, "b"), (8, "b"), (10, "b"), (8, "a")):    # deterministic single lock on p01
        finish(f"balancer-{idx:02d}-n{n}-p01-{side}", n, rand_roster(n, {"p01": side}))
        idx += 1
    print("balancer.json rosters re-drawn:", rerolled or "none")
    write("balancer.json", seed,
          "Exhaustive team-split reference scored by predict_draw: top 5 values (greedy 1e-9 distance filter, "
          "descending) and a best team A.",
          {"cases": cases}, {"redrawn_rosters": rerolled, "total": len(rerolled)})
    return len(cases)


def main():
    _instrument()
    counts = {
        "normal.json": gen_normal(),
        "rate.json": gen_rate(),
        "chains.json": gen_chains(),
        "predict.json": gen_predict(),
        "balancer.json": gen_balancer(),
    }
    for name in counts:
        size = (OUT / name).stat().st_size / 1024
        print(f"{name}: {counts[name]} cases, {size:.1f} KB")
    for ph in ("rate", "chains"):
        print(f"branches hit in {ph}.json (after filtering):")
        for name, (_, branches) in BRANCHES.items():
            print("  " + name + ": " + ", ".join(f"{b}={HITS[ph][f'{name}:{b}']}" for b in branches))
        print(f"  kappa floor applied: {KAPPA_HITS[ph]} time(s)")


if __name__ == "__main__":
    main()
