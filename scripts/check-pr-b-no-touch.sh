#!/usr/bin/env bash
# scripts/check-pr-b-no-touch.sh
#
# PR-B 冲突避让守约脚本(老板硬规则)。
#
# 验证 PR-B 分支没碰到:
#   - PR-A 改区(SipLogTab / SipFlowView,已合 main)
#   - PR-C 巨型 Screen(HomeScreen / CatalogManagementScreen / MediaScreen)
#   - P1.5 Engine 改区(SimulatorEngine / AppEngine / EngineWiring,已合 main)
#   - SipViewModel(不动 ViewModel 业务行为)
#
# 用法:
#   bash scripts/check-pr-b-no-touch.sh [base-ref] [head-ref]
#   默认 base=main,head=HEAD
set -e

BASE="${1:-main}"
HEAD_REF="${2:-HEAD}"

echo "守约检查:$BASE..$HEAD_REF"
echo ""

VIOLATIONS=()

check_no_touch() {
    local label="$1"
    local pattern="$2"
    local files
    files=$(git diff --name-only "$BASE" "$HEAD_REF" -- $pattern 2>/dev/null)
    if [ -n "$files" ]; then
        VIOLATIONS+=("❌ $label 被修改:")
        while IFS= read -r line; do VIOLATIONS+=("    $line"); done <<< "$files"
    else
        echo "✅ $label 未碰"
    fi
}

check_no_touch "PR-A SipLogTab/SipFlowView 改区" \
    "composeApp/src/commonMain/kotlin/com/uvp/sim/ui/log/"
check_no_touch "PR-C HomeScreen 巨型" \
    "composeApp/src/commonMain/kotlin/com/uvp/sim/ui/HomeScreen.kt"
check_no_touch "PR-C CatalogManagementScreen 巨型" \
    "composeApp/src/commonMain/kotlin/com/uvp/sim/ui/capability/CatalogManagementScreen.kt"
check_no_touch "PR-C MediaScreen" \
    "composeApp/src/commonMain/kotlin/com/uvp/sim/ui/MediaScreen.kt"
check_no_touch "P1.5 Engine SimulatorEngine.kt" \
    "shared/src/commonMain/kotlin/com/uvp/sim/domain/SimulatorEngine.kt"
check_no_touch "P1.5 Engine AppEngine.kt" \
    "shared/src/commonMain/kotlin/com/uvp/sim/app/AppEngine.kt"
check_no_touch "P1.5 EngineWiring.kt" \
    "shared/src/commonMain/kotlin/com/uvp/sim/app/EngineWiring.kt"
check_no_touch "SipViewModel.kt" \
    "androidApp/src/main/kotlin/com/uvp/sim/SipViewModel.kt"

if [ ${#VIOLATIONS[@]} -gt 0 ]; then
    echo ""
    printf '%s\n' "${VIOLATIONS[@]}"
    echo ""
    echo "PR-B 违反硬规则,需要老板拍板才能继续。"
    exit 1
fi

echo ""
echo "✅ 全部硬规则通过 — PR-B 改区合法"
