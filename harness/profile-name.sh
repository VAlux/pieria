#!/usr/bin/env sh
# profile-name.sh — derive the Pieria profile slug for the current working directory.
#
# Implements the SAME resolution precedence as ProfileResolver.java (SPEC §10.2, phase-4 step 4)
# so that hooks and the MCP stdio shim always agree on the profile name.
#
# Resolution order (highest to lowest):
#   1. $PIERIA_PROFILE env var (explicit override)
#   2. Last path segment of `git config --get remote.origin.url`, minus trailing .git
#   3. `basename "$PWD"` (working-directory name)
#
# After one of the above produces a raw name it is normalized:
#   - lower-case
#   - runs of characters outside [a-z0-9-] replaced with a single hyphen
#   - repeated hyphens collapsed to one
#   - leading/trailing hyphens trimmed
#   - empty result -> "default"
#
# Usage (source or execute):
#   PROFILE=$(sh harness/profile-name.sh)
#   . harness/profile-name.sh && echo "$PIERIA_RESOLVED_PROFILE"
#
# When sourced the resolved name is available in $PIERIA_RESOLVED_PROFILE.
# When executed (sh profile-name.sh) the name is printed to stdout.

# ---------------------------------------------------------------------------
# normalize RAW -> slug matching ProfileResolver.normalize()
# ---------------------------------------------------------------------------
_pieria_normalize() {
  printf '%s' "$1" \
    | tr '[:upper:]' '[:lower:]' \
    | sed 's/[^a-z0-9-][^a-z0-9-]*/\-/g' \
    | sed 's/-\{2,\}/-/g' \
    | sed 's/^-*//; s/-*$//'
}

# ---------------------------------------------------------------------------
# parse_repo_name REMOTE_URL -> last path segment minus .git
# Handles https://, ssh://, and SCP-style git@host:org/repo.git
# Matches ProfileResolver.parseRepoName()
# ---------------------------------------------------------------------------
_pieria_parse_repo_name() {
  _url="$1"
  # SCP-style: git@github.com:org/repo.git  -> strip everything up to and including ':'
  case "$_url" in
    *@*:*)
      _url="${_url##*:}"
      ;;
    *://*/*)
      # Strip scheme://host, keep the path part
      _url="${_url#*://}"   # drop scheme://
      _url="${_url#*/}"     # drop host segment
      _url="/${_url}"       # re-add leading slash so the last-segment logic works
      ;;
  esac
  # Strip trailing slashes
  _url="${_url%%/}"
  # Take the last path segment
  _segment="${_url##*/}"
  # Strip trailing .git
  case "$_segment" in
    *.git) _segment="${_segment%.git}" ;;
  esac
  printf '%s' "$_segment"
}

# ---------------------------------------------------------------------------
# Main resolution logic
# ---------------------------------------------------------------------------
_pieria_resolve() {
  # 1. Explicit env override
  if [ -n "${PIERIA_PROFILE:-}" ]; then
    _raw="$PIERIA_PROFILE"
  else
    # 2. Git remote-derived name
    _remote_url=""
    if command -v git >/dev/null 2>&1; then
      _remote_url=$(git config --get remote.origin.url 2>/dev/null) || _remote_url=""
    fi

    if [ -n "$_remote_url" ]; then
      _raw=$(_pieria_parse_repo_name "$_remote_url")
    else
      # 3. Working-directory basename
      _raw=$(basename "$PWD")
    fi
  fi

  # Normalize and fall back to "default" if result is empty
  _slug=$(_pieria_normalize "$_raw")
  if [ -z "$_slug" ]; then
    _slug="default"
  fi
  printf '%s' "$_slug"
}

PIERIA_RESOLVED_PROFILE=$(_pieria_resolve)

# When executed directly, print the profile name; when sourced, just set the variable.
case "$0" in
  *profile-name.sh) printf '%s\n' "$PIERIA_RESOLVED_PROFILE" ;;
esac
