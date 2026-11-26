using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Microsoft.VisualBasic.FileIO;

namespace PCRemote
{
    /// One connected phone.
    sealed class Session
    {
        readonly FrameIO io;
        readonly Action close;
        public readonly string Transport;
        readonly string localIp;
        public string DeviceId = "", DeviceName = "Phone";
        public bool Authorized, ClipSync = true;
        volatile bool alive = true;

        // screen
        readonly SemaphoreSlim frameRequests = new SemaphoreSlim(0, 8);
        volatile bool screenOn;
        volatile int screenWidth = 1280, screenQuality = 55;
        Thread screenThread;

        // file transfers
        readonly Dictionary<int, FileStream> uploads = new Dictionary<int, FileStream>();
        readonly Dictionary<int, string> uploadPaths = new Dictionary<int, string>();
        readonly HashSet<int> cancelled = new HashSet<int>();

        public Session(Stream s, string transport, string localIp, Action close)
        {
            io = new FrameIO(s);
            Transport = transport;
            this.localIp = localIp;
            this.close = close;
        }

        public void Start() => new Thread(Run) { IsBackground = true, Name = "session" }.Start();

        public void SendSafe(object o)
        {
            try { io.SendJson(o); } catch { Kill(); }
        }

        static Dictionary<string, object> M(string t, params object[] kv)
        {
            var d = new Dictionary<string, object> { { "t", t } };
            for (int i = 0; i + 1 < kv.Length; i += 2) d[(string)kv[i]] = kv[i + 1];
            return d;
        }

        void Kill()
        {
            if (!alive) return;
            alive = false;
            screenOn = false;
            try { frameRequests.Release(); } catch { }
            try { close(); } catch { }
            lock (uploads)
            {
                foreach (var kv in uploads)
                {
                    try { kv.Value.Dispose(); File.Delete(uploadPaths[kv.Key]); } catch { }
                }
                uploads.Clear();
            }
            Server.Remove(this);
        }

        void Run()
        {
            Server.Add(this);
            try
            {
                while (alive)
                {
                    byte type;
                    var p = io.Read(out type);
                    if (type == FrameIO.JSON)
                    {
                        var m = Json.Parse(Encoding.UTF8.GetString(p));
                        if (!Authorized) { Hello(m); continue; }
                        string t = m.S("t");
                        if (Slow.Contains(t))
                        {
                            // Things that can take a while (opening apps, listing drives, shutting down…) run on their own
                            // so mouse and keyboard keep flowing and the connection never stalls.
                            ThreadPool.QueueUserWorkItem(_ => SafeHandle(m));
                        }
                        else SafeHandle(m);
                    }
                    else if (type == FrameIO.INPUT && Authorized && p.Length >= 1)
                    {
                        FastInput(p);
                    }
                    else if (type == FrameIO.DATA && Authorized && p.Length >= 4)
                    {
                        int id = (p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3];
                        FileStream fs;
                        lock (uploads) uploads.TryGetValue(id, out fs);
                        fs?.Write(p, 4, p.Length - 4);
                    }
                }
            }
            catch (Exception ex) { if (!(ex is EndOfStreamException || ex is IOException)) Program.Log("session: " + ex.Message); }
            finally { Kill(); }
        }

        static readonly HashSet<string> Slow = new HashSet<string> { "ls", "open", "run", "run_app", "power", "stream", "clip", "mkdir", "ren" };

        void SafeHandle(Dictionary<string, object> m)
        {
            try { Handle(m); }
            catch (Exception ex)
            {
                Program.Log("handle " + m.S("t") + ": " + ex.Message);
                SendSafe(M("error", "msg", ex.Message));
            }
        }

        static short S16(byte[] p, int i) => (short)((p[i] << 8) | p[i + 1]);
        static int U16(byte[] p, int i) => (p[i] << 8) | p[i + 1];

