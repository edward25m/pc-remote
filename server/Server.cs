using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;

namespace PCRemote
{
    /// Length-prefixed frames: [1 byte type][4 bytes big-endian length][payload]
    sealed class FrameIO
    {
        public const byte JSON = 1, SCREEN = 2, DATA = 3;
        readonly Stream s;
        readonly object wl = new object();
        readonly byte[] hdr = new byte[5];

        public FrameIO(Stream stream) { s = stream; }

        public void Send(byte type, byte[] data, int off, int len)
        {
            lock (wl)
            {
                var h = new byte[5];
                h[0] = type; h[1] = (byte)(len >> 24); h[2] = (byte)(len >> 16); h[3] = (byte)(len >> 8); h[4] = (byte)len;
                s.Write(h, 0, 5);
                if (len > 0) s.Write(data, off, len);
                s.Flush();
            }
        }

        public void SendJson(object o)
        {
            var b = Encoding.UTF8.GetBytes(Json.Str(o));
            Send(JSON, b, 0, b.Length);
        }

        public void SendData(int id, byte[] buf, int len)
        {
            var b = new byte[len + 4];
            b[0] = (byte)(id >> 24); b[1] = (byte)(id >> 16); b[2] = (byte)(id >> 8); b[3] = (byte)id;
            Buffer.BlockCopy(buf, 0, b, 4, len);
            Send(DATA, b, 0, b.Length);
        }

        void ReadExact(byte[] b, int len)
        {
            int got = 0;
            while (got < len)
            {
                int n = s.Read(b, got, len - got);
                if (n <= 0) throw new EndOfStreamException();
                got += n;
            }
        }

        public byte[] Read(out byte type)
        {
            ReadExact(hdr, 5);
            type = hdr[0];
            int len = (hdr[1] << 24) | (hdr[2] << 16) | (hdr[3] << 8) | hdr[4];
            if (len < 0 || len > 32 * 1024 * 1024) throw new InvalidDataException("bad frame");
            var p = new byte[len];
            ReadExact(p, len);
            return p;
        }
    }

    static class Server
    {
        public const int DiscoveryPort = 47800, TcpPort = 47801, HttpPort = 47802;
        public static readonly Guid BtService = new Guid("6f3c2a10-9b1e-4c7d-a2f4-5e8b9c0d1a23");
        public static readonly List<Session> Sessions = new List<Session>();
        public static string BtAddress = "";
        public static event Action Changed;

        public static void Start()
        {
            StartThread(TcpLoop, "tcp");
            StartThread(DiscoveryLoop, "udp");
            StartThread(BluetoothLoop, "bt");
            StartThread(MediaHttp.Loop, "http");
        }

        static void StartThread(ThreadStart f, string name)
        {
            new Thread(() =>
            {
                while (true)
                {
                    try { f(); }
                    catch (Exception ex) { Program.Log(name + ": " + ex.Message); }
                    Thread.Sleep(5000);
                }
            }) { IsBackground = true, Name = name }.Start();
        }

        static void TcpLoop()
        {
            var l = new TcpListener(IPAddress.Any, TcpPort);
            l.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            l.Start();
            try
            {
                while (true)
                {
                    var c = l.AcceptTcpClient();
                    c.NoDelay = true;
                    c.SendBufferSize = 512 * 1024;
                    c.ReceiveTimeout = 20000; // the phone pings every few seconds; silence means it's gone
                    var local = ((IPEndPoint)c.Client.LocalEndPoint).Address;
                    if (local.IsIPv4MappedToIPv6) local = local.MapToIPv4();
                    var ses = new Session(c.GetStream(), "wifi", local.ToString(), () => c.Close());
                    ses.Start();
                }
            }
            finally { l.Stop(); }
        }

