#requires -Version 5.1
<#
  Copies the reusable AI / RAG / middleware slice of GymMind into Nova AI.

  What it does:
    1. clears backend/src/main/java/com/nova (fresh, deterministic output)
    2. copies a curated file set (ai, knowledge, ocr, shared, iam, tenancy, audit, agent, platform)
    3. skips the fitness-business classes that the AI layer does not need
    4. rewrites packages: com.gymmind -> com.nova
    5. rewrites brand/class prefixes: GymMind -> Nova, gymmind -> nova
    6. renames the two classes whose names carry the old brand

  IMPORTANT: keep this file ASCII-only. Windows PowerShell 5.1 reads a
  UTF-8 file without BOM as ANSI, and non-ASCII bytes can corrupt the script.

  Usage (from anywhere):
    powershell -ExecutionPolicy Bypass -File ".\scripts\migrate-from-gymmind.ps1"
#>

$ErrorActionPreference = 'Stop'

$SourceRoot = 'D:\java\project\full-project\GymMind'
$TargetRoot = 'D:\java\project\full-project\Nova AI'

$SourceJava = Join-Path $SourceRoot 'backend\src\main\java\com\gymmind'
$TargetJava = Join-Path $TargetRoot 'backend\src\main\java\com\nova'

if (-not $TargetJava.EndsWith('com\nova')) {
    throw "refusing to clear unexpected target: $TargetJava"
}

# ---------------------------------------------------------------------------
# Packages to migrate wholesale
# ---------------------------------------------------------------------------
$IncludeDirs = @(
    'ai',
    'knowledge',
    'ocr',
    'shared',
    'iam',
    'tenancy',
    'audit',
    'agent',
    'platform'
)

# Root-level application class (renamed later)
$RootFiles = @('GymMindApplication.java')

# ---------------------------------------------------------------------------
# Fitness-business classes that the AI layer must not drag in
# (paths are relative to com/gymmind, always with forward slashes)
# ---------------------------------------------------------------------------
$ExcludeFiles = @(
    # --- ai/api: controllers bound to analytics / workout / coach domains ---
    'ai/api/AiAnalysisController.java',
    'ai/api/AiWeeklyReportController.java',
    'ai/api/AiWorkoutPlanController.java',
    'ai/api/CoachAssistantController.java',

    # --- ai/application: services bound to analytics / workout / coach / member ---
    'ai/application/AiAnalysisResult.java',
    'ai/application/MemberAnalysisInput.java',
    'ai/application/DefaultAiAnalysisService.java',
    'ai/application/AiWorkoutPlanDraftRequest.java',
    'ai/application/AiWorkoutPlanDraftResponse.java',
    'ai/application/AiWorkoutPlanDraftService.java',
    'ai/application/AiWorkoutPlanTarget.java',
    'ai/application/DefaultAiWorkoutPlanDraftService.java',
    'ai/application/WorkoutPlanPromptComposer.java',
    'ai/application/DefaultCoachAssistantService.java',
    'ai/infrastructure/ExerciseRecommendationCandidateSource.java',

    # --- ai/harness/plugins: gym operations / churn / booking / article tools ---
    'ai/harness/plugins/ArticleSearchHarnessPlugin.java',
    'ai/harness/plugins/ImportArticleHarnessPlugin.java',
    'ai/harness/plugins/CourseBookingContextCapability.java',
    'ai/harness/plugins/GymOperationsMetricsCapability.java',
    'ai/harness/plugins/GymOperationsMetricsTool.java',
    'ai/harness/plugins/MemberChurnRiskCapability.java',
    'ai/harness/plugins/MemberFollowUpItem.java',
    'ai/harness/plugins/MemberTrainingContextCapability.java',
    'ai/harness/plugins/OperationsGroupBy.java',
    'ai/harness/plugins/OperationsMetric.java',
    'ai/harness/plugins/OperationsMetricsContext.java',
    'ai/harness/plugins/OperationsMetricsQuery.java',
    'ai/harness/plugins/OperationsQueryIntent.java',
    'ai/harness/plugins/PlatformAiAdminCapability.java',
    'ai/harness/plugins/TrainingPlanCalendarCapability.java',

    # --- iam: implementations mixed with membership domain ---
    # UserAdministrationService is a pure interface (holds the UserSummary record)
    # and must stay, otherwise UserResponse / RoleResponse fail to compile.
    'iam/application/DefaultUserAdministrationService.java',
    'iam/application/UserStatisticsService.java',
    'iam/application/DefaultUserStatisticsService.java',
    'iam/application/UserStatisticsView.java',
    'iam/api/UserController.java',
    'iam/api/RoleController.java',
    'iam/api/UserStatisticsController.java',

    # --- knowledge / platform: fitness demo seed data ---
    'knowledge/bootstrap/FitnessKnowledgeDemoInitializer.java',
    'platform/bootstrap/MemberWorkoutScheduleSchemaInitializer.java'
)

