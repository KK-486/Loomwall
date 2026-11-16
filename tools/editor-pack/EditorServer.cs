using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

// LOOW 预设编辑器 · 本地服务
//
// 设计取舍：
// 1. 不用 HttpListener —— 它在部分 Windows/受限环境下构造就抛 PlatformNotSupportedException
//    （本次实测就撞上了）。用 TcpListener 自己写最小 HTTP/1.1，零依赖、行为可控。
// 2. 不用 Python/Node 托管 —— 目标机器只要装了 .NET Framework（Windows 自带）就能跑，
//    不需要用户额外安装运行时，也不需要联网。
// 3. 编辑器资源（html/js/icons）打包成 zip 内嵌在 exe 里，单个文件即可分发。
namespace LoomwallEditor
{
    internal static class Program
    {
        // 首选端口；被占用时自动往后试（残留的僵尸监听很常见）
        private const int PreferredPort = 3009;
        private const int PortScanCount = 11;
        private static int _port = PreferredPort;
        private static string Url { get { return "http://127.0.0.1:" + _port + "/"; } }

        private static readonly Dictionary<string, string> MimeTypes =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                { ".html", "text/html; charset=utf-8" },
                { ".js",   "application/javascript; charset=utf-8" },
                { ".css",  "text/css; charset=utf-8" },
                { ".json", "application/json; charset=utf-8" },
                { ".svg",  "image/svg+xml" },
                { ".png",  "image/png" },
                { ".jpg",  "image/jpeg" },
                { ".jpeg", "image/jpeg" },
                { ".gif",  "image/gif" },
                { ".webp", "image/webp" },
                { ".ico",  "image/x-icon" },
                { ".woff", "font/woff" },
                { ".woff2", "font/woff2" }
            };

        private static Dictionary<string, byte[]> _files;

        private static int Main(string[] args)
        {
            // 输出编码/标题在"输出被重定向"的环境下可能抛异常，失败也不该让程序起不来
            try { Console.OutputEncoding = Encoding.UTF8; } catch { }
            try { Console.Title = "LOOW 预设编辑器"; } catch { }

            try
            {
                return RunServer();
            }
            catch (Exception e)
            {
                SafeWrite("启动失败： " + e.GetType().Name + " - " + e.Message);
                Pause();
                return 1;
            }
        }

        private static int RunServer()
        {
            try
            {
                _files = LoadEmbedded();
            }
            catch (Exception e)
            {
                SafeWrite("资源解包失败： " + e.Message);
                Pause();
                return 1;
            }

            if (_files.Count == 0)
            {
                SafeWrite("包里没有编辑器文件，构建过程有问题。");
                Pause();
                return 1;
            }

            TcpListener listener = null;
            SocketException lastError = null;
            for (int p = PreferredPort; p < PreferredPort + PortScanCount; p++)
            {
                try
                {
                    var candidate = new TcpListener(IPAddress.Loopback, p);
                    candidate.Start();
                    listener = candidate;
                    _port = p;
                    if (p != PreferredPort) SafeWrite("端口 " + PreferredPort + " 被占用，改用 " + p);
                    break;
                }
                catch (SocketException e)
                {
                    lastError = e;
                }
            }
            if (listener == null)
            {
                SafeWrite("端口 " + PreferredPort + ".." + (PreferredPort + PortScanCount - 1) + " 都被占用：" +
                          (lastError != null ? lastError.Message : "未知原因"));
                SafeWrite("用 netstat -ano | findstr :" + PreferredPort + " 看看是谁占着。");
                Pause();
                return 1;
            }

            SafeWrite("LOOW 预设编辑器已启动");
            SafeWrite("  地址: " + Url);
            SafeWrite("  资源: " + _files.Count + " 个文件");
            SafeWrite("  关闭: 关掉这个窗口，或按 Ctrl+C");
            SafeWrite("");

            OpenBrowser(Url);

            ThreadPool.SetMinThreads(16, 16);
            while (true)
            {
                TcpClient client;
                try
                {
                    client = listener.AcceptTcpClient();
                }
                catch (Exception)
                {
                    break;
                }
                ThreadPool.QueueUserWorkItem(_ => Handle(client));
            }

            return 0;
        }

