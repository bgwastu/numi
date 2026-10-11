#!/usr/bin/env python3
"""Assign immutable Numi versions to app commits on main.

Each first-parent commit after the last 2.x release that changes a configured
folder increments the Android version code and the 3.0 patch number. Other
commits leave the version alone. A push containing multiple commits produces a
release for each matching commit. Retries reuse those versions, including tags
whose release upload failed. This tool never creates or moves a Git tag itself.
"""

import argparse
import json
from pathlib import Path
import subprocess
import sys


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args])


def versions(repo, config, revision="HEAD"):
    base = config["base_commit"]
    history = git(repo, "rev-list", "--first-parent", revision).decode().splitlines()
    if base not in history:
        raise ValueError("Release baseline is missing from first-parent history; fetch the full main branch")
    commits = git(repo, "rev-list", "--first-parent", "--reverse", f"{base}..{revision}").decode().splitlines()
    code = config["base_version_code"]
    result = []
    for commit in commits:
        parent = git(repo, "rev-parse", f"{commit}^1").decode().strip()
        paths = git(repo, "diff", "--name-only", "-z", "--no-renames", parent, commit).decode().split("\0")
        if not any(path.startswith(prefix) for path in paths for prefix in config["paths"]):
            continue
        code += 1
        name = f"{config['series']}.{code}"
        result.append({"sha": commit, "version_code": code, "version_name": name, "tag": f"v{name}"})
    return result


def pending_releases(repo, config, published=(), revision="HEAD"):
    tags = set(git(repo, "tag", "--list").decode().splitlines())
    complete = {
        release["tag_name"] for release in published
        if config["apk_asset"] in release.get("assets", [])
    }
    pending = []
    for release in versions(repo, config, revision):
        tag = release["tag"]
        if tag in tags:
            target = git(repo, "rev-parse", f"refs/tags/{tag}^{{commit}}").decode().strip()
            if target != release["sha"]:
                raise ValueError(f"{tag} already points to a different commit; refusing to move it")
            if tag in complete:
                continue
        pending.append(release)
    return pending


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--config", type=Path, default=Path(__file__).with_suffix(".json"))
    parser.add_argument("--revision", default="HEAD")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--version-code", action="store_true")
    mode.add_argument("--verify-tag")
    parser.add_argument("--commit")
    parser.add_argument("--published-releases", type=Path, help="GitHub release metadata as JSON lines")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if args.version_code:
        releases = versions(args.repo, config, args.revision)
        # The uncommitted 3.x rebuild also needs to upgrade the installed 2.x APK.
        print(releases[-1]["version_code"] if releases else config["base_version_code"] + 1)
        return
    if args.verify_tag:
        if not args.commit:
            parser.error("--verify-tag requires --commit")
        expected = next((r for r in versions(args.repo, config, args.commit) if r["sha"] == args.commit), None)
        if expected is None or expected["tag"] != args.verify_tag:
            raise ValueError("Requested tag does not match the version assigned to this commit")
        pending_releases(args.repo, config, revision=args.commit)
        return
    published = []
    if args.published_releases:
        published = [json.loads(line) for line in args.published_releases.read_text().splitlines() if line.strip()]
    releases = pending_releases(args.repo, config, published, args.revision)
    matrix = json.dumps({"include": releases}, separators=(",", ":"))
    if len(releases) > 256:
        raise ValueError("More than 256 pending releases; process a smaller commit range first")
    if args.github_output:
        with args.github_output.open("a") as output:
            output.write(f"matrix={matrix}\nhas_releases={str(bool(releases)).lower()}\n")
    else:
        print(matrix)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