# Whole subtrees to skip
$ExcludePrefixes = @(
    'ai/harness/terminal/'
)

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function ConvertTo-ForwardSlash([string]$value) {
    return $value.Replace('\', '/')
}

function Test-Excluded([string]$relativePath) {
    if ($ExcludeFiles -contains $relativePath) { return $true }
    foreach ($prefix in $ExcludePrefixes) {
        if ($relativePath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

# ---------------------------------------------------------------------------
# Fresh target
# ---------------------------------------------------------------------------
if (Test-Path $TargetJava) {
    Remove-Item -Path $TargetJava -Recurse -Force
}
New-Item -ItemType Directory -Path $TargetJava -Force | Out-Null

# ---------------------------------------------------------------------------
# Collect source files
# ---------------------------------------------------------------------------
$collected = New-Object System.Collections.Generic.List[string]

foreach ($dir in $IncludeDirs) {
    $fullDir = Join-Path $SourceJava $dir
    if (-not (Test-Path $fullDir)) {
        Write-Warning "missing source dir: $fullDir"
        continue
    }
    Get-ChildItem -Path $fullDir -Recurse -File -Filter *.java | ForEach-Object {
        $relative = ConvertTo-ForwardSlash $_.FullName.Substring($SourceJava.Length + 1)
        if (-not (Test-Excluded $relative)) {
            [void]$collected.Add($relative)
        }
    }
}

foreach ($rootFile in $RootFiles) {
    if (Test-Path (Join-Path $SourceJava $rootFile)) {
        [void]$collected.Add($rootFile)
    }
}

# ---------------------------------------------------------------------------
# Copy + rewrite
# ---------------------------------------------------------------------------
$written = 0

foreach ($relative in $collected) {
    $sourcePath = Join-Path $SourceJava ($relative.Replace('/', '\'))
    $targetPath = Join-Path $TargetJava ($relative.Replace('/', '\'))

    # brand-carrying class names
    if ($relative -eq 'GymMindApplication.java') {
        $targetPath = Join-Path $TargetJava 'NovaApplication.java'
    }
    if ($relative -eq 'shared/security/GymMindPrincipal.java') {
        $targetPath = Join-Path $TargetJava 'shared\security\NovaPrincipal.java'
    }

    $targetDir = Split-Path $targetPath -Parent
    if (-not (Test-Path $targetDir)) {
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    }

    $text = [System.IO.File]::ReadAllText($sourcePath, [System.Text.Encoding]::UTF8)

    # package first, then brand, then lowercase config keys, then env vars
    $text = $text.Replace('com.gymmind', 'com.nova')
    $text = $text.Replace('GymMind', 'Nova')
    $text = $text.Replace('gymMind', 'nova')
    $text = $text.Replace('gymmind', 'nova')
    $text = $text.Replace('GYMMIND', 'NOVA')

    [System.IO.File]::WriteAllText($targetPath, $text, $Utf8NoBom)
    $written++
}

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
$copiedByPackage = $collected | Group-Object { ($_ -split '/')[0] } | Sort-Object Name

Write-Host ''
Write-Host 'Nova AI migration complete'
Write-Host "  source : $SourceJava"
Write-Host "  target : $TargetJava"
Write-Host "  files  : $written"
Write-Host ''
foreach ($group in $copiedByPackage) {
    Write-Host ("  {0,-24} {1,4}" -f $group.Name, $group.Count)
}
