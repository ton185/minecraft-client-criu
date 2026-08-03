#!/bin/bash
# One-time root setup so an ordinary user can run mc-criu.
#
# Checkpointing another process is inherently privileged, so something has to be
# granted. This grants the two narrowest things that work, and nothing else — no
# sudo rule, no setuid binary, and in particular NOT CAP_SYS_ADMIN (root by
# another name) and NOT CAP_DAC_READ_SEARCH (which would let any local user read
# any file on the system through criu).
#
# Established by experiment; see docs/NONROOT.md for the measurements.
set -euo pipefail

CRIU=$(command -v criu || echo /usr/bin/criu)
[ -x "$CRIU" ] || { echo "criu not found" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || { echo "run this as root (it is the only part that needs root)" >&2; exit 1; }

# CAP_CHECKPOINT_RESTORE is the capability the kernel added for exactly this.
# CAP_SYS_PTRACE is needed on top because criu has to ptrace a process that is
# not its descendant, which yama (ptrace_scope=1) otherwise forbids.
# Measured: either alone fails, both together work.
setcap cap_checkpoint_restore,cap_sys_ptrace+eip "$CRIU"
echo "granted: $(getcap "$CRIU")"

cat <<'EOF'

Done. An ordinary user can now run mc-criu.

Two things to know:

  * The user's session must set XDG_RUNTIME_DIR (every desktop session does).
    criu caches kernel feature detection there; with nowhere to write it, it
    re-runs a probe that mounts a tmpfs and fails. If you are in a bare shell
    with no session, either export XDG_RUNTIME_DIR to a writable directory or
    run: chmod 0644 /run/criu.kdat

  * Unprivileged sessions run WITHOUT a PID namespace, so a restore needs the
    image's process IDs to still be free. See docs/NONROOT.md for why this is
    a property of criu rather than a shortcut.
EOF
