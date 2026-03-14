#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./generate-ca-chain.sh <output-dir> <chain-name> [rsa-bits]

Creates a PKI chain with:
  - self-signed root CA
  - intermediate CA signed by the root

Outputs are written to:
  <output-dir>/<chain-name>/

Files created:
  root-key.pem
  root-cert.pem
  intermediate-key.pem
  intermediate.csr.pem
  intermediate-cert.pem
  intermediate-chain.pem
  ca-cert.pem
  ca-chain.pem

Defaults:
  rsa-bits = 2048
EOF
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage
  exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required but was not found in PATH" >&2
  exit 1
fi

output_root="$1"
chain_name="$2"
rsa_bits="${3:-2048}"
chain_dir="${output_root%/}/${chain_name}"

mkdir -p "$chain_dir"

root_key="${chain_dir}/root-key.pem"
root_cert="${chain_dir}/root-cert.pem"
intermediate_key="${chain_dir}/intermediate-key.pem"
intermediate_csr="${chain_dir}/intermediate.csr.pem"
intermediate_cert="${chain_dir}/intermediate-cert.pem"
intermediate_chain="${chain_dir}/intermediate-chain.pem"
ca_cert="${chain_dir}/ca-cert.pem"
ca_chain="${chain_dir}/ca-chain.pem"
root_serial="${chain_dir}/root-ca.srl"

tmp_root_ext="$(mktemp)"
tmp_intermediate_ext="$(mktemp)"
trap 'rm -f "$tmp_root_ext" "$tmp_intermediate_ext"' EXIT

cat >"$tmp_root_ext" <<EOF
[v3_root_ca]
basicConstraints = critical,CA:true,pathlen:1
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
EOF

cat >"$tmp_intermediate_ext" <<EOF
[v3_intermediate_ca]
basicConstraints = critical,CA:true,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
EOF

openssl req \
  -x509 \
  -newkey "rsa:${rsa_bits}" \
  -sha256 \
  -days 3650 \
  -nodes \
  -keyout "$root_key" \
  -out "$root_cert" \
  -subj "/C=US/ST=Madrid/L=Madrid/O=${chain_name}/OU=Root CA/CN=${chain_name} Root CA" \
  -extensions v3_root_ca \
  -config "$tmp_root_ext"

openssl req \
  -new \
  -newkey "rsa:${rsa_bits}" \
  -sha256 \
  -nodes \
  -keyout "$intermediate_key" \
  -out "$intermediate_csr" \
  -subj "/C=US/ST=Madrid/L=Madrid/O=${chain_name}/OU=Intermediate CA/CN=${chain_name} Intermediate CA"

openssl x509 \
  -req \
  -in "$intermediate_csr" \
  -CA "$root_cert" \
  -CAkey "$root_key" \
  -CAcreateserial \
  -CAserial "$root_serial" \
  -out "$intermediate_cert" \
  -days 1825 \
  -sha256 \
  -extensions v3_intermediate_ca \
  -extfile "$tmp_intermediate_ext"

cat "$intermediate_cert" >"$intermediate_chain"
cat "$root_cert" >"$ca_cert"
cat "$intermediate_cert" "$root_cert" >"$ca_chain"

echo "Created CA chain in ${chain_dir}"
echo "Trust anchor for the app: ${ca_cert}"
echo "Leaf issuers should chain through: ${intermediate_cert}"
