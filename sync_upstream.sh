#!/usr/bin/env bash
# v2.1 — upstream sync (iOS/Android) + optional auto-reapply
# macOS bash 3.2 compatible

set -euo pipefail

# ====== PRESET: ios | android ======
PRESET="${PRESET:-android}"                     # <--- switch target here

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/flutter/packages.git}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-main}"

# Defaults based on PRESET
if [ "$PRESET" = "android" ]; then
  UPSTREAM_SUBPATH="${UPSTREAM_SUBPATH:-packages/camera/camera_android}"
else
  UPSTREAM_SUBPATH="${UPSTREAM_SUBPATH:-packages/camera/camera_avfoundation}"
fi

TARGET_BRANCH="${TARGET_BRANCH:-upstream-sync}"          # branch to place clean upstream
FEATURE_BASE="${FEATURE_BASE:-origin/main}"              # where your features live
ASSUME_YES="${ASSUME_YES:-0}"                            # 1 = skip prompt
AUTO_COMMIT="${AUTO_COMMIT:-1}"
AUTO_PUSH="${AUTO_PUSH:-0}"                               # 1 = push both branches
REMOTE_NAME="${REMOTE_NAME:-origin}"
COMMIT_MSG="${COMMIT_MSG:-sync: upstream $(basename "$UPSTREAM_SUBPATH") → local tree}"

EXCLUDE_FILE="${EXCLUDE_FILE:-.upstream-sync-exclude}"
PROTECT_FILE="${PROTECT_FILE:-.upstream-sync-protect}"
EXTRA_PROTECT="${EXTRA_PROTECT:-}"                        # comma-separated extra paths to protect

# ===== helpers =====
die(){ echo "Error: $*" >&2; exit 1; }
need_clean(){ if ! git diff --quiet || ! git diff --cached --quiet; then die "Uncommitted changes. Commit/stash before running."; fi; }
append_unique(){ local f="$1"; shift; local line="$*"; grep -qxF "$line" "$f" 2>/dev/null || echo "$line" >> "$f"; }

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "Run from a git repo root."
need_clean

# Switch/create target branch
if git show-ref --verify --quiet "refs/heads/$TARGET_BRANCH"; then
  git checkout "$TARGET_BRANCH"
else
  git checkout -b "$TARGET_BRANCH"
fi

# Exclude/protect
touch "$EXCLUDE_FILE" "$PROTECT_FILE"

# Common excludes (Dart + generic)
for p in \
  "CHANGELOG.md" "README.md" "pubspec.yaml" \
  "example/**" "test/**" \
  "*.g.dart" "*.freezed.dart" "*.mocks.dart" "*.gen.dart" \
  "*.pb.dart" "*.pbjson.dart" "*.pbenum.dart" "*.pbserver.dart" \
  "*.gr.dart" "*.graphql.dart" "*.chopper.dart" \
  ".dart_tool/**" "build/**" \
; do append_unique "$EXCLUDE_FILE" "$p"; done

# Platform-specific excludes
if [ "$PRESET" = "android" ]; then
  for p in \
    ".idea/**" "*.iml" ".gradle/**" \
    "android/local.properties" \
    "android/**/build/**" "**/generated/**" \
  ; do append_unique "$EXCLUDE_FILE" "$p"; done
else
  for p in \
    "ios/camera_avfoundation.podspec" \
    "ios/camera_avfoundation/Sources/camera_avfoundation_objc/messages.g.m" \
    "**/*.g.h" "**/*.g.m" "**/*.g.mm" \
  ; do append_unique "$EXCLUDE_FILE" "$p"; done
fi

# Protect: never delete these locally
for p in \
  ".git" ".github" ".gitignore" ".DS_Store" \
  "$EXCLUDE_FILE" "$PROTECT_FILE" "sync_upstream.sh" \
  ".upstream_tmp" ".upstream_tmp/**" ".dart_tool/**" "build/**" \
; do append_unique "$PROTECT_FILE" "$p"; done

# Extra protect from env
if [ -n "$EXTRA_PROTECT" ]; then
  IFS=',' read -r -a arr <<< "$EXTRA_PROTECT"
  for it in "${arr[@]}"; do
    it="$(echo "$it" | sed 's/^ *//;s/ *$//')"
    [ -n "$it" ] && append_unique "$PROTECT_FILE" "$it"
  done
fi

# Fetch upstream subpath (sparse)
TMP_DIR="$(mktemp -d -t upstream_camera_sync_XXXXXX)"
trap 'rm -rf "$TMP_DIR" 2>/dev/null || true' EXIT

echo "==> PRESET: $PRESET"
echo "==> Upstream: $UPSTREAM_URL [$UPSTREAM_BRANCH] :: $UPSTREAM_SUBPATH"
echo "==> Temp dir: $TMP_DIR"
echo "==> Target branch: $TARGET_BRANCH"
echo "==> Feature base:  $FEATURE_BASE"

