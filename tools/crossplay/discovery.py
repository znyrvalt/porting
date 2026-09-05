"""Discovery of every (base item, custom_model_data) pair the packs define.

Nothing here is a hand-written list.  The item definition tree under
``assets/<ns>/items/*.json`` is walked generically: ``range_dispatch``,
``select``, ``condition`` and ``composite`` nodes are all traversed, and the
custom-model-data thresholds are only harvested from dispatch nodes whose
``property`` really is ``custom_model_data``.  Every other branch is recorded as
a *state* of the pair it sits under (``using_item``, ``display_context=gui``,
``crossbow/pull>=0.58`` and so on) so the Bedrock side can decide what it can
represent and what has to be documented as unmappable.
"""

from __future__ import annotations

import posixpath

MOD_CONTENT_DIR = "fabric-mod/src/main/resources/altarsmp/content"


def _norm(prop: str | None) -> str:
    if not prop:
        return ""
    return prop.split(":", 1)[-1]


class Leaf:
    __slots__ = ("model", "states")

    def __init__(self, model, states):
        self.model = model
        self.states = tuple(states)

    def __repr__(self):
        return "Leaf(%s, %s)" % (self.model, ",".join(self.states) or "-")


class Pair:
    """One (base, cmd) pair with every model/state leaf underneath it."""

    __slots__ = ("namespace", "base", "cmd", "leaves")

    def __init__(self, namespace, base, cmd):
        self.namespace = namespace
        self.base = base
        self.cmd = cmd
        self.leaves = []

    @property
    def base_id(self):
        return "%s:%s" % (self.namespace, self.base)

    @staticmethod
    def _rank(leaf):
        """Lower is more 'default': negated conditions do not count against a leaf."""
        return sum(0 if state.startswith("!") else 1 for state in leaf.states)

    @property
    def primary(self):
        """The leaf a Bedrock icon/attachable should be built from.

        The most default leaf wins - no active states at all, or only negated
        ones such as ``!using_item``.  That matches what the Java client shows
        for an item that is simply being held.
        """
        if not self.leaves:
            return None
        return min(self.leaves, key=lambda l: (self._rank(l), self.leaves.index(l)))

    @property
    def icon_leaf(self):
        """The leaf whose artwork belongs in the inventory slot.

        A ``display_context=gui`` branch exists precisely to change the
        inventory appearance, so it wins over the held model when present.
        """
        for leaf in self.leaves:
            if any(s.startswith("display_context=") and "gui" in s for s in leaf.states):
                return leaf
        return self.primary

    @property
    def extra_states(self):
        return [leaf for leaf in self.leaves if leaf is not self.primary]

    def __repr__(self):
        return "Pair(%s,%s,%d leaves)" % (self.base, self.cmd, len(self.leaves))


def walk_definition(node, on_leaf, cmd=None, states=()):
    """Recursive walk of an item-definition model tree."""
    if node is None:
        return
    if isinstance(node, str):
        on_leaf(cmd, states, node)
        return
    if not isinstance(node, dict):
        return
    ntype = _norm(node.get("type"))

    if ntype == "model" or ("model" in node and isinstance(node["model"], str)):
        on_leaf(cmd, states, node["model"])
        # a stray "model" string next to cases/entries is malformed but present
        # in these packs; keep walking the rest of the node.

    if ntype == "range_dispatch" or "entries" in node:
        prop = _norm(node.get("property"))
        for entry in node.get("entries", []) or []:
            threshold = entry.get("threshold")
            child = entry.get("model")
            if prop == "custom_model_data":
                try:
                    value = int(threshold)
                except (TypeError, ValueError):
                    continue
                walk_definition(child, on_leaf, value, states)
            else:
                walk_definition(child, on_leaf, cmd, states + ("%s>=%s" % (prop, threshold),))
        for extra in node.get("models", []) or []:
            walk_definition(extra, on_leaf, cmd, states)
        walk_definition(node.get("fallback"), on_leaf, cmd, states)
        return

    if ntype == "select" or "cases" in node:
        prop = _norm(node.get("property"))
        for case in node.get("cases", []) or []:
            when = case.get("when")
            whens = when if isinstance(when, list) else [when]
            label = "%s=%s" % (prop, "|".join(str(w) for w in whens))
            walk_definition(case.get("model"), on_leaf, cmd, states + (label,))
        walk_definition(node.get("fallback"), on_leaf, cmd, states)
        return

    if ntype == "condition" or "on_true" in node or "on_false" in node:
        prop = _norm(node.get("property")) or "condition"
        walk_definition(node.get("on_true"), on_leaf, cmd, states + (prop,))
        walk_definition(node.get("on_false"), on_leaf, cmd, states + ("!" + prop,))
        return

    if ntype == "composite":
        for child in node.get("models", []) or []:
            walk_definition(child, on_leaf, cmd, states)
        return

    if ntype in ("special", "empty", "bundle/selected_item"):
        on_leaf(cmd, states, None)
        return


def discover_pairs(source):
    """Walk every item definition in the packs; return sorted Pair objects."""
    pairs = {}
    for namespace, item_id, path in source.item_definitions():
        try:
            data = source.read_json(path)
        except ValueError:
            continue
        root = data.get("model")

        def on_leaf(cmd, states, model, _ns=namespace, _id=item_id):
            if cmd is None or model is None:
                return
            key = (_ns, _id, cmd)
            pair = pairs.get(key)
            if pair is None:
                pair = pairs[key] = Pair(_ns, _id, cmd)
            if not any(l.model == model and l.states == tuple(states) for l in pair.leaves):
                pair.leaves.append(Leaf(model, states))

        walk_definition(root, on_leaf)
    return [pairs[k] for k in sorted(pairs)]


# --------------------------------------------------------------------- models


def resolve_model(source, ref, _seen=None):
    """Load a model and flatten its parent chain (textures + display + elements)."""
    seen = _seen or set()
    if ref in seen:
        return None
    seen.add(ref)
    path = source.model_path(ref)
    if path is None:
        return None
    data = source.read_json(path)
    parent = data.get("parent")
    merged = {"ref": ref, "path": path, "textures": {}, "display": {}, "elements": [],
              "texture_size": None, "parent_chain": []}
    if parent:
        base = resolve_model(source, parent, seen)
        if base:
            merged["textures"].update(base["textures"])
            merged["display"].update(base["display"])
            merged["elements"] = list(base["elements"])
            merged["texture_size"] = base["texture_size"]
            merged["parent_chain"] = [parent] + base["parent_chain"]
        else:
            merged["parent_chain"] = [parent]
    merged["textures"].update(data.get("textures") or {})
    for key, value in (data.get("display") or {}).items():
        merged["display"][key] = value
    if data.get("elements"):
        merged["elements"] = data["elements"]
    if data.get("texture_size"):
        merged["texture_size"] = data["texture_size"]
    merged["own_parent"] = parent
    return merged


def resolve_texture_ref(model, ref):
    """Follow ``#var`` texture indirections to a concrete texture id."""
    seen = 0
    while isinstance(ref, str) and ref.startswith("#") and seen < 8:
        ref = model["textures"].get(ref[1:])
        seen += 1
    return ref


def model_textures(model):
    """Every concrete texture reference a model uses, ordered."""
    out = []
    for key in sorted(model["textures"]):
        ref = resolve_texture_ref(model, model["textures"][key])
        if isinstance(ref, str) and not ref.startswith("#") and ref not in out:
            out.append(ref)
    return out
