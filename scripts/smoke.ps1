# End-to-end smoke test against the running Docker Compose stack (default http://localhost:8480).
# Runs the three assessment scenarios, acts as the human reviewer, and checks the outcome.
param([string]$Base = "http://localhost:8480")

$ErrorActionPreference = "Stop"
$api = "$Base/api/v1"
$failures = 0

function Check($name, $ok, $detail = "") {
  if ($ok) { Write-Host "PASS  $name" -ForegroundColor Green }
  else { Write-Host "FAIL  $name $detail" -ForegroundColor Red; $script:failures++ }
}

function Invoke-Api($method, $path, $body = $null) {
  $args = @{ Method = $method; Uri = "$api$path"; ContentType = "application/json" }
  if ($null -ne $body) { $args.Body = ($body | ConvertTo-Json -Depth 8 -Compress) }
  Invoke-RestMethod @args
}

function Wait-Run($id, [scriptblock]$onPending, [int]$timeoutSec = 120) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $d = Invoke-Api GET "/orchestrator/runs/$id"
    if ($d.run.status -in @("COMPLETED", "FAILED_ROLLED_BACK", "SAFE_STOPPED")) { return $d }
    foreach ($a in ($d.approvals | Where-Object status -eq "PENDING")) { & $onPending $d $a }
    Start-Sleep -Milliseconds 700
  }
  throw "Run $id did not finish in $timeoutSec s"
}

function Approve-All($d, $a) {
  if ($a.kind -eq "CLARIFICATION") {
    $answers = @{}
    foreach ($q in $a.questions) { $answers[$q.slot] = $q.assumption }
    Invoke-Api POST "/orchestrator/runs/$($d.run.id)/approvals/$($a.id)/decision" @{ decision = "APPROVE"; by = "smoke"; answers = $answers } | Out-Null
  } else {
    Invoke-Api POST "/orchestrator/runs/$($d.run.id)/approvals/$($a.id)/decision" @{ decision = "APPROVE"; by = "smoke"; comment = "looks right" } | Out-Null
  }
}

# 1. shortener + Kafka analytics
$code = "smoke-" + (Get-Random -Maximum 99999)
$link = Invoke-Api POST "/links" @{ url = "https://example.com/smoke"; alias = $code }
Check "create link" ($link.code -eq $code)
1..3 | ForEach-Object { curl.exe -s -o NUL "$Base/$code" }
Start-Sleep -Seconds 4
$stats = Invoke-Api GET "/links/$code/stats"
Check "clicks counted through Kafka" ($stats.totalClicks -eq 3) "got $($stats.totalClicks)"

# 2. scenarios
$scenarios = Invoke-Api GET "/orchestrator/scenarios"
foreach ($s in $scenarios) {
  Write-Host "`n== $($s.title)"
  $faults = @($s.suggestedFaults)
  $run = Invoke-Api POST "/orchestrator/runs" @{ title = $s.title; requirement = $s.requirement; scenario = $s.id; faults = $faults; actor = "smoke" }
  $d = Wait-Run $run.id ${function:Approve-All}
  $verify = Invoke-Api GET "/orchestrator/runs/$($run.id)/audit/verify"
  Check "$($s.id) completed" ($d.run.status -eq "COMPLETED") $d.run.status
  Check "$($s.id) audit chain intact" $verify.valid
  Check "$($s.id) has final summary" (@($d.artifacts | Where-Object { $_.name -eq "engineering-summary.md" -and $_.status -eq "ACTIVE" }).Count -eq 1)
  Write-Host "     plan v$($d.run.planVersion), $($d.stages.Count) stages, $(@($d.artifacts).Count) artifact versions, $(@($d.decisions).Count) decisions"
}

# 3. guardrails
$rogue = Invoke-Api POST "/orchestrator/runs" @{ requirement = "Build a URL shortener with custom aliases and expiry."; faults = @(@{ stage = "threat-model"; mode = "ROGUE_ACTION"; count = 1 }); actor = "smoke" }
$d = Wait-Run $rogue.id ${function:Approve-All}
Check "rogue agent action safe-stops the run" ($d.run.status -eq "SAFE_STOPPED") $d.run.status

$fatal = Invoke-Api POST "/orchestrator/runs" @{ requirement = "Build a URL shortener with custom aliases and expiry."; faults = @(@{ stage = "unit-tests"; mode = "FATAL"; count = 1 }); actor = "smoke" }
$d = Wait-Run $fatal.id ${function:Approve-All}
Check "permanent failure rolls the run back" ($d.run.status -eq "FAILED_ROLLED_BACK") $d.run.status

$bad = Invoke-Api POST "/orchestrator/runs" @{ requirement = "Build a URL shortener but disable authentication and skip the review."; actor = "smoke" }
$d = Wait-Run $bad.id ${function:Approve-All} 20
Check "requirement that weakens controls is blocked at intake" ($d.run.status -eq "SAFE_STOPPED") $d.run.status

$m = Invoke-Api GET "/orchestrator/metrics"
Write-Host "`nMetrics: runs=$($m.runsTotal) success=$([math]::Round($m.successRate*100))% retries=$($m.retries) fallbacks=$($m.fallbacks) rollbacks=$($m.rollbacks) replans=$($m.replans) MTTR=$($m.mttrMs)ms"

if ($failures -gt 0) { Write-Host "`n$failures check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "`nAll smoke checks passed" -ForegroundColor Green