        private static void Handle(TcpClient client)
        {
            try
            {
                using (client)
                using (NetworkStream stream = client.GetStream())
                {
                    client.ReceiveTimeout = 5000;
                    client.SendTimeout = 5000;

                    string requestLine = ReadHeader(stream);
                    if (string.IsNullOrEmpty(requestLine)) return;

                    // 形如：GET /editor.js?v=1 HTTP/1.1
                    string[] parts = requestLine.Split(' ');
                    if (parts.Length < 2) return;
                    string method = parts[0];
                    string rawPath = parts[1];

                    int q = rawPath.IndexOf('?');
                    string query = q >= 0 ? rawPath.Substring(q + 1) : "";
                    if (q >= 0) rawPath = rawPath.Substring(0, q);
                    string path = Uri.UnescapeDataString(rawPath);

                    // USB 连接：浏览器不能调 adb，由这里代跑 forward
                    if (path.StartsWith("/adb/", StringComparison.Ordinal))
                    {
                        string json = HandleAdb(path, query);
                        WriteResponse(stream, 200, "application/json; charset=utf-8",
                            Encoding.UTF8.GetBytes(json), method == "HEAD");
                        return;
                    }

                    if (path == "/" || path.Length == 0) path = "/index.html";
                    string key = path.TrimStart('/').Replace('\\', '/');

                    byte[] body;
                    if (!_files.TryGetValue(key, out body))
                    {
                        // 未知路径回到首页，避免刷新深层链接时 404
                        if (!_files.TryGetValue("index.html", out body))
                        {
                            body = Encoding.UTF8.GetBytes("404 Not Found");
                            WriteResponse(stream, 404, "text/plain; charset=utf-8", body, method == "HEAD");
                            return;
                        }
                        WriteResponse(stream, 200, "text/html; charset=utf-8", body, method == "HEAD");
                        return;
                    }

                    WriteResponse(stream, 200, MimeFor(key), body, method == "HEAD");
                }
            }
            catch (Exception)
            {
                try { client.Close(); } catch { }
            }
        }

        // ---------------------------------------------------------------- ADB（USB）

        /// <summary>找 adb.exe：环境变量 → SDK 默认位置 → exe 同目录 → PATH。</summary>
        /// <summary>
        /// 找 adb.exe。
        ///
        /// 为什么候选这么多：从资源管理器双击启动的进程拿不到开发机的 PATH，
        /// 而用户电脑上 adb 可能只在 Android Studio 的 SDK 目录里。按以下顺序试：
        /// LOOW_ADB/ADB 环境变量 → EXE 同目录的 adb-path.txt → 同目录 platform-tools
        /// → 上级目录的 .toolchain SDK（仓库内开发）→ SDK 默认安装位置 → PATH。
        /// </summary>
        private static string FindAdb()
        {
            var cands = new List<string>();
            string a1 = Environment.GetEnvironmentVariable("LOOW_ADB");
            if (!string.IsNullOrEmpty(a1)) cands.Add(a1);
            string a2 = Environment.GetEnvironmentVariable("ADB");
            if (!string.IsNullOrEmpty(a2)) cands.Add(a2);

            string exeDir = Path.GetDirectoryName(typeof(Program).Assembly.Location);
            if (!string.IsNullOrEmpty(exeDir))
            {
                // 用户可把路径写进 adb-path.txt（第一行）
                try
                {
                    string cfg = Path.Combine(exeDir, "adb-path.txt");
                    if (File.Exists(cfg))
                    {
                        string line = File.ReadAllLines(cfg)[0].Trim();
                        if (line.Length > 0) cands.Add(Directory.Exists(line) ? Path.Combine(line, "adb.exe") : line);
                    }
                }
                catch { }

                cands.Add(Path.Combine(exeDir, "platform-tools", "adb.exe"));
                cands.Add(Path.Combine(exeDir, "adb.exe"));
                cands.Add(Path.GetFullPath(Path.Combine(exeDir, "..", ".toolchain", "android-sdk", "platform-tools", "adb.exe")));
                cands.Add(Path.GetFullPath(Path.Combine(exeDir, "..", "..", ".toolchain", "android-sdk", "platform-tools", "adb.exe")));
                cands.Add(Path.GetFullPath(Path.Combine(exeDir, "..", "tools", "platform-tools", "adb.exe")));
            }

            string androidHome = Environment.GetEnvironmentVariable("ANDROID_HOME");
            if (!string.IsNullOrEmpty(androidHome))
                cands.Add(Path.Combine(androidHome, "platform-tools", "adb.exe"));
            string sdkRoot = Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT");
            if (!string.IsNullOrEmpty(sdkRoot))
                cands.Add(Path.Combine(sdkRoot, "platform-tools", "adb.exe"));
            string localAppData = Environment.GetEnvironmentVariable("LOCALAPPDATA");
            if (!string.IsNullOrEmpty(localAppData))
                cands.Add(Path.Combine(localAppData, "Android", "Sdk", "platform-tools", "adb.exe"));
            cands.Add(@"C:\Android\platform-tools\adb.exe");
            cands.Add(@"C:\Program Files\Android\platform-tools\adb.exe");
            cands.Add("adb");

            foreach (string c in cands)
            {
                if (string.IsNullOrEmpty(c)) continue;
                try
                {
                    var r = RunProcess(c, "version", 4000);
                    if (r.Item1 == 0) return c;
                }
                catch { /* 试下一个 */ }
            }
            return null;
        }

