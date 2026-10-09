# Server setup

UnisonDroid syncs with a computer by connecting over SSH to a `unison` server
process listening on a TCP socket. Everything below runs on the computer you
want to sync with (the "server"), not on the phone.

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

Make sure the SSH server is running and reachable from the phone's network.

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

## 3. Run the Unison socket server

Start the socket on the port UnisonDroid uses by default (`22333`):

```sh
unison -socket 22333
```

The socket only accepts connections that authenticate over SSH, so keep it on
localhost or behind your normal SSH access controls. In the app's profile,
set the **Socket port** to match.

## 4. Keep it running with a systemd user unit

Create `~/.config/systemd/user/unison-socket.service`:

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

## 5. Sync

In UnisonDroid, create a profile with the server host, user, SSH port, the
remote root directory you want to sync, and the socket port from step 3. The
first sync will ask you to approve the server's SSH host key fingerprint.

## Limitations

UnisonDroid v1 disables Unison's archive locks (`ignorelocks = true`) on both
replicas. Android's SELinux policy denies `link(2)` on app data, and Unison's
lock is implemented with a hard link, so the lock cannot be taken on-device at
all. The app serializes its own syncs, but it **cannot** detect or prevent
another client (a desktop `unison` process, or another UnisonDroid profile)
from syncing the same server roots concurrently. Running more than one client
against the same roots at the same time can corrupt the archives; do not do it
in v1.
