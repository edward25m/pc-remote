using System;
using System.Drawing;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace PCRemote
{
    sealed class TrayApp : ApplicationContext
    {
        readonly NotifyIcon icon;
        readonly HiddenForm ui;
        readonly ToolStripMenuItem status, allowedMenu, autostart;
        string lastClipSet = null;
        Action balloonClick;

        public TrayApp(bool firstRun, bool updated)
        {
            ui = new HiddenForm();
            ui.ClipboardChanged += OnClipboard;
            var _ = ui.Handle; // create the window now so Invoke works from other threads

            var menu = new ContextMenuStrip();
            var title = new ToolStripMenuItem("PC Remote " + Program.Version) { Enabled = false };
            status = new ToolStripMenuItem("No phone connected") { Enabled = false };
            var rename = new ToolStripMenuItem("Rename this PC…", null, (s, e) => RenamePc());
            allowedMenu = new ToolStripMenuItem("Allowed phones");
            autostart = new ToolStripMenuItem("Start with Windows", null, (s, e) =>
            {
                try { Autostart.Set(!Autostart.IsOn()); } catch { }
                autostart.Checked = Autostart.IsOn();
            }) { Checked = Autostart.IsOn() };
            var update = new ToolStripMenuItem("Check for updates", null, (s, e) => new Thread(() => Updater.CheckInteractive()) { IsBackground = true }.Start());
            var help = new ToolStripMenuItem("How to connect…", null, (s, e) => ShowHelp());
            var admin = new ToolStripMenuItem("Admin mode (control admin windows && UAC prompts)…", null, (s, e) => Admin.Toggle())
            { Checked = Admin.IsElevated() };
            var fix = new ToolStripMenuItem("Fix connection problems", null, (s, e) => new Thread(() =>
            {
                Firewall.Ensure();
                MessageBox.Show("Done. PC Remote is now allowed through the Windows firewall on all networks (home Wi-Fi and phone hotspot).\n\n" +
                    "If the phone still can't connect, make sure both are on the same Wi-Fi, and that the router doesn't have \"AP isolation\" / \"client isolation\" turned on.",
                    "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }) { IsBackground = true }.Start());
            var folder = new ToolStripMenuItem("Open log folder", null, (s, e) => { try { System.Diagnostics.Process.Start(Config.Dir); } catch { } });
            var exit = new ToolStripMenuItem("Exit", null, (s, e) => { icon.Visible = false; Environment.Exit(0); });
            menu.Items.AddRange(new ToolStripItem[] { title, status, new ToolStripSeparator(), rename, allowedMenu, autostart, admin, update, fix, help, folder, new ToolStripSeparator(), exit });
            menu.Opening += (s, e) => { RefreshAllowed(); autostart.Checked = Autostart.IsOn(); };

            icon = new NotifyIcon { Icon = Program.AppIcon(), Text = "PC Remote", ContextMenuStrip = menu, Visible = true };
            icon.DoubleClick += (s, e) => ShowHelp();
            icon.BalloonTipClicked += (s, e) => { var a = balloonClick; balloonClick = null; a?.Invoke(); };

            Server.Changed += () => Ui(UpdateStatus);
            Server.Start();

            if (firstRun) Notify("PC Remote is running", "It lives here in the tray and starts with Windows. Open the PC Remote app on your phone to connect.");
            if (updated) Notify("PC Remote updated", "You're now on version " + Program.Version + ".");

            var timer = new System.Windows.Forms.Timer { Interval = 6 * 60 * 60 * 1000 };
            timer.Tick += (s, e) => new Thread(() => Updater.CheckBackground()) { IsBackground = true }.Start();
            timer.Start();
            new Thread(() => { Thread.Sleep(60000); Updater.CheckBackground(); }) { IsBackground = true }.Start();
        }

        void Ui(Action a)
        {
            try { if (ui.InvokeRequired) ui.BeginInvoke(a); else a(); } catch { }
        }

        T UiWait<T>(Func<T> f) => ui.InvokeRequired ? (T)ui.Invoke(f) : f();

        void UpdateStatus()
        {
            var list = Server.All().Where(s => s.Authorized).ToArray();
            if (list.Length == 0) { status.Text = "No phone connected"; icon.Text = "PC Remote"; return; }
            string t = string.Join(", ", list.Select(s => s.DeviceName + (s.Transport == "bluetooth" ? " (Bluetooth)" : " (Wi-Fi)")));
            status.Text = "Connected: " + t;
            icon.Text = ("PC Remote - " + t).Length > 63 ? "PC Remote - connected" : "PC Remote - " + t;
        }

        void RefreshAllowed()
        {
            allowedMenu.DropDownItems.Clear();
            if (Config.Data.Allowed.Count == 0)
            {
                allowedMenu.DropDownItems.Add(new ToolStripMenuItem("(none yet)") { Enabled = false });
                return;
            }
            foreach (var kv in Config.Data.Allowed.ToList())
            {
                var item = new ToolStripMenuItem(kv.Value);
                item.DropDownItems.Add("Remove (it will have to ask again)", null, (s, e) =>
                {
                    Config.Data.Allowed.Remove(kv.Key);
                    Config.Save();
                });
                allowedMenu.DropDownItems.Add(item);
            }
        }

        void RenamePc()
        {
            string n = Microsoft.VisualBasic.Interaction.InputBox("Name shown on your phone:", "Rename this PC", Config.Data.Name);
            if (!string.IsNullOrWhiteSpace(n)) { Config.Data.Name = n.Trim(); Config.Save(); }
        }

        void ShowHelp()
        {
            MessageBox.Show(
                "PC Remote " + Program.Version + " is running.\n\n" +
                "Wi-Fi or hotspot: put the phone and this PC on the same Wi-Fi (or connect this PC to your phone's hotspot), then open the PC Remote app. It finds this PC by itself.\n\n" +
                "Bluetooth: first pair the phone with this PC once in Windows Settings > Bluetooth & devices > Add device. Then in the app tap Switch PC and pick this PC under Bluetooth.\n\n" +
                "The first time a new phone connects, this PC asks you to allow it.\n\n" +
                "This PC: " + Config.Data.Name,
                "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        public void Notify(string title, string text, Action onClick = null)
        {
            Ui(() =>
            {
                balloonClick = onClick;
                icon.ShowBalloonTip(5000, title, text, ToolTipIcon.Info);
            });
        }

        public void Connected(string name, string transport) { } // no pop-up: the phone app shows the connection status

        public bool AskAllow(string name, string transport)
        {
            return UiWait(() =>
            {
                using (var f = new AllowForm(name, transport))
                    return f.ShowDialog() == DialogResult.Yes;
            });
        }

        public void SetClipboard(string text)
        {
            if (text == null) return;
            Ui(() =>
            {
                for (int i = 0; i < 5; i++)
                {
                    try
                    {
                        lastClipSet = text;
                        if (text.Length == 0) Clipboard.Clear(); else Clipboard.SetText(text);
                        return;
                    }
                    catch { Thread.Sleep(50); }
                }
            });
        }

        void OnClipboard()
        {
            try
            {
                if (!Clipboard.ContainsText()) return;
                string t = Clipboard.GetText();
                if (t == lastClipSet) return;
                lastClipSet = t;
                if (t.Length > 200000) return;
                Server.BroadcastClipboard(t);
            }
            catch { }
        }
    }

    sealed class HiddenForm : Form
    {
        [DllImport("user32.dll")] static extern bool AddClipboardFormatListener(IntPtr hwnd);
        public event Action ClipboardChanged;

        public HiddenForm()
        {
            ShowInTaskbar = false;
            FormBorderStyle = FormBorderStyle.None;
            Opacity = 0;
            Size = new Size(1, 1);
        }

        protected override void SetVisibleCore(bool value) => base.SetVisibleCore(false);

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            AddClipboardFormatListener(Handle);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == 0x031D) ClipboardChanged?.Invoke(); // WM_CLIPBOARDUPDATE
            base.WndProc(ref m);
        }
    }

    sealed class AllowForm : Form
    {
        public AllowForm(string name, string transport)
        {
            Text = "PC Remote";
            Icon = Program.AppIcon();
            TopMost = true;
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = MinimizeBox = false;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(420, 170);
            BackColor = Color.FromArgb(15, 23, 42);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 10f);

            var title = new Label
            {
                Text = "Allow this phone?",
                Font = new Font("Segoe UI Semibold", 14f),
                AutoSize = true,
                Location = new Point(20, 16)
            };
            var body = new Label
            {
                Text = "\"" + name + "\" wants to control this PC over " + (transport == "bluetooth" ? "Bluetooth" : "Wi-Fi") +
                       ".\nIt will be remembered, so you only need to allow it once.",
                AutoSize = false,
                Size = new Size(380, 50),
                Location = new Point(20, 54),
                ForeColor = Color.FromArgb(203, 213, 225)
            };
            var allow = new Button
            {
                Text = "Allow", DialogResult = DialogResult.Yes, Size = new Size(110, 36), Location = new Point(290, 116),
                BackColor = Color.FromArgb(37, 99, 235), FlatStyle = FlatStyle.Flat, ForeColor = Color.White
            };
            allow.FlatAppearance.BorderSize = 0;
            var deny = new Button
            {
                Text = "Don't allow", DialogResult = DialogResult.No, Size = new Size(110, 36), Location = new Point(170, 116),
                BackColor = Color.FromArgb(51, 65, 85), FlatStyle = FlatStyle.Flat, ForeColor = Color.White
            };
            deny.FlatAppearance.BorderSize = 0;
            Controls.AddRange(new Control[] { title, body, allow, deny });
            AcceptButton = allow;
            CancelButton = deny;
            Shown += (s, e) => { Activate(); allow.Focus(); System.Media.SystemSounds.Asterisk.Play(); };
        }
    }
}
