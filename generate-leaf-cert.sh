#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./generate-leaf-cert.sh <chain-dir> <leaf-name> <server|client|both> [common-name] [rsa-bits]

Generates a leaf certificate signed by the intermediate CA created with
generate-ca-chain.sh.

Required files in <chain-dir>:
  intermediate-key.pem
  intermediate-cert.pem
  root-cert.pem

Outputs:
  <leaf-name>-key.pem
  <leaf-name>.csr.pem
  <leaf-name>-cert.pem
  <leaf-name>-fullchain.pem

Defaults:
  common-name = <leaf-name>
  rsa-bits = 2048
EOF
}

if [[ $# -lt 3 || $# -gt 5 ]]; then
  usage
  exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required but was not found in PATH" >&2
  exit 1
fi

chain_dir="${1%/}"
leaf_name="$2"
purpose="$3"
common_name="${4:-$leaf_name}"
rsa_bits="${5:-2048}"

intermediate_key="${chain_dir}/intermediate-key.pem"
intermediate_cert="${chain_dir}/intermediate-cert.pem"
root_cert="${chain_dir}/root-cert.pem"

for required in "$intermediate_key" "$intermediate_cert" "$root_cert"; do
  if [[ ! -f "$required" ]]; then
    echo "Missing required file: $required" >&2
    exit 1
  fi
done

case "$purpose" in
  server) extended_key_usage="serverAuth" ;;
  client) extended_key_usage="clientAuth" ;;
  both) extended_key_usage="serverAuth,clientAuth" ;;
  *)
    echo "purpose must be one of: server, client, both" >&2
    exit 1
    ;;
esac

leaf_key="${chain_dir}/${leaf_name}-key.pem"
leaf_csr="${chain_dir}/${leaf_name}.csr.pem"
leaf_cert="${chain_dir}/${leaf_name}-cert.pem"
leaf_fullchain="${chain_dir}/${leaf_name}-fullchain.pem"
intermediate_serial="${chain_dir}/intermediate-ca.srl"

tmp_leaf_ext="$(mktemp)"
trap 'rm -f "$tmp_leaf_ext"' EXIT

cat >"$tmp_leaf_ext" <<EOF
[v3_leaf]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = ${extended_key_usage}
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
subjectAltName = @alt_names

[alt_names]
DNS.1 = ${common_name}
EOF

openssl req \
  -new \
  -newkey "rsa:${rsa_bits}" \
  -sha256 \
  -nodes \
  -keyout "$leaf_key" \
  -out "$leaf_csr" \
  -subj "/C=US/ST=Madrid/L=Madrid/O=${leaf_name}/OU=${purpose}/CN=${common_name}"

openssl x509 \
  -req \
  -in "$leaf_csr" \
  -CA "$intermediate_cert" \
  -CAkey "$intermediate_key" \
  -CAcreateserial \
  -CAserial "$intermediate_serial" \
  -out "$leaf_cert" \
  -days 825 \
  -sha256 \
  -extensions v3_leaf \
  -extfile "$tmp_leaf_ext"

cat "$leaf_cert" "$intermediate_cert" >"$leaf_fullchain"

echo "Created leaf certificate in ${chain_dir}"
echo "Leaf certificate: ${leaf_cert}"
echo "Leaf full chain: ${leaf_fullchain}"
echo "App trust anchor remains: ${chain_dir}/ca-cert.pem"
