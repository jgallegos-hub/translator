# English-interlocutor probe: plays synthetic English phrases (plus two Spanish controls) from the PC
# speakers, as if someone talked to Abraham ~1 m away, and logs when each one played so it can be
# matched against the phone's logcat. Uses the voices installed with Windows (no downloads).
#
# Saved as UTF-8 with BOM: Windows PowerShell 5.1 reads BOM-less files as ANSI and mangles accents.
# Usage: powershell -NoProfile -File english_probe.ps1 <out_log> [gapSeconds]
param([Parameter(Mandatory = $true)][string]$OutLog, [int]$GapSeconds = 7)

Add-Type -AssemblyName System.Speech
$phrases = @(
    @{ voice = "Microsoft David Desktop"; lang = "en"; text = "Hi there, welcome. How many people?" },
    @{ voice = "Microsoft Zira Desktop";  lang = "en"; text = "Do you want a table for two?" },
    @{ voice = "Microsoft David Desktop"; lang = "en"; text = "Sure, no problem. Give me one second." },
    @{ voice = "Microsoft Sabina Desktop"; lang = "es"; text = "Disculpe, ¿dónde está el baño?" },
    @{ voice = "Microsoft Zira Desktop";  lang = "en"; text = "That will be twenty five dollars and fifty cents." },
    @{ voice = "Microsoft David Desktop"; lang = "en"; text = "Are you guys from Mexico?" },
    @{ voice = "Microsoft Zira Desktop";  lang = "en"; text = "Pizza or tacos tonight?" },
    @{ voice = "Microsoft David Desktop"; lang = "en"; text = "Yes." },
    @{ voice = "Microsoft Zira Desktop";  lang = "en"; text = "The museum closes at five." },
    @{ voice = "Microsoft David Desktop"; lang = "en"; text = "Hello Abraham, can you check the UPS on the second floor?" },
    @{ voice = "Microsoft Sabina Desktop"; lang = "es"; text = "Muchas gracias por todo, que tengan buen día." },
    @{ voice = "Microsoft Zira Desktop";  lang = "en"; text = "Okay, okay." },
    @{ voice = "Microsoft David Desktop"; lang = "en"; text = "Let me know if you need anything else." },
    @{ voice = "Microsoft Zira Desktop";  lang = "en"; text = "No way!" }
)

$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.Rate = 0
$synth.Volume = 100
"# english_probe start $(Get-Date -Format o)" | Out-File -FilePath $OutLog -Encoding utf8
$i = 0
foreach ($p in $phrases) {
    $i++
    $synth.SelectVoice($p.voice)
    $start = Get-Date
    $synth.Speak($p.text)
    $end = Get-Date
    "{0}`t{1}`t{2}`t{3}`t{4}" -f $i, $p.lang, $start.ToString("HH:mm:ss.fff"), $end.ToString("HH:mm:ss.fff"), $p.text |
        Out-File -FilePath $OutLog -Encoding utf8 -Append
    Write-Output ("#{0} [{1}] {2}" -f $i, $p.lang, $p.text)
    Start-Sleep -Seconds $GapSeconds
}
$synth.Dispose()
"# english_probe end $(Get-Date -Format o)" | Out-File -FilePath $OutLog -Encoding utf8 -Append
