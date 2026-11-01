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
        private const int Port = 3009;
        private const string Url = "http://127.0.0.1:3009/";

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

            TcpListener listener = new TcpListener(IPAddress.Loopback, Port);
            try
            {
                listener.Start();
            }
            catch (SocketException e)
            {
                SafeWrite("端口 " + Port + " 启动失败：" + e.Message);
                SafeWrite("用 netstat -ano | findstr :" + Port + " 看看是谁占着。");
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
                    if (q >= 0) rawPath = rawPath.Substring(0, q);
                    string path = Uri.UnescapeDataString(rawPath);
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
