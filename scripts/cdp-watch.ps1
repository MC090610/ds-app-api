param(
    [Parameter(Mandatory = $true)]
    [string]$WebSocketUrl,
    [int]$Seconds = 8
)

$ErrorActionPreference = 'Stop'
$socket = [System.Net.WebSockets.ClientWebSocket]::new()
$cancel = [System.Threading.CancellationToken]::None
$nextId = 1

function Send-Cdp {
    param([string]$Method, [hashtable]$Params = @{})
    $script:nextId++
    $payload = @{
        id = $script:nextId
        method = $Method
        params = $Params
    } | ConvertTo-Json -Depth 12 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($payload)
    [void]$socket.SendAsync(
        [ArraySegment[byte]]::new($bytes),
        [System.Net.WebSockets.WebSocketMessageType]::Text,
        $true,
        $cancel
    ).GetAwaiter().GetResult()
    return $script:nextId
}

try {
    [void]$socket.ConnectAsync([Uri]$WebSocketUrl, $cancel).GetAwaiter().GetResult()
    [void](Send-Cdp -Method 'Runtime.enable')
    [void](Send-Cdp -Method 'Log.enable')
    [void](Send-Cdp -Method 'Page.enable')
    [void](Send-Cdp -Method 'Page.reload' -Params @{ ignoreCache = $true })

    $deadline = (Get-Date).AddSeconds($Seconds)
    $buffer = New-Object byte[] 1048576
    while ((Get-Date) -lt $deadline -and $socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        $stream = [IO.MemoryStream]::new()
        do {
            $result = $socket.ReceiveAsync(
                [ArraySegment[byte]]::new($buffer),
                $cancel
            ).GetAwaiter().GetResult()
            if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                break
            }
            $stream.Write($buffer, 0, $result.Count)
        } while (-not $result.EndOfMessage)
        if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            break
        }
        $text = [Text.Encoding]::UTF8.GetString($stream.ToArray())
        $event = $text | ConvertFrom-Json
        if ($event.method -in @(
            'Runtime.exceptionThrown',
            'Runtime.consoleAPICalled',
            'Log.entryAdded',
            'Page.javascriptDialogOpening'
        )) {
            $text
        }
    }
} finally {
    $socket.Dispose()
}
