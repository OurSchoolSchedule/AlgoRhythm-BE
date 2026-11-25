param([ValidateSet('serve','test')][string]$Mode='serve', [switch]$DevLogin)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not $env:DEMO_DB_PASSWORD) { throw 'Set DEMO_DB_PASSWORD to the password used for the local Compose volume.' }
$db = if ($Mode -eq 'test') { 'algorhythm_test' } else { 'algorhythm_demo' }
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:33306/${db}?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
$env:SPRING_DATASOURCE_USERNAME='demo'
$env:SPRING_DATASOURCE_PASSWORD=$env:DEMO_DB_PASSWORD
$env:REDISHOST='127.0.0.1'
$env:REDISPORT='36379'
$env:REDISPASSWORD=$env:DEMO_DB_PASSWORD
$env:SPRING_DATA_REDIS_PASSWORD=$env:DEMO_DB_PASSWORD
$env:JWT_SECRET=([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
$env:OPENAI_API_KEY='offline-demo'
$env:RESEND_API_KEY='offline-demo'
$env:RESEND_FROM_EMAIL='demo@example.invalid'
if ($Mode -eq 'test') {
    $env:BE_A1_AUTH_TEST='1'
    $env:M3_MYSQL_TEST='1'
    $env:R1_MYSQL_TEST='1'
    $env:R2_MYSQL_TEST='1'
    $env:M2_FULL_BENCHMARK='1'
    & .\gradlew.bat test --rerun-tasks
} else {
    $serverArgs = '--spring.config.additional-location=file:./demo/local.yml --server.port=8080 --spring.profiles.active=local'
    $enableDevLogin = $DevLogin.IsPresent.ToString().ToLowerInvariant()
    & .\gradlew.bat bootRun "--args=$serverArgs --app.auth.dev-login-enabled=$enableDevLogin"
}
exit $LASTEXITCODE