        /// Compact mouse messages: handled straight away with no text parsing (keeps the pointer snappy).
        void FastInput(byte[] p)
        {
            switch (p[0])
            {
                case 1: if (p.Length >= 5) Input.MoveBy(S16(p, 1), S16(p, 3)); break;
                case 2: if (p.Length >= 5) Input.MoveTo(U16(p, 1) / 65535.0, U16(p, 3) / 65535.0); break;
                case 3:
                    if (p.Length >= 3)
                    {
                        string b = p[1] == 1 ? "r" : p[1] == 2 ? "m" : "l";
                        string a = p[2] == 1 ? "down" : p[2] == 2 ? "up" : p[2] == 3 ? "dbl" : "click";
                        Input.Button(b, a);
                    }
                    break;
                case 4: if (p.Length >= 5) Input.Scroll(S16(p, 1), S16(p, 3)); break;
            }
        }

        void Hello(Dictionary<string, object> m)
        {
            if (m.S("t") != "hello") return;
            DeviceId = m.S("id");
            DeviceName = m.S("name", "Phone");
            ClipSync = m.B("clip", true);
            if (string.IsNullOrEmpty(DeviceId)) { Kill(); return; }

            bool ok = Config.Data.Allowed.ContainsKey(DeviceId);
            if (!ok)
            {
                SendSafe(M("pending"));
                ok = Program.Tray.AskAllow(DeviceName, Transport);
                if (ok)
                {
                    Config.Data.Allowed[DeviceId] = DeviceName;
                    Config.Save();
                }
            }
            else if (Config.Data.Allowed[DeviceId] != DeviceName)
            {
                Config.Data.Allowed[DeviceId] = DeviceName;
                Config.Save();
            }

            if (!ok)
            {
                SendSafe(M("denied"));
                Thread.Sleep(300);
                Kill();
                return;
            }
            Authorized = true;
            var b = System.Windows.Forms.Screen.PrimaryScreen.Bounds;
            SendSafe(M("welcome", "id", Config.Data.Id, "name", Config.Data.Name, "ver", Program.Version,
                "bt", Server.BtAddress, "sw", b.Width, "sh", b.Height, "transport", Transport));
            Program.Tray.Connected(DeviceName, Transport);
        }

