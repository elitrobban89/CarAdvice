# Nattlig extraktion av expertinsikter från motorsajter — körs av Windows Schemaläggaren.
# Nycklarna läses från användarens miljövariabler (sätts en gång med setx):
#   setx GROQ_API_KEY "gsk_..."
#   setx ADMIN_KEY "..."
# Loggar till insights_nightly.log i projektmappen.

$ErrorActionPreference = "Continue"
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectDir

$logFile = Join-Path $projectDir "insights_nightly.log"
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

# Läs nycklar från User-scope (Schemaläggaren startar inte alltid med färsk miljö)
if (-not $env:GROQ_API_KEY) { $env:GROQ_API_KEY = [System.Environment]::GetEnvironmentVariable('GROQ_API_KEY', 'User') }
if (-not $env:ADMIN_KEY)    { $env:ADMIN_KEY    = [System.Environment]::GetEnvironmentVariable('ADMIN_KEY', 'User') }

Add-Content $logFile "`n===== $timestamp — startar nattlig insiktskörning ====="

if (-not $env:GROQ_API_KEY -or -not $env:ADMIN_KEY) {
    Add-Content $logFile "AVBRYTER: GROQ_API_KEY eller ADMIN_KEY saknas i användarens miljövariabler."
    exit 1
}

py extract_web_insights.py --upload 2>&1 | Out-File -FilePath $logFile -Append -Encoding utf8

$exitCode = $LASTEXITCODE
Add-Content $logFile "===== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') — klar (exit $exitCode) ====="

# Håll loggen under ~500 kB genom att behålla de sista 2000 raderna
$lines = Get-Content $logFile
if ($lines.Count -gt 2000) {
    $lines | Select-Object -Last 2000 | Set-Content $logFile -Encoding utf8
}

exit $exitCode
