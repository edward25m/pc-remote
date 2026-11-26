using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Windows.Forms;

namespace PCRemote
{
    static class Updater
    {
        sealed class Release { public string Version; public string Url; public long Size; }

        static Release Latest()
        {
            using (var wc = new WebClient())
            {
                wc.Headers[HttpRequestHeader.UserAgent] = "PCRemote/" + Program.Version;
                wc.Headers[HttpRequestHeader.Accept] = "application/vnd.github+json";
                wc.Encoding = Encoding.UTF8;
                var json = Json.Parse(wc.DownloadString($"https://api.github.com/repos/{Program.RepoOwner}/{Program.RepoName}/releases/latest"));
                var r = new Release { Version = json.S("tag_name").TrimStart('v') };
                foreach (var a in json.A("assets"))
                {
                    var d = a as Dictionary<string, object>;
                    if (d != null && d.S("name").Equals("PCRemote.exe", StringComparison.OrdinalIgnoreCase))
                    {
                        r.Url = d.S("browser_download_url");
                        r.Size = d.L("size");
                    }
                }
                return r;
            }
        }

        static bool IsNewer(string v)
        {
            Version a, b;
            return System.Version.TryParse(v, out a) && System.Version.TryParse(Program.Version, out b) && a > b;
        }

        static string Mb(long bytes) => (bytes / 1048576.0).ToString("0.0") + " MB";

        public static void CheckInteractive()
        {
            try
            {
                var r = Latest();
                if (r.Url == null || !IsNewer(r.Version))
                {
                    MessageBox.Show("You have the latest version (" + Program.Version + ").", "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                if (MessageBox.Show("Version " + r.Version + " is available (" + Mb(r.Size) + ").\nYou have " + Program.Version + ".\n\nUpdate now? PC Remote will restart.",
                        "PC Remote update", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes)
                    Install(r);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Couldn't check for updates. Is this PC online?\n\n" + ex.Message, "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        public static void CheckBackground()
        {
            try
            {
                var r = Latest();
                if (r.Url == null || !IsNewer(r.Version)) return;
                Program.Tray.Notify("PC Remote update available", "Version " + r.Version + " (" + Mb(r.Size) + "). Click here to install.",
                    () => new System.Threading.Thread(() => CheckInteractive()) { IsBackground = true }.Start());
            }
            catch { }
        }

        public static void UpdateFromPhone(Session s)
        {
            Dictionary<string, object> St(string state, string msg) => new Dictionary<string, object> { { "t", "upd_status" }, { "state", state }, { "msg", msg } };
            try
            {
                s.SendSafe(St("checking", "Checking for a PC update…"));
                var r = Latest();
                if (r.Url == null || !IsNewer(r.Version))
                {
                    s.SendSafe(St("uptodate", "The PC already has the latest version (" + Program.Version + ")."));
                    return;
                }
                s.SendSafe(St("downloading", "The PC is downloading version " + r.Version + " (" + Mb(r.Size) + ")…"));
                string tmp = Download(r);
                s.SendSafe(St("restarting", "Installing. PC Remote on the PC will restart; reconnect in a few seconds."));
                System.Threading.Thread.Sleep(500);
                Swap(tmp);
            }
            catch (Exception ex)
            {
                s.SendSafe(St("error", "The PC couldn't update: " + ex.Message));
            }
        }

        static string Download(Release r)
        {
            string tmp = Path.Combine(Path.GetTempPath(), "PCRemote_update.exe");
            using (var wc = new WebClient())
            {
                wc.Headers[HttpRequestHeader.UserAgent] = "PCRemote/" + Program.Version;
                wc.DownloadFile(r.Url, tmp);
            }
            if (new FileInfo(tmp).Length < 10000) throw new Exception("download was incomplete");
            return tmp;
        }

        static void Install(Release r)
        {
            try
            {
                Program.Tray.Notify("Updating PC Remote", "Downloading version " + r.Version + "…");
                Swap(Download(r));
            }
            catch (Exception ex)
            {
                MessageBox.Show("The update failed:\n" + ex.Message, "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        /// Replaces the installed exe with the new one after this process exits, then starts it again.
        static void Swap(string newExe)
        {
            string target = Program.InstalledExe;
            if (!File.Exists(target)) target = System.Reflection.Assembly.GetExecutingAssembly().Location;
            string cmd = Path.Combine(Path.GetTempPath(), "PCRemote_update.cmd");
            File.WriteAllText(cmd,
                "@echo off\r\n" +
                "ping 127.0.0.1 -n 3 >nul\r\n" +
                ":retry\r\n" +
                "copy /y \"" + newExe + "\" \"" + target + "\" >nul 2>&1\r\n" +
                "if errorlevel 1 (ping 127.0.0.1 -n 2 >nul & goto retry)\r\n" +
                "start \"\" \"" + target + "\" --updated\r\n" +
                "del \"" + newExe + "\"\r\n" +
                "del \"%~f0\"\r\n");
            Process.Start(new ProcessStartInfo("cmd.exe", "/c \"" + cmd + "\"") { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
            Environment.Exit(0);
        }
    }
}
