#!/bin/sh
set -e

KEYSTORE="/app/keystore.p12"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-changeme}"

# 生成自签名证书（首次运行）
if [ ! -f "$KEYSTORE" ]; then
    echo ">>> 生成自签名 TLS 证书..."
    keytool -genkeypair -noprompt \
        -alias vsec \
        -keyalg RSA -keysize 2048 \
        -sigalg SHA256withRSA \
        -storetype PKCS12 \
        -keystore "$KEYSTORE" \
        -storepass "$KEYSTORE_PASSWORD" \
        -keypass "$KEYSTORE_PASSWORD" \
        -dname "CN=backend, OU=Dev, O=VSec, L=SZ, ST=GD, C=CN" \
        -validity 3650 \
        -ext "SAN=DNS:backend,DNS:localhost,IP:127.0.0.1"
    echo ">>> 证书已生成: $KEYSTORE"
fi

echo ">>> 启动 VSec-Storage..."
exec java -jar /app/app.jar
