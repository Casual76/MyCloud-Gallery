@echo off
curl.exe -i -X POST -H "Content-Type: application/json" -d @test_ok.json http://192.168.178.58/nas/v1/auth
echo.
echo --- BAD ---
echo.
curl.exe -i -X POST -H "Content-Type: application/json" -d @test_bad.json http://192.168.178.58/nas/v1/auth