#!/usr/bin/env bash
# Fetches the Gradle wrapper jar for fabric-mod/.
#
# The wrapper script and its properties are committed, but gradle-wrapper.jar is a
# binary that this repository does not carry. Run this once before the first
# `./gradlew build`; it needs network access, or a Gradle installation to fall back
# on. It prints the SHA-256 of whatever it fetched so the result can be checked
# against Gradle's published checksums.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WRAPPER_DIR="$ROOT/fabric-mod/gradle/wrapper"
JAR="$WRAPPER_DIR/gradle-wrapper.jar"
PROPERTIES="$WRAPPER_DIR/gradle-wrapper.properties"

if [[ -f "$JAR" ]]; then
	echo "gradle-wrapper.jar is already present."
else
	VERSION="$(sed -n 's|.*gradle-\(.*\)-bin.zip|\1|p' "$PROPERTIES")"
	URL="https://raw.githubusercontent.com/gradle/gradle/v${VERSION}/gradle/wrapper/gradle-wrapper.jar"
	echo "Fetching the Gradle ${VERSION} wrapper jar..."
	if command -v curl >/dev/null 2>&1; then
		curl -fL --retry 3 -o "$JAR" "$URL"
	elif command -v wget >/dev/null 2>&1; then
		wget -O "$JAR" "$URL"
	else
		echo "Neither curl nor wget is available." >&2
		echo "Install one of them, or run: gradle wrapper --gradle-version ${VERSION}" >&2
		exit 1
	fi
fi

# A wrapper jar is a zip whose entry point is GradleWrapperMain.
if command -v unzip >/dev/null 2>&1; then
	unzip -l "$JAR" | grep -q 'org/gradle/wrapper/GradleWrapperMain.class' \
		|| { echo "The downloaded jar does not look like a Gradle wrapper jar." >&2; exit 1; }
fi

if command -v sha256sum >/dev/null 2>&1; then
	echo "SHA-256: $(sha256sum "$JAR" | cut -d' ' -f1)"
elif command -v shasum >/dev/null 2>&1; then
	echo "SHA-256: $(shasum -a 256 "$JAR" | cut -d' ' -f1)"
fi

echo "Done. Build with: cd fabric-mod && ./gradlew build"