git clone --no-checkout "$UPSTREAM_URL" "$TMP_DIR" >/dev/null
pushd "$TMP_DIR" >/dev/null
git sparse-checkout init --cone >/dev/null
git sparse-checkout set "$UPSTREAM_SUBPATH" >/dev/null
git checkout "$UPSTREAM_BRANCH" >/dev/null
popd >/dev/null

# Build rsync args
RSYNC_ARGS=(-a --delete --exclude-from="$EXCLUDE_FILE")
while IFS= read -r line; do
  [ -z "$line" ] && continue
  case "$line" in \#*) continue ;; esac
  RSYNC_ARGS+=(--filter="P $line")
done < "$PROTECT_FILE"

echo "==> DRY RUN (no changes):"
rsync -n "${RSYNC_ARGS[@]}" "$TMP_DIR/$UPSTREAM_SUBPATH/" "./" || true

if [ "$ASSUME_YES" != "1" ]; then
  printf "Apply changes? [y/N] "
  read ans
  case "$ans" in y|Y) ;; *) echo "Canceled."; exit 0 ;; esac
fi

echo "==> Applying rsync"
rsync "${RSYNC_ARGS[@]}" "$TMP_DIR/$UPSTREAM_SUBPATH/" "./"

# Commit clean upstream
if [ "$AUTO_COMMIT" = "1" ]; then
  git add -A
  if ! git diff --cached --quiet; then
    git commit -m "$COMMIT_MSG"
    echo "==> Commit created on $TARGET_BRANCH"
  else
    echo "==> Nothing to commit on $TARGET_BRANCH"
  fi
fi

# ===== Auto reapply (optional, same as before) =====
REAPPLY_BRANCH="feature/reapply-$(date +%Y%m%d-%H%M%S)"
echo "==> Creating reapply branch: $REAPPLY_BRANCH (base: $TARGET_BRANCH)"
git checkout -b "$REAPPLY_BRANCH" "$TARGET_BRANCH"

PATCH_FILE="$(mktemp -t reapply_patch_XXXXXX.diff)"

# Patch paths differ per preset
if [ "$PRESET" = "android" ]; then
  INCLUDE_PATHS=(
    'android/**'
    'lib/**'
    'include/**'
  )
else
    INCLUDE_PATHS=(
      'ios/**'
      'lib/**'
      'include/**'
    )
fi

# Build git diff command
DIFF_ARGS=( "$TARGET_BRANCH..$FEATURE_BASE" -- )
for p in "${INCLUDE_PATHS[@]}"; do DIFF_ARGS+=( "$p" ); done
# common excludes (generated / samples / docs)
DIFF_ARGS+=( \
  ':(exclude)**/*.g.dart' ':(exclude)**/*.freezed.dart' ':(exclude)**/*.mocks.dart' \
  ':(exclude)**/*.pb*.dart' ':(exclude)**/*.gr.dart' ':(exclude)**/*.gen.dart' \
  ':(exclude)**/*.graphql.dart' ':(exclude)**/*.chopper.dart' \
  ':(exclude)**/*.g.h' ':(exclude)**/*.g.m' ':(exclude)**/*.g.mm' \
  ':(exclude)example/**' ':(exclude)test/**' \
  ':(exclude)CHANGELOG.md' ':(exclude)README.md' ':(exclude)pubspec.yaml' \
)

git diff "${DIFF_ARGS[@]}" > "$PATCH_FILE" || true

if [ ! -s "$PATCH_FILE" ]; then
  echo "==> Reapply patch is empty (nothing to reapply)."
else
  echo "==> Applying reapply patch (3-way)"
  set +e
  git apply --3way "$PATCH_FILE"
  STATUS=$?
  set -e
  git add -A
  if ! git diff --cached --quiet; then
    git commit -m "feat: reapply custom features on top of upstream ($PRESET)"
    echo "==> Reapply commit created on $REAPPLY_BRANCH"
  else
    echo "==> Nothing to commit after reapply."
  fi
  if [ $STATUS -ne 0 ]; then
    echo "⚠️  Some hunks failed; resolve conflicts if any, then commit."
  fi
fi

# Push (optional)
if [ "$AUTO_PUSH" = "1" ]; then
  echo "==> Pushing to $REMOTE_NAME"
  git push -u "$REMOTE_NAME" "$TARGET_BRANCH"
  git push -u "$REMOTE_NAME" "$REAPPLY_BRANCH"
  echo "Open PRs:"
  echo "  1) $TARGET_BRANCH → main   (clean upstream: $PRESET)"
  echo "  2) $REAPPLY_BRANCH → $TARGET_BRANCH  (your features)"
else
  echo "==> Done. Push branches and open PRs:"
  echo "   git push -u $REMOTE_NAME $TARGET_BRANCH"
  echo "   git push -u $REMOTE_NAME $REAPPLY_BRANCH"
  echo "   PR #1: $TARGET_BRANCH → main   (upstream $PRESET only)"
  echo "   PR #2: $REAPPLY_BRANCH → $TARGET_BRANCH  (reapply features)"
fi
