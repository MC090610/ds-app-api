param(
    [Parameter(Mandatory = $true)]
    [string]$WebSocketUrl,
    [Parameter(Mandatory = $true)]
    [string]$Expression
)

$ErrorActionPreference = 'Stop'
$socket = [System.Net.WebSockets.ClientWebSocket]::new()
$cancel = [System.Threading.CancellationToken]::None

try {
    [void]$socket.ConnectAsync([Uri]$WebSocketUrl, $cancel).GetAwaiter().GetResult()
    $message = @{
        id = 1
        method = 'Runtime.evaluate'
        params = @{
            expression = $Expression
            awaitPromise = $true
            returnByValue = $true
        }
    } | ConvertTo-Json -Depth 8 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($message)
    [void]$socket.SendAsync(
        [ArraySegment[byte]]::new($bytes),
        [System.Net.WebSockets.WebSocketMessageType]::Text,
        $true,
        $cancel
    ).GetAwaiter().GetResult()

    $buffer = New-Object byte[] 1048576
    do {
        $stream = [IO.MemoryStream]::new()
        do {
            $result = $socket.ReceiveAsync(
                [ArraySegment[byte]]::new($buffer),
                $cancel
            ).GetAwaiter().GetResult()
            $stream.Write($buffer, 0, $result.Count)
        } while (-not $result.EndOfMessage)
        $text = [Text.Encoding]::UTF8.GetString($stream.ToArray())
        $response = $text | ConvertFrom-Json
    } while ($response.id -ne 1)

    $response | ConvertTo-Json -Depth 20
} finally {
    if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        try {
            [void]$socket.CloseAsync(
                [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                'done',
                $cancel
            ).GetAwaiter().GetResult()
        } catch {
            # Chromium may close immediately after the response.
        }
    }
    $socket.Dispose()
}
