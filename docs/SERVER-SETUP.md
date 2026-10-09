# Server setup

UnisonDroid syncs with a computer by connecting over SSH to a `unison` process
on that computer. By default no server daemon is needed: for each sync the app
opens an SSH connection and starts `unison -server` on the far side
automatically — the same way the desktop Unison client works. Everything below
runs on the computer you want to sync with (the "server"), not on the phone.

Alternatively, a profile can be set to connect to a long-running
`unison -socket` daemon; that mode is described at the end.

## 1. Install Unison and an SSH server

Install `unison` and an OpenSSH server with your distribution's package
manager. The `unison` version must match the version embedded in the
UnisonDroid build (`dev` in development builds, or the pinned release version
shown on the About screen).

```sh
# Debian / Ubuntu
sudo apt update && sudo apt install -y unison openssh-server

# Fedora
sudo dnf install -y unison openssh-server

# Arch
sudo pacman -S unison openssh

unison -version
```

Make sure the SSH server is running and `unison` is on the login account's
`PATH` (the app runs `unison -server` over SSH).

## 2. Authorize the phone's public key

UnisonDroid authenticates with an OpenSSH key. Create one on the **Keys**
screen in the app (`Generate`), then copy its public line. It looks like:

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA... laptop
```

On the server, append that line to the account's `authorized_keys`:

```sh
mkdir -p ~/.ssh
chmod 700 ~/.ssh
echo 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA... laptop' >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

You can also `Import` an existing OpenSSH private key into the app instead of
generating a new one; only the matching **public** key has to be on the server.

Test the login from another machine before pointing the app at it:

```sh
ssh -p 22 user@server.example.com 'echo ok'
```

## 3. Sync

In UnisonDroid, create a profile with the server host, user, SSH port, and the
remote root directory you want to sync. Leave the connection type on
**SSH (auto-start)** — that is all you need. The first sync will ask you to
approve the server's SSH host key fingerprint.

## Optional: connect to a `unison -socket` daemon

If you would rather run a persistent server (for example to avoid a process
per sync), start it on the port UnisonDroid uses by default (`22333`):

```sh
unison -socket 22333
```

In the app's profile, switch the connection type to **Socket server** and set
the **Socket port** to match. The socket only accepts connections that
authenticate over SSH, so keep it on localhost or behind your normal SSH
access controls.

To keep it running, create `~/.config/systemd/user/unison-socket.service`:

```ini
[Unit]
Description=Unison socket server for UnisonDroid
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/unison -socket 22333
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

Adjust `ExecStart` if `unison` lives somewhere other than `/usr/bin/unison`
(check with `command -v unison`). Enable and start it:

```sh
systemctl --user daemon-reload
systemctl --user enable --now unison-socket.service
systemctl --user status unison-socket.service
```

To keep the user service running after you log out, enable lingering for your
account:

```sh
sudo loginctl enable-linger "$USER"
```

## Locking

Android's SELinux policy denies `link(2)` on app data, and Unison's default
Unix archive lock is implemented with a hard link. The bundled binary is
therefore built using Unison's `O_EXCL` lock branch instead (safe here —
Android storage is not NFS), so archive locks work normally: if another Unison
client is already syncing the same server roots, UnisonDroid stops rather than
risk corrupting the archives, and the server side keeps its usual hard-link
lock.

The tradeoff of an `O_EXCL` lock is that a sync killed mid-run can leave the
lock file behind, and the next run refuses until it is removed. UnisonDroid
clears stale lock files from its own private archive directory before each run,
so this is handled automatically.