        void Handle(Dictionary<string, object> m)
        {
            switch (m.S("t"))
            {
                case "ping": SendSafe(M("pong")); break;

                // ---- mouse & keyboard ----
                case "mm": Input.MoveBy(m.I("dx"), m.I("dy")); break;
                case "ma": Input.MoveTo(m.D("x"), m.D("y")); break;
                case "mb": Input.Button(m.S("b", "l"), m.S("a", "click")); break;
                case "ms": Input.Scroll(m.I("dy"), m.I("dx")); break;
                case "txt": Input.Text(m.S("s")); break;
                case "keys": Input.Combo(m.A("vks").Select(x => Convert.ToInt32(x)).ToList()); break;
                case "kd": Input.KeyDown(m.I("vk")); break;
                case "ku": Input.KeyUp(m.I("vk")); break;

                // ---- power ----
                case "power": Power(m.S("a")); break;

                // ---- screen ----
                case "scr_start":
                    screenWidth = m.I("w", 1280);
                    screenQuality = m.I("q", 55);
                    StartScreen();
                    break;
                case "scr_cfg":
                    screenWidth = m.I("w", screenWidth);
                    screenQuality = m.I("q", screenQuality);
                    break;
                case "fr":
                    try { frameRequests.Release(); } catch (SemaphoreFullException) { }
                    break;
                case "scr_stop": screenOn = false; break;

                // ---- files ----
                case "ls": List(m.S("path")); break;
                case "get": Download(m.S("path"), m.I("id")); break;
                case "cancel": lock (cancelled) cancelled.Add(m.I("id")); CancelUpload(m.I("id")); break;
                case "put_start": UploadStart(m); break;
                case "put_end": UploadEnd(m.I("id")); break;
                case "del": FileOp("del", m, () => { foreach (var p in Paths(m)) Delete(p); }); break;
                case "ren": FileOp("ren", m, () => Rename(m.S("path"), m.S("name"))); break;
                case "mv": FileOp("mv", m, () => { foreach (var p in Paths(m)) MoveOrCopy(p, m.S("dest"), true); }); break;
                case "cp": FileOp("cp", m, () => { foreach (var p in Paths(m)) MoveOrCopy(p, m.S("dest"), false); }); break;
                case "mkdir": FileOp("mkdir", m, () => Directory.CreateDirectory(m.S("path"))); break;
                case "open":
                    Process.Start(new ProcessStartInfo(m.S("path")) { UseShellExecute = true });
                    break;
                case "stream":
                    if (Transport != "wifi") { SendSafe(M("error", "msg", "Playing on the phone needs Wi-Fi or hotspot, not Bluetooth.")); break; }
                    SendSafe(M("stream_url", "url", "http://" + localIp + ":" + Server.HttpPort + "/" + MediaHttp.Register(m.S("path")),
                        "name", Path.GetFileName(m.S("path"))));
                    break;

                // ---- clipboard ----
                case "clip": Program.Tray.SetClipboard(m.S("s")); break;
                case "clipsync": ClipSync = m.B("on", true); break;

                // ---- custom buttons ----
                case "run":
                    {
                        string cmd = m.S("cmd").Trim();
                        string args = m.S("args");
                        Process.Start(new ProcessStartInfo(Environment.ExpandEnvironmentVariables(cmd), args) { UseShellExecute = true });
                        break;
                    }

                // ---- apps (Start menu) ----
                case "list_apps":
                    new Thread(() => SendSafe(M("apps", "items", Apps.List().Select(a => a.Name).ToList()))) { IsBackground = true }.Start();
                    break;
                case "run_app":
                    {
                        var app = Apps.Find(m.S("q"));
                        if (app == null) SendSafe(M("error", "msg", "Couldn't find \"" + m.S("label", m.S("q")) + "\" on " + Config.Data.Name + ". Is it installed?"));
                        else Process.Start(new ProcessStartInfo(app.Path) { UseShellExecute = true });
                        break;
                    }
                case "app_icon":
                    {
                        string q = m.S("q"), uid = m.S("uid");
                        var th = new Thread(() =>
                        {
                            var app = Apps.Find(q);
                            string png = app == null ? "" : Apps.IconPng(app.Path, 96);
                            SendSafe(M("app_icon", "uid", uid, "found", app != null, "name", app?.Name ?? "", "png", png));
                        }) { IsBackground = true };
                        th.SetApartmentState(ApartmentState.STA);
                        th.Start();
                        break;
                    }

                // ---- updates ----
                case "upd_pc":
                    new Thread(() => Updater.UpdateFromPhone(this)) { IsBackground = true }.Start();
                    break;
            }
        }

        // ---------------- power ----------------
        [DllImport("user32.dll")] static extern bool LockWorkStation();
        [DllImport("powrprof.dll")] static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);
        [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr hWnd, uint msg, IntPtr w, IntPtr l);

        static void Power(string a)
        {
            switch (a)
            {
                case "shutdown": Process.Start(new ProcessStartInfo("shutdown", "/s /t 0") { CreateNoWindow = true, UseShellExecute = false }); break;
                case "restart": Process.Start(new ProcessStartInfo("shutdown", "/r /t 0") { CreateNoWindow = true, UseShellExecute = false }); break;
                case "signout": Process.Start(new ProcessStartInfo("shutdown", "/l") { CreateNoWindow = true, UseShellExecute = false }); break;
                case "lock": LockWorkStation(); break;
                case "sleep": SetSuspendState(false, false, false); break;
                case "hibernate": SetSuspendState(true, false, false); break;
                case "screen_off": SendMessage((IntPtr)0xFFFF, 0x0112, (IntPtr)0xF170, (IntPtr)2); break;
                case "cancel": Process.Start(new ProcessStartInfo("shutdown", "/a") { CreateNoWindow = true, UseShellExecute = false }); break;
            }
        }

