@echo off
REM ============================================================
REM Copy ALL Gemma 4 E4B model + companion files from AI Edge
REM Gallery to /sdcard/Download/gemma_model/
REM
REM The GPU backend REQUIRES companion files (.xnnpack_cache, .bin)
REM to be in the same directory as the main .litertlm file.
REM
REM Run this from your PC with the Xiaomi connected via USB.
REM ============================================================

set SRC=/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E4B_it/20260325
set DST=/sdcard/Download/gemma_model

echo === Gemma 4 E4B Companion File Copier ===
echo.
echo Source: %SRC%
echo Dest:   %DST%
echo.

REM Create destination directory
adb shell mkdir -p %DST%

echo.
echo [1/8] Listing source directory...
adb shell ls -la %SRC%/

echo.
echo [2/8] Copying main model file...
adb shell cp "%SRC%/gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm" "%DST%/"

echo.
echo [3/8] Copying audio_adapter xnnpack cache...
adb shell cp "%SRC%/.litertlm.audio_adapter.xnnpack_cache" "%DST%/"

echo.
echo [4/8] Copying audio_encoder xnnpack cache...
adb shell cp "%SRC%/.litertlm.audio_encoder.xnnpack_cache" "%DST%/"

echo.
echo [5/8] Copying static_audio_encoder xnnpack cache...
adb shell cp "%SRC%/.litertlm.static_audio_encoder.xnnpack_cache" "%DST%/"

echo.
echo [6/8] Copying weight shard 1...
adb shell cp "%SRC%/.litertlm_16442536968298684338.bin" "%DST%/"

echo.
echo [7/8] Copying mldrift program cache...
adb shell cp "%SRC%/.litertlm_1776297412_3609411584_mldrift_program_cache.bin" "%DST%/"

echo.
echo [8/8] Copying weight shard 2...
adb shell cp "%SRC%/.litertlm_5818495038867434237.bin" "%DST%/"

echo.
echo === Verifying destination ===
adb shell ls -la %DST%/

echo.
echo === Done! ===
echo.
echo If cp fails with "Permission denied", try one of:
echo   1. adb shell su -c "cp ..."  (if rooted)
echo   2. adb shell content read --uri content://... (SAF method)
echo   3. Copy files via a file manager app on the phone that has
echo      "All files access" permission (e.g. Solid Explorer)
echo.
echo After copying, open the POC app and tap "Scan for Model".
echo The app searches /sdcard/Download/gemma_model/ automatically.
pause
