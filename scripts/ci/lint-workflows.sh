#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
if command -v actionlint >/dev/null 2>&1; then
  exec actionlint -color .github/workflows/*.yml
fi
if [[ "$(uname -s):$(uname -m)" != Linux:x86_64 ]]; then
  echo 'Install actionlint 1.7.12 from its official release for your platform, then rerun this script.' >&2
  exit 2
fi
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
curl --fail --location --retry 3 --connect-timeout 15 --max-time 180 \
  --proto '=https' --tlsv1.2 \
  https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz \
  --output "$temporary/actionlint.tar.gz"
printf '%s  %s\n' '8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8' "$temporary/actionlint.tar.gz" | sha256sum --check --status
tar -xzf "$temporary/actionlint.tar.gz" -C "$temporary" actionlint
"$temporary/actionlint" -color .github/workflows/*.yml