        // ---------------- screen ----------------
        void StartScreen()
        {
            screenOn = true;
            if (screenThread != null && screenThread.IsAlive) return;
            screenThread = new Thread(() =>
            {
                using (var cap = new ScreenCapture())
                {
                    while (alive && screenOn)
                    {
                        if (!frameRequests.Wait(1000)) continue;
                        if (!alive || !screenOn) break;
                        try
                        {
                            var jpg = cap.Grab(screenWidth, screenQuality);
                            io.Send(FrameIO.SCREEN, jpg, 0, jpg.Length);
                        }
                        catch (IOException) { Kill(); }
                        catch (Exception ex) { Program.Log("capture: " + ex.Message); Thread.Sleep(200); }
                    }
                }
                while (frameRequests.CurrentCount > 0) frameRequests.Wait(0);
            }) { IsBackground = true, Name = "screen" };
            screenThread.Start();
        }

        // ---------------- files ----------------
        static readonly DateTime Epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        static long Ms(DateTime t) => (long)(t.ToUniversalTime() - Epoch).TotalMilliseconds;

        void List(string path)
        {
            var items = new List<object>();
            if (string.IsNullOrEmpty(path))
            {
                void Fav(string name, Environment.SpecialFolder f)
                {
                    string p = Environment.GetFolderPath(f);
                    if (Directory.Exists(p)) items.Add(new Dictionary<string, object> { { "n", name }, { "p", p }, { "d", true }, { "k", "fav" } });
                }
                Fav("Desktop", Environment.SpecialFolder.DesktopDirectory);
                string dl = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
                if (Directory.Exists(dl)) items.Add(new Dictionary<string, object> { { "n", "Downloads" }, { "p", dl }, { "d", true }, { "k", "fav" } });
                Fav("Documents", Environment.SpecialFolder.MyDocuments);
                Fav("Pictures", Environment.SpecialFolder.MyPictures);
                Fav("Videos", Environment.SpecialFolder.MyVideos);
                Fav("Music", Environment.SpecialFolder.MyMusic);
                foreach (var d in DriveInfo.GetDrives())
                {
                    if (d.DriveType == DriveType.Network || d.DriveType == DriveType.CDRom || d.DriveType == DriveType.NoRootDirectory) continue;
                    if (!d.IsReady) continue;
                    string label = "";
                    try { label = d.VolumeLabel; } catch { }
                    items.Add(new Dictionary<string, object> {
                        { "n", (string.IsNullOrEmpty(label) ? "Local Disk" : label) + " (" + d.Name.TrimEnd('\\') + ")" },
                        { "p", d.RootDirectory.FullName }, { "d", true }, { "k", "drive" },
                        { "s", d.TotalSize }, { "free", d.AvailableFreeSpace } });
                }
                SendSafe(M("ls_res", "path", "", "items", items));
                return;
            }

            var di = new DirectoryInfo(path);
            foreach (var d in Safe(() => di.GetDirectories()))
            {
                if ((d.Attributes & (FileAttributes.Hidden | FileAttributes.System)) != 0) continue;
                items.Add(new Dictionary<string, object> { { "n", d.Name }, { "p", d.FullName }, { "d", true }, { "m", Ms(d.LastWriteTime) } });
            }
            foreach (var f in Safe(() => di.GetFiles()))
            {
                if ((f.Attributes & (FileAttributes.Hidden | FileAttributes.System)) != 0) continue;
                items.Add(new Dictionary<string, object> { { "n", f.Name }, { "p", f.FullName }, { "d", false }, { "s", f.Length }, { "m", Ms(f.LastWriteTime) } });
            }
            SendSafe(M("ls_res", "path", di.FullName, "parent", di.Parent?.FullName ?? "", "items", items));
        }

        static T[] Safe<T>(Func<T[]> f)
        {
            try { return f(); } catch { return new T[0]; }
        }

        static List<string> Paths(Dictionary<string, object> m) => m.A("paths").Select(x => Convert.ToString(x)).ToList();

        void FileOp(string op, Dictionary<string, object> m, Action a)
        {
            var th = new Thread(() =>
            {
                try { a(); SendSafe(M("op_done", "op", op, "ok", true)); }
                catch (Exception ex) { SendSafe(M("op_done", "op", op, "ok", false, "err", ex.Message)); }
            }) { IsBackground = true };
            th.SetApartmentState(ApartmentState.STA);
            th.Start();
        }