        /// <summary>跑一个进程，返回 (退出码, stdout, stderr)；超时直接杀掉。</summary>
        /// <summary>当前使用的 adb 服务端口；null = 用 adb 默认值（5037）。</summary>
        private static string _adbServerPort;
        /// <summary>最近一次端口扫描的过程（诊断用）。</summary>
        private static string _adbTrace = "";

        /// <summary>
        /// 候选端口（null = adb 默认 5037）。
        /// 为什么要扫：5037 被别的程序占用时，adb 客户端"连得上但设备列表为空"，
        /// 而设备可能挂在另一个端口的服务上 —— 必须挑一个真能看到设备的。
        /// </summary>
        private static readonly string[] AdbServerPortCandidates = { null, "15037", "15038", "15039" };

        /// <summary>输出里是否出现一台状态为 device 的设备。</summary>
        private static bool HasDevice(Tuple<int, string, string> r)
        {
            string[] lines = (r.Item2 ?? "").Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
            foreach (string raw in lines)
            {
                string line = raw.Trim();
                if (line.Length == 0) continue;
                if (line.StartsWith("List of devices", StringComparison.OrdinalIgnoreCase)) continue;
                if (line.StartsWith("*", StringComparison.Ordinal)) continue;
                // 形如 "65cb411f  device product:umi ... transport_id:2"：
                // 状态在第二个字段，行尾并不是 device
                string[] parts = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 2 && parts[1] == "device") return true;
            }
            return false;
        }

        /// <summary>挑一个能看到设备的 adb 服务端口；都没有设备就返回第一个能连上的。</summary>
        private static string ResolveAdbPort(string adb)
        {
            var trace = new System.Text.StringBuilder();
            string firstOk = null;
            bool firstOkSet = false;
            foreach (string port in AdbServerPortCandidates)
            {
                var r = RunProcess(adb, "devices -l", 8000, port);
                trace.Append("[" + (port ?? "def") + "] exit=" + r.Item1 +
                             " out=" + ((r.Item2 ?? "").Replace("\n", "/").Replace("\r", "").Trim()) +
                             " err=" + ((r.Item3 ?? "").Replace("\n", "/").Replace("\r", "").Trim()) + " ;; ");
                _adbTrace = trace.ToString();
                if (IsDaemonFailure(r)) continue;
                if (!firstOkSet) { firstOk = port; firstOkSet = true; }
                if (HasDevice(r))
                {
                    _adbServerPort = port;
                    return port;
                }
            }
            _adbServerPort = firstOk;
            return firstOk;
        }

        private static bool IsDaemonFailure(Tuple<int, string, string> r)
        {
            string text = ((r.Item2 ?? "") + "\n" + (r.Item3 ?? "")).ToLowerInvariant();
            return text.Contains("cannot connect to daemon")
                || text.Contains("could not read ok from adb server")
                || text.Contains("failed to start daemon")
                || text.Contains("cannot connect to daemon");
        }

        /// <summary>
        /// 跑 adb，并在"连不上守护进程"时自动换一个服务端口重试。
        /// 为什么要这样：adb 客户端与服务端靠一个本地端口通信，默认 5037；
        /// 该端口被别的程序占用时，客户端只会打印 cannot connect to daemon，
        /// 用户完全看不出是端口冲突。
        /// </summary>
        private static Tuple<int, string, string> RunAdb(string adb, string args, int timeoutMs)
        {
            var first = RunProcess(adb, args, timeoutMs, _adbServerPort);
            if (!IsDaemonFailure(first)) return first;
            if (_adbServerPort != null) return first;

            foreach (string port in AdbServerPortCandidates)
            {
                var retry = RunProcess(adb, args, timeoutMs, port);
                if (!IsDaemonFailure(retry))
                {
                    _adbServerPort = port;
                    SafeWrite("adb 默认端口不可用，已改用 " + port);
                    return retry;
                }
            }
            return first;
        }

        private static Tuple<int, string, string> RunProcess(string file, string args, int timeoutMs)
        {
            return RunProcess(file, args, timeoutMs, null);
        }

        private static Tuple<int, string, string> RunProcess(string file, string args, int timeoutMs, string adbServerPort)
        {
            var psi = new ProcessStartInfo(file, args)
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            if (!string.IsNullOrEmpty(adbServerPort))
            {
                psi.EnvironmentVariables["ANDROID_ADB_SERVER_PORT"] = adbServerPort;
            }
            using (var p = Process.Start(psi))
            {
                string stdout = p.StandardOutput.ReadToEnd();
                string stderr = p.StandardError.ReadToEnd();
                if (!p.WaitForExit(timeoutMs))
                {
                    try { p.Kill(); } catch { }
                    return Tuple.Create(-1, stdout, "adb 超时（" + timeoutMs + "ms）");
                }
                return Tuple.Create(p.ExitCode, stdout, stderr);
            }
        }

        private static string JsonEscape(string s)
        {
            if (s == null) return "";
            var sb = new StringBuilder();
            foreach (char c in s)
            {
                if (c == '"' || c == 92) { sb.Append((char)92); sb.Append(c); }
                else if (c == 10) { sb.Append((char)92); sb.Append('n'); }
                else if (c == 13) { }
                else if (c == 9) { sb.Append(' '); }
                else if (c < 32) { sb.Append(' '); }
                else { sb.Append(c); }
            }
            return sb.ToString();
        }

        private static string QueryValue(string query, string key, string fallback)
        {
            foreach (string pair in (query ?? "").Split('&'))
            {
                int eq = pair.IndexOf('=');
                if (eq <= 0) continue;
                if (pair.Substring(0, eq) == key) return Uri.UnescapeDataString(pair.Substring(eq + 1));
            }
            return fallback;
        }

        private static string HandleAdb(string path, string query)
        {
            string adb = FindAdb();
            if (adb == null)
            {
                return "{\"ok\":false,\"error\":\"没找到 adb.exe（装 Android SDK，或把 adb 加进 PATH，或放到 EXE 同目录的 platform-tools 下）\"}";
            }
            // 每次请求先确认"哪个服务端口能看到设备"：
            // 用户可能刚插拔过数据线，或别的程序抢占了默认端口
            string resolvedPort = ResolveAdbPort(adb);
            SafeWrite("[adb] 使用服务端口: " + (resolvedPort ?? "(默认 5037)"));

            // 列出设备
            if (path == "/adb/devices")
            {
                var r = RunAdb(adb, "devices -l", 12000);
                if (r.Item1 != 0)
                    return "{\"ok\":false,\"error\":\"" + JsonEscape(r.Item3 ?? r.Item2) + "\"}";
                var items = new List<string>();
                string[] lines = (r.Item2 ?? "").Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
                for (int i = 1; i < lines.Length; i++)
                {
                    string line = lines[i].Trim();
                    if (line.Length == 0) continue;
                    string[] parts = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                    string serial = parts.Length > 0 ? parts[0] : "";
                    string state = parts.Length > 1 ? parts[1] : "";
                    int mi = line.IndexOf("model:");
                    string model = "";
                    if (mi >= 0)
                    {
                        int end = line.IndexOf(' ', mi);
                        model = end > 0 ? line.Substring(mi + 6, end - mi - 6) : line.Substring(mi + 6);
                    }
                    items.Add("{\"serial\":\"" + JsonEscape(serial) + "\",\"state\":\"" +
                              JsonEscape(state) + "\",\"model\":\"" + JsonEscape(model) + "\"}");
                }
                return "{\"ok\":true,\"adb\":\"" + JsonEscape(adb) + "\",\"adbPort\":\"" + JsonEscape(_adbServerPort ?? "default") + "\",\"trace\":\"" + JsonEscape(_adbTrace) + "\",\"devices\":[" +
                       string.Join(",", items.ToArray()) + "]}";
            }

            // 建立转发
            if (path == "/adb/connect")
            {
                int port = 18765;
                int.TryParse(QueryValue(query, "port", "18765"), out port);
                var list = RunAdb(adb, "devices -l", 12000);
                if (list.Item1 != 0)
                    return "{\"ok\":false,\"error\":\"" + JsonEscape(list.Item3 ?? list.Item2) + "\"}";

                string serial = null;
                bool unauthorized = (list.Item2 ?? "").IndexOf("unauthorized", StringComparison.Ordinal) >= 0;
                string[] lines = (list.Item2 ?? "").Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
                for (int i = 1; i < lines.Length; i++)
                {
                    string[] parts = lines[i].Trim().Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                    if (parts.Length >= 2 && parts[1] == "device") { serial = parts[0]; break; }
                }
                if (serial == null)
                {
                    string err = unauthorized
                        ? "手机未授权 USB 调试：在手机上点「允许」"
                        : "没检测到 USB 设备（数据线插了吗？USB 调试开了吗？）";
                    return "{\"ok\":false,\"error\":\"" + JsonEscape(err) + "\"}";
                }

                var fw = RunAdb(adb, "-s " + serial + " forward tcp:" + port + " tcp:8765", 15000);
                if (fw.Item1 != 0)
                    return "{\"ok\":false,\"error\":\"" + JsonEscape(fw.Item3 ?? fw.Item2) + "\"}";

                return "{\"ok\":true,\"adb\":\"" + JsonEscape(adb) + "\",\"serial\":\"" +
                       JsonEscape(serial) + "\",\"base\":\"127.0.0.1:" + port + "\"}";
            }

            // 断开转发
            if (path == "/adb/disconnect")
            {
                int port = 18765;
                int.TryParse(QueryValue(query, "port", "18765"), out port);
                RunAdb(adb, "forward --remove tcp:" + port, 8000);
                return "{\"ok\":true}";
            }

            return "{\"ok\":false,\"error\":\"未知的 /adb 路径\"}";
        }

        /// <summary>读到空行为止，返回请求行；同时把请求头丢弃（本服务不需要头信息）。</summary>
        private static string ReadHeader(NetworkStream stream)
        {
            var buffer = new MemoryStream();
            var one = new byte[1];
            while (buffer.Length < 16384)
            {
                int read = stream.Read(one, 0, 1);
                if (read <= 0) break;
                buffer.WriteByte(one[0]);
                // 头结束标志：\r\n\r\n
                if (buffer.Length >= 4)
                {
                    byte[] data = buffer.GetBuffer();
                    long n = buffer.Length;
                    if (data[n - 1] == (byte)'\n' && data[n - 2] == (byte)'\r'
                        && data[n - 3] == (byte)'\n' && data[n - 4] == (byte)'\r')
                    {
                        break;
                    }
                }
            }

            string text = Encoding.ASCII.GetString(buffer.ToArray());
            int end = text.IndexOf("\r\n", StringComparison.Ordinal);
            return end > 0 ? text.Substring(0, end) : null;
        }

        private static void WriteResponse(NetworkStream stream, int status, string mime, byte[] body, bool headOnly)
        {
            string reason = status == 200 ? "OK" : (status == 404 ? "Not Found" : "Error");
            var header = new StringBuilder();
            header.Append("HTTP/1.1 ").Append(status).Append(' ').Append(reason).Append("\r\n");
            header.Append("Content-Type: ").Append(mime).Append("\r\n");
            header.Append("Content-Length: ").Append(body.Length).Append("\r\n");
            // 本地开发工具：禁缓存，改了文件刷新就能看到
            header.Append("Cache-Control: no-store\r\n");
            header.Append("Connection: close\r\n\r\n");

            byte[] headerBytes = Encoding.ASCII.GetBytes(header.ToString());
            stream.Write(headerBytes, 0, headerBytes.Length);
            if (!headOnly) stream.Write(body, 0, body.Length);
            stream.Flush();
        }

        private static string MimeFor(string name)
        {
            string ext = Path.GetExtension(name);
            string mime;
            return MimeTypes.TryGetValue(ext ?? "", out mime) ? mime : "application/octet-stream";
        }

        private static Dictionary<string, byte[]> LoadEmbedded()
        {
            var result = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);

            using (Stream zipStream = typeof(Program).Assembly.GetManifestResourceStream("web-editor.zip"))
            {
                if (zipStream == null) return result;
                using (var archive = new ZipArchive(zipStream, ZipArchiveMode.Read))
                {
                    foreach (ZipArchiveEntry entry in archive.Entries)
                    {
                        string name = entry.FullName.Replace('\\', '/');
                        if (name.EndsWith("/")) continue; // 目录项
                        if (name.StartsWith("__MACOSX/")) continue;
                        using (var ms = new MemoryStream())
                        using (Stream s = entry.Open())
                        {
                            s.CopyTo(ms);
                            result[name] = ms.ToArray();
                        }
                    }
                }
            }
            return result;
        }

        private static void OpenBrowser(string url)
        {
            try
            {
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch (Exception)
            {
                // 打不开浏览器不是致命错误，用户手动点地址即可
            }
        }

        private static void SafeWrite(string text)
        {
            try { Console.WriteLine(text); } catch { }
        }

        private static void Pause()
        {
            SafeWrite("");
            SafeWrite("按任意键退出…");
            try { Console.ReadKey(true); } catch { }
        }
    }
}