        static void DiscoveryLoop()
        {
            using (var u = new UdpClient())
            {
                u.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                u.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));
                u.EnableBroadcast = true;
                while (true)
                {
                    var ep = new IPEndPoint(IPAddress.Any, 0);
                    var data = u.Receive(ref ep);
                    var msg = Encoding.UTF8.GetString(data);
                    if (!msg.StartsWith("PCREMOTE?")) continue;
                    var reply = Encoding.UTF8.GetBytes(Json.Str(new Dictionary<string, object> {
                        { "t", "pc" }, { "id", Config.Data.Id }, { "name", Config.Data.Name },
                        { "port", TcpPort }, { "ver", Program.Version }, { "bt", BtAddress }
                    }));
                    try { u.Send(reply, reply.Length, ep); } catch { }
                }
            }
        }

        static void BluetoothLoop()
        {
            BluetoothRadio radio = null;
            try { radio = BluetoothRadio.Default; } catch { }
            if (radio == null) { Thread.Sleep(30000); return; } // no Bluetooth (or it's off): try again later
            try { BtAddress = radio.LocalAddress.ToString("C"); } catch { }

            var l = new BluetoothListener(BtService) { ServiceName = "PC Remote" };
            l.Start();
            try
            {
                while (true)
                {
                    var c = l.AcceptBluetoothClient();
                    var st = c.GetStream();
                    try { st.ReadTimeout = 20000; } catch { }
                    var ses = new Session(st, "bluetooth", "", () => c.Close());
                    ses.Start();
                }
            }
            finally { l.Stop(); }
        }

        public static void Add(Session s) { lock (Sessions) Sessions.Add(s); Changed?.Invoke(); }
        public static void Remove(Session s) { lock (Sessions) Sessions.Remove(s); Changed?.Invoke(); }

        public static Session[] All() { lock (Sessions) return Sessions.ToArray(); }

        public static void BroadcastClipboard(string text)
        {
            foreach (var s in All().Where(x => x.Authorized && x.ClipSync))
                s.SendSafe(new Dictionary<string, object> { { "t", "clip" }, { "s", text } });
        }
    }

    /// Lets the phone play a video or song that lives on the PC (only works over Wi-Fi / hotspot).
    static class MediaHttp
    {
        static readonly Dictionary<string, string> Tokens = new Dictionary<string, string>();

        public static string Register(string path)
        {
            string tok = Guid.NewGuid().ToString("N");
            lock (Tokens) Tokens[tok] = path;
            return tok + "/" + Uri.EscapeDataString(Path.GetFileName(path));
        }

        public static void Loop()
        {
            var l = new TcpListener(IPAddress.Any, Server.HttpPort);
            l.Start();
            try
            {
                while (true)
                {
                    var c = l.AcceptTcpClient();
                    new Thread(() => Handle(c)) { IsBackground = true }.Start();
                }
            }
            finally { l.Stop(); }
        }

        static void Handle(TcpClient c)
        {
            try
            {
                using (c)
                using (var ns = c.GetStream())
                {
                    string first = ReadLine(ns);
                    if (first == null) return;
                    string range = null; string line;
                    while (!string.IsNullOrEmpty(line = ReadLine(ns)))
                        if (line.StartsWith("Range:", StringComparison.OrdinalIgnoreCase)) range = line.Substring(6).Trim();
                    var parts = first.Split(' ');
                    bool head = parts[0] == "HEAD";
                    string tok = parts.Length > 1 ? parts[1].TrimStart('/').Split('/')[0] : "";
                    string path;
                    lock (Tokens) Tokens.TryGetValue(tok, out path);
                    if (path == null || !File.Exists(path)) { WriteHead(ns, "404 Not Found", 0, null, null); return; }

                    using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                    {
                        long size = fs.Length, start = 0, end = size - 1;
                        bool partial = false;
                        if (range != null && range.StartsWith("bytes="))
                        {
                            var r = range.Substring(6).Split(',')[0].Split('-');
                            if (r[0].Length > 0) start = long.Parse(r[0]);
                            if (r.Length > 1 && r[1].Length > 0) end = Math.Min(long.Parse(r[1]), size - 1);
                            if (r[0].Length == 0 && r.Length > 1) { start = size - long.Parse(r[1]); end = size - 1; }
                            partial = true;
                        }
                        long len = end - start + 1;
                        WriteHead(ns, partial ? "206 Partial Content" : "200 OK", len, Mime(path),
                            partial ? $"Content-Range: bytes {start}-{end}/{size}\r\n" : null);
                        if (head) return;
                        fs.Position = start;
                        var buf = new byte[128 * 1024];
                        while (len > 0)
                        {
                            int n = fs.Read(buf, 0, (int)Math.Min(buf.Length, len));
                            if (n <= 0) break;
                            ns.Write(buf, 0, n);
                            len -= n;
                        }
                    }
                }
            }
            catch { }
        }

        static string ReadLine(Stream s)
        {
            var sb = new StringBuilder();
            while (true)
            {
                int b = s.ReadByte();
                if (b < 0) return sb.Length > 0 ? sb.ToString() : null;
                if (b == '\n') return sb.ToString().TrimEnd('\r');
                sb.Append((char)b);
                if (sb.Length > 8192) return null;
            }
        }

        static void WriteHead(Stream s, string status, long len, string mime, string extra)
        {
            var h = "HTTP/1.1 " + status + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\nContent-Length: " + len + "\r\n" +
                    (mime != null ? "Content-Type: " + mime + "\r\n" : "") + (extra ?? "") + "\r\n";
            var b = Encoding.ASCII.GetBytes(h);
            s.Write(b, 0, b.Length);
        }

        static string Mime(string p)
        {
            switch (Path.GetExtension(p).ToLowerInvariant())
            {
                case ".mp4": case ".m4v": return "video/mp4";
                case ".mkv": return "video/x-matroska";
                case ".webm": return "video/webm";
                case ".3gp": return "video/3gpp";
                case ".avi": return "video/x-msvideo";
                case ".mov": return "video/quicktime";
                case ".mp3": return "audio/mpeg";
                case ".m4a": case ".aac": return "audio/mp4";
                case ".wav": return "audio/wav";
                case ".ogg": return "audio/ogg";
                case ".flac": return "audio/flac";
                default: return "application/octet-stream";
            }
        }
    }
}
