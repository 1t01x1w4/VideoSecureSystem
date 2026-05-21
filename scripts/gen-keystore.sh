#!/bin/bash
# 生成开发用自签名 TLS 证书 (keystore.p12)
# 用法: bash scripts/gen-keystore.sh

KEYSTORE="backend/src/main/resources/keystore.p12"
PASSWORD="${KEYSTORE_PASSWORD:-vsec123}"
ALIAS="vsec"

echo ">>> 生成自签名证书: $KEYSTORE"
keytool -genkeypair \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -sigalg SHA256withRSA \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -dname "CN=localhost, OU=Dev, O=VSec, L=SZ, ST=GD, C=CN" \
  -validity 3650 \
  -ext "SAN=DNS:localhost,IP:127.0.0.1"

echo ">>> 证书已生成: $KEYSTORE"
echo ">>> 密码: $PASSWORD"
echo ">>> 请确保 application.yml 中 keystore 密码与此一致"
