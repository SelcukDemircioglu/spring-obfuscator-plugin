#!/usr/bin/env bash
# =============================================================================
# deploy-plugin-to-nexus.sh
# Obfuscator plugin'ini Nexus Maven repository'e deploy eder.
# Dockerfile build aşaması veya Jenkins bu repo'yu plugin için kullanır.
#
# Kullanım:
#   NEXUS_USER=admin NEXUS_PASS=secret bash scripts/deploy-plugin-to-nexus.sh
# veya .env dosyasından:
#   source .env && bash scripts/deploy-plugin-to-nexus.sh
# =============================================================================
set -euo pipefail

NEXUS_BASE="${NEXUS_BASE:-https://nexus.sesasis.com}"
NEXUS_MAVEN_REPO="${NEXUS_BASE}/repository/maven-releases"
NEXUS_USER="${NEXUS_USER:-admin}"
NEXUS_PASS="${NEXUS_PASS:-(Ses@s!s.1923**)}"
PLUGIN_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -z "$NEXUS_PASS" ]]; then
  read -rsp "Nexus şifresi: " NEXUS_PASS
  echo ""
fi

echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║  Obfuscator Plugin → Nexus Maven Deploy      ║"
echo "╚══════════════════════════════════════════════╝"
echo "  Nexus: $NEXUS_MAVEN_REPO"
echo ""

cd "$PLUGIN_DIR"

# Geçici settings.xml oluştur (credentials settings.xml üzerinden iletilmeli)
SETTINGS_TMP="$(mktemp /tmp/mvn-settings-XXXXXX.xml)"
trap 'rm -f "$SETTINGS_TMP"' EXIT

cat > "$SETTINGS_TMP" << SETTINGS_EOF
<settings>
  <servers>
    <server>
      <id>nexus</id>
      <username>${NEXUS_USER}</username>
      <password>${NEXUS_PASS}</password>
    </server>
  </servers>
</settings>
SETTINGS_EOF

# 1. Plugin'i derle ve local .m2'ye yükle
echo "▶  Plugin derleniyor..."
mvn clean install -q

# 2. Nexus'a deploy et (releases reposu)
echo "▶  Nexus'a deploy ediliyor..."
mvn deploy \
  -s "$SETTINGS_TMP" \
  -DskipTests=true \
  -DaltDeploymentRepository="nexus::default::${NEXUS_MAVEN_REPO}" \
  -q

echo ""
PLUGIN_VERSION=""
while IFS= read -r line; do
  if [[ "$line" == *"<version>"* ]]; then
    PLUGIN_VERSION="${line#*<version>}"
    PLUGIN_VERSION="${PLUGIN_VERSION%</version>*}"
    PLUGIN_VERSION="${PLUGIN_VERSION//[[:space:]]/}"
    break
  fi
done < "$PLUGIN_DIR/pom.xml"
echo "✅  Plugin başarıyla deploy edildi:"
echo "    groupId:    com.obfuscator"
echo "    artifactId: spring-obfuscator-maven-plugin"
echo "    version:    $PLUGIN_VERSION"
echo "    repo:       $NEXUS_MAVEN_REPO"
echo ""
echo "Dockerfile ve pom.xml için pluginRepository:"
cat << 'EOF'
  <pluginRepositories>
    <pluginRepository>
      <id>nexus</id>
      <url>https://nexus.sesasis.com/repository/maven-releases</url>
    </pluginRepository>
  </pluginRepositories>
EOF
