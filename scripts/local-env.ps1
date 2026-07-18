param(
    [ValidateSet('start', 'stop', 'reset', 'status', 'verify')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $ProjectRoot 'compose.local.yml'

function Assert-DockerEngine {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    docker info *> $null
    $dockerExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference

    if ($dockerExitCode -ne 0) {
        throw 'Docker engine is unavailable. Start Docker Desktop and retry.'
    }
}

function Invoke-Compose {
    param([string[]]$ComposeArguments)
    & docker compose -f $ComposeFile @ComposeArguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($ComposeArguments -join ' ')"
    }
}

function Verify-Databases {
    Write-Host 'Checking MySQL schema and Agent seed data...'
    Invoke-Compose @('exec', '-T', 'mysql', 'mysql', '-uroot', '-p123456', '-N', '-e', "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ai-agent-station-study';")
    Invoke-Compose @('exec', '-T', 'mysql', 'mysql', '-uroot', '-p123456', '-N', '-D', 'ai-agent-station-study', '-e', 'SELECT COUNT(*) FROM ai_agent;')

    Write-Host 'Checking PGVector extension and embedding dimension...'
    Invoke-Compose @('exec', '-T', 'pgvector', 'psql', '-U', 'postgres', '-d', 'ai-rag-knowledge', '-tAc', "SELECT extversion FROM pg_extension WHERE extname='vector';")
    Invoke-Compose @('exec', '-T', 'pgvector', 'psql', '-U', 'postgres', '-d', 'ai-rag-knowledge', '-tAc', "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a WHERE a.attrelid='public.vector_store'::regclass AND a.attname='embedding';")
}

Assert-DockerEngine

switch ($Action) {
    'start' {
        Invoke-Compose @('up', '-d', '--wait')
        Verify-Databases
    }
    'stop' {
        Invoke-Compose @('down')
    }
    'reset' {
        Invoke-Compose @('down', '--volumes', '--remove-orphans')
        Invoke-Compose @('up', '-d', '--wait')
        Verify-Databases
    }
    'status' {
        Invoke-Compose @('ps')
    }
    'verify' {
        Verify-Databases
    }
}
