#!/usr/bin/env bash
# Lists the phrases in tracked source files that a gate, workflow, or ops script greps for.
#
# Those phrases are build fixtures: deleting one turns a gate red, and nothing in the source file
# reveals the dependency. The fixture list in CONTRIBUTING.md is generated from this output, so run
# it after adding or removing a grep under gates/, .github/ or ops/ and fold the result back in.
#
# A source phrase becomes a fixture two ways:
#   direct   a script greps the repo file itself, so the phrase must survive in that source file
#   emitted  a script greps a run log for a phrase some source prints, so the string literal that
#            produces it must survive
#
# Deliberately over-inclusive: it reports candidates for a human to confirm rather than silently
# dropping what it cannot classify. Maven's own chatter (BUILD SUCCESS, Tests run) is filtered out
# because it does not come from this repo's sources.

set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

consumers=$(git ls-files 'gates/*.sh' 'ops/scripts/*.sh' 'ops/dr-drills/*.sh' '.github/workflows/*.yml')
mapfile -t javas < <(git ls-files '*.java')

emit() { printf '%-28s %-70s %s\n' "$1" "$2" "$3"; }

# Backslash line-continuations split single logical commands across lines, so join them first.
joined() { sed -e ':a' -e '/\\$/{N;s/\\\n[[:space:]]*/ /;ba' -e '}' "$1" 2>/dev/null; }

{
  echo "== direct: a script greps the tracked file itself =="

  for c in $consumers; do
    text=$(joined "$c")

    # assert_grep "<path>" "<pattern>" — the explicit form used by gate-B, gate-mswatch, gate-phase1
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      path=${line#*\"}; path=${path%%\"*}
      pat=${line%\"}; pat=${pat##*\"}
      emit "$c" "$path" "$pat"
    done < <(printf '%s\n' "$text" | grep -oE 'assert_grep +"[^"]+" +"[^"]+"')

    # grep against a shell variable assigned a tracked repo path earlier in the same script
    while IFS= read -r assign; do
      [ -n "$assign" ] || continue
      var=${assign%%=*}
      path=${assign#*/}; path=${path%\"}
      git ls-files --error-unmatch "$path" >/dev/null 2>&1 || continue
      while IFS= read -r g; do
        [ -n "$g" ] || continue
        pat=${g#*grep }; pat=${pat#-* }
        pat=${pat%%\"\$$var\"*}
        pat=$(printf '%s' "$pat" | sed -E "s/^['\"]//; s/['\"] *$//")
        emit "$c" "$path" "$pat"
      done < <(printf '%s\n' "$text" \
               | grep -oE "grep -[a-zA-Z]+ '[^']+' \"\\\$$var\"|grep -[a-zA-Z]+ \"[^\"]+\" \"\\\$$var\"")
    done < <(printf '%s\n' "$text" | grep -oE '^[A-Z_]+="\$\{?ROOT\}?/[^"]+"')

    # a tracked source path named literally on a grep or git-diff line
    while IFS= read -r path; do
      [ -n "$path" ] || continue
      git ls-files --error-unmatch "$path" >/dev/null 2>&1 \
        && emit "$c" "$path" "(path named literally on a grep line)"
    done < <(printf '%s\n' "$text" | grep -E 'grep|git diff' \
             | grep -ohE '[A-Za-z0-9_./-]+/[A-Za-z0-9_]+\.(java|tla)' | sort -u)
  done | sort -u

  echo
  echo "== emitted: a script greps a run log for a phrase this repo's source prints =="

  for c in $consumers; do
    while IFS= read -r g; do
      [ -n "$g" ] || continue
      # grep -q asserts; a bare grep only filters what gets echoed, so it is not load-bearing
      case "$g" in
        "grep -"*q*) kind=asserted ;;
        *) kind=display-only ;;
      esac
      pat=${g#*grep }; pat=${pat#-* }
      pat=$(printf '%s' "$pat" | sed -E "s/^['\"]//; s/['\"]$//")
      # grep -E alternations are several independent fixtures; score each branch on its own
      while IFS= read -r branch; do
        [ ${#branch} -ge 8 ] || continue
        case "$branch" in
          *'$'*|*'BUILD SUCCESS'*|*'Tests run'*|*ERROR*|*FAILURE*|*WARNING*) continue ;;
        esac
        # Only count a hit inside a real Java string literal, i.e. the source truly prints it.
        # Bare identifier matches (enum constants, field names) are not fixtures.
        hits=$(BRANCH="$branch" awk '
          FNR == 1 { shown = 0 }
          shown { next }
          {
            line = $0; lit = ""
            while (match(line, /"([^"\\]|\\.)*"/)) {
              lit = lit substr(line, RSTART + 1, RLENGTH - 2) "\n"
              line = substr(line, RSTART + RLENGTH)
            }
            if (index(lit, ENVIRON["BRANCH"])) { print FILENAME; shown = 1 }
          }' "${javas[@]}" 2>/dev/null | head -3)
        [ -n "$hits" ] || continue
        while IFS= read -r h; do emit "$c" "$h" "$branch  [$kind]"; done <<< "$hits"
      done < <(printf '%s\n' "$pat" | tr '|' '\n')
    done < <(joined "$c" | grep -oE "grep -[a-zA-Z]+ '[^']{8,}'|grep -[a-zA-Z]+ \"[^\"]{8,}\"")
  done | sort -u
}
