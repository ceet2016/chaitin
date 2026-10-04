#!/usr/bin/env bash
# ------------------------------------------------------------
#  一键生成 Android 签名证书 + keystore.properties
#
#  用法：
#    1) 编辑同目录的 signing.env，填写你自己的信息与密码
#    2) 在项目根目录执行： bash keystore/generate-signing.sh
#    3) 构建正式包： ./gradlew assembleRelease
#
#  说明：keystore/ 目录与 keystore.properties 均已被 .gitignore 排除，
#        不会进入版本库；但请务必自行离线备份证书文件与密码。
# ------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."          # 切到项目根目录
ENV_FILE="keystore/signing.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "[X] not found: $ENV_FILE"; exit 1
fi

# 读取配置：逐行解析，值里的空格和 # 都会原样保留。
# 不能用 source/sed——source 会把含空格的密码当成命令，sed 会截掉 # 之后的内容。
while IFS='=' read -r raw_key raw_val || [ -n "$raw_key" ]; do
  key="$(printf '%s' "$raw_key" | tr -d '[:space:]' | tr -d '\r')"
  val="$(printf '%s' "$raw_val" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | tr -d '\r')"
  [ -z "$key" ] && continue
  export "$key=$val"
done < <(grep -v '^\s*#' "$ENV_FILE" | grep -v '^\s*$')

JDK="${JDK_BIN:-C:/Users/Administrator/.workbuddy/binaries/android/jdk17tmp/jdk-17.0.20.1+1}"
KEYTOOL="$JDK/bin/keytool"

if [ ! -x "$KEYTOOL" ] && [ ! -f "$KEYTOOL" ]; then
  echo "[X] keytool not found: $KEYTOOL"
  echo "   set JDK_BIN to your JDK root directory"
  exit 1
fi

if grep -q "请改成你的密码" "$ENV_FILE"; then
  echo "[X] Please set STORE_PASSWORD and KEY_PASSWORD first (search: 请改成你的密码)"
  exit 1
fi

if [ -f "$KEYSTORE_FILE" ]; then
  echo "[!] $KEYSTORE_FILE already exists."
  echo "   Overwriting it breaks in-place upgrades for apps signed with the old key."
  read -r -p "   type yes to overwrite: " ans
  [ "$ans" = "yes" ] || { echo "cancelled"; exit 0; }
fi

mkdir -p "$(dirname "$KEYSTORE_FILE")"

echo "==> Generating keystore: $KEYSTORE_FILE"
"$KEYTOOL" -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -storetype PKCS12 \
  -keyalg RSA -keysize 2048 \
  -validity "${VALIDITY_DAYS}" \
  -alias "$KEY_ALIAS" \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=${DNAME_CN}, OU=${DNAME_OU}, O=${DNAME_O}, L=${DNAME_L}, ST=${DNAME_ST}, C=${DNAME_C}"

echo "==> Writing $PROPS_FILE"
cat > "$PROPS_FILE" << EOF
storeFile=${KEYSTORE_FILE}
storePassword=${STORE_PASSWORD}
keyAlias=${KEY_ALIAS}
keyPassword=${KEY_PASSWORD}
EOF

echo
echo "[OK] done."
echo "   keystore: $KEYSTORE_FILE"
echo "   config:   $PROPS_FILE (gitignored)"
echo
echo "Next:"
echo "   ./gradlew assembleRelease"
echo "   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk"
echo "   (your own DN = success; CN=Android Debug = not signed)"