        static void Delete(string p)
        {
            if (Directory.Exists(p)) FileSystem.DeleteDirectory(p, UIOption.OnlyErrorDialogs, RecycleOption.SendToRecycleBin);
            else FileSystem.DeleteFile(p, UIOption.OnlyErrorDialogs, RecycleOption.SendToRecycleBin);
        }

        static void Rename(string p, string name)
        {
            if (string.IsNullOrWhiteSpace(name) || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) throw new Exception("That name isn't allowed.");
            if (Directory.Exists(p)) FileSystem.RenameDirectory(p, name);
            else FileSystem.RenameFile(p, name);
        }

        static void MoveOrCopy(string p, string destDir, bool move)
        {
            string target = Path.Combine(destDir, Path.GetFileName(p.TrimEnd('\\')));
            if (string.Equals(Path.GetFullPath(p), Path.GetFullPath(target), StringComparison.OrdinalIgnoreCase))
            {
                if (move) return;
                target = Unique(destDir, Path.GetFileName(p));
            }
            if (Directory.Exists(p))
            {
                if (move) FileSystem.MoveDirectory(p, target, UIOption.OnlyErrorDialogs);
                else FileSystem.CopyDirectory(p, target, UIOption.OnlyErrorDialogs);
            }
            else
            {
                if (move) FileSystem.MoveFile(p, target, UIOption.OnlyErrorDialogs);
                else FileSystem.CopyFile(p, target, UIOption.OnlyErrorDialogs);
            }
        }

        static string Unique(string dir, string name)
        {
            string path = Path.Combine(dir, name);
            if (!File.Exists(path) && !Directory.Exists(path)) return path;
            string bn = Path.GetFileNameWithoutExtension(name), ext = Path.GetExtension(name);
            for (int i = 2; ; i++)
            {
                path = Path.Combine(dir, bn + " (" + i + ")" + ext);
                if (!File.Exists(path) && !Directory.Exists(path)) return path;
            }
        }

        void Download(string path, int id)
        {
            new Thread(() =>
            {
                try
                {
                    using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                    {
                        SendSafe(M("file_start", "id", id, "name", Path.GetFileName(path), "size", fs.Length));
                        var buf = new byte[Transport == "wifi" ? 256 * 1024 : 16 * 1024];
                        int n;
                        while (alive && (n = fs.Read(buf, 0, buf.Length)) > 0)
                        {
                            lock (cancelled) if (cancelled.Remove(id)) { SendSafe(M("file_cancelled", "id", id)); return; }
                            io.SendData(id, buf, n);
                        }
                    }
                    SendSafe(M("file_end", "id", id));
                }
                catch (Exception ex) { SendSafe(M("file_error", "id", id, "err", ex.Message)); }
            }) { IsBackground = true, Name = "download" }.Start();
        }

        void UploadStart(Dictionary<string, object> m)
        {
            int id = m.I("id");
            string dir = m.S("dir");
            if (string.IsNullOrEmpty(dir) || !Directory.Exists(dir)) dir = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            string name = Path.GetFileName(m.S("name", "file"));
            foreach (char c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
            string path = Unique(dir, name);
            var fs = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, 256 * 1024);
            lock (uploads) { uploads[id] = fs; uploadPaths[id] = path; }
            SendSafe(M("put_ready", "id", id, "path", path));
        }

        void UploadEnd(int id)
        {
            FileStream fs; string path;
            lock (uploads)
            {
                uploads.TryGetValue(id, out fs);
                uploadPaths.TryGetValue(id, out path);
                uploads.Remove(id); uploadPaths.Remove(id);
            }
            fs?.Dispose();
            SendSafe(M("put_done", "id", id, "path", path ?? ""));
            if (path != null) Program.Tray.Notify("File received", Path.GetFileName(path) + " was saved to " + Path.GetDirectoryName(path));
        }

        void CancelUpload(int id)
        {
            FileStream fs; string path;
            lock (uploads)
            {
                if (!uploads.TryGetValue(id, out fs)) return;
                uploadPaths.TryGetValue(id, out path);
                uploads.Remove(id); uploadPaths.Remove(id);
            }
            try { fs.Dispose(); File.Delete(path); } catch { }
        }
    }
}
