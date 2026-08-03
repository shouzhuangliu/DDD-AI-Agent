param(
    [string]$HostName = $(if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' }),
    [int]$Port = $(if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 }),
    [string]$UserName = $(if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { 'root' }),
    [string]$Password = $(if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { '1234' }),
    [string]$Database = $(if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { 'ai-agent-station-study' })
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sqlRoot = Join-Path $projectRoot 'ai-agent-station-study-app/src/main/resources/sql/mysql'

$mysqlCommand = Get-Command mysql.exe -ErrorAction SilentlyContinue
$mysqlPath = if ($null -ne $mysqlCommand) { $mysqlCommand.Source } else { $null }
if ($null -eq $mysqlPath) {
    foreach ($candidate in @(
        'D:\develop\mysql-8.0.42-winx64\bin\mysql.exe',
        'D:\mysql\mysql-8.0.42-winx64\bin\mysql.exe',
        'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    )) {
        if (Test-Path -LiteralPath $candidate) {
            $mysqlPath = $candidate
            break
        }
    }
}
if ($null -eq $mysqlPath) {
    throw 'mysql.exe not found. Add the MySQL bin directory to PATH or update the candidate paths in this script.'
}

$mysqlArgs = @(
    '--protocol=tcp', '-h', $HostName, '-P', "$Port", '-u', $UserName,
    '--default-character-set=utf8mb4', '-D', $Database
)
$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $Password

function Invoke-SqlFile {
    param([string]$Path)

    Write-Host "Applying $([IO.Path]::GetFileName($Path))"
    Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | & $mysqlPath @mysqlArgs
    if ($LASTEXITCODE -ne 0) {
        throw "SQL execution failed: $Path (exit code $LASTEXITCODE)"
    }
}

try {
    $bootstrapArgs = $mysqlArgs | Where-Object { $_ -ne '-D' -and $_ -ne $Database }
    $databaseIdentifier = $Database.Replace('`', '``')
    & $mysqlPath @bootstrapArgs -e "CREATE DATABASE IF NOT EXISTS ``$databaseIdentifier`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create or access database $Database (exit code $LASTEXITCODE)"
    }

    $files = @(
        (Join-Path $sqlRoot 'create_tables.sql'),
        (Join-Path $sqlRoot 'init_agent_system.sql'),
        (Join-Path $sqlRoot 'init_data.sql'),
        (Join-Path $sqlRoot 'init_intent_data.sql'),
        (Join-Path $sqlRoot 'init_react_data.sql')
    )
    $files += Get-ChildItem (Join-Path $sqlRoot 'migrations') -File -Filter '*.sql' |
        Sort-Object Name |
        Select-Object -ExpandProperty FullName

    foreach ($sqlFile in $files) {
        Invoke-SqlFile -Path $sqlFile
    }

    $tableCount = & $mysqlPath @mysqlArgs -N -e 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();'
    $agentCount = & $mysqlPath @mysqlArgs -N -e 'SELECT COUNT(*) FROM ai_agent;'
    $availableAtCount = & $mysqlPath @mysqlArgs -N -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='analysis_job' AND column_name='available_at';"
    if ([int]$tableCount -lt 50 -or [int]$agentCount -lt 1 -or [int]$availableAtCount -ne 1) {
        throw "Database verification failed: tables=$tableCount agents=$agentCount available_at=$availableAtCount"
    }
    Write-Host "Database ready: tables=$tableCount agents=$agentCount available_at=$availableAtCount"
}
finally {
    $env:MYSQL_PWD = $previousMysqlPassword
}
