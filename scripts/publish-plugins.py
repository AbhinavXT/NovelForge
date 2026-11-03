#!/usr/bin/env python3
"""Copy the bundled plugins into a checkout of the public plugin repository.

    python3 scripts/publish-plugins.py ../NovelForge-plugins

The app's bundled plugins (app/src/main/assets/plugins) are the source of
truth. This writes <repo>/plugins/<file>.js and <repo>/index.json from
them, dropping the bundle-only "sourceId" field, which a downloaded
repository is not allowed to set.

It refuses to publish a plugin whose version is lower than the one already
in the repository, or whose script changed without a version bump, since
installed copies only update when the version goes up. Plugins in the
repository that are no longer bundled are removed.

Review the result with `git diff` in the repository, then commit and push.
"""
import json
import os
import shutil
import sys

ASSETS = os.path.join(os.path.dirname(__file__), os.pardir, "app", "src", "main", "assets", "plugins")


def version_tuple(v):
    return tuple(int(p) if p.isdigit() else 0 for p in v.split("."))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    repo = sys.argv[1]
    if not os.path.isdir(os.path.join(repo, ".git")):
        sys.exit(f"{repo} is not a git checkout")

    bundled = json.load(open(os.path.join(ASSETS, "index.json"), encoding="utf-8"))
    repo_index_path = os.path.join(repo, "index.json")
    existing = {}
    if os.path.exists(repo_index_path):
        existing = {e["id"]: e for e in json.load(open(repo_index_path, encoding="utf-8"))}

    problems = []
    out = []
    os.makedirs(os.path.join(repo, "plugins"), exist_ok=True)
    for entry in bundled:
        src = os.path.join(ASSETS, entry["url"])
        dest_rel = "plugins/" + os.path.basename(entry["url"])
        dest = os.path.join(repo, dest_rel)
        old = existing.get(entry["id"])
        if old:
            if version_tuple(entry["version"]) < version_tuple(old["version"]):
                problems.append(f"{entry['id']}: bundled {entry['version']} is older than published {old['version']}")
            elif entry["version"] == old["version"] and os.path.exists(dest):
                if open(src, "rb").read() != open(dest, "rb").read():
                    problems.append(f"{entry['id']}: script changed but version is still {entry['version']}")
        published = {k: v for k, v in entry.items() if k != "sourceId"}
        published["url"] = dest_rel
        out.append((src, dest, published, old))

    if problems:
        sys.exit("Not publishing:\n  " + "\n  ".join(problems))

    keep = set()
    for src, dest, published, old in out:
        shutil.copyfile(src, dest)
        keep.add(os.path.basename(dest))
        if not old:
            print(f"added    {published['id']} {published['version']}")
        elif old["version"] != published["version"]:
            print(f"updated  {published['id']} {old['version']} -> {published['version']}")
    for name in os.listdir(os.path.join(repo, "plugins")):
        if name.endswith(".js") and name not in keep:
            os.remove(os.path.join(repo, "plugins", name))
            print(f"removed  {name}")
    with open(repo_index_path, "w", encoding="utf-8") as f:
        json.dump([p for _, _, p, _ in out], f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {repo_index_path} ({len(out)} plugins)")


if __name__ == "__main__":
    main()
