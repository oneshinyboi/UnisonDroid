package io.unisondroid.app.sync

import java.io.File

/**
 * Renders the OpenSSH client config passed to the bundled ssh via `-F`. Keeping
 * every option in one file (rather than a long hand-assembled `sshargs` line)
 * makes the transport settings self-contained and independently testable.
 */
object SshConfig {

    fun render(keyFile: File, knownHosts: File, port: Int): String {
        val sb = StringBuilder()
        sb.append("Host *\n")
        sb.append("  IdentityFile ").append(keyFile.absolutePath).append('\n')
        sb.append("  UserKnownHostsFile ").append(knownHosts.absolutePath).append('\n')
        sb.append("  GlobalKnownHostsFile /dev/null\n")
        sb.append("  StrictHostKeyChecking yes\n")
        sb.append("  BatchMode yes\n")
        sb.append("  IdentitiesOnly yes\n")
        sb.append("  LogLevel ERROR\n")
        sb.append("  Port ").append(port).append('\n')
        return sb.toString()
    }
}
