#!/bin/sh
# Run in every new development clone; hooks are repository-local, not global.
set -eu
fail() {
    printf 'Git identity guard install: %s\n' "$*" >&2
    exit 1
}
root=$(CDPATH= cd -P "$(dirname "$0")/.." && pwd)
cd "$root"
[ "$(git rev-parse --is-inside-work-tree)" = true ] && [ -z "$(git rev-parse --show-prefix)" ] || fail 'Run the installer from its own Git clone.'
[ "$(git symbolic-ref HEAD)" = refs/heads/main ] || fail 'Only main is supported.'
case "$(git remote get-url origin)" in
    https://github.com/y2485871697/Daiyu.git|git@github.com:y2485871697/Daiyu.git) ;;
    *) fail 'This installer is only for y2485871697/Daiyu.' ;;
esac
hooks=scripts/git-hooks
[ -f "$hooks/pre-push" ] && [ -x "$hooks/pre-push" ] || fail 'The executable pre-push script is missing.'
sh -n "$hooks/pre-push"
configured=$(git config --get core.hooksPath || :)
case "$configured" in
    "$hooks") ;;
    '')
        standard=$(git rev-parse --git-path hooks)
        for hook in "$standard"/*; do
            [ -f "$hook" ] || continue
            case "$hook" in *.sample) continue ;; esac
            fail "Existing hook must be reviewed before changing hooksPath: $hook"
        done
        ;;
    *) fail 'An existing core.hooksPath must be reviewed, not overwritten.' ;;
esac
name=y2485871697
email=y2485871697@users.noreply.github.com
[ "${GIT_AUTHOR_NAME-$name}" = "$name" ] || fail 'GIT_AUTHOR_NAME overrides the allowed identity.'
[ "${GIT_COMMITTER_NAME-$name}" = "$name" ] || fail 'GIT_COMMITTER_NAME overrides the allowed identity.'
[ "${GIT_AUTHOR_EMAIL-$email}" = "$email" ] || fail 'GIT_AUTHOR_EMAIL overrides the allowed identity.'
[ "${GIT_COMMITTER_EMAIL-$email}" = "$email" ] || fail 'GIT_COMMITTER_EMAIL overrides the allowed identity.'
git config --local user.name "$name"
git config --local user.email "$email"
git config --local user.useConfigOnly true
for identity in "$(git var GIT_AUTHOR_IDENT)" "$(git var GIT_COMMITTER_IDENT)"; do
    case "$identity" in "$name <$email> "*) ;; *) fail 'Effective identity is still overridden; check Git environment/config.' ;; esac
done
git config --local core.hooksPath "$hooks"
printf 'Installed: author/committer identity checks and corrected-history protection before every push.\n'
