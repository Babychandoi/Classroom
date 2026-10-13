<# Update only the application and assessment broker of an existing Compose project.
   Preserve the running application environment; never recreate database containers.
   Generated overlays contain secrets and stay under ignored .artifacts/. #>
param(
    [string]$ProjectName='classroom-demo',
    [string]$EnvFile='D:\classroom-server\infra\.env',
    [switch]$SkipBuild,
    [switch]$SkipBackup
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Invoke-Docker([string[]]$Arguments) { & docker @Arguments; if($LASTEXITCODE -ne 0){throw "Docker action failed: $($Arguments[0])"} }
function Inspect-Service([string]$Service) {
    $ids=@(& docker ps -aq --filter "label=com.docker.compose.project=$ProjectName" --filter "label=com.docker.compose.service=$Service")
    if($LASTEXITCODE -ne 0 -or $ids.Count -ne 1){throw "Expected one $Service container in project $ProjectName."}
    $data=(& docker inspect $ids[0] | ConvertFrom-Json)[0]
    if($LASTEXITCODE -ne 0){throw "Cannot inspect $Service."};return $data
}
function Application-Environment($Container,[switch]$Frontend) {
    $result=@{};foreach($entry in $Container.Config.Env){$pair=$entry.Split('=',2);$key=$pair[0]
        if($key -in @('PATH','JAVA_HOME','JAVA_VERSION','LANG','LANGUAGE','LC_ALL','HOME','HOSTNAME')){continue}
        if($Frontend -and $key -notmatch '^(APP_|REAL_IP_|MINIO_)'){continue}
        # Compose interpolates dollar signs even inside JSON/YAML values.
        $result[$key]=$pair[1].Replace('$','$$')
    };return $result
}
function Wait-Service([string]$Service) {
    $limit=(Get-Date).AddMinutes(5)
    do{$container=Inspect-Service $Service
        if($container.State.Status -eq 'exited'){throw "$Service exited during startup."}
        if($container.State.Health.Status -eq 'healthy'){return}
        Start-Sleep -Seconds 3
    }while((Get-Date) -lt $limit)
    throw "$Service did not become healthy within 5 minutes."
}
Push-Location $repo
try{
    if($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]+$'){throw 'Invalid project name.'}
    $EnvFile=[IO.Path]::GetFullPath($EnvFile);if(-not(Test-Path -LiteralPath $EnvFile -PathType Leaf)){throw 'Deployment env file is missing.'}
    $backend=Inspect-Service 'backend';$frontend=Inspect-Service 'frontend'
    if(-not $SkipBackup){& (Join-Path $PSScriptRoot 'backup.ps1') -ProjectName $ProjectName}
    $release=Get-Date -Format 'yyyyMMdd-HHmmss';$dir=Join-Path $repo ".artifacts/deploy-$release";New-Item -ItemType Directory -Path $dir | Out-Null
    $appEnv=Application-Environment $backend;$frontEnv=Application-Environment $frontend -Frontend
    $token=$appEnv['ASSESSMENT_SANDBOX_TOKEN']
    if(-not $token -or $token.Length -lt 32){$bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create();try{$rng.GetBytes($bytes)}finally{$rng.Dispose()};$token=[BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()}
    $appEnv['ASSESSMENT_SANDBOX_URL']='http://assessment-sandbox:8081';$appEnv['ASSESSMENT_SANDBOX_TOKEN']=$token
    if(-not $appEnv['SCHEDULER_POOL_SIZE'] -or [int]$appEnv['SCHEDULER_POOL_SIZE'] -lt 12){$appEnv['SCHEDULER_POOL_SIZE']='12'}
    $tokenFile=Join-Path $dir 'sandbox.env';[IO.File]::WriteAllText($tokenFile,"ASSESSMENT_SANDBOX_TOKEN=$token`n",(New-Object Text.UTF8Encoding($false)))
    $newBackend="classroom/assessment-backend:$release";$newFrontend="classroom/assessment-frontend:$release"
    $oldBackend="classroom/rollback-assessment-backend:$release";$oldFrontend="classroom/rollback-assessment-frontend:$release"
    Invoke-Docker (@('tag',$backend.Image,$oldBackend));Invoke-Docker (@('tag',$frontend.Image,$oldFrontend))
    $overlay=@{services=@{backend=@{image=$newBackend;environment=$appEnv};frontend=@{image=$newFrontend;environment=$frontEnv}}}
    $runtimeFile=Join-Path $dir 'runtime.json';$rollbackFile=Join-Path $dir 'rollback.json'
    [IO.File]::WriteAllText($runtimeFile,($overlay|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
    $overlay.services.backend.image=$oldBackend;$overlay.services.frontend.image=$oldFrontend
    [IO.File]::WriteAllText($rollbackFile,($overlay|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
    $base=@('compose','-p',$ProjectName,'--env-file',$EnvFile,'--env-file',$tokenFile,'-f','infra/compose.yaml','-f','infra/compose.assessment.yaml')
    $compose=$base+@('-f',$runtimeFile)
    Invoke-Docker ($compose+@('config','--quiet'))
    foreach($worker in @('python:3.13-alpine','node:22-alpine')){if(-not(& docker image ls -q $worker)){Invoke-Docker (@('pull',$worker))}}
    if(-not $SkipBuild){Invoke-Docker ($compose+@('build','backend','frontend','assessment-sandbox'))}
    else{Invoke-Docker (@('tag',$backend.Image,$newBackend));Invoke-Docker (@('tag',$frontend.Image,$newFrontend))}
    try{
        Invoke-Docker ($compose+@('up','-d','--no-deps','--no-build','assessment-sandbox'));Wait-Service 'assessment-sandbox'
        Invoke-Docker ($compose+@('up','-d','--no-deps','--no-build','backend'));Wait-Service 'backend'
        Invoke-Docker ($compose+@('up','-d','--no-deps','--no-build','frontend'));Wait-Service 'frontend'
    }catch{
        Write-Host 'Application startup failed; restoring the saved application images.'
        Invoke-Docker ($base+@('-f',$rollbackFile,'up','-d','--no-deps','--no-build','backend','frontend'))
        throw
    }
    @{project=$ProjectName;release=$release;backendImage=$newBackend;frontendImage=$newFrontend;rollback=$rollbackFile}|ConvertTo-Json|Set-Content -Encoding UTF8 (Join-Path $dir 'manifest.json')
    Write-Host "Application deployment is healthy. Project: $ProjectName. Evidence: .artifacts/deploy-$release/manifest.json"
}finally{Pop-Location}
