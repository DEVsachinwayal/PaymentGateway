#!/usr/bin/env bash
taskkill //F //IM java.exe >/dev/null 2>&1 && echo "stopped all java processes" || echo "nothing running"
