# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 D-Modem Authors
#
# Sourced by the Dockerfile's steps: an HTTPS_PROXY (Docker passes the
# usual proxy variables to a build) handed to Java as well, which doesn't
# read them (Gradle, sdkmanager).
p=${HTTPS_PROXY:-${https_proxy:-}}
if [ -n "$p" ]; then
	hp=${p#*://}
	hp=${hp#*@}
	hp=${hp%%/*}
	host=${hp%:*}
	port=${hp##*:}
	[ "$port" = "$hp" ] && port=443
	export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttps.proxyHost=$host -Dhttps.proxyPort=$port -Dhttp.proxyHost=$host -Dhttp.proxyPort=$port -Dhttp.nonProxyHosts=localhost|127.*"
fi
