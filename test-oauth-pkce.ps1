Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   TEST DE BOUT EN BOUT OAUTH 2.0 PKCE & VALIDATION RS256 " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Paramètres PKCE réels
$codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
# Empreinte SHA-256 en Base64URL exacte :
$codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
$clientId = "sbe-front"
$redirectUri = "http://localhost:4200/callback"

Write-Host "`n[1/4] Envoi des identifiants et du code_challenge a /authorize..." -ForegroundColor Yellow

$authBody = @{
    client_id             = $clientId
    redirect_uri          = $redirectUri
    state                 = "xyz123"
    nonce                 = "nonce456"
    code_challenge        = $codeChallenge
    code_challenge_method = "S256"
    username              = "occupant"
    password              = "occupant123"
}

# Appel avec -UseBasicParsing et interception de la redirection 302
$authResponse = Invoke-WebRequest -Uri "http://127.0.0.1:8081/iam/api/oauth2/authorize" `
    -Method Post `
    -Body $authBody `
    -MaximumRedirection 0 `
    -UseBasicParsing `
    -ErrorAction SilentlyContinue

$location = $authResponse.Headers.Location
if ($location -match "code=([^&]+)") {
    $authCode = [System.Web.HttpUtility]::UrlDecode($matches[1])
    Write-Host "  -> Succes ! Code d'autorisation recu : $authCode" -ForegroundColor Green
} else {
    Write-Host "  -> Echec de recuperation du code d'autorisation." -ForegroundColor Red
    return
}

# 2. Échange du code contre les tokens (POST /oauth2/token)
Write-Host "`n[2/4] Echange du code + code_verifier aupres du token endpoint..." -ForegroundColor Yellow

$tokenBody = @{
    grant_type    = "authorization_code"
    client_id     = $clientId
    redirect_uri  = $redirectUri
    code          = $authCode
    code_verifier = $codeVerifier
}

try {
    $tokenResponse = Invoke-RestMethod -Uri "http://127.0.0.1:8081/iam/api/oauth2/token" `
        -Method Post `
        -Body $tokenBody `
        -ContentType "application/x-www-form-urlencoded"

    $accessToken = $tokenResponse.access_token
    $idToken = $tokenResponse.id_token
    $refreshToken = $tokenResponse.refresh_token

    Write-Host "  -> Tokens recus avec succes !" -ForegroundColor Green
    Write-Host "     Access Token (RS256) : $($accessToken.Substring(0, 45))..." -ForegroundColor Gray
    Write-Host "     Type : $($tokenResponse.token_type) | Expire dans : $($tokenResponse.expires_in)s" -ForegroundColor Gray
} catch {
    Write-Host "  -> Erreur lors de l'echange : $($_.Exception.Message)" -ForegroundColor Red
    return
}

# 3. Test de l'API sans token (doit être refusé avec 401)
Write-Host "`n[3/4] Test de l'API protegee SANS token (Attendu : 401 Unauthorized)..." -ForegroundColor Yellow
try {
    $noAuthResp = Invoke-WebRequest -Uri "http://127.0.0.1:8081/api/readings" -Method Get -UseBasicParsing -ErrorAction Stop
    Write-Host "  -> Anomalie : l'API a repondu $($noAuthResp.StatusCode) sans token !" -ForegroundColor Red
} catch {
    if ($_.Exception.Response.StatusCode -eq 401) {
        Write-Host "  -> Succes ! L'API a bien refuse l'acces (401 Unauthorized)." -ForegroundColor Green
    } else {
        Write-Host "  -> Reponse : $($_.Exception.Message)" -ForegroundColor Red
    }
}

# 4. Test de l'API avec l'access_token RS256 (doit être accepté avec 200)
Write-Host "`n[4/4] Test de l'API protegee AVEC l'Access Token RS256 (Attendu : 200 OK)..." -ForegroundColor Yellow
$headers = @{
    Authorization = "Bearer $accessToken"
}

try {
    $apiResp = Invoke-RestMethod -Uri "http://127.0.0.1:8081/api/readings" -Method Get -Headers $headers
    Write-Host "  -> Succes total ! L'API a valide la signature RS256 et repondu 200 OK." -ForegroundColor Green
    Write-Host "  -> Nombre de mesures remontees : $($apiResp.Count)" -ForegroundColor Gray
} catch {
    Write-Host "  -> Erreur lors de l'appel avec token : $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n==========================================================" -ForegroundColor Cyan